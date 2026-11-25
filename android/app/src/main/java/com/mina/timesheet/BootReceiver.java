package com.mina.timesheet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Puts the next reminder back after a restart, an app update, or a time/zone change. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Reminders.scheduleNext(context);
    }
}
