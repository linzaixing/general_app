package com.example.panocam;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class PanoLensCacheTest {
    @Test public void repeatedSourceWidthUsesCachedImmutableLensValues() {
        PanoStitcher.Lenses first = PanoStitcher.cachedLenses(3008);
        assertSame(first, PanoStitcher.cachedLenses(3008));
        assertNotSame(first, PanoStitcher.cachedLenses(1504));

        float original = PanoStitcher.lensParams(3008)[0][0];
        float[][] exposed = PanoStitcher.lensParams(3008);
        exposed[0][0] = -1f;
        assertTrue(PanoStitcher.lensParams(3008)[0][0] == original);
    }
}
