package com.icehome.up.cc;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;
import com.icehome.up.xp.XposedHelpers;

/**
 * Centro de control con el material de cristal de HyperOS 4 (plugin miui.systemui.plugin, de
 * serie o los mods de Kashi, también el estilo iOS).
 *
 * Qué es una pieza de cristal: cualquier vista del plugin a la que el sistema le pone el
 * desenfoque mezclado de MIUI (View.setMiBackgroundBlendColors). Se engancha ese método del
 * framework, así entran también las piezas propias de Kashi (tiles de sonido, auriculares, fila
 * superior, etc.) sin tener que conocer cada clase. Si el framework no lo tiene, se usan los
 * cuatro puntos de serie (botones, tarjetas, sliders, tarjetas del panel).
 *
 * Encendido/apagado se lee del propio desenfoque: el color SRC_OVER de la mezcla es casi
 * transparente en reposo y opaco (azul/blanco) cuando la pieza está activa; durante la
 * animación de MIUI la mezcla se interpola, así el cristal la sigue fotograma a fotograma.
 *
 * Cristal real: al empezar a abrirse el centro de control se hace una captura de lo que hay
 * debajo (sin su propia ventana), reducida y desenfocada. Cada pieza la usa para refractar
 * el canto y teñir su borde del color de detrás (GlassShader). Si la captura no está
 * disponible, el canto se ilumina sin refracción.
 * Si el framework tiene el material nativo de HyperOS 4 (View.setMiGlass), se usa ese.
 */
public final class CcGlass {

    // ---------------------------------------------------------------- ajustes
    static final int BUTTON = 0, CARD = 1;
    /** Velo blanco cuando la pieza está apagada. Encendida: 0. */
    static final int VEIL_BUTTON = 0x24, VEIL_CARD = 0x1C;
    static final int SHEEN_OFF = 0x08, SHEEN_ON = 0x06;
    static final float RIM_DP = 1.0f;
    static final int RIM_TOP = 0xD0, RIM_SIDE = 0x26, RIM_BOTTOM = 0x70;
    static final float BAND_DP = 7f, BAND_MAX_FRACTION = 0.16f;
    /** Refracción del canto (con captura): desplazamiento, opacidad y tono de lo de detrás. */
    static final float DISP_DP = 8f, DISPERSION = 0.2f, BAND_ALPHA = 0.6f, TONE = 0.82f;
    /** Canto de luz (núcleo + halo hacia dentro), como el bloom stroke de las fotos del mod iOS. */
    static final float TINT_RIM_DP = 0.8f, GLOW_DP = 0f, TINT_RIM_GAIN = 1.0f, SATURATION = 1.6f;
    /** Velo en piezas que el mod ya pinta como cristal (su propio fondo): más suave. */
    static final int VEIL_STRONG = 0x10;
    /** Captura: un píxel cada 3 dp, desenfoque de 10 dp (el del sistema es mayor). */
    static final float SAMPLE_DP = 1.5f, BACKDROP_BLUR_DP = 9f;
    /** Fondo vibrante: muy suave, como el de HyperOS 4. */
    static final float BG_SAMPLE_DP = 6f, BG_BLUR_DP = 50f;
    /**
     * Cristal completo (con captura): bajo el contenido de cada pieza se dibuja lo que hay detrás
     * procesado como el material "Bionics" de HyperOS 4 (DEFAULT_GLASS_TOKEN): saturación 1,4,
     * luces altas atenuadas, velo blanco del 10 %, y un canto grueso que lo refracta.
     */
    static final boolean FULL_GLASS = true;
    /** Quitar la mezcla oscura de MIUI de las piezas con cristal completo (ver syncMiBlur). */
    static final boolean CLEAR_MIUI_BLEND = true;
    /** Luz interior de la lente (encima): baja para que el borde se vea fino. */
    static final float LENS_LIGHT = 0.04f;
    /** Brillo "glossy" superior sobre el cristal completo. */
    static final int GLOSS_TOP = 0x40;
    /** Detección por recorrido: tamaño mínimo y radio mínimo de una ficha con fondo propio. */
    static final float AUTO_MIN_DP = 40f, AUTO_MIN_RADIUS_DP = 8f;
    /** Fondo del centro de control: capa vibrante (captura saturada) sobre el desenfoque del sistema. */
    static final boolean VIBRANT_BACKGROUND = false;
    /** Oscurecido del desenfoque del sistema (0..1), bajo el contenido y según la apertura. */
    static final float BG_DIM = 0.22f;
    private static final Paint DIM_PAINT = new Paint();   // fondo = desenfoque real del sistema (como las notificaciones)
    static final float BG_SAT = 1.4f, BG_ALPHA = 0.95f, BG_GAIN = 0.76f;
    /** Neblina del fondo (0..255 sumado): el aspecto "vidrioso" de HyperOS 4. */
    static final float BG_HAZE = 16f;
    static final float BIO_SAT = 1.55f, BIO_DARKER = 0.08f, BIO_WHITE = 0.03f, BIO_TONE = 1.1f, BIO_EDGE_LIFT = 0f;
    /** Botones redondos apagados: casi sin color propio, gris translúcido como HyperOS 4. */
    static final float BIO_SAT_BUTTON = 1.3f;
    /** Círculos apagados: sin aclarar, translúcidos como en HyperOS 4. */
    static final float BUTTON_TONE = 1.07f;
    static final int GLOSS_BUTTON = 0x10;
    /** Línea interior tenue y oscura bajo la raya blanca: da el contorno nítido. */
    static final float INNER_DARK = 0.02f;
    /** Canto como HyperOS 4: el color de lo que hay junto al borde se estira hacia dentro. */
    static final float EDGE_STRETCH = 1f, EDGE_OVER_DP = 3f, EDGE_SAT = 0.4f;
    static final float THICK_DP = 3f, THICK_MAX_FRACTION = 0.07f, THICK_DISP_DP = 0f, THICK_DISPERSION = 0.06f;
    static final float BIO_MAGNIFY = 0.035f;
    /** Tamaño mínimo de una pieza (los iconos sueltos con mezcla no son cristal). */
    static final float MIN_DP = 34f;
    static final long ACTIVE_ANIM_MS = 260;
    /**
     * Material nativo de HyperOS 4 (View.setMiGlass). Desactivado: en ROMs de HyperOS 3 que traen
     * el método pero no el motor, la llamada no hace nada y el centro de control se queda igual.
     */
    static final boolean USE_NATIVE = false;

    // ---------------------------------------------------------------- estado
    private static final Set<ClassLoader> PLUGIN = Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    /**
     * Pieza → su cristal. El cristal vive en el overlay de la vista; aquí solo se guarda una
     * referencia débil, así las vistas que el sistema descarta (al reciclar o recargar el
     * plugin) se liberan. Antes el mapa las retenía y, con el tiempo, crecía sin parar.
     */
    private static final SkinMap SKINS = new SkinMap();

    private static final class SkinMap {
        private final WeakHashMap<View, java.lang.ref.WeakReference<Skin>> m = new WeakHashMap<>();

        Skin get(View v) {
            java.lang.ref.WeakReference<Skin> r = m.get(v);
            return r == null ? null : r.get();
        }

        void put(View v, Skin s) {
            m.put(v, new java.lang.ref.WeakReference<>(s));
        }

        int size() {
            return m.size();
        }

        java.util.List<Skin> values() {
            java.util.List<Skin> out = new ArrayList<>(m.size());
            for (java.lang.ref.WeakReference<Skin> r : m.values()) {
                Skin s = r.get();
                if (s != null) out.add(s);
            }
            return out;
        }

