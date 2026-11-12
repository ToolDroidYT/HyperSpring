package com.icehome.up;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;
import android.view.SurfaceControl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Dibujo del genio con OpenGL ES en un hilo y contexto propios.
 *
 * Todo lo que dibuja el launcher (HWUI) pasa por un único hilo de render, y mientras abre o cierra
 * una app ese hilo está ocupado con su desenfoque y sus vistas: dibujar ahí costaba 15-50 ms por
 * fotograma en un móvil modesto. Con un contexto propio el genio no espera a nadie.
 *
 * <ul>
 * <li>Salida: una capa hija de la ventana del launcher, a la resolución del ajuste (50-100 %),
 *     que el compositor escala a pantalla completa.</li>
 * <li>Contenido: las capturas de la ventana entran sin copias en una {@link SurfaceTexture}
 *     (textura externa), como hace Android con la animación de girar la pantalla. Antes de la
 *     primera captura, al abrir, el color de la app con su icono en el centro, hecho en el propio
 *     shader (sin crear ni subir imágenes al empezar).</li>
 * <li>Forma: un solo fragment shader, la misma deformación analítica e invertible de siempre.</li>
 * </ul>
 *
 * Todo en el hilo de dibujo, salvo {@link #queue}, que llama el hilo de capturas.
 */
final class GlRenderer {

    /** Resolución pedida en ajustes (respecto a la pantalla); se aplica al empezar una animación. */
    static volatile float wantedScale = 0.75f;
    /** Resolución de la capa actual: dibujo y capturas (solo hilo de dibujo, salvo lectura). */
    static volatile float scale = 0.75f;

    /** Filas de la malla del genio: la curva de los bordes se calcula una vez por fila, no por píxel. */
    static final int ROWS = 64;

    /**
     * Deformación "genio", en coordenadas de pantalla. Cada fila (s = 0 arriba, 1 abajo) está a
     * n del extremo lejano (0) al cercano al icono (1): con el icono abajo n = s; con el icono
     * arriba la copa se da la vuelta, n = 1 - s ({@code flip}, que pasa de uno a otro sin saltos
     * según la altura del icono). Su progreso de anchura es k = pw^e(n): e va de g.y (< 1, el
     * extremo lejano se adelanta al muelle de MIUI) a g.z (> 1, el cercano se queda pegado al
     * icono). La anchura de la fila va de la del icono a la de la pantalla con k; su centro, del
     * del muelle hacia el del icono con un peso pw^((g.z - 1)·n²), curva suave sin esquinas (con
     * una esquina, al cerrar en diagonal la copa se doblaba como una camiseta). Y la inclinación
     * de la lámpara ({@code bend}, repartida en curva desde el extremo cercano).
     *
     * Todo eso solo depende de la fila: lo calcula el vertex shader en una malla de {@link #ROWS}
     * filas que sigue el contorno del genio (con 2 px de margen para el antialias). Así la GPU no
     * repite potencias en cada píxel y solo pinta los píxeles del genio, no la pantalla entera.
     * Al fragment shader le llegan los bordes de su fila (vE) y la corrección por su pendiente (vD).
     * aRow = (posición de la fila 0..1, lado 0 = izquierdo / 1 = derecho).
     */
    /**
     * Profundidad: la ventana es una lámina que se inclina y se curva hacia el fondo, vista con
     * una cámara. A una altura y0 de la lámina (px desde su centro, sin perspectiva) está a
     * z = persp.z + persp.x·y0 + persp.y·y0² más lejos (en fracción de la distancia a la cámara):
     * persp.x la inclina (arriba atrás o abajo atrás) y persp.y dobla los dos extremos hacia
     * atrás, como una hoja que se curva. persp.z la empuja entera lo justo para que ningún punto
     * quede más cerca que el rectángulo de MIUI: la ventana solo se aleja, nunca se sale de la
     * pantalla. A esa altura todo se ve 1 / (1 + z) de grande. {@code unproject} es la cuenta al
     * revés (de la pantalla a la lámina), exacta con la fórmula de segundo grado en su forma
     * estable (sin dividir por persp.y, que puede ser 0).
     */
    private static final String PERSP =
            "float shrink(float y0) { return 1.0 / (1.0 + persp.z + persp.x * y0 + persp.y * y0 * y0); }\n"
            + "float project(float y0) { return y0 * shrink(y0); }\n"
            + "float unproject(float yy) {\n"
            + "  float a = 1.0 - yy * persp.x, b = 1.0 + persp.z;\n"
            + "  return 2.0 * yy * b / max(a + sqrt(max(a * a - 4.0 * yy * yy * persp.y * b, 0.0)), 0.001);\n"
            + "}\n";

    private static final String VERTEX =
            "attribute vec2 aRow;\n"
            + "uniform vec4 cur;\n"
            + "uniform vec4 icon;\n"
            + "uniform vec4 g;\n"
            + "uniform float fullHalf;\n"
            + "uniform float bend;\n"
            + "uniform float flip;\n"
            + "uniform float cup;\n"
            + "uniform float scale;\n"
            + "uniform vec2 buf;\n"
            + "uniform vec3 persp;\n"
            + "uniform vec4 vel;\n"
            + "varying vec2 vE;\n"
            + "varying vec2 vD;\n"
            + PERSP
            + "vec2 row(float s) {\n"
            + "  float q = max(g.x, 0.0001);\n"
            + "  float n = mix(s, 1.0 - s, flip);\n"
            // Copa: extremo lejano adelantado (1-(1-q)^g.y) y cercano retrasado (q^g.z), las dos
            // apagándose cerca del icono (env, como Genie.CUP_FADE = 0,12); entre los dos,
            // lados casi rectos con hombros suaves. Lo que el dedo desplaza el rectángulo de MIUI
            // (off) se suma, menos cerca del icono.
            + "  if (cup > 0.5) {\n"
            + "    float env = smoothstep(0.0, 0.12, q);\n"
            + "    float kf = mix(q, 1.0 - pow(1.0 - q, g.y), env), kn = mix(q, pow(q, g.z), env);\n"
            + "    float ic = (icon.x + icon.z) * 0.5, ih = (icon.z - icon.x) * 0.5;\n"
            + "    float off = (cur.x + cur.z) * 0.5 - mix(ic, fullHalf, q);\n"
            + "    float pr = mix(n, n * n * (3.0 - 2.0 * n), 0.5);\n"
            + "    float hc = mix(mix(ih, fullHalf, kf), mix(ih, fullHalf, kn), pr);\n"
            + "    float cc = mix(mix(ic, fullHalf, kf), mix(ic, fullHalf, kn), pr) + off * (1.0 - 0.5 * pr);\n"
            + "    cc += bend * (1.0 - n) * (1.0 - n);\n"
            + "    return vec2(cc - hc, cc + hc);\n"
            + "  }\n"
            // Genio clásico: la misma desaparición cerca del icono (Genie.shape) para salir
            // del icono y volver a él sin saltos.
            + "  float envc = smoothstep(0.0, 0.12, q);\n"
            + "  float k = mix(q, pow(q, mix(g.y, g.z, n)), envc);\n"
            + "  float w = mix(1.0, pow(q, max(g.z - 1.0, 0.0) * n * n), envc);\n"
            + "  float c = mix((icon.x + icon.z) * 0.5, (cur.x + cur.z) * 0.5, w);\n"
            + "  c += bend * (1.0 - n) * (1.0 - n);\n"
            + "  float h = mix((icon.z - icon.x) * 0.5, fullHalf, k);\n"
            + "  return vec2(c - h, c + h);\n"
            + "}\n"
            + "void main() {\n"
            + "  float h = max(cur.w - cur.y, 1.0);\n"
            // Margen de la malla: 2 px de antialias, más la mitad de la estela del desenfoque
            // (si no, el borde desenfocado quedaría cortado).
            + "  float mb = 2.0 + 0.5 * max(max(abs(vel.x), abs(vel.y)), max(abs(vel.z), abs(vel.w)));\n"
            // Profundidad (cup = 2): la lámina inclinada y curvada, vista en perspectiva. Cada fila
            // de la malla es una altura de la pantalla; su anchura es la de la lámina a esa altura
            // (más lejos, más estrecha). El fragment shader hace la cuenta exacta en cada píxel.
            + "  if (cup > 1.5) {\n"
            + "    float cx = (cur.x + cur.z) * 0.5, cy = (cur.y + cur.w) * 0.5;\n"
            + "    float hw = (cur.z - cur.x) * 0.5, hh = h * 0.5;\n"
            + "    float yt = project(-hh), yb = project(hh);\n"
            + "    float yd = cy + mix(yt - mb, yb + mb, aRow.x);\n"
            + "    float y0 = clamp(unproject(yd - cy), -hh, hh);\n"
            + "    float xd = cx + (aRow.y < 0.5 ? -1.0 : 1.0) * (hw * shrink(y0) + mb);\n"
            + "    vE = vec2(0.0);\n"
            + "    vD = vec2(1.0);\n"
            + "    gl_Position = vec4(xd * scale / buf.x * 2.0 - 1.0, 1.0 - 2.0 * yd * scale / buf.y, 0.0, 1.0);\n"
            + "    return;\n"
            + "  }\n"
            + "  float y = cur.y - mb + aRow.x * (h + 2.0 * mb);\n"
            + "  float s = clamp((y - cur.y) / h, 0.0, 1.0);\n"
            + "  vec2 e = row(s);\n"
            + "  vec2 e2 = row(clamp(s + 1.0 / h, 0.0, 1.0)) - e;\n"
            + "  vE = e;\n"
            + "  vD = vec2(inversesqrt(1.0 + e2.x * e2.x), inversesqrt(1.0 + e2.y * e2.y));\n"
            + "  float x = aRow.y < 0.5 ? e.x - mb : e.y + mb;\n"
            + "  gl_Position = vec4(x * scale / buf.x * 2.0 - 1.0, 1.0 - 2.0 * y * scale / buf.y, 0.0, 1.0);\n"
            + "}\n";

    /**
     * Cada píxel: distancia al borde (esquinas redondeadas, antialias corregido por la pendiente),
     * contenido en (u, s), debajo el color de la app y encima, fundido, el icono ({@code iconMix}):
     * el genio sale del icono y vuelve a ser el icono. g = (pw, ex.x, ex.y, radio).
     */
    static final String FRAGMENT_BODY =
            "uniform vec4 cur;\n"
            + "uniform vec4 g;\n"
            + "uniform float iconMix;\n"
            + "uniform vec4 bg;\n"
            + "uniform float scale;\n"
            + "uniform float bufH;\n"
            // Profundidad: persp = inclinación, curvatura y empuje (ver PERSP); vel = lo que se mueve cada
            // borde (izquierdo, arriba, derecho, abajo) mientras «el obturador está abierto».
            // En los demás estilos los dos son 0 y todo queda como antes.
            + "uniform float cup;\n"
            + "uniform vec3 persp;\n"
            + "uniform vec4 vel;\n"
            + "varying vec2 vE;\n"
            + "varying vec2 vD;\n"
            + PERSP
            + "void main() {\n"
            + "  vec2 p = vec2(gl_FragCoord.x, bufH - gl_FragCoord.y) / scale;\n"
            + "  float wE, h, u, s, d;\n"
            + "  if (cup > 1.5) {\n"
            // Profundidad: de la pantalla al punto de la lámina (y0, x0), y allí, como siempre,
            // la distancia al borde redondeado. Se pasa a píxeles de pantalla con la escala de
            // esa altura (k), para que el antialias sea de un píxel también lejos.
            + "    float cx = (cur.x + cur.z) * 0.5, cy = (cur.y + cur.w) * 0.5;\n"
            + "    float hw = max((cur.z - cur.x) * 0.5, 0.5), hh = max((cur.w - cur.y) * 0.5, 0.5);\n"
            + "    float y0 = unproject(p.y - cy);\n"
            + "    float k = shrink(y0);\n"
            + "    float x0 = (p.x - cx) / k;\n"
            + "    wE = 2.0 * hw * k;\n"
            + "    h = 2.0 * hh * k;\n"
            + "    u = clamp(x0 / (2.0 * hw) + 0.5, 0.0, 1.0);\n"
            + "    s = clamp(y0 / (2.0 * hh) + 0.5, 0.0, 1.0);\n"
            + "    float dx = hw - abs(x0), dy = hh - abs(y0);\n"
            + "    float rad = min(g.w, min(hw, hh));\n"
            + "    d = ((dx < rad && dy < rad) ? rad - length(vec2(rad - dx, rad - dy)) : min(dx, dy)) * k;\n"
            + "  } else {\n"
            + "    wE = max(vE.y - vE.x, 1.0);\n"
            + "    u = clamp((p.x - vE.x) / wE, 0.0, 1.0);\n"
            + "    h = max(cur.w - cur.y, 1.0);\n"
            + "    s = clamp((p.y - cur.y) / h, 0.0, 1.0);\n"
            + "    float dx = min((p.x - vE.x) * vD.x, (vE.y - p.x) * vD.y);\n"
            + "    float dy = min(p.y - cur.y, cur.w - p.y);\n"
            + "    float rad = min(g.w, min(wE * 0.5, h * 0.5));\n"
            + "    d = (dx < rad && dy < rad) ? rad - length(vec2(rad - dx, rad - dy)) : min(dx, dy);\n"
            + "  }\n"
            // Lo que se mueve este punto: mezcla de la velocidad de los bordes según dónde está.
            // Así, al crecer, cada borde se desenfoca hacia fuera (un zoom) y el centro casi nada.
            + "  vec2 mv = vec2(mix(vel.x, vel.z, u), mix(vel.y, vel.w, s));\n"
            + "  float ml = length(mv);\n"
            // El borde también se desenfoca: el antialias se ensancha con la estela.
            + "  float a = clamp(d / max(1.0 / scale, ml * 0.5) + 0.5, 0.0, 1.0);\n"
            + "  if (a <= 0.0) { gl_FragColor = vec4(0.0); return; }\n"
            + "  vec4 col;\n"
            + "  if (ml * scale < 1.5) {\n"
            + "    col = CONTENT(u, s);\n"
            + "  } else {\n"
            // Desenfoque de movimiento: 8 muestras a lo largo de la estela, centradas en el punto
            // (de −0,5 a +0,5 del recorrido). Es lo que haría una cámara con el obturador abierto.
            + "    vec2 duv = mv / vec2(wE, h);\n"
            + "    col = vec4(0.0);\n"
            + "    for (int i = 0; i < 8; i++) {\n"
            + "      float t = float(i) / 7.0 - 0.5;\n"
            + "      col += CONTENT(clamp(u - duv.x * t, 0.0, 1.0), clamp(s - duv.y * t, 0.0, 1.0));\n"
            + "    }\n"
            + "    col *= 0.125;\n"
            + "  }\n"
            + "  col = col + bg * (1.0 - col.a);\n"
            + "  if (iconMix > 0.0) { col = mix(col, texture2D(iconTex, vec2(u, s)), iconMix); }\n"
            + "  gl_FragColor = col * a;\n"
            + "}\n";

    /** Contenido = captura de la ventana (textura externa, con la transformación de SurfaceTexture). */
    /** Pantalla provisional: icono centrado (splash = media anchura en u, ídem en v, 1 si hay icono). */
    private static final String SPLASH_AT =
            "vec4 splashAt(vec2 uv) {\n"
            + "  vec2 q = (uv - 0.5) / (2.0 * splash.xy) + 0.5;\n"
            + "  vec2 inside = step(0.0, q) * step(q, vec2(1.0));\n"
            + "  return texture2D(iconTex, clamp(q, 0.0, 1.0)) * (inside.x * inside.y * splash.z);\n"
            + "}\n";

    /**
     * Contenido = captura de la ventana. Al llegar la primera (al abrir), {@code fade} la funde
     * desde la pantalla provisional en unos fotogramas, sin cambio en seco.
     */
    static final String FRAGMENT_WINDOW =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision highp float;\n"
            + "uniform samplerExternalOES content;\n"
            + "uniform mat4 texM;\n"
            + "uniform sampler2D iconTex;\n"
            + "uniform vec3 splash;\n"
            + "uniform float fade;\n"
            + SPLASH_AT
            + "vec4 windowAt(vec2 uv) {\n"
            + "  vec4 w = texture2D(content, (texM * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy);\n"
            + "  return fade >= 1.0 ? w : mix(splashAt(uv), w, fade);\n"
            + "}\n"
            + "#define CONTENT(u, v) windowAt(vec2(u, v))\n";

    /**
     * Contenido = pantalla de inicio provisional: transparente (queda el color de la app) con el
     * icono en el centro. splash = (media anchura del icono en u, ídem en v, 1 si hay icono).
     */
    static final String FRAGMENT_SPLASH =
            "precision highp float;\n"
            + "uniform sampler2D iconTex;\n"
            + "uniform vec3 splash;\n"
            + SPLASH_AT
            + "#define CONTENT(u, v) splashAt(vec2(u, v))\n";

    /** Geometría de un instante (coordenadas de pantalla). */
    static final class Geo {
        final RectF cur = new RectF(), icon = new RectF();
        float pw, lead, lag, radius, iconMix;
        /** Pantalla provisional: icono de este tamaño (fracción del ancho) en el centro; 0 = sin icono. */
        float splashIcon;
        /** Físicas de lámpara: desplazamiento horizontal (px) del extremo lejano. */
        float bend;
        /** 0 = icono abajo (la copa se estrecha hacia abajo), 1 = icono arriba (al revés). */
        float flip;
        /** 1 = forma de copa; 0 = genio clásico. */
        float cup;
        /** Con captura: 1 = solo la captura; menos, fundida con la pantalla provisional. */
        float fade = 1f;
        /** Profundidad: inclinación (1/px), curvatura (1/px²) y empuje hacia el fondo de la lámina (ver PERSP). 0 = plana. */
        float tilt, curl, push;
        /**
         * Profundidad, antes del muelle (Genie): inclinación pedida por la velocidad, con la que
         * sale al abrir, y cuánto se deja (0 = plana: junto al icono o a pantalla completa).
         */
        float tiltTarget, tiltStart, tiltEnv = 1f;
        /** Profundidad: inclinación máxima que cabe sin que el borde cercano se salga de la pantalla. */
        float tiltRoom = 1f;
        /** Profundidad: lo que se mueve cada borde (izq., arriba, der., abajo) con el obturador abierto, en px. */
        final float[] vel = new float[4];
    }

    static String fragment(boolean window) {
        return (window ? FRAGMENT_WINDOW : FRAGMENT_SPLASH) + FRAGMENT_BODY;
    }

    private static EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private static EGLContext context = EGL14.EGL_NO_CONTEXT;
    private static EGLConfig config;
    private static EGLSurface window = EGL14.EGL_NO_SURFACE;
    /** [ventana, pantalla de inicio] */
    private static final Program[] programs = new Program[2];
    /**
     * Contenido de un genio: la captura de su ventana (textura externa alimentada sin copias por
     * una SurfaceTexture) y su icono. Las capturas entran siempre en {@link #main}; un genio que
     * sigue solo (el cierre de una app mientras se abre otra) se queda con el suyo
     * ({@link #detach}) y el principal pasa a ser uno nuevo.
     */
    static final class Content {
        int windowTexture, iconTexture;
        /** De quién es el icono que hay en la textura (una animación, o la precarga). */
        volatile Object iconOwner;
        /** De quién es la imagen que hay en la textura de la ventana. */
        volatile Object windowOwner;
        SurfaceTexture input;
        Surface inputSurface;
        final float[] texMatrix = new float[16];

        /** Solo hilo de dibujo, con el contexto actual. */
        static Content create() {
            Content c = new Content();
            int[] tex = new int[2];
            GLES20.glGenTextures(2, tex, 0);
            c.windowTexture = tex[0];
            c.iconTexture = tex[1];
            setupTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, c.windowTexture);
            setupTexture(GLES20.GL_TEXTURE_2D, c.iconTexture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
            c.input = new SurfaceTexture(c.windowTexture);
            c.inputSurface = new Surface(c.input);
            return c;
        }

        void release() {
            try {
                inputSurface.release();
                input.release();
                GLES20.glDeleteTextures(2, new int[]{windowTexture, iconTexture}, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    private static volatile Content main;
    /** Captura en espera en el contenido principal (y de qué animación es: otra no la usa). */
    private static final Object queueLock = new Object();
    private static boolean queued;
    private static Object queuedOwner;
    /** Malla fija: por cada fila, su vértice izquierdo y su vértice derecho (tira de triángulos). */
    private static final FloatBuffer mesh = buildMesh();

    private static FloatBuffer buildMesh() {
        FloatBuffer b = ByteBuffer.allocateDirect((ROWS + 1) * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int i = 0; i <= ROWS; i++) {
            float t = i / (float) ROWS;
            b.put(t).put(0f).put(t).put(1f);
        }
        b.position(0);
        return b;
    }

    static SurfaceControl layer, parent;
    private static Surface output;
    private static int width, height;          // tamaño del búfer (media resolución)
    static boolean warmed;

    // ------------------------------------------------------------------ capa y contexto

    /**
     * Capa colgada de {@code root} que cubre {@code screenW × screenH}, con su contexto listo. Si ya
     * está, no hace nada; rehacerla es lento y no debería pasar en mitad de una animación.
     */
    static boolean ensure(SurfaceControl root, int screenW, int screenH) {
        float sc0 = Math.max(0.5f, Math.min(1f, wantedScale));
        int w = Math.round(screenW * sc0), h = Math.round(screenH * sc0);
        boolean sameScale = Math.abs(sc0 - scale) < 0.001f;
        if (layer != null && layer.isValid() && parent == root && sameScale && w <= width && h <= height
                && window != EGL14.EGL_NO_SURFACE) {
            return true;
        }
        if (layer != null) Log.i("genio: capa nueva a " + w + "×" + h + " (antes " + width + "×" + height + ")");
        if (sameScale) {                                   // p. ej. al girar: cabe en ambos sentidos
            w = Math.max(w, width);
            h = Math.max(h, height);
        }
        releaseLayer();
        try {
            if (!initContext()) return false;
            SurfaceControl sc = new SurfaceControl.Builder()
                    .setName("IceHome genie")
                    .setBufferSize(w, h)
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .setOpaque(false)
                    .setParent(root)
                    .build();
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.setVisibility(sc, false);
            t.apply();
            output = new Surface(sc);
            window = EGL14.eglCreateWindowSurface(display, config, output, new int[]{EGL14.EGL_NONE}, 0);
            if (window == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("eglCreateWindowSurface " + EGL14.eglGetError());
            makeCurrent();
            EGL14.eglSwapInterval(display, 0);              // nunca esperar al refresco
            layer = sc;
            parent = root;
            scale = sc0;
            width = w;
            height = h;
            return true;
        } catch (Throwable e) {
            Log.e("genio: no se pudo preparar OpenGL", e);
            releaseLayer();
            return false;
        }
    }

    /**
     * Cuelga la capa de la superficie actual del launcher. Android la destruye cuando el launcher
     * pasa a segundo plano y crea otra al volver, en el mismo objeto Java: sin esto, la capa
     * quedaría colgando de la superficie vieja y no se vería.
     */
    static void attach(SurfaceControl.Transaction t, SurfaceControl root) {
        if (layer != null && root != null && root.isValid()) t.reparent(layer, root);
    }

    /** Matriz de la capa: escala a pantalla completa y, si hace falta, la inversa del inicio. */
    static Matrix layerMatrix(Matrix homeInverse, Matrix out) {
        if (homeInverse != null) out.set(homeInverse);
        else out.reset();
        out.preScale(1f / scale, 1f / scale);
        return out;
    }

    private static boolean initContext() {
        if (context != EGL14.EGL_NO_CONTEXT) return true;
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("eglInitialize");
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        int[] attrs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE};
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw new IllegalStateException("eglChooseConfig");
        }
        config = configs[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("eglCreateContext");
        // Un pbuffer mínimo para crear texturas y programas antes de tener la capa.
        EGLSurface pb = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(display, pb, pb, context);
        programs[0] = new Program(fragment(true));
        programs[1] = new Program(fragment(false));
        main = Content.create();
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display, pb);
        return true;
    }

    private static void setupTexture(int target, int name) {
        GLES20.glBindTexture(target, name);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static void makeCurrent() {
        if (!EGL14.eglMakeCurrent(display, window, window, context)) {
            throw new IllegalStateException("eglMakeCurrent " + EGL14.eglGetError());
        }
    }

    // ------------------------------------------------------------------ contenido

    /** Hilo de capturas: deja una captura lista para el siguiente dibujo, sin copiarla. */
    static boolean queue(HardwareBuffer buffer, ColorSpace colorSpace, Object owner) {
        synchronized (queueLock) {
            Content c = main;
            if (c == null || queued) return false;
            if (!Hidden.queueBuffer(c.inputSurface, buffer, colorSpace)) return false;
            queuedOwner = owner;
            queued = true;
            return true;
        }
    }

    /** Si queda una captura sin usar (de una animación anterior), se descarta. */
    static boolean hasQueued() {
        synchronized (queueLock) {
            return queued;
        }
    }

    /** Hilo de dibujo: pasa la última captura a la textura principal. Devuelve de quién era, o null. */
    static Object consume() {
        synchronized (queueLock) {
            if (!queued) return null;
            queued = false;
            try {
                main.input.updateTexImage();
                main.input.getTransformMatrix(main.texMatrix);
                main.windowOwner = queuedOwner;
                return queuedOwner;
            } catch (Throwable e) {
                Log.e("genio: textura de la captura", e);
                return null;
            }
        }
    }

    /**
     * Hilo de dibujo: el contenido principal pasa a ser de un genio que sigue solo (con la última
     * captura de {@code owner}, si había una en espera) y las capturas van a uno nuevo.
     */
    static Content detach(Object owner) {
        synchronized (queueLock) {
            Content old = main;
            if (old == null || window == EGL14.EGL_NO_SURFACE) return null;
            try {
                makeCurrent();
            } catch (Throwable e) {
                return null;
            }
            if (queued && queuedOwner == owner) consume();
            queued = false;
            main = Content.create();
            return old;
        }
    }

    /** Icono de la app (cuadrado, potencia de 2): el genio sale de él y vuelve a él. */
    static void uploadIcon(Bitmap b, Object owner) {
        makeCurrent();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, main.iconTexture);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        main.iconOwner = owner;
    }

    static Object iconOwner() {
        Content c = main;
        return c == null ? null : c.iconOwner;
    }

    static Object windowOwner() {
        Content c = main;
        return c == null ? null : c.windowOwner;
    }

    /** Una animación se queda con la imagen precargada. */
    static void claimWindow(Object owner) {
        Content c = main;
        if (c != null) c.windowOwner = owner;
    }

    /** Una animación se queda con el icono precargado. */
    static void claimIcon(Object owner) {
        Content c = main;
        if (c != null) c.iconOwner = owner;
    }

    static Content main() {
        return main;
    }

    /** Hilo de dibujo: libera el contenido de un genio que ya terminó. */
    static void release(Content c) {
        if (c == null || c == main) return;
        try {
            if (window != EGL14.EGL_NO_SURFACE) makeCurrent();
            c.release();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ dibujo

    /** Un fotograma de un solo genio, con el contenido principal. */
    static boolean draw(boolean fromWindow, Geo g, float fullHalf, float fullH, int bg) {
        return beginFrame(0f) && drawGenie(main, fromWindow, g, fullHalf, fullH, bg) && endFrame();
    }

    /** Empieza un fotograma: búfer limpio y transparente. Luego {@link #drawGenie} y {@link #endFrame}. */
    /** @param dim oscurecido del escritorio detrás (negro premultiplicado; 0 = transparente). */
    static boolean beginFrame(float dim) {
        try {
            makeCurrent();
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0f, 0f, 0f, Math.max(0f, Math.min(1f, dim)));
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            // Alfa premultiplicado: un genio encima de otro (el que se abre sobre el que se cierra).
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            return true;
        } catch (Throwable e) {
            Log.e("genio: no se pudo dibujar", e);
            releaseLayer();
            return false;
        }
    }

    /**
     * Un genio.
     *
     * @param fromWindow contenido: captura de la ventana (true) o pantalla provisional (false)
     * @param fullHalf   media anchura de la ventana a pantalla completa
     * @param bg         color de la app (ARGB)
     */
    static boolean drawGenie(Content c, boolean fromWindow, Geo g, float fullHalf, float fullH, int bg) {
        try {
            Program p = programs[fromWindow ? 0 : 1];
            GLES20.glUseProgram(p.id);
            if (fromWindow) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, c.windowTexture);
                GLES20.glUniformMatrix4fv(p.texM, 1, false, c.texMatrix, 0);
                GLES20.glUniform1i(p.content, 0);
                GLES20.glUniform1f(p.fade, g.fade);
            }
            float half = g.splashIcon * 0.5f;
            // Icono cuadrado aunque la tarjeta no tenga la forma de la pantalla (al abrir es más
            // ancha de proporción): la mitad en v sale de la proporción actual de la tarjeta.
            GLES20.glUniform3f(p.splash, half, half * Math.max(1f, g.cur.width()) / Math.max(1f, g.cur.height()),
                    g.splashIcon > 0f ? 1f : 0f);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, c.iconTexture);
            GLES20.glUniform1i(p.iconTex, 1);
            rect(p.cur, g.cur);
            rect(p.icon, g.icon);
            GLES20.glUniform4f(p.g, g.pw, g.lead, g.lag, g.radius);
            GLES20.glUniform1f(p.iconMix, g.iconMix);
            GLES20.glUniform1f(p.fullHalf, fullHalf);
            // Color de la app bajo el contenido, premultiplicado (transparente al cerrar: ver Genie).
            float bgA = (bg >>> 24) / 255f;
            GLES20.glUniform4f(p.bg, ((bg >> 16) & 255) / 255f * bgA, ((bg >> 8) & 255) / 255f * bgA,
                    (bg & 255) / 255f * bgA, bgA);
            GLES20.glUniform1f(p.scale, scale);
            GLES20.glUniform1f(p.bufH, height);
            GLES20.glUniform2f(p.buf, width, height);
            GLES20.glUniform1f(p.bend, g.bend);
            GLES20.glUniform1f(p.flip, g.flip);
            GLES20.glUniform1f(p.cup, g.cup);
            GLES20.glUniform3f(p.persp, g.tilt, g.curl, g.push);
            GLES20.glUniform4f(p.vel, g.vel[0], g.vel[1], g.vel[2], g.vel[3]);
            mesh.position(0);
            GLES20.glEnableVertexAttribArray(p.pos);
            GLES20.glVertexAttribPointer(p.pos, 2, GLES20.GL_FLOAT, false, 0, mesh);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, (ROWS + 1) * 2);
            return true;
        } catch (Throwable e) {
            Log.e("genio: no se pudo dibujar", e);
            releaseLayer();
            return false;
        }
    }

    /** Termina el fotograma y lo manda al compositor. */
    static boolean endFrame() {
        try {
            if (!EGL14.eglSwapBuffers(display, window)) throw new IllegalStateException("eglSwapBuffers " + EGL14.eglGetError());
            return true;
        } catch (Throwable e) {
            Log.e("genio: no se pudo dibujar", e);
            releaseLayer();
            return false;
        }
    }

    private static void rect(int loc, RectF r) {
        GLES20.glUniform4f(loc, r.left, r.top, r.right, r.bottom);
    }

    /** Compila los programas y dibuja una vez con cada uno, sin que se vea (la capa está oculta). */
    static void warm(SurfaceControl root, int screenW, int screenH) {
        long t0 = android.os.SystemClock.uptimeMillis();
        SurfaceControl before = layer;
        if (!ensure(root, screenW, screenH) || (layer == before && warmed)) return;
        makeCurrent();
        Bitmap one = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        uploadIcon(one, null);
        one.recycle();
        Geo g = new Geo();
        g.cur.set(0, 0, 8, 8);
        g.icon.set(0, 0, 8, 8);
        g.pw = g.lead = g.lag = 1f;
        g.radius = 2f;
        g.splashIcon = 0.3f;
        warmed = draw(true, g, 4f, 8f, 0xFFFFFFFF) && draw(false, g, 4f, 8f, 0xFFFFFFFF);
        Log.i("genio preparado en " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
    }

    private static void releaseLayer() {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, window);
            window = EGL14.EGL_NO_SURFACE;
        }
        if (output != null) output.release();
        if (layer != null) {
            try {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                t.reparent(layer, null);
                t.apply();
            } catch (Throwable ignored) {
            }
            layer.release();
        }
        output = null;
        layer = parent = null;
        width = height = 0;
        warmed = false;
    }

    /** Un programa con sus uniformes. */
    private static final class Program {
        final int id, pos, content, texM, iconTex, splash, fade, bend, flip, cup, buf, cur, icon, g, iconMix, fullHalf, bg, scale, bufH,
                persp, vel;

        Program(String fragment) {
            id = GLES20.glCreateProgram();
            GLES20.glAttachShader(id, compile(GLES20.GL_VERTEX_SHADER, VERTEX));
            GLES20.glAttachShader(id, compile(GLES20.GL_FRAGMENT_SHADER, fragment));
            GLES20.glLinkProgram(id);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("link: " + GLES20.glGetProgramInfoLog(id));
            pos = GLES20.glGetAttribLocation(id, "aRow");
            content = u("content");
            texM = u("texM");
            iconTex = u("iconTex");
            splash = u("splash");
            fade = u("fade");
            bend = u("bend");
            flip = u("flip");
            cup = u("cup");
            buf = u("buf");
            cur = u("cur");
            icon = u("icon");
            g = u("g");
            iconMix = u("iconMix");
            fullHalf = u("fullHalf");
            bg = u("bg");
            scale = u("scale");
            bufH = u("bufH");
            persp = u("persp");
            vel = u("vel");
        }

        private int u(String name) {
            return GLES20.glGetUniformLocation(id, name);
        }

        private static int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }
    }

    private GlRenderer() {
    }
}
