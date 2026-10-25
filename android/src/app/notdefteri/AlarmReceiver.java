package app.notdefteri;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.util.Calendar;

/** Reminder alarms, the notification buttons (Bitti / Ertele), the morning summary and the restart that restores alarms. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        final PendingResult pr = goAsync();
        final Context app = c.getApplicationContext();
        final String action = i.getAction() == null ? "" : i.getAction();
        final long task = i.getLongExtra("task", 0);
        final String mode = i.getStringExtra("mode");
        new Thread(new Runnable() {
            @Override public void run() {
                try { handle(app, action, task, mode); } catch (Exception ignored) { } finally { pr.finish(); }
            }
        }).start();
    }

    private static void handle(Context c, String action, long task, String mode) {
        if (Alarms.REMIND.equals(action)) {
            Db db = Db.get(c);
            db.reminderFired(task);
            JSONObject t = db.task(task);
            if (t != null && Tasks.isOpen(t)) Notify.task(c, t);
        } else if (Alarms.DONE.equals(action)) {
            Tasks.done(c, task, "bildirim");
        } else if (Alarms.SNOOZE.equals(action)) {
            Tasks.snooze(c, task, mode, 0);
        } else if (Alarms.MORNING.equals(action)) {
            Calendar k = Calendar.getInstance();
            k.set(Calendar.HOUR_OF_DAY, 0); k.set(Calendar.MINUTE, 0); k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0);
            JSONObject s = Db.get(c).summary(k.getTimeInMillis());
            Notify.morning(c, s);
            Backup.auto(c);
            Alarms.morning(c);
            Widget.refresh(c);
        } else {
            // BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED: alarms do not survive these
            Tasks.restoreAll(c);
        }
    }
}
