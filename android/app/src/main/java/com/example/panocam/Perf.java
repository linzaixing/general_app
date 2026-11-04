package com.example.panocam;

import android.util.Log;

import java.util.Arrays;
import java.util.Locale;

/**
 * 超轻量分阶段耗时统计：热路径只记 System.nanoTime()，每 5 秒打一条聚合日志。
 * 日志 tag 固定为 PanoPerf，方便 adb logcat 过滤与脚本解析。
 */
public final class Perf {

    private static final String TAG = "PanoPerf";
    private static final int CAP = 200;                 // 每个阶段窗口内最多保留的样本数
    private static final long WINDOW_MS = 5000L;        // 聚合窗口

    // ---- 预览链路 ----
    public static final Stat GRAB = new Stat("grab");
    public static final Stat STITCH = new Stat("stitch");
    public static final Stat GPU = new Stat("gpu");
    public static final Stat READ = new Stat("read");
    public static final Stat ENCODE = new Stat("enc");
    public static final Stat PUSH = new Stat("push");
    public static final Stat ROUND = new Stat("round");
    public static final Stat FRAME = new Stat("frame");
    public static final Stat SIZE = new Stat("jpgKB");

    // ---- 录制 ----
    public static final Stat REC_PUSH = new Stat("recpush");
    public static final Stat REC_START = new Stat("recstart");
    public static final Stat REC_STOP = new Stat("recstop");

    // ---- 拍照 / 保存 ----
    public static final Stat P_GRAB = new Stat("p_grab");
    public static final Stat P_STITCH = new Stat("p_stitch");
    public static final Stat P_ROTATE = new Stat("p_rotate");
    public static final Stat P_PNG = new Stat("p_png");
    public static final Stat P_IO = new Stat("p_io");
    public static final Stat P_ALL = new Stat("p_all");

    private static long lastFlushAt = System.currentTimeMillis();
    private static int frames;
    private static int recFrames;

    private Perf() {
    }

    public static long t() {
        return System.nanoTime();
    }

    public static void end(Stat s, long startNanos) {
        s.add((System.nanoTime() - startNanos) / 1000L);
    }

    public static void frameTick() {
        frames++;
    }

    public static void recFrameTick() {
        recFrames++;
    }

    public static void logConfig(String cfg) {
        Log.i(TAG, "cfg " + cfg);
    }

    /** 一次性拼接测速结果（不同输出分辨率），单独打一行方便筛。 */
    public static void logBench(String msg) {
        Log.i(TAG, "bench " + msg);
    }

    /** 网页端上报的 JS 侧指标（decode/upload/draw/rAF），由页面每 5 秒汇总一次。 */
    public static void logJs(String key, double p50, double p95, int n) {
        Log.i(TAG, String.format(Locale.US, "js %s p50=%.2f p95=%.2f n=%d", key, p50, p95, n));
    }

    public static void flushIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastFlushAt < WINDOW_MS) {
            return;
        }
        double win = (now - lastFlushAt) / 1000.0;
        lastFlushAt = now;
        StringBuilder sb = new StringBuilder(256);
        sb.append("win=").append(String.format(Locale.US, "%.1fs", win))
                .append(" frames=").append(frames)
                .append(" fps=").append(String.format(Locale.US, "%.1f", frames / win))
                .append(" recFrames=").append(recFrames)
                .append(" memJ=").append((Runtime.getRuntime().totalMemory()
                        - Runtime.getRuntime().freeMemory()) / (1024 * 1024)).append("MB");
        appendStat(sb, GRAB);
        appendStat(sb, STITCH);
        appendStat(sb, GPU);
        appendStat(sb, READ);
        appendStat(sb, ENCODE);
        appendStat(sb, PUSH);
        appendStat(sb, ROUND);
        appendStat(sb, FRAME);
        appendStat(sb, SIZE);
        appendStat(sb, REC_PUSH);
        appendStat(sb, REC_START);
        appendStat(sb, REC_STOP);
        appendStat(sb, P_GRAB);
        appendStat(sb, P_STITCH);
        appendStat(sb, P_ROTATE);
        appendStat(sb, P_PNG);
        appendStat(sb, P_IO);
        appendStat(sb, P_ALL);
        Log.i(TAG, sb.toString());
        frames = 0;
        recFrames = 0;
    }

    private static void appendStat(StringBuilder sb, Stat s) {
        String text = s.summary();
        if (text == null) {
            return;
        }
        sb.append(' ').append(text);
    }

    /** 单阶段样本环，summary 输出 p50/p95/样本数（单位 ms）。 */
    public static final class Stat {
        final String name;
        private final long[] us = new long[CAP];
        private int idx;
        private int n;

        Stat(String name) {
            this.name = name;
        }

        synchronized void add(long valueUs) {
            us[idx] = valueUs;
            idx = (idx + 1) % CAP;
            if (n < CAP) {
                n++;
            }
        }

        synchronized String summary() {
            if (n == 0) {
                return null;
            }
            long[] a = Arrays.copyOf(us, n);
            Arrays.sort(a);
            long p50 = a[(int) Math.floor((n - 1) * 0.5)];
            long p95 = a[(int) Math.floor((n - 1) * 0.95)];
            String out = String.format(Locale.US, "%s=%.1f/%.1f(%d)",
                    name, p50 / 1000.0, p95 / 1000.0, n);
            n = 0;
            idx = 0;
            return out;
        }
    }
}
