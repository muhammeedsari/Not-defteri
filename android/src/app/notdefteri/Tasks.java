package app.notdefteri;

import android.content.ContentValues;
import android.content.Context;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * Everything that moves a task (plan 7): done, snooze, delete, undo, split, and the reminder that goes with it.
 * The notification buttons, the voice "done" sentence and the screens all come through here.
 */
final class Tasks {
    interface Listener { void onTasksChanged(); }

    static volatile Listener listener;

    private Tasks() { }

    /** Sets (or clears) the task's reminder: its time, or the morning hour of its day when it has no time. */
    static void schedule(Context c, long id) {
        Db db = Db.get(c);
        JSONObject t = db.task(id);
        Alarms.cancel(c, id);
        if (t == null || !t.has("due") || !isOpen(t)) { db.setReminder(id, -1); return; }
        long at = t.optLong("due");
        if (!t.optBoolean("hasTime")) at = atPeriod(at, db.periods().sabah);
        if (at <= System.currentTimeMillis()) { db.setReminder(id, -1); return; }
        db.setReminder(id, at);
        Alarms.set(c, id, at);
    }

    static boolean isOpen(JSONObject t) {
        String s = t.optString("status");
        return "gelen".equals(s) || "acik".equals(s) || "ertelendi".equals(s);
    }

    static long atPeriod(long day, int[] hm) {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(day);
        k.set(Calendar.HOUR_OF_DAY, hm[0]); k.set(Calendar.MINUTE, hm[1]); k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    /** Done: to Bitenler, the next one of a repeating task, the note to the archive when nothing of it is open. */
    static void done(Context c, long id, String how) {
        Db db = Db.get(c);
        JSONObject t = db.task(id);
        if (t == null || !isOpen(t)) return;
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("status", "bitti");
        v.put("completed_at", now);
        v.putNull("conf");
        db.updateTask(id, v);
        db.setReminder(id, -1);
        Alarms.cancel(c, id);
        Notify.cancel(c, id);
        try {
            db.learn("bitirme", new JSONObject().put("task", id).put("title", t.optString("title")).put("cat", t.optLong("cat", Db.OTHER))
                .put("prio", t.optInt("prio")).put("ms", now - t.optLong("created", now)).put("how", how));
        } catch (Exception ignored) { }
        String rule = t.optString("rec", null);
        if (rule != null && !rule.isEmpty() && t.has("due")) {
            long next = Parser.next(rule, t.optLong("due"));
            while (next >= 0 && next < now - 86400000L) next = Parser.next(rule, next);
            if (next >= 0) {
                Parser.Task k = new Parser.Task();
                k.title = t.optString("title"); k.due = next; k.hasTime = t.optBoolean("hasTime"); k.priority = t.optInt("prio", 2);
                k.person = t.optString("person", null); k.recurrence = rule; k.category = (int) t.optLong("cat", Db.OTHER);
                long nid = db.addTask(t.optLong("note", 0), k, "acik");
                db.updateTask(nid, conflessValues());
                schedule(c, nid);
            }
        }
        if (t.has("note")) db.archiveIfDone(t.optLong("note"));
        changed(c);
    }

    private static ContentValues conflessValues() { ContentValues v = new ContentValues(); v.putNull("conf"); return v; }

    /** mode: hour | evening | tomorrow | at (with "at" ms) */
    static void snooze(Context c, long id, String mode, long at) {
        Db db = Db.get(c);
        JSONObject t = db.task(id);
        if (t == null || !isOpen(t)) return;
        Parser.Periods p = db.periods();
        long now = System.currentTimeMillis();
        long until;
        if ("hour".equals(mode)) until = now + 3600000L;
        else if ("evening".equals(mode)) {
            until = atPeriod(now, p.aksam);
            if (until <= now + 30 * 60000L) until = atPeriod(now + 86400000L, p.aksam);
        } else if ("tomorrow".equals(mode)) {
            int[] hm = p.sabah;
            if (t.optBoolean("hasTime") && t.has("due")) { Calendar k = Calendar.getInstance(); k.setTimeInMillis(t.optLong("due")); hm = new int[]{k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE)}; }
            until = atPeriod(now + 86400000L, hm);
        } else until = at;
        ContentValues v = new ContentValues();
        v.put("status", "ertelendi");
        v.put("due_at", until);
        v.put("has_time", 1);
        v.put("snooze_count", t.optInt("snoozes") + 1);
        db.updateTask(id, v);
        Notify.cancel(c, id);
        Calendar k = Calendar.getInstance(); k.setTimeInMillis(until);
        try {
            db.learn("erteleme", new JSONObject().put("task", id).put("title", t.optString("title")).put("mode", mode)
                .put("hour", k.get(Calendar.HOUR_OF_DAY)).put("hadTime", t.optBoolean("hasTime")).put("count", t.optInt("snoozes") + 1));
        } catch (Exception ignored) { }
        schedule(c, id);
        changed(c);
    }

