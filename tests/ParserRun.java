package app.notdefteri;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Runs tests/ornekler.tsv through the parser (only when the user says "test et"): bash tests/run_parser.sh */
public class ParserRun {
    static final String[] CATS = {"", "İş", "Ev", "Alışveriş", "Para", "Sağlık", "Fikir", "Diğer"};

    public static void main(String[] a) throws Exception {
        Calendar now = Calendar.getInstance();
        now.set(2026, Calendar.OCTOBER, 26, 10, 0, 0);
        now.set(Calendar.MILLISECOND, 0);
        Parser p = new Parser(now.getTimeInMillis(), null, null, null);
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US), tf = new SimpleDateFormat("HH:mm", Locale.US);
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(a.length > 0 ? a[0] : "tests/ornekler.tsv"), "UTF-8"));
        int lines = 0, okLines = 0, dt = 0, dtOk = 0;
        Map<String, int[]> per = new HashMap<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;
            String[] f = line.split("\t");
            Parser.Result r;
            try { r = p.parse(f[0]); } catch (Exception e) { System.out.println("ÇÖKTÜ  " + f[0] + "  " + e); lines++; continue; }
            Parser.Task t = r.tasks.isEmpty() ? null : r.tasks.get(0);
            Map<String, String> got = new HashMap<>();
            got.put("n", String.valueOf(r.tasks.size()));
            got.put("tarih", t == null || t.due < 0 ? "-" : df.format(new Date(t.due)));
            got.put("saat", t == null || !t.hasTime ? "-" : tf.format(new Date(t.due)));
            got.put("son", t != null && t.deadline ? "1" : "0");
            got.put("kisi", t == null || t.person == null ? "-" : t.person);
            got.put("kat", CATS[t != null ? t.category : r.category]);
            got.put("onc", t == null ? "-" : String.valueOf(t.priority));
            got.put("tekrar", t == null || t.recurrence == null ? "-" : t.recurrence);
            got.put("bitti", r.done.isEmpty() ? "0" : "1");
            StringBuilder bad = new StringBuilder();
            boolean dateLine = false, dateGood = true;
            for (String kv : f[1].split(";\\s*")) {
                String[] x = kv.split("=", 2);
                String k = x[0].trim(), want = x[1].trim(), g = got.get(k);
                int[] c = per.get(k); if (c == null) per.put(k, c = new int[2]);
                c[1]++;
                if (want.equals(g)) c[0]++; else bad.append(k).append(": ").append(g).append(" (beklenen ").append(want).append(")  ");
                if (k.equals("tarih") || k.equals("saat")) { dateLine = true; if (!want.equals(g)) dateGood = false; }
            }
            lines++;
            if (bad.length() == 0) okLines++; else System.out.println("YANLIŞ  " + f[0] + "\n        " + bad);
            if (dateLine) { dt++; if (dateGood) dtOk++; }
        }
        System.out.println();
        for (Map.Entry<String, int[]> e : per.entrySet()) System.out.printf("%-7s %d/%d%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
        System.out.printf("Tamamen doğru cümle: %d/%d%n", okLines, lines);
        System.out.printf("Tarih ve saat doğru: %d/%d (%%%.1f; v1 ölçütü en az %%90)%n", dtOk, dt, 100.0 * dtOk / Math.max(1, dt));
    }
}
