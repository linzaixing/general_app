package com.example.panocam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PreviewBackpressurePolicyTest {
    @Test public void pageBackpressureOnlyPausesPreviewOnlyWork() {
        assertTrue(MainActivity.shouldWaitForPreviewConsumer(false, false));
        assertFalse(MainActivity.shouldWaitForPreviewConsumer(true, false));
        assertFalse(MainActivity.shouldWaitForPreviewConsumer(false, true));
    }

    @Test public void cpuPreviewHonorsPageAckAndAdaptiveIntervalWithoutGatingRecording() {
        assertTrue(MainActivity.shouldEncodeCpuPreview(true, true, 0, 100, 1_000_000_000L, 0));
        assertFalse(MainActivity.shouldEncodeCpuPreview(false, true, 2, 100, 1_200_000_000L, 0));
        assertFalse(MainActivity.shouldEncodeCpuPreview(true, true, 1, 100, 1_200_000_000L, 0));
        assertFalse(MainActivity.shouldEncodeCpuPreview(true, true, 0, 100, 1_050_000_000L, 1_000_000_000L));
        assertTrue(MainActivity.shouldEncodeCpuPreview(true, true, 0, 100, 1_100_000_000L, 1_000_000_000L));
    }
}
