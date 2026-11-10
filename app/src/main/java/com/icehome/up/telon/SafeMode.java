package com.icehome.up.telon;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Modo seguro: si la interfaz del sistema (SystemUI) arranca {@link #LIMIT} veces seguidas con
 * menos de {@link #QUICK_MS} entre una y otra (se está cayendo y el sistema la relanza), Telón deja
 * de cargarse. Un arranque que aguanta {@link #STABLE_MS} pone el contador a cero.
 *
 * El estado va en ajustes globales del sistema (SystemUI puede escribirlos; cualquier app puede
 * leerlos, así la app de Telón lo enseña):
 * <ul>
 * <li>{@code telon_safe_mode}: 1 = en modo seguro (Telón no se carga).</li>
 * <li>{@code telon_starts}, {@code telon_last_start}: arranques seguidos y hora del último.</li>
 * </ul>
 * Rescate a mano (Termux, root): {@code su -c "settings put global telon_safe_mode 1"} y reiniciar.
 */
public final class SafeMode {

    static final String KEY = "telon_safe_mode";
    private static final String STARTS = "telon_starts", LAST = "telon_last_start";
    private static final String INTENTIONAL_RESTART = "hyperspring_sysui_intentional_restart_at";
    private static final int LIMIT = 3;
    private static final long QUICK_MS = 60_000, STABLE_MS = 30_000, INTENTIONAL_TTL_MS = 120_000;

    /** Al cargar SystemUI: cuenta el arranque; true si hay que cargar Telón. */
    public static boolean checkAtLoad(Context c) {
        if (c == null) return true;                          // sin contexto no se puede contar
        try {
            android.content.ContentResolver r = c.getContentResolver();
            if (Settings.Global.getInt(r, KEY, 0) == 1) {
                Log.w("modo seguro: Telón no se carga (reactívalo desde la app)");
                return false;
            }
            long now = System.currentTimeMillis();
            long intentionalAt = Settings.Global.getLong(r, INTENTIONAL_RESTART, 0);
            if (intentionalAt != 0L) {
                // A Settings-triggered restart is not evidence of a SystemUI crash loop.
                Settings.Global.putLong(r, INTENTIONAL_RESTART, 0L);
                if (now >= intentionalAt && now - intentionalAt <= INTENTIONAL_TTL_MS) {
                    Settings.Global.putLong(r, LAST, now);
                    Settings.Global.putInt(r, STARTS, 0);
                    Log.i("reinicio intencional de SystemUI reconocido; no cuenta como fallo");
                    return true;
                }
            }
            long last = Settings.Global.getLong(r, LAST, 0);
            int starts = now - last < QUICK_MS ? Settings.Global.getInt(r, STARTS, 0) + 1 : 1;
            Settings.Global.putLong(r, LAST, now);
            Settings.Global.putInt(r, STARTS, starts);
            if (starts >= LIMIT) {
                Settings.Global.putInt(r, KEY, 1);
                Settings.Global.putInt(r, STARTS, 0);
                Log.w("modo seguro: la interfaz se ha reiniciado " + starts + " veces seguidas; Telón desactivado");
                return false;
            }
        } catch (Throwable t) {
            Log.e("modo seguro: no se pudo contar el arranque", t);
        }
        return true;
    }

    /** Marca una orden de reinicio emitida desde Ajustes antes de cerrar SystemUI. */
    public static void markIntentionalRestart(Context c) {
        if (c == null) return;
        try {
            Settings.Global.putLong(c.getContentResolver(), INTENTIONAL_RESTART, System.currentTimeMillis());
        } catch (Throwable t) {
            Log.e("no se pudo marcar el reinicio intencional de SystemUI", t);
        }
    }

    /** Con SystemUI ya en marcha: si aguanta un rato, el arranque fue bueno. */
    public static void markStableLater(final Context c) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Settings.Global.putInt(c.getContentResolver(), STARTS, 0);
                } catch (Throwable ignored) {
                }
            }
        }, STABLE_MS);
    }

    /** Desde la app (por difusión): salir del modo seguro. */
    public static void reset(Context c) {
        try {
            Settings.Global.putInt(c.getContentResolver(), KEY, 0);
            Settings.Global.putInt(c.getContentResolver(), STARTS, 0);
            Log.i("modo seguro: desactivado; Telón se cargará en el próximo arranque de la interfaz");
        } catch (Throwable t) {
            Log.e("modo seguro: no se pudo desactivar", t);
        }
    }

    /** En la app: ¿está en modo seguro? */
    public static boolean active(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), KEY, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private SafeMode() {
    }
}
