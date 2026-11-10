package com.example.choptorch;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground-only lifetime for long-running IMU monitoring, including when screen is locked. */
public final class GestureDetectionService extends Service implements SensorEventListener {
    public static final String ACTION_START = "com.example.choptorch.START";
    public static final String ACTION_STOP = "com.example.choptorch.STOP";
    public static final String ACTION_FALSE_POSITIVE = "com.example.choptorch.FALSE_POSITIVE";
    private static final int NOTICE_ID = 1902;
    private static final String CHANNEL = "gesture_detector";
    private static final String TAG = "ChopTorch/Sensors";
    private static final boolean DIAGNOSTICS = BuildConfig.ENABLE_TRAINING;
    private static final long STATS_INTERVAL_NS = 5_000_000_000L;
    private static volatile boolean running;
    private static volatile double latestScore;
    private static volatile String lastError = "";

    private final GestureDetector detector = new GestureDetector();
    private final ExecutorService trainer = Executors.newSingleThreadExecutor();
    private SensorManager manager;
    private Sensor accelerometer, gyroscope;
    private TorchController torch;
    private GestureStore store;
    private PowerManager.WakeLock wakeLock;
    private SensorDeliveryGuard accelGuard = new SensorDeliveryGuard();
    private SensorDeliveryGuard gyroGuard = new SensorDeliveryGuard();
    private SensorRecoveryCoordinator recovery = new SensorRecoveryCoordinator();
    private SensorTimingStats accelStats = new SensorTimingStats("accel", STATS_INTERVAL_NS);
    private SensorTimingStats gyroStats = new SensorTimingStats("gyro", STATS_INTERVAL_NS);
    private long lastAccelTimestampNs, lastGyroTimestampNs, lastDetectorResetNs;
    private long lastPredictionEvaluatedAtNs, lastPositiveEvaluatedAtNs;
    private long lastPositiveEventMs;
    private long freshHistoryStartNs;
    private int freshAccelSamples;
    private boolean freshHistoryReadyLogged;
    private float latestGx, latestGy, latestGz;

    public static boolean isRunning() { return running; }
    public static double getScore() { return latestScore; }
    public static String getError() { return lastError; }

    public static Intent command(Context context, String action) {
        return new Intent(context, GestureDetectionService.class).setAction(action);
    }

    @Override public void onCreate() {
        super.onCreate();
        store = GestureStore.shared(this);
        manager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        torch = new TorchController(this, on -> { });
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                "Gesture flashlight detection", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopMonitoring();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_FALSE_POSITIVE.equals(action)) {
            if (BuildConfig.ENABLE_TRAINING && store.flagFalsePositive()) {
                try {
                    torch.initializeAfterPermission();
                    torch.turnOff();
                } catch (Exception ignored) { /* Feedback is still stored. */ }
                retrainAfterFeedback();
            }
            updateNotification();
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Start action comes only from a visible Activity after CAMERA permission is granted.
        startForeground(NOTICE_ID, notification());
        if (running) return START_NOT_STICKY;
        if (accelerometer == null || gyroscope == null || store.getModel() == null
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            lastError = "Missing sensors, camera permission, or trained model";
            stopMonitoring();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            torch.initializeAfterPermission();
            if (!torch.hasFlash()) throw new IllegalStateException("Flashlight unavailable");
            detector.reset();
            accelGuard = new SensorDeliveryGuard();
            gyroGuard = new SensorDeliveryGuard();
            recovery = new SensorRecoveryCoordinator();
            accelStats = new SensorTimingStats("accel", STATS_INTERVAL_NS);
            gyroStats = new SensorTimingStats("gyro", STATS_INTERVAL_NS);
            lastAccelTimestampNs = lastGyroTimestampNs = 0;
            latestGx = latestGy = latestGz = 0;
            lastDetectorResetNs = SystemClock.elapsedRealtimeNanos();
            lastPredictionEvaluatedAtNs = lastPositiveEvaluatedAtNs = lastPositiveEventMs = 0;
            freshHistoryStartNs = 0;
            freshAccelSamples = 0;
            freshHistoryReadyLogged = false;
            if (DIAGNOSTICS) logSensorInventory();
            boolean acc = manager.registerListener(this, accelerometer, 20000);
            boolean gyro = manager.registerListener(this, gyroscope, 20000);
            if (!acc || !gyro) throw new IllegalStateException("Sensor registration failed");
            // Non-wake-up IMU samples need the CPU active while the display is off.
            // This is power intensive; user can stop monitoring from the notification.
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChopTorch:IMU");
            wakeLock.acquire();
            running = true;
            latestScore = 0;
            lastError = "";
            updateNotification();
        } catch (Exception e) {
            lastError = e.getMessage() == null ? "Unable to start sensors" : e.getMessage();
            stopMonitoring();
            stopSelf();
        }
        return START_NOT_STICKY; // Do not secretly restart after the system kills the service.
    }

