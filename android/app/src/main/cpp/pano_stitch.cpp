// 双鱼眼 -> 等距柱状拼接的 NEON 内核：与 PanoStitcher.stitchRowsJava 逐像素等价。
// 采样坐标全部来自 Java 侧预计算的 LUT，这里只做「按行采样 + 双线性 + 左右镜头混合」。
#include <jni.h>
#include <cstdint>
#include <cmath>
#include <algorithm>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define PANO_NEON 1
#else
#define PANO_NEON 0
#endif

namespace {

inline int clamp255(int v) {
    return v < 0 ? 0 : (v > 255 ? 255 : v);
}

// 与 Java 的 sample() 等价：越界返回透明黑，否则双线性采样
inline uint32_t samplePx(const uint32_t* px, int stride, int localW, int h,
                         float u, float v, int xOffset) {
    if (u < 0.f || v < 0.f || u > (float) (localW - 1) || v > (float) (h - 1)) {
        return 0u;
    }
    int x0 = (int) u;
    int y0 = (int) v;
    int x1 = std::min(x0 + 1, localW - 1);
    int y1 = std::min(y0 + 1, h - 1);
    float fx = u - (float) x0;
    float fy = v - (float) y0;
    uint32_t c00 = px[y0 * stride + x0 + xOffset];
    uint32_t c10 = px[y0 * stride + x1 + xOffset];
    uint32_t c01 = px[y1 * stride + x0 + xOffset];
    uint32_t c11 = px[y1 * stride + x1 + xOffset];
    float w00 = (1.f - fx) * (1.f - fy);
    float w10 = fx * (1.f - fy);
    float w01 = (1.f - fx) * fy;
    float w11 = fx * fy;
    int r = (int) (((c00 >> 16) & 0xFF) * w00 + ((c10 >> 16) & 0xFF) * w10
                   + ((c01 >> 16) & 0xFF) * w01 + ((c11 >> 16) & 0xFF) * w11);
    int g = (int) (((c00 >> 8) & 0xFF) * w00 + ((c10 >> 8) & 0xFF) * w10
                   + ((c01 >> 8) & 0xFF) * w01 + ((c11 >> 8) & 0xFF) * w11);
    int b = (int) ((c00 & 0xFF) * w00 + (c10 & 0xFF) * w10
                   + (c01 & 0xFF) * w01 + (c11 & 0xFF) * w11);
    return 0xFF000000u | ((uint32_t) clamp255(r) << 16)
           | ((uint32_t) clamp255(g) << 8) | (uint32_t) clamp255(b);
}

// 与 Java 的 mix() 等价
inline uint32_t mixPx(uint32_t cA, uint32_t cB, float wA) {
    float wB = 1.f - wA;
    int r = (int) (((cA >> 16) & 0xFF) * wA + ((cB >> 16) & 0xFF) * wB);
    int g = (int) (((cA >> 8) & 0xFF) * wA + ((cB >> 8) & 0xFF) * wB);
    int b = (int) ((cA & 0xFF) * wA + (cB & 0xFF) * wB);
    return 0xFF000000u | ((uint32_t) clamp255(r) << 16)
           | ((uint32_t) clamp255(g) << 8) | (uint32_t) clamp255(b);
}

#if PANO_NEON
// 一次混合 4 个像素：左右镜头颜色与混合权重都按 4 通道并行
inline void mix4(const uint32_t cL[4], const uint32_t cR[4], const float w[4],
                 uint32_t out[4]) {
    float32x4_t wa = vld1q_f32(w);
    float32x4_t wb = vsubq_f32(vdupq_n_f32(1.f), wa);
    uint32x4_t vl = vld1q_u32(cL);
    uint32x4_t vr = vld1q_u32(cR);
    uint32x4_t mask = vdupq_n_u32(0xFFu);
    uint32x4_t cap = vdupq_n_u32(255u);

    uint32x4_t rl = vandq_u32(vshrq_n_u32(vl, 16), mask);
    uint32x4_t gl = vandq_u32(vshrq_n_u32(vl, 8), mask);
    uint32x4_t bl = vandq_u32(vl, mask);
    uint32x4_t rr = vandq_u32(vshrq_n_u32(vr, 16), mask);
    uint32x4_t gr = vandq_u32(vshrq_n_u32(vr, 8), mask);
    uint32x4_t br = vandq_u32(vr, mask);

    float32x4_t fr = vaddq_f32(vmulq_f32(vcvtq_f32_u32(rl), wa),
                               vmulq_f32(vcvtq_f32_u32(rr), wb));
    float32x4_t fg = vaddq_f32(vmulq_f32(vcvtq_f32_u32(gl), wa),
                               vmulq_f32(vcvtq_f32_u32(gr), wb));
    float32x4_t fb = vaddq_f32(vmulq_f32(vcvtq_f32_u32(bl), wa),
                               vmulq_f32(vcvtq_f32_u32(br), wb));

    uint32x4_t ur = vminq_u32(vcvtq_u32_f32(fr), cap);
    uint32x4_t ug = vminq_u32(vcvtq_u32_f32(fg), cap);
    uint32x4_t ub = vminq_u32(vcvtq_u32_f32(fb), cap);

    uint32x4_t packed = vdupq_n_u32(0xFF000000u);
    packed = vorrq_u32(packed, vshlq_n_u32(ur, 16));
    packed = vorrq_u32(packed, vshlq_n_u32(ug, 8));
    packed = vorrq_u32(packed, ub);
    vst1q_u32(out, packed);
}
#endif

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_example_panocam_PanoStitcher_stitchRowsNative(
        JNIEnv* env, jclass,
        jintArray frameArr, jintArray outArr,
        jint fw, jint fh, jint ow, jint yStart, jint yEnd,
        jfloatArray ulArr, jfloatArray vlArr, jfloatArray ul2Arr, jfloatArray vl2Arr,
        jfloatArray urArr, jfloatArray vrArr, jfloatArray ur2Arr, jfloatArray vr2Arr,
        jfloatArray blendArr) {
    if (frameArr == nullptr || outArr == nullptr || blendArr == nullptr) {
        return;
    }
    uint32_t* frame = (uint32_t*) env->GetPrimitiveArrayCritical(frameArr, nullptr);
    uint32_t* out = (uint32_t*) env->GetPrimitiveArrayCritical(outArr, nullptr);
    float* ul = (float*) env->GetPrimitiveArrayCritical(ulArr, nullptr);
    float* vl = (float*) env->GetPrimitiveArrayCritical(vlArr, nullptr);
    float* ul2 = (float*) env->GetPrimitiveArrayCritical(ul2Arr, nullptr);
    float* vl2 = (float*) env->GetPrimitiveArrayCritical(vl2Arr, nullptr);
    float* ur = (float*) env->GetPrimitiveArrayCritical(urArr, nullptr);
    float* vr = (float*) env->GetPrimitiveArrayCritical(vrArr, nullptr);
    float* ur2 = (float*) env->GetPrimitiveArrayCritical(ur2Arr, nullptr);
    float* vr2 = (float*) env->GetPrimitiveArrayCritical(vr2Arr, nullptr);
    float* bl = (float*) env->GetPrimitiveArrayCritical(blendArr, nullptr);

    if (frame == nullptr || out == nullptr || ul == nullptr || vl == nullptr
        || ul2 == nullptr || vl2 == nullptr || ur == nullptr || vr == nullptr
        || ur2 == nullptr || vr2 == nullptr || bl == nullptr) {
        if (frame) env->ReleasePrimitiveArrayCritical(frameArr, frame, JNI_ABORT);
        if (out) env->ReleasePrimitiveArrayCritical(outArr, out, JNI_ABORT);
        if (ul) env->ReleasePrimitiveArrayCritical(ulArr, ul, JNI_ABORT);
        if (vl) env->ReleasePrimitiveArrayCritical(vlArr, vl, JNI_ABORT);
        if (ul2) env->ReleasePrimitiveArrayCritical(ul2Arr, ul2, JNI_ABORT);
        if (vl2) env->ReleasePrimitiveArrayCritical(vl2Arr, vl2, JNI_ABORT);
        if (ur) env->ReleasePrimitiveArrayCritical(urArr, ur, JNI_ABORT);
        if (vr) env->ReleasePrimitiveArrayCritical(vrArr, vr, JNI_ABORT);
        if (ur2) env->ReleasePrimitiveArrayCritical(ur2Arr, ur2, JNI_ABORT);
        if (vr2) env->ReleasePrimitiveArrayCritical(vr2Arr, vr2, JNI_ABORT);
        if (bl) env->ReleasePrimitiveArrayCritical(blendArr, bl, JNI_ABORT);
        return;
    }

    const int stride = fw;
    const int half = fw / 2;
    const int fwHalfW = fw - half;
    const float maxUL = (float) (half - 1);
    const float maxUR = (float) (fwHalfW - 1);
    const float maxV = (float) (fh - 1);

    for (int y = yStart; y < yEnd; ++y) {
        int row = y * ow;
        int x = 0;
#if PANO_NEON
        // 一次 4 个像素：采样仍是标量 gather（NEON 没有 gather），混合走 SIMD
        for (; x + 3 < ow; x += 4) {
            int i = row + x;
            uint32_t cl[4], cr[4];
            float w[4];
            for (int k = 0; k < 4; ++k) {
                int j = i + k;
                float u = ul[j], v = vl[j];
                cl[k] = (u < 0.f || v < 0.f || u > maxUL || v > maxV)
                        ? samplePx(frame, stride, half, fh, ul2[j], vl2[j], 0)
                        : samplePx(frame, stride, half, fh, u, v, 0);
                u = ur[j];
                v = vr[j];
                cr[k] = (u < 0.f || v < 0.f || u > maxUR || v > maxV)
                        ? samplePx(frame, stride, fwHalfW, fh, ur2[j], vr2[j], half)
                        : samplePx(frame, stride, fwHalfW, fh, u, v, half);
                w[k] = bl[j];
            }
            uint32_t packed[4];
            mix4(cl, cr, w, packed);
            out[i] = packed[0];
            out[i + 1] = packed[1];
            out[i + 2] = packed[2];
            out[i + 3] = packed[3];
        }
#endif
        for (; x < ow; ++x) {
            int j = row + x;
            float u = ul[j], v = vl[j];
            uint32_t cL = (u < 0.f || v < 0.f || u > maxUL || v > maxV)
                          ? samplePx(frame, stride, half, fh, ul2[j], vl2[j], 0)
                          : samplePx(frame, stride, half, fh, u, v, 0);
            u = ur[j];
            v = vr[j];
            uint32_t cR = (u < 0.f || v < 0.f || u > maxUR || v > maxV)
                          ? samplePx(frame, stride, fwHalfW, fh, ur2[j], vr2[j], half)
                          : samplePx(frame, stride, fwHalfW, fh, u, v, half);
            out[j] = mixPx(cL, cR, bl[j]);
        }
    }

    env->ReleasePrimitiveArrayCritical(blendArr, bl, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(vr2Arr, vr2, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(ur2Arr, ur2, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(vrArr, vr, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(urArr, ur, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(vl2Arr, vl2, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(ul2Arr, ul2, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(vlArr, vl, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(ulArr, ul, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(outArr, out, 0);
    env->ReleasePrimitiveArrayCritical(frameArr, frame, JNI_ABORT);
}
