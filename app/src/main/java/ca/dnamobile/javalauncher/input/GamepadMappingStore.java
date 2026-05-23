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

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class GamepadMappingStore {
    private static final String PREFS_NAME = "gamepad_mapping";
    private static final String GAME_PREFIX = "game.";
    private static final String MENU_PREFIX = "menu.";
    private static final String PROFILE_PREFIX = "profile.";
    private static final String PROFILE_NAME_PREFIX = "profile_name.";
    private static final String KNOWN_PROFILES = "known_profiles";
    private static final String ACTIVE_PROFILE = "active_profile";
    private static final String DEFAULT_PROFILE = "default";

    private static final String FORCE_GAME_MODE = "force_game_mode";
    private static final String SHOW_CURSOR_OVERLAY = "show_cursor_overlay";
    private static final String PREF_VERSION = "pref_version";

    private static final String MENU_CURSOR_SENSITIVITY = "menu_cursor_sensitivity";
    private static final String GAME_CAMERA_SENSITIVITY = "game_camera_sensitivity";
    private static final String HARDWARE_MOUSE_DPI_SCALE = "hardware_mouse_dpi_scale";

    private static final int CURRENT_PREF_VERSION = 10;
    private static final int DEFAULT_SENSITIVITY = 100;
    private static final int DEFAULT_MOUSE_DPI_SCALE = 100;
    public static final int MIN_SENSITIVITY = 25;
    public static final int MAX_SENSITIVITY = 200;
    public static final int MIN_MOUSE_DPI_SCALE = 25;
    public static final int MAX_MOUSE_DPI_SCALE = 300;
    public static final int MAX_ACTION_SLOTS = 4;

    private static volatile GamepadMappingStore instance;

    private final SharedPreferences prefs;

    private GamepadMappingStore(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        migrateIfNeeded();
    }

    @NonNull
    public static GamepadMappingStore get(@NonNull Context context) {
        GamepadMappingStore local = instance;
        if (local == null) {
            synchronized (GamepadMappingStore.class) {
                local = instance;
                if (local == null) {
                    local = new GamepadMappingStore(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    private void migrateIfNeeded() {
        int version = prefs.getInt(PREF_VERSION, 0);
        if (version >= CURRENT_PREF_VERSION) return;

        SharedPreferences.Editor editor = prefs.edit();

        // These three were previously defaulted to ENTER in one experimental patch.
        // Remove only these menu entries so they fall back to the correct Mouse Left Click default.
        editor.remove(keyFor(GamepadButton.BUTTON_A, false));
        editor.remove(keyFor(GamepadButton.BUTTON_R2, false));
        editor.remove(keyFor(GamepadButton.DPAD_CENTER, false));
        editor.remove(keyFor(DEFAULT_PROFILE, GamepadButton.BUTTON_A, false));
        editor.remove(keyFor(DEFAULT_PROFILE, GamepadButton.BUTTON_R2, false));
        editor.remove(keyFor(DEFAULT_PROFILE, GamepadButton.DPAD_CENTER, false));

        // Older builds also defaulted menu D-pad directions to Cursor actions.
        // That makes the D-pad behave like a virtual mouse even when the user expects
        // it to be a normal controller D-pad. Remove only those old Cursor values so
        // they fall back to the safer Arrow key defaults below. Users can still map
        // any D-pad direction back to Cursor Up/Down/Left/Right manually.
        removeLegacyMenuDpadCursorMapping(editor, DEFAULT_PROFILE);
        removeOldRequestedDefaultMappings(editor, DEFAULT_PROFILE);
        Set<String> knownProfiles = prefs.getStringSet(KNOWN_PROFILES, Collections.emptySet());
        if (knownProfiles != null) {
            for (String profileKey : knownProfiles) {
                removeLegacyMenuDpadCursorMapping(editor, profileKey);
                removeOldRequestedDefaultMappings(editor, profileKey);
            }
        }

        editor.putBoolean(SHOW_CURSOR_OVERLAY, prefs.getBoolean(SHOW_CURSOR_OVERLAY, true));
        editor.putInt(MENU_CURSOR_SENSITIVITY, clampSensitivity(prefs.getInt(MENU_CURSOR_SENSITIVITY, DEFAULT_SENSITIVITY)));
        editor.putInt(GAME_CAMERA_SENSITIVITY, clampSensitivity(prefs.getInt(GAME_CAMERA_SENSITIVITY, DEFAULT_SENSITIVITY)));
        editor.putInt(HARDWARE_MOUSE_DPI_SCALE, clampMouseDpiScale(prefs.getInt(HARDWARE_MOUSE_DPI_SCALE, DEFAULT_MOUSE_DPI_SCALE)));
        editor.putString(ACTIVE_PROFILE, prefs.getString(ACTIVE_PROFILE, DEFAULT_PROFILE));
        editor.putInt(PREF_VERSION, CURRENT_PREF_VERSION);
        editor.apply();
    }

    public boolean isForceGameMode() {
        return prefs.getBoolean(FORCE_GAME_MODE, false);
    }

    public void setForceGameMode(boolean force) {
        prefs.edit().putBoolean(FORCE_GAME_MODE, force).apply();
    }

    public boolean isShowCursorOverlay() {
        return prefs.getBoolean(SHOW_CURSOR_OVERLAY, true);
    }

    public void setShowCursorOverlay(boolean show) {
        prefs.edit().putBoolean(SHOW_CURSOR_OVERLAY, show).apply();
    }

    public int getMenuCursorSensitivity() {
        return clampSensitivity(prefs.getInt(MENU_CURSOR_SENSITIVITY, DEFAULT_SENSITIVITY));
    }

    public void setMenuCursorSensitivity(int sensitivity) {
        prefs.edit().putInt(MENU_CURSOR_SENSITIVITY, clampSensitivity(sensitivity)).apply();
    }

    public int getGameCameraSensitivity() {
        return clampSensitivity(prefs.getInt(GAME_CAMERA_SENSITIVITY, DEFAULT_SENSITIVITY));
    }

    public void setGameCameraSensitivity(int sensitivity) {
        prefs.edit().putInt(GAME_CAMERA_SENSITIVITY, clampSensitivity(sensitivity)).apply();
    }

    public float getMenuCursorSensitivityMultiplier() {
        return getMenuCursorSensitivity() / 100f;
    }

    public float getGameCameraSensitivityMultiplier() {
        return getGameCameraSensitivity() / 100f;
    }

    public int getHardwareMouseDpiScale() {
        return clampMouseDpiScale(prefs.getInt(HARDWARE_MOUSE_DPI_SCALE, DEFAULT_MOUSE_DPI_SCALE));
    }

    public void setHardwareMouseDpiScale(int dpiScale) {
        prefs.edit().putInt(HARDWARE_MOUSE_DPI_SCALE, clampMouseDpiScale(dpiScale)).apply();
    }

    public float getHardwareMouseDpiScaleMultiplier() {
        return getHardwareMouseDpiScale() / 100f;
    }

    @NonNull
    public String getDefaultProfileKey() {
        return DEFAULT_PROFILE;
    }

    @NonNull
    public String getActiveProfileKey() {
        String key = prefs.getString(ACTIVE_PROFILE, DEFAULT_PROFILE);
        return isValidProfileKey(key) ? key : DEFAULT_PROFILE;
    }

    public void setActiveProfileKey(@Nullable String profileKey) {
        String safeKey = isValidProfileKey(profileKey) ? profileKey : DEFAULT_PROFILE;
        prefs.edit().putString(ACTIVE_PROFILE, safeKey).apply();
    }

    @NonNull
    public Set<String> getKnownProfileKeys() {
        Set<String> stored = prefs.getStringSet(KNOWN_PROFILES, Collections.emptySet());
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(DEFAULT_PROFILE);
        if (stored != null) result.addAll(stored);
        return result;
    }

    @NonNull
    public String getProfileDisplayName(@Nullable String profileKey) {
        if (!isValidProfileKey(profileKey) || DEFAULT_PROFILE.equals(profileKey)) {
            return "Default controller";
        }
        String name = prefs.getString(PROFILE_NAME_PREFIX + profileKey, null);
        return name == null || name.trim().isEmpty() ? "Controller " + profileKey : name;
    }

    public void registerDevice(@Nullable InputDevice device) {
        if (device == null) return;

        String profileKey = profileKeyForDevice(device);
        if (DEFAULT_PROFILE.equals(profileKey)) return;

        LinkedHashSet<String> known = new LinkedHashSet<>(getKnownProfileKeys());
        known.add(profileKey);

        prefs.edit()
                .putStringSet(KNOWN_PROFILES, known)
                .putString(PROFILE_NAME_PREFIX + profileKey, displayNameForDevice(device))
                .apply();
    }

    public void rememberDevice(@Nullable InputDevice device) {
        if (device == null) return;
        registerDevice(device);
        setActiveProfileKey(profileKeyForDevice(device));
    }

    @NonNull
    public String profileKeyForDevice(@Nullable InputDevice device) {
        if (device == null) return DEFAULT_PROFILE;

        String raw = device.getDescriptor();
        if (raw == null || raw.trim().isEmpty()) {
            raw = device.getName() + ":" + device.getVendorId() + ":" + device.getProductId();
        }
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_PROFILE;

        return "device_" + Integer.toHexString(raw.hashCode());
    }

    @NonNull
    public GamepadAction getButtonAction(@NonNull GamepadButton button, boolean gameMode) {
        return getButtonActionSlot(button, gameMode, getActiveProfileKey(), 0);
    }

    @NonNull
    public GamepadAction getButtonAction(
            @NonNull GamepadButton button,
            boolean gameMode,
            @Nullable InputDevice device
    ) {
        return getButtonActionSlot(button, gameMode, profileKeyForDevice(device), 0);
    }

    @NonNull
    public GamepadAction getButtonAction(
            @NonNull GamepadButton button,
            boolean gameMode,
            @Nullable String profileKey
    ) {
        return getButtonActionSlot(button, gameMode, profileKey, 0);
    }

    @NonNull
    public GamepadAction[] getButtonActions(
            @NonNull GamepadButton button,
            boolean gameMode,
            @Nullable InputDevice device
    ) {
        return getButtonActions(button, gameMode, profileKeyForDevice(device));
    }

    @NonNull
    public GamepadAction[] getButtonActions(
            @NonNull GamepadButton button,
            boolean gameMode,
            @Nullable String profileKey
    ) {
        GamepadAction[] actions = new GamepadAction[MAX_ACTION_SLOTS];
        for (int slot = 0; slot < MAX_ACTION_SLOTS; slot++) {
            actions[slot] = getButtonActionSlot(button, gameMode, profileKey, slot);
        }
        return actions;
    }

    @NonNull
    public GamepadAction getButtonActionSlot(
            @NonNull GamepadButton button,
            boolean gameMode,
            @Nullable String profileKey,
            int slot
    ) {
        int safeSlot = clampActionSlot(slot);
        if (safeSlot == 0) {
            return getPrimaryButtonAction(button, gameMode, profileKey);
        }

        String safeProfile = isValidProfileKey(profileKey) ? profileKey : DEFAULT_PROFILE;
        GamepadAction action = readStoredAction(keyForSlot(safeProfile, button, gameMode, safeSlot));
        if (action != null) return action;

        String activeProfile = getActiveProfileKey();
        if (!DEFAULT_PROFILE.equals(activeProfile) && !activeProfile.equals(safeProfile)) {
            action = readStoredAction(keyForSlot(activeProfile, button, gameMode, safeSlot));
            if (action != null) return action;
        }

        if (!DEFAULT_PROFILE.equals(safeProfile)) {
            action = readStoredAction(keyForSlot(DEFAULT_PROFILE, button, gameMode, safeSlot));
            if (action != null) return action;
        }

        return defaultActionSlot(button, gameMode, safeSlot);
    }

    @NonNull
    private GamepadAction getPrimaryButtonAction(
            @NonNull GamepadButton button,
            boolean gameMode,
            @Nullable String profileKey
    ) {
        String safeProfile = isValidProfileKey(profileKey) ? profileKey : DEFAULT_PROFILE;

        GamepadAction action = readStoredAction(keyFor(safeProfile, button, gameMode));
        if (action != null) return action;

        // Some Android handhelds report different InputDevice descriptors for
        // KeyEvent and MotionEvent from the same physical controller. Fall back to
        // the currently selected/remembered profile before using the old global keys
        // so remapped D-pad values do not silently fall back to the default cursor.
        String activeProfile = getActiveProfileKey();
        if (!DEFAULT_PROFILE.equals(activeProfile) && !activeProfile.equals(safeProfile)) {
            action = readStoredAction(keyFor(activeProfile, button, gameMode));
            if (action != null) return action;
        }

        // Legacy/global fallback so saved mappings from older builds still work.
        if (!DEFAULT_PROFILE.equals(safeProfile)) {
            action = readStoredAction(keyFor(button, gameMode));
            if (action != null) return action;
        }

        return gameMode ? defaultGameAction(button) : defaultMenuAction(button);
    }

    public void setButtonAction(
            @NonNull GamepadButton button,
            @NonNull GamepadAction action,
            boolean gameMode
    ) {
        setButtonActionSlot(button, action, gameMode, getActiveProfileKey(), 0);
    }

    public void setButtonAction(
            @NonNull GamepadButton button,
            @NonNull GamepadAction action,
            boolean gameMode,
            @Nullable String profileKey
    ) {
        setButtonActionSlot(button, action, gameMode, profileKey, 0);
    }

    public void setButtonActionSlot(
            @NonNull GamepadButton button,
            @NonNull GamepadAction action,
            boolean gameMode,
            @Nullable String profileKey,
            int slot
    ) {
        String safeProfile = isValidProfileKey(profileKey) ? profileKey : DEFAULT_PROFILE;
        int safeSlot = clampActionSlot(slot);
        String key = safeSlot == 0
                ? keyFor(safeProfile, button, gameMode)
                : keyForSlot(safeProfile, button, gameMode, safeSlot);
        prefs.edit().putString(key, action.name()).apply();
    }

    public void clearButtonActionSlot(
            @NonNull GamepadButton button,
            boolean gameMode,
            @Nullable String profileKey,
            int slot
    ) {
        setButtonActionSlot(button, GamepadAction.NONE, gameMode, profileKey, slot);
    }

    public void resetDefaults() {
        prefs.edit()
                .clear()
                .putInt(PREF_VERSION, CURRENT_PREF_VERSION)
                .putBoolean(SHOW_CURSOR_OVERLAY, true)
                .putString(ACTIVE_PROFILE, DEFAULT_PROFILE)
                .putInt(MENU_CURSOR_SENSITIVITY, DEFAULT_SENSITIVITY)
                .putInt(GAME_CAMERA_SENSITIVITY, DEFAULT_SENSITIVITY)
                .putInt(HARDWARE_MOUSE_DPI_SCALE, DEFAULT_MOUSE_DPI_SCALE)
                .apply();
    }

    @NonNull
    public JSONObject exportProfileToJson(@Nullable String profileKey) throws Exception {
        String safeProfile = isValidProfileKey(profileKey) ? profileKey : getActiveProfileKey();

        JSONObject root = new JSONObject();
        root.put("format", "droidbridge.gamepad.profile");
        root.put("version", 1);
        root.put("profileKey", safeProfile);
        root.put("profileName", getProfileDisplayName(safeProfile));

        JSONObject settings = new JSONObject();
        settings.put("showCursorOverlay", isShowCursorOverlay());
        settings.put("menuCursorSensitivity", getMenuCursorSensitivity());
        settings.put("gameCameraSensitivity", getGameCameraSensitivity());
        settings.put("hardwareMouseDpiScale", getHardwareMouseDpiScale());
        root.put("settings", settings);

        root.put("menu", exportModeToJson(safeProfile, false));
        root.put("game", exportModeToJson(safeProfile, true));
        return root;
    }

    public void importProfileFromJson(@NonNull JSONObject root, @Nullable String profileKey) throws Exception {
        String safeProfile = isValidProfileKey(profileKey) ? profileKey : getActiveProfileKey();
        String profileName = root.optString("profileName", null);

        SharedPreferences.Editor editor = prefs.edit();
        rememberImportedProfile(editor, safeProfile, profileName);
        importModeFromJson(editor, root.optJSONObject("menu"), safeProfile, false);
        importModeFromJson(editor, root.optJSONObject("game"), safeProfile, true);

        JSONObject settings = root.optJSONObject("settings");
        if (settings != null) {
            if (settings.has("showCursorOverlay")) {
                editor.putBoolean(SHOW_CURSOR_OVERLAY, settings.optBoolean("showCursorOverlay", true));
            }
            if (settings.has("menuCursorSensitivity")) {
                editor.putInt(MENU_CURSOR_SENSITIVITY, clampSensitivity(settings.optInt("menuCursorSensitivity", DEFAULT_SENSITIVITY)));
            }
            if (settings.has("gameCameraSensitivity")) {
                editor.putInt(GAME_CAMERA_SENSITIVITY, clampSensitivity(settings.optInt("gameCameraSensitivity", DEFAULT_SENSITIVITY)));
            }
            if (settings.has("hardwareMouseDpiScale")) {
                editor.putInt(HARDWARE_MOUSE_DPI_SCALE, clampMouseDpiScale(settings.optInt("hardwareMouseDpiScale", DEFAULT_MOUSE_DPI_SCALE)));
            }
        }

        editor.apply();
    }

    @NonNull
    private JSONObject exportModeToJson(@NonNull String profileKey, boolean gameMode) throws Exception {
        JSONObject mode = new JSONObject();
        for (GamepadButton button : GamepadButton.values()) {
            JSONArray slots = new JSONArray();
            for (int slot = 0; slot < MAX_ACTION_SLOTS; slot++) {
                slots.put(getButtonActionSlot(button, gameMode, profileKey, slot).name());
            }
            mode.put(button.name(), slots);
        }
        return mode;
    }

    private void importModeFromJson(
            @NonNull SharedPreferences.Editor editor,
            @Nullable JSONObject mode,
            @NonNull String profileKey,
            boolean gameMode
    ) throws Exception {
        if (mode == null) return;

        for (GamepadButton button : GamepadButton.values()) {
            if (!mode.has(button.name())) continue;
            Object value = mode.opt(button.name());
            importButtonActionsFromJson(editor, value, profileKey, button, gameMode);
        }
    }

    private void importButtonActionsFromJson(
            @NonNull SharedPreferences.Editor editor,
            @Nullable Object value,
            @NonNull String profileKey,
            @NonNull GamepadButton button,
            boolean gameMode
    ) throws Exception {
        if (value == null || value == JSONObject.NULL) return;

        if (value instanceof JSONArray) {
            JSONArray slots = (JSONArray) value;
            for (int slot = 0; slot < MAX_ACTION_SLOTS; slot++) {
                GamepadAction action = slot < slots.length()
                        ? parseImportedAction(slots.opt(slot))
                        : GamepadAction.NONE;
                if (action != null) putButtonActionSlot(editor, profileKey, button, gameMode, slot, action);
            }
            return;
        }

        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray slots = object.optJSONArray("slots");
            if (slots != null) {
                importButtonActionsFromJson(editor, slots, profileKey, button, gameMode);
                return;
            }

            for (int slot = 0; slot < MAX_ACTION_SLOTS; slot++) {
                String key = slot == 0 ? "primary" : "slot" + slot;
                if (!object.has(key)) continue;
                GamepadAction action = parseImportedAction(object.opt(key));
                if (action != null) putButtonActionSlot(editor, profileKey, button, gameMode, slot, action);
            }
            return;
        }

        GamepadAction action = parseImportedAction(value);
        if (action != null) putButtonActionSlot(editor, profileKey, button, gameMode, 0, action);
    }

    @Nullable
    private static GamepadAction parseImportedAction(@Nullable Object rawValue) {
        if (rawValue == null || rawValue == JSONObject.NULL) return null;

        String raw = String.valueOf(rawValue).trim();
        if (raw.isEmpty()) return null;

        try {
            return GamepadAction.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void putButtonActionSlot(
            @NonNull SharedPreferences.Editor editor,
            @NonNull String profileKey,
            @NonNull GamepadButton button,
            boolean gameMode,
            int slot,
            @NonNull GamepadAction action
    ) {
        int safeSlot = clampActionSlot(slot);
        String key = safeSlot == 0
                ? keyFor(profileKey, button, gameMode)
                : keyForSlot(profileKey, button, gameMode, safeSlot);
        editor.putString(key, action.name());
    }

    private void rememberImportedProfile(
            @NonNull SharedPreferences.Editor editor,
            @NonNull String profileKey,
            @Nullable String profileName
    ) {
        if (DEFAULT_PROFILE.equals(profileKey)) return;

        Set<String> stored = prefs.getStringSet(KNOWN_PROFILES, Collections.emptySet());
        LinkedHashSet<String> known = new LinkedHashSet<>();
        if (stored != null) known.addAll(stored);
        known.add(profileKey);
        editor.putStringSet(KNOWN_PROFILES, known);

        if (profileName != null && !profileName.trim().isEmpty()) {
            editor.putString(PROFILE_NAME_PREFIX + profileKey, profileName.trim());
        }
    }

    private void removeLegacyMenuDpadCursorMapping(
            @NonNull SharedPreferences.Editor editor,
            @Nullable String profileKey
    ) {
        removeIfStoredAction(editor, keyForSafeProfile(profileKey, GamepadButton.DPAD_UP, false), GamepadAction.CURSOR_UP);
        removeIfStoredAction(editor, keyForSafeProfile(profileKey, GamepadButton.DPAD_DOWN, false), GamepadAction.CURSOR_DOWN);
        removeIfStoredAction(editor, keyForSafeProfile(profileKey, GamepadButton.DPAD_LEFT, false), GamepadAction.CURSOR_LEFT);
        removeIfStoredAction(editor, keyForSafeProfile(profileKey, GamepadButton.DPAD_RIGHT, false), GamepadAction.CURSOR_RIGHT);
    }

    private void removeOldRequestedDefaultMappings(
            @NonNull SharedPreferences.Editor editor,
            @Nullable String profileKey
    ) {
        String safeProfile = isValidProfileKey(profileKey) ? profileKey : DEFAULT_PROFILE;

        // These values were the old built-in defaults. Remove only those old
        // values so the new defaults below take effect, while custom user edits
        // to the same buttons survive the migration.
        removeIfStoredAction(editor, keyFor(safeProfile, GamepadButton.BUTTON_Y, false), GamepadAction.NONE);
        removeIfStoredAction(editor, keyForSlot(safeProfile, GamepadButton.BUTTON_Y, false, 1), GamepadAction.NONE);
        removeIfStoredAction(editor, keyFor(safeProfile, GamepadButton.BUTTON_THUMBL, false), GamepadAction.NONE);

        removeIfStoredAction(editor, keyFor(safeProfile, GamepadButton.DPAD_UP, true), GamepadAction.SNEAK);
        removeIfStoredAction(editor, keyFor(safeProfile, GamepadButton.DPAD_DOWN, true), GamepadAction.KEY_O);
        removeIfStoredAction(editor, keyFor(safeProfile, GamepadButton.DPAD_RIGHT, true), GamepadAction.KEY_K);
    }

    private void removeIfStoredAction(
            @NonNull SharedPreferences.Editor editor,
            @NonNull String key,
            @NonNull GamepadAction legacyAction
    ) {
        String stored = prefs.getString(key, null);
        if (legacyAction.name().equals(stored)) {
            editor.remove(key);
        }
    }

    @NonNull
    private static String keyForSafeProfile(
            @Nullable String profileKey,
            @NonNull GamepadButton button,
            boolean gameMode
    ) {
        String safeProfile = isValidProfileKey(profileKey) ? profileKey : DEFAULT_PROFILE;
        return keyFor(safeProfile, button, gameMode);
    }

    @Nullable
    private GamepadAction readStoredAction(@NonNull String key) {
        String stored = prefs.getString(key, null);
        if (stored == null) return null;

        try {
            return GamepadAction.valueOf(stored);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @NonNull
    private static String keyFor(@NonNull GamepadButton button, boolean gameMode) {
        return (gameMode ? GAME_PREFIX : MENU_PREFIX) + button.name();
    }

    @NonNull
    private static String keyFor(
            @NonNull String profileKey,
            @NonNull GamepadButton button,
            boolean gameMode
    ) {
        if (DEFAULT_PROFILE.equals(profileKey)) {
            return keyFor(button, gameMode);
        }
        return PROFILE_PREFIX + profileKey + "." + (gameMode ? GAME_PREFIX : MENU_PREFIX) + button.name();
    }

    @NonNull
    private static String keyForSlot(
            @NonNull String profileKey,
            @NonNull GamepadButton button,
            boolean gameMode,
            int slot
    ) {
        return keyFor(profileKey, button, gameMode) + ".slot" + clampActionSlot(slot);
    }

    private static int clampActionSlot(int slot) {
        return Math.max(0, Math.min(MAX_ACTION_SLOTS - 1, slot));
    }

    private static boolean isValidProfileKey(@Nullable String profileKey) {
        return profileKey != null && !profileKey.trim().isEmpty();
    }

    @NonNull
    private static String displayNameForDevice(@NonNull InputDevice device) {
        String name = device.getName();
        if (name == null || name.trim().isEmpty()) name = "Controller";

        int vendorId = device.getVendorId();
        int productId = device.getProductId();
        if (vendorId > 0 || productId > 0) {
            return name + " (" + vendorId + ":" + productId + ")";
        }
        return name;
    }

    private static int clampSensitivity(int sensitivity) {
        return Math.max(MIN_SENSITIVITY, Math.min(MAX_SENSITIVITY, sensitivity));
    }

    private static int clampMouseDpiScale(int dpiScale) {
        return Math.max(MIN_MOUSE_DPI_SCALE, Math.min(MAX_MOUSE_DPI_SCALE, dpiScale));
    }

    @NonNull
    private static GamepadAction defaultGameAction(@NonNull GamepadButton button) {
        switch (button) {
            case BUTTON_A:
                return GamepadAction.JUMP;
            case BUTTON_B:
                return GamepadAction.DROP;
            case BUTTON_X:
                return GamepadAction.INVENTORY;
            case BUTTON_Y:
                return GamepadAction.OFFHAND;

            case BUTTON_L1:
                return GamepadAction.SCROLL_UP;
            case BUTTON_R1:
                return GamepadAction.SCROLL_DOWN;
            case BUTTON_L2:
                return GamepadAction.MOUSE_RIGHT;
            case BUTTON_R2:
                return GamepadAction.MOUSE_LEFT;

            case BUTTON_THUMBL:
                return GamepadAction.SPRINT;
            case BUTTON_THUMBR:
                return GamepadAction.SNEAK;

            case BUTTON_START:
                return GamepadAction.ESCAPE;
            case BUTTON_SELECT:
                return GamepadAction.TAB;

            case DPAD_UP:
                return GamepadAction.F5;
            case DPAD_DOWN:
                return GamepadAction.F3;
            case DPAD_LEFT:
                return GamepadAction.KEY_J;
            case DPAD_RIGHT:
                return GamepadAction.KEY_T;
            case DPAD_CENTER:
                return GamepadAction.NONE;

            default:
                return GamepadAction.NONE;
        }
    }

    @NonNull
    private static GamepadAction defaultMenuAction(@NonNull GamepadButton button) {
        switch (button) {
            case BUTTON_A:
            case BUTTON_R2:
            case DPAD_CENTER:
                return GamepadAction.MOUSE_LEFT;

            case BUTTON_X:
            case BUTTON_L2:
                return GamepadAction.MOUSE_RIGHT;

            case BUTTON_Y:
            case BUTTON_THUMBL:
                return GamepadAction.LEFT_SHIFT;

            case BUTTON_B:
            case BUTTON_START:
            case BUTTON_SELECT:
                return GamepadAction.ESCAPE;

            case BUTTON_L1:
                return GamepadAction.SCROLL_UP;
            case BUTTON_R1:
                return GamepadAction.SCROLL_DOWN;

            case DPAD_UP:
                return GamepadAction.ARROW_UP;
            case DPAD_DOWN:
                return GamepadAction.ARROW_DOWN;
            case DPAD_LEFT:
                return GamepadAction.ARROW_LEFT;
            case DPAD_RIGHT:
                return GamepadAction.ARROW_RIGHT;

            default:
                return GamepadAction.NONE;
        }
    }

    @NonNull
    private static GamepadAction defaultActionSlot(
            @NonNull GamepadButton button,
            boolean gameMode,
            int slot
    ) {
        if (slot <= 0) {
            return gameMode ? defaultGameAction(button) : defaultMenuAction(button);
        }

        if (!gameMode && button == GamepadButton.BUTTON_Y && slot == 1) {
            return GamepadAction.MOUSE_LEFT;
        }

        return GamepadAction.NONE;
    }
}
