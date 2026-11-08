package com.icehome.up;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * El icono dibujado en vectores para «Acerca de»: el fondo coral es algo transparente, así que
 * los brillos de color que pasan por detrás lo tiñen (como el de HyperCeiler); la H es blanca y
 * opaca, con un muelle en zigzag en lugar del travesaño. Misma geometría que el icono (rejilla de 512).
 */
final class LogoDrawable extends Drawable {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), fg = new Paint(Paint.ANTI_ALIAS_FLAG),
            coil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path squircle = new Path(), glyph = new Path(), spring = new Path();
    private final RectF r = new RectF();

    LogoDrawable(int color, int alpha) {
        bg.setColor(color);
        bg.setAlpha(alpha);
        fg.setColor(0xFFFFFFFF);
        coil.setColor(0xFFFFFFFF);
        coil.setStyle(Paint.Style.STROKE);
        coil.setStrokeCap(Paint.Cap.ROUND);
        coil.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        float s = Math.min(b.width(), b.height()) / 512f;
        float ox = b.left + (b.width() - 512 * s) / 2f, oy = b.top + (b.height() - 512 * s) / 2f;
        squircle.reset();
        r.set(ox, oy, ox + 512 * s, oy + 512 * s);
        squircle.addRoundRect(r, 512 * s * 0.27f, 512 * s * 0.27f, Path.Direction.CW);

        Path p = new Path();
        rr(p, ox, oy, s, 136, 132, 196, 380, 30);
        rr(p, ox, oy, s, 316, 132, 376, 380, 30);
        glyph.set(p);

        // Muelle: zigzag entre las dos patas, trazo de 22 con extremos redondeados.
        float[] pts = {196, 256, 208, 224, 228, 288, 248, 224, 268, 288, 288, 224, 304, 256, 316, 256};
        spring.reset();
        spring.moveTo(ox + pts[0] * s, oy + pts[1] * s);
        for (int i = 2; i < pts.length; i += 2) spring.lineTo(ox + pts[i] * s, oy + pts[i + 1] * s);
        coil.setStrokeWidth(22 * s);
    }

    private void rr(Path p, float ox, float oy, float s, float l, float t, float rt, float bt, float rad) {
        Path q = new Path();
        r.set(ox + l * s, oy + t * s, ox + rt * s, oy + bt * s);
        q.addRoundRect(r, rad * s, rad * s, Path.Direction.CW);
        p.op(q, Path.Op.UNION);
    }

    @Override
    public void draw(Canvas c) {
        c.drawPath(squircle, bg);
        c.drawPath(glyph, fg);
        c.drawPath(spring, coil);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
