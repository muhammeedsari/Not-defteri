package app.notdefteri;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import java.util.Calendar;

/** AlarmManager glue: one exact alarm per reminder plus the daily morning summary (flows B and D). */
final class Alarms {
    static final String REMIND = "app.notdefteri.REMIND", DONE = "app.notdefteri.DONE", SNOOZE = "app.notdefteri.SNOOZE",
        MORNING = "app.notdefteri.MORNING";

    private Alarms() { }

    static PendingIntent intent(Context c, String action, long taskId, String mode) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(action)
            .setData(Uri.parse("nd://" + action + "/" + taskId + (mode == null ? "" : "/" + mode)))
            .putExtra("task", taskId);
        if (mode != null) i.putExtra("mode", mode);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Exact alarms need the user's permission on Android 12+; without it Android may deliver a few minutes late. */
    static boolean exact(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms());
    }

    static void set(Context c, long taskId, long at) {
        at(c, at, intent(c, REMIND, taskId, null));
    }

    private static void at(Context c, long at, PendingIntent pi) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (exact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancel(Context c, long taskId) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(intent(c, REMIND, taskId, null));
    }

    /** The next morning summary at the time in settings (default 08:00). */
    static void morning(Context c) {
        int[] hm = Db.hm(Db.get(c).setting("morning_summary"), new int[]{8, 0});
        Calendar k = Calendar.getInstance();
        k.set(Calendar.HOUR_OF_DAY, hm[0]); k.set(Calendar.MINUTE, hm[1]); k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0);
        if (k.getTimeInMillis() <= System.currentTimeMillis() + 1000) k.add(Calendar.DAY_OF_MONTH, 1);
        at(c, k.getTimeInMillis(), intent(c, MORNING, 0, null));
    }
}
