package com.icehome.up;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import com.icehome.up.cc.CcGlass;
import com.icehome.up.telon.SafeMode;

import com.icehome.up.xp.XC_MethodHook;
import com.icehome.up.xp.XposedBridge;
import com.icehome.up.xp.XposedHelpers;

/**
 * Órdenes de la app de ajustes, sin root: el launcher y la interfaz del sistema escuchan estas
 * difusiones, protegidas por un permiso de firma de la propia app.
 */
final class Remote {

    static final String PERMISSION = "com.icehome.up.permission.CONTROL";
    static final String TUNE = "com.icehome.up.TUNE";
    static final String RESTART_LAUNCHER = "com.icehome.up.RESTART_LAUNCHER";
    static final String RESTART_SYSTEMUI = "com.icehome.up.RESTART_SYSTEMUI";
    static final String RESET_SAFE = "com.icehome.up.RESET_SAFE";

    /**
     * Desde la app: manda una orden al launcher y a la interfaz del sistema.
     *
     * <p>Va dirigida a cada paquete por su nombre y sin exigir permiso al que la recibe. Antes se
     * mandaba con {@code sendBroadcast(i, PERMISSION)}, que obliga al receptor a TENER el permiso:
     * la interfaz (usuario "system") lo tiene todo, pero el launcher no, y Android descartaba el
     * aviso ("Permission Denial"): sus ajustes no se aplicaban hasta reiniciarlo. La protección
     * que importa sigue en pie: los receptores se registran exigiendo que quien ENVÍA tenga el
     * permiso de firma, y eso solo lo cumple esta app.
     */
    static void send(Context c, String action) {
        for (String pkg : new String[]{"com.miui.home", "com.mi.android.globallauncher", "com.android.systemui"}) {
            try {
                Intent i = new Intent(action);
                i.setPackage(pkg);
                i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                c.sendBroadcast(i);
            } catch (Throwable ignored) {
            }
        }
    }

    /** @param systemUi true en la interfaz del sistema, false en el launcher. */
    static void install(final boolean systemUi) {
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (systemUi) registerSystemUi((Context) p.thisObject);
                    else registerLauncher((Context) p.thisObject);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    private static void registerLauncher(Context ctx) {
        Log.attach(ctx);
        Tune.reload(ctx);
        LauncherSafe.started(ctx);
        seen(ctx, "launcher_seen");
        IntentFilter f = new IntentFilter(TUNE);
        f.addAction(RESTART_LAUNCHER);
        f.addAction(RESET_SAFE);
        register(ctx, f, new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (TUNE.equals(i.getAction())) {
                    Tune.reload(c);
                    GlassFolder.retune(c);
                } else if (RESET_SAFE.equals(i.getAction())) {
                    LauncherSafe.reset(c);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());   // el sistema lo relanza
                }
            }
        });
    }

    private static void registerSystemUi(Context ctx) {
        com.icehome.up.telon.Log.attach(ctx);
        com.icehome.up.telon.Tune.reload(ctx);
        SafeMode.markStableLater(ctx);
        seen(ctx, "systemui_seen");
        IntentFilter f = new IntentFilter(TUNE);
        f.addAction(RESTART_SYSTEMUI);
        f.addAction(RESTART_LAUNCHER);
        f.addAction(RESET_SAFE);
        register(ctx, f, new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (TUNE.equals(i.getAction())) {
                    com.icehome.up.telon.Tune.reload(c);
                    if (MainHook.ccInstalled) CcGlass.retune(c);
                } else if (RESET_SAFE.equals(i.getAction())) {
                    // Reset SystemUI's own guard, then persist a one-shot command for POCO
                    // Launcher. HyperOS can freeze the launcher receiver while it is hidden.
                    SafeMode.reset(c);
                    try {
                        android.content.ContentValues reset = new android.content.ContentValues();
                        reset.put("key", "launcher_safe_reset_requested");
                        reset.put("value", String.valueOf(System.currentTimeMillis()));
                        c.getContentResolver().insert(PrefsProvider.STATE, reset);
                        com.icehome.up.telon.Log.i("launcher safe-mode reset queued for next launcher process start");
                        stopLauncher(c);
                    } catch (Throwable t) {
                        com.icehome.up.telon.Log.e("could not queue launcher safe-mode reset", t);
                    }
                } else if (RESTART_LAUNCHER.equals(i.getAction())) {
                    stopLauncher(c);
                } else {
                    SafeMode.markIntentionalRestart(c);
                    android.os.Process.killProcess(android.os.Process.myPid());   // el sistema lo relanza
                }
            }
        });
    }

    /**
     * Sin root, el launcher no recibe la orden mientras HyperOS lo tiene congelado (cuando no se
     * ve). La interfaz del sistema no se congela nunca y puede cerrar paquetes: lo cierra ella y
     * el sistema lo vuelve a abrir.
     */
    private static void stopLauncher(Context c) {
        try {
            Object am = c.getSystemService(Context.ACTIVITY_SERVICE);
            am.getClass().getMethod("forceStopPackage", String.class).invoke(am, LauncherAdapters.resolvePackage(c));
            com.icehome.up.telon.Log.i("launcher reiniciado desde la interfaz del sistema");
        } catch (Throwable t) {
            com.icehome.up.telon.Log.e("no se pudo reiniciar el launcher desde la interfaz", t);
        }
    }

    /** Para la tarjeta de compatibilidad de la app: cuándo cargó el módulo aquí y con qué versión. */
    private static void seen(Context ctx, String key) {
        try {
            String ver = Version.NAME;              // la del módulo cargado aquí, no la de este proceso
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("key", key);
            // hora | versión | API con la que se cargó (para la tarjeta de compatibilidad)
            v.put("value", System.currentTimeMillis() + "|" + ver + "|" + MainHook.engineName());
            ctx.getContentResolver().insert(PrefsProvider.STATE, v);
        } catch (Throwable ignored) {
        }
    }

    private static void register(Context ctx, IntentFilter f, BroadcastReceiver r) {
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, PERMISSION, null, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(r, f, PERMISSION, null);
    }

    private Remote() {
    }
}
