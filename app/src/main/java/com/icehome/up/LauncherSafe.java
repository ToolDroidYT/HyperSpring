package com.icehome.up;

import android.content.ContentValues;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Modo seguro del escritorio: si el launcher se cae y vuelve a arrancar {@link #LIMIT} veces en
 * menos de un minuto cada vez, la siguiente vez arranca sin las animaciones de HyperSpring (el
 * launcher sigue funcionando normal) y la app lo avisa. Se sale desde la app («Reactivar»).
 * <p>
 * El recuento va en un archivo del propio launcher (al cargar todavía no hay contexto ni permiso
 * para los ajustes del sistema). El estado se publica en el proveedor de la app para que lo vea.
 */
final class LauncherSafe {

    private static final String FILE = "hypertweak_safe.properties";
    private static final int LIMIT = 4;
    private static final long QUICK_MS = 60_000, STABLE_MS = 30_000;
    private static File file;

    /** Al cargar el launcher: cuenta el arranque; true si se pueden instalar las animaciones. */
    static boolean checkAtLoad(String dataDir) {
        if (dataDir == null) return true;
        file = new File(dataDir, FILE);
        Properties p = read();
        if ("1".equals(p.getProperty("safe"))) {
            Log.w("modo seguro del escritorio: las animaciones no se cargan (reactívalas desde la app)");
            return false;
        }
        long now = System.currentTimeMillis();
        long last = parse(p.getProperty("last"));
        int starts = now - last < QUICK_MS ? (int) parse(p.getProperty("starts")) + 1 : 1;
        p.setProperty("last", String.valueOf(now));
        p.setProperty("starts", String.valueOf(starts));
        boolean ok = starts < LIMIT;
        if (!ok) {
            p.setProperty("safe", "1");
            p.setProperty("starts", "0");
            Log.w("modo seguro del escritorio: el launcher se ha reiniciado " + starts + " veces seguidas; animaciones desactivadas");
        }
        write(p);
        return ok;
    }

    /** Con el launcher en marcha: publica el estado y, si aguanta un rato, el arranque fue bueno. */
    static void started(final Context c) {
        publish(c);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                Properties p = read();
                p.setProperty("starts", "0");
                write(p);
            }
        }, STABLE_MS);
    }

    /** Desde la app: salir del modo seguro (se aplica al reiniciar el launcher). */
    static void reset(Context c) {
        Properties p = read();
        p.setProperty("safe", "0");
        p.setProperty("starts", "0");
        write(p);
        Log.i("modo seguro del escritorio: desactivado; reinicia el launcher");
        publish(c);
    }

    private static void publish(Context c) {
        try {
            ContentValues v = new ContentValues();
            v.put("key", "launcher_safe");
            v.put("value", "1".equals(read().getProperty("safe")) ? "1" : "0");
            c.getContentResolver().insert(PrefsProvider.STATE, v);
        } catch (Throwable ignored) {
        }
    }

    private static Properties read() {
        Properties p = new Properties();
        if (file == null || !file.exists()) return p;
        try (FileInputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (Throwable ignored) {
        }
        return p;
    }

    private static void write(Properties p) {
        if (file == null) return;
        try (FileOutputStream out = new FileOutputStream(file)) {
            p.store(out, null);
        } catch (Throwable ignored) {
        }
    }

    private static long parse(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private LauncherSafe() {
    }
}
