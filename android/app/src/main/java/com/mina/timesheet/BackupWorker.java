package com.mina.timesheet;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Runs about once a day, when the phone has internet, and uploads the latest backup. */
public class BackupWorker extends Worker {
    public BackupWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        if (!DriveSync.prefs(c).getBoolean("on", false)) return Result.success();
        try {
            String token = DriveSync.tokenSilently(c);
            if (token == null) {
                DriveSync.prefs(c).edit().putString("error", "Google sign-in expired. Open the app and tap Back up now.").apply();
                return Result.success();
            }
            DriveSync.upload(c, token);
            return Result.success();
        } catch (Exception e) {
            DriveSync.prefs(c).edit().putString("error", "Last automatic backup failed: " + e.getMessage()).apply();
            return getRunAttemptCount() < 3 ? Result.retry() : Result.success();
        }
    }
}
