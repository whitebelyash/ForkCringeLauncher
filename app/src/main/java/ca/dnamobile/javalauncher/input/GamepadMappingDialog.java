/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, PojavLauncher, Zalith Launcher, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.javalauncher.input;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import ca.dnamobile.javalauncher.controls.ControlsPreferences;
import ca.dnamobile.javalauncher.controls.TouchControlsLayoutData;
import ca.dnamobile.javalauncher.controls.TouchControlsStore;
import ca.dnamobile.javalauncher.controls.TouchKeyPickerDialog;
import ca.dnamobile.javalauncher.settings.LauncherPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Dialog that handles the controller and button mappings including mouse dpi etc
public final class GamepadMappingDialog {
    @Nullable private static AlertDialog activeDialog;

    private static final int COLOR_DIALOG_BG = Color.rgb(30, 34, 42);
    private static final int COLOR_CARD_BG = Color.rgb(38, 43, 53);
    private static final int COLOR_CARD_BG_PRESSED = Color.rgb(43, 49, 60);
    private static final int COLOR_CARD_STROKE = Color.rgb(54, 61, 74);
    private static final int COLOR_TEXT_PRIMARY = Color.rgb(238, 241, 248);
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(198, 204, 216);
    private static final int COLOR_TEXT_MUTED = Color.rgb(150, 159, 176);
    private static final int COLOR_ACCENT = Color.rgb(37, 211, 128);
    private static final int COLOR_ACCENT_MUTED = Color.rgb(86, 135, 110);

    private static final float DIALOG_DIM_NORMAL = 0.58f;
    private static final float DIALOG_DIM_PREVIEW = 0.02f;
    private static final float DIALOG_ALPHA_VISIBLE = 1.0f;
    private static final float DIALOG_ALPHA_HITBOX_PREVIEW = 0.12f;

    public interface OnSettingsSavedListener {
        void onSettingsSaved();
    }

    private GamepadMappingDialog() {
    }

    public static void show(@NonNull Activity activity) {
        show(activity, null);
    }

    public static void show(@NonNull Activity activity, @Nullable OnSettingsSavedListener listener) {
        GamepadMappingStore store = GamepadMappingStore.get(activity);

        final boolean originalHotbarDebug = ControlsPreferences.isHotbarHitboxDebugEnabled(activity);
        final int originalHotbarGuiScaleOverride = ControlsPreferences.getHotbarGuiScaleOverride(activity);
        final int originalMouseDpiScale = store.getHardwareMouseDpiScale();
        final boolean[] saved = new boolean[]{false};

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(false);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_DIALOG_BG);
        scrollView.setBackgroundColor(COLOR_DIALOG_BG);
        int padding = dp(activity, 18);
        root.setPadding(padding, padding, padding, dp(activity, 8));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(activity);
        title.setText("In-game Button Overlay");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setPadding(dp(activity, 2), 0, dp(activity, 2), dp(activity, 6));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView info = new TextView(activity);
        info.setText("Configure the launcher-side controller bridge, visual menu cursor, hotbar touch hitbox, and floating in-game overlay. "
                + "Mappings are saved to the selected controller profile when Android reports a physical controller.");
        info.setTextSize(14);
        info.setTextColor(COLOR_TEXT_SECONDARY);
        info.setPadding(dp(activity, 2), 0, dp(activity, 2), dp(activity, 12));
        root.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Map<GamepadButton, ActionSlotView[]> gameSlots = new EnumMap<>(GamepadButton.class);
        Map<GamepadButton, ActionSlotView[]> menuSlots = new EnumMap<>(GamepadButton.class);

        // Controller profile card.
        LinearLayout profileCard = addCard(activity, root);
        addCardTitle(activity, profileCard, "Controller profile");
        TextView profileInfo = addInfoText(activity, profileCard,
                "Use the attached controller profile below. If Android cannot identify a controller, the default profile is used.");

        List<DeviceProfile> profiles = discoverProfiles(activity, store);
        Spinner profileSpinner = new Spinner(activity);
        ArrayAdapter<DeviceProfile> profileAdapter = darkAdapter(activity, profiles);
        profileSpinner.setAdapter(profileAdapter);
        profileSpinner.setSelection(preferredProfileIndex(profiles, store.getActiveProfileKey()));
        profileCard.addView(profileSpinner, matchWrapWithTopMargin(activity, 6));
        profileInfo.setVisibility(profiles.size() > 1 ? View.VISIBLE : View.GONE);

        LinearLayout transferRow = new LinearLayout(activity);
        transferRow.setOrientation(LinearLayout.HORIZONTAL);
        transferRow.setGravity(Gravity.CENTER_VERTICAL);
        transferRow.setPadding(0, dp(activity, 8), 0, 0);

