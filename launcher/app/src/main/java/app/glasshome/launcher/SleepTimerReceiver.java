package app.glasshome.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The sleep timer going off: clears it and puts the TV to sleep. */
public final class SleepTimerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        new HomeSettings(context).setSleepAt(0);
        SystemControls.sleepNow(context);
    }
}
