package com.codex.courserecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecordingService extends Service implements ScreenRecorderEngine.Listener {
    public static final String ACTION_START = "com.codex.courserecorder.START";
    public static final String ACTION_STOP = "com.codex.courserecorder.STOP";
    public static final String ACTION_TOGGLE_PAUSE = "com.codex.courserecorder.TOGGLE_PAUSE";
    public static final String ACTION_STATE = "com.codex.courserecorder.STATE";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_RESOLUTION = "resolution";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_BITRATE = "bitrate";
    public static final String EXTRA_AUDIO_MODE = "audio_mode";
    public static final String EXTRA_PAUSE_ENABLED = "pause_enabled";
    public static final String EXTRA_OVERLAY_POSITION = "overlay_position";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";

    private static final int NOTIFICATION_ID = 701;
    private static final String CHANNEL_ID = "screen_recording";
    private static volatile boolean running;
    private static volatile boolean paused;
    private static volatile boolean pauseEnabled;
    private static volatile String currentState = "idle", currentMessage = "设置参数后即可开始录制";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile ScreenRecorderEngine engine;
    private boolean stopping;
    private boolean failed;
    private Runnable countdown;
    private FloatingRecordingControls floatingControls;

    public static boolean isRunning() { return running; }
    public static boolean isPaused() { return paused; }
    public static boolean allowsPause() { return pauseEnabled; }
    public static String state() { return currentState; }
    public static String message() { return currentMessage; }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        switch (intent.getAction()) {
            case ACTION_START:
                if (!running) {
                    try { beginRecording(intent); }
                    catch (Throwable error) { onError("无法取得系统录屏权限：" + friendlyMessage(error)); }
                }
                break;
            case ACTION_TOGGLE_PAUSE:
                togglePause();
                break;
            case ACTION_STOP:
                stopAndSave();
                break;
        }
        return START_NOT_STICKY;
    }

    private void beginRecording(Intent intent) {
        int audioMode = intent.getIntExtra(EXTRA_AUDIO_MODE, 1);
        pauseEnabled = intent.getBooleanExtra(EXTRA_PAUSE_ENABLED, true);
        Notification starting = buildNotification("正在准备录制…", false);
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (Build.VERSION.SDK_INT >= 30 && (audioMode == 1 || audioMode == 3))
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        startForeground(NOTIFICATION_ID, starting, types);

        running = true;
        paused = false;
        failed = false;
        stopping = false;
        int corner = intent.getIntExtra(EXTRA_OVERLAY_POSITION, 0);
        if (corner != 2) {
            floatingControls = new FloatingRecordingControls(this, pauseEnabled, corner,
                    new FloatingRecordingControls.Actions() {
                        @Override public void pause() { togglePause(); }
                        @Override public void stop() { stopAndSave(); }
                        @Override public void unavailable() { overlayUnavailable(); }
                    });
            if (!floatingControls.show()) overlayUnavailable();
        }

        Intent permissionData;
        if (Build.VERSION.SDK_INT >= 33) {
            permissionData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            permissionData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        ScreenRecorderEngine.Config config = new ScreenRecorderEngine.Config(
                intent.getIntExtra(EXTRA_RESULT_CODE, 0),
                permissionData,
                intent.getIntExtra(EXTRA_RESOLUTION, 1),
                intent.getIntExtra(EXTRA_FPS, 30),
                intent.getIntExtra(EXTRA_BITRATE, 6_000_000),
                audioMode,
                intent.getStringExtra(EXTRA_TITLE));

        countdown = new Runnable() {
            private int remaining = 5;
            @Override public void run() {
                if (!running || stopping || failed) return;
                if (remaining > 0) {
                    String message = remaining-- + " 秒后开始，请切换到华为笔记";
                    sendState("starting", message);
                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(
                            NOTIFICATION_ID, buildNotification(message, false));
                    mainHandler.postDelayed(this, 1000);
                    return;
                }
                countdown = null;
                sendState("starting", "正在准备编码器…");
                startEngine(config);
            }
        };
        countdown.run();
    }

    private void startEngine(ScreenRecorderEngine.Config config) {
        worker.execute(() -> {
            try {
                engine = new ScreenRecorderEngine(this, config, this);
                engine.start();
                mainHandler.post(() -> {
                    if (stopping || failed) return;
                    updateNotification();
                    sendState("recording", engine.description());
                    scheduleClock();
                });
            } catch (Throwable error) {
                onError("无法开始录制：" + friendlyMessage(error));
            }
        });
    }

    private void togglePause() {
        if (!running || !pauseEnabled || engine == null || stopping || failed || "starting".equals(currentState)) return;
        paused = !paused;
        engine.setPaused(paused);
        updateNotification();
        sendState(paused ? "paused" : "recording",
                paused ? "暂停时间不会计入视频；点击继续恢复" : engine.description());
    }

    private void stopAndSave() {
        if (!running || stopping) return;
        stopping = true;
        paused = false;
        if (countdown != null) {
            mainHandler.removeCallbacks(countdown);
            countdown = null;
            running = false;
            stopping = false;
            sendState("idle", "已取消本次录制");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        sendState("saving", "正在保存视频，请稍候…");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification("正在保存视频…", true));
        worker.execute(() -> {
            try {
                if (engine != null) engine.stop();
                else throw new IllegalStateException("录制尚未开始");
            } catch (Throwable error) {
                onError("保存失败：" + friendlyMessage(error));
            }
        });
    }

    private void scheduleClock() {
        mainHandler.postDelayed(() -> {
            if (running && !stopping && !failed) {
                updateNotification();
                sendState(paused ? "paused" : "recording", clockLabel() + " · " + engine.description());
                scheduleClock();
            }
        }, 1000);
    }

    private void updateNotification() {
        if (!running) return;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, buildNotification(clockLabel(), false));
    }

    private String clockLabel() {
        long seconds = engine == null ? 0 : engine.durationUs() / 1_000_000;
        String time = String.format(Locale.CHINA, "%02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
        return paused ? "已暂停 · " + time : "正在录制 · " + time;
    }

    private Notification buildNotification(String content, boolean saving) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 10, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        builder.setSmallIcon(R.drawable.ic_record_notification)
                .setContentTitle(paused ? "课堂录屏已暂停" : "课堂录屏")
                .setContentText(content)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setColor(Color.rgb(36, 107, 253))
                .setShowWhen(false);

        if (!saving && pauseEnabled && engine != null && !"starting".equals(currentState)) {
            Intent pauseIntent = new Intent(this, RecordingService.class).setAction(ACTION_TOGGLE_PAUSE);
            PendingIntent pauseAction = PendingIntent.getService(this, 11, pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(0, paused ? "继续" : "暂停", pauseAction);
        }
        if (!saving) {
            Intent stopIntent = new Intent(this, RecordingService.class).setAction(ACTION_STOP);
            PendingIntent stopAction = PendingIntent.getService(this, 12, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(0, "停止并保存", stopAction);
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "屏幕录制",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示录制状态以及暂停、停止按钮");
        channel.setSound(null, null);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private void sendState(String state, String message) {
        currentState = state;
        currentMessage = message;
        if (floatingControls != null) {
            if ("idle".equals(state) || "saved".equals(state) || "error".equals(state)) {
                floatingControls.hide(); floatingControls = null;
            } else floatingControls.update(state, engine == null ? 0 : engine.durationUs());
        }
        Intent broadcast = new Intent(ACTION_STATE).setPackage(getPackageName());
        broadcast.putExtra(EXTRA_STATE, state);
        broadcast.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(broadcast);
    }

    @Override public void onSaved(String displayName) {
        mainHandler.post(() -> {
            if (failed) return;
            running = false;
            paused = false;
            stopping = false;
            sendState("saved", "已保存到 Movies/课堂录屏/" + displayName);
            Toast.makeText(this, "课程视频已保存", Toast.LENGTH_LONG).show();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    @Override public void onError(String message) {
        mainHandler.post(() -> {
            if (failed) return;
            failed = true;
            stopping = true;
            if (countdown != null) mainHandler.removeCallbacks(countdown);
            worker.execute(() -> {
                try { if (engine != null) engine.abort(); } catch (Throwable ignored) { }
                mainHandler.post(() -> finishError(message));
            });
        });
    }

    private void finishError(String message) {
            running = false;
            paused = false;
            stopping = false;
            sendState("error", message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
    }

    @Override public void onProjectionEnded() { mainHandler.post(this::stopAndSave); }

    private void overlayUnavailable() {
        if (floatingControls != null) { floatingControls.hide(); floatingControls = null; }
        Toast.makeText(this, "系统未允许悬浮条显示，可从应用或通知控制录制", Toast.LENGTH_LONG).show();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (floatingControls != null) floatingControls.onConfigurationChanged(configuration);
    }

    private String friendlyMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    @Override public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (floatingControls != null) { floatingControls.hide(); floatingControls = null; }
        if (running) worker.execute(() -> { if (engine != null) engine.abort(); });
        running = false;
        paused = false;
        worker.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
