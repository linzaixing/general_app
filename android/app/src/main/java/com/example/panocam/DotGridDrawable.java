package com.example.panocam;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** libraries.dev 风格的 1px 点阵背景。 */
final class DotGridDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float step;
    private final float radius;

    DotGridDrawable(int color, float stepPx, float radiusPx) {
        paint.setColor(color);
        this.step = Math.max(stepPx, 2f);
        this.radius = radiusPx;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        float w = getBounds().width();
        float h = getBounds().height();
        float ox = getBounds().left;
        float oy = getBounds().top;
        for (float y = step / 2f; y < h; y += step) {
            for (float x = step / 2f; x < w; x += step) {
                canvas.drawCircle(ox + x, oy + y, radius, paint);
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
