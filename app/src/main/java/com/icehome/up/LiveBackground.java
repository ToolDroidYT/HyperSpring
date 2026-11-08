package com.icehome.up;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/**
 * Fondo vivo de las páginas de la app: una red de puntos que se mueven al azar y se unen con
 * líneas finas cuando se acercan. En «Acerca de», en su lugar, dos brillos de color que pasan
 * por detrás (coral, el del icono, por un lado y azul por el contrario).
 * <p>
 * Va de fondo del ScrollView de cada página: el fondo de una vista no se desplaza con el
 * contenido, así que queda fijo mientras las tarjetas pasan por encima, y la barra de cristal lo
 * ve (y lo refracta) porque es parte de la página. Todas las páginas comparten la misma red, así
 * que al cambiar de pestaña no salta. Un solo bucle de fotogramas para todas, que se para con la
 * app en segundo plano ({@link #pause()} / {@link #resume()}).
 */
final class LiveBackground extends Drawable {

    /** Color del icono. */
    static final int CORAL = 0xFFF0604D;
    /** El del lado contrario. */
    static final int BLUE = 0xFF4C6FFF;

    private static final String GLOW =
            "uniform float2 size;\n"
            + "uniform float time;\n"
            + "uniform half4 c1;\n"
            + "uniform half4 c2;\n"
            + "uniform half4 base;\n"
            + "half blob(float2 p, float2 c, float k) {\n"
            + "  float2 d = p - c;\n"
            + "  return half(exp(-dot(d, d) * k));\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "  float2 p = coord / size.x;\n"
            + "  float hgt = size.y / size.x;\n"
            + "  float t = time;\n"
            + "  p += 0.05 * float2(sin(p.y * 3.3 + t * 0.8), cos(p.x * 2.6 - t * 0.6));\n"
            // coral por la izquierda y azul por la derecha; los dos cruzan por detrás del logo
            + "  float2 a = float2(0.22 + 0.26 * sin(t * 0.31), hgt * (0.14 + 0.08 * cos(t * 0.27)));\n"
            + "  float2 b = float2(0.80 - 0.26 * sin(t * 0.23 + 1.3), hgt * (0.22 + 0.10 * sin(t * 0.35)));\n"
            + "  float2 a2 = float2(0.15 + 0.20 * sin(t * 0.19 + 2.0), hgt * (0.70 + 0.10 * cos(t * 0.29)));\n"
            + "  float2 b2 = float2(0.85 - 0.20 * sin(t * 0.17 + 0.4), hgt * (0.55 + 0.12 * sin(t * 0.21)));\n"
            + "  half wa = blob(p, a, 3.4), wb = blob(p, b, 3.0), wa2 = blob(p, a2, 3.6) * 0.45, wb2 = blob(p, b2, 3.2) * 0.45;\n"
            + "  half ka = wa + wa2, kb = wb + wb2;\n"
            + "  half qa = ka * ka, qb = kb * kb;\n"
            + "  half4 col = (c1 * qa + c2 * qb) / (qa + qb + 0.0001);\n"
            + "  half glow = clamp((ka + kb) * 1.1, 0.0, 1.0);\n"
            + "  col = mix(base, col, glow);\n"
            + "  half n = half(fract(sin(dot(coord, float2(12.9898, 78.233))) * 43758.5453) - 0.5);\n"
            + "  col.rgb += n * 0.010;\n"
            + "  return half4(col.rgb, 1.0);\n"
            + "}\n";

    // ------------------------------------------------------------------ red de puntos compartida

    private static final int N = 46;
    /** Distancia (en anchos de pantalla) a la que dos puntos se unen. */
    private static final float LINK = 0.27f;
    private static final float[] X = new float[N], Y = new float[N], VX = new float[N], VY = new float[N];
    private static final Random RND = new Random();
    private static float aspect = 2.2f;
    private static boolean seeded;
    private static long last;
    private static float time;

    private static void seed(float asp) {
        aspect = asp;
        for (int i = 0; i < N; i++) {
            X[i] = RND.nextFloat();
            Y[i] = RND.nextFloat() * aspect;
            double ang = RND.nextDouble() * Math.PI * 2;
            float sp = 0.018f + RND.nextFloat() * 0.022f;          // anchos de pantalla por segundo
            VX[i] = (float) Math.cos(ang) * sp;
            VY[i] = (float) Math.sin(ang) * sp;
        }
        seeded = true;
    }

    private static void step(float dt) {
        for (int i = 0; i < N; i++) {
            // Paseo al azar: el rumbo gira un poco cada fotograma; la velocidad se mantiene.
            float turn = (RND.nextFloat() - 0.5f) * 1.6f * dt;
            float c = (float) Math.cos(turn), s = (float) Math.sin(turn);
            float vx = VX[i] * c - VY[i] * s, vy = VX[i] * s + VY[i] * c;
            VX[i] = vx;
            VY[i] = vy;
            X[i] += vx * dt;
            Y[i] += vy * dt;
            if (X[i] < -0.05f && VX[i] < 0 || X[i] > 1.05f && VX[i] > 0) VX[i] = -VX[i];
            if (Y[i] < -0.05f && VY[i] < 0 || Y[i] > aspect + 0.05f && VY[i] > 0) VY[i] = -VY[i];
        }
    }