        java.util.List<Map.Entry<View, Skin>> entrySet() {
            java.util.List<Map.Entry<View, Skin>> out = new ArrayList<>(m.size());
            for (Map.Entry<View, java.lang.ref.WeakReference<Skin>> e : m.entrySet()) {
                Skin s = e.getValue().get();
                if (e.getKey() != null && s != null) out.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), s));
            }
            return out;
        }
    }

    /** Cristal de una vista; null si nunca lo tuvo o si el recolector ya lo liberó. */
    private static Skin skin(View view) {
        return view == null ? null : SKINS.get(view);
    }

    private static boolean shaderBroken, frameworkHooked, frameworkTried;
    /**
     * Fallos seguidos al dibujar con el shader o al capturar. Un fallo suelto (p. ej. una captura
     * mientras hay una app protegida en pantalla) ya no apaga el cristal para siempre: solo tras
     * varios seguidos. Un acierto pone el contador a cero.
     */
    private static int shaderFails, captureFails;
    static final int SHADER_FAILS_MAX = 5, CAPTURE_FAILS_MAX = 3;

    private static void shaderFailed(String what, Throwable t) {
        once(what, t);
        if (++shaderFails >= SHADER_FAILS_MAX && !shaderBroken) {
            shaderBroken = true;
            note("HyperSpring CC: shader desactivado tras " + SHADER_FAILS_MAX + " fallos seguidos (" + what + ")");
        }
    }

    // Motor nativo de HyperOS 4
    private static Method setMaterial, setGlass;
    private static boolean nativeResolved;
    private static final float[] DEFAULT_GLASS = {0.67f, 0.16f, 0.09f, 0.0f, 0.24f, 1.4f, -0.02f, 0.3f, 0.6f, 1.0f, 0.03f, 1.0f, 1.0f, 1.0f, 0.1f, 0.2f, 0.3f, 1.0f, 1.0f, 72.0f, 3.8f, 80.0f, 800.0f, 1.2f, 1.0f, -0.4f, 0.6f, -0.8f, 1.4f, 0.7f, 0.8f, 1.15f, 4.0f, 2.0f, 0.0f, 0.0f, 0.0f};
    private static final float[] ACTIVATED_GLASS = {1.24f, 0.64f, 0.24f, 0.0f, 0.32f, 2.6f, -0.02f, 0.42f, 0.6f, 1.0f, 0.05f, 1.0f, 1.0f, 1.0f, 1.4f, 0.15f, 0.9f, 0.4f, 1.0f, 72.0f, 3.8f, 80.0f, 800.0f, 1.2f, 1.0f, -0.4f, 0.6f, -0.8f, 3.0f, 2.0f, 0.8f, 1.15f, 4.0f, 2.0f, 0.0f, 0.0f, 0.0f};

    /** En el proceso de SystemUI (antes de que cargue el plugin). */
    public static void installFramework() {
        synchronized (CcGlass.class) {
            if (frameworkTried) return;
            frameworkTried = true;
        }
        resolveNative();
        int n = 0;
        for (Method m : View.class.getDeclaredMethods()) {
            Class<?>[] pt = m.getParameterTypes();
            try {
                if (m.getName().equals("setMiBackgroundBlendColors") && pt.length == 1 && pt[0] == ArrayList.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (selfCall) return;
                            onBlend((View) p.thisObject, (ArrayList<?>) p.args[0]);
                        }
                    });
                    n++;
                } else if ((m.getName().equals("setMiViewBlurMode") && pt.length == 1 && pt[0] == int.class)
                        || (m.getName().equals("clearMiBackgroundBlendColor") && pt.length == 0)) {
                    final boolean clear = pt.length == 0;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (selfCall) return;
                            if (clear || (Integer) p.args[0] == 0) off((View) p.thisObject);
                        }
                    });
                }
            } catch (Throwable t) {
                once("fw " + m.getName(), t);
            }
        }
        frameworkHooked = n > 0;
        if (FULL_GLASS) {
            try {
                XposedHelpers.findAndHookMethod(View.class, "drawBackground", Canvas.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        View v = (View) p.thisObject;
                        if (ccRoot != null && ccRoot.get() == v) {
                            if (VIBRANT_BACKGROUND) drawVibrantBackground(v, (Canvas) p.args[0]);
                            else if (Tune.ccOn && Tune.dim > 0f && ccRatio > 0.01f) {
                                DIM_PAINT.setColor(Math.round(255 * Tune.dim * Math.min(1f, ccRatio)) << 24);
                                ((Canvas) p.args[0]).drawRect(0, 0, v.getWidth(), v.getHeight(), DIM_PAINT);
                            }
                            return;
                        }
                        Skin s = skin(v);
                        if (s != null) s.drawUnder((Canvas) p.args[0]);
                    }
                });
            } catch (Throwable t) {
                once("drawBackground", t);
            }
        }
        note("HyperSpring CC: desenfoque MIUI " + (frameworkHooked ? "enganchado" : "no encontrado")
                + ", material nativo " + (setGlass != null ? "presente" : "ausente") + (hasNative() ? " (en uso)" : " (motor propio)"));
    }

    /** Con el cargador de clases del plugin. */
    public static void install(ClassLoader cl) {
        synchronized (PLUGIN) {
            if (!PLUGIN.add(cl)) return;
        }
        installFramework();
        hookOpen(cl);
        try {
            hookIos(cl);
        } catch (Throwable t) {
            once("ios", t);
        }
        try {
            installFallback(cl);      // las piezas de serie también por su clase (no se duplican)
        } catch (Throwable t) {
            once("fallback", t);
        }
        try {
            hookIconBackgrounds(cl);
        } catch (Throwable t) {
            once("icon hooks", t);
        }
        note("HyperSpring CC: plugin enganchado");
        com.icehome.up.telon.Log.state("cc_seen", System.currentTimeMillis() + "|" + (XposedHelpers.findClassIfExists("com.kashi.ios.Ios", cl) != null ? "ios" : "stock"));
    }

    // ---------------------------------------------------------------- piezas por el desenfoque MIUI
    private static void onBlend(View v, ArrayList<?> list) {
        try {
            if (v instanceof TextView) return;
            if (v.getParent() != null && !ours(v)) return;     // sin padre aún: se decide al colocarse
            float a = 0f;
            if (list != null) {
                for (Object o : list) {
                    if (!(o instanceof Point)) continue;
                    Point pt = (Point) o;
                    if (pt.y == 3) a = Math.max(a, ((pt.x >>> 24) & 255) / 255f);   // SRC_OVER
                }
            }
            float active = clamp01((a - 0.25f) / 0.65f);
            Skin prev = skin(v);
            apply(v, prev != null && prev.pinned ? -1f : active, false);   // fijada por su clase: no se pisa
            Skin s = skin(v);
            if (s == null) return;
            if (s.blendActive != active) {
                s.blendActive = active;             // la mezcla también dice si está encendida
                s.invalidateSelf();
                v.invalidate();
            }
            s.lastBlend = list;
            s.miCleared = false;                    // el sistema acaba de ponerle su mezcla
            // Icono de botón: su fondo (encendido/apagado) depende de este estado.
            if (v.getParent() != null && v.getParent().getClass().getName().endsWith("QSTileItemIconView")) {
                clearIconBackground(v.getParent());
            }
            s.syncMiBlur();
        } catch (Throwable t) {
            once("blend", t);
        }
    }

    /** ¿Pertenece al centro de control? Por el contexto del plugin o por sus vistas padre. */
    static boolean ours(View v) {
        android.content.Context c = v.getContext();
        if (c != null) {
            if ("miui.systemui.plugin".equals(c.getPackageName())) return true;
            ClassLoader cl = c.getClassLoader();
            synchronized (PLUGIN) {
                if (cl != null && PLUGIN.contains(cl)) return true;
            }
        }
        int depth = 0;
        for (Object o = v; o instanceof View && depth < 40; o = ((View) o).getParent(), depth++) {
            String n = o.getClass().getName();
            if (n.startsWith("miui.systemui.") || n.startsWith("com.kashi.") || n.contains("ControlCenter")) return true;
            if (n.startsWith("com.android.systemui.")) return false;
        }
        return false;
    }

    // ---------------------------------------------------------------- quitar la mezcla de MIUI
    /**
     * El desenfoque mezclado de MIUI (setMiViewBlurMode 1 + colores de mezcla) se aplica al
     * contenido de la vista, también a nuestro cristal: en HyperOS 3 lo oscurece y lo apaga.
     * Mientras el cristal completo está activo y la pieza apagada, se quita; al encenderse (el
     * color lo pone la mezcla) o sin captura, se restaura tal y como la dejó el sistema.
     */
    private static volatile boolean selfCall;
    private static Method mViewBlur, mClearBlend, mSetBlend, mBgBlurMode;
    private static boolean miResolved;

    private static void resolveMi() {
        if (miResolved) return;
        miResolved = true;
        try {
            mViewBlur = View.class.getMethod("setMiViewBlurMode", int.class);
        } catch (Throwable ignored) {
        }
        try {
            mClearBlend = View.class.getMethod("clearMiBackgroundBlendColor");
        } catch (Throwable ignored) {
        }
        try {
            mSetBlend = View.class.getMethod("setMiBackgroundBlendColors", ArrayList.class);
        } catch (Throwable ignored) {
        }
        try {
            mBgBlurMode = View.class.getMethod("setMiBackgroundBlurMode", int.class);
        } catch (Throwable ignored) {
        }
    }

    static void miClear(View v, boolean bgBlur) {
        resolveMi();
        selfCall = true;
        try {
            if (mClearBlend != null) XposedBridge.invokeOriginalMethod(mClearBlend, v, new Object[0]);
            if (mViewBlur != null) XposedBridge.invokeOriginalMethod(mViewBlur, v, new Object[]{0});
            if (bgBlur && mBgBlurMode != null) XposedBridge.invokeOriginalMethod(mBgBlurMode, v, new Object[]{0});
        } catch (Throwable t) {
            once("mi clear", t);
        } finally {
            selfCall = false;
        }
    }

    static void miRestore(View v, ArrayList<?> blend) {
        resolveMi();
        selfCall = true;
        try {
            if (mViewBlur != null) XposedBridge.invokeOriginalMethod(mViewBlur, v, new Object[]{1});
            if (blend != null && mSetBlend != null) XposedBridge.invokeOriginalMethod(mSetBlend, v, new Object[]{blend});
        } catch (Throwable t) {
            once("mi restore", t);
        } finally {
            selfCall = false;
        }
    }

    private static void off(View v) {
        Skin s = skin(v);
        if (s != null && !s.strong) s.setOn(false);
    }

    /** Pieza marcada como cristal por el propio mod (material, bloom stroke, IosGlass, círculos). */
    static void strong(View v) {
        if (v == null || v instanceof TextView) return;
        if (v.getParent() != null && !ours(v)) return;     // p. ej. notificaciones de SystemUI
        apply(v, -1f, false);
        Skin s = skin(v);
        if (s != null) s.strong = true;
    }

    static void strongOff(View v) {
        Skin s = v == null ? null : skin(v);
        if (s != null) {
            s.strong = false;
            s.setOn(false);
        }
    }

    // ---------------------------------------------------------------- fondo oscuro de los botones
    private static final android.graphics.PorterDuffColorFilter CLEAR =
            new android.graphics.PorterDuffColorFilter(0, android.graphics.PorterDuff.Mode.SRC);

    /**
     * Los botones (QSTileItemIconView, también las fichas grandes del mod iOS, que lo estiran)
     * pintan su fondo como primera capa de la IMAGEN del icono, por encima del cristal. Apagados,
     * ese fondo gris se vuelve transparente para que se vea el cristal; encendidos se mantiene
     * (azul, verde, blanco…).
     */
    private static void hookIconBackgrounds(ClassLoader cl) {
        Class<?> c = find("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView", cl);
        if (c == null) return;
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                clearIconBackground(p.thisObject);
            }
        };
        hookAll(c, h, "setIconProgress", "updateIconInternal", "updateIcon", "liteIconUpdate",
                "updateResources", "setCornerRadius", "setEnabledBg", "setDisabledBg");
        XposedBridge.hookAllConstructors(c, h);
        Deopt.all(c);
    }

    private static void clearIconBackground(Object tv) {
        try {
            View icon = (View) XposedHelpers.getObjectField(tv, "icon");
            if (!(icon instanceof android.widget.ImageView)) return;
            float prog = 0f;
            try {
                prog = XposedHelpers.getFloatField(tv, "iconProgress");
            } catch (Throwable ignored) {
            }
            Skin sk = skin(icon);
            boolean on = prog >= 0.5f || (sk != null && sk.blendActive >= 0.5f);
            boolean solidBg = false;
            Drawable d = ((android.widget.ImageView) icon).getDrawable();
            if (d instanceof android.graphics.drawable.LayerDrawable
                    && ((android.graphics.drawable.LayerDrawable) d).getNumberOfLayers() >= 2) {
                Drawable bg = ((android.graphics.drawable.LayerDrawable) d).getDrawable(0);
                boolean keep = on && lit(bg);
                solidBg = keep;
                if (bg.getColorFilter() != (keep ? null : CLEAR)) {
                    bg.mutate();
                    bg.setColorFilter(keep ? null : CLEAR);
                    icon.invalidate();
                }
            }
            for (String f : new String[]{"disabledBg", "enabledBg"}) {
                Object o = XposedHelpers.getObjectField(tv, f);
                if (!(o instanceof Drawable)) continue;
                Drawable bg = (Drawable) o;
                boolean keep = lit(bg) && (on || f.equals("enabledBg"));
                if (f.equals("disabledBg")) keep = false;
                if (bg.getColorFilter() != (keep ? null : CLEAR)) bg.setColorFilter(keep ? null : CLEAR);
            }
            Skin s = skin(icon);
            if (s == null) {
                apply(icon, clamp01(prog), false);   // fichas sin mezcla de MIUI (grandes del mod)
                s = skin(icon);
            } else {
                s.setActive(clamp01(prog), false);
            }
            if (s != null) {
                s.pinned = true;                     // encendido según iconProgress, no según la mezcla
                s.solidContent = solidBg;            // encendido con fondo propio: sin la mezcla de MIUI
            }
        } catch (Throwable t) {
            once("icon bg", t);
        }
    }

    /** ¿Es un fondo "encendido" (claro o saturado)? */
    private static boolean lit(Drawable d) {
        if (d instanceof GradientDrawable) {
            android.content.res.ColorStateList cs = ((GradientDrawable) d).getColor();
            return cs != null && litColor(cs.getDefaultColor());
        }
        if (d instanceof android.graphics.drawable.ColorDrawable) {
            return litColor(((android.graphics.drawable.ColorDrawable) d).getColor());
        }
        return true;                        // desconocido: no se toca
    }

    // ---------------------------------------------------------------- recorrido de ventanas
    private static final Set<View> ROOTS = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static final Map<View, Runnable> WALKS = new WeakHashMap<>();

    /**
     * Vigila una ventana del centro de control: tras cada layout (debounce 120 ms) la recorre y
     * pone cristal a toda ficha con fondo propio redondeado que no lo tenga ya. Así entran las
     * piezas que no pasan por el desenfoque de MIUI ni por el material del mod (fichas grandes
     * oscuras, paneles de detalle, edición, menú de apagado…).
     */
    static void watchRoot(final View root) {
        if (root == null || !ROOTS.add(root)) return;
        final Runnable walk = new Runnable() {
            @Override
            public void run() {
                try {
                    walkInto(root, 0);
                } catch (Throwable t) {
                    once("walk", t);
                }
            }
        };
        WALKS.put(root, walk);
        final ViewTreeObserver.OnGlobalLayoutListener onLayout = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                root.removeCallbacks(walk);
                root.postDelayed(walk, 120);
            }
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(onLayout);
        // Si el sistema quita la ventana y la vuelve a poner, su ViewTreeObserver es otro: sin
        // volver a registrarse, las piezas nuevas se quedaban sin cristal "al cabo de un rato".
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.getViewTreeObserver().removeOnGlobalLayoutListener(onLayout);
                v.getViewTreeObserver().addOnGlobalLayoutListener(onLayout);
                root.postDelayed(walk, 120);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
            }
        });
        root.postDelayed(walk, 120);
    }

    /** Devuelve el área de la mayor pieza con cristal dentro de {@code v} (incluida). */
    private static long walkInto(View v, int depth) {
        if (depth > 48 || v.getVisibility() != View.VISIBLE) return 0;
        long best = 0;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) best = Math.max(best, walkInto(g.getChildAt(i), depth + 1));
        }
        Skin s = skin(v);
        if (s == null && candidate(v)) {
            apply(v, -1f, false);
            s = skin(v);
            if (s != null) s.auto = true;
        }
        if (s == null) return best;
        long area = (long) v.getWidth() * v.getHeight();
        boolean sup = best >= area * 0.9f;
        if (s.suppressed != sup) {
            s.suppressed = sup;
            s.invalidateSelf();
            v.invalidate();
        }
        s.syncMiBlur();
        return sup ? best : Math.max(best, area);
    }

    private static boolean candidate(View v) {
        if (v instanceof TextView || !ours(v)) return false;
        float d = v.getResources().getDisplayMetrics().density;
        int w = v.getWidth(), h = v.getHeight();
        if (Math.min(w, h) < AUTO_MIN_DP * d) return false;
        android.util.DisplayMetrics dm = v.getResources().getDisplayMetrics();
        if (w > dm.widthPixels * 0.92f && h > dm.heightPixels * 0.5f) return false;
        Drawable bg = v.getBackground();
        if (bg == null && !(v instanceof android.widget.ImageView
                && ((android.widget.ImageView) v).getDrawable() instanceof android.graphics.drawable.LayerDrawable)) return false;
        if (bg != null && bg instanceof android.graphics.drawable.ColorDrawable
                && (((android.graphics.drawable.ColorDrawable) bg).getColor() >>> 24) == 0) return false;
        return strictRadius(v) >= AUTO_MIN_RADIUS_DP * d;
    }

    /** Radio de esquina real (contorno o forma del fondo); -1 si no lo hay. */
    private static float strictRadius(View v) {
        try {
            ViewOutlineProvider p = v.getOutlineProvider();
            if (p != null && v.getWidth() > 0) {
                OUTLINE.setEmpty();
                p.getOutline(v, OUTLINE);
                float r = OUTLINE.getRadius();
                if (r > 0) return r;
            }
        } catch (Throwable ignored) {
        }
        Drawable bg = v.getBackground();
        for (int i = 0; i < 3 && bg != null; i++) {
            if (bg instanceof GradientDrawable) {
                try {
                    float r = ((GradientDrawable) bg).getCornerRadius();
                    if (r > 0) return r;
                    float[] rr = ((GradientDrawable) bg).getCornerRadii();
                    if (rr != null && rr.length > 0 && rr[0] > 0) return rr[0];
                } catch (Throwable ignored) {
                }
                return -1;
            }
            if (bg instanceof android.graphics.drawable.LayerDrawable
                    && ((android.graphics.drawable.LayerDrawable) bg).getNumberOfLayers() > 0) {
                bg = ((android.graphics.drawable.LayerDrawable) bg).getDrawable(0);
            } else if (bg instanceof android.graphics.drawable.DrawableWrapper) {
                bg = ((android.graphics.drawable.DrawableWrapper) bg).getDrawable();
            } else {
                return -1;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- mod iOS de Kashi y miuix
    /**
     * Estilo de cristal del mod iOS: Kashi solo lo ofrece con Android 16 (glassStylesAvailable =
     * SDK ≥ 36); si no, fuerza el estilo 0 (fichas oscuras clásicas). Aquí se habilita siempre y,
     * si el ajuste está en 0, se usa el 2 (material: desenfoque + mezcla + canto de luz), que es
     * el mismo cristal de HyperOS 4 hecho con el motor de desenfoque de HyperOS 3.
     */
    static final boolean FORCE_IOS_GLASS = true;
    static final int IOS_GLASS_STYLE = 2;

    private static void forceIosGlass(ClassLoader cl) {
        final Class<?> ios = XposedHelpers.findClassIfExists("com.kashi.ios.Ios", cl);
        if (ios == null || !FORCE_IOS_GLASS) return;
        int n = 0;
        for (Method m : ios.getDeclaredMethods()) {
            if (m.getName().equals("glassStylesAvailable") && m.getParameterTypes().length == 0) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        p.setResult(true);
                    }
                });
                n++;
            } else if (m.getName().equals("readSwitches")) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (XposedHelpers.getStaticIntField(ios, "sBlurStyle") == 0) {
                                XposedHelpers.setStaticIntField(ios, "sBlurStyle", IOS_GLASS_STYLE);
                            }
                            XposedHelpers.setStaticBooleanField(ios, "sGlass", true);
                        } catch (Throwable t) {
                            once("ios style", t);
                        }
                    }
                });
                n++;
            }
        }
        Deopt.all(ios);
        // Por si el mod ya leyó los ajustes antes de engancharnos.
        try {
            if (XposedHelpers.getStaticIntField(ios, "sBlurStyle") == 0) {
                XposedHelpers.setStaticIntField(ios, "sBlurStyle", IOS_GLASS_STYLE);
                XposedHelpers.setStaticBooleanField(ios, "sGlass", true);
            }
        } catch (Throwable ignored) {
        }
        note("HyperSpring CC: estilo cristal del mod iOS forzado (" + n + " hooks)");
    }

    /**
     * Fichas grandes del mod (CCNewTileCardView) pintan su relleno oscuro en onDraw, encima del
     * cristal, salvo cuando blend() es true. Apagadas y con cristal completo, se fuerza true.
     */
    private static void hookTileCards(ClassLoader cl) {
        final Class<?> c = XposedHelpers.findClassIfExists("miui.systemui.controlcenter.ModsCenter.CCNewTileCardView", cl);
        if (c == null) return;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("blend") || m.getParameterTypes().length != 0 || m.getReturnType() != boolean.class) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!FULL_GLASS || backdrop == null || shaderBroken) return;
                    try {
                        Object st = XposedHelpers.getObjectField(p.thisObject, "mState");
                        int state = st != null ? XposedHelpers.getIntField(st, "state") : 1;
                        if (state != 2) p.setResult(true);
                    } catch (Throwable t) {
                        once("tilecard", t);
                    }
                }
            });
        }
        Deopt.all(c);
    }

    private static void hookIos(ClassLoader cl) {
        forceIosGlass(cl);
        try {
            hookTileCards(cl);
        } catch (Throwable t) {
            once("tilecards", t);
        }
        int n = 0;
        Class<?> hm = XposedHelpers.findClassIfExists("miuix.core.util.HyperMaterialUtils", cl);
        if (hm != null) {
            for (Method m : hm.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 2 || pt[0] != View.class) continue;
                final String name = m.getName();
                if (!name.equals("applyViewMaterial") && !name.startsWith("applyElement")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (p.args[1] == null) strongOff((View) p.args[0]);
                        else strong((View) p.args[0]);
                    }
                });
                n++;
            }
            Deopt.all(hm);
        }
        Class<?> bs = XposedHelpers.findClassIfExists("miuix.core.util.HyperBloomStrokeUtils", cl);
        if (bs != null) {
            for (Method m : bs.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length < 2 || pt[0] != View.class || !m.getName().startsWith("setBloomStroke")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args[1] != null) strong((View) p.args[0]);
                    }
                });
                n++;
            }
            Deopt.all(bs);
        }
        Class<?> ig = XposedHelpers.findClassIfExists("com.kashi.ios.IosGlass", cl);
        if (ig != null) {
            for (Method m : ig.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 0 || pt[0] != View.class) continue;
                final boolean clear = m.getName().equals("clear");
                if (!clear && !m.getName().equals("apply") && !m.getName().equals("reapply")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (clear) strongOff((View) p.args[0]);
                        else strong((View) p.args[0]);
                    }
                });
                n++;
            }
            Deopt.all(ig);
        }
        Class<?> gv = XposedHelpers.findClassIfExists("com.kashi.ios.IosCircleGlass$GlassView", cl);
        if (gv != null) {
            XposedBridge.hookAllConstructors(gv, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    strong((View) p.thisObject);
                }
            });
            n++;
        }
        note("HyperSpring CC: mod iOS/miuix (" + n + " hooks)");
    }

    // ---------------------------------------------------------------- captura al abrir
    private static Bitmap backdrop;
    private static float backdropScale = 1f;         // px de captura por px de pantalla
    private static Handler worker;
    private static boolean captureBroken, captureInFlight;
    /** Ventana con un reintento de captura pendiente (solo hilo principal). */
    private static View retryView;
    private static long lastCapture;
    /** Ventana (vista raíz) para la que se hizo la captura actual. */
    private static java.lang.ref.WeakReference<View> backdropRoot;
    /**
     * Otras ventanas del plugin con piezas de cristal (la barra de volumen): su captura se
     * rehace cada vez que la ventana aparece, porque lo que tiene detrás cambia. Mientras no la
     * hay, esas piezas usan el desenfoque de MIUI en vez de una captura vieja de otra pantalla.
     */
    private static final Map<View, Boolean> WINDOW_WATCHED = new WeakHashMap<>();
    private static final Set<View> WINDOW_STALE = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    /**
     * Desenfoque del fondo: HyperOS 3 lo aplica muy fuerte y oscuro. Se escala el valor que el
     * plugin envía a SystemUI (radio y oscurecido del fondo), no su estado interno, así la
     * apertura y los gestos siguen igual. Se suma al ajuste "intensidad del desenfoque" del mod.
     */
    static final float BACKGROUND_BLUR_SCALE = 1f;

    private static void hookBackgroundBlur(ClassLoader cl) {
        Class<?> c = find("miui.systemui.controlcenter.windowview.ControlCenterExpandController", cl);
        if (c == null) return;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("notifyBlurRatioChanged") || m.getParameterTypes().length != 1) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (BACKGROUND_BLUR_SCALE != 1f) p.args[0] = (Float) p.args[0] * BACKGROUND_BLUR_SCALE;
                }
            });
        }
        Deopt.all(c);
    }

    private static void hookOpen(ClassLoader cl) {
        hookBackgroundBlur(cl);
        Class<?> c = find("miui.systemui.controlcenter.windowview.ControlCenterWindowViewController", cl);
        if (c == null) return;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("setBlurRatio") || m.getParameterTypes().length != 1) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        float old = XposedHelpers.getFloatField(p.thisObject, "blurRatio");
                        float now = (Float) p.args[0];
                        if (old <= 0.001f && now > 0.001f) {
                            View root = (View) XposedHelpers.callMethod(p.thisObject, "getView");
                            capture(root);
                            if (root != null) root.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    dumpState();
                                }
                            }, 1500);
                        }
                    } catch (Throwable t) {
                        once("open", t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        float r = XposedHelpers.getFloatField(p.thisObject, "blurRatio");
                        View root = (View) XposedHelpers.callMethod(p.thisObject, "getView");
                        if (root == null) return;
                        if (ccRoot == null || ccRoot.get() != root) {
                            ccRoot = new java.lang.ref.WeakReference<>(root);
                            if (root.willNotDraw()) root.setWillNotDraw(false);
                        }
                        watchRoot(root);
                        if (Math.abs(r - ccRatio) > 0.002f || (r == 0f) != (ccRatio == 0f)) {
                            ccRatio = r;
                            root.invalidate();
                        }
                    } catch (Throwable t) {
                        once("bg ratio", t);
                    }
                }
            });
        }
        Deopt.all(c);
    }

    private static java.lang.ref.WeakReference<View> ccRoot;
    private static float ccRatio;
    private static Bitmap bgSoft;
    private static float bgSoftScale = 1f;
    private static final Paint BG_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static Bitmap bgBound;
    private static final android.graphics.Matrix BG_MATRIX = new android.graphics.Matrix();
    private static final int[] BG_LOC = new int[2];

    /**
     * Fondo vibrante: la captura (desenfocada y saturada) encima del desenfoque del sistema y bajo
     * todo el contenido, con la opacidad del progreso de apertura. Da el fondo colorido de
     * HyperOS 4 en vez del gris oscuro de HyperOS 3.
     */
    private static void drawVibrantBackground(View root, Canvas c) {
        Bitmap b = bgSoft;
        if (b == null || ccRatio <= 0.01f || !c.isHardwareAccelerated()) return;
        try {
            if (bgBound != b) {
                BG_PAINT.setShader(GlassShader.linear(b));
                android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                cm.setSaturation(BG_SAT);
                android.graphics.ColorMatrix lift = new android.graphics.ColorMatrix();
                lift.set(new float[]{BG_GAIN, 0, 0, 0, BG_HAZE, 0, BG_GAIN, 0, 0, BG_HAZE, 0, 0, BG_GAIN, 0, BG_HAZE, 0, 0, 0, 1, 0});
                cm.postConcat(lift);
                BG_PAINT.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
                bgBound = b;
            }
            root.getLocationOnScreen(BG_LOC);
            BG_MATRIX.setScale(1f / bgSoftScale, 1f / bgSoftScale);
            BG_MATRIX.postTranslate(-BG_LOC[0], -BG_LOC[1]);
            BG_PAINT.getShader().setLocalMatrix(BG_MATRIX);
            BG_PAINT.setAlpha(Math.round(255 * BG_ALPHA * Math.min(1f, ccRatio)));
            int save = c.save();
            c.translate(root.getScrollX(), root.getScrollY());
            c.drawRect(0, 0, root.getWidth(), root.getHeight(), BG_PAINT);
            c.restoreToCount(save);
        } catch (Throwable t) {
            once("bg draw", t);
        }
    }

    /** Captura lo que hay debajo del centro de control (sin su ventana), en segundo plano. */
    private static void capture(final View cc) {
        if (captureBroken || cc == null || Build.VERSION.SDK_INT < 34) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (captureInFlight && now - lastCapture > 2000) captureInFlight = false;   // nunca se queda bloqueada
        if (captureInFlight || now - lastCapture < 300) {
            // Demasiado pronto: un único reintento pendiente (se pide en cada dibujo y no deben
            // acumularse), en cuanto se pueda, en vez de quedarse sin captura.
            if (retryView == null) {
                retryView = cc;
                // En el hilo principal, no en la vista: si la vista se quita antes, su cola se
                // congela hasta que vuelva y no habría más reintentos para nadie.
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        View v = retryView;
                        retryView = null;
                        if (v != null && v.isAttachedToWindow()) capture(v);
                    }
                }, Math.max(60, 300 - (now - lastCapture)));
            } else {
                retryView = cc;                    // el último que la pidió
            }
            return;
        }
        lastCapture = now;
        captureInFlight = true;
        final View window = cc.getRootView();
        final SurfaceControl own = surfaceOf(cc);
        final int display = cc.getDisplay() != null ? cc.getDisplay().getDisplayId() : 0;
        final android.util.DisplayMetrics dm = cc.getResources().getDisplayMetrics();
        if (worker == null) {
            // Desde la semilla: con todas las CPU (ver com.icehome.up.Threads).
            worker = com.icehome.up.Threads.start("HyperSpring-ccglass", android.os.Process.THREAD_PRIORITY_DEFAULT);
        }
        worker.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final float frame = 0.25f;
                    Bitmap shot = screenshot(display, own, frame);
                    if (shot == null) {
                        captureDone();
                        return;
                    }
                    lastShot = shot;
                    lastDensity = dm.density;
                    float pxPerSample = SAMPLE_DP * dm.density;
                    Tune.load(cc.getContext());
                    float blurDp = Tune.ccBlur;
                    final Bitmap small = GlassShader.shrinkBlur(shot, 1f / frame, pxPerSample, blurDp * dm.density);
                    if (small == null) {
                        captureDone();
                        return;
                    }
                    final float scale = 1f / pxPerSample;
                    float bgPx = BG_SAMPLE_DP * dm.density;
                    final Bitmap soft = VIBRANT_BACKGROUND
                            ? GlassShader.shrinkBlur(shot, 1f / frame, bgPx, BG_BLUR_DP * dm.density) : null;
                    final float softScale = 1f / bgPx;
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (backdrop == null) {
                                note("HyperSpring CC: captura de fondo OK " + small.getWidth() + "x" + small.getHeight());
                            }
                            backdrop = small;
                            backdropScale = scale;
                            backdropRoot = new java.lang.ref.WeakReference<>(window);
                            WINDOW_STALE.remove(window);
                            captureFails = 0;
                            captureInFlight = false;
                            bgSoft = soft;
                            bgSoftScale = softScale;
                            for (Skin s : SKINS.values()) s.backdropChanged();
                            View root = ccRoot != null ? ccRoot.get() : null;
                            if (root != null) root.invalidate();
                        }
                    });
                } catch (Throwable t) {
                    once("capture", t);
                    if (++captureFails >= CAPTURE_FAILS_MAX) {
                        captureBroken = true;
                        note("HyperSpring CC: captura desactivada tras " + CAPTURE_FAILS_MAX + " fallos seguidos");
                    }
                    captureDone();
                }
            }
        });
    }

    /** Fin de una captura que no llegó a usarse (en el hilo principal). */
    private static void captureDone() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                captureInFlight = false;
            }
        });
    }

    /** Raíz de la ventana del centro de control (o null si aún no se ha abierto). */
    private static View ccWindow() {
        View r = ccRoot != null ? ccRoot.get() : null;
        return r != null ? r.getRootView() : null;
    }

    /**
     * ¿Vale la captura actual para las piezas de esta ventana? La del centro de control se
     * captura al abrirse (hookOpen). Las demás ventanas del plugin (volumen) piden la suya al
     * aparecer y, hasta tenerla, no usan la de otra pantalla.
     */
    private static boolean backdropFits(View host) {
        View root = host.getRootView();
        if (root == null) return false;
        if (root == ccWindow()) return true;
        watchWindow(root);
        View br = backdropRoot != null ? backdropRoot.get() : null;
        if (br == root && !WINDOW_STALE.contains(root)) return true;
        capture(root);
        return false;
    }

    /** Marca la ventana como pendiente de captura cada vez que se vuelve a mostrar. */
    private static void watchWindow(final View root) {
        if (WINDOW_WATCHED.containsKey(root)) return;
        WINDOW_WATCHED.put(root, Boolean.TRUE);
        WINDOW_STALE.add(root);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                WINDOW_STALE.add(v);
                for (Skin s : SKINS.values()) if (s.host.getRootView() == v) s.syncMiBlur();
                capture(v);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                WINDOW_STALE.add(v);
            }
        });
    }

    /** Ajuste por comando: settings put global <clave> <valor>. Si no existe, el valor por defecto. */
    static float setting(android.content.Context c, String key, float def) {
        try {
            String v = android.provider.Settings.Global.getString(c.getContentResolver(), key);
            if (v != null) return Math.max(0f, Float.parseFloat(v.trim()));
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static Bitmap lastShot;
    private static float lastDensity = 3f;

    /** Ajustes cambiados desde la app: se releen y se aplican al momento (sin reiniciar). */
    public static void retune(final android.content.Context ctx) {
        if (worker == null) {
            // Desde la semilla: con todas las CPU (ver com.icehome.up.Threads).
            worker = com.icehome.up.Threads.start("HyperSpring-ccglass", android.os.Process.THREAD_PRIORITY_DEFAULT);
        }
        worker.post(new Runnable() {
            @Override
            public void run() {
                Bitmap small0 = null;
                try {
                    Tune.load(ctx);
                    Bitmap shot = lastShot;
                    small0 = shot == null ? null
                            : GlassShader.shrinkBlur(shot, 4f, SAMPLE_DP * lastDensity, Tune.ccBlur * lastDensity);
                } catch (Throwable t) {
                    once("retune", t);           // la captura anterior pudo liberarse: se usa la que hay
                }
                final Bitmap small = small0;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (small != null) {
                            backdrop = small;
                            backdropScale = 1f / (SAMPLE_DP * lastDensity);
                        }
                        for (Skin s : SKINS.values()) {
                            s.miCleared = s.miCleared && Tune.ccOn;
                            s.backdropChanged();
                            s.syncMiBlur();
                        }
                        View root = ccRoot != null ? ccRoot.get() : null;
                        if (root != null) root.invalidate();
                    }
                });
            }
        });
    }

    private static SurfaceControl surfaceOf(View v) {
        try {
            Object vri = View.class.getMethod("getViewRootImpl").invoke(v);
            if (vri == null) return null;
            return (SurfaceControl) vri.getClass().getMethod("getSurfaceControl").invoke(vri);
        } catch (Throwable t) {
            return null;
        }
    }

    /** IWindowManager.captureDisplay con la ventana del centro de control excluida (Android 14+). */
    private static Bitmap screenshot(int display, SurfaceControl exclude, float frameScale) throws Exception {
        Class<?> sc = Class.forName("android.window.ScreenCapture");
        Class<?> builderClass = Class.forName("android.window.ScreenCapture$CaptureArgs$Builder");
        Object builder = builderClass.getDeclaredConstructor().newInstance();
        builderClass.getMethod("setFrameScale", float.class).invoke(builder, frameScale);
        if (exclude != null) {
            builderClass.getMethod("setExcludeLayers", SurfaceControl[].class)
                    .invoke(builder, (Object) new SurfaceControl[]{exclude});
        }
        Object args = builderClass.getMethod("build").invoke(builder);
        Object listener = sc.getMethod("createSyncCaptureListener").invoke(null);
        Object wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        Method cap = null;
        for (Method m : wm.getClass().getMethods()) {
            if (m.getName().equals("captureDisplay") && m.getParameterTypes().length == 3) cap = m;
        }
        if (cap == null) throw new NoSuchMethodException("captureDisplay");
        cap.invoke(wm, display, args, listener);
        Object buffer = listener.getClass().getMethod("getBuffer").invoke(listener);
        if (buffer == null) return null;
        return (Bitmap) buffer.getClass().getMethod("asBitmap").invoke(buffer);
    }

    // ---------------------------------------------------------------- respaldo sin hook del framework
    private static void installFallback(ClassLoader cl) {
        final Class<?> tile = find("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView", cl);
        if (tile != null) {
            hookAll(tile, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        View icon = (View) XposedHelpers.getObjectField(p.thisObject, "icon");
                        if (icon != null) apply(icon, clamp01(XposedHelpers.getFloatField(p.thisObject, "iconProgress")), false);
                    } catch (Throwable t) {
                        once("tile", t);
                    }
                }
            }, "setIconProgress", "updateIconInternal", "updateIcon", "updateResources", "setCornerRadius");
            Deopt.all(tile);
        }
        final Class<?> card = find("miui.systemui.controlcenter.qs.tileview.QSCardItemView", cl);
        if (card != null) {
            hookAll(card, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object st = XposedHelpers.getObjectField(p.thisObject, "state");
                        boolean on = st != null && XposedHelpers.getIntField(st, "state") == 2;
                        apply((View) p.thisObject, on ? 1f : 0f, true);
                        Skin sk = skin((View) p.thisObject);
                        if (sk != null) sk.pinned = true;
                    } catch (Throwable t) {
                        once("card", t);
                    }
                }
            }, "updateBlurBlendBackground", "setBackgroundProgress", "onFinishInflate", "onBindViewHolder", "updateState");
            Deopt.all(card);
        }
        final Class<?> slider = find("miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder", cl);
        if (slider != null) {
            hookAll(slider, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object b = XposedHelpers.getObjectField(p.thisObject, "binding");
                        View host = (View) XposedHelpers.getObjectField(b, "progressBg");
                        try {
                            exclude((View) XposedHelpers.getObjectField(b, "progress"));   // relleno: blanco sólido
                            exclude((View) XposedHelpers.getObjectField(b, "icon"));       // icono del slider
                        } catch (Throwable ignored) {
                        }
                        if (host != null) apply(host, 0f, false);
                    } catch (Throwable t) {
                        once("slider", t);
                    }
                }
            }, "updateBlendBlur", "updateSize", "setDisableState", "onConfigurationChanged", "setOutlineRadius", "setProgressRadius");
            Deopt.all(slider);
        }
        final Class<?> holder = find("miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder", cl);
        if (holder != null) {
            hookAll(holder, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        View item = (View) XposedHelpers.getObjectField(p.thisObject, "itemView");
                        if (item != null) apply(item, 0f, false);
                    } catch (Throwable t) {
                        once("panel", t);
                    }
                }
            }, "updateBlendBlur");
            Deopt.all(holder);
        }
        note("HyperSpring CC: clases de serie enganchadas");
    }

    // ---------------------------------------------------------------- aplicar
    private static final Set<View> EXCLUDED = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    /** Piezas que no deben ser cristal (relleno de los sliders): se quita y se restaura MIUI. */
    static void exclude(View v) {
        if (v == null || !EXCLUDED.add(v)) return;
        Skin s = skin(v);
        if (s != null) {
            if (s.miCleared && s.lastBlend != null) miRestore(v, s.lastBlend);
            s.miCleared = false;
            s.strong = false;
            s.setOn(false);
            v.invalidate();
        }
    }

    private static void apply(View v, float active, boolean animate) {
        if (EXCLUDED.contains(v)) return;
        if (hasNative()) {
            nativeGlass(v, active);
            return;
        }
        Skin s = skin(v);
        if (s == null) {
            s = new Skin(v);
            SKINS.put(v, s);
            v.getOverlay().add(s);
            if (FULL_GLASS && v.willNotDraw()) v.setWillNotDraw(false);   // para que se llame drawBackground
            final Skin fs = s;
            v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    fs.layout();
                }
            });
            s.active = Math.max(0f, active);
            animate = false;
        }
        s.setOn(true);
        s.layout();
        s.invalidateSelf();
        if (active >= 0f) s.setActive(active, animate);
    }

    // ---------------------------------------------------------------- motor nativo
    private static void resolveNative() {
        if (nativeResolved) return;
        nativeResolved = true;
        try {
            setMaterial = View.class.getMethod("setMiViewMaterialType", int.class);
            setGlass = View.class.getMethod("setMiGlass", float[].class);
        } catch (Throwable t) {
            setMaterial = null;
            setGlass = null;
        }
    }

    private static boolean hasNative() {
        return USE_NATIVE && setMaterial != null && setGlass != null;
    }

    private static void nativeGlass(View v, float a) {
        float[] p = new float[42];                // 37 del material + 5 de luz al pulsar (0)
        for (int i = 0; i < 37; i++) p[i] = DEFAULT_GLASS[i] + (ACTIVATED_GLASS[i] - DEFAULT_GLASS[i]) * a;
        try {
            setMaterial.invoke(v, 1);             // MaterialMode.Bionics
            setGlass.invoke(v, (Object) p);
        } catch (Throwable t) {
            once("native", t);
            setMaterial = null;                   // pasa al motor propio
        }
    }

    // ---------------------------------------------------------------- dibujo propio
    static final class Skin extends Drawable {
        final View host;
        final float dp;
        float active, radius = -1;
        private boolean on = true, eligible;
        boolean strong;               // marcada por el material del mod (no la apaga el framework)
        boolean auto;                 // encontrada recorriendo la ventana (fondo propio redondeado)
        boolean suppressed;           // una vista hija del mismo tamaño ya lleva el cristal
        boolean pinned;               // encendido fijado por su clase (botón/tarjeta), no por la mezcla
        float blendActive;            // encendido según la mezcla de MIUI
        boolean solidContent;         // encendido con fondo sólido propio (blanco/azul limpio)
        ArrayList<?> lastBlend;       // última mezcla que le puso el sistema (para restaurarla)
        int underCalls;
        String why = "sin-dibujar";
        boolean miCleared;

        /** Quita o restaura la mezcla de MIUI según si se ve nuestro cristal completo. */
        void syncMiBlur() {
            if (!FULL_GLASS || !CLEAR_MIUI_BLEND) return;
            boolean want = Tune.ccOn && backdrop != null && on && eligible && !suppressed
                    && (!host.isAttachedToWindow() || backdropFits(host))
                    && (currentActive() < 0.5f || solidContent)
                    && Build.VERSION.SDK_INT >= 33 && !shaderBroken;
            if (want && !miCleared) {
                miClear(host, strong || auto);
                miCleared = true;
                host.invalidate();
            } else if (!want && miCleared) {
                if (lastBlend != null) miRestore(host, lastBlend);
                miCleared = false;
                host.invalidate();
            }
        }
        private int kind = CARD;
        private int w, h;
        private ValueAnimator anim;
        private float animTarget = -1;
        private final Path shape = new Path();
        private final Path rimPath = new Path();
        private final RectF rf = new RectF();
        private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lens = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Object shader;                     // RuntimeShader (API 33+)
        private Bitmap boundBackdrop, underBackdrop;
        private Object under;                      // RuntimeShader del cristal bajo el contenido
        private final Paint underPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean underDrawn;
        private float thick;
        private float band;
        private final int[] loc = new int[2];
        private final float[] scaleScratch = new float[2];
        private final int[] last = {Integer.MIN_VALUE, 0};
        private float lastSx = -1, lastSy = -1;
        private boolean tracking;

        Skin(View host) {
            this.host = host;
            this.dp = host.getResources().getDisplayMetrics().density;
            veil.setColor(0xFFFFFFFF);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(RIM_DP * dp);
        }

        void setOn(boolean v) {
            if (on == v) return;
            on = v;
            invalidateSelf();
        }

        void backdropChanged() {
            under = null;                 // ajustes releídos: se rehacen los shaders
            w = -1;
            layout();
            invalidateSelf();
            host.invalidate();
            syncMiBlur();
            if (!tracking) track();
        }

        /** Si la pieza se mueve (apertura, desplazamiento, pulsación) cambia lo que tiene detrás. */
        private void track() {
            tracking = true;
            final ViewTreeObserver.OnPreDrawListener pre = new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (backdrop == null || !on || !eligible || !host.isAttachedToWindow()) return true;
                    host.getLocationOnScreen(loc);
                    chainScale(host, scaleScratch);
                    if (loc[0] != last[0] || loc[1] != last[1] || scaleScratch[0] != lastSx || scaleScratch[1] != lastSy) {
                        invalidateSelf();
                        if (underDrawn) host.invalidate();
                    }
                    return true;
                }
            };
            host.getViewTreeObserver().addOnPreDrawListener(pre);
            // Al volver a una ventana nueva el observador cambia: se vuelve a registrar.
            host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    v.getViewTreeObserver().removeOnPreDrawListener(pre);
                    v.getViewTreeObserver().addOnPreDrawListener(pre);
                    w = -1;
                    layout();
                    invalidateSelf();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                }
            });
        }

        void layout() {
            int nw = host.getWidth(), nh = host.getHeight();
            float nr = radiusOf(host);
            if (nw == w && nh == h && nr == radius) return;
            w = nw;
            h = nh;
            radius = nr;
            setBounds(0, 0, w, h);
            android.util.DisplayMetrics dm = host.getResources().getDisplayMetrics();
            eligible = w > 0 && h > 0 && Math.min(w, h) >= MIN_DP * dp
                    && !(w > dm.widthPixels * 0.92f && h > dm.heightPixels * 0.5f)
                    && ours(host)
                    && (strong || strictRadius(host) >= AUTO_MIN_RADIUS_DP * dp);   // nada de cuadrados
            if (!eligible) return;
            report(host, w, h);
            if (host.isAttachedToWindow()) watchRoot(host.getRootView());
            float r = Math.min(radius, Math.min(w, h) / 2f);
            kind = r >= Math.min(w, h) / 2f - 1f ? BUTTON : CARD;
            rf.set(0, 0, w, h);
            shape.reset();
            shape.addRoundRect(rf, r, r, Path.Direction.CW);
            float in = RIM_DP * dp / 2f;
            rf.set(in, in, w - in, h - in);
            rimPath.reset();
            rimPath.addRoundRect(rf, Math.max(0, r - in), Math.max(0, r - in), Path.Direction.CW);
            sheen.setShader(new LinearGradient(0, 0, 0, h * 0.55f, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{argb(RIM_TOP), argb(RIM_SIDE), argb(RIM_SIDE), argb(RIM_BOTTOM)},
                    new float[]{0f, 0.38f, 0.62f, 1f}, Shader.TileMode.CLAMP));
            band = Math.min(BAND_DP * dp, Math.min(w, h) * BAND_MAX_FRACTION);
            thick = Math.min(Tune.edge * dp, Math.min(w, h) * THICK_MAX_FRACTION);
            under = null;
            buildLens(r);
            invalidateSelf();
        }

        private void buildLens(float r) {
            if (Build.VERSION.SDK_INT >= 33 && !shaderBroken) {
                try {
                    RuntimeShader rs = shader instanceof RuntimeShader ? (RuntimeShader) shader : GlassShader.create();
                    rs.setFloatUniform("size", w, h);
                    rs.setFloatUniform("radius", r);
                    rs.setFloatUniform("band", band);
                    rs.setFloatUniform("disp", 0f);
                    // La raya toma el color de justo fuera del borde: así "mueve" los colores por el canto.
                    rs.setFloatUniform("stretch", 1f);
                    rs.setFloatUniform("overshoot", Tune.edgeOver * dp);
                    rs.setFloatUniform("dispersion", DISPERSION);
                    rs.setFloatUniform("fill", 0f);
                    rs.setFloatUniform("bandAlpha", BAND_ALPHA);
                    rs.setFloatUniform("tone", TONE);
                    rs.setFloatUniform("mag", 0f);
                    rs.setFloatUniform("rimw", Tune.rim * dp);
                    rs.setFloatUniform("glow", GLOW_DP * dp);
                    rs.setFloatUniform("innerDark", INNER_DARK);
                    rs.setFloatUniform("sat", SATURATION);
                    rs.setFloatUniform("rimGain", Tune.rimGain);
                    rs.setFloatUniform("light", LENS_LIGHT);
                    shader = rs;
                    lens.setShader(rs);
                    lens.setStyle(Paint.Style.FILL);
                    return;
                } catch (Throwable t) {
                    shaderBroken = true;
                    once("agsl", t);
                }
            }
            shader = null;
            lens.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0x40FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x22FFFFFF},
                    new float[]{0f, 0.4f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            lens.setStyle(Paint.Style.STROKE);
            lens.setStrokeWidth(band * 0.5f);
        }

        void setActive(float target, boolean animate) {
            if (anim != null && anim.isRunning() && animTarget == target) return;
            if ((target >= 0.5f) != (active >= 0.5f)) host.post(new Runnable() {
                @Override
                public void run() {
                    syncMiBlur();
                }
            });
            if (anim != null) anim.cancel();
            animTarget = target;
            if (!animate || !host.isAttachedToWindow() || Math.abs(target - active) < 0.001f) {
                if (active != target) {
                    active = target;
                    invalidateSelf();
                }
                return;
            }
            anim = ValueAnimator.ofFloat(active, target);
            anim.setDuration(ACTIVE_ANIM_MS);
            anim.setInterpolator(new PathInterpolator(0.25f, 0.1f, 0.25f, 1f));
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator va) {
                    active = (Float) va.getAnimatedValue();
                    invalidateSelf();
                }
            });
            anim.start();
        }

        /** Enlaza la captura y la posición de la pieza sobre ella. */
        private boolean bindBackdrop(RuntimeShader rs) {
            Bitmap b = backdrop;
            if (b == null) {
                rs.setFloatUniform("hasWall", 0f);
                return false;
            }
            if (boundBackdrop != b) {
                rs.setInputShader("wall", GlassShader.linear(b));
                boundBackdrop = b;
            }
            host.getLocationOnScreen(loc);
            chainScale(host, scaleScratch);
            last[0] = loc[0];
            last[1] = loc[1];
            lastSx = scaleScratch[0];
            lastSy = scaleScratch[1];
            rs.setFloatUniform("origin", loc[0] * backdropScale, loc[1] * backdropScale);
            rs.setFloatUniform("scale", scaleScratch[0] * backdropScale, scaleScratch[1] * backdropScale);
            rs.setFloatUniform("hasWall", 1f);
            return true;
        }

        private float currentActive() {
            float a = Math.max(active, blendActive);
            return strong || auto ? Math.max(a, solid(host)) : a;
        }

        /** Cristal bajo el contenido: lo de detrás procesado como HyperOS 4, con canto grueso. */
        void drawUnder(Canvas c) {
            underDrawn = false;
            underCalls++;
            Bitmap b = backdrop;
            if (!FULL_GLASS || !Tune.ccOn || !on || suppressed || !eligible || b == null || w <= 0 || h <= 0 || shaderBroken
                    || Build.VERSION.SDK_INT < 33 || !c.isHardwareAccelerated()) {
                why = !on ? "off" : suppressed ? "hija" : !eligible ? "no-apta" : b == null ? "sin-captura"
                        : shaderBroken ? "shader-roto" : !c.isHardwareAccelerated() ? "sin-gpu" : "?";
                return;
            }
            if (!backdropFits(host)) {
                why = "esperando-captura";
                syncMiBlur();
                return;
            }
            float scaleB = backdropScale;
            float act = currentActive();
            if (act >= 0.5f) {
                why = "encendida";
                return;                                      // encendida: se ve su color, no lo de detrás
            }
            why = "ok";
            try {
                RuntimeShader u;
                if (!(under instanceof RuntimeShader)) {
                    u = GlassShader.create();
                    float r = Math.min(radius, Math.min(w, h) / 2f);
                    u.setFloatUniform("size", w, h);
                    u.setFloatUniform("radius", r);
                    u.setFloatUniform("band", thick);
                    u.setFloatUniform("disp", THICK_DISP_DP * dp);
                    u.setFloatUniform("dispersion", THICK_DISPERSION);
                    u.setFloatUniform("tone", kind == BUTTON ? BUTTON_TONE : Tune.tone);
                    u.setFloatUniform("sat", kind == BUTTON ? Tune.satButton : Tune.sat);
                    u.setFloatUniform("darker", BIO_DARKER);
                    u.setFloatUniform("whiteMix", Tune.white);
                    u.setFloatUniform("edgeLift", BIO_EDGE_LIFT);
                    u.setFloatUniform("stretch", EDGE_STRETCH);
                    u.setFloatUniform("overshoot", Tune.edgeOver * dp);
                    u.setFloatUniform("edgeSat", Tune.edgeSat);
                    u.setFloatUniform("mag", Tune.magnify);
                    // Refracción del borde (lente de Kyant0/AndroidLiquidGlass), sin pasar de un
                    // tercio del lado corto para que en los botones pequeños no se coma el centro.
                    float lensH = Math.min(Tune.refrH * dp, Math.min(w, h) / 3f);
                    u.setFloatUniform("refrH", lensH);
                    u.setFloatUniform("refrAmt", Tune.refr * dp * (lensH / Math.max(1f, Tune.refrH * dp)));
                    u.setFloatUniform("chroma", Tune.chroma);
                    u.setFloatUniform("rimGain", 0f);          // el canto de luz va encima (overlay)
                    u.setFloatUniform("light", 0f);
                    under = u;
                    underBackdrop = null;
                } else {
                    u = (RuntimeShader) under;
                }
                if (underBackdrop != b) {
                    u.setInputShader("wall", GlassShader.linear(b));
                    underBackdrop = b;
                }
                host.getLocationOnScreen(loc);
                chainScale(host, scaleScratch);
                last[0] = loc[0];
                last[1] = loc[1];
                lastSx = scaleScratch[0];
                lastSy = scaleScratch[1];
                u.setFloatUniform("origin", loc[0] * scaleB, loc[1] * scaleB);
                u.setFloatUniform("scale", scaleScratch[0] * scaleB, scaleScratch[1] * scaleB);
                u.setFloatUniform("hasWall", 1f);
                u.setFloatUniform("fill", 1f);
                u.setFloatUniform("bandAlpha", 1f);
                u.setFloatUniform("active", act);
                underPaint.setShader(u);
                underPaint.setAlpha(Math.round(255 * clamp01(1f - act * 2f)));
                int save = c.save();
                c.translate(host.getScrollX(), host.getScrollY());
                c.drawRect(0, 0, w, h, underPaint);
                c.restoreToCount(save);
                underDrawn = true;
                shaderFails = 0;
                if (!tracking) track();
            } catch (Throwable t) {
                shaderFailed("under", t);
            }
        }

        @Override
        public void draw(Canvas c) {
            if (!Tune.ccOn || !on || suppressed || !eligible || w <= 0 || h <= 0) return;
            float act = currentActive();
            float off = 1f - act;
            // Sin clipPath: en GPU recorta sin antialias y deja el borde dentado. Las formas se
            // dibujan con su propio antialias y el shader ya enmascara con la distancia al borde.
            // Con el cristal completo debajo, encima solo van la luz y el canto (sin velo ni refracción).
            if (underDrawn) off *= 0.35f;
            int va = underDrawn && kind == BUTTON ? 0
                    : Math.round((strong ? VEIL_STRONG : kind == BUTTON ? VEIL_BUTTON : VEIL_CARD) * off);
            if (va > 0) {
                veil.setAlpha(va);
                c.drawPath(shape, veil);
            }
            sheen.setAlpha(underDrawn ? Math.round((kind == BUTTON ? GLOSS_BUTTON : Tune.gloss) * (1f - act) + SHEEN_ON * act)
                    : Math.round(SHEEN_OFF * off + SHEEN_ON * act));
            c.drawPath(shape, sheen);
            boolean lensDrawn = false;
            if (shader != null && !shaderBroken) {
                try {
                    RuntimeShader rs = (RuntimeShader) shader;
                    if (c.isHardwareAccelerated() && backdropFits(host)) bindBackdrop(rs);
                    else rs.setFloatUniform("hasWall", 0f);
                    rs.setFloatUniform("active", act);
                    // Sobre un relleno sólido (pieza encendida) no se ve lo de detrás: solo el canto.
                    rs.setFloatUniform("bandAlpha", underDrawn || act >= 0.5f ? 0f : BAND_ALPHA * off);
                    lens.setShader(rs);
                    c.drawRect(0, 0, w, h, lens);
                    lensDrawn = true;
                } catch (Throwable t) {
                    shaderFailed("draw", t);
                }
            } else {
                float in = band * 0.25f;
                rf.set(in, in, w - in, h - in);
                float r = Math.max(0, Math.min(radius, Math.min(w, h) / 2f) - in);
                c.drawRoundRect(rf, r, r, lens);
            }
            // Con el shader el canto de luz ya va dentro; el trazo blanco solo sin AGSL.
            if (!lensDrawn) {
                rim.setAlpha(Math.round(255 * (0.85f + 0.15f * active)));
                c.drawPath(rimPath, rim);
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
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** 1 si la pieza (o su primer hijo) tiene un fondo de color casi opaco: está encendida. */
    static float solid(View v) {
        float a = alphaOf(v.getBackground());
        if (a < 0.8f && v instanceof android.view.ViewGroup && ((android.view.ViewGroup) v).getChildCount() > 0) {
            View ch = ((android.view.ViewGroup) v).getChildAt(0);
            if (ch.getWidth() >= v.getWidth() * 0.9f && ch.getHeight() >= v.getHeight() * 0.9f) {
                a = Math.max(a, alphaOf(ch.getBackground()));
            }
        }
        return a >= 0.8f ? 1f : 0f;
    }

    /** Color de fondo "encendido": casi opaco y claro o saturado (no el gris oscuro clásico). */
    private static boolean litColor(int c) {
        float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float lum = 0.299f * r + 0.587f * g + 0.114f * b;
        return lum > 0.45f || (max - min) > 0.35f;
    }

    private static float alphaOf(Drawable d) {
        if (d == null || !d.isVisible()) return 0f;
        int c = 0;
        if (d instanceof android.graphics.drawable.ColorDrawable) {
            c = ((android.graphics.drawable.ColorDrawable) d).getColor();
        } else if (d instanceof GradientDrawable) {
            android.content.res.ColorStateList cs = ((GradientDrawable) d).getColor();
            if (cs != null) c = cs.getDefaultColor();
        } else {
            return 0f;
        }
        if (!litColor(c)) return 0f;
        return ((c >>> 24) / 255f) * (d.getAlpha() / 255f);
    }

    // ---------------------------------------------------------------- utilidades
    private static final Outline OUTLINE = new Outline();

    static float radiusOf(View v) {
        int w = v.getWidth(), h = v.getHeight();
        float half = Math.min(w, h) / 2f;
        try {
            ViewOutlineProvider p = v.getOutlineProvider();
            if (p != null && w > 0) {
                OUTLINE.setEmpty();
                p.getOutline(v, OUTLINE);
                float r = OUTLINE.getRadius();
                if (r > 0) return Math.min(r, half);
            }
        } catch (Throwable ignored) {
        }
        Drawable bg = v.getBackground();
        if (bg instanceof GradientDrawable) {
            try {
                float r = ((GradientDrawable) bg).getCornerRadius();
                if (r > 0) return Math.min(r, half);
            } catch (Throwable ignored) {
            }
        }
        float d = v.getResources().getDisplayMetrics().density;
        if (w > 0 && Math.abs(w - h) < 2 && w < 90 * d) return half;
        return Math.min(22 * d, half);
    }

    private static void chainScale(View v, float[] out) {
        float sx = 1f, sy = 1f;
        for (View c = v; c != null; c = c.getParent() instanceof View ? (View) c.getParent() : null) {
            sx *= c.getScaleX();
            sy *= c.getScaleY();
        }
        out[0] = sx;
        out[1] = sy;
    }

    private static int hookAll(Class<?> c, XC_MethodHook h, String... names) {
        int n = 0;
        for (String name : names) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                try {
                    XposedBridge.hookMethod(m, h);
                    n++;
                } catch (Throwable t) {
                    once("hook " + name, t);
                }
            }
        }
        return n;
    }

    private static Class<?> find(String name, ClassLoader cl) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            note("HyperSpring CC: no existe " + name);
            return null;
        }
    }

    private static int argb(int a) {
        return (a << 24) | 0xFFFFFF;
    }

    private static float clamp01(float f) {
        return f < 0 ? 0 : f > 1 ? 1 : f;
    }

    private static int dumps;

    /** Estado de cada pieza (LSPosed → Registros), las 3 primeras aperturas tras arrancar. */
    private static void dumpState() {
        if (dumps >= 3) return;
        dumps++;
        StringBuilder sb = new StringBuilder("HyperSpring CC: estado #" + dumps + " captura="
                + (backdrop != null ? backdrop.getWidth() + "x" + backdrop.getHeight() : "no")
                + " capturaRota=" + captureBroken + " shaderRoto=" + shaderBroken + " piezas=" + SKINS.size());
        XposedBridge.log(sb.toString());
        int n = 0;
        for (Map.Entry<View, Skin> e : SKINS.entrySet()) {
            View v = e.getKey();
            Skin s = e.getValue();
            if (v == null || s == null || !v.isAttachedToWindow() || v.getWidth() == 0) continue;
            if (++n > 45) break;
            Drawable bg = v.getBackground();
            String img = "";
            if (v instanceof android.widget.ImageView) {
                Drawable d = ((android.widget.ImageView) v).getDrawable();
                img = " img=" + (d == null ? "-" : d.getClass().getSimpleName()
                        + (d instanceof android.graphics.drawable.LayerDrawable
                        ? "[" + ((android.graphics.drawable.LayerDrawable) d).getDrawable(0).getClass().getSimpleName() + "]" : ""));
            }
            View par = v.getParent() instanceof View ? (View) v.getParent() : null;
            XposedBridge.log("HyperSpring CC:  " + v.getClass().getSimpleName() + "<" + (par != null ? par.getClass().getSimpleName() : "-")
                    + " " + v.getWidth() + "x" + v.getHeight()
                    + (s.strong ? " mod" : "") + (s.auto ? " auto" : "") + " act=" + String.format(java.util.Locale.ROOT, "%.2f", s.currentActive())
                    + " cristal=" + s.why + " llamadas=" + s.underCalls + " mezclaQuitada=" + s.miCleared
                    + " fondo=" + (bg == null ? "-" : bg.getClass().getSimpleName()) + img
                    + " hijos=" + (v instanceof android.view.ViewGroup ? ((android.view.ViewGroup) v).getChildCount() : 0));
        }
    }

    private static int reported;

    /** Registro de las primeras piezas encontradas (para diagnosticar en LSPosed → Registros). */
    private static void report(View v, int w, int h) {
        if (reported >= 40) return;
        String key = v.getClass().getName() + "@" + (v.getParent() != null ? v.getParent().getClass().getName() : "-");
        if (!LOGGED.add("piece " + key)) return;
        reported++;
        note("HyperSpring CC: cristal en " + key + " " + w + "x" + h);
    }

    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<String>());

    private static void once(String what, Throwable t) {
        if (LOGGED.add(what)) {
            XposedBridge.log("HyperSpring CC: fallo en " + what);
            XposedBridge.log(t);
            com.icehome.up.telon.Log.e("centro de control: fallo en " + what, t);
        }
    }

    /** Piezas con cristal ahora mismo (tarjetas y botones), para la apertura por capas. */
    static java.util.List<View> glassHosts() {
        java.util.List<View> out = new java.util.ArrayList<>();
        for (Map.Entry<View, Skin> e : SKINS.entrySet()) {
            Skin sk = e.getValue();
            if (e.getKey() != null && sk != null && !sk.suppressed) out.add(e.getKey());
        }
        return out;
    }

    /** Al registro de LSPosed y al de la app (sin el prefijo antiguo). */
    static void note(String msg) {
        XposedBridge.log(msg);
        com.icehome.up.telon.Log.i(msg.replace("HyperSpring CC: ", "centro de control: "));
    }

    private CcGlass() {
    }
}
