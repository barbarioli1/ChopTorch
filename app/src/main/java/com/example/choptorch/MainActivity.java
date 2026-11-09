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
import android.widget.ImageView;
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
    private static final int EXPORT_EXAMPLES_REQUEST = 35;
    private static final String STATE_MONITORING_START = "monitoring_start_state";
    private static final String STATE_BATTERY_ALLOWLISTED = "battery_allowlisted_on_return";
    private static final String STATE_PERMISSION_ACTION = "monitoring_permission_action";
    private static final String STATE_SCREEN = "screen";
    private static final long WINDOW_MS = GestureDetector.WINDOW_MS;
    private static final int BG = 0xFF060809;
    private static final int CARD = 0xFF14191C;
    private static final int BANNER = 0xFF11191A;
    private static final int MINT = 0xFF66E0C7;
    private static final int WHITE = 0xFFF0F5F4;
    private static final int MUTED = 0xFF9CB2AD;

    private enum Screen { HOME, SETTINGS, DETECTION_MODEL, TRAINING, ABOUT }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService trainingWorker = Executors.newSingleThreadExecutor();
    private final MotionProcessor recordingMotion = new MotionProcessor();
    private final MonitoringStartFlow monitoringStartFlow = new MonitoringStartFlow();
    private SensorManager sensorManager;
    private Sensor accelerometer, gyroscope;
    private GestureStore store;
    private TorchController torch;
    private Screen currentScreen = Screen.HOME;
    private boolean onTrainingPage, preparing, recording, training, resumed, sensorRegistered;
    private boolean sensorsReady;
    private int permissionAction; // 1 manual torch; 2 background detection
    private ArrayList<MotionSample> recordingSamples;
    private GestureFeatures.Result pendingResult;
    private Runnable prepareTask, finishTask;
    private AlertDialog batteryDialog;

    private LinearLayout page;
    private TextView statusText, countText, hintText, torchText;
    private TextView homeMonitoringText, homeMonitoringMessage, modelStatusText;
    private TextView detectionMonitoringText, settingsMonitoringText, settingsMonitoringSubtitle;
    private TextView detectionCountsText, detectionFeedbackText;
    private Button captureButton, trainButton, falsePositiveButton;
    private Button positiveButton, negativeButton, discardButton;
    private Button homeMonitoringButton, manualTorchButton, openTrainingButton;
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
        Screen restored = savedInstanceState == null ? Screen.HOME : restoreScreen(savedInstanceState.getString(STATE_SCREEN));
        if (restored == Screen.SETTINGS) showSettings();
        else if (restored == Screen.DETECTION_MODEL) showDetectionModel();
        else if (restored == Screen.ABOUT) showAbout();
        else showHome();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_MONITORING_START, monitoringStartFlow.saveState());
        outState.putBoolean(STATE_BATTERY_ALLOWLISTED, monitoringStartFlow.saveAllowlistedOnReturn());
        outState.putInt(STATE_PERMISSION_ACTION, permissionAction);
        outState.putString(STATE_SCREEN, currentScreen.name());
        super.onSaveInstanceState(outState);
    }

    private Screen restoreScreen(String savedScreen) {
        if (savedScreen == null) return Screen.HOME;
        try {
            Screen restored = Screen.valueOf(savedScreen);
            return restored == Screen.TRAINING ? Screen.HOME : restored;
        } catch (IllegalArgumentException invalid) {
            return Screen.HOME;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try { torch.initializeAfterPermission(); } catch (Exception ignored) { }
        }
        if (onTrainingPage) syncTrainingSensors();
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
        switch (currentScreen) {
            case TRAINING: showDetectionModel(); break;
            case DETECTION_MODEL:
            case ABOUT: showSettings(); break;
            case SETTINGS: showHome(); break;
            default: super.onBackPressed(); break;
        }
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
        leaveTrainingIfNeeded();
        currentScreen = Screen.HOME;
        cancelRecording();
        unregisterTrainingSensors();
        pendingResult = null;
        onTrainingPage = false;
        page = freshPage(R.id.screen_home);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.mipmap.ic_launcher);
        appIcon.setContentDescription("ChopTorch app icon");
        header.addView(appIcon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = text("ChopTorch", 24, WHITE, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = dp(12);
        header.addView(title, titleParams);
        Button settings = compactButton("⚙", "Settings", this::showSettings);
        settings.setId(R.id.btn_open_settings);
        header.addView(settings);
        page.addView(header);

        View topSpace = new View(this);
        page.addView(topSpace, new LinearLayout.LayoutParams(1, dp(36)));
        homeMonitoringButton = new Button(this);
        homeMonitoringButton.setId(R.id.btn_home_monitoring);
        homeMonitoringButton.setText("⏻");
        homeMonitoringButton.setTextSize(42);
        homeMonitoringButton.setTextColor(MINT);
        homeMonitoringButton.setAllCaps(false);
        homeMonitoringButton.setContentDescription("Toggle background gesture monitoring");
        homeMonitoringButton.setMinHeight(0);
        homeMonitoringButton.setMinimumHeight(0);
        homeMonitoringButton.setPadding(0, 0, 0, 0);
        homeMonitoringButton.setBackground(monitoringCircle(false));
        homeMonitoringButton.setOnClickListener(v -> changeListening());
        LinearLayout.LayoutParams circleParams = new LinearLayout.LayoutParams(dp(164), dp(164));
        circleParams.gravity = Gravity.CENTER_HORIZONTAL;
        page.addView(homeMonitoringButton, circleParams);
        homeMonitoringText = spacedText("PAUSED", 19, WHITE, 20);
        homeMonitoringText.setGravity(Gravity.CENTER);
        page.addView(homeMonitoringText);
        homeMonitoringMessage = spacedText("", 14, MUTED, 8);
        homeMonitoringMessage.setGravity(Gravity.CENTER);
        page.addView(homeMonitoringMessage);

        View lowerSpace = new View(this);
        page.addView(lowerSpace, new LinearLayout.LayoutParams(1, dp(38)));
        LinearLayout lightCard = card(BANNER);
        torchText = text("FLASHLIGHT OFF", 15, WHITE, true);
        manualTorchButton = button("Manual flashlight", false, () -> askForCamera(1));
        manualTorchButton.setId(R.id.btn_manual_flashlight);
        lightCard.addView(torchText);
        lightCard.addView(manualTorchButton);
        updateLabels();
    }

    private void showSettings() {
        leaveTrainingIfNeeded();
        currentScreen = Screen.SETTINGS;
        onTrainingPage = false;
        page = freshPage(R.id.screen_settings);
        addBackHeader("Settings", R.id.btn_settings_back, this::showHome);

        LinearLayout statusCard = card();
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView vibrationIcon = new ImageView(this);
        vibrationIcon.setImageResource(R.drawable.ic_phone_vibrate);
        vibrationIcon.setContentDescription("Phone vibration gesture");
        statusRow.addView(vibrationIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout statusLabels = new LinearLayout(this);
        statusLabels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        labelsParams.leftMargin = dp(12);
        statusLabels.addView(text("Gesture detection", 16, WHITE, true));
        settingsMonitoringSubtitle = spacedText("", 13, MUTED, 4);
        statusLabels.addView(settingsMonitoringSubtitle);
        statusRow.addView(statusLabels, labelsParams);
        settingsMonitoringText = text("PAUSED", 12, MUTED, true);
        statusRow.addView(settingsMonitoringText);
        statusCard.addView(statusRow);

        sectionTitle("BACKGROUND & POWER");
        LinearLayout powerCard = card();
        addSettingsRow(powerCard, android.R.drawable.ic_lock_idle_low_battery,
                "Battery optimization", "Review Android app battery settings", this::openAppBatterySettings, 0);
        addSettingsRow(powerCard, android.R.drawable.ic_dialog_info,
                "Service notification", "Manage monitoring notification settings", this::openNotificationSettings, 0);

        sectionTitle("DETECTION");
        LinearLayout detectionCard = card();
        addSettingsRow(detectionCard, android.R.drawable.ic_menu_info_details,
                "Detection & model", "Model and personal training", this::showDetectionModel, R.id.row_detection_model);

        sectionTitle("ABOUT");
        LinearLayout aboutCard = card();
        addSettingsRow(aboutCard, android.R.drawable.ic_menu_info_details,
            "About ChopTorch", "Version and privacy", this::showAbout, R.id.row_about);
        updateLabels();
    }

    private void showDetectionModel() {
        leaveTrainingIfNeeded();
        currentScreen = Screen.DETECTION_MODEL;
        onTrainingPage = false;
        page = freshPage(R.id.screen_detection_model);
        addBackHeader("Detection & model", R.id.btn_detection_back, this::showSettings);

        LinearLayout modelCard = card();
        modelCard.addView(text("MODEL", 12, MINT, true));
        modelStatusText = spacedText("", 16, WHITE, 7);
        modelStatusText.setId(R.id.model_status);
        modelCard.addView(modelStatusText);
        detectionMonitoringText = spacedText("", 14, MUTED, 9);
        modelCard.addView(detectionMonitoringText);
        modelCard.addView(spacedText("Double-chop recognition runs locally on your device.", 14, MUTED, 9));

        if (BuildConfig.ENABLE_TRAINING) {
            sectionTitle("PERSONAL TRAINING");
            LinearLayout trainingCard = card();
            detectionCountsText = spacedText("", 15, WHITE, 0);
            trainingCard.addView(detectionCountsText);
            openTrainingButton = button("Open training page", true, () -> {
                if (BuildConfig.ENABLE_TRAINING && currentScreen != Screen.TRAINING && !training) showTraining();
            });
            openTrainingButton.setId(R.id.btn_open_training);
            trainingCard.addView(openTrainingButton);
            trainingCard.addView(spacedText("Training pauses background gesture monitoring.", 13, MUTED, 8));

            sectionTitle("IMPROVE RECOGNITION");
            LinearLayout feedbackCard = card();
            feedbackCard.addView(spacedText("If ChopTorch mistakenly turned the flashlight on, flag that detection within 10 minutes.", 14, WHITE, 0));
            falsePositiveButton = button("Flag last ON as false positive", false, this::flagFalsePositive);
            falsePositiveButton.setId(R.id.btn_false_positive);
            feedbackCard.addView(falsePositiveButton);
            detectionFeedbackText = spacedText("", 13, MUTED, 8);
            detectionFeedbackText.setId(R.id.feedback_status);
            feedbackCard.addView(detectionFeedbackText);
        }
        updateLabels();
    }

    private void showAbout() {
        leaveTrainingIfNeeded();
        currentScreen = Screen.ABOUT;
        onTrainingPage = false;
        page = freshPage(R.id.screen_about);
        addBackHeader("About ChopTorch", R.id.btn_about_back, this::showSettings);
        LinearLayout aboutCard = card();
        aboutCard.addView(text("ChopTorch", 19, WHITE, true));
        String version;
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException missing) { version = "Unavailable"; }
        aboutCard.addView(spacedText("Version " + version, 14, MUTED, 8));
        aboutCard.addView(spacedText("Motion data and gesture recognition are processed locally on this device.", 14, WHITE, 16));
        updateLabels();
    }

    private void showTraining() {
        if (!BuildConfig.ENABLE_TRAINING) return;
        if (currentScreen == Screen.TRAINING) return;
        if (GestureDetectionService.isRunning()) {
            startService(GestureDetectionService.command(this, GestureDetectionService.ACTION_STOP));
        }
        currentScreen = Screen.TRAINING;
        onTrainingPage = true;
        page = freshPage(R.id.screen_training);
        Button back = addBackHeader("Training", R.id.btn_training_back, this::showDetectionModel);
        back.setContentDescription("Back to Detection & model");
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
        trainingCard.addView(button("Export examples as JSON", false, this::exportExamples));
        trainingCard.addView(button("Erase all examples and model", false, this::confirmErase));
        trainingCard.addView(spacedText("To ship without a training page: put the exported JSON into app/src/main/assets/choptorch_model.json and build with -Pshipping=true.", 14, MUTED, 12));
        updateLabels();
    }

    private void leaveTrainingIfNeeded() {
        if (!onTrainingPage) return;
        cancelRecording();
        unregisterTrainingSensors();
        pendingResult = null;
        onTrainingPage = false;
    }

    private void openAppBatterySettings() {
        Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(settings);
        } catch (ActivityNotFoundException unavailable) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (ActivityNotFoundException noFallback) { showToast("Battery settings are unavailable on this device."); }
        }
    }

    private void openNotificationSettings() {
        Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        settings.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try { startActivity(settings); }
        catch (ActivityNotFoundException unavailable) { openAppBatterySettings(); }
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

    private void exportExamples() {
        if (!BuildConfig.ENABLE_TRAINING) return;
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("application/json");
        picker.putExtra(Intent.EXTRA_TITLE, "choptorch_seed_examples.json");
        startActivityForResult(picker, EXPORT_EXAMPLES_REQUEST);
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
        if ((requestCode != EXPORT_REQUEST && requestCode != EXPORT_EXAMPLES_REQUEST)
                || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("Could not open destination");
            boolean exportingExamples = requestCode == EXPORT_EXAMPLES_REQUEST;
            String json = exportingExamples ? store.exportExamplesJson() : store.exportModelJson();
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
            showToast(exportingExamples ? "Training examples exported as JSON."
                    : "Neural-network weights exported as JSON.");
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
        switch (currentScreen) {
            case HOME:
                updateHomeLabels();
                break;
            case SETTINGS:
                updateSettingsLabels();
                break;
            case DETECTION_MODEL:
                updateDetectionModelLabels();
                break;
            case TRAINING:
                updateTrainingLabels();
                break;
            case ABOUT:
                break;
        }
    }

    private void updateHomeLabels() {
        boolean active = GestureDetectionService.isRunning();
        boolean starting = monitoringStartFlow.isPending();
        boolean ready = sensorsReady && store.isModelCurrent();
        if (torchText != null) torchText.setText(torch.isOn() ? "FLASHLIGHT ON" : "FLASHLIGHT OFF");
        if (homeMonitoringButton == null) return;
        homeMonitoringButton.setBackground(monitoringCircle(active));
        homeMonitoringButton.setEnabled(active || starting || ready);
        homeMonitoringButton.setContentDescription(active ? "Pause background gesture monitoring"
                : starting ? "Cancel monitoring start" : "Start background gesture monitoring");
        if (homeMonitoringText != null) {
            homeMonitoringText.setText(active ? "ACTIVE" : starting ? "STARTING"
                    : ready ? "PAUSED" : "UNAVAILABLE");
        }
        if (homeMonitoringMessage != null) {
            String message;
            if (active) message = "Background gesture monitoring is running.";
            else if (starting) message = "Tap the circle to cancel start.";
            else if (!GestureDetectionService.getError().isEmpty())
                message = "Monitoring stopped after an error. Tap to try again.";
            else if (!sensorsReady) message = "Motion sensors are unavailable on this device.";
            else if (!store.isModelCurrent()) message = BuildConfig.ENABLE_TRAINING
                    ? "The model needs training before monitoring can start."
                    : "The model is unavailable.";
            else message = "Tap the circle to start gesture monitoring.";
            homeMonitoringMessage.setText(message);
        }
    }

    private void updateSettingsLabels() {
        boolean active = GestureDetectionService.isRunning();
        boolean unavailable = !sensorsReady || !store.isModelCurrent();
        if (settingsMonitoringSubtitle != null) settingsMonitoringSubtitle.setText(
                active ? "Background monitoring is active" : "Monitoring is paused");
        if (settingsMonitoringText != null) {
            settingsMonitoringText.setText(active ? "ACTIVE" : unavailable ? "UNAVAILABLE" : "PAUSED");
            settingsMonitoringText.setTextColor(active ? MINT : unavailable ? 0xFFFFC56E : MUTED);
        }
    }

    private void updateDetectionModelLabels() {
        boolean current = store.isModelCurrent();
        int total = store.count(true) + store.count(false);
        if (modelStatusText != null) modelStatusText.setText(current ? "Ready"
                : total > 0 ? "Needs training" : "Unavailable");
        if (detectionMonitoringText != null) detectionMonitoringText.setText(
                GestureDetectionService.isRunning() ? "Monitoring: Active" : "Monitoring: Paused");
        if (detectionCountsText != null) detectionCountsText.setText(trainingCountsSummary());
        boolean candidate = BuildConfig.ENABLE_TRAINING && store.hasFeedbackCandidate();
        if (falsePositiveButton != null) falsePositiveButton.setEnabled(candidate && !training);
        if (detectionFeedbackText != null) {
            String feedback = store.getFeedbackMessage();
            detectionFeedbackText.setText(!feedback.isEmpty() ? feedback : candidate
                    ? "A recent automatic torch-ON detection is available to flag."
                    : "No recent torch-ON detection to correct.");
        }
        if (openTrainingButton != null) openTrainingButton.setEnabled(
                BuildConfig.ENABLE_TRAINING && !training && currentScreen != Screen.TRAINING);
    }

    private void updateTrainingLabels() {
        if (torchText != null) torchText.setText(torch.isOn() ? "FLASHLIGHT ON" : "FLASHLIGHT OFF");
        if (countText != null) countText.setText(trainingCountsSummary());
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

    private String trainingCountsSummary() {
        int positiveCount = store.count(true);
        int negativeCount = store.count(false);
        String positiveSummary = getResources().getQuantityString(
                R.plurals.double_chop_count, positiveCount, positiveCount);
        String negativeSummary = getResources().getQuantityString(
                R.plurals.other_movement_count, negativeCount, negativeCount);
        return getString(R.string.training_count_summary, positiveSummary, negativeSummary);
    }

    private LinearLayout freshPage(int screenId) {
        ScrollView scroller = new ScrollView(this);
        scroller.setId(screenId);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BG);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroller.addView(content);
        setContentView(scroller);
        statusText = countText = hintText = torchText = null;
        homeMonitoringText = homeMonitoringMessage = modelStatusText = null;
        detectionMonitoringText = settingsMonitoringText = settingsMonitoringSubtitle = null;
        detectionCountsText = detectionFeedbackText = null;
        captureButton = trainButton = falsePositiveButton = null;
        homeMonitoringButton = manualTorchButton = openTrainingButton = null;
        positiveButton = negativeButton = discardButton = null;
        return content;
    }

    private Button addBackHeader(String title, int buttonId, Runnable onBack) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button back = compactButton("‹", "Back", onBack);
        back.setId(buttonId);
        row.addView(back);
        TextView titleView = text(title, 24, WHITE, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = dp(12);
        row.addView(titleView, titleParams);
        page.addView(row);
        View space = new View(this);
        page.addView(space, new LinearLayout.LayoutParams(1, dp(18)));
        return back;
    }

    private Button compactButton(String label, String description, Runnable onClick) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(21);
        button.setTextColor(WHITE);
        button.setContentDescription(description);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(12));
        button.setBackground(background);
        button.setOnClickListener(v -> onClick.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    private GradientDrawable monitoringCircle(boolean active) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(BANNER);
        circle.setStroke(dp(3), active ? MINT : 0xFF53615E);
        return circle;
    }

    private void sectionTitle(String value) {
        TextView title = text(value, 12, MINT, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(2);
        params.bottomMargin = dp(9);
        page.addView(title, params);
    }

    private void addSettingsRow(LinearLayout container, int iconResource, String title,
                                String subtitle, Runnable action, int viewId) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(68));
        row.setPadding(0, dp(8), 0, dp(8));
        row.setContentDescription(title + ". " + subtitle);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());
        if (viewId != 0) row.setId(viewId);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setColorFilter(MINT);
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        labelsParams.leftMargin = dp(14);
        labels.addView(text(title, 15, WHITE, true));
        labels.addView(spacedText(subtitle, 13, MUTED, 3));
        row.addView(labels, labelsParams);
        TextView chevron = text("›", 25, MUTED, false);
        chevron.setGravity(Gravity.CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(30), dp(40)));
        container.addView(row);
    }

    private void heading(String title, String subtitle) {
        TextView titleView = text(title, 24, WHITE, true);
        page.addView(titleView);
        page.addView(spacedText(subtitle, 16, MUTED, 6));
        View space = new View(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(18)));
        page.addView(space);
    }

    private LinearLayout card() {
        return card(CARD);
    }

    private LinearLayout card(int backgroundColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(19), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(backgroundColor);
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
