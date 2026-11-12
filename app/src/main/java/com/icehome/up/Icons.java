package com.icehome.up;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Iconos de línea dibujados con trazos (rejilla de 24 × 24, trazo de 1,8), sin recursos: casa,
 * escritorio, sistema, información, genio, recientes, carpeta, cristal, interruptores, telón,
 * centro de control, registro, reiniciar, flechas.
 */
final class Icons extends Drawable {

    static final int HOME = 0, GRID = 1, PHONE = 2, INFO = 3, SPARKLE = 4, LAYERS = 5, FOLDER = 6, DROP = 7,
            TOGGLES = 8, CURTAIN = 9, SLIDERS = 10, LIST = 11, RESTART = 12, CHEVRON = 13, BACK = 14, WIDGETS = 15,
            LIGHT = 16, PALETTE = 17, CODE = 18, SHIELD = 19, DOWNLOAD = 20;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    Icons(int kind, int color) {
        this.kind = kind;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    void setColor(int c) {
        paint.setColor(c);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        if (b.isEmpty()) return;
        c.save();
        c.translate(b.left, b.top);
        float s = Math.min(b.width(), b.height()) / 24f;
        c.scale(s, s);
        path.reset();
        switch (kind) {
            case HOME:
                path.moveTo(4, 10.5f);
                path.lineTo(12, 4);
                path.lineTo(20, 10.5f);
                path.lineTo(20, 19);
                path.quadTo(20, 20, 19, 20);
                path.lineTo(15, 20);
                path.lineTo(15, 14.5f);
                path.lineTo(9, 14.5f);
                path.lineTo(9, 20);
                path.lineTo(5, 20);
                path.quadTo(4, 20, 4, 19);
                path.close();
                break;
            case GRID:
                rr(4, 4, 10.5f, 10.5f, 2.2f);
                rr(13.5f, 4, 20, 10.5f, 2.2f);
                rr(4, 13.5f, 10.5f, 20, 2.2f);
                rr(13.5f, 13.5f, 20, 20, 2.2f);
                break;
            case PHONE:
                rr(6.5f, 3, 17.5f, 21, 3);
                path.moveTo(10.5f, 18);
                path.lineTo(13.5f, 18);
                break;
            case INFO:
                path.addCircle(12, 12, 8.5f, Path.Direction.CW);
                path.moveTo(12, 11);
                path.lineTo(12, 16.5f);
                path.addCircle(12, 7.8f, 0.4f, Path.Direction.CW);
                break;
            case SPARKLE:
                path.moveTo(12, 3.5f);
                path.quadTo(13, 11, 20.5f, 12);
                path.quadTo(13, 13, 12, 20.5f);
                path.quadTo(11, 13, 3.5f, 12);
                path.quadTo(11, 11, 12, 3.5f);
                path.close();
                break;
            case LAYERS:
                rr(5, 8, 19, 20, 2.5f);
                path.moveTo(7, 5.5f);
                path.lineTo(17, 5.5f);
                path.moveTo(9, 3);
                path.lineTo(15, 3);
                break;
            case FOLDER:
                path.moveTo(3.5f, 7);
                path.quadTo(3.5f, 5, 5.5f, 5);
                path.lineTo(9.5f, 5);
                path.lineTo(11.5f, 7.5f);
                path.lineTo(18.5f, 7.5f);
                path.quadTo(20.5f, 7.5f, 20.5f, 9.5f);
                path.lineTo(20.5f, 17.5f);
                path.quadTo(20.5f, 19.5f, 18.5f, 19.5f);
                path.lineTo(5.5f, 19.5f);
                path.quadTo(3.5f, 19.5f, 3.5f, 17.5f);
                path.close();
                break;
            case DROP:
                path.moveTo(12, 3.5f);
                path.cubicTo(12, 3.5f, 5.5f, 10.5f, 5.5f, 14.5f);
                path.cubicTo(5.5f, 18.2f, 8.4f, 20.5f, 12, 20.5f);
                path.cubicTo(15.6f, 20.5f, 18.5f, 18.2f, 18.5f, 14.5f);
                path.cubicTo(18.5f, 10.5f, 12, 3.5f, 12, 3.5f);
                path.close();
                path.moveTo(9, 15);
                path.quadTo(9.2f, 17.3f, 11.5f, 17.6f);
                break;
            case TOGGLES:
                rr(3.5f, 5, 20.5f, 11, 3);
                path.addCircle(17.5f, 8, 1.6f, Path.Direction.CW);
                rr(3.5f, 13, 20.5f, 19, 3);
                path.addCircle(6.5f, 16, 1.6f, Path.Direction.CW);
                break;
            case CURTAIN:
                rr(4, 4, 20, 20, 3);
                path.moveTo(9, 4);
                path.quadTo(8, 13, 11, 20);
                path.moveTo(15, 4);
                path.quadTo(16, 13, 13, 20);
                break;
            case SLIDERS:
                path.moveTo(4, 7);
                path.lineTo(20, 7);
                path.moveTo(4, 17);
                path.lineTo(20, 17);
                path.addCircle(9, 7, 2.2f, Path.Direction.CW);
                path.addCircle(15, 17, 2.2f, Path.Direction.CW);
                break;
            case LIST:
                rr(4, 3.5f, 20, 20.5f, 2.5f);
                path.moveTo(8, 9);
                path.lineTo(16, 9);
                path.moveTo(8, 12.5f);
                path.lineTo(16, 12.5f);
                path.moveTo(8, 16);
                path.lineTo(12.5f, 16);
                break;
            case RESTART:
                r.set(4.5f, 4.5f, 19.5f, 19.5f);
                path.addArc(r, -60, 300);
                path.moveTo(15.8f, 3.6f);
                path.lineTo(16.3f, 6.6f);
                path.lineTo(13.3f, 7.2f);
                break;
            case CHEVRON:
                path.moveTo(9.5f, 6);
                path.lineTo(15.5f, 12);
                path.lineTo(9.5f, 18);
                break;
            case BACK:
                path.moveTo(14.5f, 5.5f);
                path.lineTo(8, 12);
                path.lineTo(14.5f, 18.5f);
                break;
            case WIDGETS:
                rr(4, 4, 20, 11, 2.5f);
                rr(4, 13.5f, 11, 20, 2.5f);
                rr(13.5f, 13.5f, 20, 20, 2.5f);
                break;
            case LIGHT:
                path.addCircle(12, 12, 3.5f, Path.Direction.CW);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    path.moveTo(12 + (float) Math.cos(a) * 6.5f, 12 + (float) Math.sin(a) * 6.5f);
                    path.lineTo(12 + (float) Math.cos(a) * 8.5f, 12 + (float) Math.sin(a) * 8.5f);
                }
                break;
            case PALETTE:
                path.addCircle(12, 12, 8.5f, Path.Direction.CW);
                path.addCircle(8.5f, 10, 1.1f, Path.Direction.CW);
                path.addCircle(12, 7.5f, 1.1f, Path.Direction.CW);
                path.addCircle(15.5f, 10, 1.1f, Path.Direction.CW);
                break;
            case CODE:
                path.moveTo(9, 7);
                path.lineTo(4, 12);
                path.lineTo(9, 17);
                path.moveTo(15, 7);
                path.lineTo(20, 12);
                path.lineTo(15, 17);
                break;
            case SHIELD:
                path.moveTo(12, 3.5f);
                path.lineTo(19, 6.5f);
                path.lineTo(19, 11.5f);
                path.quadTo(19, 17.5f, 12, 20.5f);
                path.quadTo(5, 17.5f, 5, 11.5f);
                path.lineTo(5, 6.5f);
                path.close();
                break;
            case DOWNLOAD:
                // flecha hacia abajo que cae en una bandeja
                path.moveTo(12, 4);
                path.lineTo(12, 14.5f);
                path.moveTo(7.5f, 10.5f);
                path.lineTo(12, 15);
                path.lineTo(16.5f, 10.5f);
                path.moveTo(4.5f, 15.5f);
                path.lineTo(4.5f, 18.5f);
                path.quadTo(4.5f, 20, 6, 20);
                path.lineTo(18, 20);
                path.quadTo(19.5f, 20, 19.5f, 18.5f);
                path.lineTo(19.5f, 15.5f);
                break;
            default:
                break;
        }
        c.drawPath(path, paint);
        c.restore();
    }

    private void rr(float l, float t, float rr, float b, float rad) {
        r.set(l, t, rr, b);
        path.addRoundRect(r, rad, rad, Path.Direction.CW);
    }

    @Override
    public void setAlpha(int a) {
        paint.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return -1;
    }

    @Override
    public int getIntrinsicHeight() {
        return -1;
    }
}
