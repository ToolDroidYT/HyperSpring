package com.icehome.up.telon;

import android.content.Context;
import android.provider.Settings;

import java.util.HashMap;
import java.util.Map;

/**
 * Ajustes de Telón: desde la app (PrefsProvider) o con "settings put global telon_… valor".
 * En SystemUI se leen una vez al arrancar y cada vez que la app avisa (difusión TUNE), y se
 * guardan en memoria: las animaciones nunca consultan el proveedor en mitad de un fotograma.
 * Los interruptores de función se aplican al reiniciar SystemUI; los valores, al momento.
 */
public final class Tune {

    /** Clave, nombre, mínimo, máximo, por defecto, decimales (-1 = interruptor), explicación. "#" = sección. */
    public static final Object[][] ITEMS = {
            {"#", "Función"},
            {"telon_on", "Telón al cambiar de pantalla", 0f, 1f, 1f, -1, "Dentro de una app, la ventana que entra o sale se inclina según su velocidad y la de debajo se desenfoca. Se aplica al reiniciar la interfaz del sistema."},
            {"#", "Telón"},
            {"telon_strength", "Inclinación", 0f, 3f, 1f, 1, "Cuánto se inclina la ventana según lo rápido que se mueve. 0 la deja recta."},
            {"telon_curve", "Forma", 0f, 1f, 0f, 2, "0 = se inclina recta, como un paralelogramo. 1 = se curva como un telón, con la parte de abajo más doblada."},
            {"telon_blur", "Estela de movimiento", 0f, 1.5f, 0.6f, 2, "Desenfoque en la dirección del movimiento cuando la ventana va rápida. 0 la quita."},
            {"telon_bg_blur", "Desenfoque de la pantalla de debajo", 0f, 1.5f, 1f, 2, "La pantalla que queda detrás se desenfoca mientras entra la nueva y se enfoca al volver. 0 lo quita."},
            {"telon_fps", "Fotogramas por segundo", 30f, 120f, 60f, 0, "Máximo de fotogramas del telón. Ponlo igual al refresco de tu pantalla."},
            {"telon_quality", "Resolución", 30f, 100f, 50f, 0, "Nitidez de la ventana durante el telón. Más alta se ve más nítida pero gasta más."},
    };

    private static volatile Map<String, String> cache;

    /** Relee todo y lo aplica a las animaciones (en SystemUI). */
    public static void reload(Context c) {
        cache = read(c);
        Telon.strength = f(c, "telon_strength");
        Telon.maxFps = f(c, "telon_fps");
        Telon.captureScale = f(c, "telon_quality") / 100f;
        Telon.motionBlur = f(c, "telon_blur");
        Telon.belowBlur = f(c, "telon_bg_blur");
        TelonRenderer.power = 1f + 1.4f * Math.max(0f, Math.min(1f, f(c, "telon_curve")));
    }

    /** Valor de un ajuste (de la memoria si ya se leyó; si no, del proveedor). */
    static float get(Context c, String key) {
        Map<String, String> m = cache;
        return value(c, key, m != null ? m : read(c));
    }

    /** En la app de ajustes: siempre del proveedor, sin memoria. */
    static float fresh(Context c, String key) {
        return value(c, key, read(c));
    }

    /**
     * Interruptor de función al cargar SystemUI, antes de que exista su Application: se usa el
     * contexto del sistema. Si no se puede leer, vale lo de por defecto.
     */
    /** Contexto del proceso al cargar (antes de que exista su Application), o null. */
    public static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread != null) return (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean on(String key) {
        Context c = null;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) c = (Context) app;
            if (c == null) {
                Object thread = at.getMethod("currentActivityThread").invoke(null);
                if (thread != null) c = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
            }
        } catch (Throwable ignored) {
        }
        if (c == null) return (Float) item(key)[4] >= 0.5f;
        if (cache == null) cache = read(c);
        return get(c, key) >= 0.5f;
    }

    /** Interruptor leído con el contexto propio del proceso enganchado (el que el proveedor acepta). */
    public static boolean on(Context c, String key) {
        cache = read(c);
        return get(c, key) >= 0.5f;
    }

    private static float f(Context c, String key) {
        return value(c, key, cache);
    }

    private static Map<String, String> read(Context c) {
        Map<String, String> m = new HashMap<>();
        try (android.database.Cursor cur = c.getContentResolver().query(com.icehome.up.PrefsProvider.URI, null, null, null, null)) {
            while (cur != null && cur.moveToNext()) m.put(cur.getString(0), cur.getString(1));
        } catch (Throwable ignored) {
        }
        return m;
    }

    private static Object[] item(String key) {
        for (Object[] it : ITEMS) if (it[0].equals(key)) return it;
        throw new IllegalArgumentException(key);
    }

    private static float value(Context c, String key, Map<String, String> saved) {
        Object[] it = item(key);
        float def = (Float) it[4];
        try {
            String v = saved == null ? null : saved.get(key);
            if (v == null && c != null) v = Settings.Global.getString(c.getContentResolver(), key);
            if (v != null) return Math.max((Float) it[2], Math.min((Float) it[3], Float.parseFloat(v.trim())));
        } catch (Throwable ignored) {
        }
        return def;
    }

    private Tune() {
    }
}
