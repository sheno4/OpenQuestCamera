package org.openquestcamera.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.view.Surface;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class StereoCameraEngine {
    interface Listener {
        void onReady();
        void onStats(double actualFps, int dropped, double syncMs);
        void onFatal(String message);
    }

    interface PhotoResultCallback {
        void onPhoto(android.graphics.Bitmap sbs, android.graphics.Bitmap left, android.graphics.Bitmap right, long timestampNs);
        void onError(String message);
    }

    private final Activity activity;
    private final CameraModels.Pair pair;
    private final CameraModels.RecorderConfig config;
    private final Listener listener;
    private final CameraManager cameraManager;
    private final HandlerThread cameraThread;
    private final Handler cameraHandler;
    private GpuStereoRenderer renderer;
    private CameraDevice leftCamera;
    private CameraDevice rightCamera;
    private CameraCaptureSession leftSession;
    private CameraCaptureSession rightSession;
    private boolean leftReady;
    private boolean rightReady;
    private volatile boolean running;

    StereoCameraEngine(Activity activity, CameraModels.Pair pair, CameraModels.RecorderConfig config, Listener listener) {
        this.activity = activity;
        this.pair = pair;
        this.config = config;
        this.listener = listener;
        cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        cameraThread = new HandlerThread("stereo-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    void start() throws Exception {
        renderer = new GpuStereoRenderer(activity, config.width, config.height, config.fps, new GpuStereoRenderer.StatsCallback() {
            @Override public void onStats(double actualFps, int dropped, double syncMs) {
                if (listener != null) listener.onStats(actualFps, dropped, syncMs);
            }

            @Override public void onFatal(String message) {
                if (listener != null) listener.onFatal(message);
            }
        });
        renderer.start();
        renderer.setCalibration(config.swap, config.convergencePx, config.verticalPx);
        renderer.setPreviewEnabled(config.preview);
        running = true;
        openCamera(pair.leftId, true, configRange(true), renderer.leftInputSurface());
        openCamera(pair.rightId, false, configRange(false), renderer.rightInputSurface());
    }

    void setPreviewSurface(Surface surface, int width, int height, boolean enabled) {
        if (renderer != null) renderer.setPreviewSurface(surface, width, height, enabled);
    }

    void setPreviewEnabled(boolean enabled) {
        if (renderer != null) renderer.setPreviewEnabled(enabled);
    }

    void setCalibration(boolean swap, int convergencePx, int verticalPx) {
        if (renderer != null) renderer.setCalibration(swap, convergencePx, verticalPx);
    }

    void attachRecorder(VideoRecorder recorder) throws Exception {
        if (renderer == null || recorder == null || recorder.inputSurface() == null) throw new IllegalStateException(activity.getString(R.string.error_encoder_not_ready));
        renderer.attachEncoderSurface(recorder.inputSurface(), config.width * 2, config.height);
    }

    void detachRecorder() {
        if (renderer != null) renderer.detachEncoderSurface();
    }

    void capturePhoto(boolean saveOriginals, final PhotoResultCallback callback) {
        if (renderer == null) {
            callback.onError(activity.getString(R.string.error_camera_not_ready));
            return;
        }
        renderer.requestPhoto(saveOriginals, new GpuStereoRenderer.PhotoCallback() {
            @Override public void onPhoto(android.graphics.Bitmap sbs, android.graphics.Bitmap left, android.graphics.Bitmap right, long timestampNs) {
                callback.onPhoto(sbs, left, right, timestampNs);
            }

            @Override public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    void stop() {
        running = false;
        final CountDownLatch latch = new CountDownLatch(1);
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                closeCameras();
                latch.countDown();
            }
        });
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        if (renderer != null) {
            try { renderer.release(); } catch (Throwable ignored) {}
            renderer = null;
        }
        cameraThread.quitSafely();
    }

    private Range<Integer> configRange(boolean left) {
        CameraModels.Mode mode = findMode();
        if (mode == null) return null;
        return left ? mode.leftRange(config.fps) : mode.rightRange(config.fps);
    }

    private CameraModels.Mode findMode() {
        for (CameraModels.Mode mode : pair.modes) {
            if (mode.width == config.width && mode.height == config.height) return mode;
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    private void openCamera(final String id, final boolean left, final Range<Integer> fpsRange, final Surface target) throws Exception {
        cameraManager.openCamera(id, new CameraDevice.StateCallback() {
            @Override public void onOpened(final CameraDevice camera) {
                if (!running) {
                    camera.close();
                    return;
                }
                if (left) leftCamera = camera;
                else rightCamera = camera;
                try {
                    camera.createCaptureSession(Arrays.asList(target), new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            if (!running) {
                                session.close();
                                return;
                            }
                            try {
                                CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                request.addTarget(target);
                                if (fpsRange != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                                session.setRepeatingRequest(request.build(), null, cameraHandler);
                                if (left) {
                                    leftSession = session;
                                    leftReady = true;
                                } else {
                                    rightSession = session;
                                    rightReady = true;
                                }
                                if (leftReady && rightReady && listener != null) listener.onReady();
                            } catch (Throwable t) {
                                fail(activity.getString(R.string.error_camera_stream_start, shortMessage(t)));
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            fail(activity.getString(R.string.error_stereo_session_config));
                        }
                    }, cameraHandler);
                } catch (Throwable t) {
                    fail(activity.getString(R.string.error_camera_session_create, shortMessage(t)));
                }
            }

            @Override public void onDisconnected(CameraDevice camera) {
                camera.close();
                fail(activity.getString(R.string.error_camera_disconnected));
            }

            @Override public void onError(CameraDevice camera, int error) {
                camera.close();
                fail(activity.getString(R.string.error_camera_code, error));
            }
        }, cameraHandler);
    }

    private void fail(String message) {
        if (!running) return;
        if (listener != null) listener.onFatal(message);
    }

    private void closeCameras() {
        try { if (leftSession != null) leftSession.stopRepeating(); } catch (Throwable ignored) {}
        try { if (rightSession != null) rightSession.stopRepeating(); } catch (Throwable ignored) {}
        try { if (leftSession != null) leftSession.close(); } catch (Throwable ignored) {}
        try { if (rightSession != null) rightSession.close(); } catch (Throwable ignored) {}
        try { if (leftCamera != null) leftCamera.close(); } catch (Throwable ignored) {}
        try { if (rightCamera != null) rightCamera.close(); } catch (Throwable ignored) {}
        leftSession = null;
        rightSession = null;
        leftCamera = null;
        rightCamera = null;
        leftReady = false;
        rightReady = false;
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.length() == 0 ? t.getClass().getSimpleName() : m;
    }
}
