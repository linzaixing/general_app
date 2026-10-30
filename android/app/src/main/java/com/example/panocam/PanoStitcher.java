package com.example.panocam;

/**
 * Insta360 Air 双鱼眼拼接与全景投影（纯 Java 移植自 PC 端 stitch_air.py）。
 * 参数直接使用原 App 的 offset，不重新标定。
 */
public final class PanoStitcher {

    /** 原 App 资源 assets/sample/photo.insp 中的 offset。 */
    public static final String OFFSET =
            "2_766.580_754.000_748.740_0.000_0.000_90.000_"
            + "781.580_2259.760_753.000_-0.400_0.000_90.800_3008_1504_1035";

    public static final float FOV_DEG = 200f;
    private static final float BLEND_DEG = 10f;

    public static final int MODE_PERSPECTIVE = 0;
    public static final int MODE_TINY_PLANET = 1;
    public static final int MODE_RABBIT_HOLE = 2;
    public static final int MODE_FISHEYE = 3;

    private PanoStitcher() {
    }

    /**
     * 解析 offset，按实际帧宽缩放，返回 {cx, cy, r, roll}。
     * 第二个镜头的 cx 若超过半宽则换算为右半幅局部坐标。
     */
    private static float[][] parseLenses(int frameW) {
        String[] t = OFFSET.split("_");
        int n = Integer.parseInt(t[0]);
        float originW = Float.parseFloat(t[1 + 6 * n]);
        float scale = frameW / originW;
        float half = frameW / 2f;
        float[][] lens = new float[n][4];
        for (int i = 0; i < n; i++) {
            int b = 1 + 6 * i;
            float r = Float.parseFloat(t[b]) * scale;
            float cx = Float.parseFloat(t[b + 1]) * scale;
            float cy = Float.parseFloat(t[b + 2]) * scale;
            float roll = Float.parseFloat(t[b + 5]);
            if (i > 0 && cx > half) {
                cx -= half;
            }
            lens[i][0] = cx;
            lens[i][1] = cy;
            lens[i][2] = r;
            lens[i][3] = roll;
        }
        return lens;
    }