    private void retrainAfterFeedback() {
        trainer.execute(() -> {
            List<TinyNeuralNet.Example> examples = store.getExamples();
            int positive = store.count(true), negative = store.count(false);
            if (positive < 6 || negative < 6) {
                store.setFeedbackMessage("Negative example saved; add more training examples before retraining.");
                return;
            }
            try {
                TinyNeuralNet neural = new TinyNeuralNet();
                neural.train(examples);
                boolean saved = store.saveModel(neural, examples.size());
                store.setFeedbackMessage(saved
                        ? "False positive saved and model retrained (" + examples.size() + " samples)."
                        : "Samples changed during training; retrain on the training page.");
            } catch (Exception failure) {
                store.setFeedbackMessage("Negative saved, but retraining failed: " + failure.getMessage());
            }
            updateNotification();
        });
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!running) return;
        boolean gyro = event.sensor.getType() == Sensor.TYPE_GYROSCOPE;
        if (!gyro && event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        long arrivalNs = SystemClock.elapsedRealtimeNanos();
        SensorDeliveryGuard guard = gyro ? gyroGuard : accelGuard;
        SensorDeliveryGuard.Decision decision = guard.check(event.timestamp, arrivalNs);
        String summary = DIAGNOSTICS
            ? (gyro ? gyroStats : accelStats).record(event.timestamp, arrivalNs, decision) : null;
        if (summary != null) {
            long freshHistoryMs = freshHistoryStartNs == 0 ? 0
                : Math.max(0, (event.timestamp - freshHistoryStartNs) / 1_000_000L);
            Log.i(TAG, summary + String.format(Locale.US,
                " recoveryEpisodes=%d recoveryReason=%s sinceResetMs=%.1f freshHistoryMs=%d "
                        + "predictionEventMs=%d predictionEvaluatedAtNs=%d "
                        + "positiveEventMs=%d positiveEvaluatedAtNs=%d %s",
                recovery.episodeCount(), recovery.lastReason(),
                (arrivalNs - lastDetectorResetNs) / 1e6, freshHistoryMs,
                    detector.lastPredictionTimeMs(), lastPredictionEvaluatedAtNs,
                    detector.lastPositiveMatchTimeMs(), lastPositiveEvaluatedAtNs, powerState()));
        }
        if (decision == SensorDeliveryGuard.Decision.DROP_STALE
            || decision == SensorDeliveryGuard.Decision.DROP_FUTURE
            || decision == SensorDeliveryGuard.Decision.DROP_INVALID_TIMESTAMP) {
            String reason = decision == SensorDeliveryGuard.Decision.DROP_STALE
                ? (gyro ? "STALE_GYRO" : "STALE_ACCEL")
                : decision == SensorDeliveryGuard.Decision.DROP_FUTURE
                    ? (gyro ? "FUTURE_GYRO" : "FUTURE_ACCEL")
                    : (gyro ? "INVALID_GYRO_TIMESTAMP" : "INVALID_ACCEL_TIMESTAMP");
            startRecovery(reason, arrivalNs, arrivalNs - event.timestamp, 0);
            return;
        }
        if (decision != SensorDeliveryGuard.Decision.ACCEPT) return;

        long gapNs = guard.consumeGapNs();
        long otherTimestampNs = gyro ? lastAccelTimestampNs : lastGyroTimestampNs;
        long crossSensorGapNs = otherTimestampNs == 0 ? 0 : Math.abs(event.timestamp - otherTimestampNs);
        String recoveryReason = gapNs > SensorDeliveryGuard.MAX_GAP_NS
                ? (gyro ? "GAP_GYRO" : "GAP_ACCEL")
                : crossSensorGapNs > SensorDeliveryGuard.MAX_GAP_NS
                        ? (gyro ? "ACCEL_NOT_FRESH" : "GYRO_NOT_FRESH") : null;
        if (recoveryReason != null) {
            boolean started = startRecovery(recoveryReason, arrivalNs, 0,
                Math.max(gapNs, crossSensorGapNs));
            if (started && otherTimestampNs > 0
                    && crossSensorGapNs <= SensorDeliveryGuard.MAX_GAP_NS) {
                recovery.recordFresh(gyro ? SensorRecoveryCoordinator.Sensor.ACCELEROMETER
                        : SensorRecoveryCoordinator.Sensor.GYROSCOPE, otherTimestampNs);
            }
        }

        if (gyro) {
            lastGyroTimestampNs = event.timestamp;
            latestGx = event.values[0];
            latestGy = event.values[1];
            latestGz = event.values[2];
        } else {
            lastAccelTimestampNs = event.timestamp;
        }
        if (recovery.isRecovering()) {
            boolean resumed = recovery.recordFresh(gyro
                    ? SensorRecoveryCoordinator.Sensor.GYROSCOPE
                    : SensorRecoveryCoordinator.Sensor.ACCELEROMETER, event.timestamp);
            if (!resumed) return;
            if (DIAGNOSTICS) {
                Log.i(TAG, "RECOVERY_RESUMED accelFresh=true gyroFresh=true reason="
                        + recovery.lastReason() + " episodes=" + recovery.episodeCount());
            }
        }
        if (gyro) {
            detector.onGyroscope(latestGx, latestGy, latestGz);
            return;
        }
        detector.onGyroscope(latestGx, latestGy, latestGz);
        long previousPredictionMs = detector.lastPredictionTimeMs();
        long previousPositiveMs = detector.lastPositiveMatchTimeMs();
        GestureDetector.Detection match = detector.onAccelerometer(event.timestamp,
                event.values[0], event.values[1], event.values[2], store.getModel());
        if (detector.lastPredictionTimeMs() != previousPredictionMs) {
            lastPredictionEvaluatedAtNs = SystemClock.elapsedRealtimeNanos();
        }
        if (detector.lastPositiveMatchTimeMs() != previousPositiveMs) {
            lastPositiveEventMs = detector.lastPositiveMatchTimeMs();
            lastPositiveEvaluatedAtNs = SystemClock.elapsedRealtimeNanos();
        }
        recordFreshHistory(event.timestamp);
        latestScore = detector.latestScore();
        if (match == null) return;
        long recognizedNs = SystemClock.elapsedRealtimeNanos();
        if (DIAGNOSTICS) {
            Log.i(TAG, String.format(Locale.US,
                "DETECTION score=%.3f detectionEventNs=%d callbackArrivalNs=%d recognitionCompleteNs=%d "
                            + "predictionEventMs=%d predictionEvaluatedAtNs=%d "
                    + "positiveEventMs=%d positiveEvaluatedAtNs=%d %s",
                match.score, event.timestamp, arrivalNs, recognizedNs, detector.lastPredictionTimeMs(),
                        lastPredictionEvaluatedAtNs, lastPositiveEventMs,
                lastPositiveEvaluatedAtNs, powerState()));
        }
        long toggleRequestNs = SystemClock.elapsedRealtimeNanos();
        try {
            torch.toggle();
            if (DIAGNOSTICS) {
            Log.i(TAG, "TORCH_REQUEST detectionEventNs=" + event.timestamp
                + " toggleRequestNs=" + toggleRequestNs
                + " cameraApiReturnNs=" + SystemClock.elapsedRealtimeNanos()
                + " requestedOn=" + torch.isOn());
            }
            if (torch.isOn()) store.rememberTorchOn(match.features, match.score);
            updateNotification();
        } catch (Exception failure) {
            lastError = "Torch unavailable: " + failure.getMessage();
            Log.e(TAG, lastError + " toggleRequestNs=" + toggleRequestNs, failure);
        }
    }

