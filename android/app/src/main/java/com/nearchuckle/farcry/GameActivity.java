package com.nearchuckle.farcry;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.RelativeLayout;

import com.nearchuckle.farcry.controls.OscManager;
import com.nearchuckle.farcry.driver.DriverHook;
import com.nearchuckle.farcry.driver.DriverInfo;
import com.nearchuckle.farcry.driver.TurnipDriverManager;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Main game execution activity extending SDLActivity.
 * Configures Mesa Zink, Turnip custom drivers, working paths, and overlays touch controls.
 */
public class GameActivity extends SDLActivity {
    private static final String TAG = "NearChuckle-GameActivity";
    private OscManager oscManager;
    private RelativeLayout oscContainer;
    /** The player chose "Settings" or "Quit the game" in the gear menu: the launcher stays. */
    private boolean backToLauncher;
    /** Hardware gamepad -> WASD / mouse look / mouse buttons (see GamepadMapper). */
    private GamepadMapper gamepadMapper;

    @Override
    protected String[] getLibraries() {
        return new String[]{
                "c++_shared",
                "SDL3",
                "FarCry"
        };
    }

    @Override
    public void loadLibraries() {
        Context context = this;
        SharedPreferences prefs = context.getSharedPreferences(LauncherActivity.PREFS_NAME, MODE_PRIVATE);

        String defaultPath = android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/FarCry";
        String gamePath = prefs.getString(LauncherActivity.KEY_GAME_PATH, defaultPath);
        boolean useZink = prefs.getBoolean(LauncherActivity.KEY_USE_ZINK, true);
        boolean turbo = TurnipDriverManager.isTurboEnabled(context);
        DriverInfo selectedDriver = TurnipDriverManager.getSelectedDriver(context);

        Log.i(TAG, "Initializing Far Cry Android Port...");
        Log.i(TAG, "Game Path: " + gamePath);
        Log.i(TAG, "Renderer Mesa Zink: " + useZink);
        Log.i(TAG, "GPU Driver: " + (selectedDriver != null ? selectedDriver.getName() : "System Default"));

        // Clean any problematic system.cfg before native engine starts
        LauncherActivity.cleanSystemConfigFiles(gamePath);

        // 1. Set Far Cry Working directory and Module search path
        String nativeLibDir = getApplicationInfo().nativeLibraryDir;
        try {
            if (!gamePath.isEmpty()) {
                Os.setenv("FARCRY_DATA_DIR", gamePath, true);
                Os.setenv("HOME", gamePath, true);
            }
            Os.setenv("MODULE_PATH", nativeLibDir + "/", true);
            Os.setenv("USER", "FarCryPlayer", true);
            Os.setenv("FC_VIDEO_FIT", String.valueOf(prefs.getInt(LauncherActivity.KEY_VIDEO_FIT, 0)), true);
            Os.setenv("LOGNAME", "FarCryPlayer", true);
            Os.setenv("TMPDIR", context.getCacheDir().getAbsolutePath(), true);
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed setting path environment variables", e);
        }

        // Game language: opens FCData/Localized/<language>.pak (CrySystem
        // SystemInit.cpp) and sets g_language (CryGame GameActionInput.cpp)
        String language = LauncherActivity.resolveGameLanguage(context, gamePath);
        try {
            if (language != null)
                Os.setenv("NC_GAME_LANGUAGE", language, true);
            else
                Os.unsetenv("NC_GAME_LANGUAGE");
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed setting the game language", e);
        }
        Log.i(TAG, "Game language: " + (language != null ? language : "english (engine default)"));

        // 2. Configure Mesa Zink (OpenGL over Vulkan)
        if (useZink) {
            try {
                Os.setenv("MESA_LOADER_DRIVER_OVERRIDE", "zink", true);
                Os.setenv("GALLIUM_DRIVER", "zink", true);
                Os.setenv("ZINK_DESCRIPTORS", "lazy", true);
                Os.setenv("MESA_GL_VERSION_OVERRIDE", "2.1COMPAT", true);
                Os.setenv("MESA_GLSL_VERSION_OVERRIDE", "140", true);
                // Allow ARB shaders for Far Cry CryEngine 1
                Os.setenv("MESA_EXTENSION_OVERRIDE", "+GL_ARB_vertex_program +GL_ARB_fragment_program", true);
                Log.i(TAG, "Configured Mesa Zink environment variables.");
            } catch (ErrnoException e) {
                Log.e(TAG, "Failed setting Zink environment variables", e);
            }
        }

        // Configure gl4es (desktop OpenGL 2.1 translation to GLES)
        try {
            Os.setenv("LIBGL_ES", "2", true);
            Os.setenv("LIBGL_GL", "21", true);
            Os.setenv("LIBGL_NPOT", "2", true);
            Os.setenv("LIBGL_MIPMAP", "1", true);
            Os.setenv("LIBGL_NOBANNER", "1", true);
            Os.setenv("LIBGL_NORMALIZE", "1", true);
            Os.setenv("LIBGL_NOTEXMAT", "0", true);
            Os.setenv("LIBGL_NODOWNSAMPLING", "1", true);
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed setting gl4es environment variables", e);
        }

        // 3. Preload libc++_shared and libGL so native dependencies are resolved
        try {
            System.loadLibrary("c++_shared");
        } catch (Throwable t) {
            Log.w(TAG, "libc++_shared pre-load: " + t.getMessage());
        }
        try {
            System.loadLibrary("GL");
            Log.i(TAG, "libGL.so (gl4es) pre-loaded successfully.");
        } catch (Throwable t) {
            Log.w(TAG, "libGL pre-load: " + t.getMessage());
        }

        // 4. Configure Turnip / Custom Vulkan driver via adrenotools
        try {
            DriverHook.apply(context, selectedDriver, turbo);
        } catch (Throwable t) {
            Log.e(TAG, "Error applying GPU driver hook", t);
        }

        // 5. Load native libraries (c++_shared, SDL3, FarCry)
        super.loadLibraries();
    }

