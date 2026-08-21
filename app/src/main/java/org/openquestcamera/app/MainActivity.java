package org.openquestcamera.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1001;
    private static final int REQ_AUDIO = 1002;
    private static final String HZOS_CAMERA_PERMISSION = "horizonos.permission.HEADSET_CAMERA";
    private static final String PREFS = "openquestcamera_0_1";

    private SharedPreferences prefs;
    private SurfaceView previewSurfaceView;
    private StereoGuideOverlay guideOverlay;
    private TextView statusView;
    private TextView hudView;
    private TextView bitrateLabel;
    private TextView convergenceLabel;
    private TextView verticalLabel;
    private Spinner resolutionSpinner;
    private Spinner fpsSpinner;
    private Spinner codecSpinner;
    private Spinner gridSpinner;
    private SeekBar bitrateSeek;
    private SeekBar convergenceSeek;
    private SeekBar verticalSeek;
    private CheckBox micCheck;
    private CheckBox saveOriginalsCheck;
    private CheckBox previewCheck;
    private CheckBox dimCheck;
    private CheckBox swapCheck;
    private Button photoButton;
    private Button startButton;
    private Button stopButton;
    private Button resetCalButton;

    private CameraModels.Pair cameraPair;
    private volatile StereoCameraEngine cameraEngine;
    private volatile VideoRecorder recorder;
    private boolean engineReady;
    private boolean photoBusy;
    private boolean pendingStartAfterAudioPermission;
    private boolean uiUpdating;
    private int engineGeneration;
    private Surface previewSurface;
    private int previewSurfaceWidth;
    private int previewSurfaceHeight;

    private PowerManager.WakeLock wakeLock;
    private SensorManager sensorManager;
    private SensorEventListener levelListener;
    private final Handler uiHandler = new Handler();
    private long recordingStartElapsed;
    private volatile double lastSyncMs;
    private volatile double lastActualFps;
    private volatile int lastDropped;
    private volatile float lastRoll;

    private final Runnable restartRunnable = new Runnable() {
        @Override public void run() { restartCameraEngine(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        setupLevelSensor();
        startHudLoop();
        ensureCameraPermissions();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(16, 16, 18));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(10), dp(18), dp(22));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        int contentWidth = Math.min(dp(900), getResources().getDisplayMetrics().widthPixels - dp(36));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(contentWidth, dp(380));
        previewLp.setMargins(0, 0, 0, 0);
        root.addView(previewFrame, previewLp);

        previewSurfaceView = new SurfaceView(this);
        previewSurfaceView.setZOrderOnTop(false);
        previewFrame.addView(previewSurfaceView, new FrameLayout.LayoutParams(-1, -1));
        guideOverlay = new StereoGuideOverlay(this);
        guideOverlay.setGridMode(clamp(prefs.getInt("grid", 0), 0, 2));
        previewFrame.addView(guideOverlay, new FrameLayout.LayoutParams(-1, -1));

        previewSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                previewSurface = holder.getSurface();
                applyPreviewSurface();
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                previewSurface = holder.getSurface();
                previewSurfaceWidth = width;
                previewSurfaceHeight = height;
                applyPreviewSurface();
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                previewSurface = null;
                StereoCameraEngine engine = cameraEngine;
                if (engine != null) engine.setPreviewSurface(null, 1, 1, false);
            }
        });

        statusView = label(getString(R.string.status_initializing), 14);
        statusView.setTextColor(Color.WHITE);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusView.setBackgroundColor(Color.rgb(30, 30, 34));
        root.addView(statusView, new LinearLayout.LayoutParams(contentWidth, -2));

        hudView = label(getString(R.string.hud_placeholder), 12);
        hudView.setTextColor(Color.rgb(235, 235, 238));
        hudView.setGravity(Gravity.CENTER_VERTICAL);
        hudView.setPadding(dp(10), dp(7), dp(10), dp(7));
        hudView.setBackgroundColor(Color.rgb(23, 23, 26));
        hudView.setMaxLines(2);
        LinearLayout.LayoutParams hudLp = new LinearLayout.LayoutParams(contentWidth, -2);
        hudLp.setMargins(0, dp(3), 0, dp(8));
        root.addView(hudView, hudLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(contentWidth, dp(58));
        actionsLp.setMargins(0, 0, 0, dp(8));
        root.addView(actions, actionsLp);

        photoButton = actionButton(getString(R.string.action_photo));
        startButton = actionButton(getString(R.string.action_record));
        stopButton = actionButton(getString(R.string.action_stop));
        stopButton.setEnabled(false);
        addAction(actions, photoButton);
        addAction(actions, startButton);
        addAction(actions, stopButton);

        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(18), dp(8), dp(18), dp(12));
        root.addView(settings, new LinearLayout.LayoutParams(contentWidth, -2));

        settings.addView(label(getString(R.string.setting_resolution), 14));
        resolutionSpinner = new Spinner(this);
        settings.addView(resolutionSpinner, new LinearLayout.LayoutParams(-1, dp(52)));

        settings.addView(label(getString(R.string.setting_frame_rate), 14));
        fpsSpinner = new Spinner(this);
        settings.addView(fpsSpinner, new LinearLayout.LayoutParams(-1, dp(52)));

        bitrateLabel = label(getString(R.string.setting_bitrate, 24), 14);
        settings.addView(bitrateLabel);
        bitrateSeek = new SeekBar(this);
        bitrateSeek.setMax(99);
        bitrateSeek.setProgress(clamp(prefs.getInt("bitrate", 24), 1, 100) - 1);
        settings.addView(bitrateSeek, new LinearLayout.LayoutParams(-1, dp(42)));

        settings.addView(label(getString(R.string.setting_encoding), 14));
        codecSpinner = new Spinner(this);
        codecSpinner.setAdapter(adapter(new String[]{"H.264", "H.265"}));
        codecSpinner.setSelection(prefs.getString("codec", "video/avc").equals("video/hevc") ? 1 : 0);
        settings.addView(codecSpinner, new LinearLayout.LayoutParams(-1, dp(52)));

        settings.addView(label(getString(R.string.setting_guides), 14));
        gridSpinner = new Spinner(this);
        gridSpinner.setAdapter(adapter(new String[]{getString(R.string.guide_off), getString(R.string.guide_thirds), getString(R.string.guide_crosshair)}));
        gridSpinner.setSelection(clamp(prefs.getInt("grid", 0), 0, 2));
        settings.addView(gridSpinner, new LinearLayout.LayoutParams(-1, dp(52)));

        micCheck = checkbox(getString(R.string.option_microphone), prefs.getBoolean("mic", true));
        saveOriginalsCheck = checkbox(getString(R.string.option_save_originals), prefs.getBoolean("saveOriginals", false));
        previewCheck = checkbox(getString(R.string.option_preview), prefs.getBoolean("preview", true));
        dimCheck = checkbox(getString(R.string.option_dim_recording), prefs.getBoolean("dimDuringRecord", false));
        swapCheck = checkbox(getString(R.string.option_swap_eyes), prefs.getBoolean("swap", false));
        settings.addView(micCheck);
        settings.addView(saveOriginalsCheck);
        settings.addView(previewCheck);
        settings.addView(dimCheck);
        settings.addView(swapCheck);

        convergenceLabel = label(getString(R.string.setting_convergence, "0"), 14);
        settings.addView(convergenceLabel);
        convergenceSeek = new SeekBar(this);
        convergenceSeek.setMax(128);
        convergenceSeek.setProgress(clamp(prefs.getInt("conv", 0), -64, 64) + 64);
        settings.addView(convergenceSeek, new LinearLayout.LayoutParams(-1, dp(42)));

        verticalLabel = label(getString(R.string.setting_vertical, "0"), 14);
        settings.addView(verticalLabel);
        verticalSeek = new SeekBar(this);
        verticalSeek.setMax(64);
        verticalSeek.setProgress(clamp(prefs.getInt("vert", 0), -32, 32) + 32);
        settings.addView(verticalSeek, new LinearLayout.LayoutParams(-1, dp(42)));

        resetCalButton = new Button(this);
        resetCalButton.setText(R.string.action_reset);
        settings.addView(resetCalButton, new LinearLayout.LayoutParams(dp(260), dp(48)));

        resolutionSpinner.setAdapter(adapter(new String[]{getString(R.string.status_detecting)}));
        fpsSpinner.setAdapter(adapter(new String[]{getString(R.string.fps_value, 30)}));
        updateBitrateLabel();
        updateCalibrationLabels();
        setContentView(scroll);
        installListeners();
        updatePreviewVisibility();
        updateHud();
    }

    private void installListeners() {
        bitrateSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) { updateBitrateLabel(); updateHud(); }
            @Override public void onStopTrackingTouch(SeekBar bar) { prefs.edit().putInt("bitrate", selectedBitrateMbps()).apply(); }
        });
        convergenceSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) { updateCalibrationLabels(); applyCalibration(); }
            @Override public void onStopTrackingTouch(SeekBar bar) { prefs.edit().putInt("conv", getConvergence()).apply(); }
        });
        verticalSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) { updateCalibrationLabels(); applyCalibration(); }
            @Override public void onStopTrackingTouch(SeekBar bar) { prefs.edit().putInt("vert", getVertical()).apply(); }
        });
        resetCalButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { convergenceSeek.setProgress(64); verticalSeek.setProgress(32); }
        });
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!uiUpdating) {
                    updateFpsChoices();
                    scheduleEngineRestart();
                }
                updateHud();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!uiUpdating) {
                    prefs.edit().putInt("fps", selectedFps()).apply();
                    scheduleEngineRestart();
                }
                updateHud();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        codecSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!uiUpdating) {
                    prefs.edit().putString("codec", selectedCodec()).apply();
                    updateFpsChoices();
                    scheduleEngineRestart();
                }
                updateHud();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        gridSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                guideOverlay.setGridMode(position);
                prefs.edit().putInt("grid", position).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        micCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) { prefs.edit().putBoolean("mic", checked).apply(); updateHud(); }
        });
        saveOriginalsCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) { prefs.edit().putBoolean("saveOriginals", checked).apply(); }
        });
        previewCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.edit().putBoolean("preview", checked).apply();
                updatePreviewVisibility();
                StereoCameraEngine engine = cameraEngine;
                if (engine != null) {
                    engine.setPreviewEnabled(checked);
                    applyPreviewSurface();
                }
            }
        });
        dimCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) { prefs.edit().putBoolean("dimDuringRecord", checked).apply(); }
        });
        swapCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) { prefs.edit().putBoolean("swap", checked).apply(); applyCalibration(); }
        });
        photoButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { capturePhoto(); }
        });
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startRecording(); }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopRecording(); }
        });
    }

    private TextView label(String text, int sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(218, 218, 222));
        return view;
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(17f);
        button.setEnabled(false);
        return button;
    }

    private void addAction(LinearLayout parent, View child) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(child, lp);
    }

    private CheckBox checkbox(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(Color.WHITE);
        box.setChecked(checked);
        return box;
    }

    private ArrayAdapter<String> adapter(String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private static abstract class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }

    private void ensureCameraPermissions() {
        ArrayList<String> missing = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(HZOS_CAMERA_PERMISSION) != PackageManager.PERMISSION_GRANTED) missing.add(HZOS_CAMERA_PERMISSION);
        if (!missing.isEmpty()) {
            statusView.setText(R.string.status_camera_permission_required);
            requestPermissions(missing.toArray(new String[missing.size()]), REQ_CAMERA);
        } else inspectCameras();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean ok = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(HZOS_CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED;
            if (ok) inspectCameras(); else statusView.setText(R.string.status_camera_permission_missing);
        } else if (requestCode == REQ_AUDIO && pendingStartAfterAudioPermission) {
            pendingStartAfterAudioPermission = false;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording();
            else {
                micCheck.setChecked(false);
                statusView.setText(R.string.status_microphone_permission_denied);
            }
        }
    }

    private void inspectCameras() {
        statusView.setText(R.string.status_finding_cameras);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final CameraModels.Pair pair = CameraModels.Pair.discover(MainActivity.this);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            cameraPair = pair;
                            if (pair == null || pair.modes.isEmpty()) {
                                statusView.setText(R.string.status_no_stereo_mode);
                            } else {
                                populateModes(pair);
                                scheduleEngineRestart();
                            }
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { statusView.setText(getString(R.string.error_initialization, shortMessage(t))); }
                    });
                }
            }
        }, "camera-discovery").start();
    }

    private void populateModes(CameraModels.Pair pair) {
        uiUpdating = true;
        String[] labels = new String[pair.modes.size()];
        for (int i = 0; i < pair.modes.size(); i++) labels[i] = pair.modes.get(i).label(this);
        resolutionSpinner.setAdapter(adapter(labels));
        int wantedW = prefs.getInt("width", 1280);
        int wantedH = prefs.getInt("height", 1280);
        int selection = 0;
        for (int i = 0; i < pair.modes.size(); i++) {
            CameraModels.Mode mode = pair.modes.get(i);
            if (mode.width == wantedW && mode.height == wantedH) { selection = i; break; }
        }
        resolutionSpinner.setSelection(selection);
        uiUpdating = false;
        updateFpsChoices();
    }

    private CameraModels.Mode selectedMode() {
        if (cameraPair == null || cameraPair.modes.isEmpty()) return null;
        int i = resolutionSpinner.getSelectedItemPosition();
        if (i < 0 || i >= cameraPair.modes.size()) i = 0;
        return cameraPair.modes.get(i);
    }

    private void updateFpsChoices() {
        CameraModels.Mode mode = selectedMode();
        if (mode == null) return;
        uiUpdating = true;
        ArrayList<String> values = new ArrayList<String>();
        for (int fps : CameraModels.FPS_CANDIDATES) if (mode.supports(fps, selectedCodec())) values.add(getString(R.string.fps_value, fps));
        if (values.isEmpty()) values.add(getString(R.string.status_unavailable));
        fpsSpinner.setAdapter(adapter(values.toArray(new String[values.size()])));
        int wanted = prefs.getInt("fps", 30);
        int selection = 0;
        for (int i = 0; i < values.size(); i++) if (values.get(i).startsWith(wanted + " ")) selection = i;
        fpsSpinner.setSelection(selection);
        uiUpdating = false;
    }

    private void scheduleEngineRestart() {
        if (recorder != null && recorder.isRecording()) return;
        uiHandler.removeCallbacks(restartRunnable);
        uiHandler.postDelayed(restartRunnable, 120);
    }

    private void restartCameraEngine() {
        if (cameraPair == null || selectedMode() == null) return;
        final CameraModels.RecorderConfig config;
        try { config = collectConfig(); }
        catch (Throwable t) { statusView.setText(shortMessage(t)); return; }
        final int generation = ++engineGeneration;
        final StereoCameraEngine old = cameraEngine;
        cameraEngine = null;
        engineReady = false;
        updateActionAvailability();
        statusView.setText(R.string.status_starting_camera);

        new Thread(new Runnable() {
            @Override public void run() {
                if (old != null) try { old.stop(); } catch (Throwable ignored) {}
                final StereoCameraEngine next = new StereoCameraEngine(MainActivity.this, cameraPair, config, new StereoCameraEngine.Listener() {
                    @Override public void onReady() {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (generation != engineGeneration) return;
                                engineReady = true;
                                statusView.setText(R.string.status_ready);
                                applyPreviewSurface();
                                updateActionAvailability();
                            }
                        });
                    }

                    @Override public void onStats(double actualFps, int dropped, double syncMs) {
                        if (generation == engineGeneration) {
                            lastActualFps = actualFps;
                            lastDropped = dropped;
                            lastSyncMs = syncMs;
                        }
                    }

                    @Override public void onFatal(final String message) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (generation == engineGeneration) {
                                    engineReady = false;
                                    statusView.setText(message);
                                    updateActionAvailability();
                                }
                            }
                        });
                    }
                });
                try {
                    next.start();
                    next.setCalibration(config.swap, config.convergencePx, config.verticalPx);
                    if (generation != engineGeneration) {
                        next.stop();
                        return;
                    }
                    cameraEngine = next;
                    runOnUiThread(new Runnable() {
                        @Override public void run() { applyPreviewSurface(); }
                    });
                } catch (final Throwable t) {
                    try { next.stop(); } catch (Throwable ignored) {}
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (generation == engineGeneration) statusView.setText(getString(R.string.error_camera_start, shortMessage(t)));
                        }
                    });
                }
            }
        }, "camera-engine-restart").start();
    }

    private CameraModels.RecorderConfig collectConfig() {
        CameraModels.Mode mode = selectedMode();
        if (mode == null) throw new IllegalStateException(getString(R.string.error_no_available_mode));
        int fps = selectedFps();
        String codec = selectedCodec();
        if (fps <= 0 || !mode.supports(fps, codec)) throw new IllegalStateException(getString(R.string.error_mode_unavailable));
        CameraModels.RecorderConfig config = new CameraModels.RecorderConfig(
                mode.width, mode.height, fps, selectedBitrateMbps() * 1_000_000, codec,
                micCheck.isChecked(), previewCheck.isChecked(), swapCheck.isChecked(), saveOriginalsCheck.isChecked(),
                getConvergence(), getVertical());
        prefs.edit().putInt("width", config.width).putInt("height", config.height).putInt("fps", config.fps)
                .putString("codec", config.codec).putInt("bitrate", selectedBitrateMbps())
                .putBoolean("mic", config.mic).putBoolean("preview", config.preview)
                .putBoolean("swap", config.swap).putBoolean("saveOriginals", config.saveOriginals)
                .putInt("conv", config.convergencePx).putInt("vert", config.verticalPx)
                .putBoolean("dimDuringRecord", dimCheck.isChecked()).apply();
        return config;
    }

    private void applyPreviewSurface() {
        StereoCameraEngine engine = cameraEngine;
        if (engine == null) return;
        boolean enabled = previewCheck.isChecked() && previewSurface != null && previewSurface.isValid();
        engine.setPreviewSurface(enabled ? previewSurface : null,
                Math.max(1, previewSurfaceWidth), Math.max(1, previewSurfaceHeight), enabled);
    }

    private void applyCalibration() {
        StereoCameraEngine engine = cameraEngine;
        if (engine != null) engine.setCalibration(swapCheck.isChecked(), getConvergence(), getVertical());
    }

    private void startRecording() {
        if (!engineReady || recorder != null) return;
        CameraModels.RecorderConfig config;
        try { config = collectConfig(); }
        catch (Throwable t) { statusView.setText(shortMessage(t)); return; }
        if (config.mic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterAudioPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        beginRecording();
    }

    private void beginRecording() {
        final StereoCameraEngine engine = cameraEngine;
        if (engine == null || !engineReady || recorder != null) return;
        final CameraModels.RecorderConfig config;
        try { config = collectConfig(); }
        catch (Throwable t) { statusView.setText(shortMessage(t)); return; }
        statusView.setText(R.string.status_starting_recording);
        setControlsEnabled(false);
        acquireWake(config.mic);
        if (dimCheck.isChecked()) setScreenDim(true);
        final VideoRecorder next = new VideoRecorder(this, config);
        recorder = next;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    next.start();
                    engine.attachRecorder(next);
                    next.startAudio();
                    recordingStartElapsed = SystemClock.elapsedRealtime();
                    lastActualFps = 0;
                    lastDropped = 0;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            statusView.setText(R.string.status_recording);
                            stopButton.setEnabled(true);
                        }
                    });
                } catch (final Throwable t) {
                    try { engine.detachRecorder(); } catch (Throwable ignored) {}
                    try { next.stop(true); } catch (Throwable ignored) {}
                    recorder = null;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setScreenDim(false);
                            releaseWake();
                            setControlsEnabled(true);
                            statusView.setText(getString(R.string.error_recording_start, shortMessage(t)));
                            updateActionAvailability();
                        }
                    });
                }
            }
        }, "record-start").start();
    }

    private void stopRecording() {
        final VideoRecorder current = recorder;
        final StereoCameraEngine engine = cameraEngine;
        if (current == null || engine == null) return;
        stopButton.setEnabled(false);
        statusView.setText(R.string.status_saving);
        new Thread(new Runnable() {
            @Override public void run() {
                try { engine.detachRecorder(); } catch (Throwable ignored) {}
                final String result = current.stop(false);
                recorder = null;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setScreenDim(false);
                        releaseWake();
                        setControlsEnabled(true);
                        lastActualFps = 0;
                        lastSyncMs = 0;
                        statusView.setText(result);
                        updateActionAvailability();
                    }
                });
            }
        }, "record-stop").start();
    }

    private void capturePhoto() {
        final StereoCameraEngine engine = cameraEngine;
        if (engine == null || !engineReady || photoBusy || recorder != null) return;
        photoBusy = true;
        updateActionAvailability();
        statusView.setText(R.string.status_taking_photo);
        acquireLocalWake();
        engine.capturePhoto(saveOriginalsCheck.isChecked(), new StereoCameraEngine.PhotoResultCallback() {
            @Override public void onPhoto(final Bitmap sbs, final Bitmap left, final Bitmap right, long timestampNs) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        String result;
                        try {
                            savePhotoSet(sbs, left, right);
                            result = getString(R.string.status_photo_saved);
                        } catch (Throwable t) {
                            result = getString(R.string.error_photo_save, shortMessage(t));
                        } finally {
                            try { if (sbs != null) sbs.recycle(); } catch (Throwable ignored) {}
                            try { if (left != null) left.recycle(); } catch (Throwable ignored) {}
                            try { if (right != null) right.recycle(); } catch (Throwable ignored) {}
                        }
                        final String message = result;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                releaseWake();
                                photoBusy = false;
                                statusView.setText(message);
                                updateActionAvailability();
                            }
                        });
                    }
                }, "photo-save").start();
            }

            @Override public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        releaseWake();
                        photoBusy = false;
                        statusView.setText(message);
                        updateActionAvailability();
                    }
                });
            }
        });
    }

    private void savePhotoSet(Bitmap sbs, Bitmap left, Bitmap right) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        saveJpeg(sbs, "OpenQuestCamera_3D_" + stamp + "_SBS.jpg");
        if (left != null && right != null) {
            saveJpeg(left, "OpenQuestCamera_3D_" + stamp + "_L.jpg");
            saveJpeg(right, "OpenQuestCamera_3D_" + stamp + "_R.jpg");
        }
    }

    private void saveJpeg(Bitmap bitmap, String name) throws Exception {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OpenQuestCamera");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException(getString(R.string.error_photo_create));
        boolean ok = false;
        OutputStream out = null;
        try {
            out = resolver.openOutputStream(uri);
            if (out == null) throw new IllegalStateException(getString(R.string.error_photo_write));
            ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
        if (!ok) {
            try { resolver.delete(uri, null, null); } catch (Throwable ignored) {}
            throw new IllegalStateException(getString(R.string.error_jpeg_encode));
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
    }

    private void setControlsEnabled(boolean enabled) {
        resolutionSpinner.setEnabled(enabled);
        fpsSpinner.setEnabled(enabled);
        bitrateSeek.setEnabled(enabled);
        codecSpinner.setEnabled(enabled);
        micCheck.setEnabled(enabled);
        saveOriginalsCheck.setEnabled(enabled);
        dimCheck.setEnabled(enabled);
        previewCheck.setEnabled(true);
        gridSpinner.setEnabled(true);
        swapCheck.setEnabled(true);
        convergenceSeek.setEnabled(true);
        verticalSeek.setEnabled(true);
        resetCalButton.setEnabled(true);
        updateActionAvailability();
    }

    private void updateActionAvailability() {
        boolean recording = recorder != null;
        startButton.setEnabled(engineReady && !recording && !photoBusy);
        photoButton.setEnabled(engineReady && !recording && !photoBusy);
        stopButton.setEnabled(recording && recorder.isRecording());
    }

    private void updatePreviewVisibility() {
        boolean visible = previewCheck != null && previewCheck.isChecked();
        previewSurfaceView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        guideOverlay.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    private void setupLevelSensor() {
        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            final Sensor gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            levelListener = new SensorEventListener() {
                @Override public void onSensorChanged(SensorEvent event) {
                    if (event.values == null || event.values.length < 2) return;
                    lastRoll = (float) Math.toDegrees(Math.atan2(event.values[0], event.values[1]));
                    guideOverlay.setLevelRoll(lastRoll);
                }
                @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };
            if (gravity != null) sensorManager.registerListener(levelListener, gravity, SensorManager.SENSOR_DELAY_UI);
        } catch (Throwable ignored) {}
    }

    private void startHudLoop() {
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                updateHud();
                uiHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void updateHud() {
        if (hudView == null) return;
        int battery = -1;
        try {
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                int level = intent.getIntExtra("level", -1);
                int scale = intent.getIntExtra("scale", 100);
                battery = scale > 0 ? level * 100 / scale : -1;
            }
        } catch (Throwable ignored) {}
        long free = 0;
        try {
            StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
            free = stat.getAvailableBytes();
        } catch (Throwable ignored) {}
        int mbps = selectedBitrateMbps();
        long remainSeconds = mbps > 0 ? free * 8L / (mbps * 1_000_000L) : 0;
        String remain = getString(R.string.hud_remaining_time, remainSeconds / 3600, (remainSeconds % 3600) / 60);
        CameraModels.Mode mode = selectedMode();
        String resolution = mode == null ? "SBS --×--" : "SBS " + (mode.width * 2) + "×" + mode.height;
        String codec = selectedCodec().equals("video/hevc") ? "H.265" : "H.264";
        int fps = selectedFps();
        String mic = getString(micCheck != null && micCheck.isChecked() ? R.string.hud_microphone : R.string.hud_muted);
        String fpsLabel = fps > 0 ? getString(R.string.fps_value, fps) : "-- FPS";
        String spec = getString(R.string.hud_spec, resolution, fpsLabel, codec, mbps, mic);
        boolean recording = recorder != null && recorder.isRecording();
        String elapsed = recording ? "● " + formatElapsed(SystemClock.elapsedRealtime() - recordingStartElapsed) : getString(R.string.hud_standby);
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String actual = lastActualFps > 0.05 ? String.format(locale, "%.1f", lastActualFps) : "--";
        String sync = lastSyncMs > 0 ? String.format(locale, "%.2f", lastSyncMs) : "--";
        String batteryValue = battery < 0 ? "--" : Integer.toString(battery);
        String storage = String.format(locale, "%.1f", free / 1073741824.0);
        String roll = String.format(locale, "%+.1f°", lastRoll);
        String run = getString(R.string.hud_runtime, elapsed, batteryValue, storage, remain, actual, lastDropped, sync, roll);
        hudView.setText(getString(R.string.hud_display, spec, run));
    }

    private int getConvergence() { return convergenceSeek.getProgress() - 64; }
    private int getVertical() { return verticalSeek.getProgress() - 32; }
    private int selectedBitrateMbps() { return bitrateSeek == null ? 24 : bitrateSeek.getProgress() + 1; }
    private String selectedCodec() { return codecSpinner != null && codecSpinner.getSelectedItemPosition() == 1 ? "video/hevc" : "video/avc"; }
    private int selectedFps() {
        Object item = fpsSpinner == null ? null : fpsSpinner.getSelectedItem();
        if (item == null) return 0;
        try { return Integer.parseInt(String.valueOf(item).split(" ")[0]); }
        catch (Throwable ignored) { return 0; }
    }

    private void updateBitrateLabel() { if (bitrateLabel != null) bitrateLabel.setText(getString(R.string.setting_bitrate, selectedBitrateMbps())); }
    private void updateCalibrationLabels() {
        convergenceLabel.setText(getString(R.string.setting_convergence, signed(getConvergence())));
        verticalLabel.setText(getString(R.string.setting_vertical, signed(getVertical())));
    }
    private String signed(int v) { return v > 0 ? "+" + v : Integer.toString(v); }

    private void acquireLocalWake() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenQuestCamera:record");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void acquireWake(boolean microphone) {
        acquireLocalWake();
        try {
            Intent service = new Intent(this, CameraKeepAliveService.class).putExtra("microphone", microphone);
            startForegroundService(service);
        } catch (Throwable ignored) {}
    }

    private void releaseWake() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        try { stopService(new Intent(this, CameraKeepAliveService.class)); } catch (Throwable ignored) {}
    }

    private void setScreenDim(boolean dim) {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = dim ? 0.03f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) {}
    }

    @Override protected void onPause() {
        super.onPause();
        if (recorder == null) {
            ++engineGeneration;
            StereoCameraEngine old = cameraEngine;
            cameraEngine = null;
            engineReady = false;
            if (old != null) new Thread(new Runnable() {
                @Override public void run() { old.stop(); }
            }, "camera-pause-stop").start();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (cameraPair != null && recorder == null && cameraEngine == null) scheduleEngineRestart();
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        setScreenDim(false);
        VideoRecorder current = recorder;
        recorder = null;
        StereoCameraEngine engine = cameraEngine;
        cameraEngine = null;
        if (engine != null) try { engine.detachRecorder(); } catch (Throwable ignored) {}
        if (current != null) try { current.stop(false); } catch (Throwable ignored) {}
        if (engine != null) try { engine.stop(); } catch (Throwable ignored) {}
        releaseWake();
        try { if (sensorManager != null && levelListener != null) sensorManager.unregisterListener(levelListener); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private String formatElapsed(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
    private static String shortMessage(Throwable t) {
        String message = t.getMessage();
        return message == null || message.length() == 0 ? t.getClass().getSimpleName() : message;
    }
}
