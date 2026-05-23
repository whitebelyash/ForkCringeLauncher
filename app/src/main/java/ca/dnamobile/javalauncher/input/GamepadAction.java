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

import androidx.annotation.Nullable;

import ca.dnamobile.javalauncher.controls.TouchControlData;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;

import org.lwjgl.glfw.CallbackBridge;

/**
 * Actions a physical Android gamepad button can perform.
 */
public enum GamepadAction {
    NONE(Type.NONE, 0, 0, "None"),

    JUMP(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_SPACE, 0, "Jump / Space"),
    INVENTORY(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_E, 0, "Inventory / E"),
    DROP(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_Q, 0, "Drop / Q"),
    OFFHAND(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F, 0, "Offhand / F"),
    SPRINT(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL, 0, "Sprint / Ctrl"),
    SNEAK(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT, 0, "Sneak / Shift"),

    FORWARD(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_W, 0, "Forward / W"),
    BACKWARD(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_S, 0, "Back / S"),
    LEFT(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_A, 0, "Left / A"),
    RIGHT(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_D, 0, "Right / D"),

    MOUSE_LEFT(Type.MOUSE, LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, 0, "Mouse Left Click"),
    MOUSE_RIGHT(Type.MOUSE, LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, 0, "Mouse Right Click"),
    MOUSE_MIDDLE(Type.MOUSE, LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE, 0, "Mouse Middle Click"),
    SCROLL_UP(Type.SCROLL, 1, 0, "Mouse Wheel Up"),
    SCROLL_DOWN(Type.SCROLL, -1, 0, "Mouse Wheel Down"),
    CURSOR_UP(Type.CURSOR, 0, -72, "Cursor Up"),
    CURSOR_DOWN(Type.CURSOR, 0, 72, "Cursor Down"),
    CURSOR_LEFT(Type.CURSOR, -72, 0, "Cursor Left"),
    CURSOR_RIGHT(Type.CURSOR, 72, 0, "Cursor Right"),

    ESCAPE(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_ESCAPE, 0, "Escape"),
    ENTER(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_ENTER, 0, "Enter"),
    TAB(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_TAB, 0, "Tab"),
    BACKSPACE(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_BACKSPACE, 0, "Backspace"),
    INSERT(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_INSERT, 0, "Insert"),
    DELETE(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_DELETE, 0, "Delete"),
    HOME(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_HOME, 0, "Home"),
    END(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_END, 0, "End"),
    PAGE_UP(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_PAGE_UP, 0, "Page Up"),
    PAGE_DOWN(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_PAGE_DOWN, 0, "Page Down"),
    ARROW_UP(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_UP, 0, "Arrow Up"),
    ARROW_DOWN(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_DOWN, 0, "Arrow Down"),
    ARROW_LEFT(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_LEFT, 0, "Arrow Left"),
    ARROW_RIGHT(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_RIGHT, 0, "Arrow Right"),
    MENU(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_MENU, 0, "Menu Key"),

    F1(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F1, 0, "F1"),
    F2(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F2, 0, "F2"),
    F3(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F3, 0, "F3"),
    F4(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F4, 0, "F4"),
    F5(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F5, 0, "F5"),
    F6(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F6, 0, "F6"),
    F7(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F7, 0, "F7"),
    F8(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F8, 0, "F8"),
    F9(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F9, 0, "F9"),
    F10(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F10, 0, "F10"),
    F11(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F11, 0, "F11"),
    F12(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F12, 0, "F12"),

    KEY_A(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_A, 0, "A"),
    KEY_B(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_B, 0, "B"),
    KEY_C(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_C, 0, "C"),
    KEY_D(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_D, 0, "D"),
    KEY_E(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_E, 0, "E"),
    KEY_F(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_F, 0, "F"),
    KEY_G(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_G, 0, "G"),
    KEY_H(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_H, 0, "H"),
    KEY_I(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_I, 0, "I"),
    KEY_J(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_J, 0, "J"),
    KEY_K(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_K, 0, "K"),
    KEY_L(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_L, 0, "L"),
    KEY_M(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_M, 0, "M"),
    KEY_N(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_N, 0, "N"),
    KEY_O(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_O, 0, "O"),
    KEY_P(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_P, 0, "P"),
    KEY_Q(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_Q, 0, "Q"),
    KEY_R(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_R, 0, "R"),
    KEY_S(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_S, 0, "S"),
    KEY_T(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_T, 0, "T"),
    KEY_U(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_U, 0, "U"),
    KEY_V(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_V, 0, "V"),
    KEY_W(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_W, 0, "W"),
    KEY_X(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_X, 0, "X"),
    KEY_Y(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_Y, 0, "Y"),
    KEY_Z(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_Z, 0, "Z"),

    KEY_0(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_0, 0, "0"),
    KEY_1(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_1, 0, "1"),
    KEY_2(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_2, 0, "2"),
    KEY_3(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_3, 0, "3"),
    KEY_4(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_4, 0, "4"),
    KEY_5(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_5, 0, "5"),
    KEY_6(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_6, 0, "6"),
    KEY_7(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_7, 0, "7"),
    KEY_8(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_8, 0, "8"),
    KEY_9(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_9, 0, "9"),

