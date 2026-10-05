package com.nearchuckle.farcry;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.Choreographer;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.libsdl.app.SDLActivity;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps a hardware gamepad to the same key / mouse injections the on-screen controls use
 * (SDLActivity.onNativeKeyDown / onNativeKeyUp / onNativeMouse). Far Cry (CryEngine 1) has no
 * native gamepad support on non-Windows builds, so the pad is translated into keys + mouse look +
 * mouse buttons here, in Java. The engine and the SDL layer are not modified for it.
 *
 * What each button does is {@link GamepadBindings} (defaults and the player's choices):
 * defaults are A jump, B crouch, X reload, Y use, L1 sprint, triggers aim and fire, sticks click prone
 * and grenade, Start Esc, Select Tab. The D-Pad is the arrow keys unless it is given an action.
 * The left stick moves (W A S D), the right stick looks (relative mouse), with the sensitivity, dead
 * zone and inversion of {@link GamepadSettingsActivity}.
 *
 * In a menu (the engine says so, see {@link #menuUp()}): A is Enter, B goes back one page, up and down (D-Pad and
 * left stick) are Shift+Tab and Tab, which move the menus' focus, left and right are the arrows; the
 * right stick still moves the cursor and the triggers click.
 *
 * The mapper only reacts to events whose source is a gamepad / joystick / D-Pad, so hardware
 * keyboards and mice are completely unaffected.
 */
public final class GamepadMapper {

    private static final String TAG = "NearChuckle-Gamepad";

    private static final float TRIGGER_PRESS = 0.35f;
    private static final float TRIGGER_RELEASE = 0.20f;
    /** Mouse pixels per 1/60 s at full deflection and sensitivity 1. */
    private static final float LOOK_GAIN = 25f;

    private final Context context;
    private Map<Integer, GamepadBindings.Action> bindings;
    private float deadzone;
    private float lookSensitivity;
    private boolean invertY;

    // Current injected state, so keys are never sent twice or left pressed.
    private final Set<Integer> heldKeys = new HashSet<>();
    private boolean moveUp, moveDown, moveLeft, moveRight;
    private boolean triggerL, triggerR;
    private float lookRemainderX, lookRemainderY;
    /** Where the right stick is (after the dead zone and curve), turned into mouse motion each frame. */
    private float lookX, lookY;
    private boolean lookTicking;
    private long lookLastNanos;

    /**
     * The camera turns at every frame of the display while the right stick is pushed: Android sends
     * a stick event only when its position changes, so turning on the events alone stopped the
     * camera whenever the stick was held still (the jerky look).
     */
    private final Choreographer.FrameCallback lookTick = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (lookX == 0f && lookY == 0f) {
                lookTicking = false;
                lookRemainderX = lookRemainderY = 0f;
                return;
            }
            float frames = lookLastNanos == 0 ? 1f : (frameTimeNanos - lookLastNanos) / 16_666_667f;
            frames = Math.max(0f, Math.min(frames, 4f));
            lookLastNanos = frameTimeNanos;
            float gain = LOOK_GAIN * lookSensitivity * frames;
            lookRemainderX += lookX * gain;
            lookRemainderY += lookY * gain;
            int dx = (int) lookRemainderX;
            int dy = (int) lookRemainderY;
            lookRemainderX -= dx;
            lookRemainderY -= dy;
            if (dx != 0 || dy != 0) {
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, dx, dy, true);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /** What each pad button did when it went down, so it is undone the same way on release. */
    private final Map<Integer, Integer> pressedAs = new HashMap<>();

    public GamepadMapper(Context context) {
        this.context = context;
        reloadSettings();
    }

    /** Reads the gamepad settings again (they can be changed while the game is paused). */
    public void reloadSettings() {
        this.bindings = GamepadBindings.lookup(context);
        this.deadzone = GamepadBindings.deadzone(context);
        this.lookSensitivity = GamepadBindings.lookSensitivity(context);
        this.invertY = GamepadBindings.invertY(context);
    }

    // -----------------------------------------------------------------------------------------
    // Menu or game: the engine writes ".gamepad_menu_state" ("1" in a menu) in the game folder
    // -----------------------------------------------------------------------------------------

    private static final long MENU_POLL_MS = 150;
    private static final int ACTION_CLICK = GamepadBindings.TARGET_MOUSE_LEFT;
    private long lastMenuPoll;
    private boolean menuState;

    private boolean menuUp() {
        long now = SystemClock.uptimeMillis();
        if (now - lastMenuPoll >= MENU_POLL_MS) {
            lastMenuPoll = now;
            boolean was = menuState;
            menuState = readMenuState();
            if (menuState != was) {
                Log.i(TAG, "menu " + (menuState ? "up" : "closed"));
                releaseAll();   // nothing pressed in one mode may stay held in the other
            }
        }
        return menuState;
    }

    private boolean readMenuState() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(LauncherActivity.PREFS_NAME, Context.MODE_PRIVATE);
            File state = new File(prefs.getString(LauncherActivity.KEY_GAME_PATH, ""), ".gamepad_menu_state");
            if (!state.isFile()) return false;
            try (FileInputStream in = new FileInputStream(state)) {
                return in.read() == '1';
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Asks the engine to move in a menu: one character appended to ".gamepad_menu_cmd" in the game folder. */
    private void sendMenuCommand(char command) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(LauncherActivity.PREFS_NAME, Context.MODE_PRIVATE);
            try (FileOutputStream out = new FileOutputStream(new File(prefs.getString(LauncherActivity.KEY_GAME_PATH, ""), ".gamepad_menu_cmd"), true)) {
                out.write(command);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not send a command to the menu", e);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Buttons and D-Pad (key events)
    // -----------------------------------------------------------------------------------------

    /** @return true when the event came from a gamepad and was consumed. */
    public boolean handleKeyEvent(KeyEvent event) {
        if (!isGamepadSource(event.getSource())) {
            return false;
        }
        // The system's keys are not the game's: on a handheld the volume buttons arrive as keys of
        // the built-in pad, and swallowing them left the volume impossible to change in the game.
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_BRIGHTNESS_DOWN:
            case KeyEvent.KEYCODE_BRIGHTNESS_UP:
                return false;
            default:
                break;
        }
        final int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return true; // long-press etc. - swallow for pads
        }
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() != 0) {
            return true;
        }
        padButton(event.getKeyCode(), action == KeyEvent.ACTION_DOWN);
        return true;
    }

    private static final long LONG_PRESS_MS = 600;
    private Runnable onMenuRequest;
    private boolean selectHeld;
    private boolean selectLong;
    private final Runnable selectLongPress = new Runnable() {
        @Override
        public void run() {
            if (selectHeld && !selectLong) {
                selectLong = true;
                if (onMenuRequest != null) {
                    onMenuRequest.run();
                }
            }
        }
    };

    /** What a long press of Select (Back) does: the game menu (save, load, settings, quit), for a pad alone. */
    public void setOnMenuRequest(Runnable request) {
        this.onMenuRequest = request;
    }

    /**
     * Select / Back: a short press is what it was (objectives, Tab), a long one opens the game menu,
     * which the touch screen has as a gear button, so that a pad alone reaches it.
     */
    private void selectButton(int code, boolean down) {
        if (down) {
            if (selectHeld) {
                return;
            }
            selectHeld = true;
            selectLong = false;
            handler.postDelayed(selectLongPress, LONG_PRESS_MS);
        } else {
            handler.removeCallbacks(selectLongPress);
            boolean wasLong = selectLong;
            selectHeld = false;
            selectLong = false;
            if (!wasLong) {
                // the short press: pressed and released now, a moment apart
                final int target = resolve(code);
                if (target != 0) {
                    apply(target, true);
                    handler.postDelayed(() -> apply(target, false), 80);
                }
            }
        }
    }

    /** A pad button (a real one, or a trigger read as one) goes down or up. */
    private void padButton(int code, boolean down) {
        if (onMenuRequest != null && (code == KeyEvent.KEYCODE_BUTTON_SELECT || code == KeyEvent.KEYCODE_BACK)) {
            selectButton(code, down);
            return;
        }
        if (down) {
            if (pressedAs.containsKey(code)) {
                return;
            }
            int target = resolve(code);
            if (target == 0) {
                // Unknown or unassigned pad button: do not let it leak into the game as a random key.
                return;
            }
            pressedAs.put(code, target);
            apply(target, true);
        } else {
            Integer target = pressedAs.remove(code);
            if (target != null) {
                apply(target, false);
            }
        }
    }

    /** What a button does now: a key code, a TARGET_MOUSE_* value, or 0 for nothing. */
    private int resolve(int code) {
        if (menuUp()) {
            // The menus move their focus with Tab / Shift+Tab, press the focused button with Enter
            // and take the arrows for lists and choices.
            switch (code) {
                case KeyEvent.KEYCODE_BUTTON_A:   return KeyEvent.KEYCODE_ENTER;           // choose
                case KeyEvent.KEYCODE_BUTTON_B:   return MENU_BACK;                        // back
                case KeyEvent.KEYCODE_DPAD_UP:    return MENU_PREV;
                case KeyEvent.KEYCODE_DPAD_DOWN:  return MENU_NEXT;
                case KeyEvent.KEYCODE_DPAD_LEFT:  return KeyEvent.KEYCODE_DPAD_LEFT;
                case KeyEvent.KEYCODE_DPAD_RIGHT: return KeyEvent.KEYCODE_DPAD_RIGHT;
                default: break;
            }
        }
        GamepadBindings.Action bound = bindings.get(code);
        if (bound != null) {
            return bound.target;
        }
        switch (code) {   // the D-Pad: the arrow keys, unless it was given an action
            case KeyEvent.KEYCODE_DPAD_UP:    return KeyEvent.KEYCODE_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:  return KeyEvent.KEYCODE_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:  return KeyEvent.KEYCODE_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return KeyEvent.KEYCODE_DPAD_RIGHT;
            default:                          return 0;
        }
    }

    private void apply(int target, boolean down) {
        if (target == MENU_BACK || target == MENU_PREV || target == MENU_NEXT) {
            if (down) {
                sendMenuCommand(target == MENU_BACK ? 'b' : target == MENU_PREV ? 'p' : 'n');
            }
            return;
        }
        if (target == GamepadBindings.TARGET_MOUSE_LEFT) {
            if (down) pressMouse(MotionEvent.BUTTON_PRIMARY); else releaseMouse(MotionEvent.BUTTON_PRIMARY);
        } else if (target == GamepadBindings.TARGET_MOUSE_RIGHT) {
            if (down) pressMouse(MotionEvent.BUTTON_SECONDARY); else releaseMouse(MotionEvent.BUTTON_SECONDARY);
        } else if (down) {
            pressKey(target);
        } else {
            releaseKey(target);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Sticks and triggers (motion events)
    // -----------------------------------------------------------------------------------------

    /** @return true when the event came from a joystick-class device and was consumed. */
    public boolean handleMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) {
            return false;
        }

        // Left stick: W A S D in the game, the arrow keys in a menu
        float lx = event.getAxisValue(MotionEvent.AXIS_X);
        float ly = event.getAxisValue(MotionEvent.AXIS_Y);
        boolean inMenu = menuUp();
        int upKey = inMenu ? MENU_PREV : KeyEvent.KEYCODE_W;
        int downKey = inMenu ? MENU_NEXT : KeyEvent.KEYCODE_S;
        int leftKey = inMenu ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_A;
        int rightKey = inMenu ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_D;
        float move = Math.max(deadzone, 0.25f);
        moveUp = stickKey(upKey, ly < -move, moveUp);
        moveDown = stickKey(downKey, ly > move, moveDown);
        moveLeft = stickKey(leftKey, lx < -move, moveLeft);
        moveRight = stickKey(rightKey, lx > move, moveRight);

        // Right stick: relative mouse look. Past the dead zone the deflection is rescaled to 0..1
        // and squared, so small pushes turn slowly (fine aim) and a full push turns fast.
        float rx = curve(event.getAxisValue(MotionEvent.AXIS_Z));
        float ry = curve(event.getAxisValue(MotionEvent.AXIS_RZ));
        if (invertY) {
            ry = -ry;
        }
        lookX = rx;
        lookY = ry;
        if ((rx != 0f || ry != 0f) && !lookTicking) {
            lookTicking = true;
            lookLastNanos = 0;
            Choreographer.getInstance().postFrameCallback(lookTick);
        }

        // Triggers read as axes: the same as the trigger buttons
        float rt = triggerValue(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS);
        float lt = triggerValue(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE);
        boolean rNow = rt > (triggerR ? TRIGGER_RELEASE : TRIGGER_PRESS);
        boolean lNow = lt > (triggerL ? TRIGGER_RELEASE : TRIGGER_PRESS);
        if (rNow != triggerR) {
            triggerR = rNow;
            padButton(KeyEvent.KEYCODE_BUTTON_R2, rNow);
        }
        if (lNow != triggerL) {
            triggerL = lNow;
            padButton(KeyEvent.KEYCODE_BUTTON_L2, lNow);
        }

        return true;
    }

    private float curve(float value) {
        float magnitude = Math.abs(value);
        if (magnitude <= deadzone) {
            return 0f;
        }
        float scaled = Math.min(1f, (magnitude - deadzone) / (1f - deadzone));
        return Math.signum(value) * scaled * scaled;
    }

    private static float triggerValue(MotionEvent event, int axis, int fallbackAxis) {
        float v = event.getAxisValue(axis);
        if (v <= 0f && fallbackAxis != 0) {
            v = event.getAxisValue(fallbackAxis);
        }
        return v;
    }

    private boolean stickKey(int keyCode, boolean want, boolean current) {
        if (want && !current) {
            if (keyCode < 0) {
                apply(keyCode, true);   // a menu command: sent once per push
            } else {
                pressKey(keyCode);
            }
        } else if (!want && current && keyCode >= 0) {
            releaseKey(keyCode);
        }
        return want;
    }

    // -----------------------------------------------------------------------------------------
    // Injected state helpers
    // -----------------------------------------------------------------------------------------

    /** The menus' "previous": Shift+Tab, pressed and released as one key. */
    private static final int KEY_SHIFT_TAB = -3;
    /** B in a menu: asks the engine to go back one page (see CXGame::Update, ".gamepad_menu_back"). */
    private static final int MENU_BACK = -4;
    /** Up and down in a menu: the engine moves the menus' focus (see CXGame::Update). */
    private static final int MENU_PREV = -5;
    private static final int MENU_NEXT = -6;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long SHIFT_TAB_GAP_MS = 60;

    private void pressKey(int keyCode) {
        if (heldKeys.add(keyCode)) {
            if (keyCode == KEY_SHIFT_TAB) {
                // Shift first, Tab a moment later: the menus read the Shift state when Tab arrives
                SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT);
                handler.postDelayed(() -> SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_TAB), SHIFT_TAB_GAP_MS);
            } else {
                SDLActivity.onNativeKeyDown(keyCode);
            }
        }
    }

    private void releaseKey(int keyCode) {
        if (heldKeys.remove(keyCode)) {
            if (keyCode == KEY_SHIFT_TAB) {
                handler.postDelayed(() -> SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_TAB), SHIFT_TAB_GAP_MS + 10);
                handler.postDelayed(() -> SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT), SHIFT_TAB_GAP_MS + 50);
            } else {
                SDLActivity.onNativeKeyUp(keyCode);
            }
        }
    }

    // Mouse button injection. SDL takes the state of all the mouse buttons held: a button going down
    // sends the new state, a button going up sends what is still held (0 when none): sending the
    // released button itself, as this did, left it pressed for SDL.
    private int mouseState;

    private void pressMouse(int button) {
        if ((mouseState & button) == 0) {
            mouseState |= button;
            SDLActivity.onNativeMouse(mouseState, MotionEvent.ACTION_DOWN, 0, 0, false);
        }
    }

    private void releaseMouse(int button) {
        if ((mouseState & button) != 0) {
            mouseState &= ~button;
            SDLActivity.onNativeMouse(mouseState, MotionEvent.ACTION_UP, 0, 0, false);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Safety
    // -----------------------------------------------------------------------------------------

    /** Releases everything (pause, focus loss, pad unplug, menu change) - no key may stay stuck. */
    public void releaseAll() {
        for (int keyCode : new HashSet<>(heldKeys)) {
            releaseKey(keyCode);
        }
        moveUp = moveDown = moveLeft = moveRight = false;
        triggerL = triggerR = false;
        lookX = lookY = 0f;
        pressedAs.clear();
        releaseMouse(MotionEvent.BUTTON_PRIMARY);
        releaseMouse(MotionEvent.BUTTON_SECONDARY);
    }

    public static boolean isGamepadSource(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) != 0
                || (source & InputDevice.SOURCE_JOYSTICK) != 0
                || (source & InputDevice.SOURCE_DPAD) != 0;
    }

    /** The launcher's mouse sensitivity also scales the camera stick, as before. */
    private float mouseSensitivity() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(LauncherActivity.PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getFloat(LauncherActivity.KEY_MOUSE_SENSITIVITY, 1.0f);
        } catch (Exception e) {
            return 1.0f;
        }
    }
}
