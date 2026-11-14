package com.icehome.up;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

/**
 * Exportar e importar los ajustes en un archivo de texto (JSON). Sirve para guardarlos antes de
 * probar cosas, pasarlos a otro móvil o adjuntarlos a un reporte de error: por eso el archivo
 * lleva también la versión del módulo, el móvil y con qué API se cargó. No lleva datos personales.
 */
final class Backup {

    static final String FILE_NAME = "hyperspring-ajustes.json";
    private static final String MARK = "HyperSpring";

    /** Todas las tablas de ajustes del módulo: launcher, telón y centro de control. */
    private static Object[][][] tables() {
        return new Object[][][]{Tune.ITEMS, com.icehome.up.telon.Tune.ITEMS, com.icehome.up.cc.Tune.ITEMS};
    }

    /** El ajuste con esa clave, o null si el módulo no lo conoce. */
    private static Object[] item(String key) {
        for (Object[][] t : tables()) {
            for (Object[] it : t) if (it[0].equals(key)) return it;
        }
        return null;
    }

    /** Texto JSON con los ajustes guardados (los que no aparecen están en su valor por defecto). */
    static String export(Context c) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", MARK);
        root.put("version", Version.NAME);
        root.put("versionCode", Version.CODE);
        root.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        root.put("android", Build.VERSION.RELEASE);
        SharedPreferences state = c.getSharedPreferences("state", 0);
        root.put("launcher", String.valueOf(state.getString("launcher_seen", "")));
        root.put("systemui", String.valueOf(state.getString("systemui_seen", "")));
        JSONObject s = new JSONObject();
        for (Map.Entry<String, ?> e : c.getSharedPreferences(PrefsProvider.PREFS, 0).getAll().entrySet()) {
            // La lista de apps sin genio es texto, no un número de las tablas, pero también va.
            if (item(e.getKey()) != null || Exclude.KEY.equals(e.getKey())) s.put(e.getKey(), String.valueOf(e.getValue()));
        }
        root.put("settings", s);
        return root.toString(2);
    }

    /**
     * Sustituye los ajustes por los del archivo. Solo acepta claves que el módulo conoce y
     * valores numéricos, recortados al mínimo y máximo de cada ajuste: un archivo viejo, de otra
     * versión o tocado a mano no puede dejar nada en un estado raro.
     *
     * @return cuántos ajustes se han importado, o -1 si el archivo no es de HyperSpring
     */
    static int restore(Context c, String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (!MARK.equals(root.optString("app"))) return -1;
            JSONObject s = root.optJSONObject("settings");
            if (s == null) return -1;
            SharedPreferences.Editor e = c.getSharedPreferences(PrefsProvider.PREFS, 0).edit();
            e.clear();
            int n = 0;
            for (Iterator<String> it = s.keys(); it.hasNext(); ) {
                String key = it.next();
                if (Exclude.KEY.equals(key)) {
                    // Se limpia: solo pasan nombres de paquete válidos.
                    String list = Exclude.join(Exclude.parse(s.optString(key)));
                    if (!list.isEmpty()) {
                        e.putString(key, list);
                        n++;
                    }
                    continue;
                }
                Object[] item = item(key);
                if (item == null) continue;
                float v;
                try {
                    v = Float.parseFloat(s.optString(key).trim());
                } catch (NumberFormatException bad) {
                    continue;
                }
                if (Float.isNaN(v)) continue;
                v = Math.max((Float) item[2], Math.min((Float) item[3], v));
                int decimals = (Integer) item[5];
                e.putString(key, decimals <= 0 ? String.valueOf(Math.round(v))
                        : String.format(java.util.Locale.ROOT, "%." + decimals + "f", v));
                n++;
            }
            e.commit();
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    private Backup() {
    }
}
