"""Builds NotDefteri.apk: copies the web app into assets, compiles the Java shell, signs the APK (no Gradle)."""
import os, shutil, subprocess, pathlib
from PIL import Image, ImageDraw

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
WEB = ROOT / "web"
# everything that is not in git (android.jar, speech-to-text libraries, voice detector): see android/fetch_vendor.sh
VENDOR = pathlib.Path(os.environ.get("ND_VENDOR", ROOT / "vendor"))
ANDROID_JAR = pathlib.Path(os.environ.get("ND_ANDROID_JAR", VENDOR / "android-34.jar"))
OUT = HERE / "out"
KS = pathlib.Path(os.environ.get("ND_KS", HERE / "not-defteri-imza.p12"))   # the signing key is NEVER committed
KS_PASS = os.environ.get("ND_KS_PASS", "")  # nor its password
ALIAS = "notdefteri"

def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        print(r.stdout[-3000:], r.stderr[-3000:])
        raise SystemExit(f"failed: {cmd[0]}")
    return r.stdout

# ---------- icons: a note page with lines and a recording dot ----------
BLUE, WHITE, AMBER = (47, 74, 158), (255, 255, 255), (242, 184, 61)
def draw_mark(d, off, k):
    d.rounded_rectangle([off + 5 * k, off + 3 * k, off + 19 * k, off + 21 * k], radius=2.2 * k, fill=WHITE)
    for y, w in [(7.5, 9), (11, 9), (14.5, 5.5)]:
        d.rounded_rectangle([off + 7.5 * k, off + y * k, off + (7.5 + w) * k, off + (y + 1.4) * k], radius=0.7 * k, fill=BLUE)
    d.ellipse([off + 13.5 * k, off + 14.5 * k, off + 17.5 * k, off + 18.5 * k], fill=AMBER)

def fg_icon(px):  # adaptive foreground, 108dp canvas, mark inside the 66dp safe zone
    s = 4; W = px * s
    im = Image.new("RGBA", (W, W), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    box = W * 58 / 108; off = (W - box) / 2
    draw_mark(d, off, box / 24)
    return im.resize((px, px), Image.LANCZOS)

def legacy_icon(px):
    s = 4; W = px * s
    im = Image.new("RGBA", (W, W), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    pad = W * 0.04
    d.rounded_rectangle([pad, pad, W - pad, W - pad], radius=int(W * 0.22), fill=BLUE)
    box = W * 0.66; off = (W - box) / 2
    draw_mark(d, off, box / 24)
    return im.resize((px, px), Image.LANCZOS)

def main():
    if not ANDROID_JAR.exists():
        raise SystemExit(f"android.jar missing: run android/fetch_vendor.sh (expected {ANDROID_JAR})")
    shutil.rmtree(OUT, ignore_errors=True)
    for sub in ["gen", "obj", "dex"]:
        (OUT / sub).mkdir(parents=True)

    # assets: the web app as it is in web/
    www = HERE / "assets" / "www"
    shutil.rmtree(www, ignore_errors=True)
    shutil.copytree(WEB, www)
    # voice detector for speech-to-text (Whisper itself is downloaded in the app)
    asr = HERE / "assets" / "asr"
    asr.mkdir(parents=True, exist_ok=True)
    shutil.copy(VENDOR / "asr" / "silero_vad.onnx", asr / "silero_vad.onnx")

    # icons
    res = HERE / "res"
    for dpi, mult in {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}.items():
        (res / f"drawable-{dpi}").mkdir(exist_ok=True)
        (res / f"mipmap-{dpi}").mkdir(exist_ok=True)
        fg_icon(int(108 * mult)).save(res / f"drawable-{dpi}" / "ic_launcher_fg.png")
        legacy_icon(int(48 * mult)).save(res / f"mipmap-{dpi}" / "ic_launcher.png")

    # R.java
    run(["aapt", "package", "-f", "-m", "-J", str(OUT / "gen"), "-M", str(HERE / "AndroidManifest.xml"),
         "-S", str(res), "-I", str(ANDROID_JAR)])
    # compile (Java 8: dalvik-exchange takes no lambdas)
    srcs = [str(p) for p in (HERE / "src").rglob("*.java")] + [str(p) for p in (OUT / "gen").rglob("*.java")]
    run(["javac", "-nowarn", "-Xlint:-options", "-source", "8", "-target", "8", "-encoding", "UTF-8",
         "-bootclasspath", str(ANDROID_JAR), "-d", str(OUT / "obj")] + srcs)
    # dex
    run(["dalvik-exchange", "--dex", "--min-sdk-version=24", f"--output={OUT / 'dex' / 'classes.dex'}", str(OUT / "obj")])
    # package
    unsigned = OUT / "app-unsigned.apk"
    run(["aapt", "package", "-f", "-M", str(HERE / "AndroidManifest.xml"), "-S", str(res), "-A", str(HERE / "assets"),
         "-I", str(ANDROID_JAR), "-F", str(unsigned)])
    run(["aapt", "add", str(unsigned), "classes.dex"], cwd=str(OUT / "dex"))
    # speech-to-text native libraries (sherpa-onnx + ONNX Runtime)
    libs = OUT / "libs"
    names = []
    for abi in ["arm64-v8a", "armeabi-v7a"]:
        (libs / "lib" / abi).mkdir(parents=True, exist_ok=True)
        for so in ["libsherpa-onnx-jni.so", "libonnxruntime.so"]:
            shutil.copy(VENDOR / "jniLibs" / abi / so, libs / "lib" / abi / so)
            names.append(f"lib/{abi}/{so}")
    run(["aapt", "add", str(unsigned)] + names, cwd=str(libs))
    aligned = OUT / "app-aligned.apk"
    run(["zipalign", "-f", "-p", "4", str(unsigned), str(aligned)])
    # signing key (kept so future updates install over this version)
    if not KS.exists() and os.environ.get("ND_NEW_KEY") != "1":
        raise SystemExit("signing key missing: put not-defteri-imza.p12 next to build_apk.py (or ND_KS=...) and set ND_KS_PASS. "
                         "A new key (ND_NEW_KEY=1) makes updates impossible to install over existing versions.")
    if not KS.exists():
        run(["keytool", "-genkeypair", "-keystore", str(KS), "-storetype", "PKCS12", "-storepass", KS_PASS,
             "-keypass", KS_PASS, "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=Not Defteri, O=Not Defteri, C=TR"])
    final = HERE / "NotDefteri.apk"
    run(["apksigner", "sign", "--ks", str(KS), "--ks-pass", f"pass:{KS_PASS}", "--ks-key-alias", ALIAS,
         "--min-sdk-version", "24", "--out", str(final), str(aligned)])
    print(run(["apksigner", "verify", "--min-sdk-version", "24", str(final)]))
    print(run(["aapt", "dump", "badging", str(final)]).splitlines()[:3])
    print("size", final.stat().st_size)

if __name__ == "__main__":
    main()
