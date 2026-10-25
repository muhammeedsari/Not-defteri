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
import java.util.Locale;

/** The app's only store (plan section 5). Times are epoch milliseconds; audio_path is a file name in files/audio.
    "Overdue" is not stored: an open task whose due_at has passed is shown as overdue by the query. */
final class Db extends SQLiteOpenHelper {
    private static final int VERSION = 1;
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) { }

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
    String notes() {
        JSONArray a = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT n.id, n.created_at, n.audio_path, n.audio_ms, COALESCE(n.edited_text, n.transcript), c.name,"
            + " (SELECT COUNT(*) FROM tasks t WHERE t.note_id = n.id AND t.status NOT IN ('bitti','iptal'))"
            + " FROM notes n LEFT JOIN categories c ON c.id = n.category_id WHERE n.archived = 0 ORDER BY n.created_at DESC", null);
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
