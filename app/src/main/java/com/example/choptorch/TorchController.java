package com.example.choptorch;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/** Camera2 torch control with a callback that follows external quick-settings changes. */
public final class TorchController {
    private static final String TAG = "ChopTorch/Sensors";
    public interface Listener { void onTorchState(boolean on); }
    public interface DiagnosticsListener {
        String onRequest(boolean desiredOn, long requestTimeNs);
        void onApiResult(String eventId, boolean success, String errorClass, long requestTimeNs);
        void onObservation(String eventId, boolean unavailable, boolean enabled, long callbackTimeNs);
    }
    private final Context appContext;
    private final CameraManager manager;
    private final Listener listener;
    private volatile DiagnosticsListener diagnosticsListener;
    private volatile String pendingDiagnosticEventId;
    private String flashCameraId;
    private boolean on;
    private boolean subscribed;
    private boolean hasReportedCameraState;
    private boolean lastReportedCameraState;
    private Boolean pendingFeedbackState;
    private static boolean hasConfirmedTorchState;
    private static boolean lastConfirmedTorchOn;
    private static boolean torchUnavailable;
    private static long confirmedTorchOnSinceNs;

    private final CameraManager.TorchCallback callback = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean enabled) {
            if (id.equals(flashCameraId)) {
                boolean changed = hasReportedCameraState && lastReportedCameraState != enabled;
                boolean matchesRequest = pendingFeedbackState != null && pendingFeedbackState == enabled;
                String eventId = changed && matchesRequest ? pendingDiagnosticEventId : null;
                if (matchesRequest) {
                    pendingFeedbackState = null;
                    pendingDiagnosticEventId = null;
                }
                hasReportedCameraState = true;
                lastReportedCameraState = enabled;
                on = enabled;
                logConfirmedTorchState(enabled);
                notifyObservation(eventId, false, enabled);
                if (changed && matchesRequest) VibrationFeedback.confirmTorchState(appContext, enabled);
                listener.onTorchState(enabled);
            }
        }
        @Override public void onTorchModeUnavailable(String id) {
            if (id.equals(flashCameraId)) {
                String eventId = pendingDiagnosticEventId;
                on = false;
                hasReportedCameraState = false;
                pendingFeedbackState = null;
                pendingDiagnosticEventId = null;
                notifyObservation(eventId, true, false);
                if (BuildConfig.ENABLE_TRAINING) {
                    synchronized (TorchController.class) {
                        if (!torchUnavailable) {
                            Log.w(TAG, "TORCH_STATE_UNAVAILABLE atNs=" + SystemClock.elapsedRealtimeNanos()
                                    + " lastConfirmedOn=" + (hasConfirmedTorchState && lastConfirmedTorchOn));
                        }
                        torchUnavailable = true;
                        hasConfirmedTorchState = false;
                        confirmedTorchOnSinceNs = 0;
                    }
                }
                listener.onTorchState(false);
            }
        }
    };

    public TorchController(Context context, Listener listener) {
        appContext = context.getApplicationContext();
        manager = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
        this.listener = listener;
    }

    public void initializeAfterPermission() throws CameraAccessException {
        if (flashCameraId == null) {
            String fallback = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics props = manager.getCameraCharacteristics(id);
                if (!Boolean.TRUE.equals(props.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) continue;
                if (fallback == null) fallback = id;
                if (Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK)
                        .equals(props.get(CameraCharacteristics.LENS_FACING))) {
                    fallback = id;
                    break;
                }
            }
            flashCameraId = fallback;
        }
        if (!subscribed) {
            manager.registerTorchCallback(callback, new Handler(Looper.getMainLooper()));
            subscribed = true;
        }
    }

    public boolean hasFlash() { return flashCameraId != null; }
    public boolean isOn() { return on; }
    public void setDiagnosticsListener(DiagnosticsListener observer) { diagnosticsListener = observer; }

    private static synchronized void logConfirmedTorchState(boolean enabled) {
        if (!BuildConfig.ENABLE_TRAINING
                || (hasConfirmedTorchState && lastConfirmedTorchOn == enabled)) return;
        torchUnavailable = false;
        long nowNs = SystemClock.elapsedRealtimeNanos();
        if (enabled) {
            confirmedTorchOnSinceNs = nowNs;
            Log.i(TAG, "TORCH_STATE_CONFIRMED state=ON atNs=" + nowNs);
        } else {
            String duration = confirmedTorchOnSinceNs == 0 ? "unknown"
                    : String.format(java.util.Locale.US, "%.1f",
                            (nowNs - confirmedTorchOnSinceNs) / 1e6);
            Log.i(TAG, "TORCH_STATE_CONFIRMED state=OFF atNs=" + nowNs
                    + " priorOnDurationMs=" + duration);
            confirmedTorchOnSinceNs = 0;
        }
        hasConfirmedTorchState = true;
        lastConfirmedTorchOn = enabled;
    }

    public void toggle() throws CameraAccessException {
        if (flashCameraId == null) throw new IllegalStateException("No flashlight found");
        // Remember the requested state before calling the camera service. Reading
        // and negating `on` again after setTorchMode() can race with its callback.
        boolean desired = !on;
        requestTorchState(desired);
        on = desired;
        listener.onTorchState(desired);
    }

    /** Force OFF after a mistaken activation; does not rely on callback timing. */
    public void turnOff() throws CameraAccessException {
        if (flashCameraId == null) throw new IllegalStateException("No flashlight found");
        requestTorchState(false);
        on = false;
        listener.onTorchState(false);
    }

    private void requestTorchState(boolean desired) throws CameraAccessException {
        long requestTimeNs = SystemClock.elapsedRealtimeNanos();
        String eventId = null;
        try {
            if (diagnosticsListener != null) eventId = diagnosticsListener.onRequest(desired, requestTimeNs);
        } catch (RuntimeException ignored) { }
        pendingDiagnosticEventId = eventId;
        pendingFeedbackState = desired;
        try {
            manager.setTorchMode(flashCameraId, desired);
            notifyApiResult(eventId, true, null, requestTimeNs);
        } catch (CameraAccessException | RuntimeException failure) {
            notifyApiResult(eventId, false, failure.getClass().getSimpleName(), requestTimeNs);
            pendingFeedbackState = null;
            pendingDiagnosticEventId = null;
            throw failure;
        }
    }

    private void notifyApiResult(String eventId, boolean success, String errorClass, long requestTimeNs) {
        try {
            if (eventId != null && diagnosticsListener != null) {
                diagnosticsListener.onApiResult(eventId, success, errorClass, requestTimeNs);
            }
        } catch (RuntimeException ignored) { }
    }

    private void notifyObservation(String eventId, boolean unavailable, boolean enabled) {
        try {
            if (eventId != null && diagnosticsListener != null) {
                diagnosticsListener.onObservation(eventId, unavailable, enabled,
                        SystemClock.elapsedRealtimeNanos());
            }
        } catch (RuntimeException ignored) { }
    }

    public void release() {
        if (subscribed) {
            manager.unregisterTorchCallback(callback);
            subscribed = false;
        }
    }
}
