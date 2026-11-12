package com.icehome.up;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Actualizaciones desde las releases de GitHub.
 * <ul>
 * <li><b>Buscar:</b> pregunta a la API de GitHub por la última release (sin cuenta ni datos del
 * usuario: solo la versión de la app en la cabecera, como hace cualquier cliente). Como mucho
 * una vez cada 6 horas al abrir la app, o cuando se pide a mano.</li>
 * <li><b>Descargar:</b> el mismo APK que está instalado: {@code HyperSpring.apk} (libxposed) o
 * {@code HyperSpring-legacy.apk} (API 93). Se distinguen porque solo el primero lleva
 * {@code META-INF/xposed}.</li>
 * <li><b>Instalar:</b> con {@link PackageInstaller}, el instalador del sistema. Android enseña su
 * diálogo de confirmación (y, la primera vez, el permiso de «instalar apps desconocidas»). Al
 * terminar, Android cierra la app para ponerla al día.</li>
 * </ul>
 * Todo lo de red va en un hilo aparte y avisa en el hilo principal.
 */
final class Updates {

    static final String REPO = "olilio3/HyperSpring";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/";
    private static final long EVERY_MS = 6L * 60 * 60 * 1000;
    private static final String ACTION_INSTALLED = "com.icehome.up.UPDATE_STATUS";

    /** Solo para probar la pantalla: trata la última release como si fuera más nueva. */
    static boolean pretendNewer;

    /** Una release de GitHub. */
    static final class Release {
        String version, notes, page, apkName, apkUrl;
        long apkSize;

        boolean newer() {
            return pretendNewer || compare(version, Version.NAME) > 0;
        }
    }

    interface Callback {
        /** {@code r} es null si falló; entonces {@code error} dice por qué. */
        void done(Release r, String error);
    }

    interface Progress {
        void progress(float fraction);

        /** {@code apk} es null si falló. */
        void done(File apk, String error);
    }

    // ------------------------------------------------------------------ buscar

    /** La última release publicada (las beta no cuentan). La guarda para enseñarla sin red. */
    static void latest(final Context c, final Callback cb) {
        fetch(c, API + "latest", true, cb);
    }

    /** La release de una versión concreta (para las novedades tras actualizar). */
    static void forVersion(Context c, String version, Callback cb) {
        fetch(c, API + "tags/v" + version, false, cb);
    }

    /** Si toca buscar sola al abrir la app: activado y hace más de 6 horas de la última vez. */
    static boolean due(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean("upd_auto", true) && System.currentTimeMillis() - p.getLong("upd_last", 0) > EVERY_MS;
    }

