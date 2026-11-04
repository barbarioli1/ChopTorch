package com.example.choptorch;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;

/** Camera2 torch control with a callback that follows external quick-settings changes. */
public final class TorchController {
    public interface Listener { void onTorchState(boolean on); }
    private final CameraManager manager;
    private final Listener listener;
    private String flashCameraId;
    private boolean on;
    private boolean subscribed;

    private final CameraManager.TorchCallback callback = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean enabled) {
            if (id.equals(flashCameraId)) {
                on = enabled;
                listener.onTorchState(enabled);
            }
        }
        @Override public void onTorchModeUnavailable(String id) {
            if (id.equals(flashCameraId)) {
                on = false;
                listener.onTorchState(false);
            }
        }
    };

    public TorchController(Context context, Listener listener) {
        manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
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

    public void toggle() throws CameraAccessException {
        if (flashCameraId == null) throw new IllegalStateException("No flashlight found");
        // Remember the requested state before calling the camera service. Reading
        // and negating `on` again after setTorchMode() can race with its callback.
        boolean desired = !on;
        manager.setTorchMode(flashCameraId, desired);
        on = desired;
        listener.onTorchState(desired);
    }

    /** Force OFF after a mistaken activation; does not rely on callback timing. */
    public void turnOff() throws CameraAccessException {
        if (flashCameraId == null) throw new IllegalStateException("No flashlight found");
        manager.setTorchMode(flashCameraId, false);
        on = false;
        listener.onTorchState(false);
    }

    public void release() {
        if (subscribed) {
            manager.unregisterTorchCallback(callback);
            subscribed = false;
        }
    }
}
