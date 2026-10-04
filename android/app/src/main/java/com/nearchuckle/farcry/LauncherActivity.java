package com.nearchuckle.farcry;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.app.ActivityManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.nearchuckle.farcry.driver.DriverHook;
import com.nearchuckle.farcry.driver.DriverInfo;
import com.nearchuckle.farcry.driver.TurnipDriverManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Main launcher activity for Far Cry (NearChuckle) on Android.
 * Provides game file configuration, Mesa Zink settings, Turnip driver management, and controls setup.
 */
public class LauncherActivity extends Activity {
    public static final String PREFS_NAME = "farcry_prefs";
    public static final String KEY_GAME_PATH = "game_path";
    public static final String KEY_USE_ZINK = "use_zink";
    public static final String KEY_RES_MODE = "resolution_mode";
    public static final String KEY_FOV = "game_fov";
    public static final String KEY_DEVMODE = "devmode";
    public static final String KEY_CUSTOM_ARGS = "custom_args";
    public static final String KEY_HIDE_CONTROLS = "hide_controls";
    public static final String KEY_AUTO_LAUNCH = "auto_launch";
    public static final String KEY_VIDEO_FIT = "video_fit";
    public static final String KEY_AUTO_HIDE_PAD = "auto_hide_touch_with_gamepad";
    /** Set by the in-game settings button: show this menu instead of starting the game again. */
    public static final String EXTRA_SHOW_MENU = "show_menu";
    public static final String KEY_MOUSE_SENSITIVITY = "mouse_sensitivity";
    /** "auto" (the device's language when the game has it) or a language pak name ("french"...) */
    public static final String KEY_GAME_LANGUAGE = "game_language";

    /** Delay before the automatic update check starts, so the UI is on screen first. */
    private static final long UPDATE_CHECK_DELAY_MS = 600L;

    private static final int REQ_CODE_ZIP = 1001;
    private static final int REQ_CODE_STORAGE_PERMISSION = 1002;

    private EditText editGamePath;
    private TextView tvGamePathStatus;
    private TextView tvGpuDetect;
    private Spinner spinnerDrivers;
    private Button btnDeleteDriver;
    private Switch switchGpuTurbo;
    private Switch switchUseZink;
    private Spinner spinnerResolution;
    private Spinner spinnerVideoFit;
    private Spinner spinnerLanguage;
    /** the values behind spinnerLanguage: "auto", then the game's languages */
    private final List<String> languageValues = new ArrayList<>();
    private TextView tvFovLabel;
    private SeekBar seekbarFov;
    private Switch switchDevmode;
    private EditText editCustomArgs;
    private TextView tvSensLabel;
    private SeekBar seekbarSensitivity;
    private Switch switchHideControls;
    private Switch switchAutoLaunch;
    private Switch switchAutoHidePad;
    private Button btnResumeGame;
    /** Floating, top right (where the game's gear is): back to the game that is still running. */
    private Button btnBackToGame;

    private List<DriverInfo> installedDrivers = new ArrayList<>();
    private ArrayAdapter<String> driverAdapter;

    /** Set when an APK was downloaded but the user still has to allow "install unknown apps". */
    private File pendingInstallApk;
    /** The automatic check runs once per activity instance (not after every onResume). */
    private boolean autoUpdateCheckStarted = false;
    /** Currently visible update dialog, so it is never shown twice at once. */
    private AlertDialog updateDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashHandler.init(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        addBackToGameButton();

        requestStoragePermissions();

        initViews();
        loadPreferences();
        setupGpuDetection();
        setupDriverSpinner();
        setupListeners();

        // Remove APK files left over by previous sessions (the fresh one is downloaded on demand).
        UpdateManager.clearDownloadedUpdates(this);

        if (shouldAutoLaunch(savedInstanceState)) {
            new Handler(Looper.getMainLooper()).post(this::launchGame);
        }
    }

