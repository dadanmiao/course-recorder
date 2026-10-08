package com.codex.courserecorder;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Samples the screen at the selected FPS; pauses never enter the video encoder. */
final class ScreenFrameRenderer {
    private final int width, height, fps;
    private final RecordingTimeline timeline;
    private final Consumer<String> errorHandler;
    private final HandlerThread thread = new HandlerThread("screen-renderer");
    private Handler handler;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface captureSurface;
    private int program, textureName;
    private FloatBuffer vertices;
    private boolean hasFrame, scheduled;
    private long lastPtsUs = -1;
    private long nextFrameUptime;
    private long cadenceOrigin, frameNumber;
    private int captureWidth, captureHeight;
    private final float[] transform = new float[16];

    ScreenFrameRenderer(int width, int height, int fps, RecordingTimeline timeline,
                        Consumer<String> errorHandler) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.timeline = timeline;
        this.errorHandler = errorHandler;
        captureWidth = width;
        captureHeight = height;
    }

    Surface initialize(Surface encoderSurface) throws Exception {
        thread.start();
        handler = new Handler(thread.getLooper());
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        handler.post(() -> {
            try { initializeGl(encoderSurface); }
            catch (Throwable error) { failure.set(error); releaseGl(); }
            finally { ready.countDown(); }
        });
        if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("画面初始化超时");
        if (failure.get() != null) {
            thread.quitSafely();
            throw new IllegalStateException("无法初始化录屏画面", failure.get());
        }
        return captureSurface;
    }

    void start() {
        handler.post(() -> {
            scheduled = true;
            nextFrameUptime = SystemClock.uptimeMillis();
            cadenceOrigin = nextFrameUptime;
            frameNumber = 0;
            tick.run();
        });
    }

    void setCaptureSize(int w, int h) {
        if (handler == null) return;
        handler.post(() -> {
            captureWidth = w;
            captureHeight = h;
            if (texture != null) texture.setDefaultBufferSize(w, h);
        });
    }

    void stop() throws InterruptedException {
        if (handler == null) return;
        CountDownLatch stopped = new CountDownLatch(1);
        handler.post(() -> {
            scheduled = false;
            handler.removeCallbacks(tick);
            // Do not submit the newest texture here: it may contain private paused content.
            try { releaseGl(); }
            finally { stopped.countDown(); }
        });
        if (!stopped.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("画面停止超时");
        thread.quitSafely();
        thread.join(2000);
        handler = null;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!scheduled) return;
            try {
                long pts = timeline.presentationTimeUs(System.nanoTime());
                if (hasFrame && pts >= 0) draw(pts);
                nextFrameUptime = cadenceOrigin + (++frameNumber * 1000L / fps);
                long now = SystemClock.uptimeMillis();
                if (nextFrameUptime <= now) {
                    frameNumber = (now - cadenceOrigin) * fps / 1000 + 1;
                    nextFrameUptime = cadenceOrigin + frameNumber * 1000L / fps;
                }
                handler.postAtTime(this, nextFrameUptime);
            } catch (Throwable error) {
                scheduled = false;
                errorHandler.accept("画面渲染失败：" + error.getClass().getSimpleName());
            }
        }
    };

    private void initializeGl(Surface encoderSurface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) fail("EGL 初始化失败");
        int[] attributes = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                || count[0] == 0) fail("没有可用的录屏渲染配置");
        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface,
                new int[]{EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) fail("EGL 上下文失败");

        String vertex = "attribute vec2 position; attribute vec2 texCoord; uniform mat4 transform;"
                + "varying vec2 uv; void main(){gl_Position=vec4(position,0.0,1.0);"
                + "uv=(transform*vec4(texCoord,0.0,1.0)).xy;}";
        String fragment = "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float; varying vec2 uv; uniform samplerExternalOES image;"
                + "void main(){gl_FragColor=texture2D(image,uv);}";
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (linked[0] == 0) fail("画面着色器链接失败");

        float[] quad = {-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1};
        vertices = ByteBuffer.allocateDirect(quad.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(quad).position(0);
        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        textureName = names[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texture = new SurfaceTexture(textureName);
        texture.setDefaultBufferSize(width, height);
        texture.setOnFrameAvailableListener(source -> {
            try {
                source.updateTexImage();
                source.getTransformMatrix(transform);
                hasFrame = true;
            } catch (Throwable error) {
                if (scheduled) errorHandler.accept("无法读取屏幕画面");
            }
        }, handler);
        captureSurface = new Surface(texture);
    }

    private void draw(long ptsUs) {
        if (ptsUs <= lastPtsUs) return;
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        float scale = Math.min(width / (float) captureWidth, height / (float) captureHeight);
        int drawW = Math.round(captureWidth * scale);
        int drawH = Math.round(captureHeight * scale);
        GLES20.glViewport((width - drawW) / 2, (height - drawH) / 2, drawW, drawH);
        GLES20.glUseProgram(program);
        int position = GLES20.glGetAttribLocation(program, "position");
        int coord = GLES20.glGetAttribLocation(program, "texCoord");
        vertices.position(0);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices);
        vertices.position(2);
        GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glEnableVertexAttribArray(coord);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, transform, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsUs * 1000L);
        if (!EGL14.eglSwapBuffers(display, eglSurface)) fail("画面输出失败");
        lastPtsUs = ptsUs;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] result = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result, 0);
        if (result[0] == 0) fail("画面着色器编译失败");
        return shader;
    }

    private void releaseGl() {
        if (captureSurface != null) captureSurface.release();
        if (texture != null) { texture.setOnFrameAvailableListener(null); texture.release(); }
        if (program != 0) GLES20.glDeleteProgram(program);
        if (textureName != 0) GLES20.glDeleteTextures(1, new int[]{textureName}, 0);
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, eglSurface);
            EGL14.eglDestroyContext(display, eglContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
        captureSurface = null;
        texture = null;
        display = EGL14.EGL_NO_DISPLAY;
    }

    private static void fail(String message) { throw new IllegalStateException(message); }
}
