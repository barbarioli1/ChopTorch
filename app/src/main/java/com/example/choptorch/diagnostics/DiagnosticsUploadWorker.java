package com.example.choptorch.diagnostics;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.choptorch.BuildConfig;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Performs a bounded, server-acknowledged upload batch on WorkManager's worker thread. */
public final class DiagnosticsUploadWorker extends Worker {
    private static final long AUTH_TIMEOUT_SECONDS = 30;
    private static final long WRITE_TIMEOUT_SECONDS = 20;

    public DiagnosticsUploadWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        if (!BuildConfig.ENABLE_REMOTE_DIAGNOSTICS) return Result.success();
        DiagnosticsRecorder recorder = DiagnosticsRecorder.get(getApplicationContext());
        DiagnosticsConsent consent = recorder.consentForWorker();
        if (!consent.isEnabled()) return Result.success();
        recorder.enableFirebaseNetworkForWorker();
        recorder.setUploading(true);
        long generation = consent.generation();
        DiagnosticsQueue queue = recorder.queueForWorker();
        List<DiagnosticsQueue.QueuedEvent> events = queue.oldest(20);
        if (events.isEmpty()) {
            recorder.setUploading(false);
            return Result.success();
        }
        try {
            String uid = FirebaseDiagnosticsClient.ensureAnonymousUser(AUTH_TIMEOUT_SECONDS);
            for (DiagnosticsQueue.QueuedEvent event : events) {
                if (isStopped() || !consent.isEnabled() || consent.generation() != generation) {
                    return Result.success();
                }
                FirebaseDiagnosticsClient.writeEvent(uid, event, WRITE_TIMEOUT_SECONDS);
                if (!consent.isEnabled() || consent.generation() != generation) return Result.success();
                queue.delete(event.eventId);
            }
            queue.setLastUploadError("");
            queue.setLastUploadTime(System.currentTimeMillis());
            recorder.setUploading(false);
            recorder.refreshStatusAfterUpload();
            return Result.success();
        } catch (Exception failure) {
            queue.setLastUploadError(safeCode(failure));
            recorder.setUploading(false);
            recorder.refreshStatusAfterUpload();
            return Result.retry();
        } finally {
            recorder.setUploading(false);
        }
    }

    private static String safeCode(Exception failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof FirebaseFirestoreException) {
                FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) current).getCode();
                return code.name();
            }
            current = current.getCause();
        }
        if (failure instanceof java.util.concurrent.TimeoutException) return "ACK_TIMEOUT";
        if (failure.getMessage() != null && failure.getMessage().equals("AUTH_UNAVAILABLE")) return "AUTH_UNAVAILABLE";
        return "UPLOAD_FAILED";
    }
}