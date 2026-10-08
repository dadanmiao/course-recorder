package com.codex.courserecorder;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Range;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class ScreenRecorderEngine {
    interface Listener {
        void onSaved(String displayName);
        void onError(String message);
        void onProjectionEnded();
    }

    static final class Config {
        final int resultCode;
        final Intent resultData;
        final int resolutionIndex;
        final int fps;
        final int bitrate;
        final int audioMode; // 0 none, 1 mic, 2 device, 3 mix
        final String title;

        Config(int resultCode, Intent resultData, int resolutionIndex, int fps,
               int bitrate, int audioMode, String title) {
            this.resultCode = resultCode;
            this.resultData = resultData;
            this.resolutionIndex = resolutionIndex;
            this.fps = fps;
            this.bitrate = bitrate;
            this.audioMode = audioMode;
            this.title = title;
        }
    }

    private static final String VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int AUDIO_RATE = 48_000;
    private static final int AUDIO_CHANNELS = 1;

    private final Context context;
    private final Config config;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final RecordingTimeline timeline = new RecordingTimeline();
    private final AtomicBoolean errorReported = new AtomicBoolean(false);
    private ScreenFrameRenderer renderer;
    private volatile String failure;
    private volatile long stopDeadlineNs = Long.MAX_VALUE;
    private String actualDescription = "";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec videoCodec;
    private Surface videoInputSurface;
    private MediaCodec audioCodec;
    private AudioRecord microphone;
    private AudioRecord playback;
    private Thread videoThread;
    private Thread audioThread;
    private MuxerCoordinator muxer;
    private Uri outputUri;
    private ParcelFileDescriptor outputFd;
    private String displayName;
    private volatile boolean stopping;
    private volatile boolean aborted;

    ScreenRecorderEngine(Context context, Config config, Listener listener) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.listener = listener;
    }

    void start() throws Exception {
        if (config.resultData == null) throw new IllegalArgumentException("屏幕录制授权无效");
        try {
            createOutput();
            Point requested = calculateVideoSize();
            EncoderChoice choice = chooseVideoEncoder(requested.x, requested.y, config.fps, config.bitrate);
            createVideoEncoder(choice);
            actualDescription = choice.width + " × " + choice.height + " · " + choice.fps
                    + " FPS · " + String.format(Locale.CHINA, "%.1f Mbps", choice.bitrate / 1_000_000f);
            renderer = new ScreenFrameRenderer(choice.width, choice.height, choice.fps, timeline, this::reportError);
            Surface captureSurface = renderer.initialize(videoInputSurface);

            MediaProjectionManager manager = (MediaProjectionManager)
                    context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(config.resultCode, config.resultData);
            if (projection == null) throw new SecurityException("系统未授予屏幕录制权限");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (!stopping && running.get()) {
                        listener.onProjectionEnded();
                    }
                }
                @Override public void onCapturedContentResize(int width, int height) {
                    if (stopping || width <= 0 || height <= 0 || renderer == null || virtualDisplay == null) return;
                    renderer.setCaptureSize(width, height);
                    virtualDisplay.resize(width, height, context.getResources().getDisplayMetrics().densityDpi);
                }
            }, new Handler(Looper.getMainLooper()));

            int density = context.getResources().getDisplayMetrics().densityDpi;
            virtualDisplay = projection.createVirtualDisplay(
                    "CourseRecorderDisplay", choice.width, choice.height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    captureSurface, null, null);

            boolean withAudio = config.audioMode != 0;
            if (withAudio) createAudioPipeline();
            muxer = new MuxerCoordinator(new MediaMuxer(outputFd.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), withAudio ? 2 : 1);
            timeline.start();
            running.set(true);

            videoThread = new Thread(this::drainVideo, "video-encoder");
            videoThread.start();
            if (withAudio) {
                audioThread = new Thread(this::captureAndEncodeAudio, "audio-encoder");
                audioThread.start();
            }
            renderer.start();
        } catch (Throwable error) {
            aborted = true;
            releaseResources();
            deleteOutput();
            throw error;
        }
    }

    void setPaused(boolean value) {
        if (running.get() && !stopping) timeline.setPaused(value);
    }

    long durationUs() { return timeline.durationUs(); }
    String description() { return actualDescription; }

    synchronized void stop() throws Exception {
        if (stopping) return;
        stopping = true;
        timeline.setPaused(true);
        running.set(false);
        Exception failure = null;
        try {
            finishThreads();
            if (this.failure != null) throw new IOException(this.failure);
            if (muxer == null || !muxer.hasWrittenSamples()) {
                throw new IOException("没有生成有效的视频数据");
            }
            muxer.finish();
            publishOutput();
        } catch (Exception error) {
            failure = error;
            deleteOutput();
        } finally {
            releaseResources();
        }
        if (failure != null) throw failure;
        listener.onSaved(displayName);
    }

    synchronized void abort() {
        if (aborted) return;
        aborted = true;
        stopping = true;
        timeline.setPaused(true);
        running.set(false);
        try { finishThreads(); } catch (Throwable ignored) { }
        releaseResources();
        deleteOutput();
    }

    private void finishThreads() throws Exception {
        stopDeadlineNs = System.nanoTime() + 10_000_000_000L;
        try { if (microphone != null) microphone.stop(); } catch (Throwable ignored) { }
        try { if (playback != null) playback.stop(); } catch (Throwable ignored) { }
        if (renderer != null) { renderer.stop(); renderer = null; }
        if (videoCodec != null) videoCodec.signalEndOfInputStream();
        join(videoThread, 12_000);
        join(audioThread, 12_000);
    }

    private void reportError(String message) {
        failure = message;
        if (!stopping && errorReported.compareAndSet(false, true)) listener.onError(message);
    }

    private void createVideoEncoder(EncoderChoice choice) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME, choice.width, choice.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, choice.bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, choice.fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        videoCodec = MediaCodec.createByCodecName(choice.codecName);
        MediaCodecInfo.CodecCapabilities caps = videoCodec.getCodecInfo().getCapabilitiesForType(VIDEO_MIME);
        if (caps.getEncoderCapabilities().isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        videoInputSurface = videoCodec.createInputSurface();
        videoCodec.start();
    }

    private EncoderChoice chooseVideoEncoder(int requestedWidth, int requestedHeight,
                                             int requestedFps, int requestedBitrate) throws IOException {
        List<MediaCodecInfo> encoders = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (VIDEO_MIME.equalsIgnoreCase(type)) {
                    encoders.add(info);
                    break;
                }
            }
        }
        encoders.sort(Comparator.comparing((MediaCodecInfo info) -> !info.isHardwareAccelerated()));
        int width = requestedWidth;
        int height = requestedHeight;
        for (int attempt = 0; attempt < 8; attempt++) {
            for (MediaCodecInfo info : encoders) {
                try {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(VIDEO_MIME);
                    boolean surface = false;
                    for (int color : caps.colorFormats) {
                        if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
                    }
                    if (!surface) continue;
                    MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                    int alignedW = alignDown(width, videoCaps.getWidthAlignment());
                    int alignedH = alignDown(height, videoCaps.getHeightAlignment());
                    if (!videoCaps.isSizeSupported(alignedW, alignedH)) continue;
                    Range<Double> rates = videoCaps.getSupportedFrameRatesFor(alignedW, alignedH);
                    int fps = (int) Math.max(rates.getLower(), Math.min(requestedFps, rates.getUpper()));
                    Range<Integer> bitrates = videoCaps.getBitrateRange();
                    int bitrate = Math.max(bitrates.getLower(), Math.min(requestedBitrate, bitrates.getUpper()));
                    return new EncoderChoice(info.getName(), alignedW, alignedH, fps, bitrate);
                } catch (Throwable ignored) { }
            }
            width = alignDown((int) (width * 0.85f), 2);
            height = alignDown((int) (height * 0.85f), 2);
        }
        throw new IOException("设备没有可用的 H.264 屏幕编码器");
    }

    private Point calculateVideoSize() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point actual = new Point();
        display.getRealSize(actual);
        if (config.resolutionIndex == 0) return evenPoint(actual.x, actual.y);
        int targetLong = config.resolutionIndex == 1 ? 1920
                : config.resolutionIndex == 2 ? 1280 : 960;
        int actualLong = Math.max(actual.x, actual.y);
        if (actualLong <= targetLong) return evenPoint(actual.x, actual.y);
        float scale = targetLong / (float) actualLong;
        return evenPoint(Math.round(actual.x * scale), Math.round(actual.y * scale));
    }

    private void createAudioPipeline() throws Exception {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("未授予声音录制权限，请在设置中允许麦克风权限");
        MediaFormat audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_RATE, AUDIO_CHANNELS);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384);
        audioCodec = MediaCodec.createEncoderByType(AUDIO_MIME);
        audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioCodec.start();

        int min = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(16_384, min * 2);
        if (config.audioMode == 1 || config.audioMode == 3) {
            microphone = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
            ensureAudioReady(microphone, "麦克风");
        }
        if (config.audioMode == 2 || config.audioMode == 3) {
            AudioPlaybackCaptureConfiguration playbackConfig =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            AudioFormat pcm = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            playback = new AudioRecord.Builder()
                    .setAudioFormat(pcm)
                    .setBufferSizeInBytes(bufferBytes)
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .build();
            ensureAudioReady(playback, "设备声音");
        }
    }

    private void ensureAudioReady(AudioRecord record, String label) throws IOException {
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IOException(label + "录制初始化失败");
        }
    }

    private void drainVideo() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;
        try {
            while (true) {
                if (System.nanoTime() > stopDeadlineNs) throw new IOException("视频编码器结束超时");
                int output = videoCodec.dequeueOutputBuffer(info, 10_000);
                if (output == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!running.get() && stopping) continue;
                } else if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(videoCodec.getOutputFormat());
                } else if (output >= 0) {
                    ByteBuffer buffer = videoCodec.getOutputBuffer(output);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && buffer != null && track >= 0) {
                        muxer.writeSample(track, buffer, info);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    videoCodec.releaseOutputBuffer(output, false);
                    if (eos) break;
                }
            }
        } catch (Throwable error) {
            reportError("视频编码中断：" + safeMessage(error));
        }
    }

    private void captureAndEncodeAudio() {
        int samplesPerRead = 2048;
        short[] micData = new short[samplesPerRead];
        short[] playbackData = new short[samplesPerRead];
        short[] mixed = new short[samplesPerRead];
        long lastAudioEndUs = 0;
        long framesRead = 0;
        android.media.AudioTimestamp timestamp = new android.media.AudioTimestamp();
        try {
            if (microphone != null) microphone.startRecording();
            if (playback != null) playback.startRecording();
            while (running.get()) {
                int count;
                short[] source;
                if (config.audioMode == 1) {
                    count = microphone.read(micData, 0, samplesPerRead, AudioRecord.READ_BLOCKING);
                    source = micData;
                } else if (config.audioMode == 2) {
                    count = playback.read(playbackData, 0, samplesPerRead, AudioRecord.READ_BLOCKING);
                    source = playbackData;
                } else {
                    int micCount = microphone.read(micData, 0, samplesPerRead, AudioRecord.READ_BLOCKING);
                    int playCount = playback.read(playbackData, 0, samplesPerRead, AudioRecord.READ_BLOCKING);
                    if (running.get() && (micCount < 0 || playCount < 0)) throw new IOException("混音采集失败");
                    count = Math.min(Math.max(0, micCount), Math.max(0, playCount));
                    for (int i = 0; i < count; i++) {
                        int mic = i < micCount ? micData[i] : 0;
                        int device = i < playCount ? playbackData[i] : 0;
                        int value = mic + device;
                        mixed[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
                    }
                    source = mixed;
                }
                if (count < 0 && running.get()) throw new IOException("声音采集失败：" + count);
                if (!running.get()) break;
                if (count <= 0) {
                    drainAudio(false);
                    continue;
                }
                // Trim pause boundaries before AAC encoding; never discard encoded inter-frames.
                long firstSampleNs = System.nanoTime() - count * 1_000_000_000L / AUDIO_RATE;
                AudioRecord reference = microphone != null ? microphone : playback;
                if (reference.getTimestamp(timestamp, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                    firstSampleNs = timestamp.nanoTime + (framesRead - timestamp.framePosition) * 1_000_000_000L / AUDIO_RATE;
                framesRead += count;
                int offset = 0;
                while (offset < count && running.get()) {
                    long pts = timeline.presentationTimeUs(firstSampleNs + offset * 1_000_000_000L / AUDIO_RATE);
                    if (pts < 0) { offset++; continue; }
                    int end = offset + 1;
                    while (end < count && timeline.presentationTimeUs(firstSampleNs + end * 1_000_000_000L / AUDIO_RATE) >= 0) end++;
                    int input = -1;
                    while (input < 0 && running.get()) {
                        input = audioCodec.dequeueInputBuffer(10_000);
                        drainAudio(false);
                    }
                    if (input < 0) break;
                    ByteBuffer buffer = audioCodec.getInputBuffer(input);
                    if (buffer == null) throw new IOException("声音编码缓冲区不可用");
                    buffer.clear();
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    int accepted = Math.min(end - offset, buffer.remaining() / 2);
                    for (int i = 0; i < accepted; i++) buffer.putShort(source[offset + i]);
                    pts = Math.max(pts, lastAudioEndUs);
                    audioCodec.queueInputBuffer(input, 0, accepted * 2, pts, 0);
                    lastAudioEndUs = pts + accepted * 1_000_000L / AUDIO_RATE;
                    offset += accepted;
                }
                drainAudio(false);
            }
            int input;
            do {
                if (System.nanoTime() > stopDeadlineNs) throw new IOException("声音编码结束超时");
                input = audioCodec.dequeueInputBuffer(10_000);
                drainAudio(false);
            } while (input < 0);
            audioCodec.queueInputBuffer(input, 0, 0, lastAudioEndUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drainAudio(true);
        } catch (Throwable error) {
            reportError("声音编码中断：" + safeMessage(error));
        } finally {
            try { if (microphone != null) microphone.stop(); } catch (Throwable ignored) { }
            try { if (playback != null) playback.stop(); } catch (Throwable ignored) { }
        }
    }

    private int audioTrack = -1;

    private void drainAudio(boolean waitForEos) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            if (System.nanoTime() > stopDeadlineNs) throw new IllegalStateException("声音结束超时");
            int output = audioCodec.dequeueOutputBuffer(info, waitForEos ? 10_000 : 0);
            if (output == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEos) return;
            } else if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioTrack = muxer.addTrack(audioCodec.getOutputFormat());
            } else if (output >= 0) {
                ByteBuffer buffer = audioCodec.getOutputBuffer(output);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && buffer != null && audioTrack >= 0) {
                    muxer.writeSample(audioTrack, buffer, info);
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                audioCodec.releaseOutputBuffer(output, false);
                if (eos || !waitForEos && output < 0) return;
            }
        }
    }

    private void createOutput() throws IOException {
        String safeTitle = config.title == null ? "课程" : config.title.trim();
        safeTitle = safeTitle.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safeTitle.isEmpty()) safeTitle = "课程";
        if (safeTitle.length() > 80) safeTitle = safeTitle.substring(0, 80);
        displayName = safeTitle + "_" + String.format(Locale.CHINA, "%tY%<tm%<td_%<tH%<tM%<tS", new java.util.Date()) + ".mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/课堂录屏");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        ContentResolver resolver = context.getContentResolver();
        outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (outputUri == null) throw new IOException("无法创建视频文件");
        outputFd = resolver.openFileDescriptor(outputUri, "rw");
        if (outputFd == null) throw new IOException("无法打开视频文件");
    }

    private void publishOutput() {
        if (outputUri == null) return;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        context.getContentResolver().update(outputUri, values, null, null);
    }

    private void deleteOutput() {
        if (outputUri != null) {
            try { context.getContentResolver().delete(outputUri, null, null); }
            catch (Throwable ignored) { }
            outputUri = null;
        }
    }

    private void releaseResources() {
        try { if (renderer != null) renderer.stop(); } catch (Throwable ignored) { }
        renderer = null;
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Throwable ignored) { }
        virtualDisplay = null;
        try { if (videoInputSurface != null) videoInputSurface.release(); } catch (Throwable ignored) { }
        videoInputSurface = null;
        try { if (videoCodec != null) videoCodec.stop(); } catch (Throwable ignored) { }
        try { if (videoCodec != null) videoCodec.release(); } catch (Throwable ignored) { }
        videoCodec = null;
        try { if (audioCodec != null) audioCodec.stop(); } catch (Throwable ignored) { }
        try { if (audioCodec != null) audioCodec.release(); } catch (Throwable ignored) { }
        audioCodec = null;
        try { if (microphone != null) microphone.release(); } catch (Throwable ignored) { }
        try { if (playback != null) playback.release(); } catch (Throwable ignored) { }
        microphone = null;
        playback = null;
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
        projection = null;
        if (muxer != null && !muxer.finished) {
            try { muxer.releaseOnly(); } catch (Throwable ignored) { }
        }
        muxer = null;
        try { if (outputFd != null) outputFd.close(); } catch (Throwable ignored) { }
        outputFd = null;
    }

    private static void join(Thread thread, long millis) throws InterruptedException {
        if (thread == null || thread == Thread.currentThread()) return;
        thread.join(millis);
        if (thread.isAlive()) throw new IllegalStateException("录制线程未能安全结束");
    }

    private static Point evenPoint(int width, int height) {
        return new Point(alignDown(width, 2), alignDown(height, 2));
    }

    private static int alignDown(int value, int alignment) {
        return Math.max(alignment, value - value % alignment);
    }

    private static MediaCodec.BufferInfo copyInfo(MediaCodec.BufferInfo source) {
        MediaCodec.BufferInfo copy = new MediaCodec.BufferInfo();
        copy.set(source.offset, source.size, source.presentationTimeUs, source.flags);
        return copy;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    private static final class EncoderChoice {
        final String codecName;
        final int width;
        final int height;
        final int fps;
        final int bitrate;

        EncoderChoice(String codecName, int width, int height, int fps, int bitrate) {
            this.codecName = codecName;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrate = bitrate;
        }
    }

    private static final class PendingSample {
        final int track;
        final ByteBuffer data;
        final MediaCodec.BufferInfo info;

        PendingSample(int track, ByteBuffer source, MediaCodec.BufferInfo sourceInfo) {
            this.track = track;
            this.info = copyInfo(sourceInfo);
            source.position(sourceInfo.offset);
            source.limit(sourceInfo.offset + sourceInfo.size);
            data = ByteBuffer.allocateDirect(sourceInfo.size);
            data.put(source);
            data.flip();
            info.offset = 0;
        }
    }

    private static final class MuxerCoordinator {
        private final MediaMuxer muxer;
        private final int expectedTracks;
        private final List<PendingSample> pending = new ArrayList<>();
        private int trackCount;
        private boolean started;
        private boolean wroteSamples;
        private boolean wroteVideo;
        private int videoTrack = -1;
        private long pendingBytes;
        private final java.util.Map<Integer, Long> lastPts = new java.util.HashMap<>();
        private boolean finished;

        MuxerCoordinator(MediaMuxer muxer, int expectedTracks) {
            this.muxer = muxer;
            this.expectedTracks = expectedTracks;
        }

        synchronized int addTrack(MediaFormat format) {
            if (started) throw new IllegalStateException("编码轨道已开始");
            int track = muxer.addTrack(format);
            if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) videoTrack = track;
            trackCount++;
            if (trackCount == expectedTracks) {
                muxer.start();
                started = true;
                for (PendingSample sample : pending) {
                    muxer.writeSampleData(sample.track, sample.data, sample.info);
                    wroteSamples = true;
                    if (sample.track == videoTrack) wroteVideo = true;
                }
                pending.clear();
                pendingBytes = 0;
            }
            return track;
        }

        synchronized void writeSample(int track, ByteBuffer buffer, MediaCodec.BufferInfo info) {
            if (info.size <= 0 || finished) return;
            Long previous = lastPts.get(track);
            if (previous != null && info.presentationTimeUs <= previous) throw new IllegalStateException("编码时间戳顺序异常");
            lastPts.put(track, info.presentationTimeUs);
            if (!started) {
                pendingBytes += info.size;
                if (pendingBytes > 16 * 1024 * 1024) throw new IllegalStateException("声音轨道未准备好");
                pending.add(new PendingSample(track, buffer, info));
                return;
            }
            buffer.position(info.offset);
            buffer.limit(info.offset + info.size);
            muxer.writeSampleData(track, buffer, info);
            wroteSamples = true;
            if (track == videoTrack) wroteVideo = true;
        }

        synchronized boolean hasWrittenSamples() {
            return started && wroteSamples && wroteVideo;
        }

        synchronized void finish() {
            if (finished) return;
            if (started) muxer.stop();
            muxer.release();
            finished = true;
        }

        synchronized void releaseOnly() {
            if (finished) return;
            try { if (started) muxer.stop(); } catch (Throwable ignored) { }
            muxer.release();
            finished = true;
        }
    }
}
