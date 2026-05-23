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
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ca.dnamobile.javalauncher.feature.log.Logging;
import ca.dnamobile.javalauncher.settings.LauncherPreferences;

import java.util.EnumMap;

/**
 * Built-in Android controller support.
 *
 * In menu mode:
 * - both sticks move visible cursor
 * - D-pad never moves the visible cursor unless that exact D-pad direction is manually mapped to a Cursor action
 * - A/R2/D-pad center are guarded to left-click even if old saved prefs mapped them to Enter
 */
public final class GamepadInputController {
    private static final String TAG = "GamepadInputController";

    private static final float DEADZONE = 0.25f;
    private static final float TRIGGER_THRESHOLD = 0.50f;
    private static final float HAT_THRESHOLD = 0.85f;

    // Base values. User sensitivity prefs multiply these.
    private static final float BASE_GAME_CAMERA_SENSITIVITY = 18f;
    private static final float BASE_MENU_CURSOR_SENSITIVITY = 26f;
    private static final float BASE_DPAD_CURSOR_STEP = 14f;
    private static final float CURSOR_ACTION_BASE_STEP = 72f;

    // When Minecraft closes a GUI/inventory and re-grabs the pointer, Android can
    // still report the stick value that was being used to move the menu cursor.
    // If the right stick was already neutral, only swallow a tiny settle window so
    // the first real camera movement after leaving the menu is not lost. If the
    // stick was active, keep the stale-input guard but time it out so the camera
    // cannot stay stuck until the user moves the stick a second time.
    private static final long GAME_REGRAB_CAMERA_SETTLE_NANOS = 120_000_000L;
    private static final long GAME_REGRAB_STALE_STICK_TIMEOUT_NANOS = 650_000_000L;

    private static final int DIRECTION_NONE = -1;
    private static final int DIRECTION_EAST = 0;
    private static final int DIRECTION_NORTH_EAST = 1;
    private static final int DIRECTION_NORTH = 2;
    private static final int DIRECTION_NORTH_WEST = 3;
    private static final int DIRECTION_WEST = 4;
    private static final int DIRECTION_SOUTH_WEST = 5;
    private static final int DIRECTION_SOUTH = 6;
    private static final int DIRECTION_SOUTH_EAST = 7;

    public interface MappingRequestListener {
        void onRequestControllerMapping();
    }

    private final Choreographer choreographer = Choreographer.getInstance();
    private final Context context;
    private final GamepadMappingStore mappingStore;
    private final MappingRequestListener mappingRequestListener;
    private final EnumMap<GamepadButton, ActiveMappedAction[]> activeButtonActions = new EnumMap<>(GamepadButton.class);

    private boolean removed;
    private long lastFrameNanos = System.nanoTime();

    private float leftX;
    private float leftY;
    private float rightX;
    private float rightY;

    @Nullable private InputDevice activeDevice;

    private int currentDirection = DIRECTION_NONE;

    private boolean hatUp;
    private boolean hatDown;
    private boolean hatLeft;
    private boolean hatRight;
    private boolean leftTriggerDown;
    private boolean rightTriggerDown;