    /** NV21 -> ARGB，供拼接使用。长度不足时返回 null。 */
    public static int[] nv21ToArgb(byte[] nv21, int w, int h) {
        int frameSize = w * h;
        if (nv21 == null || nv21.length < frameSize + frameSize / 2) {
            return null;
        }
        int[] out = new int[frameSize];
        for (int y = 0; y < h; y++) {
            int yRow = y * w;
            int uvRow = frameSize + (y >> 1) * w;
            for (int x = 0; x < w; x++) {
                int yv = nv21[yRow + x] & 0xFF;
                int uv = uvRow + (x & ~1);
                int v = nv21[uv] & 0xFF;
                int u = nv21[uv + 1] & 0xFF;
                int r = yv + ((1402 * (v - 128)) >> 10);
                int g = yv - ((344 * (u - 128)) >> 10) - ((714 * (v - 128)) >> 10);
                int b = yv + ((1772 * (u - 128)) >> 10);
                out[yRow + x] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /**
     * 双线性采样。
     * px 为整帧 ARGB；stride 是整帧行宽，localW 是当前镜头半幅的可用宽度，xOffset 是半幅起始列。
     * 只在半幅范围内做边界检查，越界返回黑，避免读到另一路镜头或越界崩溃。
     */
    private static int sample(int[] px, int stride, int localW, int h,
                              float u, float v, int xOffset) {
        if (u < 0f || v < 0f || u > localW - 1f || v > h - 1f) {
            return 0;
        }
        int x0 = (int) u;
        int y0 = (int) v;
        int x1 = Math.min(x0 + 1, localW - 1);
        int y1 = Math.min(y0 + 1, h - 1);
        float fx = u - x0;
        float fy = v - y0;
        int c00 = px[y0 * stride + x0 + xOffset];
        int c10 = px[y0 * stride + x1 + xOffset];
        int c01 = px[y1 * stride + x0 + xOffset];
        int c11 = px[y1 * stride + x1 + xOffset];
        float w00 = (1f - fx) * (1f - fy);
        float w10 = fx * (1f - fy);
        float w01 = (1f - fx) * fy;
        float w11 = fx * fy;
        int r = (int) (((c00 >> 16) & 0xFF) * w00 + ((c10 >> 16) & 0xFF) * w10
                + ((c01 >> 16) & 0xFF) * w01 + ((c11 >> 16) & 0xFF) * w11);
        int g = (int) (((c00 >> 8) & 0xFF) * w00 + ((c10 >> 8) & 0xFF) * w10
                + ((c01 >> 8) & 0xFF) * w01 + ((c11 >> 8) & 0xFF) * w11);
        int b = (int) ((c00 & 0xFF) * w00 + (c10 & 0xFF) * w10
                + (c01 & 0xFF) * w01 + (c11 & 0xFF) * w11);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 双鱼眼 -> 等距柱状（equirect）。
     * frame 为 ARGB，左半幅是后镜头（P_BACK），右半幅是前镜头（P_FRONT）。
     */
    public static int[] stitchEquirect(int[] frame, int fw, int fh, int ow, int oh) {
        if (frame == null || frame.length < fw * fh) {
            return null;
        }
        float[][] lens = parseLenses(fw);
        int half = fw / 2;
        float halfFov = (float) Math.toRadians(FOV_DEG / 2.0);
        float blend = (float) Math.toRadians(BLEND_DEG);
        float cx0 = lens[0][0], cy0 = lens[0][1], r0 = lens[0][2];
        float roll0 = (float) Math.toRadians(lens[0][3]);
        float cx1 = lens[1][0], cy1 = lens[1][1], r1 = lens[1][2];
        float roll1 = (float) Math.toRadians(lens[1][3]);
        int[] out = new int[ow * oh];

        for (int y = 0; y < oh; y++) {
            double lat = Math.PI / 2.0 - Math.PI * y / (oh - 1);
            float clat = (float) Math.cos(lat);
            float slat = (float) Math.sin(lat);
            int row = y * ow;
            for (int x = 0; x < ow; x++) {
                double lon = -Math.PI + 2.0 * Math.PI * x / ow;
                float rx = clat * (float) Math.sin(lon);
                float ry = slat;
                float rz = clat * (float) Math.cos(lon);

                // 后镜头：P_BACK = diag(1, 1, -1)
                float dz = -rz;
                float th = (float) Math.acos(clampf(dz, -1f, 1f));
                float phi = (float) Math.atan2(ry, rx) + roll0;
                float rr = r0 * th / halfFov;
                int cL = sample(frame, fw, half, fh, cx0 + rr * (float) Math.cos(phi),
                        cy0 + rr * (float) Math.sin(phi), 0);
                if ((cL >>> 24) == 0) {
                    // 鱼眼圆边缘略微越出半幅时，退到圆内最后一行有效像素，避免黑边盲区
                    float rr2 = Math.min(rr, r0 * 0.99f);
                    cL = sample(frame, fw, half, fh, cx0 + rr2 * (float) Math.cos(phi),
                            cy0 + rr2 * (float) Math.sin(phi), 0);
                }

                // 前镜头：P_FRONT = diag(1, -1, 1)
                float dy = -ry;
                th = (float) Math.acos(clampf(rz, -1f, 1f));
                phi = (float) Math.atan2(dy, rx) + roll1;
                rr = r1 * th / halfFov;
                int cR = sample(frame, fw, fw - half, fh, cx1 + rr * (float) Math.cos(phi),
                        cy1 + rr * (float) Math.sin(phi), half);
                if ((cR >>> 24) == 0) {
                    float rr2 = Math.min(rr, r1 * 0.99f);
                    cR = sample(frame, fw, fw - half, fh, cx1 + rr2 * (float) Math.cos(phi),
                            cy1 + rr2 * (float) Math.sin(phi), half);
                }

                float wl = clampf((-rz + blend) / (2f * blend), 0f, 1f);
                out[row + x] = mix(cL, cR, wl);
            }
        }
        return out;
    }

    private static float clampf(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static int mix(int cA, int cB, float wA) {
        float wB = 1f - wA;
        int r = (int) (((cA >> 16) & 0xFF) * wA + ((cB >> 16) & 0xFF) * wB);
        int g = (int) (((cA >> 8) & 0xFF) * wA + ((cB >> 8) & 0xFF) * wB);
        int b = (int) ((cA & 0xFF) * wA + (cB & 0xFF) * wB);
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int equirectSample(int[] eq, int ew, int eh, float dx, float dy, float dz) {
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) {
            return 0;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        double lon = Math.atan2(dx, dz);
        double lat = Math.asin(clampf(dy, -1f, 1f));
        float col = (float) ((lon / (2.0 * Math.PI) + 0.5) * (ew - 1));
        float row = (float) (((Math.PI / 2.0 - lat) / Math.PI) * (eh - 1));
        if (col < 0f) {
            col += ew;
        }
        if (col > ew - 1f) {
            col -= ew;
        }
        if (row < 0f || row > eh - 1f) {
            return 0;
        }
        int x0 = (int) col;
        int y0 = (int) row;
        int x1 = (x0 + 1) % ew;
        int y1 = Math.min(y0 + 1, eh - 1);
        float fx = col - x0;
        float fy = row - y0;
        int c00 = eq[y0 * ew + x0];
        int c10 = eq[y0 * ew + x1];
        int c01 = eq[y1 * ew + x0];
        int c11 = eq[y1 * ew + x1];
        int r = (int) (((c00 >> 16) & 0xFF) * (1 - fx) * (1 - fy)
                + ((c10 >> 16) & 0xFF) * fx * (1 - fy)
                + ((c01 >> 16) & 0xFF) * (1 - fx) * fy
                + ((c11 >> 16) & 0xFF) * fx * fy);
        int g = (int) (((c00 >> 8) & 0xFF) * (1 - fx) * (1 - fy)
                + ((c10 >> 8) & 0xFF) * fx * (1 - fy)
                + ((c01 >> 8) & 0xFF) * (1 - fx) * fy
                + ((c11 >> 8) & 0xFF) * fx * fy);
        int b = (int) ((c00 & 0xFF) * (1 - fx) * (1 - fy)
                + (c10 & 0xFF) * fx * (1 - fy)
                + (c01 & 0xFF) * (1 - fx) * fy
                + (c11 & 0xFF) * fx * fy);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 透视（普通 360 浏览），yaw/pitch 单位为度。 */
    public static int[] renderPerspective(int[] eq, int ew, int eh, int ow, int oh,
                                          float fovDeg, float yawDeg, float pitchDeg) {
        int[] out = new int[ow * oh];
        double halfW = (ow - 1) / 2.0;
        double fx = Math.tan(Math.toRadians(fovDeg) / 2.0);
        double ay = Math.toRadians(yawDeg);
        double ap = Math.toRadians(pitchDeg);
        double cy = Math.cos(ay), sy = Math.sin(ay);
        double cp = Math.cos(ap), sp = Math.sin(ap);
        for (int y = 0; y < oh; y++) {
            double yy = -(y - (oh - 1) / 2.0) / halfW * fx;
            int row = y * ow;
            for (int x = 0; x < ow; x++) {
                double xx = (x - halfW) / halfW * fx;
                // rotX(pitch)
                double y1 = cp * yy - sp;
                double z1 = sp * yy + cp;
                // rotZ(yaw)
                double dx = cy * xx - sy * y1;
                double dy = sy * xx + cy * y1;
                out[row + x] = equirectSample(eq, ew, eh, (float) dx, (float) dy, (float) z1);
            }
        }
        return out;
    }

    /** 小行星：地面在中心。 */
    public static int[] renderTinyPlanet(int[] eq, int ew, int eh, int size, float yawDeg) {
        int[] out = new int[size * size];
        float c = size / 2f;
        float radius = size / 2f;
        float yaw = (float) Math.toRadians(yawDeg);
        for (int y = 0; y < size; y++) {
            float v = (y - c) / radius;
            int row = y * size;
            for (int x = 0; x < size; x++) {
                float u = (x - c) / radius;
                float r = (float) Math.sqrt(u * u + v * v);
                double lon = Math.atan2(v, u) + yaw;
                double lat = 2.0 * Math.atan(r) - Math.PI / 2.0;
                out[row + x] = equirectSample(eq, ew, eh,
                        (float) (Math.cos(lat) * Math.sin(lon)),
                        (float) Math.sin(lat),
                        (float) (Math.cos(lat) * Math.cos(lon)));
            }
        }
        return out;
    }

    /** 兔子洞：天顶在中心，与小行星相反。 */
    public static int[] renderRabbitHole(int[] eq, int ew, int eh, int size, float yawDeg) {
        int[] out = new int[size * size];
        float c = size / 2f;
        float radius = size / 2f;
        float yaw = (float) Math.toRadians(yawDeg);
        for (int y = 0; y < size; y++) {
            float v = (y - c) / radius;
            int row = y * size;
            for (int x = 0; x < size; x++) {
                float u = (x - c) / radius;
                float r = (float) Math.sqrt(u * u + v * v);
                double lon = Math.atan2(v, u) + yaw;
                double lat = Math.PI / 2.0 - 2.0 * Math.atan(r);
                out[row + x] = equirectSample(eq, ew, eh,
                        (float) (Math.cos(lat) * Math.sin(lon)),
                        (float) Math.sin(lat),
                        (float) (Math.cos(lat) * Math.cos(lon)));
            }
        }
        return out;
    }

    /** 鱼眼：整个球面压进一个圆。 */
    public static int[] renderFisheye(int[] eq, int ew, int eh, int size,
                                      float yawDeg, float pitchDeg) {
        int[] out = new int[size * size];
        float c = size / 2f;
        float radius = size / 2f;
        double ay = Math.toRadians(yawDeg);
        double ap = Math.toRadians(pitchDeg);
        double cy = Math.cos(ay), sy = Math.sin(ay);
        double cp = Math.cos(ap), sp = Math.sin(ap);
        for (int y = 0; y < size; y++) {
            float v = (y - c) / radius;
            int row = y * size;
            for (int x = 0; x < size; x++) {
                float u = (x - c) / radius;
                double r = Math.sqrt(u * u + v * v);
                double theta = r * Math.PI;
                double phi = Math.atan2(v, u);
                double sx = Math.sin(theta) * Math.cos(phi);
                double syy = Math.sin(theta) * Math.sin(phi);
                double sz = Math.cos(theta);
                // rotX(pitch) 再 rotZ(yaw)
                double y1 = cp * syy - sp * sz;
                double z1 = sp * syy + cp * sz;
                double dx = cy * sx - sy * y1;
                double dy = sy * sx + cy * y1;
                out[row + x] = equirectSample(eq, ew, eh, (float) dx, (float) dy, (float) z1);
            }
        }
        return out;
    }
}
