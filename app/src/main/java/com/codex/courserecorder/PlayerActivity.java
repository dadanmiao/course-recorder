package com.codex.courserecorder;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.VideoView;
import android.widget.Toast;

public class PlayerActivity extends Activity {
    private VideoView video;
    private int position;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(android.graphics.Color.BLACK);
        container.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        video = new VideoView(this);
        container.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        setContentView(container);
        MediaController controls = new MediaController(this);
        controls.setAnchorView(video); video.setMediaController(controls);
        if (state != null) position = state.getInt("position");
        video.setOnPreparedListener(player -> { video.seekTo(position); video.start(); });
        video.setOnErrorListener((player, what, extra) -> {
            Toast.makeText(this, "当前环境无法播放此视频，请导出后播放", Toast.LENGTH_LONG).show(); return true;
        });
        video.setVideoURI(getIntent().getData());
    }
    @Override protected void onPause() { super.onPause(); position = video.getCurrentPosition(); video.pause(); }
    @Override protected void onResume() { super.onResume(); if (video != null) video.start(); }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putInt("position", video.getCurrentPosition()); }
    @Override protected void onDestroy() { video.stopPlayback(); super.onDestroy(); }
}
