#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
pc_pano.py -- 双鱼眼 360 相机 PC 端验证工具

功能：
  1. probe  枚举摄像头，确认 Insta360 Air 的索引/分辨率/帧率
  2. photo  用一张双鱼眼静帧验证去畸变 + 球面拼接
  3. capture  录制 UVC 原始双鱼眼视频
  4. process  视频 + 手机 IMU CSV -> 陀螺仪防抖对比视频

依赖：opencv-python, numpy
"""

import argparse
import csv
import math
import os
import sys
import time

import cv2
import numpy as np


# ---------------------------------------------------------------------------
# 基础旋转 / 四元数
# ---------------------------------------------------------------------------
def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]], dtype=np.float64)


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], dtype=np.float64)


def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]], dtype=np.float64)


def euler_matrix(yaw, pitch, roll):
    return rot_z(yaw) @ rot_y(pitch) @ rot_x(roll)


def quat_mul(a, b):
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return np.array([
        aw * bw - ax * bx - ay * by - az * bz,
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
    ], dtype=np.float64)


def quat_normalize(q):
    n = np.linalg.norm(q)
    return q / n if n > 1e-12 else np.array([1.0, 0.0, 0.0, 0.0])


def quat_integrate(q, w, dt):
    theta = float(np.linalg.norm(w)) * dt
    if theta < 1e-12:
        return q
    axis = w / max(np.linalg.norm(w), 1e-12)
    dq = np.array([math.cos(theta / 2.0), *(axis * math.sin(theta / 2.0))])
    return quat_normalize(quat_mul(q, dq))


def quat_to_rot(q):
    w, x, y, z = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
        [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)],
    ], dtype=np.float64)


def quat_slerp(q1, q2, t):
    q1 = quat_normalize(q1)
    q2 = quat_normalize(q2)
    d = float(np.dot(q1, q2))
    if d < 0.0:
        q2 = -q2
        d = -d
    if d > 0.9995:
        return quat_normalize(q1 + t * (q2 - q1))
    theta = math.acos(max(-1.0, min(1.0, d)))
    sin_t = math.sin(theta)
    return quat_normalize((math.sin((1 - t) * theta) / sin_t) * q1 + (math.sin(t * theta) / sin_t) * q2)


# ---------------------------------------------------------------------------
# Mahony 姿态滤波（陀螺 + 加速度重力修正）
# ---------------------------------------------------------------------------
class MahonyFilter:
    def __init__(self, kp=0.6, ki=0.02):
        self.kp = kp
        self.ki = ki
        self.q = np.array([1.0, 0.0, 0.0, 0.0])
        self.bias = np.zeros(3)
        self.integral = np.zeros(3)

    def update(self, gyro, accel, dt):
        g = np.asarray(gyro, dtype=np.float64) - self.bias
        an = np.asarray(accel, dtype=np.float64)
        n = np.linalg.norm(an)
        if n > 1e-6:
            an = an / n
            v = quat_to_rot(self.q).T @ np.array([0.0, 0.0, 1.0])
            err = np.cross(an, v)
            self.integral += err * dt
            g = g + self.kp * err + self.ki * self.integral
        self.q = quat_integrate(self.q, g, dt)
        return self.q


# ---------------------------------------------------------------------------
# 双鱼眼 -> 球面采样
# ---------------------------------------------------------------------------
class DualFisheye:
    def __init__(self, frame_w, frame_h, radius_ratio=0.48, center_ratio=0.5,
                 fov_deg=190.0, blend_deg=10.0, rotate_r=0.0, flip_r=False):
        self.fw = frame_w
        self.fh = frame_h
        self.lw = frame_w // 2
        self.lh = frame_h
        self.cx = self.lw * center_ratio
        self.cy = self.lh * center_ratio
        self.radius = min(self.lw, self.lh) * radius_ratio
        self.theta_max = math.radians(fov_deg) / 2.0
        self.blend = max(1e-6, math.radians(blend_deg))
        self.rotate_r = math.radians(rotate_r)
        self.flip_r = flip_r

    def split(self, frame):
        return frame[:, :self.lw], frame[:, self.lw:]

    def _project(self, rays, sign, rotate, flip):
        z = np.clip(rays[..., 2] * sign, -1.0, 1.0)
        theta = np.arccos(z)
        phi = np.arctan2(rays[..., 1], rays[..., 0])
        if rotate:
            phi = phi + rotate
        if flip:
            phi = -phi
        r = self.radius * theta / self.theta_max
        u = self.cx + r * np.cos(phi)
        v = self.cy + r * np.sin(phi)
        return u.astype(np.float32), v.astype(np.float32)

    def sample(self, frame, rays):
        left, right = self.split(frame)
        ul, vl = self._project(rays, +1.0, 0.0, False)
        ur, vr = self._project(rays, -1.0, self.rotate_r, self.flip_r)
        ml = cv2.remap(left, ul, vl, interpolation=cv2.INTER_LINEAR,
                       borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
        mr = cv2.remap(right, ur, vr, interpolation=cv2.INTER_LINEAR,
                       borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
        z = rays[..., 2]
        wl = np.clip((z + self.blend) / (2.0 * self.blend), 0.0, 1.0)[..., None]
        out = ml.astype(np.float32) * wl + mr.astype(np.float32) * (1.0 - wl)
        return np.clip(out, 0, 255).astype(np.uint8)


def view_rays(w, h, fov_deg):
    fx = math.tan(math.radians(fov_deg) / 2.0)
    xs = (np.arange(w, dtype=np.float32) - (w - 1) / 2.0) / ((w - 1) / 2.0) * fx
    ys = -(np.arange(h, dtype=np.float32) - (h - 1) / 2.0) / ((h - 1) / 2.0) * fx
    X, Y = np.meshgrid(xs, ys)
    d = np.stack([X, Y, np.ones_like(X)], axis=-1)
    d /= np.linalg.norm(d, axis=-1, keepdims=True)
    return d.astype(np.float64)


def render_perspective(frame, lens, R_wc, out_w, out_h, fov_deg, yaw_deg=0.0, pitch_deg=0.0):
    d = view_rays(out_w, out_h, fov_deg)
    Rv = euler_matrix(math.radians(yaw_deg), math.radians(pitch_deg), 0.0)
    d_world = np.einsum('ij,hwj->hwi', Rv, d)
    d_cam = np.einsum('ij,hwj->hwi', R_wc, d_world)
    return lens.sample(frame, d_cam)


def render_equirect(frame, lens, R_wc, out_w, out_h):
    lon = np.linspace(-math.pi, math.pi, out_w, endpoint=False, dtype=np.float64)
    lat = np.linspace(math.pi / 2.0, -math.pi / 2.0, out_h, endpoint=True, dtype=np.float64)
    LON, LAT = np.meshgrid(lon, lat)
    d = np.stack([
        np.cos(LAT) * np.sin(LON),
        np.sin(LAT),
        np.cos(LAT) * np.cos(LON),
    ], axis=-1)
    d_cam = np.einsum('ij,hwj->hwi', R_wc, d)
    return lens.sample(frame, d_cam)


# ---------------------------------------------------------------------------
# IMU CSV
# ---------------------------------------------------------------------------
def load_phyphox_csv(path):
    rows = []
    with open(path, 'r', encoding='utf-8-sig', newline='') as f:
        for row in csv.reader(f):
            rows.append(row)
    header = None
    for i, row in enumerate(rows[:30]):
        low = [c.strip().lower() for c in row]
        if any('time' in c for c in low) and any(('gyro' in c or 'accel' in c) for c in low):
            header = i
            break
    if header is None:
        raise RuntimeError('找不到 phyphox 表头（需要 Time + Gyroscope/Acceleration）')
    cols = [c.strip() for c in rows[header]]
    ti = next(i for i, c in enumerate(cols) if 'time' in c.lower())
    gi = [i for i, c in enumerate(cols) if 'gyro' in c.lower()]
    ai = [i for i, c in enumerate(cols) if 'accel' in c.lower()]
    if len(gi) < 3:
        raise RuntimeError('CSV 里没有三轴陀螺仪')
    t, gyro, accel = [], [], []
    for row in rows[header + 1:]:
        if len(row) <= max([ti] + gi + ai):
            continue
        try:
            t.append(float(row[ti]))
            gyro.append([float(row[i]) for i in gi[:3]])
            if len(ai) >= 3:
                accel.append([float(row[i]) for i in ai[:3]])
            else:
                accel.append([0.0, 0.0, 0.0])
        except ValueError:
            continue
    if not t:
        raise RuntimeError('CSV 没有有效数据')
    t = np.asarray(t, dtype=np.float64)
    return t - t[0], np.asarray(gyro, dtype=np.float64), np.asarray(accel, dtype=np.float64)


def build_orientation(t, gyro, accel, kp=0.6, ki=0.02, tau=0.25, bias_s=0.5):
    if len(t) < 2:
        raise RuntimeError('IMU 数据太少')
    filt = MahonyFilter(kp=kp, ki=ki)
    n0 = max(1, int(np.searchsorted(t, bias_s)))
    filt.bias = np.mean(gyro[:n0], axis=0)
    qs = []
    q_smooth = np.array([1.0, 0.0, 0.0, 0.0])
    smooth = []
    last_q = filt.q
    for i in range(len(t)):
        dt = t[i] - t[i - 1] if i else 1.0 / 100.0
        if dt <= 0 or dt > 0.5:
            dt = 1.0 / 100.0
        q = filt.update(gyro[i], accel[i], dt)
        last_q = q
        if i == 0:
            q_smooth = q
        else:
            alpha = 1.0 - math.exp(-dt / max(tau, 1e-3))
            q_smooth = quat_slerp(q_smooth, q, alpha)
        qs.append(q.copy())
        smooth.append(q_smooth.copy())
    return np.asarray(qs), np.asarray(smooth)


def quat_at(t, qs, target):
    if target <= t[0]:
        return qs[0]
    if target >= t[-1]:
        return qs[-1]
    i = int(np.searchsorted(t, target))
    if i <= 0:
        return qs[0]
    dt = t[i] - t[i - 1]
    a = (target - t[i - 1]) / dt if dt > 1e-9 else 0.0
    return quat_slerp(qs[i - 1], qs[i], a)


# ---------------------------------------------------------------------------
# 视频运动 / 同步
# ---------------------------------------------------------------------------
def video_motion(path):
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        raise RuntimeError('打不开视频: ' + path)
    prev = None
    out = []
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        g = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        g = cv2.resize(g, (320, 160), interpolation=cv2.INTER_AREA).astype(np.float32)
        if prev is not None:
            try:
                (dx, dy), resp = cv2.phaseCorrelate(prev, g)
                out.append(math.hypot(dx, dy) * max(resp, 0.05))
            except cv2.error:
                out.append(0.0)
        prev = g
    cap.release()
    return np.asarray(out, dtype=np.float64)


def estimate_offset(vm, fps, imu_t, imu_mag, max_offset=8.0):
    if len(vm) < 10 or len(imu_t) < 10:
        return 0.0, 0.0
    t_grid = np.arange(len(vm)) / fps
    im = np.interp(t_grid, imu_t, imu_mag)
    a = vm - vm.mean()
    b = im - im.mean()
    a /= max(np.linalg.norm(a), 1e-9)
    b /= max(np.linalg.norm(b), 1e-9)
    max_lag = int(max_offset * fps)
    best_lag, best_c = 0, -1.0
    for lag in range(-max_lag, max_lag + 1):
        if lag >= 0:
            x = a[lag:]
            y = b[:len(x)]
        else:
            x = a[:len(a) + lag]
            y = b[-lag:]
        if len(x) < 10:
            continue
        c = float(np.dot(x, y) / len(x))
        if c > best_c:
            best_c, best_lag = c, lag
    return best_lag / fps, best_c


# ---------------------------------------------------------------------------
# 各子命令
# ---------------------------------------------------------------------------
def cmd_probe(args):
    for i in range(args.max_index):
        for backend, name in ((cv2.CAP_DSHOW, 'DSHOW'), (cv2.CAP_ANY, 'ANY')):
            cap = cv2.VideoCapture(i, backend)
            if cap.isOpened():
                w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
                h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
                fps = cap.get(cv2.CAP_PROP_FPS)
                print(f'index={i} backend={name} opened=True size={w}x{h} fps={fps:.1f}')
                cap.release()
                break
            cap.release()


def cmd_photo(args):
    img = cv2.imread(args.photo)
    if img is None:
        raise RuntimeError('读不到图片: ' + args.photo)
    h, w = img.shape[:2]
    print(f'photo {w}x{h}')
    lens = DualFisheye(w, h, radius_ratio=args.radius_ratio, fov_deg=args.fov,
                       blend_deg=args.blend, rotate_r=args.rotate_r, flip_r=args.flip_r)
    I = np.eye(3)
    if args.dry_run:
        eq = render_equirect(img, lens, I, 320, 160)
        pv = render_perspective(img, lens, I, 320, 180, args.view_fov, args.yaw, args.pitch)
        print('equirect', eq.shape, 'mean', round(float(eq.mean()), 2), 'std', round(float(eq.std()), 2))
        print('view    ', pv.shape, 'mean', round(float(pv.mean()), 2), 'std', round(float(pv.std()), 2))
        return
    os.makedirs(args.out_dir, exist_ok=True)
    eq = render_equirect(img, lens, I, args.width, args.height)
    pv = render_perspective(img, lens, I, args.width, int(args.width * 9 / 16), args.view_fov, args.yaw, args.pitch)
    cv2.imwrite(os.path.join(args.out_dir, 'equirect.jpg'), eq)
    cv2.imwrite(os.path.join(args.out_dir, 'view.jpg'), pv)
    print('saved', os.path.join(args.out_dir, 'equirect.jpg'))
    print('saved', os.path.join(args.out_dir, 'view.jpg'))


def cmd_capture(args):
    cap = cv2.VideoCapture(args.camera, cv2.CAP_DSHOW)
    if not cap.isOpened():
        cap = cv2.VideoCapture(args.camera)
    if not cap.isOpened():
        raise RuntimeError('打不开摄像头 index=' + str(args.camera))
    if args.width:
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, args.width)
    if args.height:
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, args.height)
    if args.fps:
        cap.set(cv2.CAP_PROP_FPS, args.fps)
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    print(f'capture {w}x{h} fps={fps:.1f} -> {args.out}')
    writer = cv2.VideoWriter(args.out, cv2.VideoWriter_fourcc(*'MJPG'), fps, (w, h))
    start = time.time()
    n = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        writer.write(frame)
        n += 1
        if not args.no_preview:
            cv2.imshow('capture (q to stop)', cv2.resize(frame, (w // 2, h // 2)))
            if cv2.waitKey(1) & 0xFF == ord('q'):
                break
        if args.seconds and time.time() - start >= args.seconds:
            break
    cap.release()
    writer.release()
    cv2.destroyAllWindows()
    print('frames', n)


def cmd_process(args):
    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        raise RuntimeError('打不开视频: ' + args.video)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    print(f'video {w}x{h} fps={fps:.2f} frames={total}')
    cap.release()

    vm = video_motion(args.video)
    print('video motion samples', len(vm))

    offset = args.offset
    corr = 0.0
    qs = qsmooth = None
    imu_t = None
    if args.imu:
        imu_t, gyro, accel = load_phyphox_csv(args.imu)
        gyro = gyro * args.gyro_scale
        qs, qsmooth = build_orientation(imu_t, gyro, accel, kp=args.kp, ki=args.ki, tau=args.tau)
        if offset is None:
            mag = np.linalg.norm(gyro, axis=1)
            offset, corr = estimate_offset(vm, fps, imu_t, mag, args.max_offset)
        else:
            offset = float(offset)
        print(f'sync offset={offset:+.3f}s correlation={corr:.3f}')
    else:
        print('no IMU: raw and stabilized will be identical')

    lens = DualFisheye(w, h, radius_ratio=args.radius_ratio, fov_deg=args.fov,
                       blend_deg=args.blend, rotate_r=args.rotate_r, flip_r=args.flip_r)
    align = euler_matrix(math.radians(args.align_yaw), math.radians(args.align_pitch), math.radians(args.align_roll))
    out_h = args.out_height
    out_w = args.out_width
    writer = None
    if not args.dry_run:
        writer = cv2.VideoWriter(args.out, cv2.VideoWriter_fourcc(*'mp4v'), fps, (out_w * 2, out_h))
    cap = cv2.VideoCapture(args.video)
    n = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if args.max_frames and n >= args.max_frames:
            break
        t = n / fps
        if qs is not None:
            q = quat_at(imu_t, qs, t + offset)
            q_s = quat_at(imu_t, qsmooth, t + offset)
            R_raw = align @ quat_to_rot(q).T
            R_stab = align @ quat_to_rot(q_s).T
        else:
            R_raw = R_stab = np.eye(3)
        raw = render_perspective(frame, lens, R_raw, out_w, out_h, args.view_fov, args.yaw, args.pitch)
        stab = render_perspective(frame, lens, R_stab, out_w, out_h, args.view_fov, args.yaw, args.pitch)
        cv2.putText(raw, 'RAW', (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 255), 2)
        cv2.putText(stab, 'STAB', (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 255, 0), 2)
        side = np.hstack([raw, stab])
        if writer is not None:
            writer.write(side)
        if args.dry_run:
            print('frame', n, 'raw mean', round(float(raw.mean()), 2), 'stab mean', round(float(stab.mean()), 2))
        n += 1
        if n % 30 == 0:
            print('processed', n)
    cap.release()
    if writer is not None:
        writer.release()
        print('saved', args.out)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def add_lens_args(p):
    p.add_argument('--fov', type=float, default=190.0, help='单镜头视场角，默认 190')
    p.add_argument('--radius-ratio', type=float, default=0.48, help='鱼眼圆半径/半宽，默认 0.48')
    p.add_argument('--blend', type=float, default=10.0, help='接缝融合角度，默认 10 度')
    p.add_argument('--rotate-r', type=float, default=0.0, help='右镜头旋转补偿角度')
    p.add_argument('--flip-r', action='store_true', help='右镜头水平镜像')
    p.add_argument('--view-fov', type=float, default=100.0, help='输出视角 FOV，默认 100')
    p.add_argument('--yaw', type=float, default=0.0, help='观察方向 yaw')
    p.add_argument('--pitch', type=float, default=0.0, help='观察方向 pitch')


def main():
    ap = argparse.ArgumentParser(description='双鱼眼 360 相机 PC 验证工具')
    sub = ap.add_subparsers(dest='cmd', required=True)

    p = sub.add_parser('probe', help='枚举摄像头')
    p.add_argument('--max-index', type=int, default=6)
    p.set_defaults(func=cmd_probe)

    p = sub.add_parser('photo', help='用静帧验证拼接')
    p.add_argument('--photo', required=True)
    p.add_argument('--out-dir', default='out')
    p.add_argument('--width', type=int, default=1920)
    p.add_argument('--height', type=int, default=960)
    p.add_argument('--dry-run', action='store_true')
    add_lens_args(p)
    p.set_defaults(func=cmd_photo)

    p = sub.add_parser('capture', help='录制 UVC 原始视频')
    p.add_argument('--camera', type=int, default=1)
    p.add_argument('--width', type=int, default=0)
    p.add_argument('--height', type=int, default=0)
    p.add_argument('--fps', type=float, default=0)
    p.add_argument('--seconds', type=float, default=0)
    p.add_argument('--no-preview', action='store_true')
    p.add_argument('--out', default='raw.avi')
    p.set_defaults(func=cmd_capture)

    p = sub.add_parser('process', help='视频 + IMU -> 防抖对比')
    p.add_argument('--video', required=True)
    p.add_argument('--imu', default='')
    p.add_argument('--out', default='stabilized.mp4')
    p.add_argument('--offset', type=float, default=None, help='手动同步偏移（秒）')
    p.add_argument('--max-offset', type=float, default=8.0)
    p.add_argument('--tau', type=float, default=0.25, help='平滑时间常数（秒）')
    p.add_argument('--kp', type=float, default=0.6)
    p.add_argument('--ki', type=float, default=0.02)
    p.add_argument('--gyro-scale', type=float, default=1.0)
    p.add_argument('--align-yaw', type=float, default=0.0)
    p.add_argument('--align-pitch', type=float, default=0.0)
    p.add_argument('--align-roll', type=float, default=0.0)
    p.add_argument('--out-width', type=int, default=960)
    p.add_argument('--out-height', type=int, default=540)
    p.add_argument('--max-frames', type=int, default=0)
    p.add_argument('--dry-run', action='store_true')
    add_lens_args(p)
    p.set_defaults(func=cmd_process)

    args = ap.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
