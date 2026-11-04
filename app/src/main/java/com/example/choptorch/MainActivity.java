package com.example.choptorch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single-activity, two-page offline gesture-training flashlight application. */
public final class MainActivity extends Activity implements SensorEventListener {
    private static final int CAMERA_REQUEST = 31;
    private static final long WINDOW_MS = 1900;
    private static final int BG = 0xFF0A1220;
    private static final int CARD = 0xFF142235;
    private static final int MINT = 0xFF66E0C7;
    private static final int WHITE = 0xFFF0F6FC;
    private static final int MUTED = 0xFFACBCCB;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService trainingWorker = Executors.newSingleThreadExecutor();
    private final MotionProcessor motion = new MotionProcessor();
    private final ArrayDeque<MotionSample> history = new ArrayDeque<>();

    private SensorManager sensorManager;
    private Sensor accelerometer, gyroscope;
    private GestureStore store;
    private TorchController torch;
    private boolean onTrainingPage, listening, preparing, recording, training;
    private boolean sensorsReady, sensorRegistered;
    private int permissionAction; // 1 = manual torch, 2 = start listening
    private double latestConfidence;
    private long lastPredictionAt, lastTriggeredAt, lastUiRefresh;
    private int consecutiveHits;
    private ArrayList<MotionSample> recordingSamples;
    private GestureFeatures.Result pendingResult;
    private Runnable prepareTask, finishTask;

