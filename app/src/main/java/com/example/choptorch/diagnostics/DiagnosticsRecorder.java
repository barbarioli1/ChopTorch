package com.example.choptorch.diagnostics;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import com.example.choptorch.BuildConfig;
import com.example.choptorch.GestureDetector;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** App-scoped handoff from sensor/camera callbacks to durable diagnostics storage. */
public final class DiagnosticsRecorder {
    private static final int MAX_PENDING_TASKS = 256;
    private static volatile DiagnosticsRecorder instance;

    private final Context appContext;
    private final DiagnosticsConsent consent;
    private final DiagnosticsQueue queue;
    private final ScheduledThreadPoolExecutor executor;
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AttemptAggregator aggregator;
    private final Map<String, PendingTorchAttempt> pendingTorchAttempts = new HashMap<>();
    private long scheduledGeneration = -1;
    private java.util.concurrent.ScheduledFuture<?> timeout;
    private String latestTriggeredEventId;
    private volatile long memoryDropCount;
    private volatile int cachedQueueCount;
    private volatile String cachedUploadError = "";
    private volatile long cachedLastUploadTime;
    private volatile boolean uploading;
    private long lastStatusRefreshMs;

    private DiagnosticsRecorder(Context context) {
        appContext = context.getApplicationContext();
        consent = new DiagnosticsConsent(appContext);
        queue = new DiagnosticsQueue(appContext);
        executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "choptorch-diagnostics");
                thread.setDaemon(true);
                return thread;
            }
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        aggregator = new AttemptAggregator(this::onAttemptFinalized);
        if (BuildConfig.ENABLE_REMOTE_DIAGNOSTICS) executor.execute(() -> {
            refreshCachedStatus();
            FirebaseDiagnosticsClient.setNetworkEnabled(appContext, consent.isEnabled());
        });
    }

    public static DiagnosticsRecorder get(Context context) {
        DiagnosticsRecorder current = instance;
        if (current != null) return current;
        synchronized (DiagnosticsRecorder.class) {
            if (instance == null) instance = new DiagnosticsRecorder(context);
            return instance;
        }
    }

    public boolean isAvailable() { return BuildConfig.ENABLE_REMOTE_DIAGNOSTICS; }
    public boolean isEnabled() { return consent.isEnabled(); }

    public void scheduleIfEnabled() {
        if (!consent.isEnabled()) return;
        long generation = consent.generation();
        submit(generation, () -> DiagnosticsScheduler.schedule(appContext, consent), false);
    }

    public void setConsent(boolean enabled) {
        boolean wasEnabled = consent.isEnabled();
        if (!enabled && !wasEnabled) return;
        long generation = consent.setEnabled(enabled);
        if (!enabled) {
            synchronized (this) {
                aggregator.discard();
                cancelTimeout();
                for (PendingTorchAttempt attempt : pendingTorchAttempts.values()) {
                    if (attempt.timeout != null) attempt.timeout.cancel(false);
                }
                pendingTorchAttempts.clear();
                latestTriggeredEventId = null;
                scheduledGeneration = generation;
            }
            submit(generation, () -> {
                DiagnosticsScheduler.cancel(appContext);
                FirebaseDiagnosticsClient.setNetworkEnabled(appContext, false);
                queue.clear();
                refreshCachedStatus();
            }, true);
            cachedQueueCount = 0;
            cachedUploadError = "";
            cachedLastUploadTime = 0;
        } else if (!wasEnabled && consent.isEnabled()) {
            submit(generation, () -> {
                FirebaseDiagnosticsClient.setNetworkEnabled(appContext, true);
                DiagnosticsScheduler.schedule(appContext, consent);
            }, false);
        }
    }

    public void onPrediction(GestureDetector.Prediction prediction) {
        if (!consent.isEnabled() || prediction == null) return;
        long generation = consent.generation();
        synchronized (this) {
            if (scheduledGeneration != generation) {
                cancelTimeout();
                scheduledGeneration = generation;
            }
            aggregator.accept(prediction);
            cancelTimeout();
            timeout = executor.schedule(() -> {
                if (!valid(generation)) return;
                synchronized (DiagnosticsRecorder.this) {
                    aggregator.flushIfExpired(SystemClock.elapsedRealtime());
                    timeout = null;
                }
            }, AttemptAggregator.SPLIT_GAP_MS, TimeUnit.MILLISECONDS);
        }
    }

    public void flushAttempts() {
        if (!consent.isEnabled()) return;
        long generation = consent.generation();
        synchronized (this) {
            cancelTimeout();
            aggregator.flush();
        }
        submit(generation, () -> DiagnosticsScheduler.schedule(appContext, consent), false);
    }

    public synchronized String onTorchRequested(boolean desiredOn) {
        if (!consent.isEnabled() || latestTriggeredEventId == null) return null;
        String eventId = latestTriggeredEventId;
        latestTriggeredEventId = null;
        PendingTorchAttempt attempt = pendingTorchAttempts.get(eventId);
        if (attempt != null) {
            attempt.requestedOn = desiredOn;
            attempt.requested = true;
        }
        return eventId;
    }

    public synchronized void onTorchApiResult(String eventId, boolean success, String errorClass,
                                               long requestTimeNs) {
        PendingTorchAttempt attempt = pendingTorchAttempts.get(eventId);
        if (attempt == null || !consent.isEnabled()) return;
        if (attempt.timeout != null) attempt.timeout.cancel(false);
        attempt.requestTimeNs = requestTimeNs;
        if (!success) {
            finishTorchAttempt(attempt, "api_error", safeErrorClass(errorClass), null);
            return;
        }
        attempt.event = attempt.event.withTorch(attempt.requestedOn, "api_returned_pending", null, null);
        attempt.timeout = executor.schedule(() -> {
            synchronized (DiagnosticsRecorder.this) {
                PendingTorchAttempt current = pendingTorchAttempts.get(eventId);
                if (current != null) finishTorchAttempt(current, "confirmation_timeout", null, null);
            }
        }, 2, TimeUnit.SECONDS);
    }

    public synchronized void onTorchObservation(String eventId, boolean unavailable,
                                                boolean enabled, long callbackTimeNs) {
        PendingTorchAttempt matched = pendingTorchAttempts.get(eventId);
        if (matched == null || !matched.requested || !consent.isEnabled()
                || matched.requestTimeNs == 0 || callbackTimeNs < matched.requestTimeNs
                || callbackTimeNs - matched.requestTimeNs > TimeUnit.SECONDS.toNanos(2)
                || (!unavailable && enabled != matched.requestedOn)) return;
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(callbackTimeNs - matched.requestTimeNs);
        finishTorchAttempt(matched, unavailable ? "unavailable"
                : enabled ? "confirmed_on" : "confirmed_off", null, latencyMs);
    }

    public void recordState(DiagnosticEvent event) {
        if (!consent.isEnabled() || event == null) return;
        appendAsync(event);
    }

    public String status() {
        if (!isAvailable()) return "Off";
        if (!isEnabled()) return "Off";
        String error = cachedUploadError;
        if (!error.isEmpty()) return "Upload error — " + error;
        if (uploading) return "Enabled — sending";
        if (cachedQueueCount > 0) return "Enabled — waiting for network";
        long lastUpload = cachedLastUploadTime;
        return lastUpload == 0 ? "Enabled" : "Enabled — last upload "
            + DateFormat.getDateTimeInstance().format(new Date(lastUpload));
    }

    public DiagnosticsQueue queueForWorker() { return queue; }
    public DiagnosticsConsent consentForWorker() { return consent; }

    private void appendAsync(DiagnosticEvent event) {
        if (!consent.isEnabled()) return;
        long generation = consent.generation();
        submit(generation, () -> {
            if (!valid(generation)) return;
            String payload = serialize(event);
            if (payload == null || !valid(generation)) return;
            if (queue.enqueue(event.eventId, event.createdAtEpochMs, event.kind, payload)) {
                refreshCachedStatus();
                if (memoryDropCount > 0) memoryDropCount = 0;
                DiagnosticsScheduler.schedule(appContext, consent);
            }
        }, false);
    }

    private synchronized void onAttemptFinalized(DiagnosticEvent event) {
        if (!consent.isEnabled()) return;
        if (!Boolean.TRUE.equals(event.triggered)) {
            appendAsync(event);
            return;
        }
        long generation = consent.generation();
        PendingTorchAttempt attempt = new PendingTorchAttempt(event, generation);
        pendingTorchAttempts.put(event.eventId, attempt);
        latestTriggeredEventId = event.eventId;
        attempt.timeout = executor.schedule(() -> {
            synchronized (DiagnosticsRecorder.this) {
                PendingTorchAttempt current = pendingTorchAttempts.get(event.eventId);
                if (current != null && !current.requested) finishTorchAttempt(current,
                        "not_requested", null, null);
            }
        }, 2, TimeUnit.SECONDS);
    }

    private void finishTorchAttempt(PendingTorchAttempt attempt, String outcome,
                                    String errorClass, Long latencyMs) {
        if (attempt.timeout != null) attempt.timeout.cancel(false);
        pendingTorchAttempts.remove(attempt.event.eventId);
        if (attempt.event.eventId.equals(latestTriggeredEventId)) latestTriggeredEventId = null;
        if (!valid(attempt.generation)) return;
        appendAsync(attempt.event.withTorch(attempt.requested, outcome, errorClass, latencyMs));
    }

    private void submit(long generation, Runnable task, boolean allowOptedOut) {
        if (!allowOptedOut && !valid(generation)) return;
        int pending = pendingTasks.incrementAndGet();
        if (!allowOptedOut && pending > MAX_PENDING_TASKS) {
            pendingTasks.decrementAndGet();
            memoryDropCount++;
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    if (allowOptedOut || valid(generation)) task.run();
                } finally {
                    pendingTasks.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException full) {
            pendingTasks.decrementAndGet();
            memoryDropCount++;
        }
    }

    private boolean valid(long generation) {
        return BuildConfig.ENABLE_REMOTE_DIAGNOSTICS && consent.isEnabled()
                && consent.generation() == generation;
    }

    public void refreshStatusAsync() {
        long generation = consent.generation();
        synchronized (this) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastStatusRefreshMs < 30_000) return;
            lastStatusRefreshMs = now;
        }
        submit(generation, this::refreshCachedStatus, false);
    }

    public void refreshStatusAfterUpload() {
        long generation = consent.generation();
        submit(generation, this::refreshCachedStatus, false);
    }

    private void refreshCachedStatus() {
        cachedQueueCount = queue.count();
        cachedUploadError = queue.lastUploadError();
        cachedLastUploadTime = queue.lastUploadTime();
    }

    public void enableFirebaseNetworkForWorker() {
        FirebaseDiagnosticsClient.setNetworkEnabled(appContext, true);
    }

    public void setUploading(boolean value) { uploading = value; }

    private void cancelTimeout() {
        if (timeout != null) timeout.cancel(false);
        timeout = null;
    }

    private String serialize(DiagnosticEvent event) {
        if (!valid(consent.generation())) return null;
        try {
            JSONObject json = new JSONObject();
            json.put("schemaVersion", 1);
            json.put("eventId", event.eventId);
            json.put("kind", event.kind);
            json.put("createdAtEpochMs", event.createdAtEpochMs);
            json.put("appVersionName", appVersionName());
            json.put("appVersionCode", appVersionCode());
            json.put("manufacturer", truncate(Build.MANUFACTURER));
            json.put("deviceModel", truncate(Build.MODEL));
            json.put("androidSdk", Build.VERSION.SDK_INT);
            PowerManager power = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            json.put("screenInteractive", power.isInteractive());
            json.put("powerSaveMode", power.isPowerSaveMode());
            putNullable(json, "maxScore", event.maxScore);
            putNullable(json, "predictionCount", event.predictionCount);
            double[] eventFeatures = event.features();
            if (eventFeatures != null && eventFeatures.length == 27 && finite(eventFeatures)) {
                JSONArray features = new JSONArray();
                for (double value : eventFeatures) features.put(value);
                json.put("features", features);
            }
            putNullable(json, "strongPeaks", event.strongPeaks);
            putNullable(json, "peakGapMs", event.peakGapMs);
            putNullable(json, "maxGyro", finiteOrNull(event.maxGyro));
            putNullable(json, "hasDoubleChopShape", event.hasDoubleChopShape);
            putNullable(json, "passedActivationScoreAndShape", event.passedActivationScoreAndShape);
            putNullable(json, "triggered", event.triggered);
            putNullable(json, "requestedTorchOn", event.requestedTorchOn);
            json.put("torchOutcome", event.torchOutcome == null ? "not_requested" : event.torchOutcome);
            putNullable(json, "torchErrorClass", safeErrorClass(event.torchErrorClass));
            putNullable(json, "torchConfirmationLatencyMs", event.torchConfirmationLatencyMs);
            putNullable(json, "recoveryEpisodeCount", event.recoveryEpisodeCount);
            putNullable(json, "durationMs", event.durationMs);
            String payload = json.toString();
            return payload.getBytes(StandardCharsets.UTF_8).length <= DiagnosticsQueue.MAX_EVENT_BYTES
                    ? payload : null;
        } catch (org.json.JSONException invalid) {
            return null;
        }
    }

    private String appVersionName() {
        try { return appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "unknown"; }
    }

    private long appVersionCode() {
        try {
            android.content.pm.PackageInfo info = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) { return 0; }
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private static void putNullable(JSONObject json, String key, Object value) throws org.json.JSONException {
        if (value != null) json.put(key, value);
    }

    private static boolean finite(double[] values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static Double finiteOrNull(Double value) {
        return value != null && Double.isFinite(value) ? value : null;
    }

    private static String safeErrorClass(String value) {
        if ("CameraAccessException".equals(value) || "SecurityException".equals(value)
                || "IllegalStateException".equals(value)) return value;
        return value == null ? null : "Other";
    }


    private static final class PendingTorchAttempt {
        DiagnosticEvent event;
        final long generation;
        boolean requested, requestedOn;
        long requestTimeNs;
        java.util.concurrent.ScheduledFuture<?> timeout;

        PendingTorchAttempt(DiagnosticEvent event, long generation) {
            this.event = event;
            this.generation = generation;
        }
    }
}