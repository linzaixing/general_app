package com.example.panocam;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PreviewLoadPolicyTest {
    @Test public void sustainedPressureReducesPreviewWorkButLeavesSaveSelectionToCaller() {
        PreviewLoadPolicy policy = new PreviewLoadPolicy();
        for (int second = 1; second <= 3; second++) {
            policy.sample(second * 1_000_000_000L, 4, 0, 0);
        }
        assertEquals(1280, policy.width(1536));
        assertEquals(80, policy.quality());
        assertEquals(135, policy.intervalMs(true));
    }
}
