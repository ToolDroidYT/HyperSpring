package com.icehome.up;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;

/**
 * Importa una sola vez los ajustes guardados con las versiones anteriores a la 1.1.0.
 *
 * <p>Con la API clásica (93), LSPosed redirige los ajustes de la app del módulo a una carpeta
 * suya ({@code /data/misc/<id>/prefs/com.icehome.up/}). Con libxposed (API 102) ya no lo hace:
 * la app guarda en su carpeta normal ({@code shared_prefs}) y, al actualizar, se encontraría
 * sin los ajustes de siempre. La carpeta antigua tiene un nombre aleatorio que la app no puede
 * averiguar por sí sola, así que se lee con root (que casi todo el que usa LSPosed tiene). Sin
 * root no se importa nada y los ajustes vuelven a los valores por defecto.
 */
final class Migrate {

    private static final String DONE = "prefs_imported";

    /** true si este APK es el de libxposed (lleva META-INF/xposed) y aún no se ha importado. */
    static boolean needed(Context c) {
        if (c.getSharedPreferences("app", 0).getBoolean(DONE, false)) return false;
        // El APK "legacy" (API 93) sigue con la carpeta de LSPosed: ahí no hay nada que importar.
        return Migrate.class.getClassLoader().getResource("META-INF/xposed/module.prop") != null;
    }

    private static boolean started;
    /** A quién avisar (en cualquier hilo) cuando termina una importación con éxito. */
    static volatile Runnable listener;

    /**
     * Importa en segundo plano, una sola vez por proceso. Lo llaman la pantalla de ajustes al
     * abrirse y PrefsProvider cuando el launcher o la interfaz piden los ajustes (así llegan
     * aunque no se abra la app). Al terminar avisa a los procesos enganchados y a {@link #listener}.
     */
    static synchronized void start(Context c) {
        if (started || !needed(c)) return;
        started = true;
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!Migrate.run(app)) return;
                Remote.send(app, Remote.TUNE);              // el launcher y la interfaz los releen
                Runnable l = listener;
                if (l != null) l.run();
            }
        }, "importar-ajustes").start();
    }

    /** Lee los ajustes antiguos con root y los copia a los de la app. true si se importó algo. */
    private static boolean run(Context c) {
        boolean imported = false;
        try {
            // El archivo más reciente de cada tipo, por si hay más de una carpeta de LSPosed.
            String cmd = "for n in tune app; do echo \"==$n\"; "
                    + "f=$(ls -t /data/misc/*/prefs/com.icehome.up/$n.xml 2>/dev/null | head -1); "
                    + "[ -n \"$f\" ] && cat \"$f\"; echo; done; echo ==fin";
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String name = null;
            StringBuilder xml = new StringBuilder();
            boolean complete = false;
            for (String l; (l = r.readLine()) != null; ) {
                if (l.startsWith("==")) {
                    if (name != null) imported |= copy(c, name, xml.toString());
                    name = l.substring(2);
                    xml.setLength(0);
                    if ("fin".equals(name)) {
                        complete = true;
                        break;
                    }
                } else {
                    xml.append(l).append('\n');
                }
            }
            p.waitFor();
            // Sin root (o denegado) no llega el "==fin": se volverá a intentar la próxima vez.
            if (!complete) return false;
        } catch (Throwable t) {
            return false;                                   // no hay "su" en este móvil
        }
        // commit(): si "app" venía en la importación, la marca se escribe después y no se pierde.
        c.getSharedPreferences("app", 0).edit().putBoolean(DONE, true).commit();
        return imported;
    }

    /** Sin root, o si el usuario ya ha cambiado algo a mano: no volver a intentarlo. */
    static void skip(Context c) {
        c.getSharedPreferences("app", 0).edit().putBoolean(DONE, true).apply();
    }

    /** Pasa un archivo de SharedPreferences (XML) a las preferencias {@code name} de la app. */
    private static boolean copy(Context c, String name, String xml) {
        if (!xml.contains("<map")) return false;
        try {
            SharedPreferences.Editor e = c.getSharedPreferences(name, 0).edit();
            // Los ajustes de animación se sustituyen enteros: lo que hubiera en la carpeta normal
            // es de antes de que LSPosed la redirigiera, y está anticuado.
            if (PrefsProvider.PREFS.equals(name)) e.clear();
            org.xmlpull.v1.XmlPullParser x = android.util.Xml.newPullParser();
            x.setInput(new StringReader(xml.trim()));
            int n = 0;
            for (int ev = x.getEventType(); ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
                String tag = x.getName(), key = x.getAttributeValue(null, "name"), v = x.getAttributeValue(null, "value");
                if (key == null) continue;
                if ("string".equals(tag)) e.putString(key, x.nextText());
                else if ("boolean".equals(tag)) e.putBoolean(key, Boolean.parseBoolean(v));
                else if ("int".equals(tag)) e.putInt(key, Integer.parseInt(v));
                else if ("long".equals(tag)) e.putLong(key, Long.parseLong(v));
                else if ("float".equals(tag)) e.putFloat(key, Float.parseFloat(v));
                else continue;
                n++;
            }
            return n > 0 && e.commit();
        } catch (Throwable t) {
            return false;
        }
    }

    private Migrate() {
    }
}