    /** Sil: the task is cancelled (kept for undo), its reminder removed. */
    static void cancel(Context c, long id) {
        Db db = Db.get(c);
        JSONObject t = db.task(id);
        if (t == null) return;
        ContentValues v = new ContentValues();
        v.put("status", "iptal");
        v.putNull("conf");
        db.updateTask(id, v);
        db.setReminder(id, -1);
        Alarms.cancel(c, id);
        Notify.cancel(c, id);
        if (t.has("note")) db.archiveIfDone(t.optLong("note"));
        changed(c);
    }

    /** Geri al: a done or deleted task is open again (and its note comes back from the archive). */
    static void undo(Context c, long id) {
        Db db = Db.get(c);
        JSONObject t = db.task(id);
        if (t == null) return;
        ContentValues v = new ContentValues();
        v.put("status", "acik");
        v.putNull("completed_at");
        db.updateTask(id, v);
        if (t.has("note")) db.setNote(t.optLong("note"), "archived", 0L);
        schedule(c, id);
        changed(c);
    }

    /** Task screen edits. */
    static void save(Context c, JSONObject e) throws Exception {
        Db db = Db.get(c);
        long id = e.getLong("id");
        JSONObject was = db.task(id);
        if (was == null) return;
        Brain.corrections(db, was, e, null);
        db.updateTask(id, Brain.values(e, null));
        schedule(c, id);
        changed(c);
    }

    /** "Bunu bölelim mi?": one line, one task; same note, date and category; the original is cancelled. */
    static void split(Context c, long id, String lines) {
        Db db = Db.get(c);
        JSONObject t = db.task(id);
        if (t == null) return;
        for (String line : lines.split("\n")) {
            if (line.trim().isEmpty()) continue;
            Parser.Task k = new Parser.Task();
            k.title = Parser.cap(line.trim());
            k.due = t.optLong("due", -1); k.hasTime = t.optBoolean("hasTime"); k.priority = t.optInt("prio", 2);
            k.person = t.optString("person", null); k.category = (int) t.optLong("cat", Db.OTHER);
            long nid = db.addTask(t.optLong("note", 0), k, "acik");
            db.updateTask(nid, conflessValues());
            schedule(c, nid);
        }
        try { db.learn("bolme", new JSONObject().put("task", id).put("title", t.optString("title")).put("into", lines)); } catch (Exception ignored) { }
        cancel(c, id);
    }

    /** After every change: home-screen widget and the open page. */
    static void changed(Context c) {
        Widget.refresh(c);
        Listener l = listener;
        if (l != null) l.onTasksChanged();
    }

    /** All reminders again (after a restart, an update or a clock change): the database is the truth. */
    static void restoreAll(Context c) {
        Db db = Db.get(c);
        long now = System.currentTimeMillis();
        for (long[] r : db.pendingReminders()) {
            if (r[2] <= now) { db.reminderFired(r[1]); continue; }   // missed while off: the task shows as overdue instead
            Alarms.set(c, r[1], r[2]);
        }
        Alarms.morning(c);
        Widget.refresh(c);
    }
}
