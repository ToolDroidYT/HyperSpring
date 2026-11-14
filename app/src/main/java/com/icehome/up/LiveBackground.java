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

/**
 * Fondo vivo de las páginas de la app: cuatro manchas de color (sacadas de Monet, el color del
 * sistema) que se mezclan entre sí y se desplazan despacio, como un «mesh gradient».
 * <p>
 * Dos modos:
 * <ul>
 * <li><b>Normal</b> (listas de ajustes): las manchas se quedan en la parte de arriba y se funden
 * con el fondo hacia abajo, para que las tarjetas se lean bien.</li>
 * <li><b>Libre</b> («Acerca de» y la bienvenida): las manchas recorren toda la pantalla, cada una
 * por su propio camino (curvas de Lissajous: un seno en x y otro de distinta velocidad en y, así
 * nunca repiten el mismo recorrido a la vez).</li>
 * </ul>
 * Va de fondo del ScrollView de cada página: el fondo de una vista no se desplaza con el
 * contenido, así que queda fijo mientras las tarjetas pasan por encima, y la barra de cristal lo
 * ve (y lo refracta) porque es parte de la página. Un solo bucle de fotogramas para todas, que se
 * para con la app en segundo plano ({@link #pause()} / {@link #resume()}).
 */
final class LiveBackground extends Drawable {

    /** Color del icono. */
    static final int CORAL = 0xFFF0604D;

    private static final String MESH =
            "uniform float2 size;\n"
            + "uniform float time;\n"
            + "uniform float roam;\n"
            + "uniform float amount;\n"
            // layout(color): Android los convierte al espacio de color de la pantalla (y sin esto
            // setColorUniform falla y el fondo cae en el plan B)
            + "layout(color) uniform half4 c1;\n"
            + "layout(color) uniform half4 c2;\n"
            + "layout(color) uniform half4 c3;\n"
            + "layout(color) uniform half4 c4;\n"
            + "layout(color) uniform half4 base;\n"
            + "half blob(float2 p, float2 c, float k) {\n"
            + "  float2 d = p - c;\n"
            + "  return half(exp(-dot(d, d) * k));\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "  float2 p = coord / size.x;\n"
            + "  float hgt = size.y / size.x;\n"
            + "  float t = time;\n"
            // ondulación suave del espacio: los bordes de las manchas no son círculos perfectos
            + "  p += 0.05 * float2(sin(p.y * 3.3 + t * 0.8), cos(p.x * 2.6 - t * 0.6));\n"
            // libre: centro a media pantalla y recorrido de arriba abajo; normal: solo la parte de arriba
            + "  float yc = hgt * mix(0.20, 0.50, roam);\n"
            + "  float ya = hgt * mix(0.16, 0.44, roam);\n"
            + "  float xa = mix(0.30, 0.44, roam);\n"
            + "  float2 a = float2(0.5 + xa * sin(t * 0.21), yc + ya * sin(t * 0.17 + 1.0));\n"
            + "  float2 b = float2(0.5 + xa * sin(t * 0.16 + 2.1), yc + ya * cos(t * 0.13 + 0.3));\n"
            + "  float2 c = float2(0.5 + xa * cos(t * 0.19 + 4.0), yc + ya * sin(t * 0.11 + 2.6));\n"
            + "  float2 d = float2(0.5 + xa * sin(t * 0.13 + 5.2), yc + ya * cos(t * 0.15 + 4.4));\n"
            + "  float k = mix(3.2, 2.2, roam);\n"
            + "  half wa = blob(p, a, k), wb = blob(p, b, k), wc = blob(p, c, k * 1.15), wd = blob(p, d, k * 1.3);\n"
            // cada punto toma sobre todo el color de la mancha más cercana (pesos al cuadrado)
            + "  half qa = wa * wa, qb = wb * wb, qc = wc * wc, qd = wd * wd;\n"
            + "  half4 col = (c1 * qa + c2 * qb + c3 * qc + c4 * qd) / (qa + qb + qc + qd + 0.0001);\n"
            + "  half glow = clamp((wa + wb + wc + wd) * 1.2, 0.0, 1.0) * half(amount);\n"
            + "  col = mix(base, col, glow);\n"
            // grano finísimo: evita las franjas de un degradado tan suave
            + "  half n = half(fract(sin(dot(coord, float2(12.9898, 78.233))) * 43758.5453) - 0.5);\n"
            + "  col.rgb += n * 0.012;\n"
            + "  return half4(col.rgb, 1.0);\n"
            + "}\n";

