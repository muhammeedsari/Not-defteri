package app.notdefteri;

import com.k2fsa.sherpa.onnx.*;

import java.util.Locale;

/**
 * Offline Turkish speech-to-text (taken from Ses Defteri): Silero VAD cuts the audio into utterances and Whisper
 * (base, int8) writes each one down as soon as the VAD closes it, so a long note is handled piece by piece.
 * Feed 16 kHz mono audio with {@link #accept}, then {@link #finish}.
 */
final class Transcriber {
    static final int RATE = 16000;

    private final Vad vad;
    private final OfflineRecognizer rec;
    private final StringBuilder text = new StringBuilder();
    private final float[] win = new float[512];
    private int winFill;

    /** dir: folder with base-encoder.int8.onnx, base-decoder.int8.onnx, base-tokens.txt and silero_vad.onnx */
    Transcriber(String dir, int threads) {
        String p = dir.endsWith("/") ? dir : dir + "/";
        VadModelConfig vc = new VadModelConfig();
        vc.sileroVadModelConfig.model = p + "silero_vad.onnx";
        vc.sileroVadModelConfig.threshold = 0.5f;
        vc.sileroVadModelConfig.minSilenceDuration = 0.45f;
        vc.sileroVadModelConfig.minSpeechDuration = 0.25f;
        vc.sileroVadModelConfig.maxSpeechDuration = 20f;
        vc.sileroVadModelConfig.windowSize = 512;
        vc.sampleRate = RATE;
        vc.numThreads = 1;
        vad = new Vad(null, vc);
        OfflineRecognizerConfig rc = new OfflineRecognizerConfig();
        rc.featConfig.sampleRate = RATE;
        rc.featConfig.featureDim = 80;
        rc.modelConfig.whisper.encoder = p + "base-encoder.int8.onnx";
        rc.modelConfig.whisper.decoder = p + "base-decoder.int8.onnx";
        rc.modelConfig.whisper.language = "tr";
        rc.modelConfig.whisper.task = "transcribe";
        rc.modelConfig.whisper.tailPaddings = 1000;
        rc.modelConfig.tokens = p + "base-tokens.txt";
        rc.modelConfig.numThreads = Math.max(1, threads);
        rc.modelConfig.modelType = "whisper";
        rc.modelConfig.provider = "cpu";
        rc.decodingMethod = "greedy_search";
        rec = new OfflineRecognizer(null, rc);
    }

    /** 16 kHz mono samples */
    void accept(float[] x, int n) {
        int i = 0;
        while (i < n) {
            int take = Math.min(n - i, win.length - winFill);
            System.arraycopy(x, i, win, winFill, take);
            winFill += take;
            i += take;
            if (winFill == win.length) {
                vad.acceptWaveform(win.clone());
                winFill = 0;
                drain();
            }
        }
    }

    /** The whole text; the transcriber can take the next note afterwards. */
    String finish() {
        if (winFill > 0) {
            float[] last = new float[win.length];
            System.arraycopy(win, 0, last, 0, winFill);
            vad.acceptWaveform(last);
            winFill = 0;
        }
        vad.flush();
        drain();
        vad.reset();
        String t = text.toString();
        text.setLength(0);
        return t;
    }

    private void drain() {
        while (!vad.empty()) {
            SpeechSegment s = vad.front();
            vad.pop();
            // each sentence at a steady level: quiet or far-away speech is recognised as well as close speech
            float[] x = s.samples;
            float pk = 0;
            for (float v : x) pk = Math.max(pk, Math.abs(v));
            if (pk > 1e-4f && pk < 0.7f) { float g = Math.min(12f, 0.8f / pk); x = x.clone(); for (int i = 0; i < x.length; i++) x[i] *= g; }
            OfflineStream os = rec.createStream();
            os.acceptWaveform(x, RATE);
            rec.decode(os);
            OfflineRecognizerResult r = rec.getResult(os);
            os.release();
            String t = clean(r.text);
            if (t.isEmpty()) continue;
            if (text.length() > 0) text.append(' ');
            text.append(t);
        }
    }

    // well-known Whisper hallucinations on silence / noise (subtitle credits etc.)
    private static final String[] JUNK = {
        "altyazı m.k.", "altyazı m.k", "abone olmayı unutmayın", "izlediğiniz için teşekkürler", "thanks for watching", "amara.org"
    };

    static String clean(String t) {
        if (t == null) return "";
        t = t.trim().replaceAll("\\s+", " ");
        String low = t.toLowerCase(new Locale("tr"));
        for (String j : JUNK) if (low.contains(j)) {
            if (low.replace(j, "").replaceAll("[\\p{Punct}\\s]", "").length() < 3) return "";
        }
        if (t.matches("[\\p{Punct}\\s♪]*")) return "";
        return t;
    }

    void release() { vad.release(); rec.release(); }
}
