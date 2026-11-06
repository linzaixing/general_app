package com.example.panocam;

/** Reduces preview-only work under sustained thermal or pipeline pressure. */
final class PreviewLoadPolicy {
    private static final int[] WIDTHS = {1536, 1280, 1024, 768, 512};
    private int level, hotCount, coolCount;
    private long lastChangeNs = Long.MIN_VALUE;
    private long lastSampleNs = Long.MIN_VALUE;
    private final double[] encodes = new double[64], rounds = new double[64];
    private final long[] times = new long[64];
    private int next, count;

    synchronized void sample(long nowNs, int thermal, double encodeMs, double roundMs) {
        sample(nowNs, thermal, encodeMs, roundMs, 0);
    }

    synchronized void sample(long nowNs, int thermal, double encodeMs, double roundMs, double previewFps) {
        if (nowNs < lastSampleNs) return;
        times[next] = nowNs;
        encodes[next] = Math.max(0, encodeMs);
        rounds[next] = Math.max(0, roundMs);
        next = (next + 1) % times.length;
        count = Math.min(count + 1, times.length);
        if (lastSampleNs != Long.MIN_VALUE && nowNs - lastSampleNs < 800_000_000L) return;
        lastSampleNs = nowNs;
        double encodeAverage = 0, roundAverage = 0;
        int samples = 0;
        for (int i = 0; i < count; i++) {
            if (nowNs - times[i] <= 5_000_000_000L) {
                encodeAverage += encodes[i];
                roundAverage += rounds[i];
                samples++;
            }
        }
        if (samples > 0) {
            encodeMs = encodeAverage / samples;
            roundMs = roundAverage / samples;
        }
        boolean hot = thermal >= 4 || encodeMs >= 30 || roundMs >= 70
                || (previewFps > 0 && previewFps < 18 && encodeMs >= 20);
        if (hot) { hotCount++; coolCount = 0; }
        else if (thermal <= 2 && encodeMs < 18 && roundMs < 35) { coolCount++; hotCount = 0; }
        else { hotCount = 0; coolCount = 0; }
        if (hotCount >= 3 && level < WIDTHS.length - 1
                && (lastChangeNs == Long.MIN_VALUE || nowNs - lastChangeNs >= 5_000_000_000L)) {
            level++;
            hotCount = 0;
            lastChangeNs = nowNs;
        } else if (coolCount >= 10 && level > 0 && nowNs - lastChangeNs >= 20_000_000_000L) {
            level--;
            coolCount = 0;
            lastChangeNs = nowNs;
        }
    }

    synchronized int width(int requested) { return Math.min(Math.max(512, requested) & ~1, WIDTHS[level]); }
    synchronized int quality() { return Math.max(65, 85 - level * 5); }
    synchronized int intervalMs(boolean recording) { return (recording ? 100 : 0) + level * 35; }
}
