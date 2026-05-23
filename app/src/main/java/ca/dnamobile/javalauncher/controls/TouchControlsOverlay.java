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

package ca.dnamobile.javalauncher.controls;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.util.SparseArray;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.Locale;
import java.util.List;

import org.json.JSONObject;
import org.lwjgl.glfw.CallbackBridge;

import ca.dnamobile.javalauncher.feature.log.Logging;
import ca.dnamobile.javalauncher.input.GameCursorOverlay;
import ca.dnamobile.javalauncher.utils.path.PathManager;
import net.kdt.pojavlaunch.MinecraftGLSurface;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;

/**
 * Runtime/editor overlay. It deliberately avoids XML so it can be injected over the
 * existing Minecraft surface without rewriting activity_game.xml.
 */
public final class TouchControlsOverlay extends FrameLayout implements TouchControlButtonView.Listener {
    public interface AppMenuListener {
        void onTouchControlsMenuRequested();
    }

    private static final String TAG = "TouchControlsOverlay";
    private static final int MAX_EDIT_HISTORY = 4;

    @NonNull private final ArrayDeque<String> undoHistory = new ArrayDeque<>();
    @NonNull private final ArrayDeque<String> redoHistory = new ArrayDeque<>();

    private boolean editMode;
    private boolean controlsVisible = true;
    private boolean rebuildPending;
    @Nullable private File layoutFile;
    @NonNull private TouchControlsLayoutData layoutData = TouchControlsLayoutData.defaultLayout();
    @Nullable private AppMenuListener appMenuListener;
    @Nullable private View passthroughTarget;

    /**
     * Optional explicit Minecraft options.txt for the active instance.
     * If GameActivity does not set this, the overlay falls back to the current
     * PathManager.DIR_MINECRAFT_HOME/options.txt so the hotbar hitbox can still
     * follow Minecraft GUI-scale changes in game.
     */
    @Nullable private File minecraftOptionsFile;
    @Nullable private File cachedMinecraftOptionsFile;
    private long lastMinecraftOptionsResolveAtMs;
    private static final long OPTIONS_FILE_RESOLVE_THROTTLE_MS = 1000L;

    /**
     * Virtual cursor is only a GUI/menu cursor. While Minecraft has grabbed the
     * mouse for normal gameplay, the launcher must not draw or route the fake
     * cursor or it will fight camera movement. Keep this cursor separate from
     * CallbackBridge.mouseX/mouseY because those coordinates are also used by
     * grabbed camera-look deltas while the player is moving around the world.
     */
    private boolean lastVirtualMousePreference;
    private boolean lastKnownMouseGrabbed = true;
    private boolean androidPointerIconHidden;
    private boolean pointerIconReapplyPending;
    @Nullable private PointerIcon transparentPointerIcon;
    private boolean virtualCursorInitialized;
    private float virtualCursorBridgeX;
    private float virtualCursorBridgeY;
    @Nullable private Bitmap virtualCursorBitmap;
    @NonNull private String loadedVirtualCursorStyle = "";
    @Nullable private String loadedVirtualCursorCustomPath;
    private int loadedVirtualCursorSizePercent = -1;

    /**
     * Runtime multi-touch routing:
     * - pointers that begin on a touch button are owned by that button
     * - the first pointer that begins on empty space is forwarded to MinecraftGLSurface as
     *   a clean single-pointer mouse/camera stream
     *
     * Android may reorder pointer indexes when a finger lifts/re-enters. Tracking by
     * pointer ID keeps the camera finger stable while other fingers hold buttons.
     *
     * Do not forward the full MotionEvent or a split multi-pointer stream to Minecraft
     * while buttons are held. The game surface expects a normal DOWN/MOVE/UP sequence
     * for camera look; sending ACTION_POINTER_DOWN/UP while another finger owns a button
     * can make the camera jump left/right when the look finger is lifted and placed back.
     */
    private static final int NO_POINTER_ID = -1;
    private static final int MOUSE_BUTTON_LEFT = 0;
    private static final int MOUSE_BUTTON_RIGHT = 1;

    private boolean keySenderKeyboardVisible;
    @Nullable private View keySenderKeyboardView;

    private final Handler gestureHandler = new Handler(Looper.getMainLooper());
    private final int cameraTouchSlop;

    /** Pointer ID for the right-thumb look/attack stream. */
    private int cameraPointerId = NO_POINTER_ID;
    private float cameraDownX;
    private float cameraDownY;
    private float cameraLastX;
    private float cameraLastY;
    private boolean cameraMovedPastSlop;
    private boolean cameraLongPressAttackActive;
    @Nullable private Runnable cameraLongPressRunnable;

    /** GUI fallback: used only when Minecraft is not grabbing the mouse. */
    private int passthroughPointerId = NO_POINTER_ID;
    private long passthroughDownTime;
    private float passthroughDownX;
    private float passthroughDownY;
    private boolean passthroughMovedPastSlop;
    /**
     * Menu fake-mouse routing. When the virtual mouse preference is enabled,
     * empty-space touches in Minecraft GUIs act like a small touchpad instead
     * of absolute touchscreen clicks. The cursor itself is drawn by this overlay
     * so devices do not depend on Android/Minecraft cursor visibility.
     */
    private int virtualMousePointerId = NO_POINTER_ID;
    private float virtualMouseDownX;
    private float virtualMouseDownY;
    private float virtualMouseLastX;
    private float virtualMouseLastY;
    private boolean virtualMouseMovedPastSlop;

    /** In-game hotbar touch routing. Keep this separate from camera/buttons. */
    private int hotbarPointerId = NO_POINTER_ID;
    private int hotbarLastSlot = -1;
    private boolean hotbarDoubleTapConsumed;
    private int lastHotbarTapSlot = -1;
    private long lastHotbarTapTimeMs;

    @NonNull private final SparseArray<TouchControlButtonView> controlPointerTargets = new SparseArray<>();

    private final Paint hotbarDebugFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hotbarDebugStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hotbarDebugSlotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hotbarDebugTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint virtualCursorFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint virtualCursorStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint virtualCursorShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path virtualCursorPath = new Path();

    public TouchControlsOverlay(@NonNull Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(true);
        setMotionEventSplittingEnabled(true);
        setWillNotDraw(false);

        hotbarDebugFillPaint.setColor(0x44FFEB3B);
        hotbarDebugFillPaint.setStyle(Paint.Style.FILL);

        hotbarDebugStrokePaint.setColor(Color.YELLOW);
        hotbarDebugStrokePaint.setStyle(Paint.Style.STROKE);
        hotbarDebugStrokePaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);

