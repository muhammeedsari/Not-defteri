package app.notdefteri;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The only two kinds of notification the app sends (principle 7): a reminder whose time has come, and one morning
 * summary a day. A reminder has three buttons: Bitti, a snooze (1 saat) and a second snooze (akşam, or yarın after
 * the evening); tapping it opens the task (Aç).
 */
final class Notify {
    private static final String REMIND = "remind", MORNING = "morning";
    private static final int MORNING_ID = 0x7fff0001;

    private Notify() { }

    private static NotificationManager nm(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(REMIND, "Hatırlatmalar", NotificationManager.IMPORTANCE_HIGH));
            NotificationChannel m = new NotificationChannel(MORNING, "Sabah özeti", NotificationManager.IMPORTANCE_DEFAULT);
            m.setShowBadge(false);
            nm.createNotificationChannel(m);
        }
        return nm;
    }

    private static Notification.Builder builder(Context c, String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
    }

    static PendingIntent open(Context c, long taskId) {
        Intent i = new Intent(c, MainActivity.class).setAction(MainActivity.OPEN).putExtra("task", taskId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, (int) taskId, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void task(Context c, JSONObject t) {
        NotificationManager nm = nm(c);
        if (nm == null) return;
        long id = t.optLong("id");
        StringBuilder text = new StringBuilder();
        if (t.optBoolean("hasTime") && t.has("due")) text.append(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t.optLong("due"))));
        if (t.has("person")) { if (text.length() > 0) text.append(" · "); text.append(t.optString("person")); }
        if (t.has("catName")) { if (text.length() > 0) text.append(" · "); text.append(t.optString("catName")); }
        boolean evening = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < Db.get(c).periods().aksam[0] - 1;
        Notification.Builder b = builder(c, REMIND)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(t.optString("title"))
            .setContentText(text.toString())
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open(c, id))
            .addAction(new Notification.Action.Builder(null, "Bitti", Alarms.intent(c, Alarms.DONE, id, null)).build())
            .addAction(new Notification.Action.Builder(null, "1 saat ertele", Alarms.intent(c, Alarms.SNOOZE, id, "hour")).build())
            .addAction(new Notification.Action.Builder(null, evening ? "Akşama ertele" : "Yarına ertele",
                Alarms.intent(c, Alarms.SNOOZE, id, evening ? "evening" : "tomorrow")).build());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        try { nm.notify((int) id, b.build()); } catch (SecurityException ignored) { }   // notifications turned off
    }

    static void cancel(Context c, long taskId) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel((int) taskId);
    }

    /** Nothing due, nothing late, nothing urgent: no notification at all. */
    static void morning(Context c, JSONObject s) {
        int today = s.optInt("today"), late = s.optInt("overdue"), high = s.optInt("high");
        if (today + late + high == 0) return;
        NotificationManager nm = nm(c);
        if (nm == null) return;
        StringBuilder t = new StringBuilder();
        if (today > 0) t.append("Bugün ").append(today).append(" görev");
        if (late > 0) { if (t.length() > 0) t.append(" · "); t.append(late).append(" geciken"); }
        if (high > 0) { if (t.length() > 0) t.append(" · "); t.append(high).append(" yüksek öncelikli"); }
        Notification.InboxStyle box = new Notification.InboxStyle();
        JSONArray titles = s.optJSONArray("titles");
        for (int i = 0; titles != null && i < titles.length(); i++) box.addLine(titles.optString(i));
        Notification.Builder b = builder(c, MORNING)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("Günaydın")
            .setContentText(t.toString())
            .setStyle(box.setSummaryText(t.toString()))
            .setAutoCancel(true)
            .setContentIntent(open(c, 0));
        try { nm.notify(MORNING_ID, b.build()); } catch (SecurityException ignored) { }
    }
}
