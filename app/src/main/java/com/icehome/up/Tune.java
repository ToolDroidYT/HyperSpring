package com.icehome.up;

import android.content.Context;
import android.provider.Settings;

import java.util.HashMap;
import java.util.Map;

/**
 * Ajustes del launcher: desde la app (PrefsProvider) o con "settings put global icehome_… valor".
 * En el launcher se leen una vez al arrancar y cada vez que la app avisa (difusión TUNE), y se
 * guardan en memoria: las animaciones nunca consultan el proveedor en mitad de un fotograma.
 * Los interruptores de función se aplican al reiniciar el launcher; los valores, al momento.
 */
final class Tune {

    /** Clave, nombre, mínimo, máximo, por defecto, decimales (-1 = interruptor), explicación. "#" = sección. */
    public static final Object[][] ITEMS = {
            {"#", "Funciones"},
            {"icehome_genie_on", "Efecto genio al abrir y cerrar apps", 0f, 1f, 1f, -1, "La ventana sale del icono y vuelve a él con forma de copa o de genio. Se aplica al reiniciar el launcher."},
            {"icehome_stack_on", "Pila de widgets", 0f, 1f, 1f, -1, "Los widgets apilados se barajan con un gesto y se pueden arrastrar libremente. Se aplica al reiniciar el launcher."},
            {"icehome_folder_morph_on", "Carpetas con muelle", 0f, 1f, 1f, -1, "Al abrir o cerrar una carpeta, cambia de tamaño con un rebote suave. Se aplica al reiniciar el launcher."},
            {"icehome_folder_glass_on", "Cristal en las carpetas", 0f, 1f, 1f, -1, "El fondo de las carpetas abiertas es cristal desenfocado en vez de un color plano. Se aplica al reiniciar el launcher."},
            {"icehome_curtain_on", "Recientes que se doblan", 0f, 1f, 1f, -1, "En la vista de recientes, las tarjetas se doblan hacia el lado contrario según la fuerza con la que las deslizas. Se aplica al reiniciar el launcher."},
            {"#", "Abrir y cerrar apps"},
            {"icehome_genie_style", "Estilo", 0f, 3f, 0f, 0, "0 = genio clásico con cola. 1 = copa como Samsung: arriba sube primero y luego baja el resto. 2 = Ice: copa más suave, con rebote al abrir, el icono que recibe la copa y el escritorio que se oscurece detrás. 3 = Profundidad: sin genio; la animación normal de HyperOS, pero la ventana es una hoja rígida que se inclina (arriba al fondo), más cuanto más fuerte cierras, y deja estela de movimiento. Intensidad regula la inclinación y la estela."},
            {"icehome_genie_strength", "Intensidad", 0f, 4f, 2.2f, 1, "Cuánto se deforma la ventana. 0 es un rectángulo normal; 1,8–2,2 se parece a Samsung."},
            {"icehome_genie_quality", "Resolución", 50f, 100f, 75f, 0, "Nitidez de la animación. Más alta se ve más nítida pero gasta más GPU; en móviles modestos, 50–60 %."},
            {"icehome_genie_physics", "Físicas al arrastrar", 0f, 2f, 1f, 1, "Al cerrar una app con el dedo, la ventana se balancea como una lámpara colgada. 0 la deja rígida."},
            {"icehome_genie_fps", "Fotogramas por segundo", 30f, 120f, 60f, 0, "Máximo de fotogramas de la animación. Ponlo igual al refresco de tu pantalla (60 si no pasa de 60 Hz) para no gastar de más."},
            {"icehome_genie_live", "Contenido en vivo", 0f, 1f, 1f, -1, "Durante la apertura, la app se actualiza mientras crece. Desactivado ahorra trabajo a la GPU y la imagen se queda quieta hasta el final."},
            {"icehome_press_on", "Iconos que se hunden", 0f, 1f, 1f, -1, "Al pulsar un icono se hace un poco más pequeño y vuelve con un rebote al soltarlo."},
            {"icehome_wallpaper_zoom", "Zoom del fondo", 0f, 1.5f, 1f, 2, "El fondo de pantalla se acerca al abrir una app y se aleja al volver. 0 deja el comportamiento de HyperOS."},
            {"icehome_haptic", "Vibración al llegar", 0f, 1f, 0.3f, 2, "Un toque de vibración muy suave cuando la ventana entra en su icono. 0 la quita. Respeta el ajuste de vibración del sistema."},
            {"icehome_anim_speed", "Velocidad", 0.5f, 2f, 1f, 2, "Velocidad de las animaciones de abrir y cerrar. Más de 1 es más rápida."},
            {"icehome_anim_bounce", "Rebote", 0f, 0.4f, 0f, 2, "Cuánto se pasa la ventana de su sitio al llegar antes de asentarse. 0 llega sin rebote."},
            {"#", "Recientes"},
            {"icehome_curtain_strength", "Doblez", 0f, 3f, 1f, 1, "Cuánto se doblan las tarjetas de recientes según la velocidad del dedo."},
            {"icehome_curtain_style", "Estilo telón", 0f, 1f, 0f, -1, "Activado: la parte de arriba queda fija y se dobla la de abajo, como un telón. Desactivado: se dobla el centro, como una cortina."},
            {"#", "Carpetas"},
            {"icehome_folder_blur", "Desenfoque", 0f, 30f, 6f, 0, "Cuánto se desenfoca lo que hay detrás de una carpeta abierta, en dp."},
    };

    private static volatile Map<String, String> cache;

    /** Relee todo y lo aplica a las animaciones (en el launcher). */
    static void reload(Context c) {
        cache = read(c);
        Genie.strength = f(c, "icehome_genie_strength");
        Genie.style = Math.round(f(c, "icehome_genie_style"));
        Genie.live = f(c, "icehome_genie_live") >= 0.5f;
        Genie.physics = f(c, "icehome_genie_physics");
        Genie.maxFps = f(c, "icehome_genie_fps");
        Haptics.strength = f(c, "icehome_haptic");
        IconPress.enabled = f(c, "icehome_press_on") >= 0.5f;
        WallpaperZoom.strength = f(c, "icehome_wallpaper_zoom");
        RecentsCurtain.strength = f(c, "icehome_curtain_strength");
        RecentsCurtain.mode = f(c, "icehome_curtain_style") >= 0.5f ? 1f : 0f;
        GlRenderer.wantedScale = f(c, "icehome_genie_quality") / 100f;
        Springs.speed = f(c, "icehome_anim_speed");
        Springs.bounce = f(c, "icehome_anim_bounce");
        Map<String, String> m = cache;
        Exclude.genie = Exclude.parse(m == null ? null : m.get(Exclude.KEY));
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
     * Interruptor de función al cargar el launcher, antes de que exista su Application: se usa el
     * contexto del sistema. Si no se puede leer, vale lo de por defecto.
     */
    static boolean on(String key) {
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
    static boolean on(Context c, String key) {
        Map<String, String> m = attachRead;
        if (m == null) cache = attachRead = m = read(c);       // una sola consulta al arrancar
        return value(c, key, m) >= 0.5f;
    }

    private static Map<String, String> attachRead;

    private static float f(Context c, String key) {
        return value(c, key, cache);
    }

    private static Map<String, String> read(Context c) {
        Map<String, String> m = new HashMap<>();
        try (android.database.Cursor cur = c.getContentResolver().query(PrefsProvider.URI, null, null, null, null)) {
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
