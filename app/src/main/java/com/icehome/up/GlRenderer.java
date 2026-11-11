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
            + "varying vec2 vE;\n"
            + "varying vec2 vD;\n"
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
            + "  float k = pow(q, mix(g.y, g.z, n));\n"
            + "  float w = pow(q, max(g.z - 1.0, 0.0) * n * n);\n"
            + "  float c = mix((icon.x + icon.z) * 0.5, (cur.x + cur.z) * 0.5, w);\n"
            + "  c += bend * (1.0 - n) * (1.0 - n);\n"
            + "  float h = mix((icon.z - icon.x) * 0.5, fullHalf, k);\n"
            + "  return vec2(c - h, c + h);\n"
            + "}\n"
            + "void main() {\n"
            + "  float h = max(cur.w - cur.y, 1.0);\n"
            + "  float y = cur.y - 2.0 + aRow.x * (h + 4.0);\n"
            + "  float s = clamp((y - cur.y) / h, 0.0, 1.0);\n"
            + "  vec2 e = row(s);\n"
            + "  vec2 e2 = row(clamp(s + 1.0 / h, 0.0, 1.0)) - e;\n"
            + "  vE = e;\n"
            + "  vD = vec2(inversesqrt(1.0 + e2.x * e2.x), inversesqrt(1.0 + e2.y * e2.y));\n"
            + "  float x = aRow.y < 0.5 ? e.x - 2.0 : e.y + 2.0;\n"
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
            + "varying vec2 vE;\n"
            + "varying vec2 vD;\n"
            + "void main() {\n"
            + "  vec2 p = vec2(gl_FragCoord.x, bufH - gl_FragCoord.y) / scale;\n"
            + "  float h = max(cur.w - cur.y, 1.0);\n"
            + "  float s = clamp((p.y - cur.y) / h, 0.0, 1.0);\n"
            + "  float dx = min((p.x - vE.x) * vD.x, (vE.y - p.x) * vD.y);\n"
            + "  float dy = min(p.y - cur.y, cur.w - p.y);\n"
            + "  float rad = min(g.w, min((vE.y - vE.x) * 0.5, h * 0.5));\n"
            + "  float d = (dx < rad && dy < rad) ? rad - length(vec2(rad - dx, rad - dy)) : min(dx, dy);\n"
            + "  float a = clamp(d * scale + 0.5, 0.0, 1.0);\n"
            + "  if (a <= 0.0) { gl_FragColor = vec4(0.0); return; }\n"
            + "  float u = clamp((p.x - vE.x) / max(vE.y - vE.x, 1.0), 0.0, 1.0);\n"
            + "  vec4 col = CONTENT(u, s);\n"
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
    }

    static String fragment(boolean window) {
        return (window ? FRAGMENT_WINDOW : FRAGMENT_SPLASH) + FRAGMENT_BODY;
    }

    private static EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private static EGLContext context = EGL14.EGL_NO_CONTEXT;
    private static EGLConfig config;
    private static EGLSurface window = EGL14.EGL_NO_SURFACE;
    private static boolean displayInitialized;
    private static volatile long contextGeneration = 1L;

    private static final class EglFailure extends RuntimeException {
        final int error;

        EglFailure(String operation, int error) {
            super(operation + " failed: EGL error 0x" + Integer.toHexString(error));
            this.error = error;
        }
    }

    private static final class GlContextLost extends RuntimeException {
        GlContextLost(String operation) { super(operation + ": GL context lost"); }
    }

    private static void checkEgl(boolean success, String operation) {
        if (!success) throw new EglFailure(operation, EGL14.eglGetError());
    }

    private static void checkGlError(String operation) {
        int firstError = GLES20.GL_NO_ERROR;
        for (int i = 0; i < 8; i++) {
            int error = GLES20.glGetError();
            if (error == GLES20.GL_NO_ERROR) break;
            if (error == 0x0507) throw new GlContextLost(operation);
            if (firstError == GLES20.GL_NO_ERROR) firstError = error;
        }
        if (firstError != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(operation + ": GL error 0x" + Integer.toHexString(firstError));
        }
    }

    private static boolean isContextLost(Throwable error) {
        return error instanceof GlContextLost
                || (error instanceof EglFailure && ((EglFailure) error).error == 0x300E);
    }

    /** Surface failures can be retried with the current context; context loss cannot. */
    private static void recover(Throwable error) {
        if (isContextLost(error)) destroyEglContext();
        else releaseLayer();
    }
    /** [ventana, pantalla de inicio] */
    private static final Program[] programs = new Program[2];
    /**
     * Contenido de un genio: la captura de su ventana (textura externa alimentada sin copias por
     * una SurfaceTexture) y su icono. Las capturas entran siempre en {@link #main}; un genio que
     * sigue solo (el cierre de una app mientras se abre otra) se queda con el suyo
     * ({@link #detach}) y el principal pasa a ser uno nuevo.
     */
    static final class Content {
        final long generation = contextGeneration;
        int windowTexture, iconTexture;
        private boolean released;
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
            try {
                int[] tex = new int[2];
                GLES20.glGenTextures(2, tex, 0);
                c.windowTexture = tex[0];
                c.iconTexture = tex[1];
                if (c.windowTexture == 0 || c.iconTexture == 0) {
                    throw new IllegalStateException("glGenTextures returned texture 0");
                }
                setupTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, c.windowTexture);
                setupTexture(GLES20.GL_TEXTURE_2D, c.iconTexture);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
                c.input = new SurfaceTexture(c.windowTexture);
                c.inputSurface = new Surface(c.input);
                checkGlError("create capture content");
                return c;
            } catch (Throwable t) {
                c.release(true);
                throw t;
            }
        }

        void release(boolean deleteTextures) {
            if (released) return;
            released = true;
            if (inputSurface != null) {
                try { inputSurface.release(); } catch (Throwable ignored) {}
                inputSurface = null;
            }
            if (input != null) {
                try { input.release(); } catch (Throwable ignored) {}
                input = null;
            }
            if (deleteTextures && (windowTexture != 0 || iconTexture != 0)) {
                try {
                    GLES20.glDeleteTextures(2, new int[]{windowTexture, iconTexture}, 0);
                } catch (Throwable ignored) {
                }
            }
            windowTexture = iconTexture = 0;
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
        if (root == null || !root.isValid() || screenW <= 0 || screenH <= 0) return false;
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
            initContext();
            SurfaceControl sc = new SurfaceControl.Builder()
                    .setName("IceHome genie")
                    .setBufferSize(w, h)
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .setOpaque(false)
                    .setParent(root)
                    .build();
            layer = sc;
            parent = root;
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.setVisibility(sc, false);
            t.apply();
            output = new Surface(sc);
            window = EGL14.eglCreateWindowSurface(display, config, output, new int[]{EGL14.EGL_NONE}, 0);
            if (window == EGL14.EGL_NO_SURFACE) throw new EglFailure("eglCreateWindowSurface", EGL14.eglGetError());
            makeCurrent();
            checkEgl(EGL14.eglSwapInterval(display, 0), "eglSwapInterval");
            scale = sc0;
            width = w;
            height = h;
            return true;
        } catch (Throwable e) {
            Log.e("genio: no se pudo preparar OpenGL", e);
            recover(e);
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
        if (context != EGL14.EGL_NO_CONTEXT && displayInitialized) return true;

        EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) throw new EglFailure("eglGetDisplay", EGL14.eglGetError());

            int[] version = new int[2];
            checkEgl(EGL14.eglInitialize(display, version, 0, version, 1), "eglInitialize");
            displayInitialized = true;

            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            int[] attrs = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE};
            checkEgl(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0), "eglChooseConfig");
            if (count[0] == 0) throw new IllegalStateException("No EGLConfig supports window+pbuffer GLES2");

            config = configs[0];
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT) throw new EglFailure("eglCreateContext", EGL14.eglGetError());

            pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (pbuffer == EGL14.EGL_NO_SURFACE) throw new EglFailure("eglCreatePbufferSurface", EGL14.eglGetError());
            checkEgl(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context), "eglMakeCurrent(pbuffer)");

            programs[0] = new Program(fragment(true));
            programs[1] = new Program(fragment(false));
            synchronized (queueLock) {
                main = Content.create();
                queued = false;
                queuedOwner = null;
            }
            checkGlError("initialize genie renderer");

            checkEgl(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT), "eglMakeCurrent(unbind pbuffer)");
            checkEgl(EGL14.eglDestroySurface(display, pbuffer), "eglDestroySurface(pbuffer)");
            pbuffer = EGL14.EGL_NO_SURFACE;
            return true;
        } catch (Throwable t) {
            if (display != EGL14.EGL_NO_DISPLAY) {
                try {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                } catch (Throwable ignored) {}
                if (pbuffer != EGL14.EGL_NO_SURFACE) {
                    try { EGL14.eglDestroySurface(display, pbuffer); } catch (Throwable ignored) {}
                }
            }
            destroyEglContext();
            throw t;
        }
    }

    private static void setupTexture(int target, int name) {
        GLES20.glBindTexture(target, name);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static void makeCurrent() {
        if (display == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT
                || window == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("EGL renderer is not initialized");
        }
        checkEgl(EGL14.eglMakeCurrent(display, window, window, context), "eglMakeCurrent(window)");
    }

    // ------------------------------------------------------------------ contenido

    /** Hilo de capturas: deja una captura lista para el siguiente dibujo, sin copiarla. */
    static boolean queue(HardwareBuffer buffer, ColorSpace colorSpace, Object owner) {
        synchronized (queueLock) {
            Content c = main;
            if (c == null || c.inputSurface == null || c.generation != contextGeneration || queued) return false;
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
            if (!queued || main == null || main.input == null) {
                queued = false;
                queuedOwner = null;
                return null;
            }
            Object owner = queuedOwner;
            queued = false;
            queuedOwner = null;
            try {
                main.input.updateTexImage();
                main.input.getTransformMatrix(main.texMatrix);
                main.windowOwner = owner;
                return owner;
            } catch (Throwable e) {
                Log.e("genio: textura de la captura", e);
                recover(e);
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
                recover(e);
                return null;
            }
            if (queued && queuedOwner == owner) consume();
            queued = false;
            queuedOwner = null;
            try {
                main = Content.create();
                return old;
            } catch (Throwable t) {
                recover(t);
                return null;
            }
        }
    }

    /** Icono de la app (cuadrado, potencia de 2): el genio sale de él y vuelve a él. */
    static void uploadIcon(Bitmap b, Object owner) {
        makeCurrent();
        Content c = main;
        if (c == null || c.generation != contextGeneration || c.iconTexture == 0) {
            throw new IllegalStateException("No valid genie content texture");
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, c.iconTexture);
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
        boolean deleteTextures = false;
        if (c.generation == contextGeneration && context != EGL14.EGL_NO_CONTEXT
                && window != EGL14.EGL_NO_SURFACE) {
            try {
                makeCurrent();
                deleteTextures = true;
            } catch (Throwable t) {
                recover(t);
            }
        }
        c.release(deleteTextures);
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
            recover(e);
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
            if (c == null || c.generation != contextGeneration || context == EGL14.EGL_NO_CONTEXT) return false;
            Program p = programs[fromWindow ? 0 : 1];
            if (p == null) return false;
            GLES20.glUseProgram(p.id);
            if (fromWindow) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, c.windowTexture);
                GLES20.glUniformMatrix4fv(p.texM, 1, false, c.texMatrix, 0);
                GLES20.glUniform1i(p.content, 0);
                GLES20.glUniform1f(p.fade, g.fade);
            }
            float half = g.splashIcon * 0.5f;
            GLES20.glUniform3f(p.splash, half, half * (2f * fullHalf) / Math.max(1f, fullH),
                    g.splashIcon > 0f ? 1f : 0f);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, c.iconTexture);
            GLES20.glUniform1i(p.iconTex, 1);
            rect(p.cur, g.cur);
            rect(p.icon, g.icon);
            GLES20.glUniform4f(p.g, g.pw, g.lead, g.lag, g.radius);
            GLES20.glUniform1f(p.iconMix, g.iconMix);
            GLES20.glUniform1f(p.fullHalf, fullHalf);
            GLES20.glUniform4f(p.bg, ((bg >> 16) & 255) / 255f, ((bg >> 8) & 255) / 255f, (bg & 255) / 255f, 1f);
            GLES20.glUniform1f(p.scale, scale);
            GLES20.glUniform1f(p.bufH, height);
            GLES20.glUniform2f(p.buf, width, height);
            GLES20.glUniform1f(p.bend, g.bend);
            GLES20.glUniform1f(p.flip, g.flip);
            GLES20.glUniform1f(p.cup, g.cup);
            mesh.position(0);
            GLES20.glEnableVertexAttribArray(p.pos);
            GLES20.glVertexAttribPointer(p.pos, 2, GLES20.GL_FLOAT, false, 0, mesh);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, (ROWS + 1) * 2);
            checkGlError("draw genie");
            return true;
        } catch (Throwable e) {
            Log.e("genio: no se pudo dibujar", e);
            recover(e);
            return false;
        }
    }

    /** Termina el fotograma y lo manda al compositor. */
    static boolean endFrame() {
        try {
            checkGlError("end genie frame");
            checkEgl(EGL14.eglSwapBuffers(display, window), "eglSwapBuffers");
            return true;
        } catch (Throwable e) {
            Log.e("genio: no se pudo dibujar", e);
            recover(e);
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
        Bitmap one = null;
        try {
            makeCurrent();
            one = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            uploadIcon(one, null);
            Geo g = new Geo();
            g.cur.set(0, 0, 8, 8);
            g.icon.set(0, 0, 8, 8);
            g.pw = g.lead = g.lag = 1f;
            g.radius = 2f;
            g.splashIcon = 0.3f;
            warmed = draw(true, g, 4f, 8f, 0xFFFFFFFF) && draw(false, g, 4f, 8f, 0xFFFFFFFF);
            Log.i("genio preparado en " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
        } catch (Throwable t) {
            Log.e("genio: precalentamiento OpenGL", t);
            recover(t);
            warmed = false;
        } finally {
            if (one != null && !one.isRecycled()) one.recycle();
        }
    }

    /** Releases only the window surface/layer; shader and texture objects remain reusable. */
    private static void releaseLayer() {
        EGLSurface oldWindow = window;
        window = EGL14.EGL_NO_SURFACE;
        if (display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT) {
            try {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            } catch (Throwable ignored) {
            }
        }
        if (oldWindow != EGL14.EGL_NO_SURFACE && display != EGL14.EGL_NO_DISPLAY) {
            try { EGL14.eglDestroySurface(display, oldWindow); } catch (Throwable ignored) {}
        }

        Surface oldOutput = output;
        output = null;
        if (oldOutput != null) {
            try { oldOutput.release(); } catch (Throwable ignored) {}
        }

        SurfaceControl oldLayer = layer;
        layer = parent = null;
        if (oldLayer != null) {
            try {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                t.reparent(oldLayer, null);
                t.apply();
            } catch (Throwable ignored) {
            }
            try { oldLayer.release(); } catch (Throwable ignored) {}
        }
        width = height = 0;
        warmed = false;
    }

    /** Full teardown is mandatory after EGL_CONTEXT_LOST: every GL name is now stale. */
    private static void destroyEglContext() {
        releaseLayer();

        Content oldMain;
        synchronized (queueLock) {
            oldMain = main;
            main = null;
            queued = false;
            queuedOwner = null;
        }

        if (display != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            } catch (Throwable ignored) {
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                try { EGL14.eglDestroyContext(display, context); } catch (Throwable ignored) {}
            }
            if (displayInitialized) {
                try { EGL14.eglTerminate(display); } catch (Throwable ignored) {}
            }
        }

        context = EGL14.EGL_NO_CONTEXT;
        display = EGL14.EGL_NO_DISPLAY;
        config = null;
        displayInitialized = false;
        programs[0] = programs[1] = null;
        contextGeneration++;
        if (oldMain != null) oldMain.release(false);
    }

    /** Un programa con sus uniformes. */
    private static final class Program {
        final int id, pos, content, texM, iconTex, splash, fade, bend, flip, cup, buf, cur, icon, g, iconMix, fullHalf, bg, scale, bufH;

        Program(String fragment) {
            int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
            int pixel = 0;
            int linked = 0;
            try {
                pixel = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
                linked = GLES20.glCreateProgram();
                if (linked == 0) throw new IllegalStateException("glCreateProgram returned 0");
                GLES20.glAttachShader(linked, vertex);
                GLES20.glAttachShader(linked, pixel);
                GLES20.glLinkProgram(linked);
                int[] ok = new int[1];
                GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, ok, 0);
                if (ok[0] == 0) throw new IllegalStateException("link: " + GLES20.glGetProgramInfoLog(linked));
                id = linked;
            } catch (Throwable t) {
                if (linked != 0) GLES20.glDeleteProgram(linked);
                throw t;
            } finally {
                GLES20.glDeleteShader(vertex);
                if (pixel != 0) GLES20.glDeleteShader(pixel);
            }
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
        }

        private int u(String name) {
            return GLES20.glGetUniformLocation(id, name);
        }

        private static int compile(int type, String src) {
            int shader = GLES20.glCreateShader(type);
            if (shader == 0) throw new IllegalStateException("glCreateShader returned 0");
            GLES20.glShaderSource(shader, src);
            GLES20.glCompileShader(shader);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                String info = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("shader: " + info);
            }
            return shader;
        }
    }

    private GlRenderer() {
    }
}
