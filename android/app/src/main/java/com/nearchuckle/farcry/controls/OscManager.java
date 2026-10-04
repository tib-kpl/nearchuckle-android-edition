package com.nearchuckle.farcry.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import com.nearchuckle.farcry.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the collection of on-screen controls for Far Cry, including Edit Mode
 * for interactive positioning, resizing, and visibility toggling.
 */
public class OscManager {
    private static final String PREF_NAME = "farcry_osc_controls";
    private static final String KEY_SENSITIVITY = "mouse_sensitivity";
    private static final String KEY_HIDE_ALL = "hide_all_controls";

    private final List<OscElement> elements = new ArrayList<>();
    private RelativeLayout container;
    private EditToolbarView editToolbar;
    private OscElement selectedElement = null;

    private boolean editMode = false;
    private float mouseSensitivity = 1.0f;
    private int screenWidth = 1920;
    private int screenHeight = 1080;
    /** The first layout pass must always place the pads, even on a screen exactly as big as the defaults above. */
    private boolean laidOut = false;

    public OscManager() {
        createDefaultElements();
    }

    private void createDefaultElements() {
        // Left Analog Stick for Movement (WASD)
        elements.add(new OscElement("joystick_move", "Движение (WASD)", "WASD",
                40, 520, 190, 0.65f, 0, 0, 0, true));

        // Right Look Area (Mouse camera look)
        elements.add(new OscElement("touch_look", "Область камеры", "LOOK",
                460, 160, 480, 0.40f, 0, 0, 0, true));

        // In-game EDIT button
        elements.add(new OscElement("btn_edit", "EDIT (Настройка)", "EDIT",
                480, 15, 48, 0.70f, 0, 0, 0, true));

        // Primary Action Buttons
        // Fire / Shoot (Mouse Button Left = 1)
        elements.add(new OscElement("btn_fire", "Огонь (ЛКМ)", "FIRE",
                830, 460, 78, 0.80f, 0, 0, 1, true));

        // Aim / Zoom (Mouse Button Right = 3)
        // Sticky (toggle): one tap locks the right mouse button down, a second tap releases it,
        // so the player does not have to keep a finger on AIM while looking around.
        elements.add(new OscElement("btn_aim", "Прицел (ПКМ)", "AIM",
                730, 400, 64, 0.75f, 0, 0, 3, true, true));

        // Jump (Space)
        elements.add(new OscElement("btn_jump", "Прыжок (Space)", "SPACE",
                860, 600, 68, 0.75f, 0, KeyEvent.KEYCODE_SPACE, 0, true));

        // Crouch (C / Left Ctrl) - Far Cry Crouch
        elements.add(new OscElement("btn_crouch", "Присесть (C)", "C",
                750, 620, 60, 0.75f, 0, KeyEvent.KEYCODE_C, 0, true));

        // Prone / Crawl (V) - Far Cry Prone (ACTION_MOVEMODE2)
        elements.add(new OscElement("btn_prone", "Лечь / Ползти (V)", "V",
                670, 620, 56, 0.75f, 0, KeyEvent.KEYCODE_V, 0, true));

        // Left Shift - Run / Sprint / Steady Breath
        elements.add(new OscElement("btn_sprint", "Спринт (Shift)", "SHIFT",
                40, 410, 56, 0.70f, 0, KeyEvent.KEYCODE_SHIFT_LEFT, 0, true));

        // Walk toggle (Z) - Optional Far Cry Walk
        elements.add(new OscElement("btn_walk", "Шаг (Z)", "Z",
                110, 410, 48, 0.60f, 0, KeyEvent.KEYCODE_Z, 0, false));

        // Reload (R)
        elements.add(new OscElement("btn_reload", "Перезарядка (R)", "R",
                850, 320, 56, 0.70f, 0, KeyEvent.KEYCODE_R, 0, true));

        // Use / Action (F)
        elements.add(new OscElement("btn_use", "Действие (F)", "F",
                750, 270, 58, 0.70f, 0, KeyEvent.KEYCODE_F, 0, true));

        // Grenade (G)
        elements.add(new OscElement("btn_grenade", "Граната (G)", "G",
                650, 320, 52, 0.65f, 0, KeyEvent.KEYCODE_G, 0, true));

        // Cycle Grenade Type (H)
        elements.add(new OscElement("btn_cycle_grenade", "Тип гранаты (H)", "H",
                580, 320, 48, 0.60f, 0, KeyEvent.KEYCODE_H, 0, true));

        // Change Fire Mode (X)
        elements.add(new OscElement("btn_firemode", "Режим огня (X)", "X",
                650, 410, 50, 0.65f, 0, KeyEvent.KEYCODE_X, 0, true));

        // Lean Left (Q)
        elements.add(new OscElement("btn_lean_l", "Наклон влево (Q)", "Q",
                200, 440, 48, 0.65f, 0, KeyEvent.KEYCODE_Q, 0, true));

        // Lean Right (E)
        elements.add(new OscElement("btn_lean_r", "Наклон вправо (E)", "E",
                200, 520, 48, 0.65f, 0, KeyEvent.KEYCODE_E, 0, true));

        // Weapon Next (Page Down)
        elements.add(new OscElement("btn_weap_next", "След. оружие (Next)", "NEXT",
                920, 200, 48, 0.65f, 0, KeyEvent.KEYCODE_PAGE_DOWN, 0, true));

        // Weapon Prev (Page Up)
        elements.add(new OscElement("btn_weap_prev", "Пред. оружие (Prev)", "PREV",
                850, 200, 48, 0.65f, 0, KeyEvent.KEYCODE_PAGE_UP, 0, true));

        // Drop Weapon (J)
        elements.add(new OscElement("btn_drop", "Сброс оружия (J)", "DROP",
                780, 200, 46, 0.60f, 0, KeyEvent.KEYCODE_J, 0, false));

        // Quick Weapon Slot selection 1-4 (optional)
        elements.add(new OscElement("btn_slot1", "Слот 1 (1)", "1",
                640, 140, 42, 0.60f, 0, KeyEvent.KEYCODE_1, 0, false));
        elements.add(new OscElement("btn_slot2", "Слот 2 (2)", "2",
                695, 140, 42, 0.60f, 0, KeyEvent.KEYCODE_2, 0, false));
        elements.add(new OscElement("btn_slot3", "Слот 3 (3)", "3",
                750, 140, 42, 0.60f, 0, KeyEvent.KEYCODE_3, 0, false));
        elements.add(new OscElement("btn_slot4", "Слот 4 (4)", "4",
                805, 140, 42, 0.60f, 0, KeyEvent.KEYCODE_4, 0, false));

        // Scoreboard & Objectives (TAB)
        elements.add(new OscElement("btn_tab", "Задачи / КПК (TAB)", "TAB",
                340, 15, 44, 0.60f, 0, KeyEvent.KEYCODE_TAB, 0, true));

        // Flashlight (L)
        elements.add(new OscElement("btn_flashlight", "Фонарик (L)", "L",
                180, 22, 44, 0.60f, 0, KeyEvent.KEYCODE_L, 0, true));

        // Binoculars (B)
        elements.add(new OscElement("btn_binoculars", "Бинокль (B)", "B",
                230, 22, 44, 0.60f, 0, KeyEvent.KEYCODE_B, 0, true));

        // Night Vision / CryVision (T)
        elements.add(new OscElement("btn_nightvision", "ПНВ (T)", "T",
                285, 22, 44, 0.60f, 0, KeyEvent.KEYCODE_T, 0, true));

        // 3rd Person View (F1)
        elements.add(new OscElement("btn_view", "Вид (F1)", "F1",
                395, 22, 42, 0.55f, 0, 131, 0, false)); // 131 = KEYCODE_F1

        // Pause Menu (ESC)
        elements.add(new OscElement("btn_menu", "Меню (Esc)", "ESC",
                20, 22, 44, 0.70f, 0, KeyEvent.KEYCODE_ESCAPE, 0, true));

        // Quick Save (F5)
        elements.add(new OscElement("btn_quicksave", "Сохранить (F5)", "F5",
                75, 22, 42, 0.55f, 0, 135, 0, false)); // Key 135 = F5

        // Quick Load (F9)
        elements.add(new OscElement("btn_quickload", "Загрузить (F9)", "F9",
                125, 22, 42, 0.55f, 0, 139, 0, false)); // Key 139 = F9

        // Console (Tilde ~)
        elements.add(new OscElement("btn_console", "Консоль (~)", "~",
                445, 22, 42, 0.50f, 0, KeyEvent.KEYCODE_GRAVE, 0, true));
    }

