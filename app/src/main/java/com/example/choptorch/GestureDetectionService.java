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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;

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
    private HandlerThread sensorThread;

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
            // Deliver unbatched 50 Hz IMU data on a dedicated thread, so UI work
            // and notification updates cannot delay recognition on the main thread.
            sensorThread = new HandlerThread("ChopTorch-IMU");
            sensorThread.start();
            Handler sensorHandler = new Handler(sensorThread.getLooper());
            boolean acc = manager.registerListener(this, accelerometer, 20000, 0, sensorHandler);
            boolean gyro = manager.registerListener(this, gyroscope, 20000, 0, sensorHandler);
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
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            detector.onGyroscope(event.values[0], event.values[1], event.values[2]);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            GestureDetector.Detection match = detector.onAccelerometer(event.timestamp,
                    event.values[0], event.values[1], event.values[2], store.getModel());
            latestScore = detector.latestScore();
            if (match != null) {
                try {
                    torch.toggle();
                    if (torch.isOn()) store.rememberTorchOn(match.features, match.score);
                    updateNotification();
                } catch (Exception failure) {
                    lastError = "Torch unavailable: " + failure.getMessage();
                }
            }
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPending = PendingIntent.getService(this, 2, command(this, ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("ChopTorch · double-chop listening")
                .setContentText(store.isModelCurrent()
                        ? String.format(Locale.US, "Monitoring IMU · confidence %.0f%%", latestScore * 100)
                        : "Retraining after feedback…")
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
        if (sensorThread != null) {
            sensorThread.quitSafely();
            sensorThread = null;
        }
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
