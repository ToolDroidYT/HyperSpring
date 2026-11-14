package com.icehome.up;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

import java.util.Map;

/**
 * Expone los ajustes de la app (SharedPreferences), solo lectura, al launcher y a la interfaz del
 * sistema, y recibe de ellos las líneas del registro ({@code /log}) para verlas y copiarlas.
 */
public class PrefsProvider extends ContentProvider {

    public static final String AUTHORITY = "com.icehome.up.prefs";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/tune");
    public static final Uri LOG = Uri.parse("content://" + AUTHORITY + "/log");
    /** Estado que publican los procesos enganchados (p. ej. modo seguro del escritorio). */
    public static final Uri STATE = Uri.parse("content://" + AUTHORITY + "/state");
    private static final String STATE_PREFS = "state";
    public static final String PREFS = "tune";
    private static final String LOG_FILE = "registro.txt";
    private static final int LOG_KEEP = 120;
    private static final String[] READERS = {
            "com.miui.home",
            "com.mi.android.globallauncher",
            "com.android.systemui",
            "miui.systemui.plugin"
    };
    private static final String[] WRITERS = {
            "com.miui.home",
            "com.mi.android.globallauncher",
            "com.android.systemui"
    };
    private final java.util.ArrayDeque<String> log = new java.util.ArrayDeque<>();
    private boolean logLoaded;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        enforceReadAccess();
        if (uri == null) throw new IllegalArgumentException("uri == null");
        if ("state".equals(uri.getLastPathSegment())) {
            MatrixCursor c = new MatrixCursor(new String[]{"key", "value"});
            for (Map.Entry<String, ?> e : getContext().getSharedPreferences(STATE_PREFS, 0).getAll().entrySet()) {
                c.addRow(new Object[]{e.getKey(), String.valueOf(e.getValue())});
            }
            return c;
        }
        if ("log".equals(uri.getLastPathSegment())) {
            MatrixCursor c = new MatrixCursor(new String[]{"line"});
            synchronized (log) {
                loadLog();
                for (String l : log) c.addRow(new Object[]{l});
            }
            return c;
        }
        if (!"tune".equals(uri.getLastPathSegment())) {
            throw new IllegalArgumentException("Unknown path: " + uri.getLastPathSegment());
        }
        // Tras actualizar a la 1.1.0 (libxposed): importa los ajustes antiguos, en segundo plano.
        Migrate.start(getContext());
        MatrixCursor c = new MatrixCursor(new String[]{"key", "value"});
        SharedPreferences p = getContext().getSharedPreferences(PREFS, 0);
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) c.addRow(new Object[]{e.getKey(), String.valueOf(e.getValue())});
        return c;
    }

    private static boolean isLauncher(String packageName) {
        return "com.miui.home".equals(packageName)
                || "com.mi.android.globallauncher".equals(packageName);
    }

    private void enforceReadAccess() {
        if (Binder.getCallingUid() == Process.myUid() || verifiedCaller(READERS) != null) return;
        throw new SecurityException("Caller is not allowed to read HyperSpring provider data");
    }

    /**
     * ContentProvider caller package metadata is useful only when it matches the actual Binder UID.
     * This prevents another app from claiming an allow-listed package name.
     */
    private String verifiedCaller(String... allowedPackages) {
        final String caller;
        try {
            caller = getCallingPackage();
        } catch (Throwable ignored) {
            return null;
        }
        if (caller == null) return null;
        for (String allowed : allowedPackages) {
            if (!allowed.equals(caller)) continue;
            try {
                if (getContext().getPackageManager().getPackageUid(caller, 0) == Binder.getCallingUid()) {
                    return caller;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        // Validate both the claimed package and its UID. getCallingPackage() alone is not an ACL.
        if (v == null || uri == null) return null;
        String from = verifiedCaller(WRITERS);
        if (from == null) return null;
        boolean launcher = isLauncher(from);
        if ("state".equals(uri.getLastPathSegment())) {
            String k = v.getAsString("key"), val = v.getAsString("value");
            if (k == null || k.length() > 64) return null;
            getContext().getSharedPreferences(STATE_PREFS, 0).edit().putString(k, val).apply();
            return uri;
        }
        if (!"log".equals(uri.getLastPathSegment())) return null;
        String line = v.getAsString("line");
        if (line == null) return null;
        line = (launcher ? "[inicio] " : "[sistema] ") + line;
        synchronized (log) {
            loadLog();
            log.addLast(line.length() > 400 ? line.substring(0, 400) : line);
            while (log.size() > LOG_KEEP) log.removeFirst();
            saveLog();
        }
        return uri;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        // Clearing diagnostic logs is an app-only operation.
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("Only HyperSpring can clear its diagnostic log");
        }
        if (uri == null || !"log".equals(uri.getLastPathSegment())) return 0;
        synchronized (log) {
            int n = log.size();
            log.clear();
            logLoaded = true;
            saveLog();
            return n;
        }
    }

    private void loadLog() {
        if (logLoaded) return;
        logLoaded = true;
        java.io.File f = new java.io.File(getContext().getFilesDir(), LOG_FILE);
        if (!f.exists()) return;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            for (String l; (l = r.readLine()) != null; ) log.addLast(l);
        } catch (java.io.IOException ignored) {
        }
        while (log.size() > LOG_KEEP) log.removeFirst();
    }

    private void saveLog() {
        java.io.File f = new java.io.File(getContext().getFilesDir(), LOG_FILE);
        try (java.io.FileWriter w = new java.io.FileWriter(f)) {
            for (String l : log) w.write(l + "\n");
        } catch (java.io.IOException ignored) {
        }
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
