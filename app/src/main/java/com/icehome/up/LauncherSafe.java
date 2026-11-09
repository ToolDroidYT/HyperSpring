package com.icehome.up;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Launcher recovery guard.
 *
 * Intentional restarts are marked by the settings app and consumed through PrefsProvider.
 * Automatic safe mode is based on Android's recorded CRASH/CRASH_NATIVE/ANR/
 * INITIALIZATION_FAILURE exits, never on process-start frequency alone.
 */
final class LauncherSafe {

    private static final String FILE = "hypertweak_safe.properties";
    private static final int CRASH_LIMIT = 3;
    private static final int EXIT_HISTORY_LIMIT = 32;
    private static final long CRASH_WINDOW_MS = 120_000L;
    private static final long INTENTIONAL_TTL_MS = 10 * 60_000L;
    private static final long STABLE_MS = 30_000L;

    private static File file;

    /**
     * Called after Application.attach, when a Context is available but before launcher hooks install.
     * Returns false only when safe mode is already active or a real crash loop is detected.
     */
    static synchronized boolean checkAtLoad(Context context, String dataDir) {
        if (context == null) {
            Log.w("launcher recovery: no Application context; safe-mode check skipped");
            return true;
        }

        String dir = dataDir;
        if (dir == null || dir.isEmpty()) {
            dir = context.getApplicationInfo() != null ? context.getApplicationInfo().dataDir : null;
        }
        if (dir == null || dir.isEmpty()) {
            Log.w("launcher recovery: data directory unavailable; safe-mode check skipped");
            publish(context, false, "unavailable", "Could not access launcher data directory");
            return true;
        }

        file = new File(dir, FILE);
        Properties p = read();
        long now = System.currentTimeMillis();

        long intentionalAt = consumeIntentionalRestart(context, now);
        if (intentionalAt > 0L) {
            p.setProperty("intentional_at", String.valueOf(intentionalAt));
            p.setProperty("stable_at", String.valueOf(intentionalAt));
            p.setProperty("last_crash_count", "0");
            p.setProperty("last_crash_summary", "Skipped after intentional restart");
            write(p);
            Log.i("launcher recovery: intentional restart recognized; it will not count as a crash");
        }

        if ("1".equals(p.getProperty("safe"))) {
            String reason = value(p, "safe_reason", "legacy-safe-mode");
            String details = value(p, "safe_details",
                    "Safe mode was enabled by an earlier HyperSpring version");
            Log.w("launcher safe mode active: reason=" + reason + "; " + details
                    + ". Use Home → Reactivate, then restart the launcher.");
            publish(context, true, reason, details);
            return false;
        }

        long baseline = Math.max(parse(p.getProperty("stable_at")),
                parse(p.getProperty("intentional_at")));
        long cutoff = Math.max(now - CRASH_WINDOW_MS, baseline);
        CrashReport report = recentCrashes(context, cutoff, now);

        p.setProperty("last_check", String.valueOf(now));
        p.setProperty("last_crash_count", String.valueOf(report.count));
        p.setProperty("last_crash_summary", report.summary);
        write(p);

        if (report.count >= CRASH_LIMIT) {
            String details = report.count + " crash/ANR exits within "
                    + (CRASH_WINDOW_MS / 1000L) + " seconds. " + report.summary;
            p.setProperty("safe", "1");
            p.setProperty("safe_at", String.valueOf(now));
            p.setProperty("safe_reason", "crash-loop");
            p.setProperty("safe_details", details);
            write(p);
            Log.w("launcher safe mode activated: " + details);
            publish(context, true, "crash-loop", details);
            return false;
        }

        Log.i("launcher recovery: " + report.count + " crash/ANR exit(s) in the last "
                + (CRASH_WINDOW_MS / 1000L) + " seconds; threshold=" + CRASH_LIMIT
                + "; intentional restarts are excluded");
        publish(context, false, "none", report.count + " crash/ANR exit(s) in the detection window");
        return true;
    }

