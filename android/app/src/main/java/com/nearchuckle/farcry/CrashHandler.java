package com.nearchuckle.farcry;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.nearchuckle.farcry.driver.DriverHook;
import com.nearchuckle.farcry.driver.DriverInfo;
import com.nearchuckle.farcry.driver.TurnipDriverManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Robust crash reporter and diagnostics collector for Far Cry Android.
 * Catches unhandled Java exceptions, formats native engine logs and logcat,
 * saves to local storage, and presents CrashReportActivity.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "NearChuckle-CrashHandler";
    private static final String CRASH_FILE_NAME = "last_crash.txt";
    private static final String UNREAD_MARKER = "unread_crash";
    private static final String PREF_CRASH = "farcry_crash_info";
    private static final String KEY_HAS_UNREAD_CRASH = "has_unread_crash";
    private static final String KEY_CRASH_TIMESTAMP = "crash_timestamp";

    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    private CrashHandler(Context context) {
        this.context = context.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void init(Context context) {
        if (!(Thread.getDefaultUncaughtExceptionHandler() instanceof CrashHandler)) {
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context));
            Log.i(TAG, "CrashHandler installed as default uncaught exception handler.");
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            Log.e(TAG, "FATAL CRASH DETECTED in thread: " + thread.getName(), ex);
            String report = buildCrashReport(context, "Java Uncaught Exception (" + thread.getName() + ")", ex);
            saveCrashReport(context, report);
            launchCrashActivity(context, report);
        } catch (Throwable t) {
            Log.e(TAG, "Error in crash handler itself", t);
        } finally {
            if (defaultHandler != null && defaultHandler != this) {
                defaultHandler.uncaughtException(thread, ex);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        }
    }

    /**
     * Records a native engine crash or critical error.
     */
    public static void recordNativeCrash(Context context, String errorTitle, String extraDetails) {
        try {
            String report = buildCrashReport(context, errorTitle, extraDetails);
            saveCrashReport(context, report);
            launchCrashActivity(context, report);
        } catch (Throwable t) {
            Log.e(TAG, "Error recording native crash", t);
        }
    }

    /**
     * Builds a comprehensive diagnostics report at any time (e.g. for user logs export).
     */
    public static String generateDiagnosticsReport(Context context) {
        return buildCrashReport(context, "User Requested Diagnostics & Engine Logs", (String) null);
    }

    private static String buildCrashReport(Context context, String crashReason, Throwable ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        if (ex != null) {
            ex.printStackTrace(pw);
        }
        return buildCrashReport(context, crashReason, sw.toString());
    }

    public static String buildCrashReport(Context context, String crashReason, String details) {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        String timestamp = sdf.format(new Date());

        sb.append("================================================================\n");
        sb.append("  FAR CRY ANDROID (NearChuckle) - CRASH & DIAGNOSTICS REPORT\n");
        sb.append("================================================================\n");
        sb.append("Date & Time: ").append(timestamp).append("\n");
        sb.append("Reason:      ").append(crashReason).append("\n");
        sb.append("\n");

        // 1. Device and OS
        sb.append("----------------------------------------------------------------\n");
        sb.append("1. DEVICE & SYSTEM INFO\n");
        sb.append("----------------------------------------------------------------\n");
        sb.append("Manufacturer:  ").append(Build.MANUFACTURER).append("\n");
        sb.append("Model:         ").append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android OS:    ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Supported ABIs:").append(String.join(", ", Build.SUPPORTED_ABIS)).append("\n");
        sb.append("Board / Chip:  ").append(Build.BOARD).append(" / ").append(Build.HARDWARE).append("\n");
        sb.append("Adreno KGSL:   ").append(DriverHook.isQualcommAdreno() ? "Yes (/dev/kgsl-3d0)" : "No (Non-Adreno)").append("\n");
        sb.append("\n");

        // 2. Launcher and Game Settings
        sb.append("----------------------------------------------------------------\n");
        sb.append("2. LAUNCHER CONFIGURATION\n");
        sb.append("----------------------------------------------------------------\n");
        SharedPreferences prefs = context.getSharedPreferences(LauncherActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String gamePath = prefs.getString(LauncherActivity.KEY_GAME_PATH, "(not set)");
        boolean useZink = prefs.getBoolean(LauncherActivity.KEY_USE_ZINK, true);
        boolean devMode = prefs.getBoolean(LauncherActivity.KEY_DEVMODE, false);
        int fov = prefs.getInt(LauncherActivity.KEY_FOV, 90);
        int resMode = prefs.getInt(LauncherActivity.KEY_RES_MODE, 0);
        String customArgs = prefs.getString(LauncherActivity.KEY_CUSTOM_ARGS, "");
        DriverInfo selectedDriver = TurnipDriverManager.getSelectedDriver(context);
        boolean turbo = TurnipDriverManager.isTurboEnabled(context);

        sb.append("Game Directory: ").append(gamePath).append("\n");
        sb.append("Mesa Zink:      ").append(useZink ? "Enabled (OpenGL over Vulkan)" : "Disabled").append("\n");
        sb.append("GPU Driver:     ").append(selectedDriver != null ? selectedDriver.getName() : "System Default");
        sb.append(" (turbo=").append(turbo).append(")\n");
        sb.append("Resolution:     ").append(getResolutionDesc(resMode)).append("\n");
        sb.append("Field of View:  ").append(fov).append("°\n");
        sb.append("DevMode:        ").append(devMode).append("\n");
        sb.append("Custom Args:    ").append(customArgs.isEmpty() ? "(none)" : customArgs).append("\n");
        sb.append("Native Lib Dir: ").append(context.getApplicationInfo().nativeLibraryDir).append("\n");
        sb.append("\n");

        // 3. Exception / Stacktrace (if available)
        if (details != null && !details.trim().isEmpty()) {
            sb.append("----------------------------------------------------------------\n");
            sb.append("3. EXCEPTION / CRASH STACK TRACE\n");
            sb.append("----------------------------------------------------------------\n");
            sb.append(details.trim()).append("\n\n");
        }

        // 4. Far Cry Engine Log (log.txt)
        sb.append("----------------------------------------------------------------\n");
        sb.append("4. CRYENGINE LOG (log.txt)\n");
        sb.append("----------------------------------------------------------------\n");
        String engineLog = readEngineLogTail(context, gamePath, 150);
        if (engineLog.isEmpty()) {
            sb.append("(log.txt was not found or is currently empty)\n\n");
        } else {
            sb.append(engineLog).append("\n\n");
        }

        // 5. System Logcat
        sb.append("----------------------------------------------------------------\n");
        sb.append("5. RECENT LOGCAT (Far Cry / SDL / Engine)\n");
        sb.append("----------------------------------------------------------------\n");
        String logcat = readLogcatTail(200);
        if (logcat.isEmpty()) {
            sb.append("(Logcat could not be captured)\n");
        } else {
            sb.append(logcat).append("\n");
        }

        sb.append("================================================================\n");
        sb.append("END OF REPORT\n");
        sb.append("================================================================\n");

        return sb.toString();
    }

    private static String getResolutionDesc(int mode) {
        switch (mode) {
            case 1: return "1920x1080 (FHD)";
            case 2: return "1280x720 (HD)";
            case 3: return "960x540 (qHD)";
            default: return "Auto (Native Display)";
        }
    }

    private static String readEngineLogTail(Context context, String gamePath, int maxLines) {
        List<File> candidates = new ArrayList<>();
        if (gamePath != null && !gamePath.isEmpty()) {
            candidates.add(new File(gamePath, "log.txt"));
            candidates.add(new File(gamePath, "Log.txt"));
            candidates.add(new File(new File(gamePath, "FCData"), "log.txt"));
        }
        candidates.add(new File(context.getFilesDir(), "log.txt"));
        candidates.add(new File(context.getExternalFilesDir(null), "log.txt"));

        for (File f : candidates) {
            if (f.exists() && f.canRead()) {
                return readTailFromFile(f, maxLines);
            }
        }
        return "";
    }

    private static String readTailFromFile(File file, int maxLines) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
                if (lines.size() > maxLines) {
                    lines.remove(0);
                }
            }
        } catch (Exception e) {
            return "(Failed reading file: " + e.getMessage() + ")";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[From: ").append(file.getAbsolutePath()).append("]\n");
        for (String l : lines) {
            sb.append(l).append("\n");
        }
        return sb.toString().trim();
    }

    private static String readLogcatTail(int maxLines) {
        List<String> lines = new ArrayList<>();
        String[] cmdOptions = new String[]{
                "/system/bin/logcat -d -t " + maxLines,
                "logcat -d -t " + maxLines,
                "/system/bin/logcat -d -v time -t " + maxLines
        };

        for (String cmd : cmdOptions) {
            try {
                java.lang.Process process = Runtime.getRuntime().exec(cmd.split("\\s+"));
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                }
                process.waitFor();
                if (!lines.isEmpty()) {
                    break;
                }
            } catch (Throwable ignored) {
            }
        }

        if (lines.isEmpty()) {
            return "(Logcat access is restricted by Android system security policy on this device)";
        }

        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append("\n");
        }
        return sb.toString().trim();
    }

    public static void saveCrashReport(Context context, String report) {
        try {
            File crashFile = new File(context.getFilesDir(), CRASH_FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(crashFile)) {
                fos.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            // A marker file too: the game and the launcher are different processes, which do not see
            // each other's SharedPreferences changes while they run.
            new File(context.getFilesDir(), UNREAD_MARKER).createNewFile();
            context.getSharedPreferences(PREF_CRASH, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_HAS_UNREAD_CRASH, true)
                    .putLong(KEY_CRASH_TIMESTAMP, System.currentTimeMillis())
                    .apply();
            Log.i(TAG, "Crash report successfully saved to: " + crashFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save crash report to storage", e);
        }
    }

    public static String getLastCrashReport(Context context) {
        File crashFile = new File(context.getFilesDir(), CRASH_FILE_NAME);
        if (crashFile.exists() && crashFile.canRead()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(crashFile)))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString().trim();
            } catch (Exception e) {
                Log.e(TAG, "Failed reading last crash report", e);
            }
        }
        return "";
    }

    public static boolean hasUnreadCrash(Context context) {
        return new File(context.getFilesDir(), UNREAD_MARKER).exists();
    }

    public static void markCrashAsRead(Context context) {
        new File(context.getFilesDir(), UNREAD_MARKER).delete();
        context.getSharedPreferences(PREF_CRASH, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_HAS_UNREAD_CRASH, false)
                .apply();
    }

    public static void clearCrashReport(Context context) {
        new File(context.getFilesDir(), UNREAD_MARKER).delete();
        File crashFile = new File(context.getFilesDir(), CRASH_FILE_NAME);
        if (crashFile.exists()) {
            crashFile.delete();
        }
        context.getSharedPreferences(PREF_CRASH, Context.MODE_PRIVATE).edit()
                .clear()
                .apply();
    }

    public static void launchCrashActivity(Context context, String report) {
        Intent intent = new Intent(context, CrashReportActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(CrashReportActivity.EXTRA_CRASH_REPORT, report);
        context.startActivity(intent);
    }
}