    SPACE(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_SPACE, 0, "Space"),
    MINUS(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_MINUS, 0, "-"),
    EQUAL(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_EQUAL, 0, "="),
    LEFT_BRACKET(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_LEFT_BRACKET, 0, "["),
    RIGHT_BRACKET(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_RIGHT_BRACKET, 0, "]"),
    BACKSLASH(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_BACKSLASH, 0, "\\"),
    SEMICOLON(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_SEMICOLON, 0, ";"),
    APOSTROPHE(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_APOSTROPHE, 0, "'"),
    GRAVE(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_GRAVE_ACCENT, 0, "`"),
    COMMA(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_COMMA, 0, ","),
    PERIOD(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_PERIOD, 0, "."),
    SLASH(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_SLASH, 0, "/"),

    LEFT_SHIFT(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT, 0, "Left Shift"),
    LEFT_CTRL(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL, 0, "Left Ctrl"),
    LEFT_ALT(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT, 0, "Left Alt"),
    RIGHT_SHIFT(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_RIGHT_SHIFT, 0, "Right Shift"),
    RIGHT_CTRL(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_RIGHT_CONTROL, 0, "Right Ctrl"),
    RIGHT_ALT(Type.KEY, LwjglGlfwKeycode.GLFW_KEY_RIGHT_ALT, 0, "Right Alt");

    private enum Type {
        NONE,
        KEY,
        MOUSE,
        SCROLL,
        CURSOR
    }

    private final Type type;
    private final int code;
    private final int code2;
    private final String displayName;

    GamepadAction(Type type, int code, int code2, String displayName) {
        this.type = type;
        this.code = code;
        this.code2 = code2;
        this.displayName = displayName;
    }

    public boolean isMouseButton() {
        return type == Type.MOUSE;
    }

    public boolean isCursorAction() {
        return type == Type.CURSOR;
    }

    public float getCursorDx() {
        return type == Type.CURSOR ? code : 0f;
    }

    public float getCursorDy() {
        return type == Type.CURSOR ? code2 : 0f;
    }

    public void perform(boolean down) {
        perform(down, false);
    }

    public void perform(boolean down, boolean pulseMouseClick) {
        switch (type) {
            case NONE:
                return;

            case KEY:
                CallbackBridge.sendKeyPress(code, CallbackBridge.getCurrentMods(), down);
                CallbackBridge.setModifiers(code, down);
                return;

            case MOUSE:
                if (pulseMouseClick) {
                    if (down) {
                        CallbackBridge.putMouseEventWithCoords(code, CallbackBridge.mouseX, CallbackBridge.mouseY);
                    }
                } else {
                    CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                    CallbackBridge.sendMouseButton(code, down);
                }
                return;

            case SCROLL:
                if (down) {
                    CallbackBridge.sendScroll(0, code);
                }
                return;

            case CURSOR:
                if (down) {
                    moveCursorBy(code, code2);
                }
        }
    }

    public static void moveCursorBy(float dx, float dy) {
        float width = Math.max(1f, CallbackBridge.windowWidth > 0
                ? CallbackBridge.windowWidth : CallbackBridge.physicalWidth);
        float height = Math.max(1f, CallbackBridge.windowHeight > 0
                ? CallbackBridge.windowHeight : CallbackBridge.physicalHeight);

        // GLFW window coordinates are zero-based. Clamping to width/height can
        // land exactly outside the last valid pixel on old Minecraft GUIs, while
        // clamping the drawable by its size made the visible cursor stop early.
        // Keep the input hotspot inside 0..width-1 and 0..height-1.
        CallbackBridge.setInputReady(true);
        CallbackBridge.mouseX = clamp(CallbackBridge.mouseX + dx, 0f, maxCursorCoordinate(width));
        CallbackBridge.mouseY = clamp(CallbackBridge.mouseY + dy, 0f, maxCursorCoordinate(height));
        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
    }

    private static float maxCursorCoordinate(float size) {
        return Math.max(0f, size - 1f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Maps the shared touch-control keyboard picker values to controller actions.
     * Returns null for touch-only launcher actions that the physical gamepad bridge
     * cannot replay as a Minecraft/gamepad action.
     */
    @Nullable
    public static GamepadAction fromKeyboardPickerCode(int keyCode) {
        switch (keyCode) {
            case 0:
                return NONE;
            case TouchControlData.SPECIAL_MOUSE_LEFT:
                return MOUSE_LEFT;
            case TouchControlData.SPECIAL_MOUSE_RIGHT:
                return MOUSE_RIGHT;
            case TouchControlData.SPECIAL_MOUSE_MIDDLE:
                return MOUSE_MIDDLE;
            case TouchControlData.SPECIAL_SCROLL_UP:
                return SCROLL_UP;
            case TouchControlData.SPECIAL_SCROLL_DOWN:
                return SCROLL_DOWN;
            case TouchControlData.SPECIAL_KEYBOARD:
            case TouchControlData.SPECIAL_KEY_SENDER_KEYBOARD:
            case TouchControlData.SPECIAL_MENU:
            case TouchControlData.SPECIAL_TOGGLE_CONTROLS:
            case TouchControlData.SPECIAL_VIRTUAL_MOUSE:
                return null;
            default:
                for (GamepadAction action : values()) {
                    if (action.type == Type.KEY && action.code == keyCode) {
                        return action;
                    }
                }
                return null;
        }
    }

    @Override
    public String toString() {
        return displayName;
    }
}