    @Override
    protected String[] getArguments() {
        SharedPreferences prefs = getSharedPreferences(LauncherActivity.PREFS_NAME, MODE_PRIVATE);
        List<String> args = new ArrayList<>();

        // Devmode
        if (prefs.getBoolean(LauncherActivity.KEY_DEVMODE, false)) {
            args.add("-DEVMODE");
        }

        // Renderer
        args.add("\"r_Driver OpenGL\"");

        // FOV
        int fov = prefs.getInt(LauncherActivity.KEY_FOV, 90);
        args.add("\"game_fov " + fov + "\"");

        // Resolution
        int resMode = prefs.getInt(LauncherActivity.KEY_RES_MODE, 0);
        DisplayMetrics dm = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.Display display = null;
            try {
                display = getDisplay();
            } catch (Throwable ignored) {}
            if (display != null) {
                display.getRealMetrics(dm);
            } else {
                getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            }
        } else {
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        }
        int screenW = Math.max(dm.widthPixels, dm.heightPixels);
        int screenH = Math.min(dm.widthPixels, dm.heightPixels);
        if (resMode == 0) { // Native Display Resolution (true physical display panel size)
            args.add("\"r_Width " + screenW + "\"");
            args.add("\"r_Height " + screenH + "\"");
        } else if (resMode == 1) { // 1080p
            args.add("\"r_Width 1920\"");
            args.add("\"r_Height 1080\"");
        } else if (resMode == 2) { // 720p
            args.add("\"r_Width 1280\"");
            args.add("\"r_Height 720\"");
        } else if (resMode == 3) { // 540p
            args.add("\"r_Width 960\"");
            args.add("\"r_Height 540\"");
        }
        args.add("\"r_Fullscreen 1\"");

        // Graphics quality settings for full SM2.0 lighting, water, and 3D menu background
        args.add("\"r_Quality_BumpMapping 3\"");
        args.add("\"r_NoPS20 0\"");
        args.add("\"r_GL_NV30_PS20 1\"");
        args.add("\"GL_NV30_PS20 1\"");

        // Custom parameters
        String customArgs = prefs.getString(LauncherActivity.KEY_CUSTOM_ARGS, "").trim();
        if (!customArgs.isEmpty()) {
            String[] split = customArgs.split("\\s+");
            for (String arg : split) {
                if (!arg.isEmpty()) {
                    args.add(arg);
                }
            }
        }

        return args.toArray(new String[0]);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashHandler.init(this);
        SharedPreferences prefs = getSharedPreferences(LauncherActivity.PREFS_NAME, MODE_PRIVATE);
        String gamePath = prefs.getString(LauncherActivity.KEY_GAME_PATH, "");
        LauncherActivity.cleanSystemConfigFiles(gamePath);
        super.onCreate(savedInstanceState);

        // Extend edge-to-edge across the camera notch / display cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        hideSystemUI();