    private LinearLayout page;
    private TextView statusText, countText, hintText, torchText, scoreText;
    private Button listenButton, captureButton, trainButton;
    private Button positiveButton, negativeButton, discardButton;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        store = new GestureStore(this);
        torch = new TorchController(this, on -> {
            if (torchText != null) torchText.setText(on ? "FLASHLIGHT ON" : "FLASHLIGHT OFF");
        });
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorsReady = accelerometer != null && gyroscope != null;
        showHome();
    }

    @Override protected void onResume() {
        super.onResume();
        motion.reset();
        history.clear();
        if (sensorsReady) {
            boolean acc = sensorManager.registerListener(this, accelerometer, 20000);
            boolean gyr = sensorManager.registerListener(this, gyroscope, 20000);
            sensorRegistered = acc && gyr;
            if (!sensorRegistered) sensorManager.unregisterListener(this);
        }
        updateLabels();
    }

    @Override protected void onPause() {
        cancelRecording();
        listening = false; // no claims of background/locked-screen monitoring
        sensorManager.unregisterListener(this);
        sensorRegistered = false;
        consecutiveHits = 0;
        updateLabels();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (onTrainingPage) showHome();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        cancelRecording();
        torch.release();
        trainingWorker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            motion.onGyroscope(event.values[0], event.values[1], event.values[2]);
            return;
        }
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        MotionSample sample = motion.onAccelerometer(event.timestamp,
                event.values[0], event.values[1], event.values[2]);
        history.addLast(sample);
        while (!history.isEmpty() && sample.timeMs - history.peekFirst().timeMs > WINDOW_MS + 160) {
            history.removeFirst();
        }
        if (recording && recordingSamples != null) recordingSamples.add(sample);

        if (listening && !recording && !preparing && store.isModelCurrent()
                && sample.timeMs - lastPredictionAt >= 160) {
            lastPredictionAt = sample.timeMs;
            ArrayList<MotionSample> window = new ArrayList<>();
            for (MotionSample s : history) {
                if (s.timeMs >= sample.timeMs - WINDOW_MS) window.add(s);
            }
            if (window.size() > 35 && sample.timeMs - window.get(0).timeMs > 1550) {
                GestureFeatures.Result features = GestureFeatures.extract(window);
                TinyNeuralNet model = store.getModel();
                if (features != null && model != null) {
                    latestConfidence = model.probability(features.values);
                    boolean pass = features.hasDoubleChopShape && latestConfidence >= 0.82;
                    consecutiveHits = pass ? consecutiveHits + 1 : 0;
                    if (consecutiveHits >= 2 && sample.timeMs - lastTriggeredAt >= 3000) {
                        consecutiveHits = 0;
                        lastTriggeredAt = sample.timeMs;
                        triggerTorch();
                    }
                }
            }
        }
        if (sample.timeMs - lastUiRefresh > 500) {
            lastUiRefresh = sample.timeMs;
            updateLabels();
        }
    }

    private void showHome() {
        cancelRecording();
        pendingResult = null;
        onTrainingPage = false;
        page = freshPage();
        heading("ChopTorch", "A double chop. A little light.");

        LinearLayout lightCard = card();
        torchText = text("FLASHLIGHT OFF", 16, MINT, true);
        lightCard.addView(torchText);
        lightCard.addView(spacedText("Two quick chopping motions turn the torch on or off.", 16, WHITE, 12));
        Button manualButton = button("Toggle flashlight manually", false, () -> askForCamera(1));
        lightCard.addView(manualButton);

        LinearLayout detectionCard = card();
        detectionCard.addView(text("GESTURE DETECTOR", 12, MINT, true));
        statusText = spacedText("", 18, WHITE, 12);
        detectionCard.addView(statusText);
        scoreText = spacedText("", 14, MUTED, 8);
        detectionCard.addView(scoreText);
        listenButton = button("Start listening", true, this::changeListening);
        detectionCard.addView(listenButton);
        detectionCard.addView(spacedText("Monitoring works only while this app is visible. It stops when you leave or lock the screen.", 13, MUTED, 12));

        LinearLayout trainCard = card();
        trainCard.addView(text("PERSONALIZE", 12, MINT, true));
        countText = spacedText("", 16, WHITE, 8);
        trainCard.addView(countText);
        trainCard.addView(spacedText("Record real examples and train the neural network on this device.", 14, MUTED, 8));
        trainCard.addView(button("Open training page", false, () -> {
            listening = false;
            showTraining();
        }));
        hintText = spacedText("No data leaves your device.", 13, MUTED, 14);
        page.addView(hintText);
        updateLabels();
    }

    private void showTraining() {
        onTrainingPage = true;
        listening = false;
        page = freshPage();
        page.addView(button("‹  Back to flashlight", false, this::showHome));
        heading("Teach the gesture", "Capture it. Confirm it. Train it.");

        LinearLayout explanation = card();
        explanation.addView(text("HOW TO RECORD", 12, MINT, true));
        explanation.addView(spacedText("Hold your PHONE firmly, tap Record, then make two quick downward chopping motions when you see “MOVE NOW”.", 16, WHITE, 8));
        explanation.addView(spacedText("Also record ordinary movements (walking, lifting, shaking once) and label those NOT double chop. Use no actual knife.", 14, MUTED, 12));
        captureButton = button("Record a 2-second movement", true, this::beginRecording);
        explanation.addView(captureButton);
        statusText = spacedText("Ready to record", 15, MINT, 12);
        explanation.addView(statusText);

        LinearLayout confirm = card();
        confirm.addView(text("CONFIRM THE MOVEMENT", 12, MINT, true));
        hintText = spacedText("After recording, choose the correct label.", 14, MUTED, 10);
        confirm.addView(hintText);
        positiveButton = button("✓ Yes — two chops", true, () -> confirmRecording(true));
        negativeButton = button("No — another movement", false, () -> confirmRecording(false));
        discardButton = button("Discard recording", false, this::discardRecording);
        confirm.addView(positiveButton);
        confirm.addView(negativeButton);
        confirm.addView(discardButton);

        LinearLayout trainingCard = card();
        trainingCard.addView(text("ON-DEVICE TRAINING", 12, MINT, true));
        countText = spacedText("", 17, WHITE, 10);
        trainingCard.addView(countText);
        trainingCard.addView(spacedText("Minimum: 6 confirmed double chops + 6 non-chops. Aim for 15–25 of each for better reliability.", 14, MUTED, 8));
        trainButton = button("Train neural network", true, this::beginTraining);
        trainingCard.addView(trainButton);
        trainingCard.addView(button("Erase all examples and model", false, this::confirmErase));
        trainingCard.addView(spacedText("After training, return to the flashlight page and tap Start listening. You can always add examples and retrain.", 14, MUTED, 12));
        updateLabels();
    }

    private void beginRecording() {
        if (!sensorRegistered || training || preparing || recording) {
            showToast(sensorRegistered ? "Finish the current task first." : "This phone needs accelerometer and gyroscope sensors.");
            return;
        }
        pendingResult = null;
        preparing = true;
        listening = false;
        updateLabels();
        statusText.setText("GET READY… hold the phone firmly");
        prepareTask = () -> {
            if (!preparing) return;
            preparing = false;
            recording = true;
            recordingSamples = new ArrayList<>();
            statusText.setText("MOVE NOW — perform the movement!");
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
        if (pendingResult == null || samples.get(samples.size() - 1).timeMs - samples.get(0).timeMs < 1300) {
            pendingResult = null;
            statusText.setText("Too few sensor readings. Try recording again.");
        } else {
            statusText.setText("Recorded. Please confirm what you did.");
        }
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
        statusText.setText("Recording discarded.");
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
        if (training) return;
        List<TinyNeuralNet.Example> examples = store.getExamples();
        int pos = store.count(true), neg = store.count(false);
        if (pos < 6 || neg < 6) {
            showToast("Record at least 6 examples of each class.");
            return;
        }
        training = true;
        statusText.setText("Training neural network…");
        updateLabels();
        trainingWorker.execute(() -> {
            try {
                TinyNeuralNet net = new TinyNeuralNet();
                TinyNeuralNet.Metrics metrics = net.train(examples);
                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    store.saveModel(net, examples.size());
                    training = false;
                    if (onTrainingPage) statusText.setText(String.format(Locale.US,
                            "Trained! %.0f%% fit on your saved samples. Test with new movements.", metrics.accuracy * 100));
                    updateLabels();
                });
            } catch (Exception error) {
                handler.post(() -> {
                    training = false;
                    if (onTrainingPage) statusText.setText("Training failed: " + error.getMessage());
                    updateLabels();
                });
            }
        });
    }

    private void confirmErase() {
        if (training) return;
        new AlertDialog.Builder(this)
                .setTitle("Erase your training data?")
                .setMessage("Delete all recorded examples and the trained neural network from this phone?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Erase", (dialog, which) -> {
                    store.eraseAll();
                    pendingResult = null;
                    listening = false;
                    statusText.setText("Data erased. Record new gestures to start.");
                    updateLabels();
                }).show();
    }

    private void changeListening() {
        if (listening) {
            listening = false;
            consecutiveHits = 0;
            updateLabels();
            return;
        }
        if (!sensorRegistered) {
            showToast("Accelerometer and gyroscope are required.");
            return;
        }
        if (!store.isModelCurrent()) {
            showToast("First record examples and train the model.");
            showTraining();
            return;
        }
        askForCamera(2);
    }

    private void askForCamera(int action) {
        permissionAction = action;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        } else {
            completeCameraAction(action);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != CAMERA_REQUEST) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            completeCameraAction(permissionAction);
        } else {
            showToast("Camera permission is required to control the flashlight.");
        }
    }

    private void completeCameraAction(int action) {
        try {
            torch.initializeAfterPermission();
            if (!torch.hasFlash()) {
                showToast("No available camera flashlight on this phone.");
                return;
            }
            if (action == 1) triggerTorch();
            if (action == 2) {
                listening = true;
                consecutiveHits = 0;
                lastTriggeredAt = 0;
                latestConfidence = 0;
            }
            updateLabels();
        } catch (Exception e) {
            showToast("Cannot access torch: " + e.getMessage());
        }
    }

    private void triggerTorch() {
        try {
            torch.toggle();
            if (torchText != null) torchText.setText(torch.isOn() ? "FLASHLIGHT ON" : "FLASHLIGHT OFF");
        } catch (Exception error) {
            listening = false;
            showToast("Flashlight unavailable: " + error.getMessage());
        }
        updateLabels();
    }

    private void updateLabels() {
        if (torchText != null && !onTrainingPage) torchText.setText(torch.isOn() ? "FLASHLIGHT ON" : "FLASHLIGHT OFF");
        if (countText != null) {
            countText.setText(String.format(Locale.US, "%d double chops  ·  %d other movements",
                    store.count(true), store.count(false)));
        }
        if (!onTrainingPage) {
            if (statusText != null) {
                statusText.setText(!sensorsReady ? "No accelerometer or gyroscope found"
                        : !store.isModelCurrent() ? "Training needed before listening"
                        : listening ? "Listening for two chops…" : "Detector paused");
            }
            if (scoreText != null) scoreText.setText(store.isModelCurrent()
                    ? String.format(Locale.US, "Live model score: %.0f%%  ·  needs 82%% and two motion peaks", latestConfidence * 100)
                    : "No trained model yet. Create a personalized detector.");
            if (listenButton != null) {
                listenButton.setText(listening ? "Stop listening" : "Start listening");
                listenButton.setEnabled(sensorsReady);
            }
        } else {
            if (captureButton != null) captureButton.setEnabled(!training && !recording && !preparing && sensorRegistered);
            if (positiveButton != null) positiveButton.setEnabled(!training && pendingResult != null);
            if (negativeButton != null) negativeButton.setEnabled(!training && pendingResult != null);
            if (discardButton != null) discardButton.setEnabled(!training && pendingResult != null);
            if (trainButton != null) {
                trainButton.setEnabled(!training && !recording && !preparing && store.count(true) >= 6 && store.count(false) >= 6);
                trainButton.setText(training ? "Training…" : "Train neural network");
            }
            if (hintText != null) {
                if (pendingResult != null) {
                    hintText.setText(String.format(Locale.US,
                            "Detected %d turning peaks · strongest gap %d ms · max rotation %.1f rad/s. Your confirmation is the training label.",
                            pendingResult.strongPeaks, pendingResult.peakGapMs, pendingResult.maxGyro));
                } else {
                    hintText.setText(store.isModelCurrent()
                            ? "Model saved. You can record more, then retrain."
                            : "After recording, choose the correct label.");
                }
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
        statusText = countText = hintText = torchText = scoreText = null;
        listenButton = captureButton = trainButton = null;
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