    /**
     * Curvas de nivel que fluyen sin fin, como un mapa topográfico vivo. Un «campo» suave (suma
     * de senos que se deforman unos a otros) se mueve con el tiempo; se dibuja una línea fina allí
     * donde el campo cruza un número entero. Como el tiempo solo avanza, nunca se repite.
     * El grosor se mide en píxeles reales: se mira cuánto cambia el campo de un píxel al de al
     * lado (AGSL no tiene fwidth), y así las líneas no engordan donde el campo cambia despacio.
     */
    private static final String LINES =
            "uniform float2 size;\n"
            + "uniform float time;\n"
            + "uniform float alpha;\n"
            + "uniform float lineW;\n"
            + "layout(color) uniform half4 c1;\n"
            + "layout(color) uniform half4 c2;\n"
            + "layout(color) uniform half4 base;\n"
            + "float field(float2 p, float t) {\n"
            + "  return sin(p.x * 1.6 + t * 0.21 + 1.3 * sin(p.y * 1.1 - t * 0.17))\n"
            + "       + sin(p.y * 1.9 - t * 0.15 + 1.1 * sin(p.x * 1.4 + t * 0.12))\n"
            + "       + 0.6 * sin((p.x + p.y) * 2.3 + t * 0.10);\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "  float k = 3.0 / size.x;\n"
            + "  float2 p = coord * k;\n"
            + "  float t = time;\n"
            + "  float v = field(p, t) * 2.0;\n"
            + "  float vx = field(p + float2(k, 0.0), t) * 2.0 - v;\n"
            + "  float vy = field(p + float2(0.0, k), t) * 2.0 - v;\n"
            + "  float g = max(length(float2(vx, vy)), 0.0001);\n"
            // distancia (en píxeles) a la curva de nivel más cercana
            + "  float dist = abs(fract(v + 0.5) - 0.5) / g;\n"
            + "  half line = half(1.0 - smoothstep(lineW, lineW + 1.2, dist));\n"
            + "  half4 lc = mix(c1, c2, half(clamp(coord.x / size.x * 0.7 + coord.y / size.y * 0.3, 0.0, 1.0)));\n"
            // algo más visibles arriba, más tranquilas abajo, donde se lee
            + "  half fade = half(mix(1.0, 0.5, clamp(coord.y / size.y, 0.0, 1.0)));\n"
            + "  half4 col = mix(base, lc, line * half(alpha) * fade);\n"
            + "  return half4(col.rgb, 1.0);\n"
            + "}\n";

    // ------------------------------------------------------------------ bucle común

    private static final ArrayList<LiveBackground> LIVE = new ArrayList<>();
    private static Runnable onFrame;
    private static boolean running, paused;
    private static long last;
    private static float time;
    private static final Choreographer.FrameCallback LOOP = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            if (!running) return;
            float dt = last == 0 ? 0f : Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            time += dt * 1.6f;                                     // lento: se nota, pero no distrae
            for (int i = 0; i < LIVE.size(); i++) LIVE.get(i).invalidateSelf();
            if (onFrame != null) onFrame.run();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /** Lo que hay que redibujar además en cada fotograma (el cristal que refracta el fondo). */
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
    private final int[] cols;
    private final float amount;
    private final boolean roam;
    private final Paint paint = new Paint();
    private RuntimeShader mesh;
    private final Paint[] blobs = new Paint[4];
    private final android.graphics.Matrix m = new android.graphics.Matrix();

