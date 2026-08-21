package org.openquestcamera.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;

final class GpuStereoRenderer {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    interface StatsCallback {
        void onStats(double actualFps, int dropped, double syncMs);
        void onFatal(String message);
    }

    interface PhotoCallback {
        void onPhoto(Bitmap sbs, Bitmap left, Bitmap right, long timestampNs);
        void onError(String message);
    }

    private static final float[] POSITIONS = new float[]{
            -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
    };
    private static final float[] TEX_COORDS = new float[]{
            0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f
    };

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTex;\n" +
            "void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTex=aTexCoord; }\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uLeft;\n" +
            "uniform samplerExternalOES uRight;\n" +
            "uniform mat4 uLeftMatrix;\n" +
            "uniform mat4 uRightMatrix;\n" +
            "uniform float uConv;\n" +
            "uniform float uVert;\n" +
            "uniform int uSwap;\n" +
            "uniform int uMode;\n" +
            "varying vec2 vTex;\n" +
            "vec4 sampleL(vec2 uv){ if(uv.x<0.0||uv.x>1.0||uv.y<0.0||uv.y>1.0)return vec4(0.0,0.0,0.0,1.0); vec2 p=(uLeftMatrix*vec4(uv,0.0,1.0)).xy; return texture2D(uLeft,p);}\n" +
            "vec4 sampleR(vec2 uv){ if(uv.x<0.0||uv.x>1.0||uv.y<0.0||uv.y>1.0)return vec4(0.0,0.0,0.0,1.0); vec2 p=(uRightMatrix*vec4(uv,0.0,1.0)).xy; return texture2D(uRight,p);}\n" +
            "void main(){\n" +
            "  if(uMode==1){ gl_FragColor=(uSwap==0)?sampleL(vTex):sampleR(vTex); return; }\n" +
            "  if(uMode==2){ gl_FragColor=(uSwap==0)?sampleR(vTex):sampleL(vTex); return; }\n" +
            "  bool leftHalf=vTex.x<0.5;\n" +
            "  float x=leftHalf?vTex.x*2.0:(vTex.x-0.5)*2.0;\n" +
            "  vec2 uv=vec2(leftHalf?x-uConv:x+uConv, leftHalf?vTex.y:vTex.y+uVert);\n" +
            "  if(leftHalf) gl_FragColor=(uSwap==0)?sampleL(uv):sampleR(uv);\n" +
            "  else gl_FragColor=(uSwap==0)?sampleR(uv):sampleL(uv);\n" +
            "}\n";

    private final int eyeWidth;
    private final int eyeHeight;
    private final int targetFps;
    private final Context context;
    private final StatsCallback statsCallback;
    private final HandlerThread glThread;
    private final Handler glHandler;
    private final FloatBuffer positionBuffer;
    private final FloatBuffer texBuffer;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;
    private EGLSurface dummySurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface previewEglSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface encoderEglSurface = EGL14.EGL_NO_SURFACE;

    private SurfaceTexture leftTexture;
    private SurfaceTexture rightTexture;
    private Surface leftInputSurface;
    private Surface rightInputSurface;
    private Surface previewNativeSurface;
    private int previewWidth;
    private int previewHeight;
    private boolean previewEnabled = true;
    private int encoderWidth;
    private int encoderHeight;
    private long encoderBaseTimestampNs = -1L;
    private long lastEncoderPtsNs = -1L;

    private int leftTexId;
    private int rightTexId;
    private int program;
    private int aPosition;
    private int aTexCoord;
    private int uLeft;
    private int uRight;
    private int uLeftMatrix;
    private int uRightMatrix;
    private int uConv;
    private int uVert;
    private int uSwap;
    private int uMode;

    private final float[] leftMatrix = new float[16];
    private final float[] rightMatrix = new float[16];
    private boolean leftAvailable;
    private boolean rightAvailable;
    private boolean leftReady;
    private boolean rightReady;
    private long leftTs;
    private long rightTs;
    private volatile boolean released;
    private volatile boolean swap;
    private volatile int convergencePx;
    private volatile int verticalPx;
    private PhotoCallback pendingPhoto;
    private boolean pendingPhotoOriginals;

    private int dropped;
    private int rendered;
    private int statsBaseRendered;
    private long statsBaseNs;

    GpuStereoRenderer(Context context, int eyeWidth, int eyeHeight, int targetFps, StatsCallback callback) {
        this.context = context.getApplicationContext();
        this.eyeWidth = eyeWidth;
        this.eyeHeight = eyeHeight;
        this.targetFps = targetFps;
        this.statsCallback = callback;
        positionBuffer = floatBuffer(POSITIONS);
        texBuffer = floatBuffer(TEX_COORDS);
        glThread = new HandlerThread("stereo-gpu");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
    }

