package com.example.choptorch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home, feedback, and optional personalized training page. Background sensing runs in a Service. */
public final class MainActivity extends Activity implements SensorEventListener {
    private static final int CAMERA_REQUEST = 31;
    private static final int EXPORT_REQUEST = 32;
    private static final int NOTIFICATION_REQUEST = 33;
    private static final int BATTERY_SETTINGS_REQUEST = 34;
    private static final String STATE_MONITORING_START = "monitoring_start_state";
    private static final String STATE_BATTERY_ALLOWLISTED = "battery_allowlisted_on_return";
    private static final String STATE_PERMISSION_ACTION = "monitoring_permission_action";
    private static final long WINDOW_MS = GestureDetector.WINDOW_MS;
    private static final int BG = 0xFF0A1220;
    private static final int CARD = 0xFF142235;
    private static final int MINT = 0xFF66E0C7;
    private static final int WHITE = 0xFFF0F6FC;
    private static final int MUTED = 0xFFACBCCB;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService trainingWorker = Executors.newSingleThreadExecutor();
    private final MotionProcessor recordingMotion = new MotionProcessor();
    private final MonitoringStartFlow monitoringStartFlow = new MonitoringStartFlow();
    private SensorManager sensorManager;
    private Sensor accelerometer, gyroscope;
    private GestureStore store;
    private TorchController torch;
    private boolean onTrainingPage, preparing, recording, training, resumed, sensorRegistered;
    private boolean sensorsReady;
    private int permissionAction; // 1 manual torch; 2 background detection
    private ArrayList<MotionSample> recordingSamples;
    private GestureFeatures.Result pendingResult;
    private Runnable prepareTask, finishTask;
    private AlertDialog batteryDialog;

