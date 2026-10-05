package com.nearchuckle.farcry;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What each gamepad button does in the game: a list of actions (fire, jump...), the pad buttons
 * assigned to each (defaults below, changed in {@link GamepadSettingsActivity}), and the settings of
 * the sticks. Saved in the launcher's preferences, read by {@link GamepadMapper} when the game starts.
 */
public final class GamepadBindings {

    /** Targets that are not a key: the mouse buttons. */
    public static final int TARGET_MOUSE_LEFT = -1;
    public static final int TARGET_MOUSE_RIGHT = -2;

    public static final String KEY_LOOK_SENSITIVITY = "pad_look_sens";
    public static final String KEY_DEADZONE = "pad_deadzone";
    public static final String KEY_INVERT_Y = "pad_invert_y";
    public static final float DEFAULT_LOOK_SENSITIVITY = 1.0f;
    public static final float DEFAULT_DEADZONE = 0.20f;

    public static final class Action {
        public final String id;
        public final int labelRes;
        /** An Android key code (what the game receives as a key), or a TARGET_MOUSE_* value. */
        public final int target;
        public final int[] defaults;

        Action(String id, int labelRes, int target, int... defaults) {
            this.id = id;
            this.labelRes = labelRes;
            this.target = target;
            this.defaults = defaults;
        }
    }

    public static final Action[] ACTIONS = {
            new Action("fire", R.string.act_fire, TARGET_MOUSE_LEFT, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_R1),
            new Action("aim", R.string.act_aim, TARGET_MOUSE_RIGHT, KeyEvent.KEYCODE_BUTTON_L2),
            new Action("jump", R.string.act_jump, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A),
            new Action("crouch", R.string.act_crouch, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_BUTTON_B),
            new Action("reload", R.string.act_reload, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_BUTTON_X),
            new Action("use", R.string.act_use, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_BUTTON_Y),
            new Action("sprint", R.string.act_sprint, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_BUTTON_L1),
            new Action("prone", R.string.act_prone, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_BUTTON_THUMBL),
            new Action("grenade", R.string.act_grenade, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_BUTTON_THUMBR),
            new Action("menu", R.string.act_menu, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_MODE),
            new Action("objectives", R.string.act_objectives, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK),
            new Action("flashlight", R.string.act_flashlight, KeyEvent.KEYCODE_L),
            new Action("binoculars", R.string.act_binoculars, KeyEvent.KEYCODE_B),
            new Action("nightvision", R.string.act_nightvision, KeyEvent.KEYCODE_T),
            new Action("weapon_next", R.string.act_weapon_next, KeyEvent.KEYCODE_PAGE_DOWN),
            new Action("weapon_prev", R.string.act_weapon_prev, KeyEvent.KEYCODE_PAGE_UP),
            new Action("drop", R.string.act_drop, KeyEvent.KEYCODE_J),
            new Action("grenade_type", R.string.act_grenade_type, KeyEvent.KEYCODE_H),
            new Action("firemode", R.string.act_firemode, KeyEvent.KEYCODE_X),
            new Action("lean_left", R.string.act_lean_left, KeyEvent.KEYCODE_Q),
            new Action("lean_right", R.string.act_lean_right, KeyEvent.KEYCODE_E),
            new Action("walk", R.string.act_walk, KeyEvent.KEYCODE_Z),
            new Action("quicksave", R.string.act_quicksave, 135),   // F5
            new Action("quickload", R.string.act_quickload, 139),   // F9
    };

    private GamepadBindings() {}

    private static SharedPreferences prefs(Context context) {
        // The game runs in a process of its own (":game") and the settings screen in the launcher's:
        // MODE_MULTI_PROCESS reads the file again when the other process changed it.
        //noinspection deprecation
        return context.getSharedPreferences(LauncherActivity.PREFS_NAME, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    private static String prefKey(Action action) {
        return "pad_bind_" + action.id;
    }

    /** The pad buttons of an action: the saved ones, else the defaults. */
    public static int[] buttonsFor(Context context, Action action) {
        String saved = prefs(context).getString(prefKey(action), null);
        if (saved == null) {
            return action.defaults;
        }
        List<Integer> list = new ArrayList<>();
        for (String part : saved.split(",")) {
            part = part.trim();
            if (!part.isEmpty()) {
                try {
                    list.add(Integer.parseInt(part));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private static void save(SharedPreferences.Editor editor, Action action, int[] buttons) {
        StringBuilder sb = new StringBuilder();
        for (int button : buttons) {
            if (sb.length() > 0) sb.append(',');
            sb.append(button);
        }
        editor.putString(prefKey(action), sb.toString());
    }

    /** Gives {@code button} to {@code action} alone (the button leaves the other actions). */
    public static void assign(Context context, Action action, int button) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (Action other : ACTIONS) {
            int[] current = buttonsFor(context, other);
            List<Integer> kept = new ArrayList<>();
            for (int b : current) {
                if (b != button) kept.add(b);
            }
            if (other == action) {
                // a new choice replaces what the action had
                kept.clear();
                kept.add(button);
            } else if (kept.size() == current.length) {
                continue;
            }
            int[] out = new int[kept.size()];
            for (int i = 0; i < out.length; i++) out[i] = kept.get(i);
            save(editor, other, out);
        }
        editor.commit();
    }

    public static void clear(Context context, Action action) {
        SharedPreferences.Editor editor = prefs(context).edit();
        save(editor, action, new int[0]);
        editor.commit();
    }

    public static void resetButtons(Context context) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (Action action : ACTIONS) {
            editor.remove(prefKey(action));
        }
        editor.commit();
    }

    /** pad button -> action, for the mapper. */
    public static Map<Integer, Action> lookup(Context context) {
        Map<Integer, Action> map = new HashMap<>();
        for (Action action : ACTIONS) {
            for (int button : buttonsFor(context, action)) {
                map.put(button, action);
            }
        }
        return map;
    }

    public static String buttonName(int keyCode) {
        String name = KeyEvent.keyCodeToString(keyCode);
        if (name.startsWith("KEYCODE_BUTTON_")) return name.substring("KEYCODE_BUTTON_".length());
        if (name.startsWith("KEYCODE_")) return name.substring("KEYCODE_".length());
        return name;
    }

    public static String buttonsText(Context context, Action action) {
        int[] buttons = buttonsFor(context, action);
        if (buttons.length == 0) return context.getString(R.string.pad_none);
        StringBuilder sb = new StringBuilder();
        for (int button : buttons) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(buttonName(button));
        }
        return sb.toString();
    }

    /** True for a key event that comes from a gamepad (not the keyboard). */
    public static boolean isPadEvent(KeyEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    public static float lookSensitivity(Context context) {
        return prefs(context).getFloat(KEY_LOOK_SENSITIVITY, DEFAULT_LOOK_SENSITIVITY);
    }

    public static float deadzone(Context context) {
        return prefs(context).getFloat(KEY_DEADZONE, DEFAULT_DEADZONE);
    }

    public static boolean invertY(Context context) {
        return prefs(context).getBoolean(KEY_INVERT_Y, false);
    }
}