    void start() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] error = new Throwable[1];
        glHandler.post(new Runnable() {
            @Override public void run() {
                try { initGl(); }
                catch (Throwable t) { error[0] = t; }
                finally { latch.countDown(); }
            }
        });
        if (!latch.await(4, TimeUnit.SECONDS)) throw new IllegalStateException(context.getString(R.string.error_gpu_initialization_timeout));
        if (error[0] != null) throw new IllegalStateException(context.getString(R.string.error_gpu_initialization, shortMessage(error[0])), error[0]);
    }

    Surface leftInputSurface() { return leftInputSurface; }
    Surface rightInputSurface() { return rightInputSurface; }

    void setPreviewSurface(final Surface surface, final int width, final int height, final boolean enabled) {
        glHandler.post(new Runnable() {
            @Override public void run() {
                makeCurrent(dummySurface);
                previewEnabled = enabled;
                previewWidth = Math.max(1, width);
                previewHeight = Math.max(1, height);
                if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, previewEglSurface);
                    previewEglSurface = EGL14.EGL_NO_SURFACE;
                }
                previewNativeSurface = surface;
                if (surface != null && surface.isValid()) {
                    previewEglSurface = createWindowSurface(surface);
                }
            }
        });
    }

    void setPreviewEnabled(final boolean enabled) {
        glHandler.post(new Runnable() {
            @Override public void run() { previewEnabled = enabled; }
        });
    }

    void setCalibration(final boolean swap, final int convergencePx, final int verticalPx) {
        this.swap = swap;
        this.convergencePx = convergencePx;
        this.verticalPx = verticalPx;
    }

    void attachEncoderSurface(final Surface surface, final int width, final int height) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] error = new Throwable[1];
        glHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    makeCurrent(dummySurface);
                    if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, encoderEglSurface);
                    }
                    encoderEglSurface = createWindowSurface(surface);
                    encoderWidth = width;
                    encoderHeight = height;
                    rendered = 0;
                    dropped = 0;
                    statsBaseRendered = 0;
                    statsBaseNs = System.nanoTime();
                    encoderBaseTimestampNs = -1L;
                    lastEncoderPtsNs = -1L;
                } catch (Throwable t) {
                    error[0] = t;
                } finally {
                    latch.countDown();
                }
            }
        });
        if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException(context.getString(R.string.error_encoder_surface_timeout));
        if (error[0] != null) throw new IllegalStateException(context.getString(R.string.error_encoder_surface, shortMessage(error[0])), error[0]);
    }

    void detachEncoderSurface() {
        final CountDownLatch latch = new CountDownLatch(1);
        glHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    makeCurrent(dummySurface);
                    if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, encoderEglSurface);
                        encoderEglSurface = EGL14.EGL_NO_SURFACE;
                        encoderBaseTimestampNs = -1L;
                        lastEncoderPtsNs = -1L;
                    }
                } finally {
                    latch.countDown();
                }
            }
        });
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    void requestPhoto(final boolean saveOriginals, final PhotoCallback callback) {
        glHandler.post(new Runnable() {
            @Override public void run() {
                if (pendingPhoto != null) {
                    callback.onError(context.getString(R.string.status_photo_processing));
                    return;
                }
                pendingPhotoOriginals = saveOriginals;
                pendingPhoto = callback;
            }
        });
    }

    void release() {
        if (released) return;
        released = true;
        final CountDownLatch latch = new CountDownLatch(1);
        glHandler.post(new Runnable() {
            @Override public void run() {
                try { releaseGl(); }
                finally { latch.countDown(); }
            }
        });
        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        glThread.quitSafely();
    }

    private void initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException(context.getString(R.string.error_no_egl_display));
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw new IllegalStateException(context.getString(R.string.error_egl_initialization));
        int[] attrs = new int[]{
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw new IllegalStateException(context.getString(R.string.error_no_egl_config));
        }
        eglConfig = configs[0];
        int[] contextAttrs = new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttrs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException(context.getString(R.string.error_egl_context));
        int[] pbufferAttrs = new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        dummySurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttrs, 0);
        makeCurrent(dummySurface);

        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        uLeft = GLES20.glGetUniformLocation(program, "uLeft");
        uRight = GLES20.glGetUniformLocation(program, "uRight");
        uLeftMatrix = GLES20.glGetUniformLocation(program, "uLeftMatrix");
        uRightMatrix = GLES20.glGetUniformLocation(program, "uRightMatrix");
        uConv = GLES20.glGetUniformLocation(program, "uConv");
        uVert = GLES20.glGetUniformLocation(program, "uVert");
        uSwap = GLES20.glGetUniformLocation(program, "uSwap");
        uMode = GLES20.glGetUniformLocation(program, "uMode");

        leftTexId = createExternalTexture();
        rightTexId = createExternalTexture();
        leftTexture = new SurfaceTexture(leftTexId);
        rightTexture = new SurfaceTexture(rightTexId);
        leftTexture.setDefaultBufferSize(eyeWidth, eyeHeight);
        rightTexture.setDefaultBufferSize(eyeWidth, eyeHeight);
        leftTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                leftAvailable = true;
                drainInputsAndRender();
            }
        }, glHandler);
        rightTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                rightAvailable = true;
                drainInputsAndRender();
            }
        }, glHandler);
        leftInputSurface = new Surface(leftTexture);
        rightInputSurface = new Surface(rightTexture);
        statsBaseNs = System.nanoTime();
    }

    private void drainInputsAndRender() {
        if (released) return;
        try {
            makeCurrent(dummySurface);
            if (leftAvailable) {
                leftTexture.updateTexImage();
                leftTexture.getTransformMatrix(leftMatrix);
                leftTs = leftTexture.getTimestamp();
                leftAvailable = false;
                leftReady = true;
            }
            if (rightAvailable) {
                rightTexture.updateTexImage();
                rightTexture.getTransformMatrix(rightMatrix);
                rightTs = rightTexture.getTimestamp();
                rightAvailable = false;
                rightReady = true;
            }
            if (!leftReady || !rightReady) return;

            long delta = Math.abs(leftTs - rightTs);
            long tolerance = targetFps >= 50 ? 12_000_000L : 25_000_000L;
            if (delta > tolerance) {
                if (leftTs < rightTs) leftReady = false;
                else rightReady = false;
                dropped++;
                publishStats(delta);
                return;
            }

            long timestamp = (leftTs + rightTs) / 2L;
            if (previewEnabled && previewEglSurface != EGL14.EGL_NO_SURFACE) {
                renderStereo(previewEglSurface, previewWidth, previewHeight, false, timestamp, 0);
            }
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                if (encoderBaseTimestampNs < 0) encoderBaseTimestampNs = timestamp;
                long ptsNs = Math.max(0L, timestamp - encoderBaseTimestampNs);
                if (ptsNs <= lastEncoderPtsNs) ptsNs = lastEncoderPtsNs + 1L;
                lastEncoderPtsNs = ptsNs;
                renderStereo(encoderEglSurface, encoderWidth, encoderHeight, true, ptsNs, 0);
                rendered++;
            }

            PhotoCallback photo = pendingPhoto;
            if (photo != null) {
                pendingPhoto = null;
                try {
                    Bitmap sbs = renderBitmap(0, eyeWidth * 2, eyeHeight);
                    Bitmap left = null;
                    Bitmap right = null;
                    if (pendingPhotoOriginals) {
                        left = renderBitmap(1, eyeWidth, eyeHeight);
                        right = renderBitmap(2, eyeWidth, eyeHeight);
                    }
                    photo.onPhoto(sbs, left, right, timestamp);
                } catch (Throwable t) {
                    photo.onError(context.getString(R.string.error_photo_capture, shortMessage(t)));
                }
            }

            leftReady = false;
            rightReady = false;
            publishStats(delta);
        } catch (Throwable t) {
            if (statsCallback != null) statsCallback.onFatal(context.getString(R.string.error_gpu_pipeline, shortMessage(t)));
        }
    }

    private void publishStats(long deltaNs) {
        long now = System.nanoTime();
        if (now - statsBaseNs < 750_000_000L) return;
        int diff = rendered - statsBaseRendered;
        double fps = diff * 1_000_000_000.0 / Math.max(1L, now - statsBaseNs);
        statsBaseNs = now;
        statsBaseRendered = rendered;
        if (statsCallback != null) statsCallback.onStats(fps, dropped, deltaNs / 1_000_000.0);
    }

    private void renderStereo(EGLSurface target, int width, int height, boolean encoder, long timestampNs, int mode) {
        makeCurrent(target);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (encoder || mode != 0) {
            GLES20.glViewport(0, 0, width, height);
        } else {
            int[] viewport = fitViewport(width, height, eyeWidth * 2f / eyeHeight);
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
        draw(mode);
        if (encoder) EGLExt.eglPresentationTimeANDROID(eglDisplay, target, timestampNs);
        if (!EGL14.eglSwapBuffers(eglDisplay, target)) throw new IllegalStateException(context.getString(R.string.error_egl_swap));
    }

    private void draw(int mode) {
        GLES20.glUseProgram(program);
        positionBuffer.position(0);
        texBuffer.position(0);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positionBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, leftTexId);
        GLES20.glUniform1i(uLeft, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, rightTexId);
        GLES20.glUniform1i(uRight, 1);
        GLES20.glUniformMatrix4fv(uLeftMatrix, 1, false, leftMatrix, 0);
        GLES20.glUniformMatrix4fv(uRightMatrix, 1, false, rightMatrix, 0);
        GLES20.glUniform1f(uConv, convergencePx / (float) eyeWidth);
        GLES20.glUniform1f(uVert, verticalPx / (float) eyeHeight);
        GLES20.glUniform1i(uSwap, swap ? 1 : 0);
        GLES20.glUniform1i(uMode, mode);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
        checkGl("draw");
    }

    private Bitmap renderBitmap(int mode, int width, int height) {
        makeCurrent(dummySurface);
        int[] tex = new int[1];
        int[] fbo = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(context.getString(R.string.error_photo_fbo));
        }
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        draw(mode);
        ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
        rgba.rewind();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int dstY = height - 1 - y;
            for (int x = 0; x < width; x++) {
                int r = rgba.get() & 255;
                int g = rgba.get() & 255;
                int b = rgba.get() & 255;
                int a = rgba.get() & 255;
                pixels[dstY * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(1, fbo, 0);
        GLES20.glDeleteTextures(1, tex, 0);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private EGLSurface createWindowSurface(Surface surface) {
        int[] attrs = new int[]{EGL14.EGL_NONE};
        EGLSurface result = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attrs, 0);
        if (result == EGL14.EGL_NO_SURFACE) throw new IllegalStateException(context.getString(R.string.error_egl_window_surface));
        return result;
    }

    private void makeCurrent(EGLSurface surface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            throw new IllegalStateException(context.getString(R.string.error_egl_make_current));
        }
    }

    private static int[] fitViewport(int width, int height, float contentAspect) {
        float surfaceAspect = width / (float) Math.max(1, height);
        if (surfaceAspect > contentAspect) {
            int w = Math.round(height * contentAspect);
            return new int[]{(width - w) / 2, 0, w, height};
        } else {
            int h = Math.round(width / contentAspect);
            return new int[]{0, (height - h) / 2, width, h};
        }
    }

    private static int createExternalTexture() {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return ids[0];
    }

    private int linkProgram(String vertex, String fragment) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        String log = GLES20.glGetProgramInfoLog(program);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (ok[0] == 0) throw new IllegalStateException(context.getString(R.string.error_gl_program_link, log));
        return program;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException(context.getString(R.string.error_gl_shader_compile, log));
        }
        return shader;
    }

    private static FloatBuffer floatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private void checkGl(String where) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException(context.getString(R.string.error_gl_operation, where, Integer.toHexString(error)));
    }

    private void releaseGl() {
        try { makeCurrent(dummySurface); } catch (Throwable ignored) {}
        if (leftTexture != null) try { leftTexture.release(); } catch (Throwable ignored) {}
        if (rightTexture != null) try { rightTexture.release(); } catch (Throwable ignored) {}
        if (leftInputSurface != null) try { leftInputSurface.release(); } catch (Throwable ignored) {}
        if (rightInputSurface != null) try { rightInputSurface.release(); } catch (Throwable ignored) {}
        leftTexture = null;
        rightTexture = null;
        leftInputSurface = null;
        rightInputSurface = null;
        if (program != 0) GLES20.glDeleteProgram(program);
        int[] ids = new int[]{leftTexId, rightTexId};
        if (leftTexId != 0 || rightTexId != 0) GLES20.glDeleteTextures(2, ids, 0);
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, previewEglSurface);
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, encoderEglSurface);
        if (dummySurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, dummySurface);
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(eglDisplay);
        previewEglSurface = EGL14.EGL_NO_SURFACE;
        encoderEglSurface = EGL14.EGL_NO_SURFACE;
        dummySurface = EGL14.EGL_NO_SURFACE;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglDisplay = EGL14.EGL_NO_DISPLAY;
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.length() == 0 ? t.getClass().getSimpleName() : m;
    }
}