    /**
     * @param base   color de fondo donde no llegan las manchas
     * @param colors los cuatro colores de las manchas (se repiten si hay menos)
     * @param amount cuánto color se ve (0 = solo el fondo, 1 = las manchas puras)
     * @param roam   true: recorren toda la pantalla; false: se quedan arriba
     */
    LiveBackground(int base, int[] colors, float amount, boolean roam) {
        this.base = base;
        this.amount = amount;
        this.roam = roam;
        cols = new int[4];
        for (int i = 0; i < 4; i++) cols[i] = colors[i % colors.length];
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                mesh = new RuntimeShader(MESH);
                mesh.setColorUniform("c1", cols[0]);
                mesh.setColorUniform("c2", cols[1]);
                mesh.setColorUniform("c3", cols[2]);
                mesh.setColorUniform("c4", cols[3]);
                mesh.setColorUniform("base", base);
                mesh.setFloatUniform("roam", roam ? 1f : 0f);
                mesh.setFloatUniform("amount", amount);
                paint.setShader(mesh);
            } catch (Throwable t) {
                android.util.Log.w("HyperSpring", "fondo: el sombreador no compila", t);
                mesh = null;
            }
        }
        if (mesh == null) {
            // Sin AGSL: cuatro degradados radiales que se mueven.
            for (int i = 0; i < 4; i++) {
                int c = (Math.round(255 * amount * 0.8f) << 24) | (cols[i] & 0x00FFFFFF);
                blobs[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                blobs[i].setShader(new android.graphics.RadialGradient(0, 0, 1f, c, c & 0x00FFFFFF,
                        android.graphics.Shader.TileMode.CLAMP));
            }
        }
    }

    /** Para {@link #lines}: solo el color de fondo, el sombreador se pone después. */
    private LiveBackground(int base) {
        this.base = base;
        this.amount = 0f;
        this.roam = false;
        this.cols = null;
    }

    /**
     * Fondo de las páginas de ajustes: curvas de nivel finas que fluyen sin fin.
     *
     * @param alpha cuánto se ven las líneas (0-1)
     * @param dp    densidad de la pantalla, para el grosor de las líneas
     */
    static LiveBackground lines(int base, int c1, int c2, float alpha, float dp) {
        LiveBackground lb = new LiveBackground(base);
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                RuntimeShader s = new RuntimeShader(LINES);
                s.setColorUniform("c1", c1);
                s.setColorUniform("c2", c2);
                s.setColorUniform("base", base);
                s.setFloatUniform("alpha", alpha);
                s.setFloatUniform("lineW", 0.45f * dp);
                lb.mesh = s;
                lb.paint.setShader(s);
            } catch (Throwable t) {
                android.util.Log.w("HyperSpring", "fondo de líneas: el sombreador no compila", t);
            }
        }
        return lb;                                   // sin AGSL: color liso
    }

    /** Pone el fondo en la vista y lo anima solo mientras está en pantalla. */
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
        if (mesh != null) {
            mesh.setFloatUniform("size", w, h);
            mesh.setFloatUniform("time", time);
            canvas.drawRect(b, paint);
            return;
        }
        canvas.drawColor(base);
        if (cols == null) return;                    // líneas sin AGSL: color liso
        float hgt = h / w, r = roam ? 1f : 0f;
        float yc = hgt * Motion.lerp(0.20f, 0.50f, r), ya = hgt * Motion.lerp(0.16f, 0.44f, r), xa = Motion.lerp(0.30f, 0.44f, r);
        double t = time;
        float[][] at = {
                {0.5f + xa * (float) Math.sin(t * 0.21), yc + ya * (float) Math.sin(t * 0.17 + 1.0)},
                {0.5f + xa * (float) Math.sin(t * 0.16 + 2.1), yc + ya * (float) Math.cos(t * 0.13 + 0.3)},
                {0.5f + xa * (float) Math.cos(t * 0.19 + 4.0), yc + ya * (float) Math.sin(t * 0.11 + 2.6)},
                {0.5f + xa * (float) Math.sin(t * 0.13 + 5.2), yc + ya * (float) Math.cos(t * 0.15 + 4.4)}};
        for (int i = 0; i < 4; i++) {
            m.setScale(w * 0.7f, w * 0.7f);
            m.postTranslate(b.left + at[i][0] * w, b.top + at[i][1] * w);
            blobs[i].getShader().setLocalMatrix(m);
            canvas.drawRect(b, blobs[i]);
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