    private boolean startRecovery(String reason, long nowNs, long ageNs, long gapNs) {
        if (!recovery.begin(reason)) return false;
        detector.resetForRecovery();
        lastDetectorResetNs = nowNs;
        lastPredictionEvaluatedAtNs = lastPositiveEvaluatedAtNs = lastPositiveEventMs = 0;
        freshHistoryStartNs = 0;
        freshAccelSamples = 0;
        freshHistoryReadyLogged = false;
        latestScore = 0;
        Log.w(TAG, String.format(Locale.US,
                "RECOVERY_START reason=%s ageMs=%.1f gapMs=%.1f episode=%d; "
                        + "persistent timestamp delay may prevent reliable continuous recognition %s",
                reason, ageNs / 1e6, gapNs / 1e6, recovery.episodeCount(), powerState()));
        return true;
    }

    private void recordFreshHistory(long eventNs) {
        if (freshHistoryStartNs == 0) freshHistoryStartNs = eventNs;
        freshAccelSamples++;
        long durationMs = (eventNs - freshHistoryStartNs) / 1_000_000L;
        if (!freshHistoryReadyLogged && freshAccelSamples > 35 && durationMs > 1550) {
            freshHistoryReadyLogged = true;
            if (DIAGNOSTICS && recovery.episodeCount() > 0) {
                Log.i(TAG, "RECOVERY_READY freshHistoryMs=" + durationMs
                        + " sinceResetMs=" + ((SystemClock.elapsedRealtimeNanos() - lastDetectorResetNs) / 1e6));
            }
        }
    }

