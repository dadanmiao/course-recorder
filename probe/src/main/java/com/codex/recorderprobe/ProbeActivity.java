package com.codex.recorderprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class ProbeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button testButton, playbackButton, shareButton;
    private String environment;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(ProbeService.status);
            testButton.setEnabled(!ProbeService.running);
            playbackButton.setEnabled(ProbeService.savedUri != null && !ProbeService.running);
            shareButton.setEnabled(ProbeService.savedUri != null && !ProbeService.running);
            handler.postDelayed(this, 700);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        environment = readEnvironment();
        if (ProbeService.savedUri == null) {
            String saved = getSharedPreferences("probe", MODE_PRIVATE).getString("saved_uri", null);
            if (saved != null) {
                ProbeService.savedUri = Uri.parse(saved);
                if (!ProbeService.running) ProbeService.status = "可播放上次的测试视频";
            }
        }
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(244, 247, 252));
        page.setPadding(dp(24), dp(20), dp(24), dp(30));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(dp(24) + bars.left, dp(20) + bars.top,
                        dp(24) + bars.right, dp(30) + bars.bottom);
            }
            return insets;
        });
        page.addView(label("录课兼容检测", 27, true));
        page.addView(label("读取卓易通实际版本，验证能否录到你的课件", 14, false));
        TextView report = label(environment, 15, false);
        report.setTextIsSelectable(true);
        report.setPadding(dp(16), dp(16), dp(16), dp(16));
        report.setBackground(round(Color.WHITE));
        LinearLayout.LayoutParams reportLp = new LinearLayout.LayoutParams(-1, -2);
        reportLp.topMargin = dp(20);
        page.addView(report, reportLp);
        page.addView(label("测试方法", 19, true));
        page.addView(label("1. 点击下方按钮并允许屏幕捕获；如有选项，请选整个屏幕。\n"
                + "2. 倒计时 5 秒内打开你准备录课的应用。\n"
                + "3. 翻页或写字，等待 20 秒测试自动结束。\n"
                + "4. 回到这里播放测试视频，确认内容是否完整。\n\n"
                + "本次测试不录声音。若只录到卓易通内的画面，不能据此认定能够录制鸿蒙原生应用。",
                15, false));
        status = label(ProbeService.status, 15, true);
        page.addView(status);
        testButton = button("开始 20 秒录屏测试", () -> confirmTest());
        page.addView(testButton);
        playbackButton = button("播放测试视频", this::playVideo);
        page.addView(playbackButton);
        shareButton = button("导出 / 分享测试视频", this::shareVideo);
        page.addView(shareButton);
        page.addView(button("复制检测信息", () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("录课兼容检测", environment + "\n\n" + ProbeService.status));
            Toast.makeText(this, "已复制；可粘贴回对话", Toast.LENGTH_SHORT).show();
        }));
        page.addView(label("无需账号，不联网。只有点击测试并授权后才录屏。"
                + "\n视频通过系统媒体库保存；卓易通内的文件可能需要使用分享功能导出。",
                13, false));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refresh.run();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void confirmTest() {
        if (ProbeService.running) return;
        new AlertDialog.Builder(this).setTitle("屏幕捕获测试")
                .setMessage("你授权后，应用会在 5 秒倒计时结束后录制 20 秒画面，并保存一个本地 MP4。"
                        + "请打开你准备用来授课的应用，以验证跨应用捕获能力。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> {
                    try {
                        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                        startActivityForResult(manager.createScreenCaptureIntent(), 1);
                    } catch (Throwable error) {
                        ProbeService.status = "屏幕授权接口不可用：" + error.getClass().getSimpleName()
                                + "\n" + error.getMessage();
                    }
                }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 1) return;
        if (resultCode != RESULT_OK || data == null) {
            ProbeService.status = "未获得屏幕捕获授权（取消、拒绝或环境不支持）";
            return;
        }
        try {
            Intent service = new Intent(this, ProbeService.class)
                    .putExtra("code", resultCode).putExtra("data", data);
            startForegroundService(service);
        } catch (Throwable error) {
            ProbeService.status = "不能启动录屏服务：" + error.getClass().getSimpleName()
                    + "\n" + error.getMessage();
        }
    }

    private void playVideo() {
        if (ProbeService.savedUri == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(ProbeService.savedUri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "没有可用播放器，请分享视频到鸿蒙图库或其他设备", Toast.LENGTH_LONG).show();
        }
    }

    private void shareVideo() {
        if (ProbeService.savedUri == null) return;
        Intent share = new Intent(Intent.ACTION_SEND).setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, ProbeService.savedUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newUri(getContentResolver(), "测试视频", ProbeService.savedUri));
        try { startActivity(Intent.createChooser(share, "导出测试视频")); }
        catch (ActivityNotFoundException error) {
            Toast.makeText(this, "当前环境没有可用的分享应用", Toast.LENGTH_LONG).show();
        }
    }

    private String readEnvironment() {
        StringBuilder report = new StringBuilder();
        report.append("设备：").append(Build.MANUFACTURER).append(" ").append(Build.MODEL);
        report.append("\nAndroid 版本：").append(Build.VERSION.RELEASE);
        report.append("\nAndroid API：").append(Build.VERSION.SDK_INT);
        report.append("\n处理器架构：").append(String.join(", ", Build.SUPPORTED_ABIS));
        report.append("\n屏幕捕获服务：").append(getSystemService(MEDIA_PROJECTION_SERVICE) == null ? "不可用" : "接口存在（需录屏实测）");
        report.append("\nH.264 编码器：");
        try {
            int found = 0;
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String mime : info.getSupportedTypes()) {
                    if (mime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                        report.append("\n  ").append(info.getName());
                        found++;
                    }
                }
            }
            if (found == 0) report.append("无");
        } catch (Throwable error) { report.append("读取失败：").append(error.getClass().getSimpleName()); }
        report.append("\n\n说明：版本信息来自当前运行环境，并非网上推测。"
                + "\n捕获权限、画面是否黑屏以及原生应用是否可录，以短视频实测为准。");
        return report.toString();
    }

    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(20, 33, 61));
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setPadding(0, dp(12), 0, dp(8));
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private Button button(String text, Runnable action) {
        Button view = new Button(this);
        view.setText(text);
        view.setTextSize(16);
        view.setTextColor(Color.WHITE);
        view.setBackground(round(Color.rgb(36, 107, 253)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.topMargin = dp(12);
        view.setLayoutParams(lp);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private GradientDrawable round(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