    // I love cheese
    private boolean lastGameMode;
    private boolean requireRightStickNeutralBeforeCamera;
    private long suppressCameraUntilNanos;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            tick(frameTimeNanos);
            if (!removed) {
                choreographer.postFrameCallback(this);
            }
        }
    };

    public GamepadInputController(@NonNull View hostView) {
        this(hostView, null);
    }

    public GamepadInputController(@NonNull View hostView, MappingRequestListener mappingRequestListener) {
        context = hostView.getContext().getApplicationContext();
        mappingStore = GamepadMappingStore.get(hostView.getContext());
        this.mappingRequestListener = mappingRequestListener;

        hostView.setFocusable(true);
        hostView.setFocusableInTouchMode(true);
        hostView.requestFocus();

        org.lwjgl.glfw.CallbackBridge.sendCursorPos(
                Math.max(1, org.lwjgl.glfw.CallbackBridge.windowWidth) / 2f,
                Math.max(1, org.lwjgl.glfw.CallbackBridge.windowHeight) / 2f
        );

        lastGameMode = isGameMode();
        choreographer.postFrameCallback(frameCallback);
    }

    public void removeSelf() {
        removed = true;
        releaseDirection();
        releaseAllMappedButtons();
    }

    public boolean handleKeyEvent(@NonNull KeyEvent event) {
        if (!isGamepadKeyEvent(event)) return false;

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return false;
        }

        InputDevice device = event.getDevice();
        rememberDevice(device);

        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE
                || event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (action == KeyEvent.ACTION_UP && mappingRequestListener != null) {
                mappingRequestListener.onRequestControllerMapping();
            }
            return true;
        }

        GamepadButton button = GamepadButton.fromAndroidKeyCode(event.getKeyCode());
        if (button == null) return false;

        // Android can generate very fast repeat KeyEvents for D-pad directions.
        // The controller bridge keeps button/hat state itself, so repeated ACTION_DOWN
        // events are not needed and can make a remapped D-pad look like a mouse again.
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
            return true;
        }

        sendMappedButton(button, action == KeyEvent.ACTION_DOWN, device);
        return true;
    }

    public boolean handleMotionEvent(@NonNull MotionEvent event) {
        // Do not ever claim normal touchscreen/mouse events. This class is only for
        // physical controller axes/buttons. Check gamepad first because some
        // handhelds expose mixed sources like JOYSTICK | MOUSE/TOUCHPAD.
        if (!isGamepadMotionEvent(event)) return false;
        if (isPointerMotionEvent(event)) return false;

        InputDevice device = event.getDevice();
        if (device == null) return false;
        rememberDevice(device);

        leftX = getCenteredAxis(event, device, MotionEvent.AXIS_X);
        leftY = getCenteredAxis(event, device, MotionEvent.AXIS_Y);

        rightX = getCenteredAxis(event, device, MotionEvent.AXIS_Z);
        rightY = getCenteredAxis(event, device, MotionEvent.AXIS_RZ);
        if (rightX == 0f) rightX = getCenteredAxis(event, device, MotionEvent.AXIS_RX);
        if (rightY == 0f) rightY = getCenteredAxis(event, device, MotionEvent.AXIS_RY);

        updateDirection();

        updateHatButton(GamepadButton.DPAD_LEFT, getCenteredAxis(event, device, MotionEvent.AXIS_HAT_X) < -HAT_THRESHOLD, device);
        updateHatButton(GamepadButton.DPAD_RIGHT, getCenteredAxis(event, device, MotionEvent.AXIS_HAT_X) > HAT_THRESHOLD, device);
        updateHatButton(GamepadButton.DPAD_UP, getCenteredAxis(event, device, MotionEvent.AXIS_HAT_Y) < -HAT_THRESHOLD, device);
        updateHatButton(GamepadButton.DPAD_DOWN, getCenteredAxis(event, device, MotionEvent.AXIS_HAT_Y) > HAT_THRESHOLD, device);

        updateTrigger(true, getCenteredAxis(event, device, MotionEvent.AXIS_LTRIGGER) > TRIGGER_THRESHOLD, device);
        updateTrigger(false, getCenteredAxis(event, device, MotionEvent.AXIS_RTRIGGER) > TRIGGER_THRESHOLD, device);

        return true;
    }

    private void rememberDevice(@Nullable InputDevice device) {
        if (device == null) return;
        activeDevice = device;
        mappingStore.rememberDevice(device);
    }

    private static boolean isGamepadKeyEvent(@NonNull KeyEvent event) {
        int source = event.getSource();
        InputDevice device = event.getDevice();

        boolean fromGamepad = (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (device != null && ((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (device.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK));

        if (fromGamepad) return true;

        return GamepadButton.fromAndroidKeyCode(event.getKeyCode()) != null
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE
                || event.getKeyCode() == KeyEvent.KEYCODE_MENU;
    }

    private static boolean isPointerMotionEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        boolean gamepad = (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (gamepad) return false;

        return (source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                || (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
                || event.getPointerCount() > 1;
    }

    private static boolean isGamepadMotionEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                && event.getActionMasked() == MotionEvent.ACTION_MOVE;
    }

    private static float getCenteredAxis(@NonNull MotionEvent event, @NonNull InputDevice device, int axis) {
        InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
        if (range == null) return 0f;

        float value = event.getAxisValue(axis);
        float flat = Math.max(range.getFlat(), DEADZONE);
        return Math.abs(value) > flat ? value : 0f;
    }

    private boolean isGameMode() {
        return org.lwjgl.glfw.CallbackBridge.isGrabbing() || mappingStore.isForceGameMode();
    }

    private void tick(long frameTimeNanos) {
        float deltaScale = (frameTimeNanos - lastFrameNanos) / 16_666_666f;
        if (deltaScale <= 0f || deltaScale > 4f) deltaScale = 1f;

        boolean gameMode = isGameMode();
        if (gameMode != lastGameMode) {
            handleInputModeChanged(gameMode, frameTimeNanos);
            lastGameMode = gameMode;
            // Do not let the frame that changed modes consume stale delta/axis data.
            lastFrameNanos = frameTimeNanos;
            return;
        }

        if (gameMode) {
            tickCamera(deltaScale, frameTimeNanos);
        } else {
            tickMenuCursor(deltaScale);
        }

        lastFrameNanos = frameTimeNanos;
    }

    private void handleInputModeChanged(boolean gameMode, long frameTimeNanos) {
        releaseDirection();

        if (gameMode) {
            blockCameraAfterMenuClose(frameTimeNanos, "Minecraft input re-grabbed");
        } else {
            requireRightStickNeutralBeforeCamera = false;
            suppressCameraUntilNanos = 0L;
        }
    }

    private void prepareForLikelyMenuCloseFromController(@NonNull GamepadButton button) {
        blockCameraAfterMenuClose(System.nanoTime(), "Menu close requested by " + button);
    }

    private void blockCameraAfterMenuClose(long nowNanos, @NonNull String reason) {
        boolean rightStickWasActive = !isRightStickNeutral();

        clearLeftStickAxes();
        if (!rightStickWasActive) {
            clearRightStickAxes();
        }
        releaseDirection();

        requireRightStickNeutralBeforeCamera = rightStickWasActive;
        suppressCameraUntilNanos = Math.max(
                suppressCameraUntilNanos,
                nowNanos + (rightStickWasActive
                        ? GAME_REGRAB_STALE_STICK_TIMEOUT_NANOS
                        : GAME_REGRAB_CAMERA_SETTLE_NANOS)
        );

        Logging.i(TAG, reason + (rightStickWasActive
                ? "; right stick was active, guarding camera until neutral or timeout"
                : "; right stick was neutral, short camera settle only"));
    }

    private boolean isRightStickNeutral() {
        return rightX == 0f && rightY == 0f;
    }

    private void clearLeftStickAxes() {
        leftX = 0f;
        leftY = 0f;
    }

    private void clearRightStickAxes() {
        rightX = 0f;
        rightY = 0f;
    }

    private void tickCamera(float deltaScale, long frameTimeNanos) {
        if (shouldBlockCameraInput(frameTimeNanos)) return;
        if (rightX == 0f && rightY == 0f) return;

        float magnitude = Math.min(1f, (float) Math.sqrt(rightX * rightX + rightY * rightY));
        float acceleration = magnitude * magnitude;

        float sensitivity = BASE_GAME_CAMERA_SENSITIVITY
                * mappingStore.getGameCameraSensitivityMultiplier();

        // Do NOT apply resolution-scale compensation here.
        // Minecraft camera look already interprets this as grabbed mouse/camera
        // movement, and multiplying it by the render-resolution scale makes the
        // right stick feel slow when the user lowers game resolution.
        float deltaX = rightX * acceleration * sensitivity * deltaScale;
        float deltaY = rightY * acceleration * sensitivity * deltaScale;

        org.lwjgl.glfw.CallbackBridge.mouseX += deltaX;
        org.lwjgl.glfw.CallbackBridge.mouseY += deltaY;
        org.lwjgl.glfw.CallbackBridge.sendCursorPos(org.lwjgl.glfw.CallbackBridge.mouseX, org.lwjgl.glfw.CallbackBridge.mouseY);
    }

    private boolean shouldBlockCameraInput(long frameTimeNanos) {
        boolean rightStickNeutral = isRightStickNeutral();
        boolean timedSuppressActive = frameTimeNanos < suppressCameraUntilNanos;

        if (requireRightStickNeutralBeforeCamera && (rightStickNeutral || !timedSuppressActive)) {
            // Clear the latch as soon as the stale stick returns neutral. Also clear
            // it after the stale timeout so a noisy controller cannot leave camera
            // input stuck until the user moves the right stick a second time.
            requireRightStickNeutralBeforeCamera = false;

            if (rightStickNeutral && timedSuppressActive) {
                suppressCameraUntilNanos = Math.min(
                        suppressCameraUntilNanos,
                        frameTimeNanos + GAME_REGRAB_CAMERA_SETTLE_NANOS
                );
                timedSuppressActive = frameTimeNanos < suppressCameraUntilNanos;
            }
        }

        return timedSuppressActive || requireRightStickNeutralBeforeCamera;
    }

    private void tickMenuCursor(float deltaScale) {
        float x = Math.abs(rightX) > Math.abs(leftX) ? rightX : leftX;
        float y = Math.abs(rightY) > Math.abs(leftY) ? rightY : leftY;

        float dx = 0f;
        float dy = 0f;

        float sensitivityMultiplier = mappingStore.getMenuCursorSensitivityMultiplier();
        float menuResolutionScale = menuCursorResolutionScale();

        if (x != 0f || y != 0f) {
            float magnitude = Math.min(1f, (float) Math.sqrt(x * x + y * y));
            float acceleration = Math.max(0.35f, magnitude * magnitude);
            float sensitivity = BASE_MENU_CURSOR_SENSITIVITY * sensitivityMultiplier * menuResolutionScale;
            dx += x * acceleration * sensitivity * deltaScale;
            dy += y * acceleration * sensitivity * deltaScale;
        }

        // Only repeat D-pad cursor movement when that D-pad direction is actually mapped
        // to a Cursor action. This fixes remapped D-pad buttons still behaving like a joystick.
        float cursorRepeatScale = (BASE_DPAD_CURSOR_STEP / CURSOR_ACTION_BASE_STEP)
                * sensitivityMultiplier
                * menuResolutionScale
                * deltaScale;
        float[] dpadDelta = addDpadCursorRepeat(cursorRepeatScale);
        dx += dpadDelta[0];
        dy += dpadDelta[1];

        if (dx != 0f || dy != 0f) {
            GamepadAction.moveCursorBy(dx, dy);
        }
    }

    /**
     * Menu cursor coordinates are visual/window coordinates, so lower render
     * resolution can make the visible cursor travel too far. Keep this correction
     * limited to menu cursor movement only; grabbed in-game camera movement must
     * stay unscaled.
     */
    private float menuCursorResolutionScale() {
        try {
            float windowWidth = org.lwjgl.glfw.CallbackBridge.windowWidth;
            float physicalWidth = org.lwjgl.glfw.CallbackBridge.physicalWidth;
            if (windowWidth > 1f && physicalWidth > 1f) {
                float ratio = windowWidth / physicalWidth;
                if (ratio > 0.05f && ratio < 4f && Math.abs(ratio - 1f) > 0.025f) {
                    return clamp(ratio, 0.35f, 2.5f);
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            int percent = LauncherPreferences.getGameResolutionScalePercent(context);
            if (percent > 0) return clamp(percent / 100f, 0.35f, 2.5f);
        } catch (Throwable ignored) {
        }

        return 1f;
    }

    @NonNull
    private float[] addDpadCursorRepeat(float scale) {
        float[] delta = new float[]{0f, 0f};
        addCursorRepeat(delta, GamepadButton.DPAD_LEFT, hatLeft, scale);
        addCursorRepeat(delta, GamepadButton.DPAD_RIGHT, hatRight, scale);
        addCursorRepeat(delta, GamepadButton.DPAD_UP, hatUp, scale);
        addCursorRepeat(delta, GamepadButton.DPAD_DOWN, hatDown, scale);
        return delta;
    }

    private void addCursorRepeat(
            @NonNull float[] delta,
            @NonNull GamepadButton button,
            boolean pressed,
            float scale
    ) {
        if (!pressed) return;
        GamepadAction[] actions = mappingStore.getButtonActions(button, false, activeDevice);
        for (GamepadAction action : actions) {
            if (action == null || !action.isCursorAction()) continue;
            delta[0] += action.getCursorDx() * scale;
            delta[1] += action.getCursorDy() * scale;
        }
    }

    private void updateDirection() {
        if (!isGameMode()) {
            releaseDirection();
            return;
        }

        int newDirection = directionFor(leftX, leftY);
        if (newDirection == currentDirection) return;

        sendDirectional(currentDirection, false);
        currentDirection = newDirection;
        sendDirectional(currentDirection, true);
    }

    private void releaseDirection() {
        sendDirectional(currentDirection, false);
        currentDirection = DIRECTION_NONE;
    }

    private static int directionFor(float x, float y) {
        if (Math.sqrt(x * x + y * y) < DEADZONE) return DIRECTION_NONE;

        double angle = Math.toDegrees(Math.atan2(-y, x));
        if (angle < 0) angle += 360.0;

        return ((int) ((angle + 22.5) / 45.0)) % 8;
    }

    private void sendDirectional(int direction, boolean isDown) {
        switch (direction) {
            case DIRECTION_NORTH:
                GamepadAction.FORWARD.perform(isDown);
                break;
            case DIRECTION_NORTH_EAST:
                GamepadAction.FORWARD.perform(isDown);
                GamepadAction.RIGHT.perform(isDown);
                break;
            case DIRECTION_EAST:
                GamepadAction.RIGHT.perform(isDown);
                break;
            case DIRECTION_SOUTH_EAST:
                GamepadAction.RIGHT.perform(isDown);
                GamepadAction.BACKWARD.perform(isDown);
                break;
            case DIRECTION_SOUTH:
                GamepadAction.BACKWARD.perform(isDown);
                break;
            case DIRECTION_SOUTH_WEST:
                GamepadAction.BACKWARD.perform(isDown);
                GamepadAction.LEFT.perform(isDown);
                break;
            case DIRECTION_WEST:
                GamepadAction.LEFT.perform(isDown);
                break;
            case DIRECTION_NORTH_WEST:
                GamepadAction.FORWARD.perform(isDown);
                GamepadAction.LEFT.perform(isDown);
                break;
            case DIRECTION_NONE:
            default:
                break;
        }
    }

    private void sendMappedButton(
            @NonNull GamepadButton button,
            boolean isDown,
            @Nullable InputDevice device
    ) {
        ActiveMappedAction[] mapped;

        if (isDown) {
            mapped = resolveMappedActions(button, device);
            activeButtonActions.put(button, mapped);
        } else {
            mapped = activeButtonActions.remove(button);
            if (mapped == null) {
                // Fallback for devices that send an UP without the matching DOWN.
                mapped = resolveMappedActions(button, device);
            }
        }

        if (isDown && containsMenuEscape(mapped)) {
            prepareForLikelyMenuCloseFromController(button);
        }

        Logging.i(TAG, "Button=" + button + ", down=" + isDown
                + ", profile=" + mappingStore.profileKeyForDevice(device)
                + ", actions=" + describeMappedActions(mapped)
                + ", cursor=" + org.lwjgl.glfw.CallbackBridge.mouseX + ","
                + org.lwjgl.glfw.CallbackBridge.mouseY);

        performMappedActions(mapped, isDown);
    }

    @NonNull
    private ActiveMappedAction[] resolveMappedActions(
            @NonNull GamepadButton button,
            @Nullable InputDevice device
    ) {
        boolean gameMode = isGameMode();
        GamepadAction[] actions = mappingStore.getButtonActions(button, gameMode, device);
        ActiveMappedAction[] mapped = new ActiveMappedAction[actions.length];

        for (int slot = 0; slot < actions.length; slot++) {
            GamepadAction action = actions[slot] == null ? GamepadAction.NONE : actions[slot];

            // Guard against old saved prefs from earlier patches where menu A/R2 were ENTER.
            // Those prefs survive reinstall/rebuild and make it look like A is not mapped to click.
            if (!gameMode && (button == GamepadButton.BUTTON_A
                    || button == GamepadButton.BUTTON_R2
                    || button == GamepadButton.DPAD_CENTER)
                    && action == GamepadAction.ENTER) {
                Logging.i(TAG, "Overriding old menu " + button + " slot " + slot + " ENTER mapping to MOUSE_LEFT");
                action = GamepadAction.MOUSE_LEFT;
            }

            boolean pulseMenuMouseClick = !gameMode && action.isMouseButton();
            mapped[slot] = new ActiveMappedAction(action, pulseMenuMouseClick, gameMode);
        }
        return mapped;
    }

    private static boolean containsMenuEscape(@NonNull ActiveMappedAction[] mapped) {
        for (ActiveMappedAction action : mapped) {
            if (action != null && !action.gameMode && action.action == GamepadAction.ESCAPE) {
                return true;
            }
        }
        return false;
    }

    private static void performMappedActions(@NonNull ActiveMappedAction[] mapped, boolean isDown) {
        if (isDown) {
            for (ActiveMappedAction action : mapped) {
                if (action != null && action.action != GamepadAction.NONE) {
                    action.action.perform(true, action.pulseMenuMouseClick);
                }
            }
        } else {
            for (int i = mapped.length - 1; i >= 0; i--) {
                ActiveMappedAction action = mapped[i];
                if (action != null && action.action != GamepadAction.NONE) {
                    action.action.perform(false, action.pulseMenuMouseClick);
                }
            }
        }
    }

    @NonNull
    private static String describeMappedActions(@NonNull ActiveMappedAction[] mapped) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < mapped.length; i++) {
            ActiveMappedAction action = mapped[i];
            if (action == null || action.action == GamepadAction.NONE) continue;
            if (builder.length() > 0) builder.append(" + ");
            builder.append(i).append(":").append(action.action.name());
        }
        return builder.length() == 0 ? "NONE" : builder.toString();
    }

    private void releaseAllMappedButtons() {
        if (activeButtonActions.isEmpty()) return;
        for (ActiveMappedAction[] mapped : activeButtonActions.values()) {
            if (mapped != null) {
                performMappedActions(mapped, false);
            }
        }
        activeButtonActions.clear();
    }

    private void updateHatButton(
            @NonNull GamepadButton button,
            boolean isDown,
            @NonNull InputDevice device
    ) {
        switch (button) {
            case DPAD_UP:
                if (hatUp == isDown) return;
                hatUp = isDown;
                sendMappedButton(button, isDown, device);
                break;
            case DPAD_DOWN:
                if (hatDown == isDown) return;
                hatDown = isDown;
                sendMappedButton(button, isDown, device);
                break;
            case DPAD_LEFT:
                if (hatLeft == isDown) return;
                hatLeft = isDown;
                sendMappedButton(button, isDown, device);
                break;
            case DPAD_RIGHT:
                if (hatRight == isDown) return;
                hatRight = isDown;
                sendMappedButton(button, isDown, device);
                break;
            default:
                break;
        }
    }

    private void updateTrigger(boolean left, boolean isDown, @NonNull InputDevice device) {
        if (left) {
            if (leftTriggerDown == isDown) return;
            leftTriggerDown = isDown;
            sendMappedButton(GamepadButton.BUTTON_L2, isDown, device);
        } else {
            if (rightTriggerDown == isDown) return;
            rightTriggerDown = isDown;
            sendMappedButton(GamepadButton.BUTTON_R2, isDown, device);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ActiveMappedAction {
        @NonNull final GamepadAction action;
        final boolean pulseMenuMouseClick;
        final boolean gameMode;

        ActiveMappedAction(
                @NonNull GamepadAction action,
                boolean pulseMenuMouseClick,
                boolean gameMode
        ) {
            this.action = action;
            this.pulseMenuMouseClick = pulseMenuMouseClick;
            this.gameMode = gameMode;
        }
    }
}