    public void init(RelativeLayout container, Context context, boolean isStandaloneEditor) {
        this.container = container;
        this.editMode = isStandaloneEditor;

        loadLayout(context);

        // Add views to container
        for (OscElement el : elements) {
            View v;
            if ("joystick_move".equals(el.id)) {
                v = new OscJoystick(context, el, this);
            } else if ("touch_look".equals(el.id)) {
                v = new OscTouchLook(context, el, this);
            } else {
                v = new OscButton(context, el, this);
            }
            container.addView(v);
        }

        // Add Edit Toolbar View
        editToolbar = new EditToolbarView(context, this);
        RelativeLayout.LayoutParams toolbarLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolbarLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        editToolbar.setLayoutParams(toolbarLp);
        editToolbar.setVisibility(editMode ? View.VISIBLE : View.GONE);
        container.addView(editToolbar);

        // Layout listener for screen size changes
        container.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            int h = b - t;
            if (w > 0 && h > 0 && (!laidOut || w != screenWidth || h != screenHeight)) {
                laidOut = true;
                screenWidth = w;
                screenHeight = h;
                updateAllLayouts();
            }
        });

        // Set initial selected element
        if (editMode && !elements.isEmpty()) {
            selectElement(elements.get(0));
        }
    }

    public void loadLayout(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        mouseSensitivity = prefs.getFloat(KEY_SENSITIVITY, 1.0f);
        boolean hideAll = prefs.getBoolean(KEY_HIDE_ALL, false);

        for (OscElement el : elements) {
            el.load(prefs);
            if (hideAll && !"btn_edit".equals(el.id)) {
                el.visible = false;
            }
        }
    }

    public void saveLayout() {
        if (container == null) return;
        Context ctx = container.getContext();
        SharedPreferences.Editor editor = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        for (OscElement el : elements) {
            el.save(editor);
        }
        editor.apply();
    }

    public void resetLayout() {
        for (OscElement el : elements) {
            el.reset();
        }
        saveLayout();
        updateAllLayouts();
        if (editToolbar != null) {
            editToolbar.updateSelectedInfo(selectedElement);
        }
    }

    public void updateAllLayouts() {
        for (OscElement el : elements) {
            el.updateViewLayout(screenWidth, screenHeight, editMode);
        }
    }

    /**
     * Releases everything the overlay currently holds down - including a toggled (sticky) button
     * such as AIM, which stays pressed after the finger leaves the screen. Called whenever the
     * overlay stops being the input source (Edit Mode, the game losing focus) so that no key or
     * mouse button is left stuck in the pressed state inside the engine.
     */
    public void releaseAllPressed() {
        for (OscElement el : elements) {
            if (el.view instanceof OscButton) {
                ((OscButton) el.view).forceRelease();
            }
        }
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void toggleEditMode() {
        setEditMode(!editMode);
    }

    public void setEditMode(boolean active) {
        this.editMode = active;
        if (active) {
            // A sticky button (AIM) may still hold the right mouse button down: drop it, otherwise
            // the engine keeps seeing "aim" pressed for the whole editing session.
            releaseAllPressed();
        }
        if (editToolbar != null) {
            editToolbar.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (active) {
            if (selectedElement == null && !elements.isEmpty()) {
                selectElement(elements.get(0));
            }
        } else {
            // Deselect on exit and save
            selectElement(null);
            saveLayout();
        }
        updateAllLayouts();
    }

    public void selectElement(OscElement element) {
        selectedElement = element;
        for (OscElement el : elements) {
            if (el.view instanceof OscButton) {
                ((OscButton) el.view).setSelected(el == selectedElement);
            } else if (el.view instanceof OscJoystick) {
                ((OscJoystick) el.view).setSelected(el == selectedElement);
            } else if (el.view instanceof OscTouchLook) {
                ((OscTouchLook) el.view).setSelected(el == selectedElement);
            }
        }
        if (editToolbar != null) {
            editToolbar.updateSelectedInfo(selectedElement);
        }
    }

    public void changeSelectedSize(int deltaDp) {
        if (selectedElement != null) {
            selectedElement.changeSize(deltaDp);
            selectedElement.updateViewLayout(screenWidth, screenHeight, true);
            saveLayout();
            if (editToolbar != null) editToolbar.updateSelectedInfo(selectedElement);
        }
    }

    public void changeSelectedOpacity(float delta) {
        if (selectedElement != null) {
            selectedElement.changeOpacity(delta);
            selectedElement.updateViewLayout(screenWidth, screenHeight, true);
            saveLayout();
            if (editToolbar != null) editToolbar.updateSelectedInfo(selectedElement);
        }
    }

    public void toggleSelectedVisibility() {
        if (selectedElement != null) {
            selectedElement.toggleVisibility();
            selectedElement.updateViewLayout(screenWidth, screenHeight, true);
            saveLayout();
            if (editToolbar != null) editToolbar.updateSelectedInfo(selectedElement);
        }
    }

    public float getMouseSensitivity() {
        return mouseSensitivity;
    }

    public void setMouseSensitivity(float s) {
        this.mouseSensitivity = s;
        if (container != null) {
            container.getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putFloat(KEY_SENSITIVITY, s).apply();
        }
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    public List<OscElement> getElements() {
        return elements;
    }
}