        hotbarDebugSlotPaint.setColor(0xCCFF9800);
        hotbarDebugSlotPaint.setStyle(Paint.Style.STROKE);
        hotbarDebugSlotPaint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);

        hotbarDebugTextPaint.setColor(Color.WHITE);
        hotbarDebugTextPaint.setTextAlign(Paint.Align.CENTER);
        hotbarDebugTextPaint.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
        hotbarDebugTextPaint.setShadowLayer(3f, 0f, 0f, Color.BLACK);

        virtualCursorFillPaint.setColor(Color.WHITE);
        virtualCursorFillPaint.setStyle(Paint.Style.STROKE);
        virtualCursorFillPaint.setStrokeJoin(Paint.Join.ROUND);
        virtualCursorFillPaint.setStrokeCap(Paint.Cap.ROUND);
        virtualCursorFillPaint.setStrokeWidth(2.0f * getResources().getDisplayMetrics().density);

        virtualCursorStrokePaint.setColor(0xFF111111);
        virtualCursorStrokePaint.setStyle(Paint.Style.STROKE);
        virtualCursorStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        virtualCursorStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        virtualCursorStrokePaint.setStrokeWidth(4.2f * getResources().getDisplayMetrics().density);

        // Kept for binary/source compatibility with the previous patch, but the
        // cursor no longer uses a drop shadow. The old shadow was what made the
        // pointer look like two mice stacked on top of each other.
        virtualCursorShadowPaint.setColor(Color.TRANSPARENT);
        virtualCursorShadowPaint.setStyle(Paint.Style.STROKE);
        virtualCursorShadowPaint.setStrokeJoin(Paint.Join.ROUND);
        virtualCursorShadowPaint.setStrokeCap(Paint.Cap.ROUND);
        virtualCursorShadowPaint.setStrokeWidth(0f);

        reloadVirtualCursorBitmapIfNeeded(true);

        cameraTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setPassthroughTarget(@Nullable View passthroughTarget) {
        this.passthroughTarget = passthroughTarget;
        boolean shouldHidePointer = shouldDrawLauncherVirtualCursor();
        androidPointerIconHidden = !shouldHidePointer;
        applyAndroidPointerIconPolicy(shouldHidePointer, true);
    }

    public void setMinecraftOptionsFile(@Nullable File minecraftOptionsFile) {
        this.minecraftOptionsFile = minecraftOptionsFile;
        this.cachedMinecraftOptionsFile = minecraftOptionsFile != null && minecraftOptionsFile.isFile()
                ? minecraftOptionsFile
                : null;
        this.lastMinecraftOptionsResolveAtMs = 0L;
        MinecraftGuiScaleResolver.clearCache();
        invalidate();
    }

    public void setAppMenuListener(@Nullable AppMenuListener appMenuListener) {
        this.appMenuListener = appMenuListener;
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        if (editMode) hideKeySenderKeyboard();
        rebuildWhenSized();
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        setVisibility(VISIBLE);
        applyControlsVisualState();
    }

    public void toggleControlVisible() {
        setControlsVisible(!controlsVisible);
        ControlsPreferences.setTouchControlsEnabled(getContext(), controlsVisible);
    }

    public void loadSelectedLayout() {
        layoutFile = TouchControlsStore.getSelectedLayoutFile(getContext());
        layoutData = TouchControlsStore.loadLayout(layoutFile);
        clearEditHistory();
        rebuildWhenSized();
    }

    public void loadLayout(@NonNull File file) {
        layoutFile = file;
        layoutData = TouchControlsStore.loadLayout(file);
        ControlsPreferences.setSelectedLayoutPath(getContext(), file.getAbsolutePath());
        clearEditHistory();
        rebuildWhenSized();
    }

    public void saveLayout() {
        try {
            normalizeUnstablePixelLayoutBeforeSave();
            File target = layoutFile != null ? layoutFile : TouchControlsStore.getSelectedLayoutFile(getContext());
            TouchControlsStore.saveLayout(target, layoutData);
            layoutFile = target;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to save touch controls", throwable);
            String message = throwable.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = throwable.getClass().getSimpleName();
            }
            Toast.makeText(getContext(), "Unable to save touch controls: " + message, Toast.LENGTH_LONG).show();
        }
    }

    public void addControl(@NonNull TouchControlData data) {
        pushUndoSnapshot();
        layoutData.controls.add(data);
        saveLayout();
        rebuildWhenSized();
    }

    public boolean undoLastChange() {
        if (undoHistory.isEmpty()) return false;
        try {
            String current = snapshotLayout();
            String previous = undoHistory.removeLast();
            pushBounded(redoHistory, current);
            layoutData = TouchControlsLayoutData.fromJson(new JSONObject(previous));
            saveLayout();
            rebuildWhenSized();
            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to undo touch layout change", throwable);
            return false;
        }
    }

    public boolean redoLastChange() {
        if (redoHistory.isEmpty()) return false;
        try {
            String current = snapshotLayout();
            String next = redoHistory.removeLast();
            pushBounded(undoHistory, current);
            layoutData = TouchControlsLayoutData.fromJson(new JSONObject(next));
            saveLayout();
            rebuildWhenSized();
            return true;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to redo touch layout change", throwable);
            return false;
        }
    }

    private void pushUndoSnapshot() {
        pushUndoSnapshot(snapshotLayoutSafely());
    }

    private void pushUndoSnapshot(@Nullable String snapshot) {
        if (snapshot == null || snapshot.trim().isEmpty()) return;
        redoHistory.clear();
        if (!undoHistory.isEmpty() && snapshot.equals(undoHistory.peekLast())) return;
        pushBounded(undoHistory, snapshot);
    }

    private void pushBounded(@NonNull ArrayDeque<String> target, @NonNull String snapshot) {
        if (!target.isEmpty() && snapshot.equals(target.peekLast())) return;
        target.addLast(snapshot);
        while (target.size() > MAX_EDIT_HISTORY) {
            target.removeFirst();
        }
    }

    @Nullable
    private String snapshotLayoutSafely() {
        try {
            return snapshotLayout();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to snapshot touch layout", throwable);
            return null;
        }
    }

    @NonNull
    private String snapshotLayout() throws Exception {
        return layoutData.toJson().toString();
    }

    private void clearEditHistory() {
        undoHistory.clear();
        redoHistory.clear();
    }

    @NonNull
    public TouchControlsLayoutData getLayoutData() {
        return layoutData;
    }

    private void rebuildWhenSized() {
        if (getWidth() <= 1 || getHeight() <= 1) {
            if (!rebuildPending) {
                rebuildPending = true;
                post(() -> {
                    rebuildPending = false;
                    if (getWidth() > 1 && getHeight() > 1) {
                        rebuild();
                    }
                });
            }
            return;
        }
        rebuild();
    }

    private void rebuild() {
        if (getWidth() <= 1 || getHeight() <= 1) {
            rebuildWhenSized();
            return;
        }

        removeAllViews();
        int parentWidth = Math.max(1, getWidth());
        int parentHeight = Math.max(1, getHeight());
        LayoutMetrics metrics = layoutMetrics(parentWidth, parentHeight);
        float density = getResources().getDisplayMetrics().density;
        for (TouchControlData control : layoutData.controls) {
            if (!editMode && !shouldCreateControlButton(control)) continue;
            TouchControlButtonView button = new TouchControlButtonView(getContext(), control, this);
            button.setEditMode(editMode);
            button.setVisibility(shouldShowControlButton(control) ? VISIBLE : INVISIBLE);
            int width = Math.min(metrics.toScreenWidth(control.width), parentWidth);
            int height = Math.min(metrics.toScreenHeight(control.height), parentHeight);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
            addView(button, params);

            float fallbackX = metrics.toScreenX(control, width);
            float fallbackY = metrics.toScreenY(control, height);
            float resolvedX = control.rawX == null
                    ? fallbackX
                    : ExpressionResolver.resolve(control.rawX, fallbackX, parentWidth, parentHeight, width, height, density, layoutData.preferredScale, metrics.formulaPixelScale);
            float resolvedY = control.rawY == null
                    ? fallbackY
                    : ExpressionResolver.resolve(control.rawY, fallbackY, parentWidth, parentHeight, width, height, density, layoutData.preferredScale, metrics.formulaPixelScale);
            resolvedX = Math.max(0f, Math.min(Math.max(0, parentWidth - width), resolvedX));
            resolvedY = Math.max(0f, Math.min(Math.max(0, parentHeight - height), resolvedY));
            button.setX(resolvedX);
            button.setY(resolvedY);
        }

        if (keySenderKeyboardVisible && !editMode) {
            attachKeySenderKeyboardView();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        rebuildWhenSized();
        post(() -> applyAndroidPointerIconPolicy(shouldUseLauncherVirtualCursorNoPolicy(), true));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) rebuildWhenSized();
        post(() -> applyAndroidPointerIconPolicy(shouldUseLauncherVirtualCursorNoPolicy(), true));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        drawVirtualMouseCursor(canvas);
        drawHotbarHitboxDebug(canvas);
    }

    private void drawVirtualMouseCursor(@NonNull Canvas canvas) {
        // GameCursorOverlay is now the single visible menu cursor renderer for
        // both controller cursor mode and touch virtual-mouse mode. If it is
        // attached, do not draw a second cursor from this overlay; otherwise
        // Redmagic/handheld devices show the old arrow or a doubled cursor.
        // Keep the Android pointer hide policy active while the virtual mouse
        // is in GUI/menu mode, but leave the pixels to GameCursorOverlay.
        if (hasGameCursorOverlayInViewTree()) {
            applyAndroidPointerIconPolicy(shouldUseLauncherVirtualCursorNoPolicy());
            return;
        }

        if (!shouldDrawLauncherVirtualCursor()) return;

        reloadVirtualCursorBitmapIfNeeded(false);
        ensureVirtualMouseCursorInBounds();

        float cursorX = bridgeCursorToViewX(virtualCursorBridgeX);
        float cursorY = bridgeCursorToViewY(virtualCursorBridgeY);
        if (Float.isNaN(cursorX) || Float.isInfinite(cursorX)) cursorX = getWidth() / 2f;
        if (Float.isNaN(cursorY) || Float.isInfinite(cursorY)) cursorY = getHeight() / 2f;

        cursorX = clamp(cursorX, 0f, Math.max(0f, getWidth() - 1f));
        cursorY = clamp(cursorY, 0f, Math.max(0f, getHeight() - 1f));

        Bitmap bitmap = virtualCursorBitmap;
        if (bitmap != null && !bitmap.isRecycled()) {
            float size = 26f
                    * getResources().getDisplayMetrics().density
                    * ControlsPreferences.getMouseCursorSizePercent(getContext())
                    / 100f;
            RectF dst = new RectF(
                    cursorX - (size / 2f),
                    cursorY - (size / 2f),
                    cursorX + (size / 2f),
                    cursorY + (size / 2f)
            );
            canvas.drawBitmap(bitmap, null, dst, null);
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float arm = 13f * density;
        float gap = 4f * density;
        float ringRadius = 7.5f * density;

        // Mojo-style virtual mouse: a clean crosshair instead of a large arrow.
        // Draw black first as an outline, then white on top. No offset shadow,
        // because the old shadow looked like a second cursor on some screens.
        drawVirtualCursorCrosshair(canvas, cursorX, cursorY, arm, gap, ringRadius, virtualCursorStrokePaint);
        drawVirtualCursorCrosshair(canvas, cursorX, cursorY, arm, gap, ringRadius, virtualCursorFillPaint);
    }

    private void drawVirtualCursorCrosshair(
            @NonNull Canvas canvas,
            float x,
            float y,
            float arm,
            float gap,
            float ringRadius,
            @NonNull Paint paint
    ) {
        canvas.drawLine(x - arm, y, x - gap, y, paint);
        canvas.drawLine(x + gap, y, x + arm, y, paint);
        canvas.drawLine(x, y - arm, x, y - gap, paint);
        canvas.drawLine(x, y + gap, x, y + arm, paint);
        canvas.drawCircle(x, y, ringRadius, paint);
    }

    private void reloadVirtualCursorBitmapIfNeeded(boolean force) {
        String style = ControlsPreferences.getMouseCursorStyle(getContext());
        String customPath = ControlsPreferences.getCustomMouseCursorPath(getContext());
        int sizePercent = ControlsPreferences.getMouseCursorSizePercent(getContext());

        boolean sameStyle = style.equals(loadedVirtualCursorStyle);
        boolean samePath = customPath == null
                ? loadedVirtualCursorCustomPath == null
                : customPath.equals(loadedVirtualCursorCustomPath);
        boolean sameSize = sizePercent == loadedVirtualCursorSizePercent;
        if (!force && sameStyle && samePath && sameSize) return;

        loadedVirtualCursorStyle = style;
        loadedVirtualCursorCustomPath = customPath;
        loadedVirtualCursorSizePercent = sizePercent;
        virtualCursorBitmap = loadVirtualCursorBitmap(getContext(), style, customPath);
        postInvalidateOnAnimation();
    }

    @Nullable
    private Bitmap loadVirtualCursorBitmap(
            @NonNull Context context,
            @NonNull String style,
            @Nullable String customPath
    ) {
        if (ControlsPreferences.MOUSE_CURSOR_STYLE_CUSTOM.equals(style) && customPath != null) {
            try {
                File file = new File(customPath);
                if (file.isFile() && file.length() > 0L) {
                    Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (bitmap != null) return bitmap;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            int id = context.getResources().getIdentifier(
                    ControlsPreferences.getMouseCursorResourceName(style),
                    "drawable",
                    context.getPackageName()
            );
            return id != 0 ? BitmapFactory.decodeResource(context.getResources(), id) : null;
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to load virtual mouse cursor image", throwable);
            return null;
        }
    }

    private void applyAndroidPointerIconPolicy(boolean hidePointerIcon) {
        applyAndroidPointerIconPolicy(hidePointerIcon, false);
    }

    private void applyAndroidPointerIconPolicy(boolean hidePointerIcon, boolean force) {
        applyAndroidPointerIconPolicy(hidePointerIcon, force, true);
    }

    private void applyAndroidPointerIconPolicy(boolean hidePointerIcon, boolean force, boolean scheduleReapply) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        // When hiding the system/native pointer, do not skip re-applying just
        // because our last request was also hidden. Several devices reset the
        // pointer icon when the focused child view changes between the overlay,
        // TextureView, SurfaceView, and MinecraftGLSurface. Re-applying to the
        // entire view tree is what prevents the native arrow from sitting on top
        // of the launcher-drawn Mojo-style crosshair.
        if (!hidePointerIcon && !force && !androidPointerIconHidden) return;

        try {
            PointerIcon icon = hidePointerIcon
                    ? invisiblePointerIcon()
                    : PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_DEFAULT);

            applyPointerIconToViewTree(getRootView(), icon);
            applyPointerIconToViewTree(this, icon);
            applyPointerIconToViewTree(passthroughTarget, icon);

            ViewParent parent = getParent();
            if (parent instanceof View) {
                applyPointerIconToViewTree((View) parent, icon);
            }

            androidPointerIconHidden = hidePointerIcon;
            if (hidePointerIcon && scheduleReapply) schedulePointerIconReapply();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to update Android pointer icon", throwable);
        }
    }

    @NonNull
    private PointerIcon invisiblePointerIcon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                if (transparentPointerIcon == null) {
                    Bitmap transparent = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                    transparentPointerIcon = PointerIcon.create(transparent, 0f, 0f);
                }
                if (transparentPointerIcon != null) return transparentPointerIcon;
            } catch (Throwable ignored) {
            }
        }
        return PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_NULL);
    }

    private void applyPointerIconToViewTree(@Nullable View view, @NonNull PointerIcon icon) {
        if (view == null) return;

        try {
            view.setPointerIcon(icon);
        } catch (Throwable ignored) {
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyPointerIconToViewTree(group.getChildAt(i), icon);
            }
        }
    }

    private void schedulePointerIconReapply() {
        if (pointerIconReapplyPending) return;
        pointerIconReapplyPending = true;
        postDelayed(() -> reapplyHiddenPointerIconIfNeeded(false), 90L);
        postDelayed(() -> reapplyHiddenPointerIconIfNeeded(true), 320L);
    }

    private void reapplyHiddenPointerIconIfNeeded(boolean finalPass) {
        if (shouldUseLauncherVirtualCursorNoPolicy()) {
            applyAndroidPointerIconPolicy(true, true, false);
        }
        if (finalPass) pointerIconReapplyPending = false;
    }

    private boolean shouldUseLauncherVirtualCursorNoPolicy() {
        return !editMode
                && ControlsPreferences.isVirtualMouseEnabled(getContext())
                && !updateMouseGrabState();
    }

    private boolean shouldDrawLauncherVirtualCursor() {
        if (editMode) {
            applyAndroidPointerIconPolicy(false);
            return false;
        }

        boolean enabled = ControlsPreferences.isVirtualMouseEnabled(getContext());
        boolean grabbed = updateMouseGrabState();
        updateVirtualMousePreferenceState(enabled, grabbed);

        boolean draw = enabled && !grabbed;
        applyAndroidPointerIconPolicy(draw);
        return draw;
    }

    private void updateVirtualMousePreferenceState(boolean enabled, boolean grabbed) {
        if (enabled == lastVirtualMousePreference) return;
        lastVirtualMousePreference = enabled;
        if (enabled) {
            if (!grabbed) resetVirtualMouseCursorToCenter(true);
            else virtualCursorInitialized = false;
        } else {
            virtualCursorInitialized = false;
            applyAndroidPointerIconPolicy(false);
        }
    }

    /**
     * Returns the current grab state and resets the fake cursor exactly once when
     * Minecraft opens a GUI. This prevents camera movement during normal gameplay
     * from dragging the menu cursor away from the center before the menu appears.
     */
    private boolean updateMouseGrabState() {
        boolean grabbed = isMouseGrabbed();
        if (grabbed != lastKnownMouseGrabbed) {
            lastKnownMouseGrabbed = grabbed;
            if (!grabbed && ControlsPreferences.isVirtualMouseEnabled(getContext())) {
                resetVirtualMouseCursorToCenter(true);
            } else if (grabbed) {
                cancelVirtualMousePointer();
                applyAndroidPointerIconPolicy(false);
            }
            applyControlsVisualStateForGrabState(grabbed);
            postInvalidateOnAnimation();
        }
        return grabbed;
    }

    private void drawHotbarHitboxDebug(@NonNull Canvas canvas) {
        if (!ControlsPreferences.isHotbarHitboxDebugEnabled(getContext())) return;

        File optionsFile = resolveMinecraftOptionsFile();
        TouchHotbarHitbox.Result hitbox = TouchHotbarHitbox.calculate(
                getContext(),
                optionsFile,
                getWidth(),
                getHeight(),
                resolveGameBufferWidth(),
                resolveGameBufferHeight()
        );

        RectF touchBounds = hitbox.touchBounds;
        RectF hotbarBounds = hitbox.hotbarBounds;

        canvas.drawRect(touchBounds, hotbarDebugFillPaint);
        canvas.drawRect(touchBounds, hotbarDebugStrokePaint);

        for (int i = 0; i <= TouchHotbarHitbox.SLOT_COUNT; i++) {
            float x = hotbarBounds.left + (i * hitbox.slotWidth);
            canvas.drawLine(x, touchBounds.top, x, touchBounds.bottom, hotbarDebugSlotPaint);
        }

        float textY = touchBounds.top - (6f * getResources().getDisplayMetrics().density);
        if (textY < hotbarDebugTextPaint.getTextSize() + 2f) {
            textY = touchBounds.bottom + hotbarDebugTextPaint.getTextSize() + 4f;
        }

        canvas.drawText(
                "Hotbar hitbox  scale=" + formatScale(hitbox.scale)
                        + "  src=" + hitbox.scaleSourceLabel()
                        + "  mcGui=" + hitbox.minecraftGuiScale
                        + "  override=" + hitbox.overrideScale,
                hotbarBounds.centerX(),
                textY,
                hotbarDebugTextPaint
        );

        canvas.drawText(
                "render=" + formatScale(hitbox.renderScaleX) + "x/" + formatScale(hitbox.renderScaleY)
                        + "  res=" + hitbox.resolutionScalePercent + "%"
                        + "  buffer=" + Math.round(hitbox.gameBufferWidth) + "x" + Math.round(hitbox.gameBufferHeight),
                hotbarBounds.centerX(),
                textY + hotbarDebugTextPaint.getTextSize() + (3f * getResources().getDisplayMetrics().density),
                hotbarDebugTextPaint
        );

        canvas.drawText(
                "options=" + debugOptionsFileName(optionsFile),
                hotbarBounds.centerX(),
                textY + (hotbarDebugTextPaint.getTextSize() * 2f) + (6f * getResources().getDisplayMetrics().density),
                hotbarDebugTextPaint
        );

        for (int slot = 0; slot < TouchHotbarHitbox.SLOT_COUNT; slot++) {
            float centerX = hotbarBounds.left + (slot * hitbox.slotWidth) + (hitbox.slotWidth / 2f);
            canvas.drawText(
                    String.valueOf(slot + 1),
                    centerX,
                    touchBounds.centerY() + (hotbarDebugTextPaint.getTextSize() / 3f),
                    hotbarDebugTextPaint
            );
        }

        postInvalidateOnAnimation();
    }

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent event) {
        // Keep the full-screen overlay attached whether visual controls are shown or hidden.
        // Hidden controls should still allow hotbar taps, camera dragging, and menu passthrough
        // through the same safe routing path instead of dropping to the raw SurfaceView path.
        if (editMode) {
            return super.dispatchTouchEvent(event);
        }

        if (dispatchKeySenderKeyboardTouch(event)) {
            return true;
        }

        if (isHardwarePointerEvent(event)) {
            return dispatchWholeTouchEventToPassthrough(event) || super.dispatchTouchEvent(event);
        }

        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        if (ControlsPreferences.isHotbarHitboxDebugEnabled(getContext())) {
            invalidate();
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                clearRuntimeTouchRouting();
                return routePointerDown(event, actionIndex);

            case MotionEvent.ACTION_POINTER_DOWN:
                routePointerDown(event, actionIndex);
                return hasActiveTouchRoute();

            case MotionEvent.ACTION_MOVE:
                dispatchActiveControlPointers(event, MotionEvent.ACTION_MOVE);
                dispatchActiveHotbarPointer(event);
                dispatchActiveCameraPointer(event);
                dispatchActiveVirtualMousePointer(event);
                dispatchActivePassthroughPointer(event, MotionEvent.ACTION_MOVE);
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                routePointerUp(event, actionIndex, false);
                return true;

            case MotionEvent.ACTION_UP:
                routePointerUp(event, actionIndex, true);
                return true;

            case MotionEvent.ACTION_CANCEL:
                dispatchCancelToControlPointers(event);
                cancelCameraPointer(true);
                cancelVirtualMousePointer();
                dispatchActivePassthroughPointer(event, MotionEvent.ACTION_CANCEL);
                clearRuntimeTouchRouting();
                return true;

            default:
                dispatchActivePassthroughPointer(event, MotionEvent.ACTION_MOVE);
                return true;
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(@NonNull MotionEvent event) {
        if (!editMode && isHardwarePointerEvent(event)) {
            return dispatchWholeGenericEventToPassthrough(event) || super.dispatchGenericMotionEvent(event);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if (!editMode && keySenderKeyboardVisible && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                hideKeySenderKeyboard();
            }
            return true;
        }

        if (!editMode && MinecraftGLSurface.shouldRouteBackKeyToMinecraft(event)) {
            MinecraftGLSurface minecraftSurface = findMinecraftSurfaceTarget();
            if (minecraftSurface != null && minecraftSurface.handleKeyEventFromActivity(event)) {
                return true;
            }
            if (passthroughTarget != null && passthroughTarget.dispatchKeyEvent(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        return editMode && super.onTouchEvent(event);
    }

    @Override
    public void onChanged() {
        saveLayout();
    }

    @Override
    public void onMoveStarted(@NonNull TouchControlButtonView view, @NonNull TouchControlData data) {
        pushUndoSnapshot();
    }

    @Override
    public void onMoveRequested(
            @NonNull TouchControlButtonView view,
            @NonNull TouchControlData data,
            float proposedX,
            float proposedY
    ) {
        float[] snapped = resolveDraggedPosition(view, proposedX, proposedY);
        LayoutMetrics metrics = layoutMetrics(getWidth(), getHeight());

        // X/Y are stored in the layout's own units. DroidBridge layouts use dp;
        // imported default_touch.json / Pojav-style layouts use source-canvas px.
        data.x = metrics.fromScreenX(data, snapped[0], view.getWidth());
        data.y = metrics.fromScreenY(snapped[1]);
        data.rawX = null;
        data.rawY = null;

        view.setX(snapped[0]);
        view.setY(snapped[1]);
    }

    @Override
    public void onEditRequested(@NonNull TouchControlButtonView view, @NonNull TouchControlData data) {
        if (!isAttachedToWindow()) return;
        showEditDialog(view, data);
    }

    @Override
    public void onMenuRequested() {
        if (appMenuListener != null) {
            appMenuListener.onTouchControlsMenuRequested();
        }
    }

    @Override
    public void onToggleControlsRequested() {
        toggleControlVisible();
    }

    @Override
    public void onKeySenderKeyboardRequested() {
        showKeySenderKeyboard();
    }

    @NonNull
    private float[] resolveDraggedPosition(@NonNull View movingView, float proposedX, float proposedY) {
        int width = Math.max(1, movingView.getWidth());
        int height = Math.max(1, movingView.getHeight());
        float maxX = Math.max(0f, getWidth() - width);
        float maxY = Math.max(0f, getHeight() - height);
        float x = clamp(proposedX, 0f, maxX);
        float y = clamp(proposedY, 0f, maxY);

        if (!ControlsPreferences.isSnapControlsEnabled(getContext())) {
            return new float[]{x, y};
        }

        float threshold = 12f * getResources().getDisplayMetrics().density;
        float bestX = x;
        float bestY = y;
        float bestXDelta = threshold + 1f;
        float bestYDelta = threshold + 1f;

        float[] screenXTargets = new float[]{0f, maxX / 2f, maxX};
        for (float target : screenXTargets) {
            float candidateX = clamp(target, 0f, maxX);
            float delta = Math.abs(x - candidateX);
            if (delta <= threshold
                    && delta < bestXDelta
                    && !positionOverlapsAnotherControl(movingView, candidateX, bestY, width, height)) {
                bestX = candidateX;
                bestXDelta = delta;
            }
        }

        float[] screenYTargets = new float[]{0f, maxY / 2f, maxY};
        for (float target : screenYTargets) {
            float candidateY = clamp(target, 0f, maxY);
            float delta = Math.abs(y - candidateY);
            if (delta <= threshold
                    && delta < bestYDelta
                    && !positionOverlapsAnotherControl(movingView, bestX, candidateY, width, height)) {
                bestY = candidateY;
                bestYDelta = delta;
            }
        }

        for (int i = 0; i < getChildCount(); i++) {
            View other = getChildAt(i);
            if (other == movingView || other.getVisibility() != VISIBLE) continue;
            if (!(other instanceof TouchControlButtonView)) continue;
            if (other.getWidth() <= 0 || other.getHeight() <= 0) continue;

            float otherLeft = other.getX();
            float otherTop = other.getY();
            float otherRight = otherLeft + other.getWidth();
            float otherBottom = otherTop + other.getHeight();
            float otherCenterX = otherLeft + other.getWidth() / 2f;
            float otherCenterY = otherTop + other.getHeight() / 2f;

            // Prefer true adjacent-edge snaps first. Alignment snaps are still allowed,
            // but only when the resulting rectangle does not overlap another button.
            float[] xTargets = new float[]{
                    otherLeft - width,
                    otherRight,
                    otherLeft,
                    otherRight - width,
                    otherCenterX - width / 2f
            };
            for (float target : xTargets) {
                float candidateX = clamp(target, 0f, maxX);
                float delta = Math.abs(x - candidateX);
                if (delta <= threshold
                        && delta < bestXDelta
                        && !positionOverlapsAnotherControl(movingView, candidateX, bestY, width, height)) {
                    bestX = candidateX;
                    bestXDelta = delta;
                }
            }

            float[] yTargets = new float[]{
                    otherTop - height,
                    otherBottom,
                    otherTop,
                    otherBottom - height,
                    otherCenterY - height / 2f
            };
            for (float target : yTargets) {
                float candidateY = clamp(target, 0f, maxY);
                float delta = Math.abs(y - candidateY);
                if (delta <= threshold
                        && delta < bestYDelta
                        && !positionOverlapsAnotherControl(movingView, bestX, candidateY, width, height)) {
                    bestY = candidateY;
                    bestYDelta = delta;
                }
            }
        }

        return new float[]{
                clamp(bestX, 0f, maxX),
                clamp(bestY, 0f, maxY)
        };
    }

    private boolean positionOverlapsAnotherControl(
            @NonNull View movingView,
            float x,
            float y,
            int width,
            int height
    ) {
        float left = x;
        float top = y;
        float right = x + Math.max(1, width);
        float bottom = y + Math.max(1, height);

        for (int i = 0; i < getChildCount(); i++) {
            View other = getChildAt(i);
            if (other == movingView || other.getVisibility() != VISIBLE) continue;
            if (!(other instanceof TouchControlButtonView)) continue;
            if (other.getWidth() <= 0 || other.getHeight() <= 0) continue;

            float otherLeft = other.getX();
            float otherTop = other.getY();
            float otherRight = otherLeft + other.getWidth();
            float otherBottom = otherTop + other.getHeight();
            if (rectanglesOverlap(left, top, right, bottom, otherLeft, otherTop, otherRight, otherBottom)) {
                return true;
            }
        }

        return false;
    }

    private static boolean rectanglesOverlap(
            float left,
            float top,
            float right,
            float bottom,
            float otherLeft,
            float otherTop,
            float otherRight,
            float otherBottom
    ) {
        return left < otherRight
                && right > otherLeft
                && top < otherBottom
                && bottom > otherTop;
    }

    private void showEditDialog(@NonNull TouchControlButtonView editingView, @NonNull TouchControlData data) {
        Context context = getContext();

        String originalId = data.id;
        String originalLabel = data.label;
        String originalAction = data.action;
        int originalKeyCode = data.keyCode;
        int[] originalKeyCodes = data.normalizedKeyCodes().clone();
        int[] originalKeySlots = data.normalizedKeySlots().clone();
        int originalMouseButton = data.mouseButton;
        int originalScrollY = data.scrollY;
        float originalX = data.x;
        float originalY = data.y;
        float originalWidth = data.width;
        float originalHeight = data.height;
        float originalSizePercent = data.sizePercent;
        String originalLayoutSnapshot = snapshotLayoutSafely();
        float originalOpacity = data.opacity;
        float originalCornerRadius = data.cornerRadius;
        float originalStrokeWidth = data.strokeWidth;
        int originalStrokeColor = data.strokeColor;
        int originalBackgroundColor = data.backgroundColor;
        boolean originalToggle = data.toggle;
        boolean originalVisibleInGame = data.visibleInGame;
        boolean originalVisibleInMenu = data.visibleInMenu;
        boolean originalVisibleWhenControlsHidden = data.visibleWhenControlsHidden;
        String originalRawX = data.rawX;
        String originalRawY = data.rawY;

        LayoutMetrics metrics = layoutMetrics(getWidth(), getHeight());
        int parentWidthUnits = Math.max(1, Math.round(metrics.maxLayoutXUnits()));
        int parentHeightUnits = Math.max(1, Math.round(metrics.maxLayoutYUnits()));

        float initialLayoutX = data.rawX == null ? data.x : metrics.fromScreenX(data, editingView.getX(), editingView.getWidth());
        float initialLayoutY = data.rawY == null ? data.y : metrics.fromScreenY(editingView.getY());

        ScrollView scrollView = new ScrollView(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(18f);
        layout.setPadding(padding, dp(8f), padding, dp(10f));
        scrollView.addView(layout, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(context);
        summary.setText("Editing: " + (data.label == null || data.label.trim().isEmpty() ? "Button" : data.label.trim()));
        summary.setTextColor(0xFFE0E0E0);
        summary.setTextSize(15f);
        summary.setPadding(0, 0, 0, dp(10f));
        layout.addView(summary);

        addSectionHeader(layout, "Identity", "Set the display name, action, ID, and optional key combo.");

        EditText label = textField(context, "Button label", data.label, false);
        addFieldRow(layout, "Label", label);

        EditText idField = textField(context, "Stable button ID", data.id, false);
        addFieldRow(layout, "Button ID", idField);

        String[] actionLabels = TouchInputBinding.actionLabels();
        String[] actionValues = TouchInputBinding.actionValues();
        Spinner actionSpinner = new Spinner(context);
        ArrayAdapter<String> actionAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, actionLabels);
        actionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        actionSpinner.setAdapter(actionAdapter);
        actionSpinner.setSelection(TouchInputBinding.actionIndex(data.action));
        addSpinnerRow(layout, "Action", actionSpinner);

        Spinner bindingSpinner = new Spinner(context);
        addSpinnerRow(layout, "Binding", bindingSpinner);
        final TouchInputBinding.Option[][] currentOptions = new TouchInputBinding.Option[1][];

        // Keep the raw numeric key slot list internally for saving, but do not show it
        // as the main UI. Users should see friendly names like "Position 0: Left Shift",
        // not GLFW key IDs like "340, 89".
        EditText keyCodes = textField(context, "Internal key slots", joinKeyCodes(data.normalizedKeySlots()), false);
        keyCodes.setVisibility(GONE);
        layout.addView(keyCodes, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
        ));

        TextView boundKeys = valueLabel(context, "Bound buttons: " + TouchInputBinding.friendlyKeyCombo(data.normalizedKeyCodes()));
        layout.addView(boundKeys);

        TextView keySlotHint = valueLabel(context, "Set up to 4 button positions. Each position can be a keyboard key, mouse click, or wheel action and can be changed or cleared without touching the others.");
        layout.addView(keySlotHint);

        TouchInputBinding.Option[] keyOptions = TouchInputBinding.optionsForAction(TouchControlActions.KEY);
        Spinner[] keySlotSpinners = new Spinner[TouchControlData.MAX_ACTION_SLOTS];
        LinearLayout[] keySlotRows = new LinearLayout[TouchControlData.MAX_ACTION_SLOTS];
        int[] startingKeySlots = data.normalizedKeySlots();
        for (int slot = 0; slot < TouchControlData.MAX_ACTION_SLOTS; slot++) {
            LinearLayout slotRow = new LinearLayout(context);
            slotRow.setOrientation(LinearLayout.HORIZONTAL);
            slotRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            slotRow.setPadding(0, dp(2f), 0, dp(2f));

            TextView slotLabel = new TextView(context);
            slotLabel.setText("Position " + slot);
            slotLabel.setTextColor(0xFFE0E0E0);
            slotLabel.setTextSize(13f);
            slotRow.addView(slotLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.75f));

            Spinner slotSpinner = new Spinner(context);
            ArrayAdapter<String> slotAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, TouchInputBinding.optionLabels(keyOptions));
            slotAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            slotSpinner.setAdapter(slotAdapter);
            int slotValue = slot < startingKeySlots.length ? startingKeySlots[slot] : 0;
            slotSpinner.setSelection(TouchInputBinding.selectedKeyOptionIndex(slotValue), false);
            slotRow.addView(slotSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));

            Button pickSlot = new Button(context);
            pickSlot.setText("Pick");
            pickSlot.setAllCaps(false);
            slotRow.addView(pickSlot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.55f));

            Button clearSlot = new Button(context);
            clearSlot.setText("Clear");
            clearSlot.setAllCaps(false);
            slotRow.addView(clearSlot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f));

            final int slotIndex = slot;
            pickSlot.setOnClickListener(v -> showKeyboardKeyPickerDialog(
                    context,
                    slotIndex,
                    keySlotSpinners[slotIndex],
                    keyCodes,
                    boundKeys,
                    keySlotSpinners,
                    keyOptions
            ));
            clearSlot.setOnClickListener(v -> {
                keySlotSpinners[slotIndex].setSelection(TouchInputBinding.selectedKeyOptionIndex(0));
                updateKeySlotSummary(keyCodes, boundKeys, keySlotSpinners, keyOptions);
            });
            slotSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    updateKeySlotSummary(keyCodes, boundKeys, keySlotSpinners, keyOptions);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

            layout.addView(slotRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            keySlotSpinners[slot] = slotSpinner;
            keySlotRows[slot] = slotRow;
        }
        updateKeySlotSummary(keyCodes, boundKeys, keySlotSpinners, keyOptions);

        AdapterView.OnItemSelectedListener actionListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String action = actionValues[Math.max(0, Math.min(position, actionValues.length - 1))];
                TouchInputBinding.Option[] options = TouchInputBinding.optionsForAction(action);
                currentOptions[0] = options;
                ArrayAdapter<String> bindingAdapter = new ArrayAdapter<>(
                        context,
                        android.R.layout.simple_spinner_item,
                        TouchInputBinding.optionLabels(options)
                );
                bindingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                bindingSpinner.setAdapter(bindingAdapter);
                boolean keyAction = TouchControlActions.KEY.equals(action);
                int selected = keyAction && TouchControlActions.KEY.equals(data.action)
                        || TouchControlActions.MOUSE.equals(action) && TouchControlActions.MOUSE.equals(data.action)
                        || TouchControlActions.SCROLL.equals(action) && TouchControlActions.SCROLL.equals(data.action)
                        ? TouchInputBinding.selectedOptionIndex(action, data)
                        : 0;
                bindingSpinner.setSelection(selected, false);
                bindingSpinner.setEnabled(!keyAction && options.length > 1);
                keyCodes.setVisibility(GONE);
                boundKeys.setVisibility(keyAction ? VISIBLE : GONE);
                keySlotHint.setVisibility(keyAction ? VISIBLE : GONE);
                for (LinearLayout slotRow : keySlotRows) {
                    if (slotRow != null) slotRow.setVisibility(keyAction ? VISIBLE : GONE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        actionSpinner.setOnItemSelectedListener(actionListener);
        actionListener.onItemSelected(actionSpinner, actionSpinner, actionSpinner.getSelectedItemPosition(), 0L);

        addSectionHeader(layout, "Position", "X/Y use the same layout units as Width/Height.");
        EditText x = textField(context, "X position", String.valueOf(Math.round(initialLayoutX)), true);
        addFieldRow(layout, "X", x);
        SeekBar xSlider = addSlider(layout, parentWidthUnits, Math.round(initialLayoutX));
        EditText y = textField(context, "Y position", String.valueOf(Math.round(initialLayoutY)), true);
        addFieldRow(layout, "Y", y);
        SeekBar ySlider = addSlider(layout, parentHeightUnits, Math.round(initialLayoutY));

        addSectionHeader(layout, "Size", "Resize both dimensions together or tune width and height separately.");
        EditText width = textField(context, "Width", String.valueOf(Math.round(data.width)), true);
        addFieldRow(layout, "Width", width);
        SeekBar widthSlider = addSlider(layout, 400, Math.round(data.width));
        EditText height = textField(context, "Height", String.valueOf(Math.round(data.height)), true);
        addFieldRow(layout, "Height", height);
        SeekBar heightSlider = addSlider(layout, 400, Math.round(data.height));

        int initialPercent = Math.round(clamp(data.sizePercent, 30f, 250f));
        TextView sizeLabel = valueLabel(context, "Button size: " + initialPercent + "%");
        layout.addView(sizeLabel);
        SeekBar sizeSlider = addSlider(layout, 250, initialPercent);

        addSectionHeader(layout, "Appearance", "Adjust opacity, rounded corners, and stroke width.");
        EditText opacity = textField(context, "Opacity 0.0 - 1.0", String.valueOf(data.opacity), false);
        addFieldRow(layout, "Opacity", opacity);
        SeekBar opacitySlider = addSlider(layout, 100, Math.round(clamp(data.opacity, 0f, 1f) * 100f));

        EditText cornerRadius = textField(context, "Corner radius", String.valueOf(Math.round(data.cornerRadius)), true);
        addFieldRow(layout, "Corner radius", cornerRadius);
        SeekBar cornerSlider = addSlider(layout, 100, Math.round(data.cornerRadius));

        EditText strokeWidth = textField(context, "Stroke width", String.valueOf(Math.round(data.strokeWidth)), true);
        addFieldRow(layout, "Stroke", strokeWidth);
        SeekBar strokeSlider = addSlider(layout, 20, Math.round(data.strokeWidth));

        EditText strokeColor = textField(context, "#AARRGGBB or #RRGGBB", formatColor(data.strokeColor), false);
        addFieldRow(layout, "Border colour", strokeColor);

        CheckBox toggle = new CheckBox(context);
        toggle.setText("Toggle button");
        toggle.setTextColor(0xFFE0E0E0);
        toggle.setChecked(data.toggle);
        layout.addView(toggle);

        CheckBox visibleInGame = new CheckBox(context);
        visibleInGame.setText("Visible in game");
        visibleInGame.setTextColor(0xFFE0E0E0);
        visibleInGame.setChecked(data.visibleInGame);
        layout.addView(visibleInGame);

        CheckBox visibleInMenu = new CheckBox(context);
        visibleInMenu.setText("Visible in menu");
        visibleInMenu.setTextColor(0xFFE0E0E0);
        visibleInMenu.setChecked(data.visibleInMenu);
        layout.addView(visibleInMenu);

        CheckBox visibleWhenControlsHidden = new CheckBox(context);
        visibleWhenControlsHidden.setText("Stay visible when touch controls are hidden");
        visibleWhenControlsHidden.setTextColor(0xFFE0E0E0);
        visibleWhenControlsHidden.setChecked(data.visibleWhenControlsHidden
                || TouchControlData.shouldStayVisibleWhenControlsHiddenByDefault(data.action));
        layout.addView(visibleWhenControlsHidden);

        CheckBox virtualMouse = new CheckBox(context);
        virtualMouse.setText("Show virtual cursor");
        virtualMouse.setTextColor(0xFFE0E0E0);
        virtualMouse.setChecked(ControlsPreferences.isVirtualMouseEnabled(context));
        virtualMouse.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ControlsPreferences.setVirtualMouseEnabled(context, isChecked);
            postInvalidateOnAnimation();
        });
        layout.addView(virtualMouse);

        Button copyButton = new Button(context);
        copyButton.setText("Copy button");
        copyButton.setAllCaps(false);
        layout.addView(copyButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final boolean[] sliderChangingText = new boolean[]{false};
        final boolean[] textChangingSlider = new boolean[]{false};
        final boolean[] accepted = new boolean[]{false};
        final boolean[] deleted = new boolean[]{false};
        final AlertDialog[] dialogRef = new AlertDialog[1];
        final float[] currentPercent = new float[]{initialPercent};
        float baseWidth = Math.max(24f, data.width * 100f / Math.max(1f, initialPercent));
        float baseHeight = Math.max(24f, data.height * 100f / Math.max(1f, initialPercent));

        Runnable applyPreview = () -> {
            data.label = label.getText() == null ? data.label : label.getText().toString().trim();
            if (data.label.isEmpty()) data.label = "Button";
            data.x = parseFloat(x, data.x);
            data.y = parseFloat(y, data.y);
            data.width = Math.max(24f, parseFloat(width, data.width));
            data.height = Math.max(24f, parseFloat(height, data.height));
            data.opacity = clamp(parseFloat(opacity, data.opacity), 0f, 1f);
            data.cornerRadius = Math.max(0f, parseFloat(cornerRadius, data.cornerRadius));
            data.strokeWidth = Math.max(0f, parseFloat(strokeWidth, data.strokeWidth));
            data.strokeColor = parseColorValue(strokeColor.getText() == null ? "" : strokeColor.getText().toString(), data.strokeColor);
            data.rawX = null;
            data.rawY = null;
            summary.setText("Editing: " + data.label);
            applyControlPreview(editingView, data);
        };

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (sliderChangingText[0]) return;
                applyPreview.run();
                textChangingSlider[0] = true;
                setSliderProgress(xSlider, Math.round(parseFloat(x, data.x)));
                setSliderProgress(ySlider, Math.round(parseFloat(y, data.y)));
                setSliderProgress(widthSlider, Math.round(parseFloat(width, data.width)));
                setSliderProgress(heightSlider, Math.round(parseFloat(height, data.height)));
                setSliderProgress(opacitySlider, Math.round(clamp(parseFloat(opacity, data.opacity), 0f, 1f) * 100f));
                setSliderProgress(cornerSlider, Math.round(parseFloat(cornerRadius, data.cornerRadius)));
                setSliderProgress(strokeSlider, Math.round(parseFloat(strokeWidth, data.strokeWidth)));
                textChangingSlider[0] = false;
            }
        };
        label.addTextChangedListener(watcher);
        x.addTextChangedListener(watcher);
        y.addTextChangedListener(watcher);
        width.addTextChangedListener(watcher);
        height.addTextChangedListener(watcher);
        opacity.addTextChangedListener(watcher);
        cornerRadius.addTextChangedListener(watcher);
        strokeWidth.addTextChangedListener(watcher);
        strokeColor.addTextChangedListener(watcher);

        addPreviewSliderListener(xSlider, dialogRef, () -> { if (!textChangingSlider[0]) setTextFromSlider(x, xSlider); });
        addPreviewSliderListener(ySlider, dialogRef, () -> { if (!textChangingSlider[0]) setTextFromSlider(y, ySlider); });
        addPreviewSliderListener(widthSlider, dialogRef, () -> { if (!textChangingSlider[0]) setTextFromSlider(width, widthSlider); });
        addPreviewSliderListener(heightSlider, dialogRef, () -> { if (!textChangingSlider[0]) setTextFromSlider(height, heightSlider); });
        addPreviewSliderListener(opacitySlider, dialogRef, () -> {
            if (!textChangingSlider[0]) {
                sliderChangingText[0] = true;
                opacity.setText(String.valueOf(opacitySlider.getProgress() / 100f));
                sliderChangingText[0] = false;
                applyPreview.run();
            }
        });
        addPreviewSliderListener(cornerSlider, dialogRef, () -> { if (!textChangingSlider[0]) setTextFromSlider(cornerRadius, cornerSlider); });
        addPreviewSliderListener(strokeSlider, dialogRef, () -> { if (!textChangingSlider[0]) setTextFromSlider(strokeWidth, strokeSlider); });

        sizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = Math.max(30, progress);
                currentPercent[0] = percent;
                data.sizePercent = percent;
                sizeLabel.setText("Button size: " + percent + "%");
                if (fromUser) {
                    sliderChangingText[0] = true;
                    width.setText(String.valueOf(Math.max(24, Math.round(baseWidth * percent / 100f))));
                    height.setText(String.valueOf(Math.max(24, Math.round(baseHeight * percent / 100f))));
                    sliderChangingText[0] = false;
                    applyPreview.run();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { setEditDialogPreviewAlpha(dialogRef[0], true); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { setEditDialogPreviewAlpha(dialogRef[0], false); }
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Edit touch button")
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Delete", null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialogRef[0] = dialog;

        copyButton.setOnClickListener(v -> {
            pushUndoSnapshot(originalLayoutSnapshot);
            TouchControlData copy = data.copy();
            copy.id = UUID.randomUUID().toString();
            copy.label = data.label + " Copy";
            copy.x = data.x + 24f;
            copy.y = data.y + 24f;
            layoutData.controls.add(copy);
            accepted[0] = true;
            saveLayout();
            rebuildWhenSized();
            Toast.makeText(context, "Copied " + data.label, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.setOnShowListener(shown -> {
            styleDialogWindow(dialog);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button -> {
                pushUndoSnapshot(originalLayoutSnapshot);
                deleted[0] = true;
                layoutData.controls.remove(data);
                saveLayout();
                rebuildWhenSized();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                pushUndoSnapshot(originalLayoutSnapshot);
                String oldLabel = originalLabel;
                String newLabel = label.getText() == null ? oldLabel : label.getText().toString().trim();
                int actionPosition = Math.max(0, actionSpinner.getSelectedItemPosition());
                String action = actionValues[Math.min(actionPosition, actionValues.length - 1)];
                TouchInputBinding.Option[] options = currentOptions[0] != null ? currentOptions[0] : TouchInputBinding.optionsForAction(action);
                int bindingPosition = Math.max(0, bindingSpinner.getSelectedItemPosition());
                TouchInputBinding.Option option = options[Math.min(bindingPosition, options.length - 1)];
                TouchInputBinding.applyOption(data, action, option);

                if (TouchControlActions.KEY.equals(action)) {
                    data.setKeySlots(readKeySlotsFromSpinners(keySlotSpinners, keyOptions));
                }

                // Preserve exactly what the user typed in the Label field.
                // Changing the binding should never rename an existing/custom button.
                data.id = safeControlId(idField.getText() == null ? "" : idField.getText().toString());
                data.label = newLabel.trim().isEmpty() ? "Button" : newLabel.trim();
                data.x = parseFloat(x, data.x);
                data.y = parseFloat(y, data.y);
                data.width = Math.max(24f, parseFloat(width, data.width));
                data.height = Math.max(24f, parseFloat(height, data.height));
                data.sizePercent = clamp(currentPercent[0], 30f, 250f);
                data.opacity = clamp(parseFloat(opacity, data.opacity), 0f, 1f);
                data.cornerRadius = Math.max(0f, parseFloat(cornerRadius, data.cornerRadius));
                data.strokeWidth = Math.max(0f, parseFloat(strokeWidth, data.strokeWidth));
                data.strokeColor = parseColorValue(strokeColor.getText() == null ? "" : strokeColor.getText().toString(), data.strokeColor);
                data.toggle = toggle.isChecked();
                data.visibleInGame = visibleInGame.isChecked();
                data.visibleInMenu = visibleInMenu.isChecked();
                data.visibleWhenControlsHidden = visibleWhenControlsHidden.isChecked()
                        || TouchControlData.shouldStayVisibleWhenControlsHiddenByDefault(data.action);
                data.rawX = null;
                data.rawY = null;
                accepted[0] = true;
                saveLayout();
                rebuildWhenSized();
                dialog.dismiss();
            });
        });

        dialog.setOnDismissListener(dismissed -> {
            setEditDialogPreviewAlpha(dialog, false);
            if (!accepted[0] && !deleted[0]) {
                data.id = originalId;
                data.label = originalLabel;
                data.action = originalAction;
                data.keyCode = originalKeyCode;
                data.keyCodes = originalKeyCodes;
                data.keySlots = originalKeySlots;
                data.mouseButton = originalMouseButton;
                data.scrollY = originalScrollY;
                data.x = originalX;
                data.y = originalY;
                data.width = originalWidth;
                data.height = originalHeight;
                data.sizePercent = originalSizePercent;
                data.opacity = originalOpacity;
                data.cornerRadius = originalCornerRadius;
                data.strokeWidth = originalStrokeWidth;
                data.strokeColor = originalStrokeColor;
                data.backgroundColor = originalBackgroundColor;
                data.toggle = originalToggle;
                data.visibleInGame = originalVisibleInGame;
                data.visibleInMenu = originalVisibleInMenu;
                data.visibleWhenControlsHidden = originalVisibleWhenControlsHidden;
                data.rawX = originalRawX;
                data.rawY = originalRawY;
                rebuildWhenSized();
            }
        });

        try {
            if (!isAttachedToWindow()) return;
            dialog.show();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to show touch control editor", throwable);
            setEditDialogPreviewAlpha(dialog, false);
            Toast.makeText(context, "Unable to open touch editor.", Toast.LENGTH_LONG).show();
        }
    }

    @NonNull
    private LayoutMetrics layoutMetrics(float parentWidth, float parentHeight) {
        float density = Math.max(0.1f, getResources().getDisplayMetrics().density);
        float safeParentWidth = Math.max(1f, parentWidth);
        float safeParentHeight = Math.max(1f, parentHeight);

        if (layoutData.usesPixelCoordinates()) {
            float sourceWidth = layoutData.resolvedSourceWidth(safeParentWidth);
            float sourceHeight = layoutData.resolvedSourceHeight(safeParentHeight);
            float[] realDisplay = realDisplaySize(safeParentWidth, safeParentHeight);

            float parentScale = Math.min(
                    safeParentWidth / Math.max(1f, sourceWidth),
                    safeParentHeight / Math.max(1f, sourceHeight)
            );
            float realScale = Math.min(
                    realDisplay[0] / Math.max(1f, sourceWidth),
                    realDisplay[1] / Math.max(1f, sourceHeight)
            );

            boolean screenSizedCanvas = Math.abs(sourceWidth - safeParentWidth) <= Math.max(4f, safeParentWidth * 0.05f)
                    && Math.abs(sourceHeight - safeParentHeight) <= Math.max(4f, safeParentHeight * 0.05f);
            float uniformScale = screenSizedCanvas ? parentScale : Math.max(parentScale, realScale);

            // Legacy default_touch.json files from a 1920x1080 device normally land
            // as an 854x480-ish logical canvas. Ultrawide devices can report a shorter
            // app surface even though the physical game buffer is still 1080p-class,
            // which made the overlay shrink vertically. Keep those layouts at least at
            // their original 1080p scale when the current display is 1080p-class.
            boolean legacy1080pCanvas = sourceWidth <= 960f
                    && sourceHeight <= 540f
                    && Math.max(safeParentWidth, realDisplay[0]) >= 1800f
                    && Math.max(safeParentHeight, realDisplay[1]) >= 900f;
            if (legacy1080pCanvas) {
                uniformScale = Math.max(uniformScale, 1080f / Math.max(1f, sourceHeight));
            }

            return new LayoutMetrics(
                    true,
                    safeParentWidth,
                    safeParentHeight,
                    sourceWidth,
                    sourceHeight,
                    uniformScale,
                    uniformScale,
                    uniformScale,
                    uniformScale
            );
        }

        return new LayoutMetrics(
                false,
                safeParentWidth,
                safeParentHeight,
                safeParentWidth / density,
                safeParentHeight / density,
                density,
                density,
                density,
                density
        );
    }

    @NonNull
    private float[] realDisplaySize(float parentWidth, float parentHeight) {
        float realWidth = Math.max(1f, parentWidth);
        float realHeight = Math.max(1f, parentHeight);

        try {
            WindowManager windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Rect current = windowManager.getCurrentWindowMetrics().getBounds();
                    Rect maximum = windowManager.getMaximumWindowMetrics().getBounds();
                    realWidth = Math.max(realWidth, Math.max(current.width(), maximum.width()));
                    realHeight = Math.max(realHeight, Math.max(current.height(), maximum.height()));
                } else {
                    DisplayMetrics metrics = new DisplayMetrics();
                    //noinspection deprecation
                    windowManager.getDefaultDisplay().getRealMetrics(metrics);
                    realWidth = Math.max(realWidth, metrics.widthPixels);
                    realHeight = Math.max(realHeight, metrics.heightPixels);
                }
            }
        } catch (Throwable ignored) {
        }

        boolean landscape = parentWidth >= parentHeight;
        float longSide = Math.max(realWidth, realHeight);
        float shortSide = Math.min(realWidth, realHeight);
        return landscape ? new float[]{longSide, shortSide} : new float[]{shortSide, longSide};
    }

    private static final class LayoutMetrics {
        final boolean pixelCoordinates;
        final float parentWidth;
        final float parentHeight;
        final float sourceWidth;
        final float sourceHeight;
        final float xScale;
        final float yScale;
        final float sizeScale;
        final float formulaPixelScale;

        LayoutMetrics(
                boolean pixelCoordinates,
                float parentWidth,
                float parentHeight,
                float sourceWidth,
                float sourceHeight,
                float xScale,
                float yScale,
                float sizeScale,
                float formulaPixelScale
        ) {
            this.pixelCoordinates = pixelCoordinates;
            this.parentWidth = Math.max(1f, parentWidth);
            this.parentHeight = Math.max(1f, parentHeight);
            this.sourceWidth = Math.max(1f, sourceWidth);
            this.sourceHeight = Math.max(1f, sourceHeight);
            this.xScale = Math.max(0.1f, xScale);
            this.yScale = Math.max(0.1f, yScale);
            this.sizeScale = Math.max(0.1f, sizeScale);
            this.formulaPixelScale = Math.max(0.1f, formulaPixelScale);
        }

        int toScreenWidth(float value) {
            return Math.max(32, Math.round(Math.max(1f, value) * sizeScale));
        }

        int toScreenHeight(float value) {
            return Math.max(32, Math.round(Math.max(1f, value) * sizeScale));
        }

        float toScreenX(@NonNull TouchControlData control, float screenControlWidth) {
            if (!pixelCoordinates) return control.x * xScale;

            float sourceControlWidth = Math.max(1f, control.width);
            float sourceCenterX = control.x + (sourceControlWidth / 2f);
            float rightThreshold = sourceWidth * 0.58f;
            float leftThreshold = sourceWidth * 0.42f;

            if (isOutOfSourceBoundsX(control, sourceControlWidth)) {
                return centerMappedScreenX(sourceCenterX, screenControlWidth);
            }

            if (sourceCenterX >= rightThreshold) {
                float sourceRightOffset = Math.max(0f, sourceWidth - control.x - sourceControlWidth);
                return parentWidth - (sourceRightOffset * sizeScale) - screenControlWidth;
            }

            if (sourceCenterX <= leftThreshold) {
                return control.x * sizeScale;
            }

            return centerMappedScreenX(sourceCenterX, screenControlWidth);
        }

        private boolean isOutOfSourceBoundsX(@NonNull TouchControlData control, float sourceControlWidth) {
            return control.x < 0f || control.x + sourceControlWidth > sourceWidth;
        }

        private float centerMappedScreenX(float sourceCenterX, float screenControlWidth) {
            float sourceCenterOffset = sourceCenterX - (sourceWidth / 2f);
            return (parentWidth / 2f) + (sourceCenterOffset * sizeScale) - (screenControlWidth / 2f);
        }

        private float centerMappedSourceX(float screenX, float screenControlWidth, float sourceControlWidth) {
            float screenCenter = screenX + (screenControlWidth / 2f);
            return (sourceWidth / 2f) + ((screenCenter - (parentWidth / 2f)) / sizeScale) - (sourceControlWidth / 2f);
        }

        float toScreenY(@NonNull TouchControlData control, float screenControlHeight) {
            if (!pixelCoordinates) return control.y * yScale;
            return control.y * sizeScale;
        }

        float fromScreenX(@NonNull TouchControlData control, float screenX, float screenControlWidth) {
            if (!pixelCoordinates) return screenX / xScale;

            float sourceControlWidth = Math.max(1f, control.width);
            float sourceCenterX = control.x + (sourceControlWidth / 2f);
            float rightThreshold = sourceWidth * 0.58f;
            float leftThreshold = sourceWidth * 0.42f;

            if (isOutOfSourceBoundsX(control, sourceControlWidth)) {
                return centerMappedSourceX(screenX, screenControlWidth, sourceControlWidth);
            }

            if (sourceCenterX >= rightThreshold) {
                float screenRightOffset = Math.max(0f, parentWidth - screenX - screenControlWidth);
                return sourceWidth - (screenRightOffset / sizeScale) - sourceControlWidth;
            }

            if (sourceCenterX <= leftThreshold) {
                return screenX / sizeScale;
            }

            return centerMappedSourceX(screenX, screenControlWidth, sourceControlWidth);
        }

        float fromScreenY(float screenY) {
            return screenY / (pixelCoordinates ? sizeScale : yScale);
        }

        float maxLayoutXUnits() {
            return pixelCoordinates ? sourceWidth : parentWidth / xScale;
        }

        float maxLayoutYUnits() {
            return pixelCoordinates ? sourceHeight : parentHeight / yScale;
        }
    }

    private void addSectionHeader(@NonNull LinearLayout parent, @NonNull String title, @Nullable String subtitle) {
        TextView header = new TextView(getContext());
        header.setText(title);
        header.setTextColor(Color.WHITE);
        header.setTextSize(16f);
        header.setPadding(0, dp(14f), 0, dp(2f));
        parent.addView(header);
        if (subtitle != null && !subtitle.trim().isEmpty()) {
            TextView sub = new TextView(getContext());
            sub.setText(subtitle);
            sub.setTextColor(0xFFBDBDBD);
            sub.setTextSize(12f);
            sub.setPadding(0, 0, 0, dp(6f));
            parent.addView(sub);
        }
    }

    private void addFieldRow(@NonNull LinearLayout parent, @NonNull String title, @NonNull EditText field) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4f), 0, dp(4f));
        TextView label = new TextView(getContext());
        label.setText(title);
        label.setTextColor(0xFFE0E0E0);
        label.setTextSize(13f);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f));
        row.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f));
        parent.addView(row);
    }

    private void addSpinnerRow(@NonNull LinearLayout parent, @NonNull String title, @NonNull Spinner spinner) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4f), 0, dp(4f));
        TextView label = new TextView(getContext());
        label.setText(title);
        label.setTextColor(0xFFE0E0E0);
        label.setTextSize(13f);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f));
        row.addView(spinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f));
        parent.addView(row);
    }

    @NonNull
    private TextView valueLabel(@NonNull Context context, @NonNull String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(0xFFE0E0E0);
        label.setTextSize(13f);
        label.setPadding(0, dp(4f), 0, dp(2f));
        return label;
    }

    @NonNull
    private SeekBar addSlider(@NonNull LinearLayout parent, int max, int value) {
        SeekBar slider = new SeekBar(getContext());
        slider.setMax(Math.max(1, max));
        setSliderProgress(slider, value);
        parent.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return slider;
    }

    private void addPreviewSliderListener(@NonNull SeekBar slider, @NonNull AlertDialog[] dialogRef, @NonNull Runnable onUserChange) {
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser) onUserChange.run(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { setEditDialogPreviewAlpha(dialogRef[0], true); }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { setEditDialogPreviewAlpha(dialogRef[0], false); }
        });
    }

    private void setTextFromSlider(@NonNull EditText field, @NonNull SeekBar slider) {
        field.setText(String.valueOf(slider.getProgress()));
    }

    private void setSliderProgress(@NonNull SeekBar slider, int value) {
        int progress = Math.max(0, Math.min(slider.getMax(), value));
        if (slider.getProgress() != progress) slider.setProgress(progress);
    }

    private void setEditDialogPreviewAlpha(@Nullable AlertDialog dialog, boolean previewing) {
        if (dialog == null || dialog.getWindow() == null) return;
        dialog.getWindow().setDimAmount(previewing ? 0.02f : 0.32f);
        dialog.getWindow().getDecorView().setAlpha(previewing ? 0.12f : 1.0f);
    }

    private void applyControlPreview(@NonNull TouchControlButtonView view, @NonNull TouchControlData data) {
        LayoutMetrics metrics = layoutMetrics(getWidth(), getHeight());
        int width = metrics.toScreenWidth(data.width);
        int height = metrics.toScreenHeight(data.height);
        width = Math.min(width, Math.max(1, getWidth()));
        height = Math.min(height, Math.max(1, getHeight()));

        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.width = width;
            params.height = height;
            view.setLayoutParams(params);
        }

        view.setX(clamp(metrics.toScreenX(data, width), 0f, Math.max(0f, getWidth() - width)));
        view.setY(clamp(metrics.toScreenY(data, height), 0f, Math.max(0f, getHeight() - height)));
        view.setText(data.label);
        view.setAlpha(clamp(data.opacity, 0f, 1f) * clamp(ControlsPreferences.getGlobalOpacity(getContext()), 0f, 1f));
        view.refreshVisualState();
        view.requestLayout();
        view.invalidate();
    }

    private void styleDialogWindow(@NonNull AlertDialog dialog) {
        if (dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawable(makeDialogBackground());
    }

    @NonNull
    private GradientDrawable makeDialogBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xEE202124);
        drawable.setCornerRadius(dp(22f));
        drawable.setStroke(Math.max(1, dp(1f)), 0x44FFFFFF);
        return drawable;
    }



    private void showKeyboardKeyPickerDialog(
            @NonNull Context context,
            int slotIndex,
            @Nullable Spinner slotSpinner,
            @NonNull EditText hiddenKeySlots,
            @NonNull TextView boundKeys,
            @NonNull Spinner[] keySlotSpinners,
            @NonNull TouchInputBinding.Option[] keyOptions
    ) {
        if (slotSpinner == null) return;

        TouchKeyPickerDialog.showPicker(
                context,
                slotIndex,
                keyCode -> {
                    int selected = TouchInputBinding.selectedKeyOptionIndex(keyCode);
                    slotSpinner.setSelection(selected, false);
                    updateKeySlotSummary(hiddenKeySlots, boundKeys, keySlotSpinners, keyOptions);
                    return true;
                }
        );
    }


    @NonNull
    private int[] readKeySlotsFromSpinners(
            @NonNull Spinner[] spinners,
            @NonNull TouchInputBinding.Option[] options
    ) {
        int[] slots = new int[TouchControlData.MAX_ACTION_SLOTS];
        for (int slot = 0; slot < slots.length && slot < spinners.length; slot++) {
            Spinner spinner = spinners[slot];
            if (spinner == null || options.length == 0) {
                slots[slot] = 0;
                continue;
            }
            int index = Math.max(0, Math.min(spinner.getSelectedItemPosition(), options.length - 1));
            slots[slot] = options[index].value;
        }
        return slots;
    }

    private void updateKeySlotSummary(
            @NonNull EditText hiddenKeySlots,
            @NonNull TextView boundKeys,
            @NonNull Spinner[] spinners,
            @NonNull TouchInputBinding.Option[] options
    ) {
        int[] slots = readKeySlotsFromSpinners(spinners, options);
        hiddenKeySlots.setText(joinKeyCodes(slots));
        java.util.ArrayList<Integer> active = new java.util.ArrayList<>();
        StringBuilder detail = new StringBuilder();
        for (int slot = 0; slot < slots.length; slot++) {
            int value = slots[slot];
            if (value == 0) continue;
            active.add(value);
            if (detail.length() > 0) detail.append("  •  ");
            detail.append(slot).append(": ").append(TouchInputBinding.labelForKeyCode(value));
        }
        int[] activeCodes = new int[active.size()];
        for (int i = 0; i < active.size(); i++) activeCodes[i] = active.get(i);
        boundKeys.setText(detail.length() == 0
                ? "Bound buttons: No bindings"
                : "Bound buttons: " + TouchInputBinding.friendlyKeyCombo(activeCodes) + "\nSlots: " + detail);
    }

    private int[] parseKeyCodes(@NonNull String text, int fallback) {
        String[] parts = text.split("[,\\s]+");
        java.util.ArrayList<Integer> values = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            try { values.add(Integer.parseInt(part.trim())); } catch (Throwable ignored) { }
        }
        if (values.isEmpty()) return new int[]{fallback};
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    @NonNull
    private String joinKeyCodes(@NonNull int[] codes) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < codes.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(codes[i]);
        }
        return builder.toString();
    }

    @NonNull
    private String appendKeyCodeText(@NonNull String current, int value) {
        String trimmed = current.trim();
        if (trimmed.isEmpty()) return String.valueOf(value);
        for (String part : trimmed.split("[,\\s]+")) {
            try { if (Integer.parseInt(part.trim()) == value) return trimmed; } catch (Throwable ignored) { }
        }
        return trimmed + ", " + value;
    }

    @NonNull
    private String safeControlId(@NonNull String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? UUID.randomUUID().toString() : trimmed;
    }

    @NonNull
    private static String formatColor(int color) {
        return String.format(java.util.Locale.US, "#%08X", color);
    }

    private static int parseColorValue(@NonNull String value, int fallback) {
        String text = value.trim();
        if (text.isEmpty()) return fallback;
        try {
            if (!text.startsWith("#")) text = "#" + text;
            if (text.length() == 7) {
                // #RRGGBB => force fully opaque border.
                return (int) (0xFF000000L | Long.parseLong(text.substring(1), 16));
            }
            if (text.length() == 9) {
                return (int) Long.parseLong(text.substring(1), 16);
            }
            return Color.parseColor(text);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @NonNull
    private static TextView labelView(@NonNull Context context, @NonNull String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(13f);
        view.setPadding(0, 12, 0, 0);
        return view;
    }

    @NonNull
    private static EditText textField(@NonNull Context context, @NonNull String hint, @NonNull String value, boolean number) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setText(value);
        if (number) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        }
        return field;
    }

    private static float parseFloat(@NonNull EditText field, float fallback) {
        try {
            return Float.parseFloat(field.getText() == null ? "" : field.getText().toString().trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }


    private void normalizeUnstablePixelLayoutBeforeSave() {
        if (!editMode || getWidth() <= 1 || getHeight() <= 1) return;
        if (!layoutData.usesPixelCoordinates()) return;
        if (!hasUnstablePixelCoordinates()) return;

        int parentWidth = Math.max(1, getWidth());
        int parentHeight = Math.max(1, getHeight());

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof TouchControlButtonView)) continue;

            TouchControlButtonView button = (TouchControlButtonView) child;
            TouchControlData data = button.getData();
            int width = Math.max(1, button.getWidth());
            int height = Math.max(1, button.getHeight());

            data.x = clamp(button.getX(), 0f, Math.max(0f, parentWidth - width));
            data.y = clamp(button.getY(), 0f, Math.max(0f, parentHeight - height));
            data.width = width;
            data.height = height;
            data.rawX = null;
            data.rawY = null;
        }

        layoutData.coordinateUnit = TouchControlsLayoutData.UNIT_PX;
        layoutData.sourceWidth = parentWidth;
        layoutData.sourceHeight = parentHeight;
        layoutData.version = Math.max(layoutData.version, 4);
    }

    private boolean hasUnstablePixelCoordinates() {
        float sourceWidth = layoutData.resolvedSourceWidth(getWidth());
        float sourceHeight = layoutData.resolvedSourceHeight(getHeight());

        for (TouchControlData control : layoutData.controls) {
            if (control.rawX != null || control.rawY != null) return true;
            float width = Math.max(1f, control.width);
            float height = Math.max(1f, control.height);
            if (control.x < 0f || control.y < 0f) return true;
            if (control.x + width > sourceWidth || control.y + height > sourceHeight) return true;
        }

        return false;
    }

    private static float maxCursorCoordinate(float size) {
        return Math.max(0f, size - 1f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }


    private boolean dispatchKeySenderKeyboardTouch(@NonNull MotionEvent event) {
        if (!keySenderKeyboardVisible || keySenderKeyboardView == null || keySenderKeyboardView.getVisibility() != VISIBLE) {
            return false;
        }

        // While the key-sender keyboard is open, it owns the whole touch stream.
        // This prevents a keyboard tap from also becoming camera movement or a hotbar tap.
        try {
            super.dispatchTouchEvent(event);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dispatch key sender keyboard touch", throwable);
        }
        return true;
    }

    private void showKeySenderKeyboard() {
        if (editMode) return;
        keySenderKeyboardVisible = true;
        attachKeySenderKeyboardView();
    }

    private void hideKeySenderKeyboard() {
        keySenderKeyboardVisible = false;
        View view = keySenderKeyboardView;
        keySenderKeyboardView = null;
        if (view != null && view.getParent() == this) {
            removeView(view);
        }
    }

    private void attachKeySenderKeyboardView() {
        if (!keySenderKeyboardVisible || editMode) return;

        if (keySenderKeyboardView != null && keySenderKeyboardView.getParent() == this) {
            keySenderKeyboardView.bringToFront();
            return;
        }

        View keyboard = createKeySenderKeyboardView();
        keySenderKeyboardView = keyboard;
        addView(keyboard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        keyboard.bringToFront();
    }

    @NonNull
    private View createKeySenderKeyboardView() {
        return new TouchKeySenderKeyboardView(getContext(), new TouchKeySenderKeyboardView.Listener() {
            @Override
            public void onCloseRequested() {
                hideKeySenderKeyboard();
            }

            @Override
            public void onSendRequested(@NonNull List<Integer> keyCodes) {
                sendQueuedKeySenderInputs(keyCodes);
            }
        });
    }


    private void sendQueuedKeySenderInputs(@NonNull List<Integer> pendingKeys) {
        if (pendingKeys.isEmpty()) {
            Toast.makeText(getContext(), "Pick at least one key first.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            java.util.ArrayList<Integer> chordKeys = new java.util.ArrayList<>();
            for (int keyCode : pendingKeys) {
                if (isPlainGlfwKeyboardKey(keyCode)) {
                    chordKeys.add(keyCode);
                    continue;
                }

                flushKeySenderChord(chordKeys);
                sendKeySenderInput(keyCode);
            }
            flushKeySenderChord(chordKeys);
        } finally {
            hideKeySenderKeyboard();
        }
    }

    private void flushKeySenderChord(@NonNull java.util.ArrayList<Integer> chordKeys) {
        if (chordKeys.isEmpty()) return;
        sendKeyChord(chordKeys);
        chordKeys.clear();
    }

    private boolean isPlainGlfwKeyboardKey(int keyCode) {
        // GLFW keyboard keys are positive. DroidBridge special actions are stored
        // as negative/sentinel values and must keep their existing action handlers.
        return keyCode > 0;
    }

    private void sendKeyChord(@NonNull List<Integer> keyCodes) {
        java.util.ArrayList<Integer> pressedKeys = new java.util.ArrayList<>();
        try {
            CallbackBridge.setInputReady(true);

            for (int keyCode : keyCodes) {
                if (!isPlainGlfwKeyboardKey(keyCode)) continue;
                if (keyCode == 84 || keyCode == 47) {
                    TouchKeyboardHelper.markChatKeyPressed();
                }
                CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
                CallbackBridge.setModifiers(keyCode, true);
                pressedKeys.add(keyCode);
            }

            for (int i = pressedKeys.size() - 1; i >= 0; i--) {
                int keyCode = pressedKeys.get(i);
                CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
                CallbackBridge.setModifiers(keyCode, false);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send key keyboard chord", throwable);

            // Best effort release in case an exception happened after one or more
            // key-down events. This prevents sticky F3/Shift/Ctrl style input.
            for (int i = pressedKeys.size() - 1; i >= 0; i--) {
                try {
                    int keyCode = pressedKeys.get(i);
                    CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
                    CallbackBridge.setModifiers(keyCode, false);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void sendKeySenderInput(int keyCode) {
        try {
            switch (keyCode) {
                case TouchControlData.SPECIAL_MOUSE_LEFT:
                    sendLeftMouse(true);
                    sendLeftMouse(false);
                    return;
                case TouchControlData.SPECIAL_MOUSE_RIGHT:
                    sendRightMouse(true);
                    sendRightMouse(false);
                    return;
                case TouchControlData.SPECIAL_MOUSE_MIDDLE:
                    sendMouseButton(2, true, "Unable to send middle mouse from key keyboard");
                    sendMouseButton(2, false, "Unable to send middle mouse from key keyboard");
                    return;
                case TouchControlData.SPECIAL_SCROLL_UP:
                    sendScrollFromKeySender(1d);
                    return;
                case TouchControlData.SPECIAL_SCROLL_DOWN:
                    sendScrollFromKeySender(-1d);
                    return;
                case TouchControlData.SPECIAL_KEYBOARD:
                    TouchKeyboardHelper.showKeyboard(this);
                    return;
                case TouchControlData.SPECIAL_MENU:
                    onMenuRequested();
                    return;
                case TouchControlData.SPECIAL_TOGGLE_CONTROLS:
                    toggleControlVisible();
                    return;
                case TouchControlData.SPECIAL_VIRTUAL_MOUSE:
                    toggleVirtualMouseFromKeySender();
                    return;
                case TouchControlData.SPECIAL_KEY_SENDER_KEYBOARD:
                    return;
                default:
                    if (keyCode > 0) sendKeyTap(keyCode);
            }
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send queued key keyboard input", throwable);
        }
    }

    private void sendScrollFromKeySender(double amount) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendScroll(0d, amount);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send scroll from key keyboard", throwable);
        }
    }

    private void toggleVirtualMouseFromKeySender() {
        boolean enabled = !ControlsPreferences.isVirtualMouseEnabled(getContext());
        ControlsPreferences.setVirtualMouseEnabled(getContext(), enabled);
        Toast.makeText(getContext(), enabled ? "Virtual cursor shown" : "Virtual cursor hidden", Toast.LENGTH_SHORT).show();
        postInvalidateOnAnimation();
    }

    private boolean routePointerDown(@NonNull MotionEvent event, int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return false;

        int pointerId = event.getPointerId(pointerIndex);
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        boolean grabbed = updateMouseGrabState();

        if (grabbed) {
            // Virtual cursor visibility must never change touch routing.
            // In a grabbed world, the real Minecraft hotbar still needs to win
            // so normal touch-only users can select slots whether the cursor is
            // shown or hidden.
            int hotbarSlot = hotbarSlotForTouch(x, y);
            if (hotbarSlot >= 0) {
                startHotbarPointer(pointerId, hotbarSlot);
                return true;
            }
        }

        TouchControlButtonView control = findControlUnder(x, y);
        if (control != null) {
            controlPointerTargets.put(pointerId, control);
            dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_DOWN, control);
            return true;
        }

        // In-game look/attack: track one empty-space pointer by ID and send
        // relative deltas. Do not switch this into absolute mouse mode just
        // because the virtual cursor is visible.
        if (grabbed) {
            if (cameraPointerId == NO_POINTER_ID) {
                startCameraPointer(event, pointerIndex, pointerId);
                return true;
            }
            return hasActiveTouchRoute();
        }

        if (!grabbed && ControlsPreferences.isVirtualMouseEnabled(getContext())) {
            if (virtualMousePointerId == NO_POINTER_ID) {
                startVirtualMousePointer(event, pointerIndex, pointerId);
                return true;
            }
            return hasActiveTouchRoute();
        }

        // Menus/inventory are not grabbed, so empty screen touches pass through
        // to Minecraft as an absolute GUI click. If the passthrough target was
        // not wired for any reason, return false so Android can continue hit
        // testing lower siblings instead of the overlay eating the touch.
        if (passthroughPointerId == NO_POINTER_ID && passthroughTarget != null) {
            passthroughPointerId = pointerId;
            passthroughDownTime = event.getEventTime();
            passthroughDownX = x;
            passthroughDownY = y;
            passthroughMovedPastSlop = false;
            dispatchSinglePointerToPassthrough(event, pointerIndex, MotionEvent.ACTION_DOWN);
            return true;
        }

        return false;
    }

    private void routePointerUp(@NonNull MotionEvent event, int pointerIndex, boolean finalUp) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
            if (finalUp) clearRuntimeTouchRouting();
            return;
        }

        int pointerId = event.getPointerId(pointerIndex);

        if (pointerId == cameraPointerId) {
            finishCameraPointer(event, pointerIndex, false);
        }

        if (pointerId == hotbarPointerId) {
            finishHotbarPointer(true);
        }

        if (pointerId == virtualMousePointerId) {
            finishVirtualMousePointer(event, pointerIndex, false);
        }

        if (pointerId == passthroughPointerId) {
            dispatchSinglePointerToPassthrough(event, pointerIndex, MotionEvent.ACTION_UP);
            passthroughPointerId = NO_POINTER_ID;
            passthroughDownTime = 0L;
            passthroughDownX = 0f;
            passthroughDownY = 0f;
            passthroughMovedPastSlop = false;
        }

        TouchControlButtonView control = controlPointerTargets.get(pointerId);
        if (control != null) {
            dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_UP, control);
            controlPointerTargets.remove(pointerId);
        }

        if (finalUp) {
            clearRuntimeTouchRouting();
        }
    }

    private void dispatchCancelToControlPointers(@NonNull MotionEvent event) {
        for (int i = 0; i < controlPointerTargets.size(); i++) {
            int pointerId = controlPointerTargets.keyAt(i);
            int pointerIndex = event.findPointerIndex(pointerId);
            TouchControlButtonView control = controlPointerTargets.valueAt(i);
            if (pointerIndex >= 0 && control != null) {
                dispatchSinglePointerToControl(event, pointerIndex, MotionEvent.ACTION_CANCEL, control);
            }
        }
    }

    private void dispatchActiveControlPointers(@NonNull MotionEvent event, int action) {
        for (int i = 0; i < controlPointerTargets.size(); i++) {
            int pointerId = controlPointerTargets.keyAt(i);
            int pointerIndex = event.findPointerIndex(pointerId);
            TouchControlButtonView control = controlPointerTargets.valueAt(i);
            if (pointerIndex >= 0 && control != null) {
                dispatchSinglePointerToControl(event, pointerIndex, action, control);
            }
        }
    }


    private void startVirtualMousePointer(@NonNull MotionEvent event, int pointerIndex, int pointerId) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;

        virtualMousePointerId = pointerId;
        virtualMouseDownX = event.getX(pointerIndex);
        virtualMouseDownY = event.getY(pointerIndex);
        virtualMouseLastX = virtualMouseDownX;
        virtualMouseLastY = virtualMouseDownY;
        virtualMouseMovedPastSlop = false;
        ensureVirtualMouseCursorInBounds();
        sendVirtualMouseCursorPosition();
        postInvalidateOnAnimation();
    }

    private void dispatchActiveVirtualMousePointer(@NonNull MotionEvent event) {
        if (virtualMousePointerId == NO_POINTER_ID) return;
        if (updateMouseGrabState()) {
            cancelVirtualMousePointer();
            return;
        }

        int pointerIndex = event.findPointerIndex(virtualMousePointerId);
        if (pointerIndex < 0) return;

        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        float dx = x - virtualMouseLastX;
        float dy = y - virtualMouseLastY;
        virtualMouseLastX = x;
        virtualMouseLastY = y;

        float totalDx = x - virtualMouseDownX;
        float totalDy = y - virtualMouseDownY;
        if (!virtualMouseMovedPastSlop
                && ((totalDx * totalDx) + (totalDy * totalDy)) > (cameraTouchSlop * cameraTouchSlop)) {
            virtualMouseMovedPastSlop = true;
        }

        if (dx == 0f && dy == 0f) {
            postInvalidateOnAnimation();
            return;
        }
        sendVirtualMouseDelta(dx, dy);
    }

    private void finishVirtualMousePointer(@NonNull MotionEvent event, int pointerIndex, boolean cancelled) {
        if (pointerIndex >= 0 && pointerIndex < event.getPointerCount() && !cancelled) {
            dispatchActiveVirtualMousePointer(event);
        }

        if (!cancelled && !virtualMouseMovedPastSlop) {
            // Quick tap in fake-mouse mode clicks at the visible cursor, not at
            // the finger position. Use a normal Mouse Left button for click-hold.
            sendVirtualMouseCursorPosition();
            sendLeftMouse(true);
            sendLeftMouse(false);
        }

        cancelVirtualMousePointer();
        postInvalidateOnAnimation();
    }

    private void cancelVirtualMousePointer() {
        virtualMousePointerId = NO_POINTER_ID;
        virtualMouseDownX = virtualMouseDownY = virtualMouseLastX = virtualMouseLastY = 0f;
        virtualMouseMovedPastSlop = false;
    }

    private void startCameraPointer(@NonNull MotionEvent event, int pointerIndex, int pointerId) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;

        cameraPointerId = pointerId;
        cameraDownX = event.getX(pointerIndex);
        cameraDownY = event.getY(pointerIndex);
        cameraLastX = cameraDownX;
        cameraLastY = cameraDownY;
        cameraMovedPastSlop = false;
        cameraLongPressAttackActive = false;
        if (ControlsPreferences.isMinecraftTouchGesturesEnabled(getContext())) {
            scheduleCameraLongPressAttack();
        }
    }

    private void dispatchActiveCameraPointer(@NonNull MotionEvent event) {
        if (cameraPointerId == NO_POINTER_ID) return;

        int pointerIndex = event.findPointerIndex(cameraPointerId);
        if (pointerIndex < 0) return;

        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        float dx = x - cameraLastX;
        float dy = y - cameraLastY;
        cameraLastX = x;
        cameraLastY = y;

        float totalDx = x - cameraDownX;
        float totalDy = y - cameraDownY;
        if (!cameraMovedPastSlop && ((totalDx * totalDx) + (totalDy * totalDy)) > (cameraTouchSlop * cameraTouchSlop)) {
            cameraMovedPastSlop = true;
            cancelCameraLongPressAttack(false);
        }

        if (dx == 0f && dy == 0f) return;
        sendRelativeCameraDelta(dx, dy);
    }

    private void finishCameraPointer(@NonNull MotionEvent event, int pointerIndex, boolean cancelled) {
        if (pointerIndex >= 0 && pointerIndex < event.getPointerCount() && !cancelled) {
            dispatchActiveCameraPointer(event);
        }

        cancelCameraLongPressAttack(cancelled);

        if (!cancelled && cameraLongPressAttackActive) {
            sendLeftMouse(false);
        } else if (!cancelled
                && !cameraMovedPastSlop
                && ControlsPreferences.isMinecraftTouchGesturesEnabled(getContext())) {
            // Quick tap on the look area should use/place, not attack.
            // Long press remains left mouse for digging / punching.
            sendRightMouse(true);
            sendRightMouse(false);
        }

        cameraLongPressAttackActive = false;
        cameraPointerId = NO_POINTER_ID;
        cameraDownX = cameraDownY = cameraLastX = cameraLastY = 0f;
        cameraMovedPastSlop = false;
    }

    private void cancelCameraPointer(boolean sendRelease) {
        if (sendRelease && cameraLongPressAttackActive) {
            sendLeftMouse(false);
        }
        cancelCameraLongPressAttack(true);
        cameraLongPressAttackActive = false;
        cameraPointerId = NO_POINTER_ID;
        cameraMovedPastSlop = false;
    }

    private void scheduleCameraLongPressAttack() {
        cancelCameraLongPressAttack(false);
        cameraLongPressRunnable = () -> {
            if (cameraPointerId == NO_POINTER_ID || cameraMovedPastSlop || cameraLongPressAttackActive) return;
            cameraLongPressAttackActive = true;
            sendLeftMouse(true);
        };
        gestureHandler.postDelayed(cameraLongPressRunnable, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelCameraLongPressAttack(boolean cancelActivePress) {
        if (cameraLongPressRunnable != null) {
            gestureHandler.removeCallbacks(cameraLongPressRunnable);
            cameraLongPressRunnable = null;
        }
        if (cancelActivePress && cameraLongPressAttackActive) {
            sendLeftMouse(false);
            cameraLongPressAttackActive = false;
        }
    }

    private void startHotbarPointer(int pointerId, int slot) {
        hotbarPointerId = pointerId;
        hotbarLastSlot = slot;
        hotbarDoubleTapConsumed = false;

        boolean doubleTap = isHotbarDoubleTap(slot);
        sendKeyTap(49 + slot); // GLFW_KEY_1 through GLFW_KEY_9

        if (doubleTap) {
            hotbarDoubleTapConsumed = true;
            clearLastHotbarTap();
            sendKeyTap(LwjglGlfwKeycode.GLFW_KEY_F);
        }
    }

    private void dispatchActiveHotbarPointer(@NonNull MotionEvent event) {
        if (hotbarPointerId == NO_POINTER_ID) return;

        int pointerIndex = event.findPointerIndex(hotbarPointerId);
        if (pointerIndex < 0) return;

        int slot = hotbarSlotForTouch(event.getX(pointerIndex), event.getY(pointerIndex));
        if (slot < 0 || slot == hotbarLastSlot) return;

        hotbarLastSlot = slot;
        hotbarDoubleTapConsumed = false;
        sendKeyTap(49 + slot);
    }

    private boolean isHotbarDoubleTap(int slot) {
        if (slot < 0 || slot != lastHotbarTapSlot || lastHotbarTapTimeMs <= 0L) return false;
        long elapsed = SystemClock.uptimeMillis() - lastHotbarTapTimeMs;
        return elapsed >= 0L && elapsed <= ViewConfiguration.getDoubleTapTimeout();
    }

    private void finishHotbarPointer(boolean recordTap) {
        if (recordTap && !hotbarDoubleTapConsumed && hotbarLastSlot >= 0) {
            lastHotbarTapSlot = hotbarLastSlot;
            lastHotbarTapTimeMs = SystemClock.uptimeMillis();
        }

        hotbarPointerId = NO_POINTER_ID;
        hotbarLastSlot = -1;
        hotbarDoubleTapConsumed = false;
    }

    private void clearLastHotbarTap() {
        lastHotbarTapSlot = -1;
        lastHotbarTapTimeMs = 0L;
    }

    private int hotbarSlotForTouch(float x, float y) {
        return TouchHotbarHitbox.slotForTouch(
                getContext(),
                resolveMinecraftOptionsFile(),
                getWidth(),
                getHeight(),
                resolveGameBufferWidth(),
                resolveGameBufferHeight(),
                x,
                y
        );
    }

    @Nullable
    private File resolveMinecraftOptionsFile() {
        if (minecraftOptionsFile != null && minecraftOptionsFile.isFile()) {
            return minecraftOptionsFile;
        }

        long now = SystemClock.uptimeMillis();
        if (cachedMinecraftOptionsFile != null
                && cachedMinecraftOptionsFile.isFile()
                && now - lastMinecraftOptionsResolveAtMs < OPTIONS_FILE_RESOLVE_THROTTLE_MS) {
            return cachedMinecraftOptionsFile;
        }

        File resolved = findBestMinecraftOptionsFile();
        cachedMinecraftOptionsFile = resolved;
        lastMinecraftOptionsResolveAtMs = now;
        return resolved;
    }

    @Nullable
    private File findBestMinecraftOptionsFile() {
        try {
            String minecraftHome = PathManager.DIR_MINECRAFT_HOME;
            if (minecraftHome == null || minecraftHome.trim().isEmpty()) return null;

            File home = new File(minecraftHome);
            java.util.ArrayList<File> candidates = new java.util.ArrayList<>();

            // These cover the common cases:
            // - PathManager points directly at the active game directory
            // - PathManager points at an isolated instance root
            // - PathManager points at the global .minecraft directory
            addOptionsCandidate(candidates, home);
            addOptionsCandidate(candidates, new File(home, "game"));

            File parent = home.getParentFile();
            if (parent != null) {
                addOptionsCandidate(candidates, parent);
                addOptionsCandidate(candidates, new File(parent, "game"));
            }

            File grandparent = parent == null ? null : parent.getParentFile();
            if (grandparent != null) {
                addOptionsCandidate(candidates, grandparent);
                addOptionsCandidate(candidates, new File(grandparent, "game"));
            }

            // If the launcher is in isolated-instance mode, the active options.txt
            // usually lives under .minecraft/instances/<instance>/game/options.txt.
            scanInstancesDirectory(candidates, new File(home, "instances"));
            if (parent != null) scanInstancesDirectory(candidates, new File(parent, "instances"));
            if (grandparent != null) scanInstancesDirectory(candidates, new File(grandparent, "instances"));

            return newestReadableOptionsFile(candidates);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addOptionsCandidate(@NonNull java.util.ArrayList<File> candidates, @Nullable File directory) {
        if (directory == null) return;
        candidates.add(new File(directory, "options.txt"));
    }

    private static void scanInstancesDirectory(@NonNull java.util.ArrayList<File> candidates, @Nullable File instancesDir) {
        if (instancesDir == null || !instancesDir.isDirectory()) return;

        File[] children;
        try {
            children = instancesDir.listFiles();
        } catch (Throwable ignored) {
            children = null;
        }
        if (children == null) return;

        for (File instance : children) {
            if (instance == null || !instance.isDirectory()) continue;
            addOptionsCandidate(candidates, new File(instance, "game"));
            addOptionsCandidate(candidates, instance);
            addOptionsCandidate(candidates, new File(instance, ".minecraft"));
        }
    }

    @Nullable
    private static File newestReadableOptionsFile(@NonNull java.util.ArrayList<File> candidates) {
        File best = null;
        long bestModified = Long.MIN_VALUE;

        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile()) continue;
            long modified;
            try {
                modified = candidate.lastModified();
            } catch (Throwable ignored) {
                modified = 0L;
            }

            if (best == null || modified > bestModified) {
                best = candidate;
                bestModified = modified;
            }
        }

        return best;
    }

    @NonNull
    private static String debugOptionsFileName(@Nullable File file) {
        if (file == null) return "none";

        try {
            String path = file.getAbsolutePath();
            String marker = File.separator + "instances" + File.separator;
            int index = path.lastIndexOf(marker);
            if (index >= 0) {
                return path.substring(index + 1);
            }

            File parent = file.getParentFile();
            return parent == null ? file.getName() : parent.getName() + File.separator + file.getName();
        } catch (Throwable ignored) {
            return file.getName();
        }
    }

    @NonNull
    private static String formatScale(float scale) {
        int rounded = Math.round(scale);
        if (Math.abs(scale - rounded) < 0.01f) return String.valueOf(rounded);
        return String.format(Locale.US, "%.2f", scale);
    }

    private float resolveGameBufferWidth() {
        try {
            if (CallbackBridge.windowWidth > 1) return CallbackBridge.windowWidth;
            if (CallbackBridge.physicalWidth > 1) return CallbackBridge.physicalWidth;
        } catch (Throwable ignored) {
        }
        return getWidth();
    }

    private float resolveGameBufferHeight() {
        try {
            if (CallbackBridge.windowHeight > 1) return CallbackBridge.windowHeight;
            if (CallbackBridge.physicalHeight > 1) return CallbackBridge.physicalHeight;
        } catch (Throwable ignored) {
        }
        return getHeight();
    }

    private void sendKeyTap(int keyCode) {
        try {
            CallbackBridge.setInputReady(true);
            if (keyCode == 84 || keyCode == 47) {
                TouchKeyboardHelper.markChatKeyPressed();
            }
            CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
            CallbackBridge.setModifiers(keyCode, true);
            CallbackBridge.sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
            CallbackBridge.setModifiers(keyCode, false);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send key tap", throwable);
        }
    }


    private void sendVirtualMouseDelta(float dx, float dy) {
        try {
            ensureVirtualMouseCursorInBounds();
            virtualCursorBridgeX = clamp(
                    virtualCursorBridgeX + viewDeltaToBridgeX(dx),
                    0f,
                    maxCursorCoordinate(CallbackBridge.windowWidth)
            );
            virtualCursorBridgeY = clamp(
                    virtualCursorBridgeY + viewDeltaToBridgeY(dy),
                    0f,
                    maxCursorCoordinate(CallbackBridge.windowHeight)
            );
            sendVirtualMouseCursorPosition();
            postInvalidateOnAnimation();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to move virtual mouse cursor", throwable);
        }
    }

    private void resetVirtualMouseCursorToCenter(boolean sendToMinecraft) {
        try {
            float maxX = maxCursorCoordinate(CallbackBridge.windowWidth);
            float maxY = maxCursorCoordinate(CallbackBridge.windowHeight);
            virtualCursorBridgeX = maxX / 2f;
            virtualCursorBridgeY = maxY / 2f;
            virtualCursorInitialized = true;
            if (sendToMinecraft) sendVirtualMouseCursorPosition();
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to reset virtual mouse cursor", throwable);
        }
    }

    private void ensureVirtualMouseCursorInBounds() {
        try {
            float maxX = maxCursorCoordinate(CallbackBridge.windowWidth);
            float maxY = maxCursorCoordinate(CallbackBridge.windowHeight);

            if (!virtualCursorInitialized
                    || Float.isNaN(virtualCursorBridgeX)
                    || Float.isInfinite(virtualCursorBridgeX)
                    || Float.isNaN(virtualCursorBridgeY)
                    || Float.isInfinite(virtualCursorBridgeY)) {
                resetVirtualMouseCursorToCenter(false);
                return;
            }

            virtualCursorBridgeX = clamp(virtualCursorBridgeX, 0f, maxX);
            virtualCursorBridgeY = clamp(virtualCursorBridgeY, 0f, maxY);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to prepare virtual mouse cursor", throwable);
        }
    }

    private void sendVirtualMouseCursorPosition() {
        try {
            ensureVirtualMouseCursorInBounds();
            CallbackBridge.setInputReady(true);
            CallbackBridge.mouseX = virtualCursorBridgeX;
            CallbackBridge.mouseY = virtualCursorBridgeY;
            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send virtual mouse cursor", throwable);
        }
    }

    private float bridgeCursorToViewX(float bridgeX) {
        float maxBridge = maxCursorCoordinate(CallbackBridge.windowWidth);
        float maxView = Math.max(0f, getWidth() - 1f);
        if (maxBridge <= 0f || maxView <= 0f) return 0f;
        return clamp(bridgeX, 0f, maxBridge) * maxView / maxBridge;
    }

    private float bridgeCursorToViewY(float bridgeY) {
        float maxBridge = maxCursorCoordinate(CallbackBridge.windowHeight);
        float maxView = Math.max(0f, getHeight() - 1f);
        if (maxBridge <= 0f || maxView <= 0f) return 0f;
        return clamp(bridgeY, 0f, maxBridge) * maxView / maxBridge;
    }

    private float viewDeltaToBridgeX(float viewDx) {
        float maxBridge = maxCursorCoordinate(CallbackBridge.windowWidth);
        float maxView = Math.max(0f, getWidth() - 1f);
        if (maxBridge <= 0f || maxView <= 0f) return viewDx;
        return viewDx * maxBridge / maxView;
    }

    private float viewDeltaToBridgeY(float viewDy) {
        float maxBridge = maxCursorCoordinate(CallbackBridge.windowHeight);
        float maxView = Math.max(0f, getHeight() - 1f);
        if (maxBridge <= 0f || maxView <= 0f) return viewDy;
        return viewDy * maxBridge / maxView;
    }

    private void sendAbsoluteCursor(float x, float y) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.mouseX = clamp(x, 0f, Math.max(1, CallbackBridge.windowWidth));
            CallbackBridge.mouseY = clamp(y, 0f, Math.max(1, CallbackBridge.windowHeight));
            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send virtual mouse cursor", throwable);
        }
    }

    private void sendRelativeCameraDelta(float dx, float dy) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.mouseX += dx;
            CallbackBridge.mouseY += dy;
            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to send camera touch delta", throwable);
        }
    }

    private void sendLeftMouse(boolean down) {
        sendMouseButton(MOUSE_BUTTON_LEFT, down, "Unable to send touch attack");
    }

    private void sendRightMouse(boolean down) {
        sendMouseButton(MOUSE_BUTTON_RIGHT, down, "Unable to send touch use/place");
    }

    private void sendMouseButton(int button, boolean down, @NonNull String errorMessage) {
        try {
            CallbackBridge.setInputReady(true);
            CallbackBridge.sendMouseButton(button, down);
        } catch (Throwable throwable) {
            Logging.e(TAG, errorMessage, throwable);
        }
    }

    private static boolean isMouseGrabbed() {
        try {
            return CallbackBridge.isGrabbing();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void applyControlsVisualState() {
        applyControlsVisualStateForGrabState(isMouseGrabbed());
    }

    private void applyControlsVisualStateForGrabState(boolean grabbed) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TouchControlButtonView) {
                TouchControlButtonView button = (TouchControlButtonView) child;
                child.setVisibility(shouldShowControlButton(button.getData(), grabbed) ? VISIBLE : INVISIBLE);
            }
        }
        postInvalidateOnAnimation();
    }

    private boolean shouldCreateControlButton(@NonNull TouchControlData data) {
        return data.visibleInGame
                || data.visibleInMenu
                || data.visibleWhenControlsHidden
                || TouchControlData.shouldStayVisibleWhenControlsHiddenByDefault(data.action);
    }

    private boolean shouldShowControlButton(@NonNull TouchControlData data) {
        return shouldShowControlButton(data, isMouseGrabbed());
    }

    private boolean shouldShowControlButton(@NonNull TouchControlData data, boolean grabbed) {
        if (editMode) return true;

        boolean allowedInCurrentMinecraftState = grabbed ? data.visibleInGame : data.visibleInMenu;
        if (!allowedInCurrentMinecraftState) return false;

        if (controlsVisible) return true;
        return data.visibleWhenControlsHidden
                || TouchControlData.shouldStayVisibleWhenControlsHiddenByDefault(data.action);
    }

    private boolean hasGameCursorOverlayInViewTree() {
        try {
            View root = getRootView();
            if (containsGameCursorOverlay(root)) return true;

            ViewParent parent = getParent();
            return parent instanceof View && containsGameCursorOverlay((View) parent);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean containsGameCursorOverlay(@Nullable View view) {
        if (view == null || view == this) return false;
        if (view instanceof GameCursorOverlay) return true;
        if (!(view instanceof ViewGroup)) return false;

        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsGameCursorOverlay(group.getChildAt(i))) return true;
        }
        return false;
    }

    @Nullable
    private MinecraftGLSurface findMinecraftSurfaceTarget() {
        View current = passthroughTarget;
        while (current != null) {
            if (current instanceof MinecraftGLSurface) {
                return (MinecraftGLSurface) current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }

        View root = getRootView();
        MinecraftGLSurface found = findMinecraftSurfaceInTree(root);
        if (found != null) return found;

        ViewParent parent = getParent();
        return parent instanceof View ? findMinecraftSurfaceInTree((View) parent) : null;
    }

    @Nullable
    private MinecraftGLSurface findMinecraftSurfaceInTree(@Nullable View view) {
        if (view == null || view == this) return null;
        if (view instanceof MinecraftGLSurface) return (MinecraftGLSurface) view;
        if (!(view instanceof ViewGroup)) return null;

        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            MinecraftGLSurface found = findMinecraftSurfaceInTree(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean dispatchActivePassthroughPointer(@NonNull MotionEvent event, int action) {
        if (passthroughPointerId == NO_POINTER_ID) {
            return false;
        }

        int pointerIndex = event.findPointerIndex(passthroughPointerId);
        if (pointerIndex < 0) {
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            updatePassthroughMoveState(event, pointerIndex);
        }

        return dispatchSinglePointerToPassthrough(event, pointerIndex, action);
    }

    private void updatePassthroughMoveState(@NonNull MotionEvent event, int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;
        float dx = event.getX(pointerIndex) - passthroughDownX;
        float dy = event.getY(pointerIndex) - passthroughDownY;
        if ((dx * dx) + (dy * dy) > (cameraTouchSlop * cameraTouchSlop)) {
            passthroughMovedPastSlop = true;
        }
    }


    private boolean dispatchSinglePointerToPassthrough(
            @NonNull MotionEvent source,
            int pointerIndex,
            int action
    ) {
        if (pointerIndex < 0 || pointerIndex >= source.getPointerCount()) {
            return false;
        }

        MinecraftGLSurface minecraftSurface = findMinecraftSurfaceTarget();
        if (minecraftSurface == null && passthroughTarget == null) {
            return false;
        }

        long downTime = passthroughDownTime > 0L ? passthroughDownTime : source.getDownTime();
        MotionEvent single = MotionEvent.obtain(
                downTime,
                source.getEventTime(),
                action,
                source.getX(pointerIndex),
                source.getY(pointerIndex),
                source.getMetaState()
        );
        int sourceClass = source.getSource();
        single.setSource(sourceClass != 0 ? sourceClass : InputDevice.SOURCE_TOUCHSCREEN);
        try {
            if (minecraftSurface != null) {
                return minecraftSurface.handleTouchFromOverlay(single);
            }
            return passthroughTarget != null && passthroughTarget.dispatchTouchEvent(single);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dispatch passthrough touch event", throwable);
            return false;
        } finally {
            single.recycle();
        }
    }

    private boolean dispatchWholeTouchEventToPassthrough(@NonNull MotionEvent event) {
        MinecraftGLSurface minecraftSurface = findMinecraftSurfaceTarget();
        if (minecraftSurface == null && passthroughTarget == null) {
            return false;
        }

        MotionEvent copy = MotionEvent.obtain(event);
        try {
            if (copy.getSource() == 0) copy.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            return minecraftSurface != null
                    ? minecraftSurface.handleTouchFromOverlay(copy)
                    : passthroughTarget != null && passthroughTarget.dispatchTouchEvent(copy);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dispatch whole passthrough touch event", throwable);
            return false;
        } finally {
            copy.recycle();
        }
    }

    private boolean dispatchWholeGenericEventToPassthrough(@NonNull MotionEvent event) {
        MinecraftGLSurface minecraftSurface = findMinecraftSurfaceTarget();
        if (minecraftSurface == null && passthroughTarget == null) {
            return false;
        }

        MotionEvent copy = MotionEvent.obtain(event);
        try {
            return minecraftSurface != null
                    ? minecraftSurface.dispatchGenericMotionEvent(copy)
                    : passthroughTarget != null && passthroughTarget.dispatchGenericMotionEvent(copy);
        } catch (Throwable throwable) {
            Logging.e(TAG, "Unable to dispatch hardware pointer passthrough event", throwable);
            return false;
        } finally {
            copy.recycle();
        }
    }

    private static boolean isHardwarePointerEvent(@NonNull MotionEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                || (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
            return true;
        }

        for (int i = 0; i < event.getPointerCount(); i++) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE) return true;
        }
        return false;
    }

    private void dispatchSinglePointerToControl(
            @NonNull MotionEvent source,
            int pointerIndex,
            int action,
            @NonNull TouchControlButtonView control
    ) {
        if (pointerIndex < 0 || pointerIndex >= source.getPointerCount()) return;

        float localX = source.getX(pointerIndex) - control.getX();
        float localY = source.getY(pointerIndex) - control.getY();
        MotionEvent single = MotionEvent.obtain(
                source.getDownTime(),
                source.getEventTime(),
                action,
                localX,
                localY,
                source.getMetaState()
        );
        try {
            control.dispatchTouchEvent(single);
        } finally {
            single.recycle();
        }
    }

    @Nullable
    private TouchControlButtonView findControlUnder(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (!(child instanceof TouchControlButtonView)) continue;
            if (child.getVisibility() != VISIBLE) continue;
            if (x >= child.getX()
                    && x <= child.getX() + child.getWidth()
                    && y >= child.getY()
                    && y <= child.getY() + child.getHeight()) {
                return (TouchControlButtonView) child;
            }
        }
        return null;
    }

    private void clearRuntimeTouchRouting() {
        cancelCameraPointer(true);
        finishHotbarPointer(false);
        cancelVirtualMousePointer();
        passthroughPointerId = NO_POINTER_ID;
        passthroughDownTime = 0L;
        passthroughDownX = 0f;
        passthroughDownY = 0f;
        passthroughMovedPastSlop = false;
        controlPointerTargets.clear();
    }


    private boolean hasActiveTouchRoute() {
        return cameraPointerId != NO_POINTER_ID
                || hotbarPointerId != NO_POINTER_ID
                || virtualMousePointerId != NO_POINTER_ID
                || passthroughPointerId != NO_POINTER_ID
                || controlPointerTargets.size() > 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        hideKeySenderKeyboard();
        clearRuntimeTouchRouting();
        applyAndroidPointerIconPolicy(false, true);
        super.onDetachedFromWindow();
    }
}
