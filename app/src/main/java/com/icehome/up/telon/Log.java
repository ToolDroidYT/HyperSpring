package com.icehome.up.telon;

import android.content.ContentValues;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.ArrayList;
import java.util.List;

import com.icehome.up.xp.XposedBridge;

/**
 * Registro en LSPosed y, desde SystemUI, también en la app (para verlo y copiarlo
 * sin buscar en LSPosed). El envío a la app va en un hilo propio: nunca frena una animación.
 */
public final class Log {

    private static Context app;
    private static Handler sender;
    private static final List<String> early = new ArrayList<>();

    /** Desde que SystemUI tiene Application, las líneas se mandan también a la app. */
    public static synchronized void attach(Context c) {
        app = c.getApplicationContext();
        HandlerThread t = new HandlerThread("HyperSpring-uilog");
        t.start();
        sender = new Handler(t.getLooper());
        for (String l : early) send(l);
        early.clear();
    }

    public static void i(String msg) {
        out("HyperSpring UI: " + msg);
    }

    public static void w(String msg) {
        out("HyperSpring UI [aviso]: " + msg);
    }

    public static void e(String msg, Throwable t) {
        out("HyperSpring UI [error]: " + msg + " — " + t);
        XposedBridge.log(t);
    }

    private static void out(String line) {
        XposedBridge.log(line);
        String stamped = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
                .format(new java.util.Date()) + "  " + line.replace("HyperSpring UI: ", "");
        synchronized (Log.class) {
            if (sender == null) {
                if (early.size() < 50) early.add(stamped);
                return;
            }
        }
        send(stamped);
    }

    private static void send(final String line) {
        sender.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ContentValues v = new ContentValues();
                    v.put("line", line);
                    app.getContentResolver().insert(com.icehome.up.PrefsProvider.LOG, v);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** Estado para la app (p. ej. «el centro de control está enganchado»), por el mismo hilo. */
    public static void state(final String key, final String value) {
        synchronized (Log.class) {
            if (sender == null) return;
        }
        sender.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ContentValues v = new ContentValues();
                    v.put("key", key);
                    v.put("value", value);
                    app.getContentResolver().insert(com.icehome.up.PrefsProvider.STATE, v);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private Log() {
    }
}
