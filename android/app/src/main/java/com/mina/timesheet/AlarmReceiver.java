package com.mina.timesheet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires when the scheduled alarm goes off. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Reminders.fire(context);
    }
}
