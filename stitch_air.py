#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
stitch_air.py -- 用 App 自带的 offset 参数做 Air 双鱼眼拼接（不含防抖）

参数来源：assets/sample/photo.insp 里的 offset 字段
  2_766.580_754.000_748.740_0.000_0.000_90.000_781.580_2259.760_753.000_-0.400_0.000_90.800_3008_1504_1035

用法：
  python stitch_air.py --photo sample.jpg --out out/equirect.jpg --view
  python stitch_air.py --video raw.avi --out out/stitched.mp4
  python stitch_air.py --photo sample.jpg --dry-run
"""

import argparse
import math
import os
import sys

import cv2
import numpy as np


DEFAULT_OFFSET = ("2_766.580_754.000_748.740_0.000_0.000_90.000_"
                  "781.580_2259.760_753.000_-0.400_0.000_90.800_3008_1504_1035")
DEFAULT_FOV = 200.0

# 默认方向：左半是后镜头（z=-1），右半是前镜头（z=+1），右镜头图像上下镜像。
P_BACK = np.array([[1, 0, 0], [0, 1, 0], [0, 0, -1]], float)
P_FRONT = np.array([[1, 0, 0], [0, -1, 0], [0, 0, 1]], float)


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]], np.float64)


def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]], np.float64)


def parse_offset(s, frame_w=None):
    """offset 串：镜头数 + 每镜头 6 个 float + 宽 + 高 + 额外版本号。"""
    t = [x for x in s.strip().split("_") if x != ""]
    n = int(t[0])
    vals = [float(x) for x in t[1:1 + 6 * n]]
    ow = int(float(t[1 + 6 * n]))
    oh = int(float(t[2 + 6 * n]))
    scale = (frame_w / float(ow)) if (frame_w and ow) else 1.0
    lenses = []
    for i in range(n):
        r, cx, cy, yaw, pitch, roll = vals[6 * i:6 * i + 6]
        r *= scale
        cx *= scale
        cy *= scale
        if i > 0:
            half = (frame_w if frame_w else ow) / 2.0
            if cx > half:
                cx -= half
        lenses.append(dict(r=r, cx=cx, cy=cy, yaw=yaw, pitch=pitch, roll=roll))
    return lenses, ow, oh


def project(im, lens, rays, P, roll_sign, fov):
    """把相机坐标射线投到单路鱼眼；P 是镜头坐标系的符号置换矩阵。"""
    d = rays @ P.T
    theta = np.arccos(np.clip(d[..., 2], -1.0, 1.0))
    phi = np.arctan2(d[..., 1], d[..., 0]) + roll_sign * math.radians(lens["roll"])
    r = lens["r"] * theta / math.radians(fov / 2.0)
    u = (lens["cx"] + r * np.cos(phi)).astype(np.float32)
    v = (lens["cy"] + r * np.sin(phi)).astype(np.float32)
    return cv2.remap(im, u, v, interpolation=cv2.INTER_LINEAR,
                     borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))


def sample(frame, lenses, rays, p):
    """双镜头采样；按 z 决定前后镜头并线性融合。"""
    half = frame.shape[1] // 2
    left = frame[:, :half]
    right = frame[:, half:]
    ml = project(left, lenses[0], rays, p["PL"], p["rollL"], p["fov"])
    mr = project(right, lenses[1], rays, p["PR"], p["rollR"], p["fov"])
    z = rays[..., 2]
    b = max(1e-6, math.radians(p["blend"]))
    if p["front_is_R"]:
        wl = np.clip((-z + b) / (2.0 * b), 0.0, 1.0)[..., None]
    else:
        wl = np.clip((z + b) / (2.0 * b), 0.0, 1.0)[..., None]
    out = ml.astype(np.float32) * wl + mr.astype(np.float32) * (1.0 - wl)
    return np.clip(out, 0, 255).astype(np.uint8)


def _candidates():
    backs = [
        np.array([[1, 0, 0], [0, 1, 0], [0, 0, -1]], float),
        np.array([[-1, 0, 0], [0, 1, 0], [0, 0, -1]], float),
        np.array([[1, 0, 0], [0, -1, 0], [0, 0, -1]], float),
        np.array([[-1, 0, 0], [0, -1, 0], [0, 0, -1]], float),
    ]
    fronts = [
        np.array([[1, 0, 0], [0, 1, 0], [0, 0, 1]], float),
        np.array([[-1, 0, 0], [0, 1, 0], [0, 0, 1]], float),
        np.array([[1, 0, 0], [0, -1, 0], [0, 0, 1]], float),
        np.array([[-1, 0, 0], [0, -1, 0], [0, 0, 1]], float),
    ]
    return backs, fronts


def auto_align(frame, lenses, blend):
    """在赤道重叠带做 NCC，自动选择前后镜头、图像翻转、roll 符号和 FOV。"""
    alphas = np.linspace(0, 2 * math.pi, 180, endpoint=False)
    zs = np.linspace(-0.05, 0.05, 5)
    A, Z = np.meshgrid(alphas, zs)
    rays = np.stack([np.cos(A), np.sin(A), Z], axis=-1)
    half = frame.shape[1] // 2
    left = frame[:, :half]
    right = frame[:, half:]
    backs, fronts = _candidates()
    best = (None, -2.0)
    for swap in (False, True):
        PLs = fronts if swap else backs
        PRs = backs if swap else fronts
        for PL in PLs:
            for PR in PRs:
                for fov in (190.0, 200.0, 210.0):
                    for sL in (1, -1):
                        ml = project(left, lenses[0], rays, PL, sL, fov).mean(axis=2).ravel()
                        a = (ml - ml.mean()) / (ml.std() + 1e-6)
                        for sR in (1, -1):
                            mr = project(right, lenses[1], rays, PR, sR, fov).mean(axis=2).ravel()
                            b = (mr - mr.mean()) / (mr.std() + 1e-6)
                            ncc = float(np.mean(a * b))
                            if ncc > best[1]:
                                best = (dict(PL=PL, PR=PR, rollL=sL, rollR=sR,
                                             fov=fov, blend=blend, front_is_R=not swap), ncc)
    p, ncc = best
    p["ncc"] = ncc
    return p


def equirect(frame, lenses, p, out_w, out_h):
    lon = np.linspace(-math.pi, math.pi, out_w, endpoint=False)
    lat = np.linspace(math.pi / 2.0, -math.pi / 2.0, out_h)
    LON, LAT = np.meshgrid(lon, lat)
    rays = np.stack([
        np.cos(LAT) * np.sin(LON),
        np.sin(LAT),
        np.cos(LAT) * np.cos(LON),
    ], axis=-1)
    return sample(frame, lenses, rays, p)


def perspective(frame, lenses, p, out_w, out_h, fov, yaw, pitch):
    fx = math.tan(math.radians(fov) / 2.0)
    xs = (np.arange(out_w) - (out_w - 1) / 2.0) / ((out_w - 1) / 2.0) * fx
    ys = -(np.arange(out_h) - (out_h - 1) / 2.0) / ((out_w - 1) / 2.0) * fx
    X, Y = np.meshgrid(xs, ys)
    d = np.stack([X, Y, np.ones_like(X)], axis=-1)
    d /= np.linalg.norm(d, axis=-1, keepdims=True)
    R = rot_z(math.radians(yaw)) @ rot_x(math.radians(pitch))
    d = np.einsum("ij,hwj->hwi", R, d)
    return sample(frame, lenses, d, p)


def default_params(blend):
    return dict(PL=P_BACK, PR=P_FRONT, rollL=1, rollR=1, fov=DEFAULT_FOV,
                blend=blend, front_is_R=True, ncc=0.0)


def resolve_params(args, frame):
    lenses, ow, oh = parse_offset(args.offset, frame.shape[1])
    p = auto_align(frame, lenses, args.blend) if args.auto_align else default_params(args.blend)
    print("offset: %dx%d, %d lenses" % (ow, oh, len(lenses)))
    for i, l in enumerate(lenses):
        print("  lens%d: r=%.1f cx=%.1f cy=%.1f roll=%.1f" %
              (i, l["r"], l["cx"], l["cy"], l["roll"]))
    print("align: fov=%.0f rollL=%+d rollR=%+d front_is_R=%s ncc=%.3f" %
          (p["fov"], p["rollL"], p["rollR"], p["front_is_R"], p["ncc"]))
    return lenses, p


def run_photo(args):
    img = cv2.imread(args.photo)
    if img is None:
        raise SystemExit("读不到图片: " + args.photo)
    lenses, p = resolve_params(args, img)
    if args.dry_run:
        eq = equirect(img, lenses, p, 320, 160)
        pv = perspective(img, lenses, p, 320, 180, args.view_fov, args.yaw, args.pitch)
        black = float((eq.max(axis=2) == 0).mean() * 100.0)
        print("equirect", eq.shape, "mean", round(float(eq.mean()), 2),
              "std", round(float(eq.std()), 2), "black%", round(black, 2))
        print("view    ", pv.shape, "mean", round(float(pv.mean()), 2), "std", round(float(pv.std()), 2))
        return
    if not args.out:
        raise SystemExit("需要 --out")
    eq = equirect(img, lenses, p, args.width, args.height)
    out_dir = os.path.dirname(os.path.abspath(args.out))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    cv2.imwrite(args.out, eq)
    print("saved", args.out)
    if args.view:
        pv = perspective(img, lenses, p, args.width, int(args.width * 9 / 16),
                         args.view_fov, args.yaw, args.pitch)
        out2 = os.path.splitext(args.out)[0] + "_view.jpg"
        cv2.imwrite(out2, pv)
        print("saved", out2)


def run_video(args):
    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        raise SystemExit("打不开视频: " + args.video)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    ok, first = cap.read()
    if not ok:
        raise SystemExit("视频没有帧")
    lenses, p = resolve_params(args, first)
    writer = None
    if not args.dry_run:
        if not args.out:
            raise SystemExit("需要 --out")
        writer = cv2.VideoWriter(args.out, cv2.VideoWriter_fourcc(*"mp4v"),
                                 fps, (args.width, args.height))
    n = 0
    frame = first
    while True:
        eq = equirect(frame, lenses, p, args.width, args.height)
        if writer is not None:
            writer.write(eq)
        n += 1
        if args.dry_run:
            print("frame", n, "mean", round(float(eq.mean()), 2))
        if args.max_frames and n >= args.max_frames:
            break
        ok, frame = cap.read()
        if not ok:
            break
    cap.release()
    if writer is not None:
        writer.release()
        print("saved", args.out, "frames", n)


def main():
    ap = argparse.ArgumentParser(description="Air 双鱼眼拼接（App offset 参数）")
    ap.add_argument("--photo")
    ap.add_argument("--video")
    ap.add_argument("--out")
    ap.add_argument("--width", type=int, default=1920)
    ap.add_argument("--height", type=int, default=960)
    ap.add_argument("--blend", type=float, default=10.0)
    ap.add_argument("--offset", default=DEFAULT_OFFSET)
    ap.add_argument("--auto-align", dest="auto_align", action="store_true", default=True)
    ap.add_argument("--no-auto-align", dest="auto_align", action="store_false")
    ap.add_argument("--view", action="store_true")
    ap.add_argument("--view-fov", type=float, default=100.0)
    ap.add_argument("--yaw", type=float, default=0.0)
    ap.add_argument("--pitch", type=float, default=0.0)
    ap.add_argument("--max-frames", type=int, default=0)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    if bool(args.photo) == bool(args.video):
        raise SystemExit("--photo 和 --video 必须二选一")
    if args.photo:
        run_photo(args)
    else:
        run_video(args)


if __name__ == "__main__":
    main()
