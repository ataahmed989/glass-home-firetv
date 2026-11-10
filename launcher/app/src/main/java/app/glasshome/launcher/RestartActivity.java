package app.glasshome.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/**
 * "Restart Glass Home" in Settings: Glass Home opens this (in a process of its own, ":restart") and closes.
 * A moment later, once Glass Home has stopped and its settings are written, this ends whatever is left of
 * Glass Home's process, opens a fresh Home and goes. Fire OS starts the Home service again by itself.
 */
public final class RestartActivity extends Activity {
    static final String EXTRA_PID = "pid";
    /** Long enough for Glass Home to stop (its settings are written then), short enough to feel immediate. */
    static final long SETTLE_MS = 500;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pid = getIntent().getIntExtra(EXTRA_PID, -1);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid);
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            overridePendingTransition(0, 0);
            // Nothing to keep here; Glass Home starts afresh in its own process
            if (pid > 0) new Handler(Looper.getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), SETTLE_MS);
        }, SETTLE_MS);
    }

    @Override
    public void onBackPressed() {
        // The restart is under way
    }
}
