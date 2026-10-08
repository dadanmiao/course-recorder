package com.codex.courserecorder;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.ComponentActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends ComponentActivity {
    private static final int BLUE = Color.rgb(36, 107, 253);
    private static final int NAVY = Color.rgb(20, 33, 61);
    private static final int CANVAS = Color.rgb(244, 247, 252);
    private static final int TEXT = Color.rgb(27, 38, 59);
    private static final String PREFS = "recorder_settings";

    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private Spinner bitrateSpinner;
    private Spinner audioSpinner;
    private Spinner overlaySpinner;
    private Button overlayPermissionButton;
    private Switch pauseSwitch;
    private EditText nameInput;
    private Button primaryButton;
    private Button pauseButton;
    private TextView statusText;
    private TextView statusHint;
    private View settingsCard, courseCard;
    private boolean pendingStart;

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) requestScreenCapture();
                else {
                    pendingStart = false;
                    Toast.makeText(this, "需要麦克风权限才能录制所选声音", Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });

    private final ActivityResultLauncher<Intent> captureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onCaptureResult);

    private final ActivityResultLauncher<Intent> overlayPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                refreshOverlayPermission();
                if (pendingStart) {
                    if (!Settings.canDrawOverlays(this)) Toast.makeText(this,
                            "未开启悬浮权限，本次可从应用或通知操作", Toast.LENGTH_LONG).show();
                    prepareAudioAndCapture();
                }
            });

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateState(intent.getStringExtra(RecordingService.EXTRA_STATE),
                    intent.getStringExtra(RecordingService.EXTRA_MESSAGE));
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(NAVY);
        window.setNavigationBarColor(Color.WHITE);
        buildUi();
        restoreSettings();
        requestNotificationPermissionIfNeeded();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(RecordingService.ACTION_STATE);
        androidx.core.content.ContextCompat.registerReceiver(this, stateReceiver, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        refreshFromService();
        refreshOverlayPermission();
    }

    @Override protected void onStop() {
        super.onStop();
        try { unregisterReceiver(stateReceiver); } catch (IllegalArgumentException ignored) { }
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(CANVAS);
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(28), dp(26), dp(28), dp(24));
        header.setBackgroundColor(NAVY);

        TextView brand = text("课堂录屏", 30, Color.WHITE, Typeface.BOLD);
        header.addView(brand);
        TextView subtitle = text("平板录课 · 帧率与码率可选 · 暂停可开关", 15,
                Color.rgb(190, 204, 232), Typeface.NORMAL);
        LinearLayout.LayoutParams subtitleLp = wrap();
        subtitleLp.topMargin = dp(6);
        header.addView(subtitle, subtitleLp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.VERTICAL);
        statusRow.setPadding(dp(16), dp(12), dp(16), dp(12));
        statusRow.setBackground(roundRect(Color.rgb(31, 51, 87), 14));
        LinearLayout.LayoutParams statusLp = matchWrap();
        statusLp.topMargin = dp(20);
        header.addView(statusRow, statusLp);
        statusText = text("●  准备就绪", 16, Color.rgb(124, 236, 180), Typeface.BOLD);
        statusHint = text("设置参数后即可开始录制", 13, Color.rgb(197, 209, 232), Typeface.NORMAL);
        statusRow.addView(statusText);
        LinearLayout.LayoutParams hintLp = wrap();
        hintLp.topMargin = dp(3);
        statusRow.addView(statusHint, hintLp);
        page.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(22), dp(22), dp(36));

        LinearLayout card = new LinearLayout(this);
        settingsCard = card;
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(22));
        card.setBackground(roundRect(Color.WHITE, 18));
        card.setElevation(dp(2));
        card.addView(text("录制设置", 21, TEXT, Typeface.BOLD));
        TextView helper = text("参数越高，画面越清晰，文件也会越大", 13,
                Color.rgb(102, 116, 141), Typeface.NORMAL);
        LinearLayout.LayoutParams helperLp = wrap();
        helperLp.topMargin = dp(4);
        helperLp.bottomMargin = dp(14);
        card.addView(helper, helperLp);

        resolutionSpinner = makeSpinner(new String[]{"跟随屏幕", "高清（长边1920）", "标准（长边1280）", "省空间（长边960）"});
        fpsSpinner = makeSpinner(new String[]{"15 FPS", "24 FPS", "30 FPS", "60 FPS"});
        bitrateSpinner = makeSpinner(new String[]{"2 Mbps", "4 Mbps", "6 Mbps", "8 Mbps", "12 Mbps"});
        audioSpinner = makeSpinner(new String[]{"无声音", "仅麦克风", "仅设备声音", "麦克风 + 设备声音"});
        card.addView(settingRow("画面尺寸", "保持屏幕比例，不拉伸", resolutionSpinner));
        card.addView(divider());
        card.addView(settingRow("画面帧率", "课件推荐 30 FPS", fpsSpinner));
        card.addView(divider());
        card.addView(settingRow("视频码率", "高清推荐 6 Mbps", bitrateSpinner));
        card.addView(divider());
        card.addView(settingRow("声音来源", "设备声音受应用保护策略限制", audioSpinner));
        card.addView(divider());

        pauseSwitch = new Switch(this);
        pauseSwitch.setChecked(true);
        pauseSwitch.setShowText(false);
        card.addView(settingRow("允许暂停", "开启后显示暂停/继续按钮", pauseSwitch));
        card.addView(divider());
        overlaySpinner = makeSpinner(new String[]{"右上角", "左上角", "关闭悬浮条"});
        card.addView(settingRow("悬浮控制条", "默认收成右上角圆点，点开可暂停或结束", overlaySpinner));
        overlayPermissionButton = new Button(this);
        overlayPermissionButton.setAllCaps(false);
        overlayPermissionButton.setTextSize(14);
        overlayPermissionButton.setOnClickListener(v -> requestOverlayPermission());
        card.addView(overlayPermissionButton, matchWrap());
        content.addView(card, matchWrap());

        LinearLayout fileCard = new LinearLayout(this);
        courseCard = fileCard;
        fileCard.setOrientation(LinearLayout.VERTICAL);
        fileCard.setPadding(dp(22), dp(19), dp(22), dp(20));
        fileCard.setBackground(roundRect(Color.WHITE, 18));
        fileCard.setElevation(dp(2));
        LinearLayout.LayoutParams fileLp = matchWrap();
        fileLp.topMargin = dp(16);
        content.addView(fileCard, fileLp);
        fileCard.addView(text("课程名称", 17, TEXT, Typeface.BOLD));
        nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setTextSize(16);
        nameInput.setTextColor(TEXT);
        nameInput.setHintTextColor(Color.rgb(145, 154, 170));
        nameInput.setHint("例如：第一章 课程导入");
        nameInput.setPadding(dp(14), 0, dp(14), 0);
        nameInput.setBackground(roundRect(Color.rgb(241, 244, 249), 10));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        inputLp.topMargin = dp(10);
        fileCard.addView(nameInput, inputLp);

        primaryButton = new Button(this);
        primaryButton.setText("开始录制");
        primaryButton.setTextSize(18);
        primaryButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        primaryButton.setTextColor(Color.WHITE);
        primaryButton.setAllCaps(false);
        primaryButton.setBackground(roundRect(BLUE, 14));
        primaryButton.setOnClickListener(v -> onPrimaryAction());
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        buttonLp.topMargin = dp(20);
        content.addView(primaryButton, buttonLp);
        pauseButton = new Button(this);
        pauseButton.setAllCaps(false);
        pauseButton.setText("暂停录制");
        pauseButton.setTextSize(17);
        pauseButton.setTextColor(BLUE);
        pauseButton.setBackground(roundRect(Color.rgb(225, 235, 255), 14));
        pauseButton.setVisibility(View.GONE);
        pauseButton.setOnClickListener(v -> startService(new Intent(this, RecordingService.class)
                .setAction(RecordingService.ACTION_TOGGLE_PAUSE)));
        LinearLayout.LayoutParams pauseLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        pauseLp.topMargin = dp(12);
        content.addView(pauseButton, pauseLp);

        Button libraryButton = new Button(this);
        libraryButton.setText("我的课程视频 · 播放 / 导出");
        libraryButton.setAllCaps(false);
        libraryButton.setTextColor(TEXT);
        libraryButton.setBackground(roundRect(Color.WHITE, 14));
        libraryButton.setOnClickListener(v -> startActivity(new Intent(this, RecordingLibraryActivity.class)));
        LinearLayout.LayoutParams libraryLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        libraryLp.topMargin = dp(16);
        content.addView(libraryButton, libraryLp);

        TextView footnote = text("授权后有 5 秒切换到笔记。卓易通内的视频可从“我的课程视频”导出。录制时请勿锁屏。",
                13, Color.rgb(105, 116, 135), Typeface.NORMAL);
        footnote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footLp = matchWrap();
        footLp.topMargin = dp(13);
        content.addView(footnote, footLp);

        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);
    }

    private View settingRow(String title, String description, View control) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, TEXT, Typeface.BOLD));
        TextView sub = text(description, 12, Color.rgb(112, 124, 145), Typeface.NORMAL);
        LinearLayout.LayoutParams subLp = wrap();
        subLp.topMargin = dp(3);
        labels.addView(sub, subLp);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams controlLp = new LinearLayout.LayoutParams(dp(getResources().getConfiguration().screenWidthDp < 600 ? 150 : 220), ViewGroup.LayoutParams.WRAP_CONTENT);
        controlLp.leftMargin = dp(12);
        row.addView(control, controlLp);
        return row;
    }

    private Spinner makeSpinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(TEXT);
                view.setTextSize(15);
                view.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private void onPrimaryAction() {
        if (RecordingService.isRunning()) {
            Intent stop = new Intent(this, RecordingService.class).setAction(RecordingService.ACTION_STOP);
            startService(stop);
            return;
        }
        if (pendingStart) return;
        pendingStart = true;
        saveSettings();
        if (overlaySpinner.getSelectedItemPosition() != 2 && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this).setTitle("开启悬浮控制条")
                    .setMessage("需要允许课堂录屏显示在其他应用上层。授权后可直接在笔记画面暂停、继续或结束；卓易通能否覆盖鸿蒙原生应用仍取决于系统支持。")
                    .setPositiveButton("去授权", (dialog, which) -> requestOverlayPermission())
                    .setNegativeButton("本次不用悬浮条", (dialog, which) -> prepareAudioAndCapture())
                    .setNeutralButton("取消", (dialog, which) -> pendingStart = false)
                    .setOnCancelListener(dialog -> pendingStart = false).show();
            return;
        }
        prepareAudioAndCapture();
    }

    private void prepareAudioAndCapture() {
        int audioMode = audioSpinner.getSelectedItemPosition();
        if (audioMode != 0 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        } else requestScreenCapture();
    }

    private void requestOverlayPermission() {
        try {
            overlayPermissionLauncher.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException error) {
            pendingStart = false;
            Toast.makeText(this, "卓易通没有可用的悬浮权限设置入口，请查看系统应用权限；也可关闭悬浮条继续录制", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshOverlayPermission() {
        if (overlayPermissionButton != null) overlayPermissionButton.setText(Settings.canDrawOverlays(this)
                ? "悬浮权限已开启 · 查看权限设置" : "开启悬浮权限（显示在其他应用上层）");
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        captureLauncher.launch(manager.createScreenCaptureIntent());
    }

    private void onCaptureResult(ActivityResult result) {
        pendingStart = false;
        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
            Toast.makeText(this, "未获得屏幕录制授权", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent service = new Intent(this, RecordingService.class);
        service.setAction(RecordingService.ACTION_START);
        service.putExtra(RecordingService.EXTRA_RESULT_CODE, result.getResultCode());
        service.putExtra(RecordingService.EXTRA_RESULT_DATA, result.getData());
        service.putExtra(RecordingService.EXTRA_RESOLUTION, resolutionSpinner.getSelectedItemPosition());
        service.putExtra(RecordingService.EXTRA_FPS, parseLeadingInt((String) fpsSpinner.getSelectedItem()));
        service.putExtra(RecordingService.EXTRA_BITRATE,
                parseLeadingInt((String) bitrateSpinner.getSelectedItem()) * 1_000_000);
        service.putExtra(RecordingService.EXTRA_AUDIO_MODE, audioSpinner.getSelectedItemPosition());
        service.putExtra(RecordingService.EXTRA_PAUSE_ENABLED, pauseSwitch.isChecked());
        service.putExtra(RecordingService.EXTRA_OVERLAY_POSITION, overlaySpinner.getSelectedItemPosition());
        String title = nameInput.getText().toString().trim();
        if (title.isEmpty()) title = "课程_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
        service.putExtra(RecordingService.EXTRA_TITLE, title);
        startForegroundService(service);
        updateState("starting", "5 秒后开始，请切换到华为笔记");
    }

    private int parseLeadingInt(String text) {
        String digits = text.replaceAll("[^0-9]", "");
        return Integer.parseInt(digits);
    }

    private void updateState(String state, String message) {
        primaryButton.setEnabled(true);
        pauseButton.setVisibility(View.GONE);
        if (state == null) state = RecordingService.isRunning() ? "recording" : "idle";
        switch (state) {
            case "starting":
                statusText.setText("◷  准备录制");
                statusHint.setText(message);
                primaryButton.setText("取消录制");
                setControlsEnabled(false);
                break;
            case "saving":
                statusText.setText("…  正在保存");
                statusHint.setText(message);
                primaryButton.setText("正在保存视频…");
                primaryButton.setEnabled(false);
                setControlsEnabled(false);
                break;
            case "recording":
                statusText.setText("●  正在录制");
                statusText.setTextColor(Color.rgb(255, 111, 111));
                statusHint.setText(message == null ? "可从通知栏暂停或停止" : message);
                primaryButton.setText("停止并保存");
                primaryButton.setBackground(roundRect(Color.rgb(224, 72, 72), 14));
                setControlsEnabled(false);
                if (RecordingService.allowsPause()) {
                    pauseButton.setVisibility(View.VISIBLE);
                    pauseButton.setText("暂停录制");
                }
                break;
            case "paused":
                statusText.setText("Ⅱ  已暂停");
                statusText.setTextColor(Color.rgb(255, 202, 87));
                statusHint.setText(message == null ? "从通知栏继续录制" : message);
                primaryButton.setText("停止并保存");
                primaryButton.setBackground(roundRect(Color.rgb(224, 72, 72), 14));
                setControlsEnabled(false);
                if (RecordingService.allowsPause()) {
                    pauseButton.setVisibility(View.VISIBLE);
                    pauseButton.setText("继续录制");
                }
                break;
            case "error":
                statusText.setText("!  录制失败");
                statusText.setTextColor(Color.rgb(255, 174, 91));
                statusHint.setText(message == null ? "请检查权限和存储空间" : message);
                resetIdleButton();
                break;
            case "saved":
                statusText.setText("✓  已保存");
                statusText.setTextColor(Color.rgb(124, 236, 180));
                statusHint.setText(message == null ? "视频已写入课程文件夹" : message);
                resetIdleButton();
                break;
            default:
                statusText.setText("●  准备就绪");
                statusText.setTextColor(Color.rgb(124, 236, 180));
                statusHint.setText(message == null ? "设置参数后即可开始录制" : message);
                resetIdleButton();
        }
    }

    private void resetIdleButton() {
        primaryButton.setText("开始录制");
        primaryButton.setBackground(roundRect(BLUE, 14));
        setControlsEnabled(true);
    }

    private void setControlsEnabled(boolean enabled) {
        settingsCard.setVisibility(enabled ? View.VISIBLE : View.GONE);
        courseCard.setVisibility(enabled ? View.VISIBLE : View.GONE);
        resolutionSpinner.setEnabled(enabled);
        fpsSpinner.setEnabled(enabled);
        bitrateSpinner.setEnabled(enabled);
        audioSpinner.setEnabled(enabled);
        overlaySpinner.setEnabled(enabled);
        overlayPermissionButton.setEnabled(enabled);
        pauseSwitch.setEnabled(enabled);
        nameInput.setEnabled(enabled);
    }

    private void refreshFromService() {
        updateState(RecordingService.state(), RecordingService.message());
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void saveSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("resolution", resolutionSpinner.getSelectedItemPosition())
                .putInt("fps", fpsSpinner.getSelectedItemPosition())
                .putInt("bitrate", bitrateSpinner.getSelectedItemPosition())
                .putInt("audio", audioSpinner.getSelectedItemPosition())
                .putBoolean("pause", pauseSwitch.isChecked())
                .putInt("overlay_position", overlaySpinner.getSelectedItemPosition())
                .apply();
    }

    private void restoreSettings() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        resolutionSpinner.setSelection(p.getInt("resolution", 1));
        fpsSpinner.setSelection(p.getInt("fps", 2));
        bitrateSpinner.setSelection(p.getInt("bitrate", 2));
        audioSpinner.setSelection(p.getInt("audio", 1));
        pauseSwitch.setChecked(p.getBoolean("pause", true));
        overlaySpinner.setSelection(p.getInt("overlay_position", 0));
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(Color.rgb(232, 236, 243));
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return view;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
