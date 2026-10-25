# Not Defteri — Durum notu

## Biten adımlar
- **1. Teknoloji kararı ve proje iskeleti (0.1, 25 Ekim 2026)**
  - Ayrı uygulama olarak kuruldu (paket `app.notdefteri`), Ses Defteri ile aynı yığın: WebView + vanilla JS arayüz, Java 8, Gradle'sız `build_apk.py`.
  - Ses Defteri'nin kayıt modülü alındı ve sadeleştirildi (`Recorder.java`, `RecordService.java`): 16 kHz mono AAC, uygulamaya özel klasör, aramada otomatik duraklatma.
  - Arayüz iskeleti: Bugün başlığı + tarih, büyük kayıt düğmesi, kayıt ekranı (kayan dalga, süre, Duraklat/Bitir/İptal), mikrofon izni akışı, kayıtları dinleme/silme (veritabanı gelene kadar geçici liste).
  - APK 71 KB (Whisper ve sherpa-onnx henüz yok).

- **2. Veritabanı ve veri modeli (0.2, 25 Ekim 2026)**
  - `Db.java`: SQLite `notdefteri.db`, plandaki 8 tablo (`notes, tasks, categories, reminders, learning_events, keyword_weights, user_dictionary, settings`), dış anahtarlar açık (not silinince görevleri ve hatırlatmaları da silinir), sık sorgular için indeksler.
  - İlk açılışta sabit 7 kategori (İş, Ev, Alışveriş, Para, Sağlık, Fikir, Diğer) ve varsayılan ayarlar (sabah özeti 08:00, sabah 09:00, öğlen 12:00, akşam 19:00, gece 22:00, model base) oluşur.
  - 0.1'deki kayıt dosyaları ilk açılışta not olarak taşınır. Her yeni kayıt bir not olur (kategori şimdilik Diğer).
  - Bugün ekranında "Kayıtlar" yerine "Notlar" bölümü: tarih, süre, kategori; dinleme ve silme (ses dosyası da silinir).
  - Plandan küçük sapmalar: `notes.audio_ms` (listede süre için dosya açmamak), durum/tür değerleri ASCII: `gelen/acik/ertelendi/bitti/iptal`, `ses/yazi`, `zaman/konum/ozet`, `kisi/yer/kisaltma`; öncelik 1 yüksek, 2 normal, 3 düşük.

- **3. Whisper entegrasyonu ve parça parça yazıya çevirme (0.3, 25 Ekim 2026)**
  - Ses Defteri'nden alındı: `Transcriber.java` (Silero VAD + Whisper base int8, dil sabit `tr`, Whisper'ın sessizlikte uydurduğu altyazı cümleleri temizlenir), `AsrModels.java` (yalnızca base; Hugging Face, yedek hf-mirror; yarıda kalan indirme kaldığı yerden devam eder), `com/k2fsa/sherpa/onnx/*` (JNI'nin okuduğu Java sınıfları, olduğu gibi).
  - `Asr.java`: arka plan kuyruğu. Sesi çözer (MediaExtractor + MediaCodec, 16 kHz mono; farklı hızda gelirse doğrusal yeniden örnekleme), VAD'in kapattığı her cümleyi hemen yazıya çevirir (parça parça), sonucu `notes.transcript`'e yazar. Model yalnızca kuyrukta iş varken bellekte durur. Model inmeden alınan notlar bekler, indirme bitince sırayla işlenir; uygulama açılışında bekleyenler de işlenir.
  - `transcript` = NULL bekliyor, "" konuşma bulunamadı (ya da hata; tekrar denenmez).
  - Arayüz: ilk kayıttan sonra model indirme önerisi (yaklaşık 95 MB), Bugün ekranında indirme kartı ve ilerleme çubuğu, not satırında "Yazıya çevriliyor · %40" / "Model indirilince yazıya çevrilecek" / "Konuşma algılanmadı" / metnin kendisi.
  - INTERNET izni eklendi (yalnızca model indirmesi). APK 19,6 MB (arm64-v8a + armeabi-v7a için sherpa-onnx + ONNX Runtime ve 0,6 MB'lık VAD).
  - Kayıt kaynağı zaten 16 kHz olduğu için çözme sonrası yeniden örnekleme normalde devreye girmez.

## Sıradaki adım
**4. Ayrıştırma: tarih ve saat** — saf Java kural motoru (bugün, yarın, öbür gün, haftaya, gün adları + ekleri, "cumaya kadar", dönemler, "3'te / 15:30'da / üç buçukta", "1–7 arası saat öğleden sonra", "25 Ekim", "ayın 15'inde"), her alana güven puanı. Örnek cümle seti (en az 200 cümle, beklenen çıktılarla) bu adımda başlar; kullanıcı "test et" deyince çalıştırılır.

## Verilen kararlar
- **Ayrı uygulama** (planın önerisi); kayıt kodu Ses Defteri'nden kopyalanarak paylaşılır.
- **Aynı dil ve arayüz altyapısı** (WebView + JS, Java 8, Gradle yok).
- **Sesten yazıya motoru: sherpa-onnx Whisper** (plandaki whisper.cpp yerine). Sebep: Ses Defteri'nde zaten çalışıyor (`Transcriber`, `AsrModels`, VAD); base int8 ~95 MB (whisper.cpp base ~140 MB); kod doğrudan alınır. Model ilk kullanımda bir kez indirilir.
- **Ad:** şimdilik "Not Defteri" (depo adı). Kesin ad açık karar; `android/res/values/strings.xml` ve `web/index.html` `<title>` değişir.
- **Dil:** sadece Türkçe (Ses Defteri'nin 9 dili yok).
- **Tema:** telefonun açık/koyu ayarını izler.

## Açık kararlar (plan 13. bölüm)
- İlk hedef kitle: öğrenci, esnaf ya da genel kullanıcı
- Uygulamanın kesin adı
- Whisper modeli: ilk açılışta mı indirilecek (öneri, APK hafif kalır) yoksa APK'da mı?
- Gelir modeli: ücretsiz, tek seferlik ödeme ya da premium (v3)
- Dağıtım: GitHub Releases mı, Google Play kapalı test mi?
- **Kalıcı imza anahtarı:** 0.1 ve 0.2 aynı deneme anahtarıyla (yalnızca bu geliştirme ortamında duruyor) imzalandı. Kalıcı anahtar kullanıcıda oluşturulup saklanmalı; ona geçişte deneme sürümü bir kez kaldırılıp kurulur.

## Bilinen hatalar / riskler
- Cihazda henüz denenmedi (kullanıcı test edecek): kayıt, ekran kapalıyken kayıt, aramada duraklatma, dinleme, model indirme, yazıya çevirme hızı (plan kabul ölçütü: 1 dk'lık kayıt orta seviye telefonda ölçülecek).
- Model adresleri (huggingface.co, hf-mirror.com) bu geliştirme ortamından erişilemediği için denenemedi. İndirme olmazsa: model dosyalarını kullanıcının GitHub Releases sayfasına koyup `AsrModels.HOSTS`'a eklemek.
- Yazıya çevirme uygulama süreci içinde çalışır (ön plan servisi yok). Uygulama uzun süre arka planda kalıp kapatılırsa kalan notlar bir sonraki açılışta işlenir.
- `/__audio/` yanıtı bayt aralığı (Range) desteklemiyor; uzun kayıtlarda ileri sarma çalışmayabilir (dinleme ekranı 5. ekranla, görev detayında ele alınacak).
