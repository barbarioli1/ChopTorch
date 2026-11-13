package com.example.choptorch.diagnostics;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.BackoffPolicy;
import androidx.work.ExistingPeriodicWorkPolicy;

import java.util.concurrent.TimeUnit;

/** Unique connected-network uploads; work is absent until explicit consent. */
public final class DiagnosticsScheduler {
    public static final String UPLOAD_WORK = "choptorch-diagnostics-upload";
    public static final String PERIODIC_WORK = "choptorch-diagnostics-periodic-upload";

    private DiagnosticsScheduler() { }

    public static void schedule(Context context, DiagnosticsConsent consent) {
        if (!consent.isEnabled()) return;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest upload = new OneTimeWorkRequest.Builder(DiagnosticsUploadWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        manager.enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.KEEP, upload);
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                DiagnosticsUploadWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP, periodic);
    }

    public static void cancel(Context context) {
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        manager.cancelUniqueWork(UPLOAD_WORK);
        manager.cancelUniqueWork(PERIODIC_WORK);
    }
}