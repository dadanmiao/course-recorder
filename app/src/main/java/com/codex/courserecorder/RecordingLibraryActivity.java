package com.codex.courserecorder;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Own MediaStore videos only. SAF export works without broad storage permissions. */
public class RecordingLibraryActivity extends ComponentActivity {
    private LinearLayout list;
    private Uri exportSource;
    private boolean copying;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String> export = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("video/mp4"), destination -> {
                Uri source = exportSource;
                if (destination == null || source == null) return;
                copying = true;
                Toast.makeText(this, "正在导出，请勿关闭应用", Toast.LENGTH_LONG).show();
                io.execute(() -> {
                    String result;
                    try (InputStream input = getContentResolver().openInputStream(source);
                         OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                        if (input == null || output == null) throw new java.io.IOException("无法打开保存位置");
                        byte[] buffer = new byte[128 * 1024];
                        int length;
                        while ((length = input.read(buffer)) >= 0) output.write(buffer, 0, length);
                        output.flush();
                        result = "导出完成，原视频仍保留";
                    } catch (Exception error) {
                        result = "导出失败：" + error.getMessage() + "；目标位置可能留有不完整副本，原视频不受影响";
                    }
                    String message = result;
                    runOnUiThread(() -> { copying = false; Toast.makeText(this, message, Toast.LENGTH_LONG).show(); });
                });
            });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null && state.containsKey("exportSource")) exportSource = Uri.parse(state.getString("exportSource"));
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(28, 28, 28, 28);
        list.setBackgroundColor(Color.rgb(244, 247, 252));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addView(list);
        setContentView(scroll);
    }

    @Override public void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        list.removeAllViews();
        Button back = new Button(this);
        back.setText("返回录制设置");
        back.setOnClickListener(v -> finish());
        list.addView(back);
        label("我的课程视频", 26);
        label("卓易通内的视频可选择导出位置或分享；若只显示容器目录，可用分享发送到可访问的应用。", 15);
        String[] columns = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION};
        int count = 0;
        try (Cursor cursor = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                columns, MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Video.Media.IS_PENDING + " = 0",
                new String[]{"Movies/课堂录屏%"}, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) while (cursor.moveToNext()) {
                count++;
                Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
                String name = cursor.getString(1);
                long seconds = cursor.getLong(3) / 1000;
                label(name, 18);
                label(String.format(Locale.CHINA, "%d 分 %02d 秒 · %.1f MB", seconds / 60, seconds % 60,
                        cursor.getLong(2) / 1048576f), 14);
                LinearLayout actions = new LinearLayout(this);
                addAction(actions, "播放", () -> startActivity(new Intent(this, PlayerActivity.class).setData(uri)));
                addAction(actions, "导出", () -> {
                    if (copying) { Toast.makeText(this, "上一段视频尚在导出", Toast.LENGTH_SHORT).show(); return; }
                    exportSource = uri;
                    export.launch(name);
                });
                addAction(actions, "分享", () -> {
                    Intent share = new Intent(Intent.ACTION_SEND).setType("video/mp4")
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    share.setClipData(android.content.ClipData.newRawUri("课程视频", uri));
                    try { startActivity(Intent.createChooser(share, "分享课程视频")); }
                    catch (Exception error) { Toast.makeText(this, "没有可接收视频的应用，请使用导出", Toast.LENGTH_LONG).show(); }
                });
                list.addView(actions);
            }
        } catch (Exception error) { label("读取失败：" + error.getMessage(), 16); }
        if (count == 0) label("还没有课程视频。录制并保存后会出现在这里。", 17);
    }

    private void label(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setTextColor(Color.rgb(27, 38, 59));
        text.setPadding(8, 18, 8, 10); list.addView(text);
    }
    private void addAction(LinearLayout row, String title, Runnable action) {
        Button button = new Button(this); button.setText(title);
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (exportSource != null) out.putString("exportSource", exportSource.toString());
    }
    @Override protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        if (state.containsKey("exportSource")) exportSource = Uri.parse(state.getString("exportSource"));
    }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
}
