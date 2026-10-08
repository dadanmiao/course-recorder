package com.codex.courserecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** One monotonic clock for video, PCM audio, and the visible recording timer. */
final class RecordingTimeline {
    private final LongSupplier clock;
    private final List<long[]> pauses = new ArrayList<>();
    private long originNs;
    private boolean started;
    private boolean paused;

    RecordingTimeline() { this(System::nanoTime); }
    RecordingTimeline(LongSupplier clock) { this.clock = clock; }

    synchronized void start() {
        originNs = clock.getAsLong();
        started = true;
        paused = false;
        pauses.clear();
    }

    synchronized void setPaused(boolean value) {
        if (!started || value == paused) return;
        long now = clock.getAsLong();
        if (value) pauses.add(new long[]{now, Long.MAX_VALUE});
        else pauses.get(pauses.size() - 1)[1] = now;
        paused = value;
    }

    synchronized boolean isPaused() { return paused; }

    synchronized long presentationTimeUs(long timestampNs) {
        if (!started || timestampNs < originNs) return -1;
        long excluded = 0;
        for (long[] pause : pauses) {
            if (timestampNs < pause[0]) break;
            if (timestampNs < pause[1]) return -1;
            excluded += pause[1] - pause[0];
        }
        return Math.max(0, (timestampNs - originNs - excluded) / 1000);
    }

    synchronized long durationUs() {
        if (!started) return 0;
        long timestamp = paused ? pauses.get(pauses.size() - 1)[0] : clock.getAsLong();
        long excluded = 0;
        for (long[] pause : pauses) {
            if (pause[1] == Long.MAX_VALUE) break;
            excluded += pause[1] - pause[0];
        }
        return Math.max(0, (timestamp - originNs - excluded) / 1000);
    }
}
