package com.codex.courserecorder;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** Only the small control window intercepts touches; never covers the full screen. */
final class FloatingRecordingControls {
    interface Actions { void pause(); void stop(); void unavailable(); }
    private final Context context;
    private final WindowManager windows;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Actions actions;
    private final boolean allowPause;
    private final int corner;
    private FrameLayout root;
    private LinearLayout panel;
    private TextView dot, status;
    private GradientDrawable dotBackground;
    private Button pause, stop;
    private WindowManager.LayoutParams params;
    private boolean attached, expanded, confirmStop;
    private int expandedX, expandedY;
    private String state = "starting";
    private final Runnable clearConfirmation = () -> {
        confirmStop = false;
        if (stop != null) stop.setText("starting".equals(state) ? "取消录制" : "结束");
    };

    FloatingRecordingControls(Context context, boolean allowPause, int corner, Actions actions) {
        this.context = context;
        this.allowPause = allowPause;
        this.corner = corner;
        this.actions = actions;
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean show() {
        if (!Settings.canDrawOverlays(context)) return false;
        root = new FrameLayout(context);
        dot = new TextView(context);
        dot.setText("●");
        dot.setTextSize(18);
        dot.setGravity(Gravity.CENTER);
        dot.setContentDescription("展开录屏控制");
        dotBackground = new GradientDrawable();
        dotBackground.setShape(GradientDrawable.OVAL);
        dotBackground.setColor(Color.rgb(20, 33, 61));
        dot.setBackground(dotBackground);
        dot.setElevation(dp(8));
        dot.setOnClickListener(v -> setExpanded(true));
        root.addView(dot, new FrameLayout.LayoutParams(-1, -1));

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(6), dp(8), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(20, 33, 61));
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), Color.rgb(77, 108, 161));
        panel.setBackground(background);
        panel.setElevation(dp(8));
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView grip = new TextView(context);
        grip.setText("⠿"); grip.setTextSize(24); grip.setTextColor(Color.rgb(168, 192, 240));
        grip.setGravity(Gravity.CENTER);
        grip.setContentDescription("拖动悬浮录制控制条");
        header.addView(grip, new LinearLayout.LayoutParams(dp(32), dp(32)));
        status = new TextView(context);
        status.setTextSize(13); status.setTextColor(Color.WHITE);
        status.setSingleLine(true);
        status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        TextView collapse = new TextView(context);
        collapse.setText("收起");
        collapse.setTextSize(13);
        collapse.setTextColor(Color.rgb(191, 212, 255));
        collapse.setGravity(Gravity.CENTER);
        collapse.setOnClickListener(v -> setExpanded(false));
        header.addView(collapse, new LinearLayout.LayoutParams(dp(48), dp(32)));
        panel.addView(header);
        installDrag(grip);

        LinearLayout buttons = new LinearLayout(context);
        pause = button("暂停", Color.rgb(36, 107, 253));
        pause.setVisibility(allowPause ? View.VISIBLE : View.GONE);
        pause.setOnClickListener(v -> {
            handler.removeCallbacks(clearConfirmation); clearConfirmation.run();
            actions.pause();
        });
        LinearLayout.LayoutParams pauseLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        pauseLp.rightMargin = dp(8);
        buttons.addView(pause, pauseLp);
        stop = button("结束", Color.rgb(210, 60, 68));
        stop.setOnClickListener(v -> {
            if (confirmStop || "starting".equals(state)) {
                handler.removeCallbacks(clearConfirmation);
                stop.setEnabled(false); pause.setEnabled(false);
                actions.stop();
            } else {
                confirmStop = true;
                stop.setText("确认结束");
                handler.postDelayed(clearConfirmation, 4000);
            }
        });
        buttons.addView(stop, new LinearLayout.LayoutParams(0, dp(44), 1));
        panel.addView(buttons);
        params = new WindowManager.LayoutParams(dp(48), dp(48),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setTitle("课堂录屏悬浮控制");
        anchorExpanded();
        anchorCollapsed();
        try { windows.addView(root, params); attached = true; update(state, 0); return true; }
        catch (RuntimeException error) { root = null; return false; }
    }

    void update(String newState, long durationUs) {
        if (!attached) return;
        if (!Settings.canDrawOverlays(context)) { unavailable(); return; }
        state = newState;
        long seconds = durationUs / 1_000_000;
        String time = String.format(Locale.CHINA, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        boolean paused = "paused".equals(state);
        boolean recording = "recording".equals(state) || paused;
        status.setText((paused ? "Ⅱ 已暂停  " : recording ? "● 录制  " : "saving".equals(state) ? "正在保存…  " : "准备录制…  ") + time);
        status.setTextColor(paused ? Color.rgb(255, 202, 87) : Color.WHITE);
        int dotColor = paused ? Color.rgb(255, 202, 87) : "recording".equals(state)
                ? Color.rgb(255, 91, 101) : Color.rgb(128, 176, 255);
        dot.setTextColor(dotColor);
        dotBackground.setStroke(dp(2), dotColor);
        dot.setContentDescription((paused ? "录制已暂停" : "recording".equals(state) ? "正在录制" : "录屏控制") + "，点按展开控制");
        pause.setText(paused ? "继续" : "暂停");
        pause.setEnabled(recording);
        stop.setEnabled(!"saving".equals(state));
        if ("starting".equals(state)) stop.setText("取消录制");
        else if (!confirmStop) stop.setText("结束");
    }

    void onConfigurationChanged(Configuration ignored) {
        if (!attached) return;
        anchorExpanded();
        if (expanded) {
            params.x = expandedX;
            params.y = expandedY;
            clamp();
        } else anchorCollapsed();
        updatePosition();
    }

    void hide() {
        handler.removeCallbacksAndMessages(null);
        if (attached) {
            try { windows.removeViewImmediate(root); } catch (RuntimeException ignored) { }
        }
        attached = false; expanded = false; root = null; panel = null; dot = null;
    }

    private void unavailable() { hide(); actions.unavailable(); }

    private Button button(String title, int color) {
        Button result = new Button(context);
        result.setText(title); result.setTextSize(15); result.setTextColor(Color.WHITE);
        result.setAllCaps(false); result.setMinWidth(0); result.setMinimumWidth(0);
        result.setMinHeight(0); result.setMinimumHeight(0); result.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(8));
        result.setBackground(background);
        return result;
    }

    private void installDrag(View grip) {
        grip.setOnTouchListener(new View.OnTouchListener() {
            private float initialX, initialY;
            private int initialWindowX, initialWindowY;
            private boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (!attached) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    initialX = event.getRawX(); initialY = event.getRawY();
                    initialWindowX = params.x; initialWindowY = params.y; moved = false;
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - initialX, dy = event.getRawY() - initialY;
                    if (Math.hypot(dx, dy) > ViewConfiguration.get(context).getScaledTouchSlop()) moved = true;
                    if (moved) {
                        params.x = initialWindowX + Math.round(dx); params.y = initialWindowY + Math.round(dy);
                        clamp(); expandedX = params.x; expandedY = params.y; updatePosition();
                    }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    if (!moved && event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
                    return true;
                }
                return false;
            }
        });
    }

    private Point screen() {
        Point point = new Point(); windows.getDefaultDisplay().getRealSize(point); return point;
    }
    private void anchorExpanded() {
        Point screen = screen();
        expandedX = corner == 1 ? dp(8) : Math.max(dp(8), screen.x - dp(232) - dp(8));
        expandedY = dp(8);
    }
    private void anchorCollapsed() {
        Point screen = screen();
        params.x = Math.max(dp(8), screen.x - dp(48) - dp(8));
        params.y = dp(8);
        clamp();
    }
    private void setExpanded(boolean showPanel) {
        if (!attached || expanded == showPanel) return;
        if (expanded) { expandedX = params.x; expandedY = params.y; }
        if (!showPanel) {
            handler.removeCallbacks(clearConfirmation);
            clearConfirmation.run();
        }
        expanded = showPanel;
        params.width = dp(showPanel ? 232 : 48);
        params.height = dp(showPanel ? 90 : 48);
        dot.setVisibility(showPanel ? View.GONE : View.VISIBLE);
        panel.setVisibility(showPanel ? View.VISIBLE : View.GONE);
        if (showPanel) { params.x = expandedX; params.y = expandedY; clamp(); }
        else anchorCollapsed();
        updatePosition();
    }
    private void clamp() {
        Point screen = screen();
        params.x = Math.max(0, Math.min(params.x, screen.x - params.width));
        params.y = Math.max(0, Math.min(params.y, screen.y - params.height - dp(72)));
    }
    private void updatePosition() {
        try { windows.updateViewLayout(root, params); }
        catch (RuntimeException error) { unavailable(); }
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