    private LinearLayout page;
    private TextView statusText, countText, hintText, torchText, scoreText, feedbackText;
    private Button listenButton, captureButton, trainButton, falsePositiveButton;
    private Button positiveButton, negativeButton, discardButton;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            updateLabels();
            handler.postDelayed(this, 650);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            monitoringStartFlow.restoreState(savedInstanceState.getInt(STATE_MONITORING_START),
                    savedInstanceState.getBoolean(STATE_BATTERY_ALLOWLISTED));
            permissionAction = savedInstanceState.getInt(STATE_PERMISSION_ACTION);
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        store = GestureStore.shared(this);
        torch = new TorchController(this, on -> {
            if (torchText != null) torchText.setText(on ? "FLASHLIGHT ON" : "FLASHLIGHT OFF");
        });
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorsReady = accelerometer != null && gyroscope != null;
        showHome();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_MONITORING_START, monitoringStartFlow.saveState());
        outState.putBoolean(STATE_BATTERY_ALLOWLISTED, monitoringStartFlow.saveAllowlistedOnReturn());
        outState.putInt(STATE_PERMISSION_ACTION, permissionAction);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try { torch.initializeAfterPermission(); } catch (Exception ignored) { }
        }
        syncTrainingSensors();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        restorePendingStartPrompt();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refresh);
        cancelRecording();
        unregisterTrainingSensors();
        // Intentionally DO NOT stop the foreground service. It owns background sensors.
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (onTrainingPage) showHome();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        cancelRecording();
        torch.release();
        trainingWorker.shutdown();
        super.onDestroy();
    }

    private void syncTrainingSensors() {
        if (!resumed || !onTrainingPage || !BuildConfig.ENABLE_TRAINING || !sensorsReady || sensorRegistered) return;
        recordingMotion.reset();
        boolean acc = sensorManager.registerListener(this, accelerometer, 20000);
        boolean gyro = sensorManager.registerListener(this, gyroscope, 20000);
        sensorRegistered = acc && gyro;
        if (!sensorRegistered) sensorManager.unregisterListener(this);
    }

    private void unregisterTrainingSensors() {
        if (sensorRegistered) sensorManager.unregisterListener(this);
        sensorRegistered = false;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    @Override public void onSensorChanged(SensorEvent event) {
        if (!sensorRegistered || !recording) return;
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            recordingMotion.onGyroscope(event.values[0], event.values[1], event.values[2]);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER && recordingSamples != null) {
            recordingSamples.add(recordingMotion.onAccelerometer(event.timestamp,
                    event.values[0], event.values[1], event.values[2]));
        }
    }

    private void showHome() {
        cancelRecording();
        unregisterTrainingSensors();
        pendingResult = null;
        onTrainingPage = false;
        page = freshPage();
        heading("ChopTorch", "Two chops. One flashlight.");

        LinearLayout lightCard = card();
        lightCard.addView(text("FLASHLIGHT", 12, MINT, true));
        torchText = spacedText("FLASHLIGHT OFF", 18, WHITE, 8);
        lightCard.addView(torchText);
        lightCard.addView(spacedText("Quickly chop your phone twice to toggle the torch.", 15, WHITE, 8));
        lightCard.addView(button("Toggle flashlight manually", false, () -> askForCamera(1)));

        LinearLayout detectionCard = card();
        detectionCard.addView(text("BACKGROUND GESTURE DETECTOR", 12, MINT, true));
        statusText = spacedText("", 18, WHITE, 12);
        detectionCard.addView(statusText);
        scoreText = spacedText("", 14, MUTED, 8);
        detectionCard.addView(scoreText);
        listenButton = button("Start background detection", true, this::changeListening);
        detectionCard.addView(listenButton);
        detectionCard.addView(spacedText("Works while you use other apps or lock the screen, when Android permits. A persistent notification lets you stop it. Continuous monitoring uses battery.", 13, MUTED, 12));

        if (BuildConfig.ENABLE_TRAINING) {
            LinearLayout feedbackCard = card();
            feedbackCard.addView(text("CORRECT A FALSE POSITIVE", 12, MINT, true));
            feedbackCard.addView(spacedText("If a double-chop detection accidentally switched the flashlight ON, flag it within 10 minutes. The exact triggering IMU features will be labeled NOT chop, and the model will retrain automatically.", 14, WHITE, 8));
            falsePositiveButton = button("Flag last ON as false positive", false, this::flagFalsePositive);
            feedbackCard.addView(falsePositiveButton);
            feedbackText = spacedText("", 13, MUTED, 10);
            feedbackCard.addView(feedbackText);

            LinearLayout trainCard = card();
            trainCard.addView(text("PERSONALIZE AND EXPORT", 12, MINT, true));
            countText = spacedText("", 16, WHITE, 8);
            trainCard.addView(countText);
            trainCard.addView(button("Open training page", false, this::showTraining));
            trainCard.addView(button("Export trained NN weights (JSON)", false, this::exportModel));
            trainCard.addView(spacedText("Exported JSON can be bundled as app/src/main/assets/choptorch_model.json for a shipping build.", 13, MUTED, 10));
        }
        updateLabels();
    }

    private void showTraining() {
        if (!BuildConfig.ENABLE_TRAINING) return;
        if (GestureDetectionService.isRunning()) {
            startService(GestureDetectionService.command(this, GestureDetectionService.ACTION_STOP));
        }
        onTrainingPage = true;
        page = freshPage();
        page.addView(button("‹  Back to flashlight", false, this::showHome));
        heading("Teach the gesture", "Capture. Confirm. Train. Export.");
        syncTrainingSensors();

        LinearLayout explanation = card();
        explanation.addView(text("RECORD EXAMPLES", 12, MINT, true));
        explanation.addView(spacedText("Hold your PHONE firmly, tap Record, then make two quick downward chopping movements when you see MOVE NOW.", 16, WHITE, 8));
        explanation.addView(spacedText("Also record walking, pocket movements, shaking and single chops; label these NOT double chop. Do not use an actual knife.", 14, MUTED, 12));
        captureButton = button("Record a 2-second movement", true, this::beginRecording);
        explanation.addView(captureButton);
        statusText = spacedText("Ready to record", 15, MINT, 12);
        explanation.addView(statusText);

        LinearLayout confirm = card();
        confirm.addView(text("CONFIRM MOVEMENT", 12, MINT, true));
        hintText = spacedText("After recording, choose the correct label.", 14, MUTED, 10);
        confirm.addView(hintText);
        positiveButton = button("✓ Yes — two chops", true, () -> confirmRecording(true));
        negativeButton = button("No — another movement", false, () -> confirmRecording(false));
        discardButton = button("Discard recording", false, this::discardRecording);
        confirm.addView(positiveButton);
        confirm.addView(negativeButton);
        confirm.addView(discardButton);

        LinearLayout trainingCard = card();
        trainingCard.addView(text("TRAIN AND EXPORT", 12, MINT, true));
        countText = spacedText("", 17, WHITE, 10);
        trainingCard.addView(countText);
        trainingCard.addView(spacedText("Minimum 6 positive and 6 negative examples. Aim for 20+ of each plus false-positive feedback.", 14, MUTED, 8));
        trainButton = button("Train neural network", true, this::beginTraining);
        trainingCard.addView(trainButton);
        trainingCard.addView(button("Export weights as JSON", false, this::exportModel));
        trainingCard.addView(button("Erase all examples and model", false, this::confirmErase));
        trainingCard.addView(spacedText("To ship without a training page: put the exported JSON into app/src/main/assets/choptorch_model.json and build with -Pshipping=true.", 14, MUTED, 12));
        updateLabels();
    }

    private void beginRecording() {
        if (!sensorRegistered || training || preparing || recording) {
            showToast(sensorRegistered ? "Finish the current task first." : "Accelerometer and gyroscope required.");
            return;
        }
        pendingResult = null;
        preparing = true;
        updateLabels();
        statusText.setText(getString(R.string.recording_ready));
        prepareTask = () -> {
            if (!preparing) return;
            preparing = false;
            recording = true;
            recordingMotion.reset();
            recordingSamples = new ArrayList<>();
            statusText.setText(getString(R.string.recording_move));
            updateLabels();
            finishTask = this::finishRecording;
            handler.postDelayed(finishTask, WINDOW_MS);
        };
        handler.postDelayed(prepareTask, 850);
    }

    private void finishRecording() {
        if (!recording) return;
        recording = false;
        List<MotionSample> samples = recordingSamples;
        recordingSamples = null;
        pendingResult = GestureFeatures.extract(samples);
        if (pendingResult == null || samples.isEmpty() ||
                samples.get(samples.size() - 1).timeMs - samples.get(0).timeMs < 1300) {
            pendingResult = null;
            statusText.setText(getString(R.string.recording_too_few_samples));
        } else statusText.setText(getString(R.string.recording_confirm));
        updateLabels();
    }

    private void confirmRecording(boolean positive) {
        if (pendingResult == null || training) return;
        store.addExample(pendingResult.values, positive);
        pendingResult = null;
        statusText.setText(positive ? "Saved: double chop ✓" : "Saved: not a chop ✓");
        updateLabels();
    }

    private void discardRecording() {
        pendingResult = null;
        statusText.setText(getString(R.string.recording_discarded));
        updateLabels();
    }

    private void cancelRecording() {
        if (prepareTask != null) handler.removeCallbacks(prepareTask);
        if (finishTask != null) handler.removeCallbacks(finishTask);
        prepareTask = finishTask = null;
        preparing = recording = false;
        recordingSamples = null;
    }

    private void beginTraining() {
        if (!BuildConfig.ENABLE_TRAINING || training) return;
        List<TinyNeuralNet.Example> examples = store.getExamples();
        if (store.count(true) < 6 || store.count(false) < 6) {
            showToast("Record at least 6 examples of each class.");
            return;
        }
        training = true;
        if (onTrainingPage) statusText.setText(getString(R.string.training_started));
        updateLabels();
        trainingWorker.execute(() -> {
            String message;
            try {
                TinyNeuralNet net = new TinyNeuralNet();
                TinyNeuralNet.Metrics metrics = net.train(examples);
                boolean saved = store.saveModel(net, examples.size());
                message = saved ? String.format(Locale.US,
                        "Trained! %.0f%% fit on saved examples. Now test on new movements.", metrics.accuracy * 100)
                        : "Dataset changed. Train again.";
            } catch (Exception failure) { message = "Training failed: " + failure.getMessage(); }
            final String done = message;
            handler.post(() -> {
                training = false;
                if (onTrainingPage && statusText != null) statusText.setText(done);
                updateLabels();
            });
        });
    }

    private void flagFalsePositive() {
        if (!BuildConfig.ENABLE_TRAINING || !store.hasFeedbackCandidate()) {
            showToast("No recent torch-ON detection to correct.");
            return;
        }
        if (GestureDetectionService.isRunning()) {
            startService(GestureDetectionService.command(this, GestureDetectionService.ACTION_FALSE_POSITIVE));
        } else if (store.flagFalsePositive()) {
            try {
                torch.initializeAfterPermission();
                torch.turnOff();
            } catch (Exception ignored) { }
            beginTraining(); // Reuses background worker; safe if user leaves app.
        }
        updateLabels();
    }

    private void exportModel() {
        if (!BuildConfig.ENABLE_TRAINING || !store.isModelCurrent()) {
            showToast("Train the network before exporting weights.");
            return;
        }
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("application/json");
        picker.putExtra(Intent.EXTRA_TITLE, "choptorch_model.json");
        startActivityForResult(picker, EXPORT_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BATTERY_SETTINGS_REQUEST) {
            if (!monitoringStartFlow.isWaitingForSettings()) return;
            if (!monitoringStartPrerequisitesReady()) {
                cancelPendingStart();
                showToast("Monitoring was not started. Check permissions and device requirements, then try again.");
                return;
            }
            monitoringStartFlow.settingsReturned(isIgnoringBatteryOptimizations());
            if (resumed) showSettingsReturnConfirmation();
            return;
        }
        if (requestCode != EXPORT_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("Could not open destination");
            out.write(store.exportModelJson().getBytes(StandardCharsets.UTF_8));
            out.flush();
            showToast("Neural-network weights exported as JSON.");
        } catch (Exception failure) { showToast("Export failed: " + failure.getMessage()); }
    }

    private void confirmErase() {
        if (training) return;
        new AlertDialog.Builder(this)
                .setTitle("Erase training data?")
                .setMessage("Delete all recorded examples and your trained neural network?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Erase", (dialog, which) -> {
                    store.eraseAll();
                    pendingResult = null;
                    if (onTrainingPage) statusText.setText(getString(R.string.data_erased));
                    updateLabels();
                }).show();
    }

    private void changeListening() {
        if (monitoringStartFlow.isPending()) {
            cancelPendingStart();
            return;
        }
        if (GestureDetectionService.isRunning()) {
            startService(GestureDetectionService.command(this, GestureDetectionService.ACTION_STOP));
            handler.postDelayed(this::updateLabels, 250);
            return;
        }
        if (!sensorsReady) { showToast("Accelerometer and gyroscope are required."); return; }
        if (!store.isModelCurrent()) {
            showToast("A trained model is required.");
            if (BuildConfig.ENABLE_TRAINING) showTraining();
            return;
        }
        monitoringStartFlow.begin();
        askForCamera(2);
    }

    private void askForCamera(int action) {
        if (action == 1 && monitoringStartFlow.isPending()) cancelPendingStart();
        permissionAction = action;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        else completeCameraAction(action);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != CAMERA_REQUEST) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
            completeCameraAction(permissionAction);
        else {
            if (permissionAction == 2) cancelPendingStart();
            showToast("Camera permission is needed for flashlight control.");
        }
    }

    private void completeCameraAction(int action) {
        try {
            torch.initializeAfterPermission();
            if (!torch.hasFlash()) {
                if (action == 2) cancelPendingStart();
                showToast("No camera flashlight found.");
                return;
            }
            if (action == 1) {
                torch.toggle();
            } else if (action == 2) {
                if (!monitoringStartPrerequisitesReady()) {
                    cancelPendingStart();
                    showToast("Monitoring requirements changed. Check the detector status and try again.");
                    return;
                }
                monitoringStartFlow.cameraReady(isIgnoringBatteryOptimizations());
                if (monitoringStartFlow.isReadyToStart()) startPendingMonitoring();
                else showBatteryGuidance();
            }
            updateLabels();
        } catch (Exception e) {
            if (action == 2) cancelPendingStart();
            showToast("Flashlight error: " + e.getMessage());
        }
    }

    private boolean monitoringStartPrerequisitesReady() {
        if (!sensorsReady || !store.isModelCurrent()
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            torch.initializeAfterPermission();
            return torch.hasFlash();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void showBatteryGuidance() {
        if (batteryDialog != null && batteryDialog.isShowing()) return;
        batteryDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.battery_guidance_title)
                .setMessage(R.string.battery_guidance_message)
                .setNegativeButton(R.string.battery_open_settings, (dialog, which) -> {
                    batteryDialog = null;
                    openBatterySettings();
                })
                .setPositiveButton(R.string.battery_continue, (dialog, which) -> {
                    batteryDialog = null;
                    if (monitoringStartFlow.continueWithoutChanging()) startPendingMonitoring();
                })
                .setNeutralButton(R.string.battery_cancel, (dialog, which) -> cancelPendingStart())
                .setOnCancelListener(dialog -> cancelPendingStart())
                .create();
        batteryDialog.show();
    }

    private void openBatterySettings() {
        if (!monitoringStartFlow.openSettings()) return;
        Intent appSettings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(appSettings, BATTERY_SETTINGS_REQUEST);
        } catch (ActivityNotFoundException unavailable) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        BATTERY_SETTINGS_REQUEST);
            } catch (ActivityNotFoundException noFallback) {
                monitoringStartFlow.settingsUnavailable();
                showBatterySettingsUnavailable();
            }
        }
    }

    private void showBatterySettingsUnavailable() {
        batteryDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.battery_settings_unavailable_title)
                .setMessage(R.string.battery_settings_unavailable_message)
                .setPositiveButton(R.string.battery_continue, (dialog, which) -> {
                    batteryDialog = null;
                    if (monitoringStartFlow.continueWithoutChanging()) startPendingMonitoring();
                })
                .setNegativeButton(R.string.battery_cancel, (dialog, which) -> cancelPendingStart())
                .setOnCancelListener(dialog -> cancelPendingStart())
                .create();
        batteryDialog.show();
    }

    private void showSettingsReturnConfirmation() {
        String status = getString(monitoringStartFlow.wasAllowlistedWhenReturned()
                ? R.string.battery_status_allowlisted : R.string.battery_status_not_allowlisted);
        batteryDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.battery_return_title)
                .setMessage(getString(R.string.battery_return_message, status))
                .setPositiveButton(R.string.battery_confirm_start, (dialog, which) -> {
                    batteryDialog = null;
                    if (monitoringStartFlow.confirmAfterSettings()) startPendingMonitoring();
                })
                .setNegativeButton(R.string.battery_cancel, (dialog, which) -> cancelPendingStart())
                .setOnCancelListener(dialog -> cancelPendingStart())
                .create();
        batteryDialog.show();
    }

    private void restorePendingStartPrompt() {
        if (monitoringStartFlow.isAwaitingBatteryChoice()) showBatteryGuidance();
        else if (monitoringStartFlow.isAwaitingReturnConfirmation()) showSettingsReturnConfirmation();
    }

    private void startPendingMonitoring() {
        if (!resumed || !monitoringStartFlow.isReadyToStart()) return;
        if (!monitoringStartPrerequisitesReady()) {
            cancelPendingStart();
            showToast("Monitoring requirements changed. Check the detector status and try again.");
            return;
        }
        if (!monitoringStartFlow.consumeStart()) return;
        permissionAction = 0;
        try {
            startForegroundService(GestureDetectionService.command(this, GestureDetectionService.ACTION_START));
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
            updateLabels();
        } catch (Exception failure) {
            showToast("Could not start monitoring: " + failure.getMessage());
            updateLabels();
        }
    }

    private void cancelPendingStart() {
        monitoringStartFlow.cancel();
        permissionAction = 0;
        AlertDialog dialog = batteryDialog;
        batteryDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        updateLabels();
    }

    private void updateLabels() {
        if (torchText != null && !onTrainingPage)
            torchText.setText(torch.isOn() ? "FLASHLIGHT ON" : "FLASHLIGHT OFF");
        if (countText != null) {
            int positiveCount = store.count(true);
            int negativeCount = store.count(false);
            String positiveSummary = getResources().getQuantityString(
                R.plurals.double_chop_count, positiveCount, positiveCount);
            String negativeSummary = getResources().getQuantityString(
                R.plurals.other_movement_count, negativeCount, negativeCount);
            countText.setText(getString(R.string.training_count_summary,
                positiveSummary, negativeSummary));
        }
        if (!onTrainingPage) {
            if (statusText != null) {
                statusText.setText(!sensorsReady ? "No accelerometer or gyroscope found"
                        : !store.isModelCurrent() ? "Model missing or retraining…"
                        : GestureDetectionService.isRunning() ? "Background monitoring active" : "Detector stopped");
            }
            if (scoreText != null) {
                String error = GestureDetectionService.getError();
                scoreText.setText(error.isEmpty()
                        ? String.format(Locale.US, "Score %.0f%%  ·  threshold 82%% + 2 peaks",
                        GestureDetectionService.getScore() * 100)
                        : "Last error: " + error);
            }
            if (listenButton != null) {
                listenButton.setText(GestureDetectionService.isRunning() ? "Stop background detection"
                        : monitoringStartFlow.isPending() ? "Cancel monitoring start" : "Start background detection");
                listenButton.setEnabled(monitoringStartFlow.isPending()
                    || sensorsReady && store.isModelCurrent());
            }
            if (falsePositiveButton != null) falsePositiveButton.setEnabled(store.hasFeedbackCandidate());
            if (feedbackText != null) feedbackText.setText(store.getFeedbackMessage());
        } else {
            if (captureButton != null) captureButton.setEnabled(!training && !recording && !preparing && sensorRegistered);
            if (positiveButton != null) positiveButton.setEnabled(!training && pendingResult != null);
            if (negativeButton != null) negativeButton.setEnabled(!training && pendingResult != null);
            if (discardButton != null) discardButton.setEnabled(!training && pendingResult != null);
            if (trainButton != null) {
                trainButton.setEnabled(!training && !recording && !preparing
                        && store.count(true) >= 6 && store.count(false) >= 6);
                trainButton.setText(training ? "Training…" : "Train neural network");
            }
            if (hintText != null) {
                if (pendingResult != null) {
                    hintText.setText(String.format(Locale.US,
                            "%d rotational peaks · gap %d ms · max rotation %.1f rad/s. Confirm the real movement.",
                            pendingResult.strongPeaks, pendingResult.peakGapMs, pendingResult.maxGyro));
                } else hintText.setText(store.isModelCurrent() ? "Model saved. Export or collect more examples."
                        : "After recording, choose the correct label.");
            }
        }
    }

    private LinearLayout freshPage() {
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BG);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(26), dp(20), dp(26));
        scroller.addView(content);
        setContentView(scroller);
        statusText = countText = hintText = torchText = scoreText = feedbackText = null;
        listenButton = captureButton = trainButton = falsePositiveButton = null;
        positiveButton = negativeButton = discardButton = null;
        return content;
    }

    private void heading(String title, String subtitle) {
        TextView titleView = text(title, 33, WHITE, true);
        page.addView(titleView);
        page.addView(spacedText(subtitle, 16, MUTED, 6));
        View space = new View(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(18)));
        page.addView(space);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(19), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(19));
        card.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(15);
        page.addView(card, params);
        return card;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(size);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private TextView spacedText(String value, int size, int color, int marginTop) {
        TextView v = text(value, size, color, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(marginTop);
        v.setLayoutParams(params);
        return v;
    }

    private Button button(String label, boolean primary, Runnable onClick) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? BG : WHITE);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(54));
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? MINT : 0xFF26394E);
        background.setCornerRadius(dp(14));
        b.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        params.topMargin = dp(12);
        b.setLayoutParams(params);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private int dp(int pixels) {
        return Math.round(pixels * getResources().getDisplayMetrics().density);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