    /** Called from the launcher Application.onCreate path after the recovery decision. */
    static void started(final Context context) {
        publish(context, "1".equals(read().getProperty("safe")),
                value(read(), "safe_reason", "none"),
                value(read(), "safe_details", "No active safe-mode diagnosis"));

        if ("1".equals(read().getProperty("safe"))) return;

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (LauncherSafe.class) {
                    Properties p = read();
                    long now = System.currentTimeMillis();
                    p.setProperty("stable_at", String.valueOf(now));
                    p.setProperty("last_crash_count", "0");
                    p.setProperty("last_crash_summary", "Launcher remained alive for "
                            + (STABLE_MS / 1000L) + " seconds");
                    write(p);
                }
                Log.i("launcher recovery: startup stable for " + (STABLE_MS / 1000L)
                        + " seconds; previous crash history cleared");
            }
        }, STABLE_MS);
    }

    /** Clear safe mode from the launcher's own process. */
    static synchronized void reset(Context context) {
        // RESET_SAFE is also delivered to SystemUI, so derive the launcher file from the
        // caller's data directory instead of relying on a previous checkAtLoad() in this process.
        if (context == null) {
            Log.w("launcher recovery: reset request had no context");
            return;
        }
        try {
            String dir = context.getApplicationInfo() != null
                    ? context.getApplicationInfo().dataDir : null;
            if (dir == null || dir.isEmpty()) {
                Log.w("launcher recovery: cannot resolve launcher data directory for reset");
                publish(context, true, "reset-failed", "Launcher data directory unavailable");
                return;
            }
            file = new File(dir, FILE);
        } catch (Throwable t) {
            Log.w("launcher recovery: cannot resolve launcher safe-mode file: " + t);
            publish(context, true, "reset-failed", String.valueOf(t));
            return;
        }

        Properties p = read();
        p.setProperty("safe", "0");
        p.setProperty("stable_at", String.valueOf(System.currentTimeMillis()));
        p.setProperty("last_crash_count", "0");
        p.setProperty("last_crash_summary", "Manually reactivated");
        p.remove("safe_reason");
        p.remove("safe_details");
        p.remove("safe_at");
        if (!write(p)) {
            Log.w("launcher recovery: failed to persist manual safe-mode reset");
            publish(context, true, "reset-failed", "Could not write launcher recovery state");
            return;
        }
        Log.i("launcher safe mode manually cleared; restart POCO Launcher to reload its features");
        publish(context, false, "manually-reset", "Safe mode was manually cleared");
    }

    private static long consumeIntentionalRestart(Context context, long now) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    PrefsProvider.STATE, new String[]{"key", "value"}, null, null, null);
            if (cursor == null) return 0L;

            int keyColumn = cursor.getColumnIndex("key");
            int valueColumn = cursor.getColumnIndex("value");
            while (cursor.moveToNext()) {
                String key = keyColumn >= 0 ? cursor.getString(keyColumn) : null;
                if (!"launcher_restart_requested".equals(key)) continue;

                String marker = valueColumn >= 0 ? cursor.getString(valueColumn) : null;
                if (marker == null || marker.trim().isEmpty() || "0".equals(marker.trim())) continue;
                String[] parts = marker.split("\\|", 2);
                long requestedAt = parts.length > 0 ? parse(parts[0]) : 0L;

                // Consume even stale markers so a later unrelated crash is not hidden.
                ContentValues clear = new ContentValues();
                clear.put("key", "launcher_restart_requested");
                clear.put("value", "0");
                context.getContentResolver().insert(PrefsProvider.STATE, clear);

                if (requestedAt > 0L && now >= requestedAt - 5_000L
                        && now - requestedAt <= INTENTIONAL_TTL_MS) {
                    String source = parts.length > 1 ? parts[1] : "unknown";
                    Log.i("launcher recovery: consumed intentional restart marker from "
                            + source + " at " + time(requestedAt));
                    return requestedAt;
                }
                Log.w("launcher recovery: discarded expired or invalid intentional restart marker");
                return 0L;
            }
        } catch (Throwable t) {
            Log.w("launcher recovery: could not read intentional restart marker: " + t);
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0L;
    }

    private static CrashReport recentCrashes(Context context, long cutoff, long now) {
        CrashReport report = new CrashReport();
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) throw new IllegalStateException("ActivityManager unavailable");

            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, EXIT_HISTORY_LIMIT);
            if (exits == null) return report;

            for (ApplicationExitInfo exit : exits) {
                if (exit == null) continue;
                long timestamp = exit.getTimestamp();
                if (timestamp < cutoff || timestamp > now + 5_000L) continue;

                String processName = exit.getProcessName();
                if (processName != null && !processName.equals(context.getPackageName())
                        && !processName.startsWith(context.getPackageName() + ":")) {
                    continue;
                }

                String reason = exitReason(exit.getReason());
                if (!isCrashLike(exit.getReason())) continue;

                report.count++;
                if (report.summary.length() > 0) report.summary += "; ";
                report.summary += reason + "@" + time(timestamp);
                if (exit.getDescription() != null && !exit.getDescription().trim().isEmpty()) {
                    String description = exit.getDescription().replace('\n', ' ').trim();
                    if (description.length() > 100) description = description.substring(0, 100);
                    report.summary += " (" + description + ")";
                }
            }
        } catch (Throwable t) {
            Log.w("launcher recovery: exit-history query failed: " + t);
        }
        return report;
    }

    private static boolean isCrashLike(int reason) {
        return reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                || reason == ApplicationExitInfo.REASON_ANR
                || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE;
    }

    private static String exitReason(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            default: return "OTHER_" + reason;
        }
    }

    private static void publish(Context context, boolean active, String reason, String details) {
        if (context == null) return;
        try {
            ContentValues status = new ContentValues();
            status.put("key", "launcher_safe");
            status.put("value", active ? "1" : "0");
            context.getContentResolver().insert(PrefsProvider.STATE, status);

            ContentValues diagnosis = new ContentValues();
            diagnosis.put("key", "launcher_safe_reason");
            diagnosis.put("value", reason == null ? "none" : reason);
            context.getContentResolver().insert(PrefsProvider.STATE, diagnosis);

            ContentValues detail = new ContentValues();
            detail.put("key", "launcher_safe_details");
            detail.put("value", details == null ? "" : details);
            context.getContentResolver().insert(PrefsProvider.STATE, detail);
        } catch (Throwable t) {
            Log.w("launcher recovery: failed to publish safe-mode status: " + t);
        }
    }

    private static Properties read() {
        Properties p = new Properties();
        if (file == null || !file.exists()) return p;
        try (FileInputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (Throwable t) {
            Log.w("launcher recovery: could not read state file: " + t);
        }
        return p;
    }

    private static boolean write(Properties p) {
        if (file == null) return false;
        try (FileOutputStream out = new FileOutputStream(file)) {
            p.store(out, "HyperSpring launcher recovery state");
            return true;
        } catch (Throwable t) {
            Log.w("launcher recovery: could not write state file: " + t);
            return false;
        }
    }

    private static String value(Properties p, String key, String fallback) {
        String value = p.getProperty(key);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static long parse(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value.trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String time(long timestamp) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date(timestamp));
    }

    private static final class CrashReport {
        int count;
        String summary = "";
    }

    private LauncherSafe() {
    }
}
