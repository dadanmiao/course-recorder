package com.codex.courserecorder;

import android.app.Instrumentation;
import android.content.ContentUris;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.provider.MediaStore;
import java.nio.ByteBuffer;

/** Integration QA: inspects and decodes the most recently saved course video. */
public class VideoInspection extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments) { args = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            android.net.Uri uri;
            try (Cursor cursor = getTargetContext().getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME},
                    MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? AND is_pending=0",
                    new String[]{"Movies/课堂录屏%"}, "date_added DESC, _id DESC")) {
                if (cursor == null || !cursor.moveToFirst()) throw new AssertionError("No saved course video");
                uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
                result.putString("file", cursor.getString(1));
            }
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(getTargetContext(), uri, null);
            result.putInt("tracks", extractor.getTrackCount());
            int videoTrack = -1;
            long videoLastPts = 0;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                result.putString("format_" + i, format.toString());
                extractor.selectTrack(i);
                long previous = -1, first = -1, last = -1, largestGap = 0;
                int samples = 0;
                ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024);
                while (extractor.readSampleData(buffer, 0) >= 0) {
                    long pts = extractor.getSampleTime();
                    if (pts <= previous) throw new AssertionError("Non-monotonic PTS track " + i);
                    if (first < 0) first = pts;
                    if (previous >= 0) largestGap = Math.max(largestGap, pts - previous);
                    previous = last = pts; samples++;
                    if (!extractor.advance()) break;
                }
                if (samples == 0) throw new AssertionError("Empty track");
                result.putString("track_" + i, "samples=" + samples + " firstUs=" + first + " lastUs=" + last
                        + " largestGapUs=" + largestGap + " averageFps=" + (samples - 1) * 1_000_000d / Math.max(1, last - first));
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) { videoTrack = i; videoLastPts = last; }
                extractor.unselectTrack(i);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }
            if (videoTrack < 0) throw new AssertionError("Missing video");
            extractor.selectTrack(videoTrack);
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            MediaFormat format = extractor.getTrackFormat(videoTrack);
            MediaCodec decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0); decoder.start();
            boolean inputEos = false, outputEos = false;
            int frames = 0;
            long deadline = System.nanoTime() + 30_000_000_000L;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputEos && System.nanoTime() < deadline) {
                if (!inputEos) {
                    int input = decoder.dequeueInputBuffer(1000);
                    if (input >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(input);
                        int length = extractor.readSampleData(buffer, 0);
                        if (length < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            decoder.queueInputBuffer(input, 0, length, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int output = decoder.dequeueOutputBuffer(info, 1000);
                if (output >= 0) {
                    if (info.size > 0) frames++;
                    outputEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(output, false);
                }
            }
            decoder.stop(); decoder.release(); extractor.release();
            if (!outputEos || frames == 0) throw new AssertionError("Decode failed or timed out");
            result.putInt("decodedFrames", frames);
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            retriever.setDataSource(getTargetContext(), uri);
            long frameTime = "true".equals(args.getString("last")) ? videoLastPts : 2_000_000;
            android.graphics.Bitmap bitmap = retriever.getFrameAtTime(frameTime,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST);
            if (bitmap == null) throw new AssertionError("No visible decoded frame");
            java.util.Set<Integer> colors = new java.util.HashSet<>();
            for (int y = 0; y < bitmap.getHeight(); y += 30)
                for (int x = 0; x < bitmap.getWidth(); x += 30) colors.add(bitmap.getPixel(x, y));
            if (colors.size() < 8) throw new AssertionError("Frame appears blank");
            result.putInt("sampledColors", colors.size());
            java.io.File screenshot = new java.io.File(getTargetContext().getExternalFilesDir(null), "decoded-frame.png");
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(screenshot)) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            }
            result.putString("frame", screenshot.getAbsolutePath());
            bitmap.recycle(); retriever.release();
            result.putString("result", "PASS");
            finish(0, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error);
            finish(1, result);
        }
    }
}
