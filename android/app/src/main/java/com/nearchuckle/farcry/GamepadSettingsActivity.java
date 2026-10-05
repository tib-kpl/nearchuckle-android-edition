package com.nearchuckle.farcry;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Gamepad settings: how fast the camera stick turns, its dead zone, inverted Y, and which button does
 * what. Built in code (no layout file); every change is saved at once.
 */
public class GamepadSettingsActivity extends Activity {

    private static final int SENS_MAX = 58;        // 0.2 .. 6.0
    private static final int DEADZONE_MAX = 40;    // 0.05 .. 0.45

    private final List<Button> actionButtons = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.pad_title);

        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getResources().getColor(R.color.background_dark));
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(label(getString(R.string.pad_title), 22, R.color.primary, true));

        // camera stick speed
        TextView sensLabel = label("", 14, R.color.text_primary, false);
        root.addView(sensLabel);
        SeekBar sens = new SeekBar(this);
        sens.setMax(SENS_MAX);
        int sensProgress = Math.round((GamepadBindings.lookSensitivity(this) - 0.2f) * 10f);
        sens.setProgress(Math.max(0, Math.min(SENS_MAX, sensProgress)));
        sensLabel.setText(getString(R.string.pad_sensitivity, 0.2f + sens.getProgress() / 10f));
        sens.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float value = 0.2f + progress / 10f;
                sensLabel.setText(getString(R.string.pad_sensitivity, value));
                putFloat(GamepadBindings.KEY_LOOK_SENSITIVITY, value);
            }
        });
        root.addView(sens);

        // dead zone
        TextView zoneLabel = label("", 14, R.color.text_primary, false);
        zoneLabel.setPadding(0, dp(12), 0, 0);
        root.addView(zoneLabel);
        SeekBar zone = new SeekBar(this);
        zone.setMax(DEADZONE_MAX);
        int zoneProgress = Math.round((GamepadBindings.deadzone(this) - 0.05f) * 100f);
        zone.setProgress(Math.max(0, Math.min(DEADZONE_MAX, zoneProgress)));
        zoneLabel.setText(getString(R.string.pad_deadzone, 0.05f + zone.getProgress() / 100f));
        zone.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float value = 0.05f + progress / 100f;
                zoneLabel.setText(getString(R.string.pad_deadzone, value));
                putFloat(GamepadBindings.KEY_DEADZONE, value);
            }
        });
        root.addView(zone);

        Switch invert = new Switch(this);
        invert.setText(R.string.pad_invert_y);
        invert.setTextColor(getResources().getColor(R.color.text_primary));
        invert.setPadding(0, dp(12), 0, dp(12));
        invert.setChecked(GamepadBindings.invertY(this));
        invert.setOnCheckedChangeListener((button, checked) -> prefs().edit().putBoolean(GamepadBindings.KEY_INVERT_Y, checked).commit());
        root.addView(invert);

        TextView buttonsTitle = label(getString(R.string.pad_buttons), 16, R.color.accent, true);
        buttonsTitle.setPadding(0, dp(8), 0, dp(4));
        root.addView(buttonsTitle);
        root.addView(label(getString(R.string.pad_buttons_help), 12, R.color.text_muted, false));

        for (GamepadBindings.Action action : GamepadBindings.ACTIONS) {
            Button row = new Button(this);
            row.setAllCaps(false);
            row.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            row.setTextColor(getResources().getColor(R.color.text_primary));
            row.setBackgroundResource(R.drawable.btn_dark);
            row.setTag(action);
            row.setOnClickListener(v -> captureButton(action));
            actionButtons.add(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            lp.topMargin = dp(6);
            root.addView(row, lp);
        }

        Button reset = new Button(this);
        reset.setText(R.string.pad_reset);
        reset.setTextColor(getResources().getColor(R.color.accent));
        reset.setBackgroundResource(R.drawable.btn_dark);
        reset.setOnClickListener(v -> {
            GamepadBindings.resetButtons(this);
            refreshRows();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(24);
        root.addView(reset, lp);

        setContentView(scroll);
        refreshRows();
    }

    private void refreshRows() {
        for (Button row : actionButtons) {
            GamepadBindings.Action action = (GamepadBindings.Action) row.getTag();
            row.setText(getString(action.labelRes) + "  →  " + GamepadBindings.buttonsText(this, action));
        }
    }

    /** Waits for a pad button and gives it to the action. */
    private void captureButton(GamepadBindings.Action action) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(action.labelRes)
                .setMessage(R.string.pad_press_button)
                .setNeutralButton(R.string.pad_clear, (d, which) -> {
                    GamepadBindings.clear(this, action);
                    refreshRows();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || !GamepadBindings.isPadEvent(event)) {
                return false;
            }
            GamepadBindings.assign(this, action, keyCode);
            refreshRows();
            d.dismiss();
            return true;
        });
        dialog.show();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(LauncherActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void putFloat(String key, float value) {
        prefs().edit().putFloat(key, value).commit();
    }

    private TextView label(String text, int sp, int colorRes, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(getResources().getColor(colorRes));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
