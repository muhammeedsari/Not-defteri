# Not Defteri (Sesli Not Asistanı) — Claude için proje rehberi

Bu dosyayı her oturumun başında oku. Ayrıntılar: `docs/PLAN.md` (ürün ve geliştirme planı, asıl kaynak; PDF'i `docs/plan.pdf`) ve `docs/DURUM.md` (biten adımlar, sıradaki adım, kararlar, bilinen hatalar).

## Proje
Çevrimdışı, Türkçe, sesle kullanılan kişisel not ve görev asistanı (Android). Kullanıcı konuşur; ses telefonda yazıya çevrilir, görev/tarih/kişi/öncelik/kategori kurallarla çıkarılır, onay kartında gösterilir, hatırlatma kurulur. Hiçbir veri telefondan çıkmaz. Kardeş uygulama: [Ses Defteri](https://github.com/muhammeedsari/ses-defteri) (kayıt modülü oradan alındı, aynı teknoloji yığını).

- **Arayüz:** tek sayfalık HTML/CSS/vanilla JS, Android WebView içinde (`web/index.html`). Çerçeve yok. Sadece Türkçe.
- **Yerel katman:** Java 8 (lambda/method reference YOK — dalvik-exchange min-sdk 24 ile derlenir), `android/src/app/notdefteri/`.
- **Derleme:** Gradle YOK. `android/build_apk.py`: aapt → javac (-source 8, android-34.jar) → dalvik-exchange → aapt add → zipalign → apksigner.
- **Sürüm:** şu an **0.9** (versionCode 4; v1 kabul ölçütleri telefonda sağlanınca 1.0). minSdk 24, targetSdk 34. Paket adı `app.notdefteri`.

## Kullanıcıyla çalışma kuralları (plan 12. bölüm, kesin)
- Kullanıcıyla **Türkçe** konuş; teknik olmayan, kısa ve net anlat.
- Kodu en optimize ve en hafif biçimde yaz.
- Önce düşün: varsayımlarını söyle, belirsizse sor.
- Sadelik: istenmeyen özellik ekleme; plandaki sürüm kapsamının dışına çıkma.
- Cerrahi değişiklik: yalnızca gereken satırları değiştir, değişen dosyaları listele.
- **Test etme; testi kullanıcı yapar. "Test et" derse test et.** (Derleme ve sözdizimi kontrolü test sayılmaz.)
- Her oturumun sonunda: ne yapıldı, hangi dosyalar değişti, sıradaki adım ne — ve bunu `docs/DURUM.md`'ye işle.
- İnternet gerektiren hiçbir çözüm önerme (tek istisna: model dosyalarının bir kez indirilmesi). Türkçe ek yapısını her zaman hesaba kat.
- Her oturum plandaki v1 sohbet sırasından (`docs/PLAN.md` 12. bölüm) **tek bir adımı** bitirir.

## Teslim
Kod değişikliği → sürüm numarası artır (`android/AndroidManifest.xml` `versionCode` +1 ve `versionName`) → imzalı APK derle → git commit + push → APK'yı kullanıcıya gönder → Türkçe kısa özet (ne değişti, neyi denemeli). Commit mesajları İngilizce, kısa, sürümle başlar ("0.2: …").

## Güvenlik
- İmza anahtarı (`not-defteri-imza.p12`, alias `notdefteri`) ve parolası **asla repoya girmez**. `build_apk.py` parolayı `ND_KS_PASS`, anahtarı `ND_KS` (varsayılan `android/not-defteri-imza.p12`, gitignore'da) ortam değişkeninden okur. Kalıcı anahtar kullanıcıda; yoksa kullanıcıdan iste. `ND_NEW_KEY=1` yeni anahtar üretir: yalnızca deneme sürümleri için.
- Kullanıcının e-postasını kodda/repoda kullanma. Analitik, reklam, sunucu yok.

## Derleme
```bash
bash android/fetch_vendor.sh            # bir kez: ../vendor içine android-34.jar, sherpa-onnx kütüphaneleri (jniLibs), asr/silero_vad.onnx
# araçlar (Ubuntu): apt install aapt zipalign apksigner dalvik-exchange openjdk-21-jdk python3-pil
ND_KS=/yol/not-defteri-imza.p12 ND_KS_PASS=… python3 android/build_apk.py   # → android/NotDefteri.apk
```
- `build_apk.py` `web/` klasörünü olduğu gibi `assets/www`'ye kopyalar, simgeleri üretir.
- JS kontrolü: `index.html`'deki `<script>` bloğunu çıkarıp `node --check`.

## Kod düzeni
- `web/index.html`: tüm arayüz + JS. Köprü `AND = window.NotDefteriAndroid`. Telefon olayları: `__ndPerm(kind, ok, blocked)`, `__ndResume()`, `__ndBack()` (true dönerse geri tuşu sayfada kaldı). Diyaloglar sayfa içi `sheet()` ile (tarayıcı `alert/confirm` kullanma).
- `MainActivity.java`: WebView, `https://appassets.androidplatform.net/` altından asset sunucusu, `/__audio/<ad>` ile kayıt dosyaları, izinler, köprü.
- `Recorder.java`: Ses Defteri'nin kaydedicisinden sadeleştirildi. 16 kHz mono AAC (32 kbps) `.m4a`, uygulamanın özel klasöründe (`files/audio`, MediaStore'a gitmez). Kaynak VOICE_RECOGNITION (olmazsa MIC). Aramada otomatik duraklatma korunur.
- `Db.java`: SQLite (plan 5. bölüm), tek örnek `Db.get(c)`. Zamanlar epoch ms; `audio_path` = `files/audio` içindeki dosya adı. Gecikti saklanmaz, sorguda hesaplanır. Köprü: `notes()`, `noteDelete(id)`, `categories()`; `recStop()` kaydı not olarak ekleyip `id` döndürür.
- `Asr.java`: yazıya çevirme kuyruğu (tek iş parçacığı) + model indirme; olaylar JS'ye `__ndAsr(id, state, p, text)` ve `__ndModel(state, p, err)` ile gider. Köprü: `asrStatus()`, `asrDownload()`, `asrCancel()`. `Transcriber.java` (VAD + Whisper, `tr`), `AsrModels.java` (yalnızca base; small v2'de).
- `com/k2fsa/sherpa/onnx/`: sherpa-onnx JNI'sinin alan adlarını okuduğu Java sınıfları; değiştirme, alan silme.
- `RecordService.java`: kayıt sürerken ön plan servisi (mikrofon türü).
- `Parser.java`: saf Java kural motoru (Android import'u yok; `tests/run_parser.sh` masaüstünde çalıştırır, yalnızca "test et" denince). Tekrar kuralı: `D`, `W:1..7` (1 = pazartesi), `M:gün`.
- `Brain.java`: not → ayrıştırma → "gelen" görevler + `notes.review` (onay kartı); `save` kartın cevabını işler, düzeltmeleri `learning_events`'e yazar. `Asr` yazıya çevirince `Brain.process` çağırır.
- `Tasks.java`: görevi değiştiren her şey (bitti, ertele, sil, geri al, böl, kaydet, hatırlatma kurma, `restoreAll`). Değişince `Tasks.changed` → widget + sayfada `__ndRefresh()`.
- `Alarms`/`AlarmReceiver`/`Notify`: AlarmManager (görev başına bir alarm + sabah özeti), bildirim düğmeleri, açılış/güncelleme/saat değişiminde yeniden kurma. `Widget`, `RecordTile` (Hızlı Ayarlar), `res/xml/shortcuts.xml`. `Backup.java`: zip dışa/içe aktarma (SAF), günlük otomatik JSON.
- Sayfa olayları ayrıca: `__ndRefresh()`, `__ndRecord(locked)`, `__ndOpen(taskId)`, `__ndBackup(kind, ok, err)`.
