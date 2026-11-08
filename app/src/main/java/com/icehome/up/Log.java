package com.icehome.up;

import android.content.ContentValues;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * Registro en LSPosed y, desde el launcher, también en la app HyperSpring (para verlo y copiarlo
 * sin buscar en LSPosed). El envío a la app va en un hilo propio: nunca frena una animación.
 */
final class Log {

    private static Context app;
    private static Handler sender;
    private static final List<String> early = new ArrayList<>();

    /** Desde que el launcher tiene Application, las líneas se mandan también a la app. */
    static synchronized void attach(Context c) {
        app = c.getApplicationContext();
        HandlerThread t = new HandlerThread("IceHome-log");
        t.start();
        sender = new Handler(t.getLooper());
        for (String l : early) send(l);
        early.clear();
    }

    static void i(String msg) {
        out("IceHome: " + msg);
    }

    static void w(String msg) {
        out("IceHome [aviso]: " + msg);
    }

    static void e(String msg, Throwable t) {
        out("IceHome [error]: " + msg + " — " + t);
        XposedBridge.log(t);
    }

    private static void out(String line) {
        XposedBridge.log(line);
        String stamped = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
                .format(new java.util.Date()) + "  " + line.replace("IceHome: ", "");
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
                    app.getContentResolver().insert(PrefsProvider.LOG, v);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private Log() {
    }
}