    private String powerState() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return String.format(Locale.US, "interactive=%b deviceIdle=%b powerSave=%b",
                power.isInteractive(), power.isDeviceIdleMode(), power.isPowerSaveMode());
    }

    private void logSensorInventory() {
        describeSensor("accelerometer(default)", manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
        describeSensor("accelerometer(wakeUp)", manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true));
        describeSensor("gyroscope(default)", manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE));
        describeSensor("gyroscope(wakeUp)", manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE, true));
        describeSensor("significantMotion", manager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION));
        describeSensor("motionDetect", manager.getDefaultSensor(Sensor.TYPE_MOTION_DETECT));
        for (Sensor sensor : manager.getSensorList(Sensor.TYPE_ALL)) {
            String type = sensor.getStringType();
            if (type != null && type.contains("pick_up")) describeSensor("pickUp", sensor);
        }
    }

    private static void describeSensor(String label, Sensor sensor) {
        if (sensor == null) {
            Log.i(TAG, label + ": unavailable");
            return;
        }
        Log.i(TAG, String.format(Locale.US,
                "%s: name=%s vendor=%s wakeUp=%b reportingMode=%d minDelayUs=%d maxDelayUs=%d fifoMax=%d fifoReserved=%d powerMa=%.3f",
                label, sensor.getName(), sensor.getVendor(), sensor.isWakeUpSensor(), sensor.getReportingMode(),
                sensor.getMinDelay(), sensor.getMaxDelay(), sensor.getFifoMaxEventCount(),
                sensor.getFifoReservedEventCount(), sensor.getPower()));
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPending = PendingIntent.getService(this, 2, command(this, ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("ChopTorch")
                .setContentText("Monitoring flashlight gesture")
                .setOngoing(true)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending);
        if (BuildConfig.ENABLE_TRAINING && store.hasFeedbackCandidate()) {
            PendingIntent falsePending = PendingIntent.getService(this, 3,
                    command(this, ACTION_FALSE_POSITIVE),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(android.R.drawable.ic_delete, "False positive", falsePending);
        }
        return builder.build();
    }

    private void updateNotification() {
        if (running) {
            NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            notifications.notify(NOTICE_ID, notification());
        }
    }

    private void stopMonitoring() {
        running = false;
        manager.unregisterListener(this);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        stopMonitoring();
        torch.release();
        trainer.shutdown();
        super.onDestroy();
    }
}
