package com.icehome.up;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.SurfaceControl;
import android.view.View;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XC_MethodReplacement;
import com.icehome.up.xp.XposedBridge;
import com.icehome.up.xp.XposedHelpers;

/**
 * Enganches en la animación de ventanas del launcher (HyperOS, mod de Kashi V7.5):
 * <ul>
 * <li>{@code LocalWindowAnimImplementor.updateElementValue}: cada fotograma del muelle, por el
 *     camino normal y por el de arranque rápido → {@link Genie#frame}.</li>
 * <li>{@code SurfaceParams.applyTo}: la transacción que mueve la ventana; ahí se cambia por la capa.</li>
 * <li>Fin y cancelación del muelle → {@link Genie#animEnded}.</li>
 * <li>{@code HomeLayerElement.bindHomeLeash} y {@code TransactionCompat.setMatrix}: compensar la
 *     escala del inicio en nuestra capa.</li>
 * <li>Icono flotante de MIUI: oculto mientras se ve el genio.</li>
 * <li>{@code SpringParamsProvider}: velocidad y rebote ({@link Springs}).</li>
 * <li>Motor de animación de SurfaceFlinger desactivado: con él el launcher no recibe los fotogramas.</li>
 * </ul>
 */
final class WindowAnim {

    private static final String P = "com.miui.home.recents.";
    private static final String SYS = "com.android.systemui.shared.recents.system.";
    private static final int FLAG_ALPHA = 1;   // SurfaceParams.flags

    // LocalWindowAnimImplementor
    private static Method lastAnimType, currentParams;
    private static Field animContext;
    // WindowAnimContext / RemoteAnimationTargetSet / RemoteAnimationTargetCompat
    private static Method targetSet, floatingIcons, firstTarget, hasMultiTask;
    private static Field targetLeash, targetPosition, targetTask;
    // RectFParams / WindowAnimParams / CornerRadii / FloatingIconInterface
    private static Method windowParams, quickSwitch, startRect, targetRect, outerRadius, iconSurface, shade;
    // SurfaceControlCompat / SurfaceParams / TransactionCompat / ventana del launcher
    private static Field compatSurface, paramsSurface, paramsAlpha, paramsFlags, compatTransaction;
    private static Method surfaceOfView, getLauncher, rootView;

    /** Solo se usa en el hilo de animación de MIUI. */
    private static final Genie.Frame FRAME = new Genie.Frame();
    private static final Set<String> SEEN = new HashSet<>();
    private static final Map<Class<?>, Field> DRAWABLE = new HashMap<>();