        Button exportProfile = styledSmallButton(activity, "Export selected profile");
        Button importProfile = styledSmallButton(activity, "Import into selected profile");
        transferRow.addView(exportProfile, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        importParams.leftMargin = dp(activity, 8);
        transferRow.addView(importProfile, importParams);
        profileCard.addView(transferRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        addSmallHint(activity, profileCard, "Exports/imports the currently selected controller profile as a portable DroidBridge gamepad mapping JSON file.");

        exportProfile.setOnClickListener(view -> {
            String profileKey = selectedProfileKey(profiles, profileSpinner);
            saveSection(store, profileKey, false, menuSlots);
            saveSection(store, profileKey, true, gameSlots);
            store.setActiveProfileKey(profileKey);
            GamepadMappingTransferActivity.startExport(activity, profileKey);
        });
        importProfile.setOnClickListener(view -> {
            String profileKey = selectedProfileKey(profiles, profileSpinner);
            store.setActiveProfileKey(profileKey);
            GamepadMappingTransferActivity.startImport(activity, profileKey);
        });

        // Mapping cards live directly under the active controller profile so users can change
        // the attached controller's mappings before the general overlay/hotbar settings.
        LinearLayout menuMappingCard = addCard(activity, root);
        LinearLayout menuMappingContent = new LinearLayout(activity);
        menuMappingContent.setOrientation(LinearLayout.VERTICAL);
        addCollapsibleHeader(activity, menuMappingCard, "Menu mappings", menuMappingContent, false);
        addInfoText(activity, menuMappingContent,
                "Used in Minecraft menus. D-pad cursor movement now only repeats when the selected D-pad action is a Cursor action.");
        addSection(activity, menuMappingContent, false, store, selectedProfileKey(profiles, profileSpinner), menuSlots);
        menuMappingCard.addView(menuMappingContent);

        LinearLayout gameMappingCard = addCard(activity, root);
        LinearLayout gameMappingContent = new LinearLayout(activity);
        gameMappingContent.setOrientation(LinearLayout.VERTICAL);
        addCollapsibleHeader(activity, gameMappingCard, "In-game mappings", gameMappingContent, false);
        addInfoText(activity, gameMappingContent,
                "Used while Minecraft has grabbed the mouse or when Force in-game mappings is enabled.");
        addSection(activity, gameMappingContent, true, store, selectedProfileKey(profiles, profileSpinner), gameSlots);
        gameMappingCard.addView(gameMappingContent);

        // General overlay card.
        LinearLayout overlayCard = addCard(activity, root);
        addCardTitle(activity, overlayCard, "Overlay behavior");

        CheckBox forceGameMode = addCheckBox(activity, overlayCard, "Force in-game mappings", store.isForceGameMode());
        addSmallHint(activity, overlayCard, "Only turn this on after entering a world if camera/WASD input does not start automatically.");

        CheckBox showCursorOverlay = addCheckBox(activity, overlayCard, "Show controller cursor overlay in menus", store.isShowCursorOverlay());
        addSmallHint(activity, overlayCard, "Visual only. It no longer becomes a touch target, so screen taps and Touch Controller input pass through to Minecraft.");
        CheckBox showFloatingSettingsButton = addCheckBox(activity, overlayCard, "Show floating settings button", LauncherPreferences.isShowInGameSettingsButton(activity));
        CheckBox showLogOverlay = addCheckBox(activity, overlayCard, "Show latest log on the left side", LauncherPreferences.isShowGameLogOverlay(activity));

        addPlainLabel(activity, overlayCard, "Touch controls layout");
        List<File> touchLayouts = TouchControlsStore.listLayouts(activity);
        ArrayList<String> touchLayoutLabels = new ArrayList<>();
        String selectedLayoutPath = ControlsPreferences.getSelectedLayoutPath(activity);
        int selectedLayoutIndex = 0;
        for (int i = 0; i < touchLayouts.size(); i++) {
            File file = touchLayouts.get(i);
            TouchControlsLayoutData data = TouchControlsStore.loadLayout(file);
            String label = data.name + "  •  " + file.getName();
            touchLayoutLabels.add(label);
            if (selectedLayoutPath != null && file.getAbsolutePath().equals(selectedLayoutPath)) {
                selectedLayoutIndex = i;
            }
        }
        if (touchLayoutLabels.isEmpty()) {
            File fallback = TouchControlsStore.getDefaultLayoutFile(activity);
            touchLayouts.add(fallback);
            touchLayoutLabels.add("Default Touch Controls  •  " + fallback.getName());
        }

        Spinner touchLayoutSpinner = new Spinner(activity);
        ArrayAdapter<String> touchLayoutAdapter = darkAdapter(activity, touchLayoutLabels);
        touchLayoutSpinner.setAdapter(touchLayoutAdapter);
        touchLayoutSpinner.setSelection(Math.max(0, Math.min(selectedLayoutIndex, touchLayouts.size() - 1)), false);
        overlayCard.addView(touchLayoutSpinner, matchWrapWithTopMargin(activity, 2));
        addSmallHint(activity, overlayCard, "Switch the active on-screen touch layout while the game is running. This keeps the same dialog design and only changes the selected layout.");

        TextView menuSensitivityLabel = addSensitivityControl(
                activity,
                overlayCard,
                "Menu cursor sensitivity",
                store.getMenuCursorSensitivity()
        );
        SeekBar menuSensitivity = addSensitivitySeekBar(activity, overlayCard, store.getMenuCursorSensitivity(), menuSensitivityLabel, "Menu cursor sensitivity");

        TextView gameSensitivityLabel = addSensitivityControl(
                activity,
                overlayCard,
                "In-game camera sensitivity",
                store.getGameCameraSensitivity()
        );
        SeekBar gameSensitivity = addSensitivitySeekBar(activity, overlayCard, store.getGameCameraSensitivity(), gameSensitivityLabel, "In-game camera sensitivity");

        TextView mouseDpiLabel = addMouseDpiControl(
                activity,
                overlayCard,
                "Hardware mouse DPI scale",
                store.getHardwareMouseDpiScale()
        );
        SeekBar mouseDpiScale = addMouseDpiSeekBar(activity, overlayCard, store, store.getHardwareMouseDpiScale(), mouseDpiLabel, "Hardware mouse DPI scale");
        addSmallHint(activity, overlayCard, "This updates live while the dialog is open, and only affects real mouse/captured-pointer movement, not touch camera swipes or absolute menu taps.");

        TextView resolutionScaleLabel = addResolutionScaleControl(
                activity,
                overlayCard,
                "Game resolution scale",
                LauncherPreferences.getGameResolutionScalePercent(activity)
        );
        SeekBar resolutionScale = addResolutionScaleSeekBar(
                activity,
                overlayCard,
                LauncherPreferences.getGameResolutionScalePercent(activity),
                resolutionScaleLabel,
                "Game resolution scale",
                listener
        );
        addSmallHint(activity, overlayCard, "Lower this for better FPS, raise it for sharper rendering. The live change is applied only when you release the slider so the game surface is not resized repeatedly while dragging.");

        // Hotbar card.
        LinearLayout hotbarCard = addCard(activity, root);
        addCardTitle(activity, hotbarCard, "Hotbar touch hitbox");
        addInfoText(activity, hotbarCard,
                "Turn on the debug box to see the launcher hotbar touch area while in game. "
                        + "Auto match Minecraft follows Minecraft\'s current GUI scale when the hitbox is calculated. "
                        + "Use a manual value only if a specific version/device needs an override.");

        CheckBox showHotbarHitbox = addCheckBox(activity, hotbarCard, "Show hotbar hitbox debug box", originalHotbarDebug);

        String[] guiScaleLabels = new String[]{"Auto match Minecraft", "2", "3", "4", "5", "6", "7", "8"};
        int[] guiScaleValues = new int[]{0, 2, 3, 4, 5, 6, 7, 8};
        TextView guiScaleLabel = addPlainLabel(activity, hotbarCard, "Hotbar GUI scale");
        Spinner guiScaleSpinner = new Spinner(activity);
        ArrayAdapter<String> guiScaleAdapter = darkAdapter(activity, Arrays.asList(guiScaleLabels));
        guiScaleSpinner.setAdapter(guiScaleAdapter);
        guiScaleSpinner.setSelection(findGuiScaleIndex(guiScaleValues, ControlsPreferences.getHotbarGuiScaleOverride(activity)));
        hotbarCard.addView(guiScaleSpinner, matchWrapWithTopMargin(activity, 2));

        final boolean[] guiScaleSpinnerReady = new boolean[]{false};
        guiScaleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!guiScaleSpinnerReady[0]) {
                    guiScaleSpinnerReady[0] = true;
                    return;
                }

                ControlsPreferences.setHotbarGuiScaleOverride(
                        activity,
                        selectedGuiScaleValue(guiScaleValues, guiScaleSpinner)
                );
                notifySettingsChanged(activity, listener);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        TextView hotbarWidthLabel = addFloatControl(activity, hotbarCard, "Hitbox width GUI px", ControlsPreferences.getHotbarWidthGui(activity));
        SeekBar hotbarWidth = addFloatSeekBar(activity, hotbarCard, 90, 260, ControlsPreferences.getHotbarWidthGui(activity), hotbarWidthLabel, "Hitbox width GUI px");

        TextView hotbarHeightLabel = addFloatControl(activity, hotbarCard, "Hitbox height GUI px", ControlsPreferences.getHotbarHeightGui(activity));
        SeekBar hotbarHeight = addFloatSeekBar(activity, hotbarCard, 12, 60, ControlsPreferences.getHotbarHeightGui(activity), hotbarHeightLabel, "Hitbox height GUI px");

        TextView hotbarXOffsetLabel = addFloatControl(activity, hotbarCard, "X offset dp", ControlsPreferences.getHotbarXOffsetDp(activity));
        SeekBar hotbarXOffset = addFloatSeekBar(activity, hotbarCard, -160, 160, ControlsPreferences.getHotbarXOffsetDp(activity), hotbarXOffsetLabel, "X offset dp");

        TextView hotbarYOffsetLabel = addFloatControl(activity, hotbarCard, "Y offset dp", ControlsPreferences.getHotbarYOffsetDp(activity));
        SeekBar hotbarYOffset = addFloatSeekBar(activity, hotbarCard, -80, 160, ControlsPreferences.getHotbarYOffsetDp(activity), hotbarYOffsetLabel, "Y offset dp");

        TextView hotbarPaddingLabel = addFloatControl(activity, hotbarCard, "Vertical padding dp", ControlsPreferences.getHotbarVerticalPaddingDp(activity));
        SeekBar hotbarPadding = addFloatSeekBar(activity, hotbarCard, 0, 80, ControlsPreferences.getHotbarVerticalPaddingDp(activity), hotbarPaddingLabel, "Vertical padding dp");

        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String profileKey = selectedProfileKey(profiles, profileSpinner);
                store.setActiveProfileKey(profileKey);
                applySectionSelections(store, profileKey, false, menuSlots);
                applySectionSelections(store, profileKey, true, gameSlots);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Live preview only for the debug box so it appears immediately in-game.
        showHotbarHitbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ControlsPreferences.setHotbarHitboxDebugEnabled(activity, isChecked);
            notifySettingsChanged(activity, listener);
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(scrollView)
                .setPositiveButton("Save", (dialogInterface, which) -> {
                    saved[0] = true;
                    String profileKey = selectedProfileKey(profiles, profileSpinner);
                    store.setActiveProfileKey(profileKey);
                    int selectedTouchLayout = Math.max(0, touchLayoutSpinner.getSelectedItemPosition());
                    if (!touchLayouts.isEmpty() && selectedTouchLayout < touchLayouts.size()) {
                        ControlsPreferences.setSelectedLayoutPath(activity, touchLayouts.get(selectedTouchLayout).getAbsolutePath());
                    }
                    store.setForceGameMode(forceGameMode.isChecked());
                    store.setShowCursorOverlay(showCursorOverlay.isChecked());
                    LauncherPreferences.setShowInGameSettingsButton(activity, showFloatingSettingsButton.isChecked());
                    LauncherPreferences.setShowGameLogOverlay(activity, showLogOverlay.isChecked());
                    store.setMenuCursorSensitivity(progressToSensitivity(menuSensitivity.getProgress()));
                    store.setGameCameraSensitivity(progressToSensitivity(gameSensitivity.getProgress()));
                    store.setHardwareMouseDpiScale(progressToMouseDpi(mouseDpiScale.getProgress()));
                    LauncherPreferences.setGameResolutionScalePercent(activity, progressToResolutionScale(resolutionScale.getProgress()));
                    ControlsPreferences.setHotbarHitboxDebugEnabled(activity, showHotbarHitbox.isChecked());
                    ControlsPreferences.setHotbarGuiScaleOverride(activity, selectedGuiScaleValue(guiScaleValues, guiScaleSpinner));
                    ControlsPreferences.setHotbarWidthGui(activity, progressToFloat(hotbarWidth.getProgress(), 90));
                    ControlsPreferences.setHotbarHeightGui(activity, progressToFloat(hotbarHeight.getProgress(), 12));
                    ControlsPreferences.setHotbarXOffsetDp(activity, progressToFloat(hotbarXOffset.getProgress(), -160));
                    ControlsPreferences.setHotbarYOffsetDp(activity, progressToFloat(hotbarYOffset.getProgress(), -80));
                    ControlsPreferences.setHotbarVerticalPaddingDp(activity, progressToFloat(hotbarPadding.getProgress(), 0));
                    saveSection(store, profileKey, true, gameSlots);
                    saveSection(store, profileKey, false, menuSlots);
                    notifySettingsChanged(activity, listener);
                })
                .setNeutralButton("Reset defaults", (dialogInterface, which) -> {
                    saved[0] = true;
                    store.resetDefaults();
                    ControlsPreferences.resetHotbarHitboxSettings(activity);
                    LauncherPreferences.setGameResolutionScalePercent(activity, LauncherPreferences.DEFAULT_GAME_RESOLUTION_SCALE_PERCENT);
                    notifySettingsChanged(activity, listener);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        activeDialog = dialog;
        dialog.setOnDismissListener(dismissed -> {
            setActiveDialogHitboxPreviewAlpha(false);
            setActiveDialogPreviewAlpha(false);
            if (!saved[0]) {
                ControlsPreferences.setHotbarHitboxDebugEnabled(activity, originalHotbarDebug);
                ControlsPreferences.setHotbarGuiScaleOverride(activity, originalHotbarGuiScaleOverride);
                store.setHardwareMouseDpiScale(originalMouseDpiScale);
                // Resolution scale is applied live so the running game can resize
                // immediately. Do not roll it back on Cancel/dismiss, otherwise the
                // overlay appears to save 100% but the next launch returns to the old
                // main-settings value.
                notifySettingsChanged(activity, listener);
            }
            activeDialog = null;
        });
        dialog.show();
        styleDialogChrome(activity, dialog);
    }

    private static void styleDialogChrome(@NonNull Activity activity, @NonNull AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(roundedDrawable(activity, COLOR_DIALOG_BG, COLOR_DIALOG_BG, 22));
            window.setDimAmount(DIALOG_DIM_NORMAL);
        }

        tintDialogButton(dialog, AlertDialog.BUTTON_POSITIVE);
        tintDialogButton(dialog, AlertDialog.BUTTON_NEGATIVE);
        tintDialogButton(dialog, AlertDialog.BUTTON_NEUTRAL);
    }

    private static void tintDialogButton(@NonNull AlertDialog dialog, int whichButton) {
        TextView button = dialog.getButton(whichButton);
        if (button != null) {
            button.setTextColor(COLOR_ACCENT);
        }
    }

    @NonNull
    private static GradientDrawable roundedDrawable(
            @NonNull Activity activity,
            int fillColor,
            int strokeColor,
            int cornerDp
    ) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius(dp(activity, cornerDp));
        bg.setStroke(dp(activity, 1), strokeColor);
        return bg;
    }

