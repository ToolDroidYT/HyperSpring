package com.icehome.up.telon;

import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;
import android.view.SurfaceControl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Dibujo del telón con OpenGL ES 2, en su propio contexto y en el hilo de dibujo de Telón.
 *
 * La ventana se dibuja como una malla de {@link #ROWS} filas: cada fila es la ventana entera de
 * ancho, desplazada en horizontal {@code bend · (y/alto)^power} (arriba fija, abajo cada vez más
 * atrás), y después transformada con la misma matriz que HyperOS le pone a la ventana en ese
 * fotograma. El contenido es una captura de la ventana (una sola, al empezar), sin copias: el
 * búfer entra directo en una SurfaceTexture.
 *
 * La capa es hija de la raíz de la transición (cambia en cada una: se recuelga) y va justo encima
 * de la ventana que se mueve, que se oculta mientras se ve la nuestra.
 */
final class TelonRenderer {

    static final int ROWS = 48;
    /**
     * Forma: 1 = recta (la ventana se inclina como un paralelogramo, bordes rectos); cuanto más
     * alto, más se concentra el doblez abajo (2,4 = telón curvo de las primeras versiones).
     */
    static volatile float power = 1f;
    /** Margen de la malla (px de la ventana) para el borde suave. */
    private static final float MARGIN = 2f;

    private static final String VERTEX =
            "attribute vec2 aRow;\n"
            + "uniform mat3 M;\n"
            + "uniform vec2 win;\n"
            + "uniform float bend;\n"
            + "uniform float power;\n"
            + "uniform float margin;\n"
            + "uniform float scale;\n"
            + "uniform vec2 buf;\n"
            + "varying vec2 vLocal;\n"
            + "void main() {\n"
            + "  float y = -margin + aRow.x * (win.y + 2.0 * margin);\n"
            + "  float x = -margin + aRow.y * (win.x + 2.0 * margin);\n"
            + "  float t = clamp(y / win.y, 0.0, 1.0);\n"
            + "  vLocal = vec2(x, y);\n"
            + "  vec3 p = M * vec3(x + bend * pow(t, power), y, 1.0);\n"
            + "  gl_Position = vec4(p.x * scale / buf.x * 2.0 - 1.0, 1.0 - 2.0 * p.y * scale / buf.y, 0.0, 1.0);\n"
            + "}\n";

    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision highp float;\n"
            + "uniform samplerExternalOES content;\n"
            + "uniform mat4 texM;\n"
            + "uniform vec2 win;\n"
            + "uniform float radius;\n"
            + "uniform float alpha;\n"
            + "uniform float aa;\n"
            + "uniform float blur;\n"
            + "varying vec2 vLocal;\n"
            + "vec4 at(vec2 uv) {\n"
            + "  return texture2D(content, (texM * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy);\n"
            + "}\n"
            + "void main() {\n"
            + "  vec2 d = min(vLocal, win - vLocal);\n"
            + "  float r = radius;\n"
            + "  float dist = (d.x < r && d.y < r) ? r - length(vec2(r - d.x, r - d.y)) : min(d.x, d.y);\n"
            + "  float a = clamp(dist * aa + 0.5, 0.0, 1.0) * alpha;\n"
            + "  if (a <= 0.0) { gl_FragColor = vec4(0.0); return; }\n"
            + "  vec2 uv = clamp(vLocal / win, 0.0, 1.0);\n"
            + "  if (blur < 0.5) { gl_FragColor = at(uv) * a; return; }\n"
            // Motion blur: 9 muestras a lo largo del movimiento (horizontal), con más peso en el
            // centro y desplazadas un poco en cada píxel (ruido): sin copias separadas, una estela.
            + "  float j = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5;\n"
            + "  float du = blur / win.x;\n"
            + "  vec4 c = vec4(0.0);\n"
            + "  float ws = 0.0;\n"
            + "  for (int i = 0; i < 9; i++) {\n"
            + "    float t = (float(i) + j) / 8.0 - 0.5;\n"
            + "    float wt = 1.0 - abs(t) * 1.4;\n"
            + "    c += at(clamp(uv + vec2(du * t, 0.0), 0.0, 1.0)) * wt;\n"
            + "    ws += wt;\n"
            + "  }\n"
            + "  c /= ws;\n"
            + "  gl_FragColor = c * a;\n"
            + "}\n";

    /**
     * Pantalla de debajo: su captura nítida mezclada con una versión desenfocada (preparada una
     * sola vez por transición, ver {@link #prepareBelow}) según {@code amount}, y oscurecida como
     * la oscurece HyperOS ({@code dim}). Sustituye a la real (que se oculta), así que es opaca.
     */
    private static final String FRAGMENT_BELOW =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision highp float;\n"
            + "uniform samplerExternalOES content;\n"
            + "uniform sampler2D blurred;\n"
            + "uniform sampler2D soft;\n"
            + "uniform mat4 texM;\n"
            + "uniform vec2 win;\n"
            + "uniform float radius;\n"
            + "uniform float alpha;\n"
            + "uniform float aa;\n"
            + "uniform vec2 btexel;\n"
            + "uniform float amount;\n"
            + "uniform float dim;\n"
            + "varying vec2 vLocal;\n"
            + "void main() {\n"
            + "  vec2 d = min(vLocal, win - vLocal);\n"
            + "  float r = radius;\n"
            + "  float dist = (d.x < r && d.y < r) ? r - length(vec2(r - d.x, r - d.y)) : min(d.x, d.y);\n"
            + "  float a = clamp(dist * aa + 0.5, 0.0, 1.0) * alpha;\n"
            + "  if (a <= 0.0) { gl_FragColor = vec4(0.0); return; }\n"
            + "  vec2 uv = clamp(vLocal / win, 0.0, 1.0);\n"
            // Desenfoque progresivo con tres niveles fijos: nítida → suave (1/4) → fuerte (1/8),
            // fundiendo solo dos vecinos a la vez (sin el «fantasma» de nítida sobre muy desenfocada).
            + "  vec4 c;\n"
            + "  if (amount < 0.5) {\n"
            + "    vec4 sharp = texture2D(content, (texM * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy);\n"
            + "    c = amount > 0.001 ? mix(sharp, texture2D(soft, uv), amount * 2.0) : sharp;\n"
            + "  } else {\n"
            // Cuatro muestras bilineales a medio texel: un filtro en tienda, sin escalones al ampliar.
            + "    vec2 o = btexel * 0.5;\n"
            + "    vec4 b = (texture2D(blurred, uv + o) + texture2D(blurred, uv - o)\n"
            + "        + texture2D(blurred, uv + vec2(o.x, -o.y)) + texture2D(blurred, uv + vec2(-o.x, o.y))) * 0.25;\n"
            + "    c = mix(texture2D(soft, uv), b, amount * 2.0 - 1.0);\n"
            + "  }\n"
            + "  c.rgb *= 1.0 - dim;\n"
            + "  gl_FragColor = vec4(c.rgb, 1.0) * a;\n"
            + "}\n";

    /** Pasadas del desenfoque (a una textura propia, una vez por transición): cuadro completo. */
    private static final String VERTEX_QUAD =
            "attribute vec2 aPos;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "  vUv = aPos;\n"
            + "  gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);\n"
            + "}\n";

    /** Tienda de 3×3 muestras (cada una ya promedia 2×2 al ser bilineal), separadas {@code step}. */
    private static final String TENT =
            "void main() {\n"
            + "  vec4 c = at(vUv) * 4.0;\n"
            + "  c += (at(vUv + vec2(step.x, 0.0)) + at(vUv - vec2(step.x, 0.0)) + at(vUv + vec2(0.0, step.y)) + at(vUv - vec2(0.0, step.y))) * 2.0;\n"
            + "  c += at(vUv + step) + at(vUv - step) + at(vUv + vec2(step.x, -step.y)) + at(vUv + vec2(-step.x, step.y));\n"
            + "  gl_FragColor = c / 16.0;\n"
            + "}\n";

    /** Primera pasada: de la captura (textura externa) a 1/4 de su tamaño. */
    private static final String FRAGMENT_DOWN_EXT =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "uniform samplerExternalOES src;\n"
            + "uniform mat4 texM;\n"
            + "uniform vec2 step;\n"
            + "varying vec2 vUv;\n"
            + "vec4 at(vec2 uv) {\n"
            + "  uv = clamp(uv, 0.0, 1.0);\n"
            + "  return texture2D(src, (texM * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy);\n"
            + "}\n"
            + TENT;

    /** Segunda pasada: a la mitad otra vez (en el espacio de la ventana: y hacia abajo). */
    private static final String FRAGMENT_DOWN =
            "precision mediump float;\n"
            + "uniform sampler2D src;\n"
            + "uniform vec2 step;\n"
            + "varying vec2 vUv;\n"
            + "vec4 at(vec2 uv) {\n"
            + "  return texture2D(src, clamp(uv, 0.0, 1.0));\n"
            + "}\n"
            + TENT;

    /** Escala del búfer respecto a la pantalla (la capa la deshace). */
    static volatile float scale = 0.5f;

    private static EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private static EGLContext context = EGL14.EGL_NO_CONTEXT;
    private static EGLConfig config;
    private static EGLSurface window = EGL14.EGL_NO_SURFACE;
    private static boolean displayInitialized;
    private static volatile Thread glThread;

    private static void assertGlThread() {
        Thread current = Thread.currentThread();
        if (glThread == null) glThread = current;
        else if (glThread != current) {
            throw new IllegalStateException("TelonRenderer EGL context accessed from a non-render thread");
        }
    }

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

    private static void setNonBlockingSwapInterval() {
        if (!EGL14.eglSwapInterval(display, 0)) {
            int error = EGL14.eglGetError();
            if (error == 0x300E) throw new EglFailure("eglSwapInterval", error);
            // Some drivers require their default interval; this is a pacing hint, not a requirement.
        }
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

    private static void recover(Throwable error) {
        if (isContextLost(error)) {
            destroyEglContext();
        } else if (releaseLayer()) {
            destroyEglContext();
        }
    }
    private static int program, aRow, uM, uWin, uBend, uPower, uMargin, uScale, uBuf, uTexM, uRadius, uAlpha, uAa, uContent, uBlur;
    /** Una entrada de capturas: textura externa alimentada sin copias por una SurfaceTexture. */
    private static final class Input {
        int texture;
        SurfaceTexture st;
        Surface surface;
        final float[] texMatrix = new float[16];
        boolean queued;
        Object owner;
        int w, h;

        void release() {
            if (surface != null) {
                try { surface.release(); } catch (Throwable ignored) {}
                surface = null;
            }
            if (st != null) {
                try { st.release(); } catch (Throwable ignored) {}
                st = null;
            }
            texture = 0;
            queued = false;
            owner = null;
            w = h = 0;
        }
    }

    /** 0 = la ventana que se mueve encima; 1 = la de debajo. */
    static final int TOP = 0, BELOW = 1;
    private static final Input[] inputs = {new Input(), new Input()};
    private static final Object queueLock = new Object();
    private static int programBelow, bM, bWin, bMargin, bScale, bBuf, bTexM, bRadius, bAlpha, bAa, bContent, bBlurred, bBtexel, bAmount, bSoft, bDim, bRow, bBend, bPower;
    private static int programDownExt, dePos, deSrc, deTexM, deStep, programDown, dPos, dSrc, dStep;
    /** Texturas y framebuffers del desenfoque (1/4 y 1/8 de la captura de la de debajo). */
    private static final int[] fboTex = new int[2], fbo = new int[2], fboW = new int[2], fboH = new int[2];
    private static final FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f});
    private static final FloatBuffer mesh = buildMesh();

    static SurfaceControl layer;
    private static Surface output;
    private static int width, height;
    private static float layerScale;

    private static FloatBuffer buildMesh() {
        FloatBuffer b = ByteBuffer.allocateDirect((ROWS + 1) * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int i = 0; i <= ROWS; i++) {
            float t = i / (float) ROWS;
            b.put(t).put(0f).put(t).put(1f);
        }
        b.position(0);
        return b;
    }

    /** Capa y contexto para una pantalla de w × h (se reutilizan entre transiciones). */
    static boolean ensure(SurfaceControl root, int w, int h) {
        assertGlThread();
        if (root == null || !root.isValid() || w <= 0 || h <= 0) return false;
        float s = Math.max(0.3f, Math.min(1f, scale));
        int bw = Math.round(w * s), bh = Math.round(h * s);
        if (layer != null && layer.isValid() && window != EGL14.EGL_NO_SURFACE && bw == width && bh == height
                && Math.abs(s - layerScale) < 0.001f) {
            return true;
        }
        if (releaseLayer()) destroyEglContext();
        try {
            initContext();
            SurfaceControl sc = new SurfaceControl.Builder()
                    .setName("Telon")
                    .setBufferSize(bw, bh)
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .setOpaque(false)
                    .setParent(root)
                    .build();
            layer = sc;
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.setVisibility(sc, false);
            Matrix m = new Matrix();
            m.setScale(1f / s, 1f / s);
            Hidden.setMatrix(t, sc, m, new float[9]);
            t.apply();
            output = new Surface(sc);
            window = EGL14.eglCreateWindowSurface(display, config, output, new int[]{EGL14.EGL_NONE}, 0);
            if (window == EGL14.EGL_NO_SURFACE) throw new EglFailure("eglCreateWindowSurface", EGL14.eglGetError());
            makeCurrent();
            setNonBlockingSwapInterval();
            width = bw;
            height = bh;
            layerScale = s;
            return true;
        } catch (Throwable e) {
            Log.e("no se pudo preparar OpenGL", e);
            recover(e);
            return false;
        }
    }

    /** La raíz de la transición es nueva cada vez: la capa se cuelga de la actual. */
    static void attach(SurfaceControl.Transaction t, SurfaceControl root) {
        if (layer != null && root != null && root.isValid()) t.reparent(layer, root);
    }

    private static boolean initContext() {
        assertGlThread();
        if (context != EGL14.EGL_NO_CONTEXT && displayInitialized) return true;

        EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) throw new EglFailure("eglGetDisplay", EGL14.eglGetError());

            int[] version = new int[2];
            checkEgl(EGL14.eglInitialize(display, version, 0, version, 1), "eglInitialize");
            displayInitialized = true;
            checkEgl(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API), "eglBindAPI(OpenGL ES)");

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

            program = link(VERTEX, FRAGMENT);
            aRow = GLES20.glGetAttribLocation(program, "aRow");
            uM = GLES20.glGetUniformLocation(program, "M");
            uWin = GLES20.glGetUniformLocation(program, "win");
            uBend = GLES20.glGetUniformLocation(program, "bend");
            uPower = GLES20.glGetUniformLocation(program, "power");
            uMargin = GLES20.glGetUniformLocation(program, "margin");
            uScale = GLES20.glGetUniformLocation(program, "scale");
            uBuf = GLES20.glGetUniformLocation(program, "buf");
            uTexM = GLES20.glGetUniformLocation(program, "texM");
            uRadius = GLES20.glGetUniformLocation(program, "radius");
            uAlpha = GLES20.glGetUniformLocation(program, "alpha");
            uAa = GLES20.glGetUniformLocation(program, "aa");
            uContent = GLES20.glGetUniformLocation(program, "content");
            uBlur = GLES20.glGetUniformLocation(program, "blur");

            programBelow = link(VERTEX, FRAGMENT_BELOW);
            bRow = GLES20.glGetAttribLocation(programBelow, "aRow");
            bM = GLES20.glGetUniformLocation(programBelow, "M");
            bWin = GLES20.glGetUniformLocation(programBelow, "win");
            bBend = GLES20.glGetUniformLocation(programBelow, "bend");
            bPower = GLES20.glGetUniformLocation(programBelow, "power");
            bMargin = GLES20.glGetUniformLocation(programBelow, "margin");
            bScale = GLES20.glGetUniformLocation(programBelow, "scale");
            bBuf = GLES20.glGetUniformLocation(programBelow, "buf");
            bTexM = GLES20.glGetUniformLocation(programBelow, "texM");
            bRadius = GLES20.glGetUniformLocation(programBelow, "radius");
            bAlpha = GLES20.glGetUniformLocation(programBelow, "alpha");
            bAa = GLES20.glGetUniformLocation(programBelow, "aa");
            bContent = GLES20.glGetUniformLocation(programBelow, "content");
            bBlurred = GLES20.glGetUniformLocation(programBelow, "blurred");
            bBtexel = GLES20.glGetUniformLocation(programBelow, "btexel");
            bAmount = GLES20.glGetUniformLocation(programBelow, "amount");
            bSoft = GLES20.glGetUniformLocation(programBelow, "soft");
            bDim = GLES20.glGetUniformLocation(programBelow, "dim");

            programDownExt = link(VERTEX_QUAD, FRAGMENT_DOWN_EXT);
            dePos = GLES20.glGetAttribLocation(programDownExt, "aPos");
            deSrc = GLES20.glGetUniformLocation(programDownExt, "src");
            deTexM = GLES20.glGetUniformLocation(programDownExt, "texM");
            deStep = GLES20.glGetUniformLocation(programDownExt, "step");
            programDown = link(VERTEX_QUAD, FRAGMENT_DOWN);
            dPos = GLES20.glGetAttribLocation(programDown, "aPos");
            dSrc = GLES20.glGetUniformLocation(programDown, "src");
            dStep = GLES20.glGetUniformLocation(programDown, "step");

            int[] tex = new int[2];
            GLES20.glGenTextures(2, tex, 0);
            if (tex[0] == 0 || tex[1] == 0) throw new IllegalStateException("glGenTextures returned 0");
            synchronized (queueLock) {
                for (int i = 0; i < inputs.length; i++) {
                    Input in = inputs[i];
                    in.texture = tex[i];
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, in.texture);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                    in.st = new SurfaceTexture(in.texture);
                    in.surface = new Surface(in.st);
                    in.queued = false;
                    in.owner = null;
                    in.w = in.h = 0;
                }
            }
            checkGlError("initialize curtain renderer");

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

    private static int link(String vs, String fs) {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, vs);
        int pixel = 0;
        int programId = 0;
        try {
            pixel = compile(GLES20.GL_FRAGMENT_SHADER, fs);
            programId = GLES20.glCreateProgram();
            if (programId == 0) throw new IllegalStateException("glCreateProgram returned 0");
            GLES20.glAttachShader(programId, vertex);
            GLES20.glAttachShader(programId, pixel);
            GLES20.glLinkProgram(programId);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("enlace: " + GLES20.glGetProgramInfoLog(programId));
            return programId;
        } catch (Throwable t) {
            if (programId != 0) GLES20.glDeleteProgram(programId);
            throw t;
        } finally {
            GLES20.glDeleteShader(vertex);
            if (pixel != 0) GLES20.glDeleteShader(pixel);
        }
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

    private static void makeCurrent() {
        assertGlThread();
        if (display == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT
                || window == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("EGL renderer is not initialized");
        }
        checkEgl(EGL14.eglMakeCurrent(display, window, window, context), "eglMakeCurrent(window)");
    }

    // ------------------------------------------------------------------ contenido

    /** Hilo de capturas: deja la captura de una ventana lista para el siguiente dibujo. */
    static boolean queue(int slot, HardwareBuffer b, ColorSpace cs, Object owner) {
        if (slot < 0 || slot >= inputs.length || b == null) return false;
        synchronized (queueLock) {
            Input in = inputs[slot];
            if (in.surface == null || in.queued) return false;
            if (!Hidden.queueBuffer(in.surface, b, cs)) return false;
            in.queued = true;
            in.owner = owner;
            in.w = b.getWidth();
            in.h = b.getHeight();
            return true;
        }
    }

    /** Hilo de dibujo: pasa la captura a su textura; devuelve de quién era (o null). */
    static Object consume(int slot) {
        assertGlThread();
        synchronized (queueLock) {
            Input in = inputs[slot];
            if (!in.queued || in.st == null) return null;
            in.queued = false;
            try {
                in.st.updateTexImage();
                in.st.getTransformMatrix(in.texMatrix);
                Object owner = in.owner;
                in.owner = null;
                return owner;
            } catch (Throwable e) {
                in.owner = null;
                Log.e("textura de la captura", e);
                if (isContextLost(e)) recover(e);
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ dibujo

    private static final float[] m3 = new float[9], gl3 = new float[9];

    /** Textura + framebuffer {@code i} de w × h (se rehacen si cambia el tamaño). */
    private static void fboEnsure(int i, int w, int h) {
        if (fbo[i] != 0 && fboW[i] == w && fboH[i] == h) return;
        int[] v = new int[1];
        if (fboTex[i] == 0) {
            GLES20.glGenTextures(1, v, 0);
            fboTex[i] = v[0];
            if (fboTex[i] == 0) throw new IllegalStateException("Could not allocate blur texture");
        }
        if (fbo[i] == 0) {
            GLES20.glGenFramebuffers(1, v, 0);
            fbo[i] = v[0];
            if (fbo[i] == 0) throw new IllegalStateException("Could not allocate blur framebuffer");
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0);
        int st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("framebuffer " + st);
        fboW[i] = w;
        fboH[i] = h;
    }

    private static void drawQuad(int pos) {
        quad.position(0);
        GLES20.glEnableVertexAttribArray(pos);
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pos);
    }

    /**
     * Desenfoca la captura de la de debajo una sola vez (está quieta): a 1/4 y luego a 1/8 de su
     * tamaño, con un filtro en tienda en cada paso. {@code spread} separa las muestras (más =
     * más desenfoque). Dos pasadas a texturas diminutas: casi gratis incluso en una Mali-G52.
     */
    static boolean prepareBelow(float spread) {
        assertGlThread();
        try {
            makeCurrent();
            Input in = inputs[BELOW];
            int w1 = Math.max(8, in.w / 4), h1 = Math.max(8, in.h / 4);
            int w2 = Math.max(4, w1 / 2), h2 = Math.max(4, h1 / 2);
            fboEnsure(0, w1, h1);
            fboEnsure(1, w2, h2);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
            GLES20.glViewport(0, 0, w1, h1);
            GLES20.glUseProgram(programDownExt);
            GLES20.glUniformMatrix4fv(deTexM, 1, false, in.texMatrix, 0);
            GLES20.glUniform2f(deStep, spread / Math.max(1, in.w), spread / Math.max(1, in.h));
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, in.texture);
            GLES20.glUniform1i(deSrc, 0);
            drawQuad(dePos);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[1]);
            GLES20.glViewport(0, 0, w2, h2);
            GLES20.glUseProgram(programDown);
            GLES20.glUniform2f(dStep, spread / w1, spread / h1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[0]);
            GLES20.glUniform1i(dSrc, 0);
            drawQuad(dPos);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            checkGlError("prepare curtain blur");
            return true;
        } catch (Throwable e) {
            Log.e("no se pudo desenfocar la de debajo", e);
            recover(e);
            if (!isContextLost(e)) {
                try {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                } catch (Throwable ignored) {
                }
            }
            return false;
        }
    }

    /** Empieza un fotograma (búfer transparente). Luego {@link #drawBelow}, {@link #drawTop}, {@link #end}. */
    static boolean begin() {
        assertGlThread();
        try {
            makeCurrent();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);   // premultiplicado
            return true;
        } catch (Throwable e) {
            Log.e("no se pudo dibujar", e);
            recover(e);
            return false;
        }
    }

    private static float matrix(Matrix m) {
        m.getValues(m3);
        // android.graphics.Matrix va por filas; GLSL, por columnas.
        gl3[0] = m3[0]; gl3[1] = m3[3]; gl3[2] = m3[6];
        gl3[3] = m3[1]; gl3[4] = m3[4]; gl3[5] = m3[7];
        gl3[6] = m3[2]; gl3[7] = m3[5]; gl3[8] = m3[8];
        return (float) Math.hypot(m3[0], m3[3]);
    }

    /**
     * La ventana que se mueve: con la matriz de HyperOS ({@code m}, de la ventana a la raíz), su
     * transparencia, sus esquinas, el doblez {@code bend} (px de la fila de abajo) y el desenfoque
     * de movimiento {@code blur} (largo de la estela, px de la ventana; 0 = nítido).
     */
    static boolean drawTop(Matrix m, float alpha, float winW, float winH, float radius, float bend, float blur) {
        assertGlThread();
        try {
            GLES20.glUseProgram(program);
            float sx = matrix(m);
            GLES20.glUniformMatrix3fv(uM, 1, false, gl3, 0);
            GLES20.glUniform2f(uWin, winW, winH);
            GLES20.glUniform1f(uBend, bend);
            GLES20.glUniform1f(uPower, power);
            GLES20.glUniform1f(uMargin, MARGIN);
            GLES20.glUniform1f(uScale, layerScale);
            GLES20.glUniform2f(uBuf, width, height);
            GLES20.glUniformMatrix4fv(uTexM, 1, false, inputs[TOP].texMatrix, 0);
            GLES20.glUniform1f(uRadius, radius);
            GLES20.glUniform1f(uAlpha, alpha);
            GLES20.glUniform1f(uBlur, blur);
            GLES20.glUniform1f(uAa, Math.max(0.05f, sx * layerScale));
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputs[TOP].texture);
            GLES20.glUniform1i(uContent, 0);
            mesh.position(0);
            GLES20.glEnableVertexAttribArray(aRow);
            GLES20.glVertexAttribPointer(aRow, 2, GLES20.GL_FLOAT, false, 0, mesh);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, (ROWS + 1) * 2);
            return true;
        } catch (Throwable e) {
            Log.e("no se pudo dibujar", e);
            recover(e);
            return false;
        }
    }

    /**
     * La ventana de debajo con su matriz de HyperOS: nítida a {@code amount} 0, desenfocada a 1,
     * oscurecida {@code dim} (lo que la oscurecería HyperOS: su capa de color se oculta con ella).
     */
    static boolean drawBelow(Matrix m, float alpha, float winW, float winH, float radius, float dim, float amount) {
        assertGlThread();
        try {
            GLES20.glUseProgram(programBelow);
            float sx = matrix(m);
            GLES20.glUniformMatrix3fv(bM, 1, false, gl3, 0);
            GLES20.glUniform2f(bWin, winW, winH);
            GLES20.glUniform1f(bBend, 0f);
            GLES20.glUniform1f(bPower, power);
            GLES20.glUniform1f(bMargin, MARGIN);
            GLES20.glUniform1f(bScale, layerScale);
            GLES20.glUniform2f(bBuf, width, height);
            Input in = inputs[BELOW];
            GLES20.glUniformMatrix4fv(bTexM, 1, false, in.texMatrix, 0);
            GLES20.glUniform1f(bRadius, radius);
            GLES20.glUniform1f(bAlpha, alpha);
            GLES20.glUniform1f(bAa, Math.max(0.05f, sx * layerScale));
            GLES20.glUniform2f(bBtexel, 1f / Math.max(1, fboW[1]), 1f / Math.max(1, fboH[1]));
            GLES20.glUniform1f(bAmount, Math.max(0f, Math.min(1f, amount)));
            GLES20.glUniform1f(bDim, dim);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, in.texture);
            GLES20.glUniform1i(bContent, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[1]);
            GLES20.glUniform1i(bBlurred, 1);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[0]);
            GLES20.glUniform1i(bSoft, 2);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            mesh.position(0);
            GLES20.glEnableVertexAttribArray(bRow);
            GLES20.glVertexAttribPointer(bRow, 2, GLES20.GL_FLOAT, false, 0, mesh);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, (ROWS + 1) * 2);
            return true;
        } catch (Throwable e) {
            Log.e("no se pudo dibujar", e);
            recover(e);
            return false;
        }
    }

    /** Termina el fotograma y lo manda al compositor. */
    static boolean end() {
        assertGlThread();
        try {
            checkGlError("end curtain frame");
            checkEgl(EGL14.eglSwapBuffers(display, window), "eglSwapBuffers");
            return true;
        } catch (Throwable e) {
            Log.e("no se pudo dibujar", e);
            recover(e);
            return false;
        }
    }

    /** Releases the layer's EGL window surface but retains the reusable GL context. */
    static boolean releaseLayer() {
        assertGlThread();
        boolean contextLost = false;
        EGLSurface oldWindow = window;
        window = EGL14.EGL_NO_SURFACE;
        if (display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT) {
            try {
                if (!EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) {
                    contextLost = EGL14.eglGetError() == 0x300E;
                }
            } catch (Throwable ignored) {
            }
        }
        if (oldWindow != EGL14.EGL_NO_SURFACE && display != EGL14.EGL_NO_DISPLAY) {
            try {
                if (!EGL14.eglDestroySurface(display, oldWindow)) {
                    contextLost |= EGL14.eglGetError() == 0x300E;
                }
            } catch (Throwable ignored) {}
        }

        Surface oldOutput = output;
        output = null;
        if (oldOutput != null) {
            try { oldOutput.release(); } catch (Throwable ignored) {}
        }

        SurfaceControl oldLayer = layer;
        layer = null;
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
        layerScale = 0f;
        return contextLost;
    }

    /** Context loss invalidates every program, texture, and framebuffer name. */
    private static void destroyEglContext() {
        assertGlThread();
        releaseLayer();

        synchronized (queueLock) {
            for (Input in : inputs) in.release();
        }

        if (display != EGL14.EGL_NO_DISPLAY && displayInitialized) {
            try {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            } catch (Throwable ignored) {
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                try { EGL14.eglDestroyContext(display, context); } catch (Throwable ignored) {}
            }
            try { EGL14.eglTerminate(display); } catch (Throwable ignored) {}
        }

        context = EGL14.EGL_NO_CONTEXT;
        display = EGL14.EGL_NO_DISPLAY;
        config = null;
        displayInitialized = false;

        program = programBelow = programDownExt = programDown = 0;
        aRow = uM = uWin = uBend = uPower = uMargin = uScale = uBuf = uTexM = uRadius = uAlpha = uAa = uContent = uBlur = 0;
        bM = bWin = bMargin = bScale = bBuf = bTexM = bRadius = bAlpha = bAa = bContent = bBlurred = bBtexel = bAmount = bSoft = bDim = bRow = bBend = bPower = 0;
        dePos = deSrc = deTexM = deStep = dPos = dSrc = dStep = 0;
        for (int i = 0; i < 2; i++) {
            fboTex[i] = fbo[i] = fboW[i] = fboH[i] = 0;
        }
    }

    private TelonRenderer() {
    }
}