    static void install(ClassLoader cl) {
        Class<?> impl = XposedHelpers.findClass(P + "anim.windowanim.LocalWindowAnimImplementor", cl);
        Class<?> ctx = XposedHelpers.findClass(P + "anim.windowanim.WindowAnimContext", cl);
        Class<?> set = XposedHelpers.findClass(P + "util.RemoteAnimationTargetSet", cl);
        Class<?> target = XposedHelpers.findClass(SYS + "RemoteAnimationTargetCompat", cl);
        Class<?> compat = XposedHelpers.findClass(SYS + "SurfaceControlCompat", cl);
        Class<?> params = XposedHelpers.findClass(SYS + "SyncRtSurfaceTransactionApplierCompat$SurfaceParams", cl);
        Class<?> tx = XposedHelpers.findClass(SYS + "TransactionCompat", cl);
        Class<?> rectParams = XposedHelpers.findClass(P + "anim.RectFParams", cl);
        Class<?> wp = XposedHelpers.findClass(P + "util.WindowAnimParams", cl);
        Class<?> floating = XposedHelpers.findClass(P + "FloatingIconInterface", cl);

        lastAnimType = method(impl, "getLastAnimType");
        currentParams = method(impl, "getCurrentRectFParams");
        animContext = field(impl, "windowAnimContext");
        targetSet = method(ctx, "getRemoteAnimationTargetSet");
        floatingIcons = method(ctx, "getFloatingIcons");
        firstTarget = method(set, "getFirstTarget");
        hasMultiTask = method(set, "hasMultiTask");
        targetLeash = field(target, "leash");
        targetPosition = field(target, "position");
        targetTask = field(target, "taskId");
        windowParams = method(rectParams, "getWindowAnimParams");
        quickSwitch = method(rectParams, "isQuickSwitchMode");
        startRect = method(wp, "getStartRect");
        targetRect = method(wp, "getTargetRect");
        outerRadius = method(XposedHelpers.findClass("com.android.systemui.shared.recents.utilities.CornerRadii", cl),
                "getOuterCornerRadius");
        iconSurface = method(floating, "getFloatingIconSurfaceControl");
        shade = method(floating, "getShadeColor");
        compatSurface = field(compat, "mSurfaceControl");
        paramsSurface = field(params, "surface");
        paramsAlpha = field(params, "alpha");
        paramsFlags = field(params, "flags");
        compatTransaction = field(tx, "mTransaction");
        surfaceOfView = method(XposedHelpers.findClass(SYS + "SurfaceControlUtils", cl), "getSurfaceControlCompat", View.class);
        getLauncher = method(XposedHelpers.findClass("com.miui.home.launcher.Application", cl), "getLauncher");
        rootView = method(XposedHelpers.findClass("com.miui.home.launcher.BaseLauncher", cl), "getRootView");

        disableSfAnim(cl);

        // (alfa, alfa2, rect, radios, progreso, bool, límites de la pila, rect girado, …)
        XposedBridge.hookAllMethods(impl, "updateElementValue", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    onFrame(p.thisObject, (RectF) p.args[2], p.args[3], (Rect) p.args[6]);
                } catch (Throwable t) {
                    Genie.stop("error");
                    Log.e("genio: fotograma", t);
                }
            }
        });

        XposedHelpers.findAndHookMethod(params, "applyTo", SurfaceControl.Transaction.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (Genie.idle()) return;
                Object sp = p.thisObject;
                Genie.onApply((SurfaceControl.Transaction) p.args[0], (SurfaceControl) paramsSurface.get(sp),
                        paramsAlpha.getFloat(sp), (paramsFlags.getInt(sp) & FLAG_ALPHA) != 0);
            }
        });

        Class<?> listener = XposedHelpers.findClass(P + "anim.windowanim.LocalWindowAnimImplementor$rectFSpringAnimListener$1", cl);
        final Field owner = field(listener, "this$0");
        XC_MethodHook ended = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                Genie.animEnded(owner.get(p.thisObject));
            }
        };
        XposedBridge.hookAllMethods(listener, "onAnimationEnd", ended);
        XposedBridge.hookAllMethods(listener, "onAnimationCancel", ended);

        XposedHelpers.findAndHookMethod(P + "anim.HomeLayerElement", cl, "bindHomeLeash", compat, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                Genie.homeLeash = surface(p.args[0]);
            }
        });
        XposedHelpers.findAndHookMethod(tx, "setMatrix", compat, Matrix.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (Genie.idle() || p.args[0] == null) return;
                Genie.onMatrix((SurfaceControl.Transaction) compatTransaction.get(p.thisObject),
                        surface(p.args[0]), (Matrix) p.args[1]);
            }
        });

        // Al volver al escritorio se prepara el genio (capa, GPU y shader) para la próxima vez.
        final Class<?> launcherClass = XposedHelpers.findClass("com.miui.home.launcher.BaseLauncher", cl);
        XposedHelpers.findAndHookMethod(android.app.Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!launcherClass.isInstance(p.thisObject)) return;
                try {
                    android.graphics.Rect b = ((android.app.Activity) p.thisObject).getWindowManager()
                            .getCurrentWindowMetrics().getBounds();
                    Genie.prewarm(launcherSurface(), b.width(), b.height());
                    TapCatcher.context = ((android.app.Activity) p.thisObject).getApplicationContext();
                } catch (Throwable t) {
                    Log.e("genio: preparación", t);
                }
            }
        });

        try {
            hookFloatingIcon(cl, tx, compat);
        } catch (Throwable t) {
            Log.e("icono flotante: puede verse la animación de MIUI por debajo", t);
        }
        try {
            hookReopenTap(cl);
        } catch (Throwable t) {
            Log.e("reabrir al tocar el icono durante el cierre: no disponible", t);
        }
        try {
            hookPreload(cl);
        } catch (Throwable t) {
            Log.e("precarga: no disponible", t);
        }
        Springs.install(cl);
        Log.i("genio y muelles de abrir/cerrar activos");
    }

    /**
     * Sin el motor de animación de SurfaceFlinger (lo usan los móviles de gama media y baja): con
     * él, el launcher no recibe los fotogramas. El valor se cambia en el propio campo: el getter
     * es tan corto que ART lo incrusta en quien lo llama y un hook no se ejecutaría.
     */
    private static void disableSfAnim(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClass("com.miui.home.common.sfanim.SfAnimCapability", cl);
        StringBuilder before = new StringBuilder();
        for (String name : new String[]{"shouldUseSfAnim", "shouldUseSfBigIconAnim", "useMultiLayersForSfAnim"}) {
            try {
                Field f = field(c, name);
                before.append(name).append('=').append(f.getBoolean(null)).append(' ');
                f.setBoolean(null, false);
            } catch (Throwable t) {
                Log.e("no se pudo cambiar " + name, t);
            }
        }
        XposedHelpers.findAndHookMethod("com.miui.home.common.sfanim.SfAnimCapability$Companion", cl,
                "getShouldUseSfAnim", XC_MethodReplacement.returnConstant(false));
        Log.i("motor de SurfaceFlinger desactivado (antes: " + before.toString().trim() + ")");
    }

    /**
     * El icono flotante de MIUI, en sus dos variantes: vista en la ventana del launcher
     * ({@code FloatingIconView2}) o capa sobre la app ({@code FloatingIconLayer2}). Ver
     * {@link Genie#floatingIconAlpha()}.
     */
    private static void hookFloatingIcon(ClassLoader cl, Class<?> tx, Class<?> compat) {
        XposedHelpers.findAndHookMethod(P + "views.FloatingIconView2", cl, "draw", Canvas.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                float a = Genie.floatingIconAlpha();
                if (a >= 0.999f) return;
                if (a <= 0.001f) {
                    p.setResult(null);                                 // no se dibuja nada
                    return;
                }
                p.setObjectExtra("save", ((Canvas) p.args[0]).saveLayerAlpha(null, Math.round(a * 255)));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Object save = p.getObjectExtra("save");
                if (save != null) ((Canvas) p.args[0]).restoreToCount((Integer) save);
            }
        });

        Class<?> layer2 = XposedHelpers.findClass(P + "views.FloatingIconLayer2", cl);
        final Method iconSc = method(layer2, "getFloatingIconSurfaceControl");
        final Field shaderSc = field(layer2, "mFloatingIconShaderSurfaceControl");
        final Field drawIcon = field(layer2, "mIsDrawIcon");
        final Method setAlpha = method(tx, "setAlpha", compat, float.class);
        // showSurfaceControl(ventana, rect, radio, alfa del icono, alfa del velo) → transacción
        XposedBridge.hookAllMethods(layer2, "showSurfaceControl", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                Object t = p.getResult();
                if (t == null || p.args.length < 5) return;
                Object self = p.thisObject;
                Object veil = shaderSc.get(self);
                // El velo, fuera desde el primer fotograma (era el rectángulo oscuro alrededor).
                if (veil != null && Genie.hideVeil()) setAlpha.invoke(t, veil, 0f);
                float a = Genie.floatingIconAlpha();
                if (a >= 0.999f) return;
                Object icon = iconSc.invoke(self);
                if (icon != null) setAlpha.invoke(t, icon, drawIcon.getBoolean(self) ? (Float) p.args[3] * a : 0f);
            }
        });
    }

    // Toque en curso (solo hilo principal).
    private static float downX, downY;
    private static long downMs;
    private static boolean clicked, tapCandidate;
    // StateManager de MIUI: icono del escritorio al que vuelve la app (para el genio suelto).
    private static Method smInstanceM, targetViewM;
    private static Field smCompanionF, smWindowF;

    /** Icono del escritorio al que vuelve la app según MIUI, o null. */
    private static View currentTargetView() {
        try {
            if (smInstanceM == null) return null;
            Object window = smWindowF.get(smInstanceM.invoke(smCompanionF.get(null)));
            return window == null ? null : (View) targetViewM.invoke(window);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Hasta cuándo (uptime) un clic en el icono de la app que se cierra debe darle la vuelta. */
    private static volatile long reuseUntilMs;

    /**
     * Reabrir la app tocando su icono mientras se cierra (como en Samsung). MIUI ya sabe darle la
     * vuelta a la animación (StateManager.animToFullScreen), pero mientras se cierra el toque no
     * llega a convertirse en clic del icono. Si se levanta el dedo sobre el icono de la app que
     * se cierra y MIUI no dio el clic, se lo damos nosotros: el camino normal de MIUI hace el resto.
     *
     * Además, MIUI solo da la vuelta si el icono tocado es exactamente la vista a la que vuelve la
     * app ({@code WindowElement.isSameElement}); si no, la lanza de cero (≈200 ms de espera y una
     * apertura nueva desde el icono). Un clic que empezó sobre el icono de la app que se cierra
     * cuenta como el mismo.
     */
    private static void hookReopenTap(ClassLoader cl) {
        Class<?> launcher = XposedHelpers.findClass("com.miui.home.launcher.BaseLauncher", cl);
        final Method onClick = method(launcher, "onClick", View.class);
        Class<?> sm = XposedHelpers.findClass(P + "anim.StateManager", cl);
        final Method smInstance = method(XposedHelpers.findClass(P + "anim.StateManager$Companion", cl), "getInstance");
        final Field smCompanion = field(sm, "Companion");
        final Field smWindow = field(sm, "windowElement");
        final Class<?> we = XposedHelpers.findClass(P + "anim.WindowElement", cl);
        final Method targetView = method(we, "getLauncherTargetView");
        final Method reusable = method(we, "isReusefulAnimRunning");
        final Method toFullScreen = method(sm, "animToFullScreen", View.class);
        final Method gotoState = XposedHelpers.findMethodExact(sm, "gotoState", XposedHelpers.findClass(P + "anim.State", cl));
        gotoState.setAccessible(true);
        final Field appState = field(sm, "appState");
        smInstanceM = smInstance;
        smCompanionF = smCompanion;
        smWindowF = smWindow;
        targetViewM = targetView;

        // El genio suelto (la app que vuelve sola a su icono mientras se abre otra) oculta el
        // icono real hasta llegar; MIUI lo enseñaría al abandonar el cierre.
        final Class<?> shortcut = XposedHelpers.findClassIfExists("com.miui.home.launcher.ShortcutIcon", cl);
        if (shortcut != null) {
            final Method setIconVisibility = method(shortcut, "setIconVisibility", int.class);
            XposedBridge.hookMethod(setIconVisibility, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if ((Integer) p.args[0] == View.VISIBLE && Genie.ghostHolds(p.thisObject)) {
                        p.args[0] = View.INVISIBLE;
                    }
                }
            });
            Genie.icons = new Genie.Icons() {
                @Override
                public void setVisible(View icon, boolean visible) {
                    if (!shortcut.isInstance(icon)) return;
                    try {
                        setIconVisibility.invoke(icon, visible ? View.VISIBLE : View.INVISIBLE);
                    } catch (Throwable t) {
                        Log.e("genio suelto: icono", t);
                    }
                }
            };
        }

        // Toque en la ventanita sobre el icono (TapCatcher): lo mismo que hace MIUI al tocar el
        // icono de la app que vuelve (StateManager.HomeState.onIconClick), sin depender de que el
        // toque llegue al launcher ni de que MIUI reconozca el icono.
        TapCatcher.listener = new TapCatcher.Listener() {
            @Override
            public void onTap() {
                try {
                    Object manager = smInstance.invoke(smCompanion.get(null));
                    Object window = smWindow.get(manager);
                    View target = window == null ? null : (View) targetView.invoke(window);
                    if (target == null) {
                        Log.i("toque: en el icono, pero MIUI ya no sabe a qué icono vuelve la app");
                        return;
                    }
                    if ((Boolean) reusable.invoke(window)) {
                        Log.i("toque: se reabre la app desde donde está");
                        toFullScreen.invoke(manager, target);
                        gotoState.invoke(manager, appState.get(manager));
                    } else {
                        Log.i("toque: MIUI no puede darle la vuelta; se abre de nuevo");
                        Object l = getLauncher.invoke(null);
                        reuseUntilMs = android.os.SystemClock.uptimeMillis() + 400;
                        if (l != null) onClick.invoke(l, target);
                    }
                } catch (Throwable t) {
                    Log.e("toque: no se pudo reabrir", t);
                }
            }

            private final int[] loc = new int[2];

            @Override
            public void forward(android.view.MotionEvent e) {
                try {
                    Object l = getLauncher.invoke(null);
                    if (!(l instanceof android.app.Activity)) return;
                    View decor = ((android.app.Activity) l).getWindow().getDecorView();
                    decor.getLocationOnScreen(loc);
                    e.offsetLocation(-loc[0], -loc[1]);
                    decor.dispatchTouchEvent(e);
                } catch (Throwable t) {
                    Log.e("toque: no se pudo pasar al escritorio", t);
                }
            }
        };

        XposedBridge.hookMethod(onClick, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                clicked = true;
                if (tapCandidate) reuseUntilMs = android.os.SystemClock.uptimeMillis() + 400;
                if (!Genie.idle()) Log.i("toque: clic durante el genio" + (tapCandidate ? ", en el icono de la app que se cierra" : ""));
            }
        });
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass(P + "anim.WindowElement", cl), "isSameElement", View.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(p.getResult())) return;
                if (android.os.SystemClock.uptimeMillis() > reuseUntilMs) return;
                reuseUntilMs = 0;
                p.setResult(true);
                Log.i("toque: MIUI no lo veía como el mismo icono; se le da la vuelta a la animación");
            }
        });
        XposedHelpers.findAndHookMethod(launcher, "dispatchTouchEvent", android.view.MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                android.view.MotionEvent e = (android.view.MotionEvent) p.args[0];
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX();
                    downY = e.getRawY();
                    downMs = e.getEventTime();
                    clicked = false;
                    tapCandidate = Genie.tapOnClosingIcon(downX, downY);
                } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_POINTER_DOWN) {
                    tapCandidate = false;
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                android.view.MotionEvent e = (android.view.MotionEvent) p.args[0];
                if (e.getActionMasked() != android.view.MotionEvent.ACTION_UP || !tapCandidate) return;
                tapCandidate = false;
                if (clicked) return;                                  // MIUI ya lo hizo
                Object self = p.thisObject;
                float slop = android.view.ViewConfiguration.get((android.content.Context) self).getScaledTouchSlop() * 2f;
                if (Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > slop
                        || e.getEventTime() - downMs > 500 || !Genie.tapOnClosingIcon(e.getRawX(), e.getRawY())) {
                    return;
                }
                try {
                    Object window = smWindow.get(smInstance.invoke(smCompanion.get(null)));
                    View target = window == null ? null : (View) targetView.invoke(window);
                    if (target == null) {
                        Log.i("toque: en el icono, pero MIUI no sabe a qué icono vuelve la app");
                        return;
                    }
                    Log.i("toque: reabro la app que se cierra (MIUI no dio el clic)");
                    tapCandidate = true;                                 // para isSameElement
                    onClick.invoke(self, target);
                } catch (Throwable t) {
                    Log.e("toque: no se pudo reabrir", t);
                }
            }
        });
    }

    /**
     * Precarga, para que el genio responda en el primer fotograma:
     * <ul>
     * <li>Al empezar el gesto de cerrar, el sistema le da la ventana de la app al launcher
     *     ({@code RecentsAnimationListenerImpl.onAnimationStart}) antes de que se mueva: se
     *     captura ya, en vez de en el primer fotograma del genio.</li>
     * <li>Al poner el dedo en un icono, se sube a la GPU antes de soltarlo: cuando empieza la
     *     apertura (con el sistema ocupado lanzando la app) ya está.</li>
     * </ul>
     */
    private static void hookPreload(ClassLoader cl) {
        final Class<?> targetClass = XposedHelpers.findClass("com.android.systemui.shared.recents.system.RemoteAnimationTargetCompat", cl);
        Class<?> targetCls = targetClass;
        final Field mode = field(targetCls, "mode");
        final Field activityType = field(targetCls, "activityType");
        final Field leash = field(targetCls, "leash");
        final Field position = field(targetCls, "position");
        final Field task = field(targetCls, "taskId");
        final Field pkgField = field(targetCls, "packageName");
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.miui.home.recents.RecentsAnimationListenerImpl", cl),
                "onAnimationStart", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!Genie.enabled) return;
                        try {
                            for (Object arg : p.args) {
                                if (!(arg instanceof Object[])) continue;
                                for (Object t : (Object[]) arg) {
                                    if (t == null || !targetClass.isInstance(t)) continue;
                                    if (mode.getInt(t) != 1 || activityType.getInt(t) != 1) continue;  // la app que se cierra
                                    SurfaceControl sc = surface(leash.get(t));
                                    Point pos = (Point) position.get(t);
                                    Genie.precapture(sc, task.getInt(t), pos == null ? 0 : pos.x, pos == null ? 0 : pos.y,
                                            packageOf(t, pkgField));
                                    return;
                                }
                            }
                        } catch (Throwable e) {
                            Log.e("precarga: captura", e);
                        }
                    }
                });

        final Class<?> shortcut = XposedHelpers.findClass("com.miui.home.launcher.ShortcutIcon", cl);
        final Field iconDrawable = field(shortcut, "mIconDrawable");
        final Method iconBounds = method(shortcut, "getIconBounds", Rect.class);
        final int[] loc = new int[2];
        final Rect r = new Rect();
        XposedHelpers.findAndHookMethod(shortcut, "onTouchEvent", android.view.MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!Genie.enabled || ((android.view.MotionEvent) p.args[0]).getActionMasked() != android.view.MotionEvent.ACTION_DOWN) return;
                try {
                    View v = (View) p.thisObject;
                    Drawable d = copy((Drawable) iconDrawable.get(v));
                    if (d == null) return;
                    iconBounds.invoke(v, r);
                    v.getLocationOnScreen(loc);
                    Genie.prepareIcon(d, new RectF(r.left + loc[0], r.top + loc[1], r.right + loc[0], r.bottom + loc[1]),
                            packageOfIcon(v));
                } catch (Throwable e) {
                    Log.e("precarga: icono", e);
                }
            }
        });
    }

    /** Paquete de la app de una ventana (campo de MIUI, o la tarea del sistema). */
    private static String packageOf(Object target, Field pkgField) {
        try {
            String p = (String) pkgField.get(target);
            if (p != null) return p;
            Object rat = XposedHelpers.getObjectField(target, "target");
            Object info = rat == null ? null : XposedHelpers.getObjectField(rat, "taskInfo");
            Object base = info == null ? null : XposedHelpers.getObjectField(info, "baseActivity");
            return base instanceof android.content.ComponentName ? ((android.content.ComponentName) base).getPackageName() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Paquete de la app de un icono del escritorio (su ShortcutInfo). */
    private static String packageOfIcon(View icon) {
        try {
            Object info = icon.getTag();
            Object intent = info == null ? null : XposedHelpers.callMethod(info, "getIntent");
            if (!(intent instanceof android.content.Intent)) return null;
            android.content.Intent i = (android.content.Intent) intent;
            if (i.getComponent() != null) return i.getComponent().getPackageName();
            return i.getPackage();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Un fotograma: decide si va con genio y pasa el estado al controlador. */
    private static void onFrame(Object impl, RectF cur, Object radii, Rect bounds) throws Throwable {
        String type = ((Enum<?>) lastAnimType.invoke(impl)).name();
        if (SEEN.add(type + impl.getClass().getSimpleName())) {
            Log.i("animación " + type + " por " + impl.getClass().getSimpleName());
        }
        boolean open = "OPEN_FROM_HOME".equals(type);
        boolean drag = "CLOSE_TO_DRAG".equals(type);
        if (!Genie.enabled || !(open || drag || "CLOSE_TO_HOME".equals(type))) {
            Genie.stopFor(impl, type);
            // Otra app que se abre sin genio (desde un widget…) mientras una se cierra.
            if (Genie.enabled && type.startsWith("OPEN")) Genie.otherAppOpening(impl, windowOf(impl));
            return;
        }
        Object params = currentParams.invoke(impl);
        if (params == null) return;
        if ((Boolean) quickSwitch.invoke(params)) {                 // deslizar entre apps
            Genie.stopFor(impl, "cambio rápido");
            return;
        }
        boolean starting = !Genie.running(impl);
        boolean fresh = !Genie.active();                             // ni siquiera otro genio en marcha

        Object ctx = animContext.get(impl);
        Object set = targetSet.invoke(ctx);                          // null en el arranque rápido
        SurfaceControl leash = null;
        Point pos = null;
        int task = -1;
        if (set != null) {
            if ((Boolean) hasMultiTask.invoke(set)) {
                Genie.stopFor(impl, "varias ventanas");
                return;
            }
            Object target = firstTarget.invoke(set);
            if (target != null) {
                leash = surface(targetLeash.get(target));
                pos = (Point) targetPosition.get(target);
                task = targetTask.getInt(target);
            }
            if (leash != null && !leash.isValid()) leash = null;
        }
        if (!open && leash == null) {                                // cerrar sin ventana: nada
            reject(type, "sin ventana");
            return;
        }

        Object wp = windowParams.invoke(params);
        RectF start = (RectF) startRect.invoke(wp);
        RectF end = (RectF) targetRect.invoke(wp);
        Genie.Frame f = FRAME;
        if (bounds != null && !bounds.isEmpty()) f.full.set(bounds);
        else if (open && end != null) f.full.set(end);
        else if (start != null) f.full.set(start);
        else return;
        if (f.full.width() >= f.full.height()) {                     // solo en vertical
            Genie.stopFor(impl, "horizontal");
            return;
        }
        RectF icon = drag ? null : (open ? start : end);
        f.hasIcon = icon != null && !icon.isEmpty();
        if (f.hasIcon) f.icon.set(icon);
        else if (!drag) return;

        if (fresh) {
            // No empezar cuando la animación ya acaba (MIUI coloca la ventana al final con un
            // último fotograma fuera del muelle): al abrir, con la ventana ya grande; al cerrar,
            // ya en el icono.
            float pw = (cur.width() - (f.hasIcon ? f.icon.width() : 0f)) / Math.max(1f, f.full.width());
            if (open ? pw > 0.6f : (!drag && pw < 0.08f)) {
                reject(type, "la animación ya está acabando (anchura " + Math.round(pw * 100) + "%)");
                return;
            }
        }
        f.key = impl;
        f.open = open;
        f.leash = leash;
        f.taskId = task;
        f.originX = pos == null ? 0 : pos.x;
        f.originY = pos == null ? 0 : pos.y;
        f.cur.set(cur);
        f.radius = (Float) outerRadius.invoke(radii);
        f.root = null;
        f.exclude = null;
        f.iconDrawable = null;
        f.targetView = null;
        f.bg = 0;
        boolean wantIcon = f.hasIcon && (starting || Genie.needsIcon(impl));
        // MIUI fija el color del velo (el de la ventana de inicio de la app) cuando el sistema
        // responde a la apertura, unos fotogramas después de empezar: hasta tenerlo, se relee.
        boolean wantShade = open && (starting || Genie.needsShade(impl));
        if (starting || wantIcon || wantShade) {
            Object icons = floatingIcons.invoke(ctx);
            Object first = icons != null && Array.getLength(icons) > 0 ? Array.get(icons, 0) : null;
            if (starting) {
                f.root = launcherSurface();
                if (f.root == null) {
                    reject(type, "sin superficie del launcher");
                    return;
                }
                f.exclude = iconLayers(icons);
            }
            if (first != null && (starting || wantShade)) f.bg = (Integer) shade.invoke(first);
            // El icono solo cuando ya se sabe cuál es (al cerrar, al soltar el dedo).
            if (wantIcon && first != null) f.iconDrawable = copy(drawableOf(first));
        }
        if (!open && f.hasIcon && (starting || wantIcon || Genie.needsTarget(impl))) f.targetView = currentTargetView();
        Genie.frame(f);
    }

    /** Ventana que anima este animador (o null). */
    private static SurfaceControl windowOf(Object impl) {
        try {
            Object set = targetSet.invoke(animContext.get(impl));
            Object target = set == null ? null : firstTarget.invoke(set);
            SurfaceControl sc = target == null ? null : surface(targetLeash.get(target));
            return sc != null && sc.isValid() ? sc : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Motivo por el que un fotograma no lleva genio; se anota una vez por tipo y motivo. */
    private static void reject(String type, String why) {
        if (SEEN.add(type + why)) Log.i("sin genio en " + type + ": " + why);
    }

    /** Superficie de la ventana del launcher (padre de nuestra capa). */
    private static SurfaceControl launcherSurface() throws Throwable {
        Object launcher = getLauncher.invoke(null);
        if (launcher == null) return null;
        SurfaceControl sc = surface(surfaceOfView.invoke(null, rootView.invoke(launcher)));
        return sc != null && sc.isValid() ? sc : null;
    }

    /** Capas del icono flotante de MIUI: se excluyen de la captura de la ventana. */
    private static SurfaceControl[] iconLayers(Object icons) throws Throwable {
        List<SurfaceControl> out = new ArrayList<>();
        for (int i = 0, n = icons == null ? 0 : Array.getLength(icons); i < n; i++) {
            SurfaceControl sc = surface(iconSurface.invoke(Array.get(icons, i)));
            if (sc != null && sc.isValid()) out.add(sc);
        }
        return out.toArray(new SurfaceControl[0]);
    }

    /** Icono de la app tal como lo tiene el icono flotante de MIUI (campo mDrawable). */
    private static Drawable drawableOf(Object floating) {
        Class<?> c = floating.getClass();
        try {
            Field f;
            synchronized (DRAWABLE) {
                f = DRAWABLE.get(c);
                if (f == null && !DRAWABLE.containsKey(c)) {
                    try {
                        f = field(c, "mDrawable");
                    } catch (Throwable ignored) {
                    }
                    DRAWABLE.put(c, f);
                }
            }
            return f == null ? null : (Drawable) f.get(floating);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Copia propia: el original lo dibuja el launcher en su hilo y no se debe tocar desde otro. */
    private static Drawable copy(Drawable d) {
        if (d == null) return null;
        Drawable.ConstantState cs = d.getConstantState();
        return cs == null ? null : cs.newDrawable().mutate();
    }

    private static SurfaceControl surface(Object compat) throws IllegalAccessException {
        return compat == null ? null : (SurfaceControl) compatSurface.get(compat);
    }

    static Method method(Class<?> c, String name, Class<?>... types) {
        Method m = XposedHelpers.findMethodExact(c, name, types);
        m.setAccessible(true);
        return m;
    }

    static Field field(Class<?> c, String name) {
        Field f = XposedHelpers.findField(c, name);
        f.setAccessible(true);
        return f;
    }

    private WindowAnim() {
    }
}
