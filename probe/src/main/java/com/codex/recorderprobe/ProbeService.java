package com.codex.recorderprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.WindowManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ProbeService extends Service {
    static volatile String status = "等待测试";
    static volatile boolean running;
    static volatile Uri savedUri;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private ParcelFileDescriptor descriptor;
    private Uri pendingUri;
    private File legacyFile;
    private boolean started, finishing;
    private String filename;

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("stop".equals(intent.getAction())) { finishTest(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("test", "兼容性录屏测试",
                    NotificationManager.IMPORTANCE_LOW));
            if (Build.VERSION.SDK_INT >= 29) startForeground(901, notification("5 秒后开始，请打开课件"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(901, notification("5 秒后开始，请打开课件"));
            running = true;
            savedUri = null;
            Intent data = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("data", Intent.class)
                    : intent.getParcelableExtra("data");
            MediaProjectionManager projections = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = projections.getMediaProjection(intent.getIntExtra("code", 0), data);
            if (projection == null) throw new IllegalStateException("授权令牌不可用");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (!finishing) finishTest();
                }
            }, handler);
            status = "授权成功。5 秒后录制 20 秒，请切换到你的课件应用。";
            handler.postDelayed(this::startTest, 5000);
        } catch (Throwable error) { fail(error); }
        return START_NOT_STICKY;
    }

    private void startTest() {
        if (finishing) return;
        try {
            filename = "录课兼容测试_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".mp4";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/课堂录屏测试");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                pendingUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (pendingUri == null) throw new IllegalStateException("无法创建测试视频");
                descriptor = getContentResolver().openFileDescriptor(pendingUri, "rw");
                if (descriptor == null) throw new IllegalStateException("无法写入测试视频");
            } else {
                legacyFile = new File(getExternalFilesDir("tests"), filename);
            }
            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            if (descriptor != null) recorder.setOutputFile(descriptor.getFileDescriptor());
            else recorder.setOutputFile(legacyFile.getAbsolutePath());
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            Point size = new Point();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
            float scale = Math.min(1f, 960f / Math.max(size.x, size.y));
            int width = Math.max(16, (int) (size.x * scale) / 16 * 16);
            int height = Math.max(16, (int) (size.y * scale) / 16 * 16);
            recorder.setVideoSize(width, height);
            recorder.setVideoFrameRate(15);
            recorder.setVideoEncodingBitRate(2_000_000);
            recorder.prepare();
            virtualDisplay = projection.createVirtualDisplay("recorder-compatibility-test",
                    width, height, getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, null);
            recorder.start();
            started = true;
            status = "测试正在录屏：20 秒后自动停止（无声音）。请操作课件。";
            getSystemService(NotificationManager.class).notify(901, notification("正在测试录屏，20 秒后自动保存"));
            handler.postDelayed(this::finishTest, 20_000);
        } catch (Throwable error) { fail(error); }
    }

    private void finishTest() {
        if (finishing) return;
        finishing = true;
        handler.removeCallbacksAndMessages(null);
        try {
            if (!started) {
                status = "测试在开始录制前结束";
                return;
            }
            recorder.stop();
            started = false;
            if (pendingUri != null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(pendingUri, values, null, null);
                savedUri = pendingUri;
                pendingUri = null;
                getSharedPreferences("probe", MODE_PRIVATE).edit()
                        .putString("saved_uri", savedUri.toString()).apply();
                status = "测试视频已保存：" + filename
                        + "\n请播放检查：是否能看到课件，切换到鸿蒙原生应用后是否仍有画面。"
                        + "\n文件存在并不代表跨环境录屏已经通过。";
            } else {
                status = "测试视频已保存到应用测试目录：" + filename;
            }
        } catch (Throwable error) {
            status = "测试保存失败：" + error.getClass().getSimpleName() + "\n" + error.getMessage();
        } finally {
            cleanup();
            stopSelf();
        }
    }

    private void fail(Throwable error) {
        status = "录屏测试失败：" + error.getClass().getSimpleName() + "\n" + error.getMessage()
                + "\n请复制此信息；这可能是当前环境接口或编码兼容性问题。";
        finishing = true;
        handler.removeCallbacksAndMessages(null);
        cleanup();
        stopSelf();
    }

    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, ProbeActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, ProbeService.class).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "test").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("录课兼容检测").setContentText(message).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).addAction(0, "停止测试", stop).build();
    }

    private void cleanup() {
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (recorder != null) { try { recorder.release(); } catch (Throwable ignored) { } recorder = null; }
        if (projection != null) { projection.stop(); projection = null; }
        if (descriptor != null) { try { descriptor.close(); } catch (Throwable ignored) { } descriptor = null; }
        if (pendingUri != null) { try { getContentResolver().delete(pendingUri, null, null); } catch (Throwable ignored) { } pendingUri = null; }
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override public void onDestroy() {
        finishing = true;
        handler.removeCallbacksAndMessages(null);
        cleanup();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