    /** La última release que se vio (guardada), o null. */
    static Release cached(Context c) {
        String j = prefs(c).getString("upd_json", null);
        if (j == null) return null;
        try {
            return parse(c, new JSONObject(j));
        } catch (Throwable t) {
            return null;
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("app", 0);
    }

    private static void fetch(final Context ctx, final String url, final boolean remember, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                Release r = null;
                String err = null;
                try {
                    String body = new String(get(url), "UTF-8");
                    r = parse(app, new JSONObject(body));
                    if (remember) {
                        prefs(app).edit().putString("upd_json", body).putLong("upd_last", System.currentTimeMillis()).apply();
                    }
                } catch (Throwable t) {
                    err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                }
                final Release fr = r;
                final String fe = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(fr, fe);
                    }
                });
            }
        }, "HyperSpring-update").start();
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(15000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("User-Agent", "HyperSpring/" + Version.NAME);
        int code = h.getResponseCode();
        if (code != 200) {
            h.disconnect();
            throw new IOException("HTTP " + code);
        }
        return h;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            h.disconnect();
        }
    }

    static Release parse(Context c, JSONObject o) throws JSONException {
        Release r = new Release();
        String tag = o.getString("tag_name");
        r.version = tag.startsWith("v") ? tag.substring(1) : tag;
        r.notes = o.optString("body", "");
        r.page = o.optString("html_url", "https://github.com/" + REPO + "/releases");
        String want = modernApk() ? "HyperSpring.apk" : "HyperSpring-legacy.apk";
        JSONArray as = o.optJSONArray("assets");
        if (as != null) {
            for (int i = 0; i < as.length(); i++) {
                JSONObject a = as.getJSONObject(i);
                String name = a.optString("name");
                // El que coincide; si la release solo trae un APK (las antiguas), ese.
                if (name.equals(want) || (r.apkUrl == null && name.endsWith(".apk"))) {
                    r.apkName = name;
                    r.apkUrl = a.optString("browser_download_url", null);
                    r.apkSize = a.optLong("size", 0);
                }
            }
        }
        return r;
    }

    /** true si esta app es el APK de libxposed (lleva {@code META-INF/xposed}); false si es el legacy. */
    static boolean modernApk() {
        ClassLoader cl = Updates.class.getClassLoader();
        return cl != null && cl.getResource("META-INF/xposed/module.prop") != null;
    }

    /**
     * Compara versiones tipo {@code 2.1.0} o {@code 1.1.0-beta1}: número a número, y a igualdad,
     * la que no tiene sufijo (la final) es más nueva que la beta.
     */
    static int compare(String a, String b) {
        String[] as = a.split("-", 2), bs = b.split("-", 2);
        String[] an = as[0].split("\\."), bn = bs[0].split("\\.");
        for (int i = 0; i < Math.max(an.length, bn.length); i++) {
            int x = i < an.length ? num(an[i]) : 0, y = i < bn.length ? num(bn[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        boolean sa = as.length > 1, sb = bs.length > 1;
        if (sa != sb) return sa ? -1 : 1;
        return sa ? as[1].compareTo(bs[1]) : 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ descargar e instalar

    /** Descarga el APK a la caché. Se puede cancelar con {@link Thread#interrupt()} del hilo devuelto. */
    static Thread download(final Context ctx, final Release r, final Progress p) {
        final Handler main = new Handler(Looper.getMainLooper());
        final File out = new File(ctx.getCacheDir(), "update.apk");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    if (r.apkUrl == null) throw new IOException("no APK");
                    HttpURLConnection h = open(r.apkUrl);         // GitHub redirige a su CDN; se sigue solo
                    long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : r.apkSize;
                    try (InputStream in = h.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[16384];
                        long got = 0;
                        int n, lastPct = -1;
                        while ((n = in.read(buf)) > 0) {
                            if (Thread.currentThread().isInterrupted()) throw new IOException("cancelado");
                            os.write(buf, 0, n);
                            got += n;
                            final float f = total > 0 ? Math.min(1f, got / (float) total) : 0f;
                            int pct = Math.round(f * 100);
                            if (pct != lastPct) {
                                lastPct = pct;
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        p.progress(f);
                                    }
                                });
                            }
                        }
                    } finally {
                        h.disconnect();
                    }
                } catch (Throwable e) {
                    err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
                final String fe = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        p.done(fe == null ? out : null, fe);
                    }
                });
            }
        }, "HyperSpring-download");
        t.start();
        return t;
    }

    /**
     * Pasa el APK al instalador del sistema. Android pide confirmación con su propio diálogo; si
     * algo falla, {@code onError} recibe el mensaje.
     */
    static void install(final Activity a, File apk, final Callback onError) {
        try {
            PackageInstaller pi = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(a.getPackageName());
            sp.setSize(apk.length());
            int id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                // El sistema avisa del resultado con este intent (solo para esta app).
                BroadcastReceiver rec = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                            if (confirm != null) a.startActivity(confirm);
                            return;
                        }
                        try {
                            a.unregisterReceiver(this);
                        } catch (Throwable ignored) {
                        }
                        if (st != PackageInstaller.STATUS_SUCCESS) {
                            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                            onError.done(null, msg != null ? msg : "status " + st);
                        }
                    }
                };
                IntentFilter f = new IntentFilter(ACTION_INSTALLED);
                if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(rec, f, Context.RECEIVER_NOT_EXPORTED);
                else a.registerReceiver(rec, f);
                Intent i = new Intent(ACTION_INSTALLED).setPackage(a.getPackageName());
                PendingIntent pend = PendingIntent.getBroadcast(a, id, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                s.commit(pend.getIntentSender());
            }
        } catch (Throwable t) {
            onError.done(null, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
    }

    private Updates() {
    }
}