        // Attach listener to continually enforce immersive full screen on insets/visibility change
        View decorView = getWindow().getDecorView();
        if (decorView != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                decorView.setOnApplyWindowInsetsListener((v, insets) -> {
                    hideSystemUI();
                    return WindowInsets.CONSUMED;
                });
            } else {
                decorView.setOnSystemUiVisibilityChangeListener(visibility -> {
                    if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                        hideSystemUI();
                    }
                });
            }
        }

        // Attach On-Screen Controls (OSC) to SDL layout
        setupControlsOverlay();

        // Hardware gamepad support: keyboard and mouse already reach the engine through SDL
        // (SDLSurface.onKey / relative-mouse pointer capture), but CryEngine 1 has no gamepad
        // backend on Android, so the pad is translated into keys / mouse here.
        gamepadMapper = new GamepadMapper(this);
    }

    // Hardware keyboard and mouse events keep flowing through SDL as before; only gamepad
    // events are consumed by the mapper.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (gamepadMapper != null && gamepadMapper.handleKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // Joystick axes are consumed by SDLSurface's generic-motion listener at view level, so we
    // intercept one step earlier - at the activity dispatch level - and only for joystick-class
    // devices. Everything else (mouse hover, etc.) flows to SDL untouched.
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (gamepadMapper != null && gamepadMapper.handleMotionEvent(event)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onPause() {
        if (gamepadMapper != null) {
            gamepadMapper.releaseAll();
        }
        if (oscManager != null) {
            // A sticky button (AIM) keeps its key/mouse button down without a finger on it -
            // it must not stay pressed while the game is in the background.
            oscManager.releaseAllPressed();
        }
        super.onPause();
    }

    private void setupControlsOverlay() {
        if (mLayout == null) return;

        // hidden by choice, or because a gamepad is connected (an option, on by default)
        boolean hideControls = TouchControls.isHidden(this)
                || (TouchControls.autoHideWithGamepad(this) && TouchControls.gamepadConnected());

        oscContainer = new RelativeLayout(this);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        oscContainer.setLayoutParams(lp);

        oscManager = new OscManager();
        oscManager.init(oscContainer, this, false);

        if (hideControls) {
            oscContainer.setVisibility(View.GONE);
        }

        mLayout.addView(oscContainer);
        addSettingsButton();
    }

    /** A small gear in the top-right corner: back to the launcher's settings (after a confirmation). */
    private void addSettingsButton() {
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(40 * density);
        TextView gear = new TextView(this);
        gear.setText("\u2699");
        gear.setTextColor(Color.WHITE);
        gear.setTextSize(22);
        gear.setGravity(Gravity.CENTER);
        gear.setAlpha(0.35f);
        gear.setContentDescription(getString(R.string.desc_settings_overlay));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0x66000000);
        gear.setBackground(background);
        gear.setOnClickListener(v -> showPauseMenu());
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(size, size);
        // top right: the top row on the left is the touch controls' (Esc, quick save, console...)
        params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        params.rightMargin = Math.round(8 * density);
        params.topMargin = Math.round(8 * density);
        mLayout.addView(gear, params);
    }

    private void showPauseMenu() {
        boolean hidden = oscContainer != null && oscContainer.getVisibility() != View.VISIBLE;
        String[] items = {
                getString(R.string.pause_back_to_game),
                getString(hidden ? R.string.pause_show_controls : R.string.pause_hide_controls),
                getString(R.string.pause_settings),
                getString(R.string.pause_quit_game),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_pause_title)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 1:
                            setTouchControlsHidden(!hidden);
                            break;
                        case 2:
                            showLauncher(false);
                            break;
                        case 3:
                            showLauncher(true);
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    /** Shows or hides the touch controls and remembers it (the launcher shows the same choice). */
    private void setTouchControlsHidden(boolean hidden) {
        if (oscContainer != null) {
            oscContainer.setVisibility(hidden ? View.GONE : View.VISIBLE);
        }
        if (oscManager != null) {
            oscManager.releaseAllPressed();
        }
        TouchControls.setHidden(this, hidden);
        if (!hidden) {
            // shown on purpose: do not hide them again on its own because a gamepad is connected
            getSharedPreferences(LauncherActivity.PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(LauncherActivity.KEY_AUTO_HIDE_PAD, false).commit();
        }
    }

    /**
     * Back to the launcher's settings. The game keeps running paused behind it (the launcher then
     * offers to resume it) unless {@code quit} asks to end it.
     */
    private void showLauncher(boolean quit) {
        backToLauncher = true;
        Intent intent = new Intent(this, LauncherActivity.class);
        intent.putExtra(LauncherActivity.EXTRA_SHOW_MENU, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        if (quit) {
            // onDestroy ends the game's own process; the launcher lives in the main one
            finish();
        }
    }

    private void hideSystemUI() {
        Window window = getWindow();
        if (window == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars()
                        | WindowInsets.Type.captionBar());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }

        View decorView = window.getDecorView();
        if (decorView != null) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        hideSystemUI();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        } else {
            // Nothing may stay "pressed" while the game is in the background.
            if (gamepadMapper != null) {
                gamepadMapper.releaseAll();
            }
            if (oscManager != null) {
                oscManager.releaseAllPressed();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            SharedPreferences prefs = getSharedPreferences(LauncherActivity.PREFS_NAME, MODE_PRIVATE);
            String gamePath = prefs.getString(LauncherActivity.KEY_GAME_PATH, "");
            LauncherActivity.cleanSystemConfigFiles(gamePath);
        } catch (Throwable ignored) {}
        // An end on purpose (so the launcher does not report it as a crash).
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(getFilesDir(), LauncherActivity.EXIT_EXPECTED_MARKER).createNewFile();
        } catch (Throwable ignored) {}
        // The game ended on its own (Quit in the game's menu): the whole app closes with it, instead of
        // leaving the launcher's settings behind. The launcher sees this marker when it comes back.
        if (!backToLauncher) {
            try {
                //noinspection ResultOfMethodCallIgnored
                new File(getFilesDir(), LauncherActivity.QUIT_APP_MARKER).createNewFile();
            } catch (Throwable ignored) {}
        }
        // Ensure clean exit of native engine
        Process.killProcess(Process.myPid());
    }
}
