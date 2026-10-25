package app.notdefteri;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.Calendar;

/** Home-screen widget: the microphone starts a recording in one tap, the text shows today's task count. */
public class Widget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        draw(c, m, ids);
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (m == null) return;
        int[] ids = m.getAppWidgetIds(new ComponentName(c, Widget.class));
        if (ids != null && ids.length > 0) draw(c, m, ids);
    }

    private static void draw(Context c, AppWidgetManager m, int[] ids) {
        Calendar k = Calendar.getInstance();
        k.set(Calendar.HOUR_OF_DAY, 0); k.set(Calendar.MINUTE, 0); k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0);
        long end = k.getTimeInMillis() + 86400000L;
        Db db = Db.get(c);
        int today = db.count("status IN " + Db.OPEN + " AND due_at < ?", end);   // late ones are today's work too
        String text = today == 0 ? "Bugün görev yok" : "Bugün " + today + " görev";
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setTextViewText(R.id.count, text);
        v.setOnClickPendingIntent(R.id.mic, PendingIntent.getActivity(c, 1,
            new Intent(c, MainActivity.class).setAction(MainActivity.RECORD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        v.setOnClickPendingIntent(R.id.count, PendingIntent.getActivity(c, 2,
            new Intent(c, MainActivity.class).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        m.updateAppWidget(ids, v);
    }
}
