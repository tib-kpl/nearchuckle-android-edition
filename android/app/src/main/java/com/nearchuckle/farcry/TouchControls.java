package com.nearchuckle.farcry;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;

import java.io.File;

/**
 * Whether the on-screen touch controls are shown. The launcher and the game run in different
 * processes, which do not see each other's SharedPreferences changes while they run, so the choice
 * lives in a marker file both read; the preferences only keep a copy for the next fresh start.
 */
public final class TouchControls {

    private static final String HIDDEN_MARKER = "hide_touch_controls";

    private TouchControls() {}

    public static boolean isHidden(Context context) {
        return new File(context.getFilesDir(), HIDDEN_MARKER).exists();
    }

    public static void setHidden(Context context, boolean hidden) {
        File marker = new File(context.getFilesDir(), HIDDEN_MARKER);
        try {
            if (hidden) {
                //noinspection ResultOfMethodCallIgnored
                marker.createNewFile();
            } else {
                //noinspection ResultOfMethodCallIgnored
                marker.delete();
            }
        } catch (java.io.IOException ignored) {
        }
        context.getSharedPreferences(LauncherActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(LauncherActivity.KEY_HIDE_CONTROLS, hidden)
                .commit();
    }

    /** Copies the saved choice into the marker file, once, when the launcher starts. */
    public static void syncFromPreferences(Context context) {
        boolean saved = context.getSharedPreferences(LauncherActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(LauncherActivity.KEY_HIDE_CONTROLS, false);
        if (saved != isHidden(context)) {
            setHidden(context, saved);
        }
    }

    /** True when the touch controls should hide on their own because a gamepad is connected. */
    public static boolean autoHideWithGamepad(Context context) {
        return context.getSharedPreferences(LauncherActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(LauncherActivity.KEY_AUTO_HIDE_PAD, true);
    }

    public static boolean gamepadConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) continue;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }
}
