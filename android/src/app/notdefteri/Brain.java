package app.notdefteri;

import android.content.ContentValues;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Text → proposals → confirmation card (flows A and C). The parser's tasks are stored as "gelen" (incoming) and the
 * note carries a review; the card turns them into open tasks with reminders, or into a plain note. A closed card
 * saves everything as proposed (plan 3.A.5).
 */
final class Brain {
    private Brain() { }

    /** Reads the note's text and stores what was understood; true when there is something to confirm. */
    static boolean process(Context c, long noteId) {
        Db db = Db.get(c);
        String text = db.noteText(noteId);
        db.getWritableDatabase().delete("tasks", "note_id = ? AND status = 'gelen'", new String[]{String.valueOf(noteId)});
        if (text == null || text.trim().isEmpty()) { db.setNote(noteId, "review", null); return false; }
        Parser.Result r;
        try {
            r = new Parser(System.currentTimeMillis(), db.periods(), db.names(), db.recentTitles()).parse(text);
        } catch (Throwable e) {   // a parser fault must not lose the note: it stays a plain note
            db.setNote(noteId, "review", null);
            return false;
        }
        db.setNote(noteId, "category_id", (long) r.category);
        for (Parser.Task k : r.tasks) db.addTask(noteId, k, "gelen");
        JSONArray done = new JSONArray();
        if (!r.done.isEmpty()) {
            List<Parser.Open> open = db.openTasks();
            for (Parser.Done d : r.done) {
                List<Long> ids = Parser.match(d, open);
                if (ids.isEmpty()) continue;
                JSONArray a = new JSONArray();
                for (Long id : ids) a.put(id.longValue());
                done.put(a);
            }
        }
        boolean card = !r.tasks.isEmpty() || done.length() > 0;
        try {
            JSONObject rv = new JSONObject();
            rv.put("done", done);
            rv.put("catConf", r.catConf);
            db.setNote(noteId, "review", card ? rv.toString() : null);
        } catch (Exception ignored) { }
        Tasks.changed(c);
        return card;
    }

    /** What the card shows for a note: {note, tasks[], done[[{id,title}]]}; null when there is nothing. */
    static String review(Context c, long noteId) {
        Db db = Db.get(c);
        try {
            JSONObject note = null;
            JSONArray ns = new JSONArray(db.notes("n.id = " + noteId, null));
            if (ns.length() > 0) note = ns.getJSONObject(0);
            if (note == null) return "null";
            JSONObject o = new JSONObject();
            o.put("note", note);
            JSONArray tasks = new JSONArray(), all = db.noteTasks(noteId);
            for (int i = 0; i < all.length(); i++) if ("gelen".equals(all.getJSONObject(i).optString("status"))) tasks.put(all.getJSONObject(i));
            o.put("tasks", tasks);
            JSONArray done = new JSONArray();
            String raw = reviewOf(db, noteId);
            if (raw != null) {
                JSONObject rv = new JSONObject(raw);
                o.put("catConf", rv.optDouble("catConf", 0.5));
                JSONArray d = rv.optJSONArray("done");
                for (int i = 0; d != null && i < d.length(); i++) {
                    JSONArray ids = d.getJSONArray(i), cands = new JSONArray();
                    for (int j = 0; j < ids.length(); j++) {
                        JSONObject t = db.task(ids.getLong(j));
                        if (t != null && !"bitti".equals(t.optString("status")) && !"iptal".equals(t.optString("status"))) cands.put(t);
                    }
                    if (cands.length() > 0) done.put(cands);
                }
            }
            o.put("done", done);
            return o.toString();
        } catch (Exception e) {
            return "null";
        }
    }

    private static String reviewOf(Db db, long noteId) {
        android.database.Cursor cur = db.getReadableDatabase().rawQuery("SELECT review FROM notes WHERE id = ?", new String[]{String.valueOf(noteId)});
        try { return cur.moveToFirst() ? cur.getString(0) : null; } finally { cur.close(); }
    }