    @NonNull
    private static <T> ArrayAdapter<T> darkAdapter(@NonNull Activity activity, @NonNull List<T> items) {
        ArrayAdapter<T> adapter = new ArrayAdapter<T>(activity, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleSpinnerText(view, false);
                return view;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                styleSpinnerText(view, true);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private static void styleSpinnerText(@NonNull View view, boolean dropdown) {
        view.setBackgroundColor(dropdown ? COLOR_CARD_BG_PRESSED : Color.TRANSPARENT);
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            textView.setTextColor(COLOR_TEXT_PRIMARY);
            textView.setTextSize(15);
            textView.setSingleLine(false);
            textView.setPadding(textView.getPaddingLeft(), dpFromView(textView, 8), textView.getPaddingRight(), dpFromView(textView, 8));
        }
    }

    private static void tintCheckBox(@NonNull CheckBox box) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        int[] colors = new int[]{COLOR_ACCENT, COLOR_TEXT_MUTED};
        box.setButtonTintList(new ColorStateList(states, colors));
    }

    private static void tintSeekBar(@NonNull SeekBar seekBar) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        seekBar.setThumbTintList(ColorStateList.valueOf(COLOR_ACCENT));
        seekBar.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(COLOR_CARD_STROKE));
    }

    private static int dpFromView(@NonNull View view, int value) {
        float density = view.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    @NonNull
    private static LinearLayout addCard(@NonNull Activity activity, @NonNull LinearLayout root) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(activity, 14);
        card.setPadding(p, p, p, p);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD_BG);
        bg.setCornerRadius(dp(activity, 18));
        bg.setStroke(dp(activity, 1), COLOR_CARD_STROKE);
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 0, dp(activity, 12));
        root.addView(card, lp);
        return card;
    }

    private static void addCardTitle(@NonNull Activity activity, @NonNull LinearLayout root, @NonNull String title) {
        TextView header = new TextView(activity);
        header.setText(title);
        header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(COLOR_TEXT_PRIMARY);
        header.setPadding(0, 0, 0, dp(activity, 8));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private static void addCollapsibleHeader(
            @NonNull Activity activity,
            @NonNull LinearLayout card,
            @NonNull String title,
            @NonNull LinearLayout content,
            boolean expanded
    ) {
        TextView header = new TextView(activity);
        header.setText((expanded ? "▾  " : "▸  ") + title);
        header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(COLOR_TEXT_PRIMARY);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(activity, 8));
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        header.setOnClickListener(v -> {
            boolean nowVisible = content.getVisibility() != View.VISIBLE;
            content.setVisibility(nowVisible ? View.VISIBLE : View.GONE);
            header.setText((nowVisible ? "▾  " : "▸  ") + title);
        });
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    @NonNull
    private static TextView addInfoText(@NonNull Activity activity, @NonNull LinearLayout root, @NonNull String text) {
        TextView info = new TextView(activity);
        info.setText(text);
        info.setTextSize(13);
        info.setTextColor(COLOR_TEXT_SECONDARY);
        info.setPadding(0, 0, 0, dp(activity, 8));
        root.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return info;
    }

    private static void addSmallHint(@NonNull Activity activity, @NonNull LinearLayout root, @NonNull String text) {
        TextView hint = new TextView(activity);
        hint.setText(text);
        hint.setTextSize(12);
        hint.setTextColor(COLOR_TEXT_MUTED);
        hint.setPadding(dp(activity, 32), 0, 0, dp(activity, 6));
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    @NonNull
    private static Button styledSmallButton(@NonNull Activity activity, @NonNull String label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(COLOR_ACCENT);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
        button.setBackground(roundedDrawable(activity, COLOR_CARD_BG, COLOR_ACCENT_MUTED, 14));
        return button;
    }

    @NonNull
    private static CheckBox addCheckBox(
            @NonNull Activity activity,
            @NonNull LinearLayout root,
            @NonNull String label,
            boolean checked
    ) {
        CheckBox box = new CheckBox(activity);
        box.setText(label);
        box.setTextSize(15);
        box.setTextColor(COLOR_TEXT_SECONDARY);
        box.setChecked(checked);
        tintCheckBox(box);
        root.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return box;
    }

    @NonNull
    private static TextView addPlainLabel(
            @NonNull Activity activity,
            @NonNull LinearLayout root,
            @NonNull String title
    ) {
        TextView label = new TextView(activity);
        label.setText(title);
        label.setTextSize(15);
        label.setTextColor(COLOR_TEXT_SECONDARY);
        label.setPadding(0, dp(activity, 10), 0, dp(activity, 2));
        root.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return label;
    }

    @NonNull
    private static TextView addFloatControl(
            @NonNull Activity activity,
            @NonNull LinearLayout root,
            @NonNull String title,
            float value
    ) {
        TextView label = addPlainLabel(activity, root, title + ": " + Math.round(value));
        return label;
    }

    @NonNull
    private static SeekBar addFloatSeekBar(
            @NonNull Activity activity,
            @NonNull LinearLayout root,
            int min,
            int max,
            float value,
            @NonNull TextView label,
            @NonNull String title
    ) {
        SeekBar seekBar = new SeekBar(activity);
        tintSeekBar(seekBar);
        seekBar.setMax(Math.max(1, max - min));
        seekBar.setProgress(floatToProgress(value, min, max));

        label.setOnClickListener(v -> showNumberInputDialog(
                activity,
                title,
                Math.round(progressToFloat(seekBar.getProgress(), min)),
                min,
                max,
                "",
                newValue -> {
                    seekBar.setProgress(floatToProgress(newValue, min, max));
                    label.setText(title + ": " + newValue);
                }
        ));
        label.setText(title + ": " + Math.round(progressToFloat(seekBar.getProgress(), min)));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(title + ": " + Math.round(progressToFloat(progress, min)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                setActiveDialogHitboxPreviewAlpha(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setActiveDialogHitboxPreviewAlpha(false);
            }
        });
        root.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return seekBar;
    }

    private static int floatToProgress(float value, int min, int max) {
        return Math.max(0, Math.min(max - min, Math.round(value) - min));
    }

    private static float progressToFloat(int progress, int min) {
        return min + progress;
    }

    @NonNull
    private static TextView addSensitivityControl(
            @NonNull Activity activity,
            @NonNull LinearLayout root,
            @NonNull String title,
            int sensitivity
    ) {
        return addPlainLabel(activity, root, title + ": " + sensitivity + "%");
    }

    @NonNull
    private static SeekBar addSensitivitySeekBar(
            @NonNull Activity activity,
            @NonNull LinearLayout root,
            int sensitivity,
            @NonNull TextView label,
            @NonNull String title
    ) {
        SeekBar seekBar = new SeekBar(activity);
        tintSeekBar(seekBar);
        seekBar.setMax(GamepadMappingStore.MAX_SENSITIVITY - GamepadMappingStore.MIN_SENSITIVITY);
        seekBar.setProgress(sensitivityToProgress(sensitivity));

        label.setOnClickListener(v -> showNumberInputDialog(
                activity,
                title,
                progressToSensitivity(seekBar.getProgress()),
                GamepadMappingStore.MIN_SENSITIVITY,
                GamepadMappingStore.MAX_SENSITIVITY,
                "%",
                newValue -> {
                    seekBar.setProgress(sensitivityToProgress(newValue));
                    label.setText(title + ": " + newValue + "%");
                }
        ));
        label.setText(title + ": " + progressToSensitivity(seekBar.getProgress()) + "%");

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(title + ": " + progressToSensitivity(progress) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                setActiveDialogPreviewAlpha(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setActiveDialogPreviewAlpha(false);
            }
        });
        root.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return seekBar;
    }

    /**
     * Used by non-visual tuning sliders such as cursor sensitivity, camera
     * sensitivity, and hardware mouse DPI. These should keep the dialog fully
     * visible because the user is not trying to line up a visual hitbox under it.
     */
    private static void setActiveDialogPreviewAlpha(boolean previewing) {
        AlertDialog dialog = activeDialog;
        if (dialog == null || dialog.getWindow() == null) return;
        dialog.getWindow().setDimAmount(DIALOG_DIM_NORMAL);
        dialog.getWindow().getDecorView().setAlpha(DIALOG_ALPHA_VISIBLE);
    }

    /**
     * Used only by visual-preview sliders: game resolution scale and the hotbar
     * hitbox size/offset/padding sliders. Fading the dialog lets the user see
     * the hotbar debug box while dragging these controls.
     */
    private static void setActiveDialogHitboxPreviewAlpha(boolean previewing) {
        AlertDialog dialog = activeDialog;
        if (dialog == null || dialog.getWindow() == null) return;
        dialog.getWindow().setDimAmount(previewing ? DIALOG_DIM_PREVIEW : DIALOG_DIM_NORMAL);
        dialog.getWindow().getDecorView().setAlpha(previewing ? DIALOG_ALPHA_HITBOX_PREVIEW : DIALOG_ALPHA_VISIBLE);
    }

    private static int sensitivityToProgress(int sensitivity) {
        return Math.max(0, Math.min(
                GamepadMappingStore.MAX_SENSITIVITY - GamepadMappingStore.MIN_SENSITIVITY,
                sensitivity - GamepadMappingStore.MIN_SENSITIVITY
        ));
    }

    private static int progressToSensitivity(int progress) {
        return GamepadMappingStore.MIN_SENSITIVITY + progress;
    }

    @NonNull
    private static TextView addMouseDpiControl(
            @NonNull Activity activity,
            @NonNull LinearLayout root,
            @NonNull String title,
            int dpiScale
    ) {
        return addPlainLabel(activity, root, title + ": " + dpiScale + "%");
    }

    @NonNull
    private static SeekBar addMouseDpiSeekBar(
            @NonNull Activity activity,
            @NonNull LinearLayout root,
            @NonNull GamepadMappingStore store,
            int dpiScale,
            @NonNull TextView label,
            @NonNull String title
    ) {
        SeekBar seekBar = new SeekBar(activity);
        tintSeekBar(seekBar);
        seekBar.setMax(GamepadMappingStore.MAX_MOUSE_DPI_SCALE - GamepadMappingStore.MIN_MOUSE_DPI_SCALE);
        seekBar.setProgress(mouseDpiToProgress(dpiScale));

        label.setOnClickListener(v -> showNumberInputDialog(
                activity,
                title,
                progressToMouseDpi(seekBar.getProgress()),
                GamepadMappingStore.MIN_MOUSE_DPI_SCALE,
                GamepadMappingStore.MAX_MOUSE_DPI_SCALE,
                "%",
                newValue -> {
                    seekBar.setProgress(mouseDpiToProgress(newValue));
                    label.setText(title + ": " + newValue + "%");
                    store.setHardwareMouseDpiScale(newValue);
                }
        ));
        label.setText(title + ": " + progressToMouseDpi(seekBar.getProgress()) + "%");

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int dpi = progressToMouseDpi(progress);
                label.setText(title + ": " + dpi + "%");
                if (fromUser) {
                    store.setHardwareMouseDpiScale(dpi);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                setActiveDialogPreviewAlpha(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setActiveDialogPreviewAlpha(false);
            }
        });
        root.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return seekBar;
    }

    private static int mouseDpiToProgress(int dpiScale) {
        return Math.max(0, Math.min(
                GamepadMappingStore.MAX_MOUSE_DPI_SCALE - GamepadMappingStore.MIN_MOUSE_DPI_SCALE,
                dpiScale - GamepadMappingStore.MIN_MOUSE_DPI_SCALE
        ));
    }

    private static int progressToMouseDpi(int progress) {
        return GamepadMappingStore.MIN_MOUSE_DPI_SCALE + progress;
    }

    @NonNull
    private static TextView addResolutionScaleControl(
            @NonNull Activity activity,
            @NonNull LinearLayout root,
            @NonNull String title,
            int resolutionScale
    ) {
        return addPlainLabel(activity, root, title + ": " + LauncherPreferences.clampGameResolutionScalePercent(resolutionScale) + "%");
    }

    @NonNull
    private static SeekBar addResolutionScaleSeekBar(
            @NonNull Activity activity,
            @NonNull LinearLayout root,
            int resolutionScale,
            @NonNull TextView label,
            @NonNull String title,
            @Nullable OnSettingsSavedListener listener
    ) {
        SeekBar seekBar = new SeekBar(activity);
        tintSeekBar(seekBar);
        seekBar.setMax(LauncherPreferences.MAX_GAME_RESOLUTION_SCALE_PERCENT - LauncherPreferences.MIN_GAME_RESOLUTION_SCALE_PERCENT);
        seekBar.setProgress(resolutionScaleToProgress(resolutionScale));

        label.setOnClickListener(v -> showNumberInputDialog(
                activity,
                title,
                progressToResolutionScale(seekBar.getProgress()),
                LauncherPreferences.MIN_GAME_RESOLUTION_SCALE_PERCENT,
                LauncherPreferences.MAX_GAME_RESOLUTION_SCALE_PERCENT,
                "%",
                newValue -> {
                    int clamped = LauncherPreferences.clampGameResolutionScalePercent(newValue);
                    seekBar.setProgress(resolutionScaleToProgress(clamped));
                    applyGameResolutionScale(activity, clamped, label, title, listener);
                }
        ));
        label.setText(title + ": " + progressToResolutionScale(seekBar.getProgress()) + "%");

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(title + ": " + progressToResolutionScale(progress) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                setActiveDialogHitboxPreviewAlpha(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                applyGameResolutionScale(activity, progressToResolutionScale(seekBar.getProgress()), label, title, listener);
                setActiveDialogHitboxPreviewAlpha(false);
            }
        });
        root.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return seekBar;
    }

    private static void applyGameResolutionScale(
            @NonNull Activity activity,
            int resolutionScale,
            @NonNull TextView label,
            @NonNull String title,
            @Nullable OnSettingsSavedListener listener
    ) {
        int clamped = LauncherPreferences.clampGameResolutionScalePercent(resolutionScale);
        LauncherPreferences.setGameResolutionScalePercent(activity, clamped);
        label.setText(title + ": " + clamped + "%");
        notifySettingsChanged(activity, listener);
    }

    private static int resolutionScaleToProgress(int resolutionScale) {
        return Math.max(0, Math.min(
                LauncherPreferences.MAX_GAME_RESOLUTION_SCALE_PERCENT - LauncherPreferences.MIN_GAME_RESOLUTION_SCALE_PERCENT,
                LauncherPreferences.clampGameResolutionScalePercent(resolutionScale) - LauncherPreferences.MIN_GAME_RESOLUTION_SCALE_PERCENT
        ));
    }

    private static int progressToResolutionScale(int progress) {
        return LauncherPreferences.clampGameResolutionScalePercent(
                LauncherPreferences.MIN_GAME_RESOLUTION_SCALE_PERCENT + progress
        );
    }

    private interface NumberValueCallback {
        void onValueSelected(int value);
    }

    private static void showNumberInputDialog(
            @NonNull Activity activity,
            @NonNull String title,
            int currentValue,
            int min,
            int max,
            @NonNull String suffix,
            @NonNull NumberValueCallback callback
    ) {
        EditText input = new EditText(activity);
        input.setText(String.valueOf(currentValue));
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setTextColor(COLOR_TEXT_PRIMARY);
        input.setHintTextColor(COLOR_TEXT_MUTED);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10));

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 18);
        container.setPadding(padding, dp(activity, 8), padding, 0);

        TextView hint = new TextView(activity);
        hint.setText("Enter a value from " + min + " to " + max + suffix + ".");
        hint.setTextSize(13);
        hint.setTextColor(COLOR_TEXT_SECONDARY);
        hint.setPadding(0, 0, 0, dp(activity, 8));

        container.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog numberDialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(container)
                .setPositiveButton("Apply", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        numberDialog.setOnShowListener(dialog -> {
            styleDialogChrome(activity, numberDialog);

            input.requestFocus();
            Window window = numberDialog.getWindow();
            if (window != null) {
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }

            TextView positiveButton = numberDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positiveButton != null) {
                positiveButton.setOnClickListener(v -> {
                    String raw = input.getText() == null ? "" : input.getText().toString().trim();
                    if (raw.isEmpty() || "-".equals(raw)) {
                        Toast.makeText(activity, "Enter a number.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    try {
                        int value = Integer.parseInt(raw);
                        int clamped = Math.max(min, Math.min(max, value));
                        callback.onValueSelected(clamped);
                        numberDialog.dismiss();
                    } catch (NumberFormatException e) {
                        Toast.makeText(activity, "Enter a valid number.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        numberDialog.show();
    }

    private static void addSection(
            @NonNull Activity activity,
            @NonNull LinearLayout root,
            boolean gameMode,
            @NonNull GamepadMappingStore store,
            @NonNull String profileKey,
            @NonNull Map<GamepadButton, ActionSlotView[]> out
    ) {
        ArrayList<String> extraSlotLabels = new ArrayList<>();
        extraSlotLabels.add("Extra mappings hidden");
        for (int slot = 1; slot < GamepadMappingStore.MAX_ACTION_SLOTS; slot++) {
            extraSlotLabels.add("Show position " + slot);
        }

        for (GamepadButton button : GamepadButton.values()) {
            LinearLayout group = new LinearLayout(activity);
            group.setOrientation(LinearLayout.VERTICAL);
            group.setPadding(0, dp(activity, 6), 0, dp(activity, 8));

            ActionSlotView[] slots = new ActionSlotView[GamepadMappingStore.MAX_ACTION_SLOTS];

            LinearLayout primaryRow = new LinearLayout(activity);
            primaryRow.setOrientation(LinearLayout.HORIZONTAL);
            primaryRow.setGravity(Gravity.CENTER_VERTICAL);
            primaryRow.setPadding(0, dp(activity, 2), 0, dp(activity, 2));

            LinearLayout buttonColumn = new LinearLayout(activity);
            buttonColumn.setOrientation(LinearLayout.VERTICAL);
            buttonColumn.setGravity(Gravity.CENTER_VERTICAL);

            TextView buttonLabel = new TextView(activity);
            buttonLabel.setText(button.toString());
            buttonLabel.setTextSize(14);
            buttonLabel.setTypeface(Typeface.DEFAULT_BOLD);
            buttonLabel.setTextColor(COLOR_TEXT_SECONDARY);

            TextView positionZeroLabel = new TextView(activity);
            positionZeroLabel.setText("Position 0");
            positionZeroLabel.setTextSize(12);
            positionZeroLabel.setTextColor(COLOR_TEXT_MUTED);

            buttonColumn.addView(buttonLabel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            buttonColumn.addView(positionZeroLabel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            ActionSlotView primarySlot = createActionSlotView(
                    activity,
                    button,
                    gameMode,
                    0,
                    store.getButtonActionSlot(button, gameMode, profileKey, 0)
            );
            slots[0] = primarySlot;

            primaryRow.addView(buttonColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));
            primaryRow.addView(primarySlot.view, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.55f));
            group.addView(primaryRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            if (GamepadMappingStore.MAX_ACTION_SLOTS > 1) {
                ArrayList<View> extraRows = new ArrayList<>();

                LinearLayout extraSelectorRow = new LinearLayout(activity);
                extraSelectorRow.setOrientation(LinearLayout.HORIZONTAL);
                extraSelectorRow.setGravity(Gravity.CENTER_VERTICAL);
                extraSelectorRow.setPadding(dp(activity, 12), dp(activity, 2), 0, dp(activity, 2));

                TextView extraLabel = new TextView(activity);
                extraLabel.setText("Extra slots");
                extraLabel.setTextSize(12);
                extraLabel.setTextColor(COLOR_TEXT_MUTED);

                Spinner extraSlotSelector = new Spinner(activity);
                extraSlotSelector.setAdapter(darkAdapter(activity, extraSlotLabels));
                extraSlotSelector.setSelection(0, false);

                extraSelectorRow.addView(extraLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.75f));
                extraSelectorRow.addView(extraSlotSelector, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
                group.addView(extraSelectorRow, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));

                for (int slot = 1; slot < GamepadMappingStore.MAX_ACTION_SLOTS; slot++) {
                    LinearLayout row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(activity, 12), dp(activity, 2), 0, dp(activity, 2));
                    row.setVisibility(View.GONE);

                    TextView slotLabel = new TextView(activity);
                    slotLabel.setText("Position " + slot);
                    slotLabel.setTextSize(12);
                    slotLabel.setTextColor(COLOR_TEXT_MUTED);

                    ActionSlotView slotView = createActionSlotView(
                            activity,
                            button,
                            gameMode,
                            slot,
                            store.getButtonActionSlot(button, gameMode, profileKey, slot)
                    );

                    row.addView(slotLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.75f));
                    row.addView(slotView.view, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
                    group.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    ));

                    extraRows.add(row);
                    slots[slot] = slotView;
                }

                extraSlotSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        int visibleExtraIndex = position - 1;
                        for (int i = 0; i < extraRows.size(); i++) {
                            extraRows.get(i).setVisibility(i == visibleExtraIndex ? View.VISIBLE : View.GONE);
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        for (View row : extraRows) {
                            row.setVisibility(View.GONE);
                        }
                    }
                });
            }

            root.addView(group, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            out.put(button, slots);
        }
    }

    @NonNull
    private static ActionSlotView createActionSlotView(
            @NonNull Activity activity,
            @NonNull GamepadButton button,
            boolean gameMode,
            int slot,
            @NonNull GamepadAction initialAction
    ) {
        TextView view = new TextView(activity);
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(COLOR_TEXT_PRIMARY);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(activity, 44));
        view.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));
        view.setSingleLine(false);
        view.setClickable(true);
        view.setFocusable(true);
        ActionSlotView slotView = new ActionSlotView(view, initialAction);
        view.setOnClickListener(v -> showActionKeyboardDialog(
                activity,
                button + " • Position " + slot,
                gameMode,
                slotView.action,
                slotView::setAction
        ));
        return slotView;
    }

    private interface ActionPickCallback {
        void onActionPicked(@NonNull GamepadAction action);
    }

    private static void showActionKeyboardDialog(
            @NonNull Activity activity,
            @NonNull String title,
            boolean gameMode,
            @NonNull GamepadAction currentAction,
            @NonNull ActionPickCallback callback
    ) {
        ArrayList<TouchKeyPickerDialog.KeySpec> gamepadExtras = new ArrayList<>();
        gamepadExtras.add(TouchKeyPickerDialog.extraKey("Cursor Up", TouchKeyPickerDialog.GAMEPAD_CURSOR_UP, 1.20f));
        gamepadExtras.add(TouchKeyPickerDialog.extraKey("Cursor Down", TouchKeyPickerDialog.GAMEPAD_CURSOR_DOWN, 1.30f));
        gamepadExtras.add(TouchKeyPickerDialog.extraKey("Cursor Left", TouchKeyPickerDialog.GAMEPAD_CURSOR_LEFT, 1.30f));
        gamepadExtras.add(TouchKeyPickerDialog.extraKey("Cursor Right", TouchKeyPickerDialog.GAMEPAD_CURSOR_RIGHT, 1.35f));

        TouchKeyPickerDialog.showPicker(
                activity,
                "Pick key for " + title,
                (gameMode
                        ? "Tap a key for in-game mode. Mouse actions are below. Current: "
                        : "Tap a key for menu mode. Mouse/cursor actions are below. Current: ") + currentAction,
                gamepadExtras,
                keyCode -> {
                    GamepadAction action = gamepadActionForPickerCode(keyCode);
                    if (action == null) {
                        Toast.makeText(activity, "That touch-only action is not available for gamepad mappings.", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    callback.onActionPicked(action);
                    return true;
                }
        );
    }

    @Nullable
    private static GamepadAction gamepadActionForPickerCode(int keyCode) {
        switch (keyCode) {
            case TouchKeyPickerDialog.GAMEPAD_CURSOR_UP:
                return GamepadAction.CURSOR_UP;
            case TouchKeyPickerDialog.GAMEPAD_CURSOR_DOWN:
                return GamepadAction.CURSOR_DOWN;
            case TouchKeyPickerDialog.GAMEPAD_CURSOR_LEFT:
                return GamepadAction.CURSOR_LEFT;
            case TouchKeyPickerDialog.GAMEPAD_CURSOR_RIGHT:
                return GamepadAction.CURSOR_RIGHT;
            default:
                return GamepadAction.fromKeyboardPickerCode(keyCode);
        }
    }

    private static void applySectionSelections(
            @NonNull GamepadMappingStore store,
            @NonNull String profileKey,
            boolean gameMode,
            @NonNull Map<GamepadButton, ActionSlotView[]> slotsByButton
    ) {
        for (Map.Entry<GamepadButton, ActionSlotView[]> entry : slotsByButton.entrySet()) {
            ActionSlotView[] slots = entry.getValue();
            for (int slot = 0; slot < slots.length; slot++) {
                if (slots[slot] != null) {
                    slots[slot].setAction(store.getButtonActionSlot(entry.getKey(), gameMode, profileKey, slot));
                }
            }
        }
    }

    private static void saveSection(
            @NonNull GamepadMappingStore store,
            @NonNull String profileKey,
            boolean gameMode,
            @NonNull Map<GamepadButton, ActionSlotView[]> slotsByButton
    ) {
        for (Map.Entry<GamepadButton, ActionSlotView[]> entry : slotsByButton.entrySet()) {
            ActionSlotView[] slots = entry.getValue();
            for (int slot = 0; slot < slots.length; slot++) {
                ActionSlotView slotView = slots[slot];
                if (slotView == null) continue;
                store.setButtonActionSlot(entry.getKey(), slotView.action, gameMode, profileKey, slot);
            }
        }
    }

    private static final class ActionSlotView {
        @NonNull final TextView view;
        @NonNull GamepadAction action;

        ActionSlotView(@NonNull TextView view, @NonNull GamepadAction action) {
            this.view = view;
            this.action = action;
            setAction(action);
        }

        void setAction(@NonNull GamepadAction action) {
            this.action = action;
            view.setText(action.toString());
            boolean empty = action == GamepadAction.NONE;
            view.setTextColor(empty ? COLOR_TEXT_MUTED : COLOR_TEXT_PRIMARY);
            view.setBackground(roundedDrawable(
                    (Activity) view.getContext(),
                    empty ? COLOR_CARD_BG : COLOR_CARD_BG_PRESSED,
                    empty ? COLOR_CARD_STROKE : COLOR_ACCENT_MUTED,
                    12
            ));
        }
    }

    @NonNull
    private static List<DeviceProfile> discoverProfiles(
            @NonNull Activity activity,
            @NonNull GamepadMappingStore store
    ) {
        LinkedHashMap<String, DeviceProfile> profiles = new LinkedHashMap<>();
        String active = store.getActiveProfileKey();

        profiles.put(store.getDefaultProfileKey(), new DeviceProfile(store.getDefaultProfileKey(), store.getProfileDisplayName(store.getDefaultProfileKey()), false));
        for (String known : store.getKnownProfileKeys()) {
            profiles.put(known, new DeviceProfile(known, store.getProfileDisplayName(known), false));
        }

        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || !isGamepadDevice(device)) continue;
            store.registerDevice(device);
            String key = store.profileKeyForDevice(device);
            profiles.put(key, new DeviceProfile(key, store.getProfileDisplayName(key), true));
        }

        store.setActiveProfileKey(active);
        return new ArrayList<>(profiles.values());
    }

    private static boolean isGamepadDevice(@NonNull InputDevice device) {
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static int preferredProfileIndex(@NonNull List<DeviceProfile> profiles, @NonNull String profileKey) {
        int activeIndex = 0;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).key.equals(profileKey)) {
                activeIndex = i;
                break;
            }
        }

        // When the user opens the mapper with one attached controller and no active
        // controller-specific profile yet, select the attached controller automatically.
        if (activeIndex == 0 && profiles.size() > 1) {
            for (int i = 1; i < profiles.size(); i++) {
                if (profiles.get(i).attached) return i;
            }
        }
        return activeIndex;
    }

    @NonNull
    private static String selectedProfileKey(@NonNull List<DeviceProfile> profiles, @NonNull Spinner spinner) {
        int position = spinner.getSelectedItemPosition();
        if (position < 0 || position >= profiles.size()) return profiles.get(0).key;
        return profiles.get(position).key;
    }

    private static int findGuiScaleIndex(@NonNull int[] guiScaleValues, int value) {
        for (int i = 0; i < guiScaleValues.length; i++) {
            if (guiScaleValues[i] == value) return i;
        }
        return 0;
    }

    private static int selectedGuiScaleValue(@NonNull int[] guiScaleValues, @NonNull Spinner spinner) {
        int position = spinner.getSelectedItemPosition();
        if (position < 0) return guiScaleValues[0];
        if (position >= guiScaleValues.length) return guiScaleValues[guiScaleValues.length - 1];
        return guiScaleValues[position];
    }

    private static LinearLayout.LayoutParams matchWrapWithTopMargin(@NonNull Activity activity, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, dp(activity, topDp), 0, 0);
        return lp;
    }

    private static void notifySettingsChanged(
            @NonNull Activity activity,
            @Nullable OnSettingsSavedListener listener
    ) {
        if (listener != null) listener.onSettingsSaved();
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor != null) {
            decor.requestLayout();
            decor.invalidate();
            decor.postInvalidateOnAnimation();
            decor.post(() -> {
                decor.requestLayout();
                decor.invalidate();
                decor.postInvalidateOnAnimation();
            });
        }
    }

    private static int dp(@NonNull Activity activity, int value) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static final class DeviceProfile {
        @NonNull final String key;
        @NonNull final String label;
        final boolean attached;

        DeviceProfile(@NonNull String key, @NonNull String label, boolean attached) {
            this.key = key;
            this.label = label;
            this.attached = attached;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