    /**
     * Starts the game straight away when it can: the option is on, its files are there, the storage
     * permission is granted, the last run did not crash and the player did not come here on purpose
     * (the settings button over the game).
     */
    private boolean shouldAutoLaunch(Bundle savedInstanceState) {
        if (savedInstanceState != null || getIntent().getBooleanExtra(EXTRA_SHOW_MENU, false)) return false;
        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_AUTO_LAUNCH, true)) return false;
        if (CrashHandler.hasUnreadCrash(this)) return false;
        if (gameProcessPid() > 0) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) return false;
        return checkGameFilesExist(editGamePath.getText().toString().trim());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    /** The pid of the game's own process, or -1 when no game is running. */
    private int gameProcessPid() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return -1;
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) return -1;
        String name = getPackageName() + ":game";
        for (ActivityManager.RunningAppProcessInfo info : processes) {
            if (name.equals(info.processName)) return info.pid;
        }
        return -1;
    }

    private void updateResumeButton() {
        int visibility = gameProcessPid() > 0 ? View.VISIBLE : View.GONE;
        btnResumeGame.setVisibility(visibility);
        if (btnBackToGame != null) {
            btnBackToGame.setVisibility(visibility);
        }
    }

    private void addBackToGameButton() {
        float density = getResources().getDisplayMetrics().density;
        btnBackToGame = new Button(this);
        btnBackToGame.setText(R.string.pause_back_to_game);
        btnBackToGame.setAllCaps(false);
        btnBackToGame.setTextColor(0xFF000000);
        btnBackToGame.setBackgroundResource(R.drawable.btn_accent);
        btnBackToGame.setVisibility(View.GONE);
        btnBackToGame.setOnClickListener(v -> resumeGame());
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(44 * density),
                android.view.Gravity.TOP | android.view.Gravity.END);
        params.topMargin = Math.round(8 * density);
        params.rightMargin = Math.round(8 * density);
        addContentView(btnBackToGame, params);
    }

    /** Brings the game that is still running (paused behind the launcher) back to the front. */
    private void resumeGame() {
        Intent intent = new Intent(this, GameActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateResumeButton();
        // the game may have shown or hidden the touch controls
        switchHideControls.setChecked(TouchControls.isHidden(this));
        if (CrashHandler.hasUnreadCrash(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_crash_detected_title)
                    .setMessage(R.string.dialog_crash_detected_msg)
                    .setPositiveButton(R.string.btn_open_report, (dialog, which) -> {
                        Intent intent = new Intent(this, CrashReportActivity.class);
                        startActivity(intent);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        // The user may have just granted "install unknown apps" - finish the pending install.
        if (pendingInstallApk != null && UpdateManager.canInstallPackages(this)) {
            File apk = pendingInstallApk;
            pendingInstallApk = null;
            installDownloadedApk(apk);
        }

        if (!autoUpdateCheckStarted) {
            autoUpdateCheckStarted = true;
            new Handler(Looper.getMainLooper())
                    .postDelayed(() -> checkForUpdates(false), UPDATE_CHECK_DELAY_MS);
        }
    }

    private void initViews() {
        editGamePath = findViewById(R.id.edit_game_path);
        tvGamePathStatus = findViewById(R.id.tv_game_path_status);
        tvGpuDetect = findViewById(R.id.tv_gpu_detect);
        spinnerDrivers = findViewById(R.id.spinner_drivers);
        btnDeleteDriver = findViewById(R.id.btn_delete_driver);
        switchGpuTurbo = findViewById(R.id.switch_gpu_turbo);
        switchUseZink = findViewById(R.id.switch_use_zink);
        spinnerResolution = findViewById(R.id.spinner_resolution);
        spinnerVideoFit = findViewById(R.id.spinner_video_fit);
        spinnerLanguage = findViewById(R.id.spinner_language);
        tvFovLabel = findViewById(R.id.tv_fov_label);
        seekbarFov = findViewById(R.id.seekbar_fov);
        switchDevmode = findViewById(R.id.switch_devmode);
        editCustomArgs = findViewById(R.id.edit_custom_args);
        tvSensLabel = findViewById(R.id.tv_sensitivity_label);
        seekbarSensitivity = findViewById(R.id.seekbar_sensitivity);
        switchHideControls = findViewById(R.id.switch_hide_controls);
        switchAutoLaunch = findViewById(R.id.switch_auto_launch);
        switchAutoHidePad = findViewById(R.id.switch_auto_hide_pad);
        btnResumeGame = findViewById(R.id.btn_resume_game);

        // Resolution options
        String[] resOptions = new String[]{
                getString(R.string.res_auto),
                getString(R.string.res_1080p),
                getString(R.string.res_720p),
                getString(R.string.res_540p)
        };
        ArrayAdapter<String> resAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, resOptions);
        spinnerResolution.setAdapter(resAdapter);

        // How the videos fill the screen
        String[] videoOptions = new String[]{
                getString(R.string.video_fit_auto),
                getString(R.string.video_fit_original),
                getString(R.string.video_fit_fill),
                getString(R.string.video_fit_stretch)
        };
        spinnerVideoFit.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, videoOptions));
    }

    // ---------- game language

    /**
     * The languages of the game in gamePath: the names of its
     * FCData/Localized/<language>.pak files ("english", "french"...), without
     * the patches (<language>1.pak, <language>2.pak). English first.
     */
    public static List<String> availableLanguages(String gamePath) {
        List<String> languages = new ArrayList<>();
        File localized = findChildIgnoreCase(findChildIgnoreCase(new File(gamePath), "FCData"), "Localized");
        File[] paks = localized != null ? localized.listFiles() : null;
        if (paks == null)
            return languages;
        for (File pak : paks) {
            String name = pak.getName().toLowerCase(java.util.Locale.ROOT);
            if (!pak.isFile() || !name.endsWith(".pak"))
                continue;
            name = name.substring(0, name.length() - 4);
            if (name.isEmpty() || Character.isDigit(name.charAt(name.length() - 1)) || languages.contains(name))
                continue;
            languages.add(name);
        }
        Collections.sort(languages);
        if (languages.remove("english"))
            languages.add(0, "english");
        return languages;
    }

    private static File findChildIgnoreCase(File parent, String name) {
        if (parent == null)
            return null;
        File[] children = parent.listFiles();
        if (children == null)
            return null;
        for (File child : children) {
            if (child.getName().equalsIgnoreCase(name))
                return child;
        }
        return null;
    }

    /** the Android language code of a game language pak, for "auto" */
    private static final String[][] LANGUAGE_CODES = {
            { "en", "english" }, { "fr", "french" }, { "de", "german" }, { "it", "italian" },
            { "es", "spanish" }, { "ru", "russian" }, { "pl", "polish" }, { "cs", "czech" },
            { "hu", "hungarian" }, { "ja", "japanese" }, { "ko", "korean" }, { "zh", "chinese" },
            { "pt", "portuguese" }, { "nl", "dutch" }, { "tr", "turkish" },
    };

    /**
     * The language to start the game in (NC_GAME_LANGUAGE), or null to let
     * the engine use English: the launcher's choice, or in "auto" the
     * device's language, when the game folder has that language's pak.
     */
    public static String resolveGameLanguage(android.content.Context context, String gamePath) {
        List<String> available = availableLanguages(gamePath);
        String chosen = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_GAME_LANGUAGE, "auto");
        if (!"auto".equals(chosen))
            return available.contains(chosen) ? chosen : null;
        String code = java.util.Locale.getDefault().getLanguage();
        for (String[] pair : LANGUAGE_CODES) {
            if (pair[0].equals(code) && available.contains(pair[1]))
                return pair[1];
        }
        return null;
    }

    private static String languageLabel(String language) {
        switch (language) {
            case "english": return "English";
            case "french": return "Français";
            case "german": return "Deutsch";
            case "italian": return "Italiano";
            case "spanish": return "Español";
            case "russian": return "Русский";
            case "polish": return "Polski";
            case "czech": return "Čeština";
            case "hungarian": return "Magyar";
            case "japanese": return "日本語";
            case "korean": return "한국어";
            case "chinese": return "中文";
            case "portuguese": return "Português";
            case "dutch": return "Nederlands";
            case "turkish": return "Türkçe";
            default: return Character.toUpperCase(language.charAt(0)) + language.substring(1);
        }
    }

    /** fills spinnerLanguage with "auto" and the languages found in gamePath */
    private void setupLanguageSpinner(String gamePath, String selected) {
        languageValues.clear();
        languageValues.add("auto");
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.language_auto));
        for (String language : availableLanguages(gamePath)) {
            languageValues.add(language);
            labels.add(languageLabel(language));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);
        spinnerLanguage.setAdapter(adapter);
        int index = languageValues.indexOf(selected);
        spinnerLanguage.setSelection(index >= 0 ? index : 0);
    }

    private void setupGpuDetection() {
        boolean isAdreno = DriverHook.isQualcommAdreno();
        if (isAdreno) {
            tvGpuDetect.setText(R.string.gpu_adreno_detected);
            tvGpuDetect.setTextColor(getColor(R.color.status_green));
            switchGpuTurbo.setEnabled(true);
        } else {
            tvGpuDetect.setText(R.string.gpu_other_detected);
            tvGpuDetect.setTextColor(getColor(R.color.text_secondary));
            switchGpuTurbo.setEnabled(false);
        }
    }

    private void setupDriverSpinner() {
        installedDrivers = TurnipDriverManager.getInstalledDrivers(this);
        List<String> names = new ArrayList<>();
        int selectedIndex = 0;
        DriverInfo selected = TurnipDriverManager.getSelectedDriver(this);

        for (int i = 0; i < installedDrivers.size(); i++) {
            DriverInfo d = installedDrivers.get(i);
            names.add(d.toString());
            if (d.getId().equals(selected.getId())) {
                selectedIndex = i;
            }
        }

        driverAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names);
        spinnerDrivers.setAdapter(driverAdapter);
        spinnerDrivers.setSelection(selectedIndex);

        btnDeleteDriver.setEnabled(!selected.isSystem());
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Default Far Cry path guess
        String defaultPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/FarCry";
        String path = prefs.getString(KEY_GAME_PATH, defaultPath);
        editGamePath.setText(path);
        validateGamePath(path);

        switchUseZink.setChecked(prefs.getBoolean(KEY_USE_ZINK, true));
        spinnerResolution.setSelection(prefs.getInt(KEY_RES_MODE, 0));
        spinnerVideoFit.setSelection(prefs.getInt(KEY_VIDEO_FIT, 0));
        setupLanguageSpinner(path, prefs.getString(KEY_GAME_LANGUAGE, "auto"));

        int fov = prefs.getInt(KEY_FOV, 90);
        seekbarFov.setProgress(Math.max(0, Math.min(50, fov - 70)));
        tvFovLabel.setText(getString(R.string.label_fov, fov));

        switchDevmode.setChecked(prefs.getBoolean(KEY_DEVMODE, false));
        editCustomArgs.setText(prefs.getString(KEY_CUSTOM_ARGS, ""));

        switchGpuTurbo.setChecked(TurnipDriverManager.isTurboEnabled(this));

        float sens = prefs.getFloat(KEY_MOUSE_SENSITIVITY, 1.0f);
        int sensProgress = Math.round((sens - 0.5f) * 10f);
        seekbarSensitivity.setProgress(Math.max(0, Math.min(25, sensProgress)));
        tvSensLabel.setText(getString(R.string.label_mouse_sensitivity, sens));

        TouchControls.syncFromPreferences(this);
        switchHideControls.setChecked(TouchControls.isHidden(this));
        switchAutoHidePad.setChecked(prefs.getBoolean(KEY_AUTO_HIDE_PAD, true));
        switchAutoLaunch.setChecked(prefs.getBoolean(KEY_AUTO_LAUNCH, true));
    }

    private void savePreferences() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(KEY_GAME_PATH, editGamePath.getText().toString().trim());
        editor.putBoolean(KEY_USE_ZINK, switchUseZink.isChecked());
        editor.putInt(KEY_RES_MODE, spinnerResolution.getSelectedItemPosition());
        editor.putInt(KEY_VIDEO_FIT, spinnerVideoFit.getSelectedItemPosition());
        int language = spinnerLanguage.getSelectedItemPosition();
        if (language >= 0 && language < languageValues.size())
            editor.putString(KEY_GAME_LANGUAGE, languageValues.get(language));

        int fov = seekbarFov.getProgress() + 70;
        editor.putInt(KEY_FOV, fov);
        editor.putBoolean(KEY_DEVMODE, switchDevmode.isChecked());
        editor.putString(KEY_CUSTOM_ARGS, editCustomArgs.getText().toString().trim());

        float sens = 0.5f + (seekbarSensitivity.getProgress() / 10.0f);
        editor.putFloat(KEY_MOUSE_SENSITIVITY, sens);
        editor.putBoolean(KEY_HIDE_CONTROLS, switchHideControls.isChecked());
        editor.putBoolean(KEY_AUTO_HIDE_PAD, switchAutoHidePad.isChecked());
        editor.putBoolean(KEY_AUTO_LAUNCH, switchAutoLaunch.isChecked());
        // commit, not apply: the game runs in its own process and reads these on start
        editor.commit();
        TouchControls.setHidden(this, switchHideControls.isChecked());

        TurnipDriverManager.setTurboEnabled(this, switchGpuTurbo.isChecked());
    }

    private void setupListeners() {
        // Path input change validation
        editGamePath.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int count, int after) {
                validateGamePath(s.toString().trim());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Folder browse button
        findViewById(R.id.btn_browse_folder).setOnClickListener(v -> showFolderPickerDialog());

        // Driver selection
        spinnerDrivers.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < installedDrivers.size()) {
                    DriverInfo chosen = installedDrivers.get(position);
                    TurnipDriverManager.setSelectedDriver(LauncherActivity.this, chosen.getId());
                    btnDeleteDriver.setEnabled(!chosen.isSystem());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Install Driver ZIP button
        findViewById(R.id.btn_install_driver_zip).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/zip");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(Intent.createChooser(intent, getString(R.string.choose_driver_zip_title)), REQ_CODE_ZIP);
            } catch (Exception e) {
                Toast.makeText(this, R.string.error_no_file_manager, Toast.LENGTH_SHORT).show();
            }
        });

        // Delete Driver button
        btnDeleteDriver.setOnClickListener(v -> {
            DriverInfo sel = TurnipDriverManager.getSelectedDriver(this);
            if (sel.isSystem()) return;

            new AlertDialog.Builder(this)
                    .setTitle(R.string.btn_delete_driver)
                    .setMessage(getString(R.string.dialog_delete_driver_prompt, sel.getName()))
                    .setPositiveButton(R.string.yes, (dialog, which) -> {
                        TurnipDriverManager.deleteDriver(this, sel.getId());
                        setupDriverSpinner();
                        Toast.makeText(this, R.string.toast_driver_removed, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.no, null)
                    .show();
        });

        // FOV seekbar
        seekbarFov.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int fov = progress + 70;
                tvFovLabel.setText(getString(R.string.label_fov, fov));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Sensitivity seekbar
        seekbarSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float sens = 0.5f + (progress / 10.0f);
                tvSensLabel.setText(getString(R.string.label_mouse_sensitivity, sens));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // NOTE: the standalone "Configure On-Screen Controls" button was removed from the
        // launcher UI on purpose. Controls are still adjustable in-game via the EDIT button
        // (OscManager edit mode); ConfigureControlsActivity stays available for debugging.

        // View Logs & Crash Reports button
        findViewById(R.id.btn_view_logs).setOnClickListener(v -> {
            Intent intent = new Intent(this, CrashReportActivity.class);
            startActivity(intent);
        });

        // Download GL Shaders button
        Button btnDownloadShaders = findViewById(R.id.btn_download_shaders);
        if (btnDownloadShaders != null) {
            btnDownloadShaders.setOnClickListener(v -> downloadShadersPack());
        }

        // Manual "Check for updates" button
        Button btnCheckUpdates = findViewById(R.id.btn_check_updates);
        if (btnCheckUpdates != null) {
            btnCheckUpdates.setOnClickListener(v -> checkForUpdates(true));
        }

        // Launch Game Button
        findViewById(R.id.btn_launch_game).setOnClickListener(v -> launchGame());
        btnResumeGame.setOnClickListener(v -> resumeGame());
        findViewById(R.id.btn_gamepad_settings).setOnClickListener(v ->
                startActivity(new Intent(this, GamepadSettingsActivity.class)));
    }

    private static final String SHADERS_URL = "https://rohitcodes.fyi/nearchuckle/files/shadercache/GL_Shaders_20260517.pak";

    private void downloadShadersPack() {
        String path = editGamePath.getText().toString().trim();
        if (path.isEmpty()) {
            Toast.makeText(this, R.string.toast_specify_game_path_first, Toast.LENGTH_SHORT).show();
            return;
        }
        File gameDir = new File(path);
        if (!gameDir.exists() || !gameDir.isDirectory()) {
            Toast.makeText(this, R.string.error_no_game_folder, Toast.LENGTH_SHORT).show();
            return;
        }

        File fcData = new File(gameDir, "FCData");
        if (!fcData.exists()) {
            fcData.mkdirs();
        }
        File targetPak = new File(fcData, "GL_Shaders_20260517.pak");
        if (targetPak.exists() && targetPak.length() > 1024) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.btn_download_shaders)
                    .setMessage(getString(R.string.dialog_shaders_exists_prompt, (int) (targetPak.length() / 1024)))
                    .setPositiveButton(R.string.yes, (dialog, which) -> startShaderDownload(targetPak))
                    .setNegativeButton(R.string.no, null)
                    .show();
        } else {
            startShaderDownload(targetPak);
        }
    }

    private void startShaderDownload(File targetFile) {
        android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
        progress.setTitle(R.string.btn_download_shaders);
        progress.setMessage(getString(R.string.dialog_shaders_downloading));
        progress.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            java.io.InputStream in = null;
            java.io.OutputStream out = null;
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URL url = new java.net.URL(SHADERS_URL);
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.connect();

                if (conn.getResponseCode() != java.net.HttpURLConnection.HTTP_OK) {
                    throw new java.io.IOException("HTTP " + conn.getResponseCode() + " " + conn.getResponseMessage());
                }

                int fileLength = conn.getContentLength();
                in = conn.getInputStream();
                out = new java.io.FileOutputStream(targetFile);

                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (fileLength > 0) {
                        int pct = (int) (total * 100 / fileLength);
                        runOnUiThread(() -> progress.setProgress(pct));
                    }
                    out.write(buffer, 0, count);
                }
                out.flush();

                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, R.string.dialog_shaders_success, Toast.LENGTH_LONG).show();
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.dialog_shaders_downloaded_title)
                            .setMessage(R.string.dialog_shaders_success)
                            .setPositiveButton(R.string.ok, null)
                            .show();
                });
            } catch (Exception e) {
                targetFile.delete();
                runOnUiThread(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.error_title)
                            .setMessage(getString(R.string.dialog_shaders_failed) + e.getMessage())
                            .setPositiveButton(R.string.ok, null)
                            .show();
                });
            } finally {
                try { if (out != null) out.close(); } catch (Exception ignored) {}
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ---------------------------------------------------------------------------------------------
    // Launcher self-update (new APK version notification)
    // ---------------------------------------------------------------------------------------------

    /**
     * Asks the update server whether a newer APK exists.
     *
     * @param manual true when the user pressed "Check for updates" (result is always reported)
     */
    private void checkForUpdates(final boolean manual) {
        if (manual) {
            Toast.makeText(this, R.string.toast_update_checking, Toast.LENGTH_SHORT).show();
        }

        UpdateManager.checkAsync(this, manual, (info, error) -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (info != null) {
                showUpdateDialog(info);
            } else if (manual) {
                if (error != null) {
                    Toast.makeText(this, R.string.toast_update_check_failed, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this,
                            getString(R.string.toast_update_up_to_date,
                                    UpdateManager.getInstalledVersionName(this)),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** "Update available" dialog: [Download] / [Cancel]. */
    private void showUpdateDialog(final UpdateManager.UpdateInfo info) {
        if (updateDialog != null && updateDialog.isShowing()) {
            return;
        }

        String message = getString(R.string.dialog_update_msg,
                info.versionName != null ? info.versionName : String.valueOf(info.versionCode),
                info.versionCode,
                UpdateManager.getInstalledVersionName(this),
                UpdateManager.getInstalledVersionCode(this));
        if (info.notes != null && !info.notes.isEmpty()) {
            message = message + "\n\n" + info.notes;
        }

        updateDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_update_title)
                .setMessage(message)
                .setPositiveButton(R.string.btn_update_download, (dialog, which) -> startUpdateDownload(info))
                .setNegativeButton(R.string.cancel, null)
                .setCancelable(!info.mandatory)
                .show();
    }

    /** Downloads the new APK with a progress bar, then hands it to the system installer. */
    private void startUpdateDownload(final UpdateManager.UpdateInfo info) {
        if (info.apkUrl == null || info.apkUrl.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_update_failed_title)
                    .setMessage(getString(R.string.dialog_update_failed_msg, "no APK URL in update manifest"))
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }

        final android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
        progress.setTitle(R.string.dialog_update_title);
        progress.setMessage(getString(R.string.dialog_update_downloading));
        progress.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();

        UpdateManager.downloadApk(this, info, new UpdateManager.DownloadListener() {
            @Override
            public void onProgress(final int percent, final long downloadedBytes, final long totalBytes) {
                runOnUiThread(() -> {
                    progress.setProgress(percent);
                    progress.setMessage(getString(R.string.dialog_update_downloading_progress,
                            percent,
                            UpdateManager.formatSize(downloadedBytes),
                            UpdateManager.formatSize(totalBytes)));
                });
            }

            @Override
            public void onSuccess(final File apk) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (!UpdateManager.canInstallPackages(LauncherActivity.this)) {
                        askForUnknownSourcesPermission(apk);
                    } else {
                        installDownloadedApk(apk);
                    }
                });
            }

            @Override
            public void onError(final Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    new AlertDialog.Builder(LauncherActivity.this)
                            .setTitle(R.string.dialog_update_failed_title)
                            .setMessage(getString(R.string.dialog_update_failed_msg,
                                    UpdateManager.describe(e)))
                            .setPositiveButton(R.string.ok, null)
                            .show();
                });
            }
        });
    }

    /** Android 8+: the user has to allow "install unknown apps" before the installer can run. */
    private void askForUnknownSourcesPermission(final File apk) {
        pendingInstallApk = apk;
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_unknown_sources_title)
                .setMessage(R.string.dialog_unknown_sources_msg)
                .setPositiveButton(R.string.btn_permission_allow, (dialog, which) -> {
                    try {
                        startActivity(UpdateManager.unknownSourcesSettingsIntent(this));
                    } catch (Exception e) {
                        pendingInstallApk = null;
                        Toast.makeText(this, R.string.toast_update_check_failed, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> pendingInstallApk = null)
                .setOnCancelListener(dialog -> pendingInstallApk = null)
                .show();
    }

    private void installDownloadedApk(File apk) {
        try {
            UpdateManager.installApk(this, apk);
            Toast.makeText(this, R.string.toast_update_installing, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_update_failed_title)
                    .setMessage(getString(R.string.dialog_update_failed_msg, UpdateManager.describe(e)))
                    .setPositiveButton(R.string.ok, null)
                    .show();
        }
    }

    private void validateGamePath(String path) {
        if (path == null || path.isEmpty()) {
            tvGamePathStatus.setText(R.string.status_files_missing);
            tvGamePathStatus.setTextColor(getColor(R.color.status_red));
            return;
        }

        File folder = new File(path);
        if (!folder.exists() || !folder.isDirectory()) {
            tvGamePathStatus.setText(R.string.folder_not_exist);
            tvGamePathStatus.setTextColor(getColor(R.color.status_red));
            return;
        }

        // Clean any problematic system.cfg in game folder and profiles
        cleanSystemConfigFiles(path);

        // Check for FCData or Levels or pak files
        File fcData = new File(folder, "FCData");
        File levels = new File(folder, "Levels");
        boolean hasFcData = fcData.exists() && fcData.isDirectory();
        boolean hasLevels = levels.exists() && levels.isDirectory();

        String chosenLanguage = "auto";
        int language = spinnerLanguage.getSelectedItemPosition();
        if (language >= 0 && language < languageValues.size())
            chosenLanguage = languageValues.get(language);
        setupLanguageSpinner(path, chosenLanguage);

        if (hasFcData || hasLevels) {
            tvGamePathStatus.setText(R.string.status_files_found);
            tvGamePathStatus.setTextColor(getColor(R.color.status_green));
        } else {
            tvGamePathStatus.setText(R.string.status_files_missing);
            tvGamePathStatus.setTextColor(getColor(R.color.status_orange));
        }
    }

    private void showFolderPickerDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_folder_picker, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();

        TextView tvCurrent = dialogView.findViewById(R.id.tv_picker_current_path);
        TextView tvStatus = dialogView.findViewById(R.id.tv_picker_status);
        ListView lvItems = dialogView.findViewById(R.id.lv_folder_items);
        Button btnSelect = dialogView.findViewById(R.id.btn_picker_select);
        Button btnCancel = dialogView.findViewById(R.id.btn_picker_cancel);

        // Start in currently entered folder if valid, otherwise external storage
        String currentInput = editGamePath.getText().toString().trim();
        File initialDir = new File(currentInput);
        if (!initialDir.exists() || !initialDir.isDirectory()) {
            initialDir = Environment.getExternalStorageDirectory();
        }
        final File[] currentDir = new File[]{initialDir};
        final List<String> itemNames = new ArrayList<>();
        final List<File> itemFiles = new ArrayList<>();

        Runnable updateList = () -> {
            tvCurrent.setText(currentDir[0].getAbsolutePath());
            itemNames.clear();
            itemFiles.clear();

            if (currentDir[0].getParentFile() != null) {
                itemNames.add(getString(R.string.folder_level_up));
                itemFiles.add(currentDir[0].getParentFile());
            }

            File[] files = currentDir[0].listFiles(File::isDirectory);
            if (files != null) {
                Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File f : files) {
                    itemNames.add(f.getName());
                    itemFiles.add(f);
                }
            }

            // Check if Far Cry files are present in the currently selected directory
            if (tvStatus != null) {
                File fcData = new File(currentDir[0], "FCData");
                File levels = new File(currentDir[0], "Levels");
                File[] paks = currentDir[0].listFiles((dir, name) -> name.toLowerCase().endsWith(".pak"));
                boolean hasFiles = (fcData.exists() && fcData.isDirectory()) ||
                                   (levels.exists() && levels.isDirectory()) ||
                                   (paks != null && paks.length > 0);
                if (hasFiles) {
                    tvStatus.setText(R.string.status_fc_found_in_folder);
                    tvStatus.setTextColor(Color.rgb(80, 220, 100));
                } else {
                    tvStatus.setText(R.string.status_fc_select_folder_hint);
                    tvStatus.setTextColor(Color.rgb(180, 190, 200));
                }
            }

            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                    android.R.layout.simple_list_item_1, itemNames) {
                @NonNull
                @Override
                public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                    TextView tv = (TextView) super.getView(position, convertView, parent);
                    tv.setTextColor(Color.WHITE);
                    tv.setTextSize(14f);
                    int padH = (int) (12 * getResources().getDisplayMetrics().density);
                    int padV = (int) (8 * getResources().getDisplayMetrics().density);
                    tv.setPadding(padH, padV, padH, padV);
                    String name = itemNames.get(position);
                    if (name.startsWith("..")) {
                        tv.setText("📁  " + name);
                        tv.setTextColor(Color.rgb(120, 200, 255));
                    } else {
                        tv.setText("📁  " + name);
                    }
                    return tv;
                }
            };
            lvItems.setAdapter(adapter);
        };

        updateList.run();

        lvItems.setOnItemClickListener((parent, view, position, id) -> {
            if (position < itemFiles.size()) {
                currentDir[0] = itemFiles.get(position);
                updateList.run();
            }
        });

        btnSelect.setOnClickListener(v -> {
            editGamePath.setText(currentDir[0].getAbsolutePath());
            validateGamePath(currentDir[0].getAbsolutePath());
            dialog.dismiss();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                int width = (int) (dm.widthPixels * 0.90);
                int height = (int) (dm.heightPixels * 0.90);
                window.setLayout(width, height);
            }
        });

        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_ZIP && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                installDriverZip(uri);
            }
        }
    }

    private void installDriverZip(Uri uri) {
        String fileName = "Turnip_Driver.zip";
        try {
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex);
                }
                cursor.close();
            }
        } catch (Exception ignored) {}

        Toast.makeText(this, getString(R.string.toast_installing_driver, fileName), Toast.LENGTH_SHORT).show();

        try {
            DriverInfo installed = TurnipDriverManager.installDriverFromUri(this, uri, fileName);
            TurnipDriverManager.setSelectedDriver(this, installed.getId());
            setupDriverSpinner();

            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_driver_installed)
                    .setMessage(getString(R.string.dialog_driver_installed_msg, installed.getName()))
                    .setPositiveButton(R.string.ok, null)
                    .show();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_driver_install_failed_title)
                    .setMessage(getString(R.string.dialog_driver_install_failed) + e.getMessage())
                    .setPositiveButton(R.string.ok, null)
                    .show();
        }
    }

    private boolean checkGameFilesExist(String path) {
        if (path == null || path.isEmpty()) return false;
        File folder = new File(path);
        if (!folder.exists() || !folder.isDirectory()) return false;
        File fcData = new File(folder, "FCData");
        File levels = new File(folder, "Levels");
        if ((fcData.exists() && fcData.isDirectory()) || (levels.exists() && levels.isDirectory())) {
            return true;
        }
        File[] paks = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".pak"));
        return paks != null && paks.length > 0;
    }

    private void launchGame() {
        int running = gameProcessPid();
        if (running > 0) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_restart_title)
                    .setMessage(R.string.dialog_restart_msg)
                    .setPositiveButton(R.string.btn_restart, (dialog, which) -> {
                        android.os.Process.killProcess(running);
                        new Handler(Looper.getMainLooper()).postDelayed(this::launchGame, 700);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        savePreferences();
        String gamePath = editGamePath.getText().toString().trim();
        File f = new File(gamePath);
        if (!f.exists()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_folder_not_found_title)
                    .setMessage(getString(R.string.dialog_folder_not_found_msg, gamePath))
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_storage_permission_title)
                    .setMessage(R.string.dialog_storage_permission_msg)
                    .setPositiveButton(R.string.btn_permission_allow, (dialog, which) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception e) {
                            startGameActivity();
                        }
                    })
                    .setNegativeButton(R.string.btn_permission_continue, (dialog, which) -> startGameActivity())
                    .show();
            return;
        }

        if (!checkGameFilesExist(gamePath)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_game_files_missing_title)
                    .setMessage(getString(R.string.dialog_game_files_missing_msg, gamePath))
                    .setPositiveButton(R.string.btn_launch, (dialog, which) -> startGameActivity())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }

        startGameActivity();
    }

    private void startGameActivity() {
        String gamePath = editGamePath.getText().toString().trim();
        cleanSystemConfigFiles(gamePath);
        Intent intent = new Intent(this, GameActivity.class);
        startActivity(intent);
    }

    /**
     * Automatically remove system.cfg and systemcfgoverride.cfg files because they
     * cause crashes and break graphics on Android mobile environment.
     */
    public static void cleanSystemConfigFiles(String gamePath) {
        if (gamePath == null || gamePath.trim().isEmpty()) return;
        try {
            File dir = new File(gamePath);
            if (!dir.exists() || !dir.isDirectory()) return;

            // Delete system.cfg and systemcfgoverride.cfg in root
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        String name = f.getName().toLowerCase();
                        if (name.equals("system.cfg") || name.equals("systemcfgoverride.cfg")) {
                            boolean deleted = f.delete();
                            android.util.Log.i("FarCry", "Cleaned problematic config: " + f.getAbsolutePath() + " (deleted=" + deleted + ")");
                        }
                    }
                }
            }

            // Also check Profiles directory
            File profilesDir = new File(dir, "Profiles");
            if (profilesDir.exists() && profilesDir.isDirectory()) {
                cleanProfilesFolder(profilesDir);
            }
        } catch (Throwable t) {
            android.util.Log.w("FarCry", "Failed to clean system configs: " + t.getMessage());
        }
    }

    private static void cleanProfilesFolder(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                cleanProfilesFolder(f);
            } else if (f.isFile()) {
                String name = f.getName().toLowerCase();
                if (name.endsWith("system.cfg") || name.equals("systemcfgoverride.cfg")) {
                    boolean deleted = f.delete();
                    android.util.Log.i("FarCry", "Cleaned profile config: " + f.getAbsolutePath() + " (deleted=" + deleted + ")");
                }
            }
        }
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQ_CODE_STORAGE_PERMISSION);
            }
        }
    }
}