    /**
     * The card's answer: {note, noteOnly, cat, tasks:[{id,title,due,hasTime,person,cat,prio,rec}], close:[ids]}.
     * Every difference from the proposal is written to the learning log (v1 records, v2 applies).
     */
    static void save(Context c, String json) throws Exception {
        Db db = Db.get(c);
        JSONObject a = new JSONObject(json);
        long noteId = a.getLong("note");
        if (a.optBoolean("noteOnly")) {
            JSONArray all = db.noteTasks(noteId);
            for (int i = 0; i < all.length(); i++) {
                JSONObject t = all.getJSONObject(i);
                if (!"gelen".equals(t.optString("status"))) continue;
                db.learn("gorev_degil", new JSONObject().put("title", t.optString("title")).put("text", db.noteText(noteId)));
                db.getWritableDatabase().delete("tasks", "id = ?", new String[]{String.valueOf(t.getLong("id"))});
            }
        }
        JSONArray ts = a.optJSONArray("tasks");
        for (int i = 0; !a.optBoolean("noteOnly") && ts != null && i < ts.length(); i++) {
            JSONObject e = ts.getJSONObject(i);
            long id = e.getLong("id");
            JSONObject was = db.task(id);
            if (was == null) continue;
            if (e.optBoolean("removed")) { db.getWritableDatabase().delete("tasks", "id = ?", new String[]{String.valueOf(id)}); continue; }
            corrections(db, was, e, db.noteText(noteId));
            db.updateTask(id, values(e, "acik"));
            Tasks.schedule(c, id);
        }
        if (a.has("cat") && !a.isNull("cat")) {
            long cat = a.getLong("cat");
            JSONArray ns = new JSONArray(db.notes("n.id = " + noteId, null));
            long was = ns.length() > 0 ? ns.getJSONObject(0).optLong("catId", Db.OTHER) : Db.OTHER;
            if (cat != was) db.learn("kategori", new JSONObject().put("from", was).put("to", cat).put("words", db.noteText(noteId)));
            db.setNote(noteId, "category_id", cat);
        }
        JSONArray close = a.optJSONArray("close");
        for (int i = 0; close != null && i < close.length(); i++) Tasks.done(c, close.getLong(i), "ses");
        db.setNote(noteId, "review", null);
        // a done sentence with no task of its own is only a command: it goes to the archive
        if (close != null && close.length() > 0 && db.noteTasks(noteId).length() == 0) db.setNote(noteId, "archived", 1L);
        Tasks.changed(c);
    }

    /** Field values from the card or the task screen. */
    static ContentValues values(JSONObject e, String status) {
        ContentValues v = new ContentValues();
        if (e.has("title")) v.put("title", e.optString("title"));
        if (e.has("due")) { if (e.isNull("due") || e.optLong("due", -1) < 0) v.putNull("due_at"); else v.put("due_at", e.optLong("due")); }
        if (e.has("hasTime")) v.put("has_time", e.optBoolean("hasTime") ? 1 : 0);
        if (e.has("person")) { String p = e.optString("person", "").trim(); if (p.isEmpty() || e.isNull("person")) v.putNull("person"); else v.put("person", p); }
        if (e.has("cat")) v.put("category_id", e.optLong("cat"));
        if (e.has("prio")) v.put("priority", e.optInt("prio", 2));
        if (e.has("rec")) { String r = e.isNull("rec") ? "" : e.optString("rec", ""); if (r.isEmpty()) v.putNull("recurrence"); else v.put("recurrence", r); }
        if (status != null) { v.put("status", status); v.putNull("conf"); }
        return v;
    }

    /** Learning log for a card or task-screen edit (plan 8: category, date/time and wording corrections). */
    static void corrections(Db db, JSONObject was, JSONObject now, String text) throws Exception {
        if (now.has("cat") && now.optLong("cat") != was.optLong("cat", Db.OTHER))
            db.learn("kategori", new JSONObject().put("from", was.optLong("cat", Db.OTHER)).put("to", now.optLong("cat")).put("words", was.optString("title")));
        if (now.has("due") && now.optLong("due", -1) != was.optLong("due", -1))
            db.learn("tarih", new JSONObject().put("from", was.optLong("due", -1)).put("to", now.optLong("due", -1))
                .put("fromTime", was.optBoolean("hasTime")).put("toTime", now.optBoolean("hasTime")).put("text", text == null ? "" : text));
        if (now.has("title") && !now.optString("title").equals(was.optString("title")))
            db.learn("metin", new JSONObject().put("from", was.optString("title")).put("to", now.optString("title")));
        if (now.has("person") && !now.optString("person", "").equals(was.optString("person", "")))
            db.learn("kisi", new JSONObject().put("from", was.optString("person", "")).put("to", now.optString("person", "")));
        if (now.has("prio") && now.optInt("prio") != was.optInt("prio"))
            db.learn("oncelik", new JSONObject().put("from", was.optInt("prio")).put("to", now.optInt("prio")).put("title", was.optString("title")));
    }

    /** "Metni düzelt" on the card: keeps the correction, reads the new text again and returns the new card. */
    static String reparse(Context c, long noteId, String text) throws Exception {
        Db db = Db.get(c);
        String was = db.noteText(noteId);
        if (was != null && !was.equals(text)) db.learn("metin", new JSONObject().put("from", was).put("to", text));
        db.setNote(noteId, "edited_text", text);
        process(c, noteId);
        if (reviewOf(db, noteId) == null) {
            // nothing to confirm any more: still show the card once, now as a plain note
            db.setNote(noteId, "review", new JSONObject().put("done", new JSONArray()).toString());
        }
        return review(c, noteId);
    }
}
