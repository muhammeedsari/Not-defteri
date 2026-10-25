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

## Sıradaki adım
**3. Whisper entegrasyonu ve parça parça yazıya çevirme** — Ses Defteri'nden `asr/Transcriber`, `AsrModels` ve VAD alınır; sherpa-onnx kütüphaneleri `fetch_vendor.sh`'a eklenir; model ilk kullanımda bir kez indirilir (INTERNET izni yalnızca bunun için); sonuç `notes.transcript`'e yazılır.

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
- Cihazda henüz denenmedi (kullanıcı test edecek): kayıt, ekran kapalıyken kayıt, aramada duraklatma, dinleme.
- `/__audio/` yanıtı bayt aralığı (Range) desteklemiyor; uzun kayıtlarda ileri sarma çalışmayabilir (dinleme ekranı 5. ekranla, görev detayında ele alınacak).
