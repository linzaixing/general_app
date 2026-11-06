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
}
