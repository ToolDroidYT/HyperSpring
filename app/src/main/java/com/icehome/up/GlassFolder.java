package com.icehome.up;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Material "cristal suave" de HyperOS 4 para el fondo de las carpetas.
 *
 * Se dibuja encima del fondo real de la carpeta (y por debajo de sus iconos) y respeta el ajuste
 * de texturas avanzadas:
 *  - Activado: el interior muestra el fondo de pantalla con un desenfoque propio (FOLDER_BLUR_DP,
 *    algo menor que el del sistema) y una lupa leve; en el canto ese mismo fondo se dobla
 *    (refracción con dispersión de color) y el borde se ilumina con el color de lo que hay
 *    detrás, como si el cristal llegara hasta el canto.
 *  - Desactivado: fondo plano del sistema; solo el canto refracta y se ilumina.
 * Encima: velo blanco suave, brillo especular desde arriba-izquierda y un filo blanco fino.
 * Sin AGSL (Android < 13) o con fondo animado: velo, brillo y canto blanco como antes.
 */
final class GlassFolder {

    // ---------------------------------------------------------------- ajustes
    static final boolean ENABLED = true;
    /** Velo (alfa arriba → abajo) con desenfoque del sistema y sin él. */
    static final int VEIL_TOP_BLUR = 0x2E, VEIL_BOTTOM_BLUR = 0x0D;
    static final int VEIL_TOP_FLAT = 0x47, VEIL_BOTTOM_FLAT = 0x1F;
    static final int SHEEN_ALPHA = 0x30;
    static final float RIM_DP = 1.1f;
    static final int RIM_TOP = 0xD9, RIM_SIDE = 0x24, RIM_BOTTOM = 0x66;
    static final float INNER_DP = 2.2f;
    static final int INNER_ALPHA = 0x33;
    /** Refracción: anchura de la franja del canto, desplazamiento máximo y opacidad. */
    static final boolean REFRACTION = true;
    static final float BAND_DP = 3f, BAND_MAX_FRACTION = 0.07f;   // como las fichas del centro de control
    static final float STRENGTH_DP = 0f;
    /** Canto: estira el color de hasta EDGE_OVER_DP por fuera (igual que CcGlass). */
    static final float EDGE_OVER_DP = 3f, EDGE_SAT = 0.4f;
    static final float GLASS_SAT = 1.55f, GLASS_DARKER = 0.08f, GLASS_WHITE = 0.03f, GLASS_TONE = 1.1f, INNER_DARK = 0.02f;
    static final float DISPERSION = 0.06f;       // separación de colores (aberración cromática)
    static final float REFRACT_ALPHA = 0.9f;
    /**
     * Con texturas avanzadas: el interior muestra el fondo de pantalla con un desenfoque propio,
     * algo menor que el del sistema, para que se vea cómo el canto lo dobla.
     */
    static final float FOLDER_BLUR_DP = 6f, SAMPLE_DP = 1.5f;
    static final float MAGNIFY = 0.035f;                // lupa leve en el interior
    /** Canto iluminado con el color de lo que hay detrás. */
    static final float TINT_RIM_DP = 0.8f, GLOW_DP = 0f, TINT_RIM_GAIN = 1.0f;
    static final float LENS_LIGHT = 0.04f;
    static final int VEIL_TOP_GLASS = 0x0C, VEIL_BOTTOM_GLASS = 0x00;

    private static boolean shaderBroken;
    private static android.graphics.Bitmap wall;
    private static int wallId = Integer.MIN_VALUE;
    private static float wallOffsetX = 0.5f, wallOffsetY = 0.5f;

    private static Method mIconImageView, mBlurSupported;
    private static final Map<View, Glass> GLASS = new WeakHashMap<>();
    private static final Map<View, View.OnLayoutChangeListener> LISTENING = new WeakHashMap<>();

