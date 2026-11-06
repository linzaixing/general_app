package com.example.panocam;

import android.graphics.BitmapFactory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class PreviewEncoderTest {
    @Test public void repeatedSameSizePreviewReusesBitmapsAndKeepsIndependentJpegs() {
        PreviewEncoder encoder = new PreviewEncoder();
        try {
            int[] pixels = new int[640 * 320];
            byte[] first = encoder.encode(pixels, 640, 320, 512, 80);
            assertNotNull(first);
            assertEquals(512, BitmapFactory.decodeByteArray(first, 0, first.length).getWidth());
            int allocations = encoder.allocations();

            java.util.Arrays.fill(pixels, 0xffff0000);
            byte[] second = encoder.encode(pixels, 640, 320, 512, 80);
            assertEquals(allocations, encoder.allocations());
            assertNotSame(first, second);
            assertEquals(256, BitmapFactory.decodeByteArray(second, 0, second.length).getHeight());
        } finally {
            encoder.close();
        }
    }
}