    // ------------------------------------------------------------------ bucle común

    private static final ArrayList<LiveBackground> LIVE = new ArrayList<>();
    private static Runnable onFrame;
    private static boolean running, paused;
    private static final Choreographer.FrameCallback LOOP = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            if (!running) return;
            float dt = last == 0 ? 0f : Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            time += dt * 2.6f;
            if (seeded) step(dt);
            for (int i = 0; i < LIVE.size(); i++) LIVE.get(i).invalidateSelf();
            if (onFrame != null) onFrame.run();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /** Lo que hay que redibujar además en cada fotograma (la barra de cristal, que lo refracta). */
    static void setOnFrame(Runnable r) {
        onFrame = r;
    }

    static void pause() {
        paused = true;
        update();
    }

    static void resume() {
        paused = false;
        update();
    }

    private static void update() {
        boolean want = !paused && !LIVE.isEmpty();
        if (want == running) return;
        running = want;
        last = 0;
        if (want) Choreographer.getInstance().postFrameCallback(LOOP);
        else Choreographer.getInstance().removeFrameCallback(LOOP);
    }

    // ------------------------------------------------------------------ cada página

    private final int base;
    private final float dp;
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            glowPaint = new Paint();
    private RuntimeShader glow;
    private final boolean about;
    private final Paint[] blobs = new Paint[2];
    private final android.graphics.Matrix m = new android.graphics.Matrix();

    /**
     * @param withGlow los brillos de color de «Acerca de»
     */
    LiveBackground(int base, float dp, boolean withGlow) {
        this.base = base;
        this.dp = dp;
        this.about = withGlow;
        dot.setColor(0xFFFFFFFF);
        line.setColor(0xFFFFFFFF);
        line.setStrokeWidth(Math.max(1f, 0.8f * dp));
        if (withGlow && Build.VERSION.SDK_INT >= 33) {
            try {
                glow = new RuntimeShader(GLOW);
                glow.setColorUniform("c1", blend(base, CORAL, 0.62f));
                glow.setColorUniform("c2", blend(base, BLUE, 0.55f));
                glow.setColorUniform("base", base);
                glowPaint.setShader(glow);
            } catch (Throwable t) {
                glow = null;
            }
        }
        if (withGlow && glow == null) {
            // Sin AGSL: dos degradados radiales que se mueven.
            int[] cols = {blend(base, CORAL, 0.62f), blend(base, BLUE, 0.55f)};
            for (int i = 0; i < 2; i++) {
                blobs[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                blobs[i].setShader(new android.graphics.RadialGradient(0, 0, 1f, cols[i], cols[i] & 0x00FFFFFF,
                        android.graphics.Shader.TileMode.CLAMP));
            }
        }
    }

    /** Pone el fondo en la página y lo anima solo mientras está en pantalla. */
    void attachTo(View page) {
        page.setBackground(this);
        page.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (!LIVE.contains(LiveBackground.this)) LIVE.add(LiveBackground.this);
                update();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                LIVE.remove(LiveBackground.this);
                update();
            }
        });
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float w = b.width(), h = b.height();
        if (w <= 0 || h <= 0) return;
        if (!seeded) seed(h / w);
        if (glow != null) {
            glow.setFloatUniform("size", w, h);
            glow.setFloatUniform("time", time);
            canvas.drawRect(b, glowPaint);
        } else {
            canvas.drawColor(base);
            if (blobs[0] != null) {
                float[][] at = {
                        {0.22f + 0.26f * (float) Math.sin(time * 0.31), 0.14f * h / w + 0.08f * (float) Math.cos(time * 0.27)},
                        {0.80f - 0.26f * (float) Math.sin(time * 0.23 + 1.3), 0.22f * h / w + 0.10f * (float) Math.sin(time * 0.35)}};
                for (int i = 0; i < 2; i++) {
                    m.setScale(w * 0.75f, w * 0.75f);
                    m.postTranslate(b.left + at[i][0] * w, b.top + at[i][1] * w);
                    blobs[i].getShader().setLocalMatrix(m);
                    canvas.drawRect(b, blobs[i]);
                }
            }
        }
        if (about) return;                                         // «Acerca de»: solo los brillos
        float sy = h / (aspect * w);                               // por si esta página mide distinto
        for (int i = 0; i < N; i++) {
            float xi = b.left + X[i] * w, yi = b.top + Y[i] * w * sy;
            for (int j = i + 1; j < N; j++) {
                float dx = X[i] - X[j], dy = (Y[i] - Y[j]) * sy;
                float d2 = dx * dx + dy * dy;
                if (d2 >= LINK * LINK) continue;
                float f = 1f - (float) Math.sqrt(d2) / LINK;
                line.setAlpha((int) (f * 72));
                canvas.drawLine(xi, yi, b.left + X[j] * w, b.top + Y[j] * w * sy, line);
            }
        }
        for (int i = 0; i < N; i++) {
            float xi = b.left + X[i] * w, yi = b.top + Y[i] * w * sy;
            dot.setAlpha(34);
            canvas.drawCircle(xi, yi, 4.5f * dp, dot);             // halo
            dot.setAlpha(140);
            canvas.drawCircle(xi, yi, 1.9f * dp, dot);
        }
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