    static void install(ClassLoader cl) {
        if (!ENABLED) return;
        Class<?> folderIcon = XposedHelpers.findClassIfExists("com.miui.home.folder.FolderIcon", cl);
        Class<?> itemIcon = XposedHelpers.findClassIfExists("com.miui.home.ItemIcon", cl);
        if (folderIcon == null || itemIcon == null) {
            XposedBridge.log("IceHome: no se encuentra FolderIcon, sin cristal en carpetas");
            return;
        }
        try {
            mIconImageView = itemIcon.getMethod("getIconImageView");
            mBlurSupported = StackSwipe.method(folderIcon, "isFolderBlurSupported");
        } catch (Throwable t) {
            XposedBridge.log("IceHome: FolderIcon ha cambiado, sin cristal en carpetas");
            return;
        }
        XposedHelpers.findAndHookMethod(folderIcon, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                final View folder = (View) p.thisObject;
                if (LISTENING.containsKey(folder)) return;
                View.OnLayoutChangeListener l = new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                        apply(folder);
                    }
                };
                LISTENING.put(folder, l);
                folder.addOnLayoutChangeListener(l);
                folder.post(new Runnable() {
                    @Override
                    public void run() {
                        apply(folder);
                    }
                });
            }
        });
        XposedHelpers.findAndHookMethod(folderIcon, "onLayout",
                boolean.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        apply((View) p.thisObject);
                    }
                });
        // Desplazamiento del fondo entre páginas (lo fija el launcher): para saber qué hay debajo.
        try {
            XposedHelpers.findAndHookMethod(android.app.WallpaperManager.class, "setWallpaperOffsets",
                    android.os.IBinder.class, float.class, float.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            float x = (Float) p.args[1], y = (Float) p.args[2];
                            if (x >= 0f && x <= 1f) wallOffsetX = x;
                            if (y >= 0f && y <= 1f) wallOffsetY = y;
                        }
                    });
        } catch (Throwable ignored) {
        }
        XposedBridge.log("IceHome: cristal suave en carpetas activo");
    }

    /** Pone (o actualiza) el cristal sobre el fondo real de la carpeta. */
    static void apply(View folder) {
        try {
            View bg = (View) mIconImageView.invoke(folder);
            if (bg == null || bg.getWidth() == 0 || bg.getHeight() == 0) return;
            boolean blur = texturesOn(folder.getContext());
            try {
                blur |= (Boolean) mBlurSupported.invoke(folder);
            } catch (Throwable ignored) {
            }
            Glass g = GLASS.get(bg);
            if (g == null) {
                g = new Glass(bg);
                GLASS.put(bg, g);
                bg.getOverlay().add(g);
                g.track();
            }
            if (REFRACTION) loadWallpaper(bg.getContext());
            g.configure(blur, radius(bg));
            g.setBounds(0, 0, bg.getWidth(), bg.getHeight());
            g.invalidateSelf();
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** Texturas avanzadas activas: campo Configuration.blur de MIUI (1 = activado). */
    private static boolean texturesOn(android.content.Context ctx) {
        try {
            java.lang.reflect.Field f = android.content.res.Configuration.class.getDeclaredField("blur");
            f.setAccessible(true);
            return f.getInt(ctx.getResources().getConfiguration()) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ajustes cambiados desde la app: se vuelve a desenfocar el fondo y se redibujan las carpetas. */
    static void retune(android.content.Context ctx) {
        wallId = Integer.MIN_VALUE;
        loadWallpaper(ctx);
        for (View v : new java.util.ArrayList<>(GLASS.keySet())) {
            Glass g = GLASS.get(v);
            if (g != null) g.invalidateSelf();
            if (v != null) v.invalidate();
        }
    }

    /** Fondo de pantalla actual (reducido al tamaño de la pantalla). Null si es animado o no se puede leer. */
    private static void loadWallpaper(android.content.Context ctx) {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(ctx);
            int id = wm.getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM);
            if (id == wallId) return;
            wallId = id;
            wall = null;
            if (wm.getWallpaperInfo() != null) return;      // fondo animado: sin refracción
            Drawable d = wm.getDrawable();
            if (!(d instanceof android.graphics.drawable.BitmapDrawable)) return;
            android.graphics.Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            if (b == null) return;
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int sw = Math.max(dm.widthPixels, 1), sh = Math.max(dm.heightPixels, 1);
            float screenPerSrc = Math.max((float) sw / b.getWidth(), (float) sh / b.getHeight());
            float blurDp = Tune.get(ctx, "icehome_folder_blur");
            wall = GlassShader.shrinkBlur(b, screenPerSrc, SAMPLE_DP * dm.density, blurDp * dm.density);
        } catch (Throwable t) {
            wall = null;
        }
    }

    /** Radio de las esquinas del fondo: el de su contorno, o el de icono / widget de MIUI. */
    private static float radius(View bg) {
        try {
            if (bg.getOutlineProvider() != null) {
                Outline o = new Outline();
                bg.getOutlineProvider().getOutline(bg, o);
                float r = o.getRadius();
                if (r > 0f) return r;
            }
        } catch (Throwable ignored) {
        }
        float w = Math.min(bg.getWidth(), bg.getHeight());
        boolean small = Math.abs(bg.getWidth() - bg.getHeight()) < w * 0.2f
                && w < 90f * bg.getResources().getDisplayMetrics().density;
        String m = small ? "computeEnforcedIconRadius" : "computeEnforcedRadius";
        try {
            Class<?> rc = Class.forName("com.miui.home.common.widget.RoundedCornerEnforcement", false,
                    bg.getClass().getClassLoader());
            Number r = (Number) rc.getMethod(m, android.content.Context.class).invoke(null, bg.getContext());
            if (r.floatValue() > 0f) return r.floatValue();
        } catch (Throwable ignored) {
        }
        return small ? w * 0.24f : 20f * bg.getResources().getDisplayMetrics().density;
    }

    /** Dibujo del cristal (en coordenadas del fondo de la carpeta). */
    static final class Glass extends Drawable {
        private final View host;
        private final float density;
        private final Paint refract = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Object runtimeShader;              // android.graphics.RuntimeShader (API 33+)
        private android.graphics.Bitmap shaderWall;
        private final int[] loc = new int[2], lastLoc = {Integer.MIN_VALUE, 0};
        private float lastSx = -1f, lastSy = -1f;
        private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glassVeil = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF box = new RectF();
        private boolean blur;
        private float radius;
        private int alpha = 255;

        Glass(View host) {
            this.host = host;
            this.density = host.getResources().getDisplayMetrics().density;
            inner.setStyle(Paint.Style.STROKE);
            rim.setStyle(Paint.Style.STROKE);
        }

        /** Si la carpeta se mueve o cambia de escala, el fondo de debajo cambia: redibujar. */
        void track() {
            if (!REFRACTION) return;
            host.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (wall == null || !host.isAttachedToWindow()) return true;
                    host.getLocationOnScreen(loc);
                    float[] sc = chainScale(host);
                    if (loc[0] != lastLoc[0] || loc[1] != lastLoc[1] || sc[0] != lastSx || sc[1] != lastSy) {
                        invalidateSelf();
                    }
                    return true;
                }
            });
        }

        void configure(boolean blur, float radius) {
            if (blur == this.blur && radius == this.radius) return;
            this.blur = blur;
            this.radius = radius;
            rebuild(getBounds());
        }

        @Override
        protected void onBoundsChange(Rect b) {
            rebuild(b);
        }

        private void rebuild(Rect b) {
            if (b.isEmpty()) return;
            box.set(b);
            float w = box.width(), h = box.height();
            float r = Math.min(radius, Math.min(w, h) / 2f);
            clip.reset();
            clip.addRoundRect(box, r, r, Path.Direction.CW);

            int top = blur ? VEIL_TOP_BLUR : VEIL_TOP_FLAT, bottom = blur ? VEIL_BOTTOM_BLUR : VEIL_BOTTOM_FLAT;
            veil.setShader(new LinearGradient(0, box.top, 0, box.bottom,
                    white(top), white(bottom), Shader.TileMode.CLAMP));
            glassVeil.setShader(new LinearGradient(0, box.top, 0, box.bottom,
                    white(VEIL_TOP_GLASS), white(VEIL_BOTTOM_GLASS), Shader.TileMode.CLAMP));

            sheen.setShader(new RadialGradient(box.left + w * 0.22f, box.top, Math.max(w, h) * 0.85f,
                    new int[]{white(SHEEN_ALPHA), white(SHEEN_ALPHA / 3), white(0)},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));

            float iw = INNER_DP * density;
            inner.setStrokeWidth(iw);
            inner.setShader(new LinearGradient(0, box.top, 0, box.top + Math.min(h, 48f * density),
                    white(INNER_ALPHA), white(0), Shader.TileMode.CLAMP));

            float rw = RIM_DP * density;
            rim.setStrokeWidth(rw);
            rim.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom,
                    new int[]{white(RIM_TOP), white(RIM_SIDE), white(RIM_SIDE), white(RIM_BOTTOM)},
                    new float[]{0f, 0.38f, 0.66f, 1f}, Shader.TileMode.CLAMP));
        }

        private static int white(int a) {
            return (Math.max(0, Math.min(255, a)) << 24) | 0xFFFFFF;
        }

        @Override
        public void draw(Canvas canvas) {
            if (box.isEmpty()) return;
            float w = box.width(), h = box.height();
            float r = Math.min(radius, Math.min(w, h) / 2f);
            boolean lens = REFRACTION && drawRefraction(canvas, w, h, r);
            int save = canvas.save();
            if (!lens) canvas.clipPath(clip);       // con lente, sin recorte: el clipPath no tiene antialias
            if (lens && blur) {
                glassVeil.setAlpha(alpha);
                canvas.drawRoundRect(box, r, r, glassVeil);
            } else {
                veil.setAlpha(alpha);
                canvas.drawRoundRect(box, r, r, veil);
            }
            sheen.setAlpha(alpha);
            canvas.drawRoundRect(box, r, r, sheen);
            if (!lens) {
                float iw = inner.getStrokeWidth();
                RectF in = new RectF(box.left + iw * 1.2f, box.top + iw * 1.2f, box.right - iw * 1.2f, box.bottom - iw * 1.2f);
                float ir = Math.max(0f, r - iw * 1.2f);
                inner.setAlpha(alpha);
                canvas.drawRoundRect(in, ir, ir, inner);
            }
            canvas.restoreToCount(save);

            // Con lente, el canto ya sale iluminado del color de detrás: el blanco solo lo afina.
            float rw = rim.getStrokeWidth();
            RectF edge = new RectF(box.left + rw / 2f, box.top + rw / 2f, box.right - rw / 2f, box.bottom - rw / 2f);
            float er = Math.max(0f, r - rw / 2f);
            rim.setAlpha(lens ? 0 : alpha);
            canvas.drawRoundRect(edge, er, er, rim);
        }

        /** Fondo, refracción y canto teñido. Devuelve false si no hay lente (sin fondo o sin AGSL). */
        private boolean drawRefraction(Canvas canvas, float w, float h, float r) {
            android.graphics.Bitmap bmp = wall;
            if (bmp == null || shaderBroken || android.os.Build.VERSION.SDK_INT < 33
                    || !canvas.isHardwareAccelerated()) return false;
            try {
                android.graphics.RuntimeShader rs;
                if (runtimeShader == null) runtimeShader = GlassShader.create();
                rs = (android.graphics.RuntimeShader) runtimeShader;
                if (shaderWall != bmp) {
                    rs.setInputShader("wall", GlassShader.linear(bmp));
                    shaderWall = bmp;
                }
                // Qué parte del fondo hay bajo la carpeta: el fondo cubre la pantalla (center-crop)
                // y se desplaza horizontalmente entre páginas según wallOffsetX.
                View root = host.getRootView();
                float sw = root.getWidth(), sh = root.getHeight();
                if (sw <= 0f || sh <= 0f) return false;
                float bw = bmp.getWidth(), bh = bmp.getHeight();
                float s = Math.max(sw / bw, sh / bh);
                float left = (bw * s - sw) * wallOffsetX, top = (bh * s - sh) * wallOffsetY;
                host.getLocationOnScreen(loc);
                float[] sc = chainScale(host);
                lastLoc[0] = loc[0];
                lastLoc[1] = loc[1];
                lastSx = sc[0];
                lastSy = sc[1];
                float band = Math.min(BAND_DP * density, Math.min(w, h) * BAND_MAX_FRACTION);
                rs.setFloatUniform("size", w, h);
                rs.setFloatUniform("radius", r);
                rs.setFloatUniform("band", band);
                rs.setFloatUniform("disp", STRENGTH_DP * density);
                rs.setFloatUniform("dispersion", DISPERSION);
                rs.setFloatUniform("origin", (loc[0] + left) / s, (loc[1] + top) / s);
                rs.setFloatUniform("scale", sc[0] / s, sc[1] / s);
                rs.setFloatUniform("hasWall", 1f);
                rs.setFloatUniform("fill", blur ? 1f : 0f);       // sin texturas avanzadas: solo el canto
                rs.setFloatUniform("bandAlpha", REFRACT_ALPHA);
                rs.setFloatUniform("tone", GLASS_TONE);
                rs.setFloatUniform("darker", GLASS_DARKER);
                rs.setFloatUniform("whiteMix", GLASS_WHITE);
                rs.setFloatUniform("stretch", 1f);
                rs.setFloatUniform("overshoot", EDGE_OVER_DP * density);
                rs.setFloatUniform("edgeSat", EDGE_SAT);
                rs.setFloatUniform("innerDark", INNER_DARK);
                rs.setFloatUniform("mag", blur ? MAGNIFY : 0f);
                rs.setFloatUniform("rimw", TINT_RIM_DP * density);
                rs.setFloatUniform("glow", GLOW_DP * density);
                rs.setFloatUniform("sat", GLASS_SAT);
                rs.setFloatUniform("rimGain", TINT_RIM_GAIN);
                rs.setFloatUniform("light", LENS_LIGHT);
                rs.setFloatUniform("active", 0f);
                refract.setShader(rs);
                refract.setAlpha(alpha);
                int save = canvas.save();
                canvas.translate(box.left, box.top);
                canvas.drawRect(0, 0, w, h, refract);
                canvas.restoreToCount(save);
                return true;
            } catch (Throwable t) {
                runtimeShader = null;
                shaderBroken = true;          // no reintentar en cada fotograma
                XposedBridge.log("IceHome: refracción desactivada");
                XposedBridge.log(t);
                return false;
            }
        }

        @Override
        public void setAlpha(int a) {
            alpha = a;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static float[] chainScale(View v) {
        float sx = 1f, sy = 1f;
        for (View c = v; c != null; c = c.getParent() instanceof View ? (View) c.getParent() : null) {
            sx *= c.getScaleX();
            sy *= c.getScaleY();
        }
        return new float[]{sx, sy};
    }

    private GlassFolder() {
    }
}
