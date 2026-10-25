package app.notdefteri;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The app's only store (plan section 5). Times are epoch milliseconds; audio_path is a file name in files/audio.
    "Overdue" is not stored: an open task whose due_at has passed is shown as overdue by the query. */
final class Db extends SQLiteOpenHelper {
    private static final int VERSION = 2;
    private static Db I;

    /** Fixed categories, created on first start; the user may add more, the app never does. */
    static final String[][] CATEGORIES = {
        {"İş", "work"}, {"Ev", "home"}, {"Alışveriş", "cart"}, {"Para", "money"},
        {"Sağlık", "health"}, {"Fikir", "idea"}, {"Diğer", "other"}};
    static final int OTHER = 7;   // id of "Diğer"

    /** Default settings; periods are where "sabah/öğlen/akşam/gece" land until learning moves them. */
    private static final String[][] SETTINGS = {
        {"morning_summary", "08:00"}, {"period_sabah", "09:00"}, {"period_oglen", "12:00"},
        {"period_aksam", "19:00"}, {"period_gece", "22:00"}, {"asr_model", "base"}};

    private final Context app;

    private Db(Context c) {
        super(c.getApplicationContext(), "notdefteri.db", null, VERSION);
        app = c.getApplicationContext();
    }

    static synchronized Db get(Context c) {
        if (I == null) I = new Db(c);
        return I;
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, is_fixed INTEGER NOT NULL DEFAULT 0, icon TEXT)");
        db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY, created_at INTEGER NOT NULL, audio_path TEXT, audio_ms INTEGER,"
            + " transcript TEXT, edited_text TEXT, category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL,"
            + " source TEXT NOT NULL DEFAULT 'ses' CHECK (source IN ('ses','yazi')), archived INTEGER NOT NULL DEFAULT 0)");
        // priority: 1 high, 2 normal, 3 low. recurrence: rule text from the parser ("her pazartesi" → step 5 decides the format)
        db.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY, note_id INTEGER REFERENCES notes(id) ON DELETE CASCADE,"
            + " title TEXT NOT NULL, due_at INTEGER, has_time INTEGER NOT NULL DEFAULT 0,"
            + " priority INTEGER NOT NULL DEFAULT 2 CHECK (priority BETWEEN 1 AND 3),"
            + " status TEXT NOT NULL DEFAULT 'gelen' CHECK (status IN ('gelen','acik','ertelendi','bitti','iptal')),"
            + " recurrence TEXT, person TEXT, snooze_count INTEGER NOT NULL DEFAULT 0, completed_at INTEGER,"
            + " category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL)");
        db.execSQL("CREATE TABLE reminders (id INTEGER PRIMARY KEY, task_id INTEGER REFERENCES tasks(id) ON DELETE CASCADE,"
            + " trigger_at INTEGER NOT NULL, kind TEXT NOT NULL CHECK (kind IN ('zaman','konum','ozet')),"
            + " state TEXT NOT NULL DEFAULT 'bekliyor' CHECK (state IN ('bekliyor','calisti','iptal')))");
        db.execSQL("CREATE TABLE learning_events (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, payload TEXT, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE keyword_weights (word TEXT NOT NULL, category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE,"
            + " weight REAL NOT NULL DEFAULT 0, PRIMARY KEY (word, category_id))");
        db.execSQL("CREATE TABLE user_dictionary (term TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK (kind IN ('kisi','yer','kisaltma')), count INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)");

        db.execSQL("CREATE INDEX tasks_open ON tasks (status, due_at)");
        db.execSQL("CREATE INDEX tasks_note ON tasks (note_id)");
        db.execSQL("CREATE INDEX notes_time ON notes (created_at)");
        db.execSQL("CREATE INDEX reminders_due ON reminders (state, trigger_at)");

        for (int i = 0; i < CATEGORIES.length; i++)
            db.execSQL("INSERT INTO categories (id, name, is_fixed, icon) VALUES (?, ?, 1, ?)", new Object[]{i + 1, CATEGORIES[i][0], CATEGORIES[i][1]});
        for (String[] s : SETTINGS)
            db.execSQL("INSERT INTO settings (key, value) VALUES (?, ?)", new Object[]{s[0], s[1]});
        adoptRecordings(db);
        onUpgrade(db, 1, VERSION);
    }

    /** 2: the confirmation card (notes.review, tasks.conf) and task times for the learning log and the 30-day rule */
    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from < 2) {
            db.execSQL("ALTER TABLE notes ADD COLUMN review TEXT");          // JSON of what the card still has to show; NULL = reviewed
            db.execSQL("ALTER TABLE tasks ADD COLUMN conf TEXT");           // field confidences from the parser, for the card
            db.execSQL("ALTER TABLE tasks ADD COLUMN created_at INTEGER");
            db.execSQL("ALTER TABLE tasks ADD COLUMN touched_at INTEGER");  // last time the user did something with it
        }
    }

    /** 0.1 kept recordings as bare files: each one becomes a note. */
    private void adoptRecordings(SQLiteDatabase db) {
        File[] fs = Recorder.dir(app).listFiles();
        if (fs == null) return;
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        for (File a : fs) {
            String n = a.getName();
            if (!n.endsWith(".m4a")) continue;
            long t;
            try { t = f.parse(n.substring(0, n.length() - 4)).getTime(); } catch (Exception e) { t = a.lastModified(); }
            ContentValues v = new ContentValues();
            v.put("created_at", t);
            v.put("audio_path", n);
            v.put("audio_ms", Recorder.duration(a));
            v.put("category_id", OTHER);
            db.insert("notes", null, v);
        }
    }

    /* ---------- notes ---------- */

    long addVoiceNote(String audio, long ms) {
        ContentValues v = new ContentValues();
        v.put("created_at", System.currentTimeMillis());
        v.put("audio_path", audio);
        v.put("audio_ms", ms);
        v.put("category_id", OTHER);   // until the parser (step 5) picks one
        return getWritableDatabase().insert("notes", null, v);
    }

    /** Notes that are not archived, newest first, with their category and open task count. */
    String notes() { return notes("n.archived = 0", null); }

    String notes(String where, String[] args) {
        JSONArray a = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT n.id, n.created_at, n.audio_path, n.audio_ms, COALESCE(n.edited_text, n.transcript), c.name,"
            + " (SELECT COUNT(*) FROM tasks t WHERE t.note_id = n.id AND t.status NOT IN ('bitti','iptal')), n.category_id, n.archived, n.review"
            + " FROM notes n LEFT JOIN categories c ON c.id = n.category_id WHERE " + where + " ORDER BY n.created_at DESC LIMIT 500", args);
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(0));
                o.put("at", c.getLong(1));
                if (!c.isNull(2)) o.put("audio", c.getString(2));
                o.put("ms", c.getLong(3));
                if (!c.isNull(4)) o.put("text", c.getString(4));
                if (!c.isNull(5)) o.put("cat", c.getString(5));
                o.put("tasks", c.getInt(6));
                if (!c.isNull(7)) o.put("catId", c.getLong(7));
                o.put("archived", c.getInt(8) == 1);
                o.put("review", !c.isNull(9));
                a.put(o);
            }
        } catch (Exception ignored) {
        } finally {
            c.close();
        }
        return a.toString();
    }

    /** Deletes the note, its tasks and reminders (cascade) and its audio file. */
    boolean deleteNote(long id) {
        SQLiteDatabase db = getWritableDatabase();
        String audio = null;
        Cursor c = db.rawQuery("SELECT audio_path FROM notes WHERE id = ?", new String[]{String.valueOf(id)});
        try { if (c.moveToFirst() && !c.isNull(0)) audio = c.getString(0); } finally { c.close(); }
        boolean ok = db.delete("notes", "id = ?", new String[]{String.valueOf(id)}) > 0;
        if (ok && audio != null) Recorder.delete(app, audio);
        return ok;
    }

    /** Notes with audio that still wait for speech-to-text, oldest first. */
    long[] pendingTranscripts() {
        Cursor c = getReadableDatabase().rawQuery("SELECT id FROM notes WHERE transcript IS NULL AND audio_path IS NOT NULL ORDER BY created_at LIMIT 20", null);
        try {
            long[] ids = new long[c.getCount()];
            for (int i = 0; c.moveToNext(); i++) ids[i] = c.getLong(0);
            return ids;
        } finally {
            c.close();
        }
    }

    String audioOf(long id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT audio_path FROM notes WHERE id = ?", new String[]{String.valueOf(id)});
        try { return c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
    }

    /** "" means no speech was found; NULL means not written down yet. */
    void setTranscript(long id, String text) {
        ContentValues v = new ContentValues();
        v.put("transcript", text);
        getWritableDatabase().update("notes", v, "id = ?", new String[]{String.valueOf(id)});
    }

    long addTextNote(String text) {
        ContentValues v = new ContentValues();
        v.put("created_at", System.currentTimeMillis());
        v.put("transcript", text);
        v.put("source", "yazi");
        v.put("category_id", OTHER);
        return getWritableDatabase().insert("notes", null, v);
    }

    String noteText(long id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(edited_text, transcript) FROM notes WHERE id = ?", new String[]{String.valueOf(id)});
        try { return c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
    }

    void setNote(long id, String col, Object value) {
        ContentValues v = new ContentValues();
        if (value == null) v.putNull(col);
        else if (value instanceof Long || value instanceof Integer) v.put(col, ((Number) value).longValue());
        else v.put(col, value.toString());
        getWritableDatabase().update("notes", v, "id = ?", new String[]{String.valueOf(id)});
    }

    /** The next note whose confirmation card has not been seen, or 0. */
    long nextReview() {
        Cursor c = getReadableDatabase().rawQuery("SELECT id FROM notes WHERE review IS NOT NULL ORDER BY created_at LIMIT 1", null);
        try { return c.moveToFirst() ? c.getLong(0) : 0; } finally { c.close(); }
    }

    /** Archived when none of its tasks is open any more (plan 7). */
    void archiveIfDone(long noteId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tasks WHERE note_id = ? AND status IN ('gelen','acik','ertelendi')", new String[]{String.valueOf(noteId)});
        try { if (c.moveToFirst() && c.getInt(0) == 0) setNote(noteId, "archived", 1L); } finally { c.close(); }
    }

    /* ---------- tasks ---------- */

    static final String OPEN = "('gelen','acik','ertelendi')";
    private static final String TASK_COLS = "t.id, t.note_id, t.title, t.due_at, t.has_time, t.priority, t.status, t.recurrence, t.person,"
        + " t.snooze_count, t.completed_at, t.category_id, c.name, t.conf, t.created_at, t.touched_at, n.audio_path, COALESCE(n.edited_text, n.transcript)";

    long addTask(long noteId, Parser.Task k, String status) {
        ContentValues v = new ContentValues();
        if (noteId > 0) v.put("note_id", noteId);
        v.put("title", k.title);
        if (k.due >= 0) v.put("due_at", k.due);
        v.put("has_time", k.hasTime ? 1 : 0);
        v.put("priority", k.priority);
        v.put("status", status);
        if (k.recurrence != null) v.put("recurrence", k.recurrence);
        if (k.person != null) v.put("person", k.person);
        v.put("category_id", k.category);
        try {
            JSONObject c = new JSONObject();
            c.put("date", k.dateConf); c.put("person", k.personConf); c.put("cat", k.catConf); c.put("prio", k.priorityConf());
            c.put("deadline", k.deadline);
            v.put("conf", c.toString());
        } catch (Exception ignored) { }
        long now = System.currentTimeMillis();
        v.put("created_at", now);
        v.put("touched_at", now);
        return getWritableDatabase().insert("tasks", null, v);
    }

    void updateTask(long id, ContentValues v) {
        v.put("touched_at", System.currentTimeMillis());
        getWritableDatabase().update("tasks", v, "id = ?", new String[]{String.valueOf(id)});
    }

    JSONObject task(long id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT " + TASK_COLS + " FROM tasks t LEFT JOIN categories c ON c.id = t.category_id"
            + " LEFT JOIN notes n ON n.id = t.note_id WHERE t.id = ?", new String[]{String.valueOf(id)});
        try { return c.moveToFirst() ? taskJson(c) : null; } catch (Exception e) { return null; } finally { c.close(); }
    }

    private static JSONObject taskJson(Cursor c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", c.getLong(0));
        if (!c.isNull(1)) o.put("note", c.getLong(1));
        o.put("title", c.getString(2));
        if (!c.isNull(3)) o.put("due", c.getLong(3));
        o.put("hasTime", c.getInt(4) == 1);
        o.put("prio", c.getInt(5));
        o.put("status", c.getString(6));
        if (!c.isNull(7)) o.put("rec", c.getString(7));
        if (!c.isNull(8)) o.put("person", c.getString(8));
        o.put("snoozes", c.getInt(9));
        if (!c.isNull(10)) o.put("doneAt", c.getLong(10));
        if (!c.isNull(11)) o.put("cat", c.getLong(11));
        if (!c.isNull(12)) o.put("catName", c.getString(12));
        if (!c.isNull(13)) o.put("conf", new JSONObject(c.getString(13)));
        if (!c.isNull(14)) o.put("created", c.getLong(14));
        if (!c.isNull(15)) o.put("touched", c.getLong(15));
        if (!c.isNull(16)) o.put("audio", c.getString(16));
        if (!c.isNull(17)) o.put("noteText", c.getString(17));
        return o;
    }

    private JSONArray tasks(String where, String[] args) {
        JSONArray a = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery("SELECT " + TASK_COLS + " FROM tasks t LEFT JOIN categories c ON c.id = t.category_id"
            + " LEFT JOIN notes n ON n.id = t.note_id WHERE " + where, args);
        try { while (c.moveToNext()) a.put(taskJson(c)); } catch (Exception ignored) { } finally { c.close(); }
        return a;
    }

    JSONArray noteTasks(long noteId) { return tasks("t.note_id = ? ORDER BY t.id", new String[]{String.valueOf(noteId)}); }

    /** Today screen: open tasks, tasks done in the last 60 days, and notes without tasks (plan 7). */
    String board() {
        JSONObject o = new JSONObject();
        try {
            o.put("open", tasks("t.status IN " + OPEN + " ORDER BY t.priority, t.due_at", null));
            o.put("done", tasks("t.status = 'bitti' AND t.completed_at > ? ORDER BY t.completed_at DESC LIMIT 300",
                new String[]{String.valueOf(System.currentTimeMillis() - 60L * 86400000L)}));
            o.put("notes", new JSONArray(notes("n.archived = 0 AND NOT EXISTS (SELECT 1 FROM tasks x WHERE x.note_id = n.id AND x.status <> 'iptal')", null)));
        } catch (Exception ignored) { }
        return o.toString();
    }

    /** Notes screen: category (0 = all), filter (all|tasks|plain|archived), words to look for in the text and task titles. */
    String search(long cat, String filter, String q) {
        StringBuilder w = new StringBuilder("1 = 1");
        List<String> args = new ArrayList<>();
        if (cat > 0) { w.append(" AND n.category_id = ?"); args.add(String.valueOf(cat)); }
        if ("archived".equals(filter)) w.append(" AND n.archived = 1");
        else {
            if (!"all".equals(filter)) w.append(" AND n.archived = 0");
            if ("tasks".equals(filter)) w.append(" AND EXISTS (SELECT 1 FROM tasks x WHERE x.note_id = n.id)");
            if ("plain".equals(filter)) w.append(" AND NOT EXISTS (SELECT 1 FROM tasks x WHERE x.note_id = n.id)");
        }
        if (q != null && !q.trim().isEmpty()) {
            for (String word : q.trim().toLowerCase(Parser.TR).split("\\s+")) {
                w.append(" AND (LOWER(COALESCE(n.edited_text, n.transcript, '')) LIKE ? OR EXISTS (SELECT 1 FROM tasks x WHERE x.note_id = n.id AND LOWER(x.title) LIKE ?))");
                args.add("%" + word + "%"); args.add("%" + word + "%");
            }
        }
        return notes(w.toString(), args.toArray(new String[0]));
    }

    /** Open tasks as the done-sentence matcher sees them. */
    List<Parser.Open> openTasks() {
        List<Parser.Open> a = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT id, title, person FROM tasks WHERE status IN ('acik','ertelendi')", null);
        try { while (c.moveToNext()) a.add(new Parser.Open(c.getLong(0), c.getString(1), c.getString(2))); } finally { c.close(); }
        return a;
    }

    /** Titles from the last 7 days: saying the same thing again raises its priority. */
    List<String> recentTitles() {
        List<String> a = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT title FROM tasks WHERE created_at > ?", new String[]{String.valueOf(System.currentTimeMillis() - 7L * 86400000L)});
        try { while (c.moveToNext()) a.add(c.getString(0)); } finally { c.close(); }
        return a;
    }

    /** Waiting reminders, for scheduling after a restart. */
    List<long[]> pendingReminders() {
        List<long[]> a = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT r.id, r.task_id, r.trigger_at FROM reminders r JOIN tasks t ON t.id = r.task_id"
            + " WHERE r.state = 'bekliyor' AND r.kind = 'zaman' AND t.status IN " + OPEN, null);
        try { while (c.moveToNext()) a.add(new long[]{c.getLong(0), c.getLong(1), c.getLong(2)}); } finally { c.close(); }
        return a;
    }

    /** Replaces the task's waiting time reminder; at < 0 only removes it. */
    void setReminder(long taskId, long at) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("reminders", "task_id = ? AND kind = 'zaman' AND state = 'bekliyor'", new String[]{String.valueOf(taskId)});
        if (at < 0) return;
        ContentValues v = new ContentValues();
        v.put("task_id", taskId);
        v.put("trigger_at", at);
        v.put("kind", "zaman");
        db.insert("reminders", null, v);
    }

    void reminderFired(long taskId) {
        ContentValues v = new ContentValues();
        v.put("state", "calisti");
        getWritableDatabase().update("reminders", v, "task_id = ? AND kind = 'zaman' AND state = 'bekliyor'", new String[]{String.valueOf(taskId)});
    }

    /** Morning summary counts: {today, overdue, high, titles[]} */
    JSONObject summary(long dayStart) {
        JSONObject o = new JSONObject();
        long dayEnd = dayStart + 86400000L;
        try {
            o.put("today", count("status IN " + OPEN + " AND due_at >= ? AND due_at < ?", dayStart, dayEnd));
            o.put("overdue", count("status IN " + OPEN + " AND due_at < ?", dayStart));
            o.put("high", count("status IN " + OPEN + " AND priority = 1 AND (due_at IS NULL OR due_at < ?)", dayEnd));
            JSONArray t = new JSONArray();
            Cursor c = getReadableDatabase().rawQuery("SELECT title FROM tasks WHERE status IN " + OPEN + " AND (due_at < ? OR (priority = 1 AND due_at IS NULL))"
                + " ORDER BY CASE WHEN due_at < ? THEN 0 ELSE 1 END, priority, due_at LIMIT 6", new String[]{String.valueOf(dayEnd), String.valueOf(dayStart)});
            try { while (c.moveToNext()) t.put(c.getString(0)); } finally { c.close(); }
            o.put("titles", t);
        } catch (Exception ignored) { }
        return o;
    }

    int count(String where, long... args) {
        String[] a = new String[args.length];
        for (int i = 0; i < args.length; i++) a[i] = String.valueOf(args[i]);
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tasks WHERE " + where, a);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    /* ---------- learning (v1 records, v2 applies) ---------- */

    void learn(String kind, JSONObject payload) {
        ContentValues v = new ContentValues();
        v.put("kind", kind);
        v.put("payload", payload.toString());
        v.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insert("learning_events", null, v);
    }

    /** Parser inputs that live in the database. */
    Parser.Periods periods() {
        Parser.Periods p = new Parser.Periods();
        p.sabah = hm(setting("period_sabah"), p.sabah);
        p.oglen = hm(setting("period_oglen"), p.oglen);
        p.aksam = hm(setting("period_aksam"), p.aksam);
        p.gece = hm(setting("period_gece"), p.gece);
        return p;
    }

    static int[] hm(String v, int[] def) {
        try { String[] x = v.split(":"); return new int[]{Integer.parseInt(x[0]), Integer.parseInt(x[1])}; } catch (Exception e) { return def; }
    }

    java.util.Set<String> names() {
        java.util.Set<String> s = new java.util.HashSet<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT term FROM user_dictionary WHERE kind = 'kisi'", null);
        try { while (c.moveToNext()) s.add(c.getString(0).toLowerCase(Parser.TR)); } finally { c.close(); }
        return s;
    }

    long addCategory(String name) {
        ContentValues v = new ContentValues();
        v.put("name", name.trim());
        v.put("is_fixed", 0);
        v.put("icon", "tag");
        return getWritableDatabase().insert("categories", null, v);
    }

    boolean deleteCategory(long id) {
        return getWritableDatabase().delete("categories", "id = ? AND is_fixed = 0", new String[]{String.valueOf(id)}) > 0;
    }

    String settings() {
        JSONObject o = new JSONObject();
        Cursor c = getReadableDatabase().rawQuery("SELECT key, value FROM settings", null);
        try { while (c.moveToNext()) o.put(c.getString(0), c.getString(1)); } catch (Exception ignored) { } finally { c.close(); }
        return o.toString();
    }

    /* ---------- backup ---------- */

    static final String[] TABLES = {"categories", "notes", "tasks", "reminders", "learning_events", "keyword_weights", "user_dictionary", "settings"};

    JSONObject dump() throws Exception {
        JSONObject o = new JSONObject();
        o.put("app", "notdefteri");
        o.put("db", VERSION);
        o.put("at", System.currentTimeMillis());
        SQLiteDatabase db = getReadableDatabase();
        for (String t : TABLES) {
            JSONArray rows = new JSONArray();
            Cursor c = db.rawQuery("SELECT * FROM " + t, null);
            try {
                while (c.moveToNext()) {
                    JSONObject r = new JSONObject();
                    for (int i = 0; i < c.getColumnCount(); i++) {
                        switch (c.getType(i)) {
                            case Cursor.FIELD_TYPE_INTEGER: r.put(c.getColumnName(i), c.getLong(i)); break;
                            case Cursor.FIELD_TYPE_FLOAT: r.put(c.getColumnName(i), c.getDouble(i)); break;
                            case Cursor.FIELD_TYPE_STRING: r.put(c.getColumnName(i), c.getString(i)); break;
                            default: break;   // NULL stays out
                        }
                    }
                    rows.put(r);
                }
            } finally {
                c.close();
            }
            o.put(t, rows);
        }
        return o;
    }

    /** Replaces everything with a dump (one transaction: all or nothing). */
    void restore(JSONObject o) throws Exception {
        if (!"notdefteri".equals(o.optString("app"))) throw new IllegalArgumentException("not a backup");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = TABLES.length - 1; i >= 0; i--) db.delete(TABLES[i], null, null);
            for (String t : TABLES) {
                JSONArray rows = o.optJSONArray(t);
                if (rows == null) continue;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.getJSONObject(i);
                    ContentValues v = new ContentValues();
                    java.util.Iterator<String> it = r.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        Object x = r.get(k);
                        if (x instanceof Integer || x instanceof Long) v.put(k, ((Number) x).longValue());
                        else if (x instanceof Number) v.put(k, ((Number) x).doubleValue());
                        else v.put(k, String.valueOf(x));
                    }
                    db.insertOrThrow(t, null, v);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /* ---------- categories and settings ---------- */

    String categories() {
        JSONArray a = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery("SELECT id, name, is_fixed, icon FROM categories ORDER BY id", null);
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(0));
                o.put("name", c.getString(1));
                o.put("fixed", c.getInt(2) == 1);
                o.put("icon", c.getString(3));
                a.put(o);
            }
        } catch (Exception ignored) {
        } finally {
            c.close();
        }
        return a.toString();
    }

    String setting(String key) {
        Cursor c = getReadableDatabase().rawQuery("SELECT value FROM settings WHERE key = ?", new String[]{key});
        try { return c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
    }

    void setSetting(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value);
        getWritableDatabase().insertWithOnConflict("settings", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
