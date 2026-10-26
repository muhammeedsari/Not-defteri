package app.notdefteri;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rule-based Turkish parser (plan section 6): dates and times, tasks, people, priority, category, recurrence and
 * "done" sentences. No model, no Android: plain Java so the sample sentence set runs on a desktop JVM.
 * Every field carries a confidence (0..1); the confirmation card highlights the ones below {@link #SURE}.
 */
final class Parser {
    static final Locale TR = new Locale("tr");
    static final double SURE = 0.6;
    /** category ids, in the order Db creates them */
    static final int IS = 1, EV = 2, ALISVERIS = 3, PARA = 4, SAGLIK = 5, FIKIR = 6, DIGER = 7;

    /** Hours the periods mean (settings period_*): sabah, öğlen, akşam, gece. */
    static final class Periods {
        int[] sabah = {9, 0}, oglen = {12, 0}, aksam = {19, 0}, gece = {22, 0};
    }

    static final class Task {
        String title;
        long due = -1;          // epoch ms, -1 = no date
        boolean hasTime, deadline;
        int priority = 2;       // 1 high, 2 normal, 3 low
        String person, recurrence;
        int category = DIGER;
        double dateConf = 1, personConf = 1, catConf = 0.5, prioConf = 0.8;
        double priorityConf() { return prioConf; }
    }

    /** A "done" sentence ("Ahmet'i aradım"): the verb stem and the other words, for {@link #match}. */
    static final class Done {
        String verb;
        final List<String> words = new ArrayList<>();
    }

    static final class Result {
        final List<Task> tasks = new ArrayList<>();
        final List<Done> done = new ArrayList<>();
        int category = DIGER;
        double catConf = 0.5;
    }

    /* ---------- word lists ---------- */

    private static final String[] DAYS = {"pazartesi", "salı", "çarşamba", "perşembe", "cuma", "cumartesi", "pazar"};
    private static final String[] MONTHS = {"ocak", "şubat", "mart", "nisan", "mayıs", "haziran", "temmuz", "ağustos", "eylül", "ekim", "kasım", "aralık"};
    private static final String[] NUMS = {"sıfır", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz", "on", "on bir", "on iki"};
    /** suffixes a number or a day may carry: -de/-te (at), -e/-ye (to), -i (the), -inde (on the …) */
    private static final String[] SUF = {"", "de", "da", "te", "ta", "e", "a", "ye", "ya", "i", "ı", "u", "ü", "yi", "yı", "yu", "yü",
        "si", "sı", "inde", "ında", "unda", "ünde", "nde", "nda", "sinde", "sında", "ine", "ına", "una", "üne", "sine", "sına", "ne", "na", "den", "dan", "ten", "tan",
        "günü", "leri", "ları", "ler", "lar", "nci", "ncı", "ncu", "ncü", "inci", "ıncı", "uncu", "üncü"};
    /** imperative work verbs (plan: ara, al, öde, gönder, götür, yaz, git, randevu al …) */
    static final Set<String> VERBS = set("ara", "al", "öde", "gönder", "götür", "getir", "yaz", "git", "yap", "bak", "sor", "iste", "bitir",
        "hazırla", "yatır", "söyle", "ver", "ilet", "et", "ayarla", "yenile", "oku", "izle", "düzelt", "temizle", "yıka", "ütüle", "topla",
        "ekle", "uğra", "katıl", "bul", "sat", "kur", "doldur", "bırak", "cevapla", "yanıtla", "planla", "çıkar", "değiştir", "tak",
        "ödet", "aldır", "yaptır", "kes", "dön", "ziyaret", "kontrol", "teslim", "iptal", "imzala", "onayla", "paylaş", "ol",
        "hazırlan", "görüş", "buluş", "kalk", "yetiş", "iç", "ölç", "yıkat", "çalıştır", "yolla", "yazdır", "giy", "koş", "ye");
    private static final Set<String> NEED = set("lazım", "gerek", "gerekiyor", "gerekli", "şart", "unutma", "unutmayayım", "unutmamalıyım",
        "hatırlat", "hatırlatsana", "hatırla");
    private static final Set<String> EMPHASIS = set("acil", "acilen", "mutlaka", "hemen", "kesin", "kesinlikle", "önemli", "unutma", "unutmamalıyım", "unutmayayım");
    private static final String[][] LOW = {{"acelesi", "yok"}, {"bir", "ara"}, {"fırsat", "olursa"}, {"fırsat", "bulursam"},
        {"boş", "vakitte"}, {"boş", "vaktimde"}, {"önemli", "değil"}, {"vakit", "olursa"}, {"zaman", "olursa"}, {"müsait", "olunca"}};
    private static final Set<String> FILLER = set("bana", "benim", "lütfen", "şunu", "şey", "ıı", "ııı", "ee", "eee", "yani", "hani", "saat",
        "hatırlat", "hatırlatsana", "hatırla", "unutma", "unutmayayım", "unutmamalıyım", "lazım", "gerek", "gerekiyor", "gerekli", "şart",
        "acil", "acilen", "mutlaka", "hemen", "kesin", "kesinlikle", "önemli", "de", "da");
    private static final Set<String> HONOR = set("abi", "abla", "hoca", "hocam", "bey", "hanım", "amca", "teyze", "usta");
    private static final Set<String> CLOSERS = set("hallettim", "bitti", "bitirdim", "tamamladım", "tamam", "yaptım", "ettim", "halloldu", "oldu");
    private static final Set<String> NOT_PAST = set("adım", "yardım", "kadim", "kendim", "tutum", "ödüm", "ardım", "dedim");
    private static final Set<String> PLACES = set("türkiye", "istanbul", "ankara", "izmir", "bursa", "antalya", "adana", "konya", "kayseri",
        "eskişehir", "trabzon", "samsun", "gaziantep", "mersin", "diyarbakır", "kocaeli", "sakarya", "denizli", "malatya", "erzurum",
        "avrupa", "almanya", "amerika", "google", "whatsapp", "instagram", "youtube", "migros", "bim", "a101", "şok", "carrefour");

    /** keyword → category; short words must match whole (with a small suffix), longer ones by prefix */
    private static final Map<String, Integer> KW = new HashMap<>();
    static {
        kw(PARA, "fatura", "kira", "ödeme", "borç", "borc", "banka", "para", "maaş", "kredi", "taksit", "vergi", "aidat", "havale", "eft",
            "sigorta", "harç", "iban", "hesab", "hesap", "ceza", "senet");
        kw(ALISVERIS, "market", "süt", "ekmek", "yumurta", "peynir", "domates", "alışveriş", "sipariş", "meyve", "sebze", "deterjan",
            "şampuan", "kahve", "çay", "su", "tavuk", "kıyma", "pirinç", "makarna", "yağ", "şeker", "un", "manav", "kasap", "fırın",
            "patates", "soğan", "zeytin", "tuz", "bez", "peçete", "kağıt havlu", "tuvalet kağıdı", "yoğurt", "elma", "muz");
        kw(IS, "toplantı", "müşteri", "rapor", "sunum", "proje", "mail", "e-posta", "eposta", "teklif", "patron", "müdür", "ofis",
            "mesai", "sözleşme", "ihale", "işyeri", "şirket", "tedarikçi", "personel", "bordro", "fatura kes", "iş");
        kw(EV, "temizlik", "çamaşır", "bulaşık", "tamir", "tesisatçı", "kombi", "ev", "mutfak", "banyo", "perde", "boya", "elektrikçi",
            "çöp", "ütü", "musluk", "ampul", "halı", "balkon", "bahçe", "kapıcı", "komşu", "tadilat");
        kw(SAGLIK, "doktor", "hastane", "ilaç", "ilac", "diş", "dişçi", "eczane", "tahlil", "muayene", "aşı", "vitamin", "spor", "tansiyon", "kontrol",
            "göz", "fizik tedavi", "reçete", "randevu", "hekim", "psikolog", "diyet", "yürüyüş");
        kw(FIKIR, "fikir", "fikri", "aklıma", "belki", "düşün", "kitap", "yazı", "hayal", "girişim", "uygulama");
    }

    private static void kw(int cat, String... ws) { for (String w : ws) KW.put(w, cat); }

    private static Set<String> set(String... a) { Set<String> s = new HashSet<>(); for (String x : a) s.add(x); return s; }

    /* ---------- tokens ---------- */

    private static final class Tok {
        final String raw;   // as written (person names keep their capital and apostrophe)
        final String low;   // lower case, apostrophes and punctuation removed
        boolean used;       // taken by a date/time/filler: left out of the title
        Tok(String r) {
            raw = r;
            low = r.toLowerCase(TR).replace("'", "").replaceAll("[^\\p{L}\\p{N}:.\\-]", "").replaceAll("^[.\\-]+|[.:\\-]+$", "");
        }
    }

    private static List<Tok> toks(String s) {
        List<Tok> t = new ArrayList<>();
        for (String w : s.trim().split("\\s+")) if (!w.isEmpty()) { Tok k = new Tok(w); if (!k.low.isEmpty()) t.add(k); }
        return t;
    }

    /* ---------- entry ---------- */

    private final long now;
    private final Periods per;
    private final Set<String> names;      // personal dictionary: people, lower case
    private final List<String> recent;    // titles of tasks from the last 7 days (repeat → higher priority)

    Parser(long now, Periods per, Set<String> names, List<String> recent) {
        this.now = now;
        this.per = per == null ? new Periods() : per;
        this.names = names == null ? new HashSet<String>() : names;
        this.recent = recent == null ? new ArrayList<String>() : recent;
    }

    Result parse(String text) {
        Result r = new Result();
        if (text == null) return r;
        String t = text.replace('’', '\'').replace('‘', '\'').replace('`', '\'').replaceAll("\\s+", " ").trim();
        int[] catScore = new int[8];
        for (String sentence : t.split("(?<=[.!?;])\\s+|\\n")) {
            List<List<Tok>> clauses = clauses(sentence);
            Task prev = null;
            for (List<Tok> c : clauses) {
                score(c, catScore);
                if (hasTask(c)) {
                    Task k = task(c, prev);
                    r.tasks.add(k);
                    prev = k;
                } else {
                    Done d = done(c);
                    if (d != null) r.done.add(d);
                    else {   // "öbür gün 10'da toplantı var", "15 kasım 14:30 noter": a dated plan is a task too
                        Task k = appointment(c, prev);
                        if (k != null) { r.tasks.add(k); prev = k; }
                    }
                }
            }
        }
        int best = DIGER, top = 0, second = 0;
        for (int i = 1; i < 7; i++) { if (catScore[i] > top) { second = top; top = catScore[i]; best = i; } else if (catScore[i] > second) second = catScore[i]; }
        if (top > 0) { r.category = best; r.catConf = top > second + 1 ? (top >= 4 ? 0.9 : 0.7) : 0.5; }
        if (!r.tasks.isEmpty()) { r.category = r.tasks.get(0).category; r.catConf = r.tasks.get(0).catConf; }
        return r;
    }

    /** Splits at commas, "ve" and "sonra" only where both sides hold a task ("Ahmet ve Ayşe'yi ara" stays whole). */
    private List<List<Tok>> clauses(String sentence) {
        List<List<Tok>> parts = new ArrayList<>();
        List<Tok> cur = new ArrayList<>();
        for (Tok k : toks(sentence)) {
            boolean comma = k.raw.endsWith(",");
            if (joiner(k, cur)) { parts.add(cur); cur = new ArrayList<>(); }
            cur.add(k);
            if (comma) { parts.add(cur); cur = new ArrayList<>(); }
        }
        if (!cur.isEmpty()) parts.add(cur);
        List<List<Tok>> out = new ArrayList<>();
        List<Tok> acc = new ArrayList<>();
        boolean accTask = false;
        List<Tok> carry = new ArrayList<>();
        for (List<Tok> p : parts) {
            // "unutmayayım, cuma kirayı öde" / "acil, …": a part of only emphasis words belongs to the next one
            boolean only = true;
            for (Tok k : p) if (!EMPHASIS.contains(k.low.replace(",", "")) && !NEED.contains(k.low)) { only = false; break; }
            if (only && p != parts.get(parts.size() - 1)) { carry.addAll(p); continue; }
            if (!carry.isEmpty()) { carry.addAll(p); p = carry; carry = new ArrayList<>(); }
            boolean pt = hasTask(p);
            if (pt && (accTask || !acc.isEmpty() && done(acc) != null)) { out.add(acc); acc = new ArrayList<>(); accTask = false; }
            if (!pt && accTask && done(p) != null) { out.add(acc); acc = new ArrayList<>(); accTask = false; }
            // "Ahmet'i aradım, yarın 3'te toplantı var": what follows a done sentence is read on its own
            else if (!pt && !accTask && !acc.isEmpty() && done(acc) != null) { out.add(acc); acc = new ArrayList<>(); }
            acc.addAll(p);
            accTask |= pt;
        }
        if (!acc.isEmpty()) {
            if (!accTask && !out.isEmpty() && hasTask(out.get(out.size() - 1)) && done(acc) == null) out.get(out.size() - 1).addAll(acc);
            else out.add(acc);
        }
        // a joiner that opens a clause is dropped; inside a clause ("Ahmet ve Ayşe'yi ara") it stays
        for (List<Tok> c : out) if (!c.isEmpty() && (c.get(0).low.equals("ve") || c.get(0).low.startsWith("sonra"))) c.get(0).used = true;
        return out;
    }

    /** "ve" / "sonra" between two parts; not the "sonra" of "öğleden sonra", "3 gün sonra", "yemekten sonra" */
    private static boolean joiner(Tok k, List<Tok> cur) {
        if (cur.isEmpty()) return false;
        if (k.low.equals("ve")) return true;
        if (!k.low.equals("sonra") && !k.low.equals("sonrada")) return false;
        String p = cur.get(cur.size() - 1).low;
        return !(p.equals("öğleden") || p.startsWith("gün") || p.startsWith("hafta") || p.equals("ay") || p.equals("yıl") || p.matches(".+[dt][ae]n"));
    }

    /* ---------- task detection ---------- */

    static boolean necessity(String w) { return w.length() > 5 && w.matches(".+m[ae]l[ıi](y[ıi]m|s[ıi]n|y[ıi]z|s[ıi]n[ıi]z|l[ae]r|d[ıi]r)?"); }
    static boolean future(String w) { return w.length() > 5 && w.matches(".+[ae]c[ae]ğ[ıi](m|z)"); }

    private boolean hasTask(List<Tok> c) {
        for (int i = 0; i < c.size(); i++) {
            String w = c.get(i).low;
            if (NEED.contains(w) || necessity(w) || future(w)) return true;
            if (w.equals("bin") && i == c.size() - 1) return true;   // "servise bin"; not the "bin" of "5 bin lira"
            if (VERBS.contains(w)) {
                // "bir ara" means "some time", not "call"
                if (w.equals("ara") && i > 0 && c.get(i - 1).low.equals("bir")) continue;
                return true;
            }
        }
        return false;
    }

    private static final String[] PLANS = {"randevu", "toplantı", "görüşme", "sınav", "maç", "düğün", "nişan", "ders", "konser", "uçuş", "tören", "mülakat", "son"};

    /** Only with a plan word or a clock time, never in a past or hearsay sentence ("dün toplantı vardı", "yağacakmış"). */
    private Task appointment(List<Tok> c, Task prev) {
        boolean plan = false;
        for (Tok t : c) {
            String w = t.low;
            if (w.equals("dün") || w.equals("geçen") || w.matches(".+(m[ıiuü]ş|[dt][ıiuü]m|[dt][ıiuü]k|[dt][ıiuü]n[ıiuü]z)") || w.matches("(var|yok|ol)[dt][ıiuü].*")) return null;
            if (w.equals("var") || w.equals("saat") || w.matches("\\d{1,2}[:.]\\d{2}.*")) plan = true;
            for (String p : PLANS) if (w.startsWith(p)) plan = true;
        }
        if (!plan) return null;
        List<Tok> copy = new ArrayList<>();
        // fresh tokens: the done-sentence check before this may have marked some; only a leading "ve/sonra" stays out
        for (Tok t : c) { Tok x = new Tok(t.raw); x.used = t.used && (t.low.equals("ve") || t.low.startsWith("sonra")); copy.add(x); }
        Task k = task(copy, null);
        if (k.due < startOfDay(today()) || k.title.isEmpty()) return null;
        for (int i = 0; i < c.size(); i++) c.get(i).used = copy.get(i).used;
        return k;
    }

    /* ---------- one task ---------- */

    private Task task(List<Tok> c, Task prev) {
        Task k = new Task();
        Cal cal = new Cal(now);
        boolean dateFound = recurrence(c, k, cal);
        if (!dateFound) dateFound = date(c, k, cal);
        boolean timeFound = time(c, k, cal);
        if (!dateFound && !timeFound && prev != null && prev.due >= 0) {   // "yarın Ahmet'i ara ve faturayı öde": both tomorrow
            k.due = prev.due; k.hasTime = prev.hasTime; k.deadline = prev.deadline; k.dateConf = prev.dateConf * 0.9;
        } else if (dateFound || timeFound) {
            if (!dateFound) {   // only a time: today if still ahead, else tomorrow
                cal.day = 0;
                if (cal.at(now) <= now) cal.day = 1;
                k.dateConf = Math.min(k.dateConf, 0.75);
            }
            k.due = cal.at(now);
            k.hasTime = cal.h >= 0;
            if (k.recurrence != null) {   // the first occurrence still ahead
                long end = k.hasTime ? now : now - 86400000L + 1;
                while (k.due < end) k.due = next(k.recurrence, k.due);
            }
        }
        person(c, k);
        priority(c, k);
        int[] cs = new int[8];
        score(c, cs);
        int best = DIGER, top = 0, second = 0;
        for (int i = 1; i < 7; i++) { if (cs[i] > top) { second = top; top = cs[i]; best = i; } else if (cs[i] > second) second = cs[i]; }
        if (top > 0) { k.category = best; k.catConf = top > second + 1 ? (top >= 4 ? 0.9 : 0.7) : 0.5; } else { k.category = DIGER; k.catConf = 0.4; }
        k.title = title(c);
        return k;
    }

    /** Day offset + time of day being built; h < 0 means no time. */
    private static final class Cal {
        int day = Integer.MIN_VALUE, h = -1, m = 0;
        int y = -1, mo = -1, dom = -1;   // absolute date when set
        Cal(long now) { }
        long at(long now) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            if (y >= 0) { c.set(Calendar.YEAR, y); c.set(Calendar.MONTH, mo); c.set(Calendar.DAY_OF_MONTH, dom); }
            else if (day != Integer.MIN_VALUE) c.add(Calendar.DAY_OF_MONTH, day);
            c.set(Calendar.HOUR_OF_DAY, h >= 0 ? h : 0);
            c.set(Calendar.MINUTE, h >= 0 ? m : 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        }
    }

    private Calendar today() { Calendar c = Calendar.getInstance(); c.setTimeInMillis(now); return c; }

    /** 0 = Monday … 6 = Sunday */
    private int dow() { return (today().get(Calendar.DAY_OF_WEEK) + 5) % 7; }

    /** index of the day name a word starts with (with its suffix), -1 if none; longest names first ("cumartesi" before "cuma") */
    static int dayOf(String w) {
        int best = -1, len = 0;
        for (int i = 0; i < DAYS.length; i++) {
            String d = DAYS[i];
            if (w.startsWith(d) && d.length() > len && suffix(w.substring(d.length()))) { best = i; len = d.length(); }
        }
        return best;
    }

    static int genMonth(String w) {
        for (int i = 0; i < MONTHS.length; i++) if (w.equals(MONTHS[i]) || w.matches(MONTHS[i] + "(ın|in|un|ün)")) return i;
        return -1;
    }

    static int monthOf(String w) {
        for (int i = 0; i < MONTHS.length; i++) if (w.startsWith(MONTHS[i]) && suffix(w.substring(MONTHS[i].length()))) return i;
        return -1;
    }

    private static boolean suffix(String s) {
        if (s.startsWith("ya kadar") || s.equals("yakadar") || s.equals("akadar") || s.equals("ekadar")) return true;
        for (String x : SUF) if (s.equals(x)) return true;
        return false;
    }

    /** "15", "15'inde", "üçte", "yirmi" … → number, or -1 */
    static int num(String w) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,2})(\\p{L}*)$").matcher(w);
        if (m.matches()) return suffix(m.group(2)) ? Integer.parseInt(m.group(1)) : -1;
        String[] words = {"yirmi", "otuz"};
        for (int i = 0; i < words.length; i++) if (w.equals(words[i]) || (w.startsWith(words[i]) && suffix(w.substring(words[i].length())))) return 20 + 10 * i;
        int best = -1, len = 0;
        for (int i = 0; i < NUMS.length; i++) {
            String n = NUMS[i];
            if (n.contains(" ")) continue;
            // the final consonant softens before a vowel suffix: dört → dördü
            String[] forms = n.equals("dört") ? new String[]{"dört", "dörd"} : new String[]{n};
            for (String f : forms) if (w.startsWith(f) && f.length() > len && suffix(w.substring(f.length()))) { best = i; len = f.length(); }
        }
        // "üçü" / "altıyı" with a buffer letter
        return best;
    }

    /** "her gün", "her pazartesi", "her ayın 1'inde" … */
    private boolean recurrence(List<Tok> c, Task k, Cal cal) {
        for (int i = 0; i + 1 < c.size(); i++) {
            String w = c.get(i).low, n = c.get(i + 1).low;
            if (!w.equals("her") && !w.equals("hergün")) continue;
            if (w.equals("hergün")) { k.recurrence = "D"; c.get(i).used = true; cal.day = 0; return true; }
            int d = dayOf(n);
            if (n.startsWith("gün")) { k.recurrence = "D"; cal.day = 0; }
            else if (n.startsWith("sabah") || n.startsWith("akşam") || n.startsWith("gece") || n.startsWith("öğle")) {
                k.recurrence = "D"; cal.day = 0;   // the period itself is read by time()
                c.get(i).used = true;
                return true;
            }
            else if (n.startsWith("hafta")) { k.recurrence = "W:" + (dow() + 1); cal.day = 0; }
            else if (d >= 0) { k.recurrence = "W:" + (d + 1); cal.day = (d - dow() + 7) % 7; }
            else if (n.equals("ay")) { k.recurrence = "M:" + today().get(Calendar.DAY_OF_MONTH); cal.day = 0; }
            else if (n.equals("ayın") && i + 2 < c.size() && num(c.get(i + 2).low) > 0) {
                int dom = num(c.get(i + 2).low);
                k.recurrence = "M:" + dom;
                monthDay(cal, dom);
                c.get(i + 2).used = true;
            } else continue;
            c.get(i).used = true;
            c.get(i + 1).used = true;
            k.dateConf = 0.85;
            // a time of day for the first one comes from time(); "her gün" with no time gets the morning
            return true;
        }
        return false;
    }

    private void monthDay(Cal cal, int dom) {
        Calendar t = today();
        int y = t.get(Calendar.YEAR), mo = t.get(Calendar.MONTH);
        if (dom < t.get(Calendar.DAY_OF_MONTH)) { mo++; if (mo > 11) { mo = 0; y++; } }
        Calendar x = Calendar.getInstance();
        x.set(y, mo, 1);
        cal.y = y; cal.mo = mo; cal.dom = Math.min(dom, x.getActualMaximum(Calendar.DAY_OF_MONTH));
    }

    private boolean date(List<Tok> c, Task k, Cal cal) {
        for (int i = 0; i < c.size(); i++) {
            Tok t = c.get(i);
            String w = t.low, n = i + 1 < c.size() ? c.get(i + 1).low : "", n2 = i + 2 < c.size() ? c.get(i + 2).low : "";
            if (t.used) continue;
            if (w.startsWith("bugün")) { cal.day = 0; t.used = true; k.dateConf = 0.95; deadline(c, i + 1, k); return true; }
            if (w.startsWith("yarın")) {
                cal.day = 1; t.used = true; k.dateConf = 0.95;
                if (w.equals("yarına") || w.equals("yarınki")) k.dateConf = 0.9;
                deadline(c, i + 1, k);
                return true;
            }
            if ((w.equals("öbür") || w.equals("ertesi")) && n.startsWith("gün")) { cal.day = 2; t.used = true; c.get(i + 1).used = true; k.dateConf = 0.9; return true; }
            if (w.equals("öbürgün")) { cal.day = 2; t.used = true; k.dateConf = 0.9; return true; }
            if (w.equals("haftaya") || ((w.equals("gelecek") || w.equals("önümüzdeki")) && (n.startsWith("hafta") || dayOf(n) >= 0))) {
                t.used = true;
                int d = dayOf(n);
                if (w.equals("haftaya") && d < 0) { cal.day = 7; k.dateConf = 0.85; return true; }
                if (d < 0 && n.startsWith("hafta")) {   // "gelecek hafta (salı)"
                    c.get(i + 1).used = true;
                    int d2 = dayOf(n2);
                    if (d2 >= 0) { c.get(i + 2).used = true; cal.day = 7 - dow() + d2; deadline(c, i + 3, k); }
                    else cal.day = 7;
                    k.dateConf = 0.85;
                    return true;
                }
                c.get(i + 1).used = true;
                cal.day = 7 - dow() + d;   // the named day in next week
                k.dateConf = 0.9;
                deadline(c, i + 1, k);
                return true;
            }
            int nn = num(w);
            if (nn > 0 && (n.startsWith("gün") || n.startsWith("hafta") || n.equals("ay")) && (n2.equals("sonra") || n2.equals("içinde") || n2.startsWith("sonra"))) {
                cal.day = n.startsWith("gün") ? nn : n.startsWith("hafta") ? 7 * nn : 30 * nn;
                if (n.equals("ay")) { Calendar x = today(); x.add(Calendar.MONTH, nn); cal.y = x.get(Calendar.YEAR); cal.mo = x.get(Calendar.MONTH); cal.dom = x.get(Calendar.DAY_OF_MONTH); }
                t.used = true; c.get(i + 1).used = true; c.get(i + 2).used = true;
                k.dateConf = 0.9;
                return true;
            }
            if (w.equals("ay") && (n.equals("sonunda") || n.equals("sonu") || n.equals("sonuna"))) {
                Calendar x = today();
                cal.y = x.get(Calendar.YEAR); cal.mo = x.get(Calendar.MONTH); cal.dom = x.getActualMaximum(Calendar.DAY_OF_MONTH);
                t.used = true; c.get(i + 1).used = true;
                if (n.equals("sonuna")) { k.deadline = true; if (n2.equals("kadar")) c.get(i + 2).used = true; }
                k.dateConf = 0.85;
                return true;
            }
            if (w.equals("hafta") && (n.equals("sonu") || n.equals("sonunda") || n.equals("sonuna"))) {
                cal.day = (5 - dow() + 7) % 7;
                t.used = true; c.get(i + 1).used = true;
                k.dateConf = 0.75;
                return true;
            }
            if (w.equals("ayın") && num(n) > 0) {
                monthDay(cal, num(n));
                t.used = true; c.get(i + 1).used = true;
                k.dateConf = 0.9;
                deadline(c, i + 2, k);
                return true;
            }
            boolean mFirst = genMonth(w) >= 0 && num(n) > 0 && num(n) <= 31;   // "kasımın 3'ünde"
            if (nn > 0 && nn <= 31 && monthOf(n) >= 0 || mFirst) {
                if (mFirst) nn = num(n);
                Calendar x = today();
                int y = x.get(Calendar.YEAR), mo = mFirst ? genMonth(w) : monthOf(n);
                Calendar d = Calendar.getInstance();
                d.set(y, mo, nn, 23, 59);
                if (d.getTimeInMillis() < now) y++;
                cal.y = y; cal.mo = mo; cal.dom = nn;
                t.used = true; c.get(i + 1).used = true;
                k.dateConf = 0.95;
                deadline(c, i + 2, k);
                return true;
            }
            boolean bu = w.equals("bu");
            int d = dayOf(bu ? n : w);
            if (d >= 0) {
                Tok day = bu ? c.get(i + 1) : t;
                // "pazara gitmem lazım": the bazaar, not Sunday
                if (d == 6 && (day.low.equals("pazara") || day.low.equals("pazardan")) && mentions(c, "git", "gid", "uğra")) continue;
                if (bu) t.used = true;
                day.used = true;
                int off = (d - dow() + 7) % 7;
                if (off == 0 && !bu) off = 7;   // "pazartesi" said on a Monday: next Monday
                cal.day = off;
                k.dateConf = d == 6 ? 0.6 : 0.85;
                if (day.low.contains("kadar")) k.deadline = true;
                int j = c.indexOf(day) + 1;
                if (j < c.size() && c.get(j).low.equals("günü")) { c.get(j).used = true; j++; }
                deadline(c, j, k);
                return true;
            }
        }
        return false;
    }

    /** "… kadar" right after a date: a deadline */
    private static void deadline(List<Tok> c, int i, Task k) {
        if (i < c.size() && c.get(i).low.matches("(sabah|öğle|akşam|gece).*") && i + 1 < c.size() && c.get(i + 1).low.equals("kadar")) i++;
        if (i < c.size() && c.get(i).low.equals("kadar")) { k.deadline = true; c.get(i).used = true; }
    }

    private static boolean mentions(List<Tok> c, String... stems) {
        for (Tok t : c) for (String s : stems) if (t.low.startsWith(s)) return true;
        return false;
    }

    /** "3'te", "saat 3", "15:30'da", "üç buçukta", "on birde", periods; 1–7 without "sabah" are afternoon hours. */
    private boolean time(List<Tok> c, Task k, Cal cal) {
        int h = -1, m = 0;
        String period = null;
        for (int i = 0; i < c.size(); i++) {
            Tok t = c.get(i);
            String w = t.low;
            if (period == null) {
                if (w.startsWith("sabah")) period = "sabah";
                else if (w.equals("öğleden") && i + 1 < c.size() && c.get(i + 1).low.equals("sonra")) { period = "ös"; c.get(i + 1).used = true; }
                else if (w.startsWith("öğle")) period = "öğle";
                else if (w.startsWith("akşam")) period = "akşam";
                else if (w.startsWith("gece")) period = "gece";
                if (period != null) { t.used = true; if (i > 0 && c.get(i - 1).low.equals("bu")) c.get(i - 1).used = true; continue; }
            }
            if (h >= 0 || t.used) continue;
            java.util.regex.Matcher hm = java.util.regex.Pattern.compile("^(\\d{1,2})[:.](\\d{2})(\\p{L}*)$").matcher(w);
            if (hm.matches() && suffix(hm.group(3))) {
                h = Integer.parseInt(hm.group(1)); m = Integer.parseInt(hm.group(2)); t.used = true;
                if (i > 0 && c.get(i - 1).low.equals("saat")) c.get(i - 1).used = true;
                continue;
            }
            boolean saat = i > 0 && c.get(i - 1).low.equals("saat");
            int n = num(w);
            int span = 1;
            if (w.equals("on") && i + 1 < c.size()) {   // "on birde" = 11
                int n2 = num(c.get(i + 1).low);
                if (n2 >= 1 && n2 <= 2) { n = 10 + n2; span = 2; }
            }
            if (n < 0 || n > 24) continue;
            String last = c.get(i + span - 1).low;
            boolean loc = last.matches(".*(de|da|te|ta)$");
            String next = i + span < c.size() ? c.get(i + span).low : "";
            boolean half = next.startsWith("buçuk");
            if (!saat && !loc && !half) continue;
            // a number before gün/hafta/ay or a month is a date, not a time
            if (next.startsWith("gün") || next.startsWith("hafta") || next.equals("ay") || monthOf(next) >= 0) continue;
            h = n;
            for (int j = 0; j < span; j++) c.get(i + j).used = true;
            if (saat) c.get(i - 1).used = true;
            if (half) { m = 30; c.get(i + span).used = true; }
        }
        if (h < 0 && period == null) return false;
        if (h < 0) {
            int[] p = "sabah".equals(period) ? per.sabah : "öğle".equals(period) ? per.oglen : "akşam".equals(period) ? per.aksam
                : "gece".equals(period) ? per.gece : new int[]{15, 0};
            h = p[0]; m = p[1];
            k.dateConf = Math.min(k.dateConf, 0.7);   // the period's hour is a default
        } else {
            if (h < 12 && ("akşam".equals(period) || "gece".equals(period) && h >= 6 || "ös".equals(period))) h += 12;
            else if (h == 12 && "gece".equals(period)) h = 0;
            else if (h >= 1 && h <= 7 && !"sabah".equals(period) && !"gece".equals(period)) h += 12;
            if (h == 24) h = 0;
        }
        cal.h = h; cal.m = m;
        return true;
    }

    /* ---------- person, priority, category, title ---------- */

    private void person(List<Tok> c, Task k) {
        for (int i = 0; i < c.size(); i++) {
            Tok t = c.get(i);
            String base = honor(t.low);
            if (base != null && i > 0) {   // "Mehmet abiyi", "Ayşe hanıma"
                String name = c.get(i - 1).raw.replaceAll("'.*$", "").replaceAll("[^\\p{L}]", "");
                if (!name.isEmpty()) { k.person = cap(name) + " " + base; k.personConf = 0.9; return; }
            }
        }
        for (Tok t : c) {   // personal dictionary first
            String w = t.raw.replaceAll("'.*$", "").toLowerCase(TR).replaceAll("[^\\p{L}]", "");
            if (!w.isEmpty() && names.contains(w)) { k.person = cap(w); k.personConf = 0.95; return; }
        }
        for (int i = 0; i < c.size(); i++) {   // "Ahmet'i", "Ayşe'ye": capital + suffix
            Tok t = c.get(i);
            int a = t.raw.indexOf('\'');
            if (a <= 0 || !Character.isUpperCase(t.raw.charAt(0))) continue;
            String name = t.raw.substring(0, a).replaceAll("[^\\p{L}]", "");
            String low = name.toLowerCase(TR);
            if (name.length() < 2 || PLACES.contains(low) || dayOf(low) >= 0 || monthOf(low) >= 0) continue;
            k.person = name;
            k.personConf = 0.8;
            return;
        }
    }

    private static String honor(String w) {
        for (String h : HONOR) if (w.equals(h) || (w.startsWith(h) && w.length() <= h.length() + 3 && suffix(w.substring(h.length())))) return h.equals("hocam") ? "hoca" : h;
        if (w.equals("abiye") || w.equals("abiyi") || w.equals("ablaya") || w.equals("ablayı") || w.equals("hocaya") || w.equals("hocayı")) return w.substring(0, w.length() - 2);
        return null;
    }

    private void priority(List<Tok> c, Task k) {
        for (int i = 0; i + 1 < c.size(); i++)
            for (String[] p : LOW) if (c.get(i).low.equals(p[0]) && c.get(i + 1).low.equals(p[1])) {
                k.priority = 3; k.prioConf = 0.85; c.get(i).used = true; c.get(i + 1).used = true; return;
            }
        int s = 0;
        for (Tok t : c) if (EMPHASIS.contains(t.low)) { s += 2; break; }
        if (k.due >= 0) {
            Calendar d = Calendar.getInstance(); d.setTimeInMillis(k.due);
            Calendar t = today();
            long days = (startOfDay(d) - startOfDay(t)) / 86400000L;
            if (days <= 1 && !k.deadline) s += 1;
        }
        String title = titleWords(c);
        for (String r : recent) if (similar(title, r)) { s += 1; break; }
        k.priority = s >= 2 ? 1 : 2;
        k.prioConf = s >= 2 ? 0.8 : 0.7;
    }

    private static long startOfDay(Calendar c) {
        Calendar x = (Calendar) c.clone();
        x.set(Calendar.HOUR_OF_DAY, 0); x.set(Calendar.MINUTE, 0); x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }

    /** two points per keyword, one more for the category named first (it breaks ties: "kira sözleşmesi" is money) */
    private static void score(List<Tok> c, int[] s) {
        boolean first = true;
        for (int i = 0; i < c.size(); i++) {
            String w = c.get(i).low;
            String two = i + 1 < c.size() ? w + " " + c.get(i + 1).low : null;
            for (Map.Entry<String, Integer> e : KW.entrySet()) {
                String kw = e.getKey();
                boolean hit = kw.contains(" ") ? two != null && two.startsWith(kw)
                    : kw.length() >= 4 ? w.startsWith(kw)
                    : w.equals(kw) || (w.startsWith(kw) && w.length() <= kw.length() + 3 && suffix(w.substring(kw.length())));
                if (hit) { s[e.getValue()] += (kw.contains(" ") ? 4 : 2) + (first ? 1 : 0); first = false; break; }
            }
        }
        // "pazara gitmem lazım" is shopping
        if (mentions(c, "pazara", "pazardan")) s[ALISVERIS] += 2;
    }

    private static String titleWords(List<Tok> c) {
        StringBuilder b = new StringBuilder();
        for (Tok t : c) if (!t.used && !FILLER.contains(t.low)) b.append(t.low).append(' ');
        return b.toString().trim();
    }

    /** What is left of the clause with the verb put back in the imperative: "Ahmet'i aramam lazım" → "Ahmet'i ara". */
    private static String title(List<Tok> c) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) {
            Tok t = c.get(i);
            if (t.used || FILLER.contains(t.low)) continue;
            String raw = t.raw.replaceAll("[,.;:!?]+$", "");
            String verb = imperative(t.low, i + 1 < c.size() ? c.get(i + 1).low : "");
            out.add(verb != null ? verb : raw);
        }
        String s = String.join(" ", out).replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) { StringBuilder b = new StringBuilder(); for (Tok t : c) b.append(t.raw).append(' '); s = b.toString().trim(); }
        return cap(s);
    }

    /** aramalıyım / arayacağım / aramam (lazım) / aramak (gerek) → ara; gideceğim → git */
    static String imperative(String w, String next) {
        String stem = null;
        if (necessity(w)) stem = w.replaceAll("m[ae]l[ıi](y[ıi]m|s[ıi]n|y[ıi]z|s[ıi]n[ıi]z|l[ae]r|d[ıi]r)?$", "");
        else if (future(w)) stem = w.replaceAll("[ae]c[ae]ğ[ıi](m|z)$", "");
        else if ((NEED.contains(next)) && w.matches(".+m([ae]m|[ae]k|[ae]n|[ae]mız|[ae]miz|[ae]yı|[ae]yi|[ae]sı|[ae]si)$"))
            stem = w.replaceAll("m([ae]m|[ae]k|[ae]n|[ae]mız|[ae]miz|[ae]yı|[ae]yi|[ae]sı|[ae]si)$", "");
        if (stem == null || stem.length() < 2) return null;
        if (stem.endsWith("y") && "aeıioöuü".indexOf(stem.charAt(stem.length() - 2)) >= 0) stem = stem.substring(0, stem.length() - 1);
        if (stem.endsWith("d") && VERBS.contains(stem.substring(0, stem.length() - 1) + "t")) stem = stem.substring(0, stem.length() - 1) + "t";
        return stem;
    }

    static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(TR) + s.substring(1);
    }

    /* ---------- done sentences and the matcher (flow C) ---------- */

    /** past tense first person ("aradım", "ödedim", "gittim") or a closing word; null when the clause has neither */
    private static Done done(List<Tok> c) {
        Done d = null;
        for (Tok t : c) {
            String w = t.low;
            boolean past = w.length() >= 4 && w.matches(".+[dt][ıiuü]m") && !NOT_PAST.contains(w);
            if (!past && !CLOSERS.contains(w)) continue;
            if (d == null) d = new Done();
            if (!past || CLOSERS.contains(w) && d.verb != null) continue;
            String stem = w.substring(0, w.length() - 3);
            if (stem.length() >= 2 && stem.charAt(stem.length() - 1) == stem.charAt(stem.length() - 2)) stem = stem.substring(0, stem.length() - 1);   // gitt → git
            if (stem.endsWith("ed") && stem.length() > 2) stem = stem.substring(0, stem.length() - 2) + "et";
            d.verb = stem;
        }
        if (d == null) return null;
        for (Tok t : c) {
            String w = t.raw.replaceAll("'.*$", "").toLowerCase(TR).replaceAll("[^\\p{L}\\p{N}]", "");
            if (w.length() >= 3 && !FILLER.contains(w) && !CLOSERS.contains(t.low) && !t.low.matches(".+[dt][ıiuü]m")) d.words.add(w);
        }
        return d;
    }

    /** open task as the matcher sees it */
    static final class Open {
        final long id;
        final String title, person;
        Open(long id, String title, String person) { this.id = id; this.title = title; this.person = person; }
    }

    /** Open tasks a done sentence may close, best first (several → the card asks which one). */
    static List<Long> match(Done d, List<Open> open) {
        List<Long> ids = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (Open o : open) {
            List<String> tw = new ArrayList<>();
            for (String w : o.title.split("\\s+")) { String x = w.replaceAll("'.*$", "").toLowerCase(TR).replaceAll("[^\\p{L}\\p{N}]", ""); if (!x.isEmpty()) tw.add(x); }
            double s = 0;
            if (d.verb != null) for (String x : tw) if (x.equals(d.verb) || (VERBS.contains(x) && same(x, d.verb))) { s += 1; break; }
            int hits = 0, objs = 0;
            for (String x : tw) {
                if (VERBS.contains(x) || x.length() < 3) continue;
                objs++;
                for (String y : d.words) if (same(x, y)) { hits++; break; }
            }
            if (objs > 0) s += (double) hits / objs * 1.5;
            if (o.person != null) for (String y : d.words) if (same(o.person.toLowerCase(TR).split(" ")[0], y)) { s += 0.5; break; }
            if (s < 1.2) continue;
            int at = 0;
            while (at < scores.size() && scores.get(at) >= s) at++;
            scores.add(at, s);
            ids.add(at, o.id);
        }
        // a clear winner closes alone; close scores leave the choice to the card
        if (ids.size() > 1 && scores.get(0) - scores.get(1) >= 0.75) return ids.subList(0, 1);
        return ids.size() > 3 ? ids.subList(0, 3) : ids;
    }

    /** same word apart from its suffixes: "faturayı" ~ "fatura", "kirayı" ~ "kira" */
    static boolean same(String a, String b) {
        int n = Math.min(a.length(), b.length()), p = 0;
        while (p < n && a.charAt(p) == b.charAt(p)) p++;
        return p >= Math.min(4, n) && p >= 0.6 * n && n >= 2;
    }

    static boolean similar(String a, String b) {
        String[] x = a.split(" "), y = b.toLowerCase(TR).split(" ");
        int hit = 0, n = 0;
        for (String p : x) { if (p.length() < 3) continue; n++; for (String q : y) if (same(p, q)) { hit++; break; } }
        return n > 0 && hit * 2 >= n + 1;
    }

    /* ---------- recurrence ---------- */

    /** The next due time after "from" for a rule (D, W:1..7, M:day), keeping the time of day of "from". */
    static long next(String rule, long from) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(from);
        if ("D".equals(rule)) c.add(Calendar.DAY_OF_MONTH, 1);
        else if (rule != null && rule.startsWith("W:")) c.add(Calendar.DAY_OF_MONTH, 7);
        else if (rule != null && rule.startsWith("M:")) {
            int dom = Integer.parseInt(rule.substring(2));
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.add(Calendar.MONTH, 1);
            c.set(Calendar.DAY_OF_MONTH, Math.min(dom, c.getActualMaximum(Calendar.DAY_OF_MONTH)));
        } else return -1;
        return c.getTimeInMillis();
    }
}
