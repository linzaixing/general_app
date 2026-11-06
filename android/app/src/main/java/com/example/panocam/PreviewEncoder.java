package com.example.panocam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;

/** Reuses frame-sized bitmaps and its compression buffer on the preview worker. */
final class PreviewEncoder implements AutoCloseable {
    private Bitmap source, target;
    private int allocations;
    private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256 * 1024);

    synchronized int allocations() { return allocations; }

    synchronized byte[] encode(int[] pixels, int width, int height, int maxWidth, int quality) {
        if (pixels == null || width < 2 || height < 2 || pixels.length < (long) width * height) return null;
        int outW = maxWidth > 0 ? Math.min(width, maxWidth) & ~1 : width;
        int outH = (outW == width ? height : (int) ((long) height * outW / width)) & ~1;
        if (outW < 2 || outH < 2) return null;
        if (source == null || source.getWidth() != width || source.getHeight() != height) {
            if (source != null) source.recycle();
            source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            allocations++;
        }
        source.setPixels(pixels, 0, width, 0, 0, width, height);
        Bitmap encoded = source;
        if (outW != width || outH != height) {
            if (target == null || target.getWidth() != outW || target.getHeight() != outH) {
                if (target != null) target.recycle();
                target = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                allocations++;
            }
            new Canvas(target).drawBitmap(source, new Rect(0, 0, width, height),
                    new Rect(0, 0, outW, outH), filter);
            encoded = target;
        }
        buffer.reset();
        if (!encoded.compress(Bitmap.CompressFormat.JPEG, quality, buffer)) return null;
        return buffer.toByteArray();
    }

    @Override public synchronized void close() {
        if (source != null) { source.recycle(); source = null; }
        if (target != null) { target.recycle(); target = null; }
    }
}
