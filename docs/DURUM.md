# Not Defteri — Durum notu

## Biten adımlar
- **1. Teknoloji kararı ve proje iskeleti (0.1, 25 Ekim 2026)**
  - Ayrı uygulama olarak kuruldu (paket `app.notdefteri`), Ses Defteri ile aynı yığın: WebView + vanilla JS arayüz, Java 8, Gradle'sız `build_apk.py`.
  - Ses Defteri'nin kayıt modülü alındı ve sadeleştirildi (`Recorder.java`, `RecordService.java`): 16 kHz mono AAC, uygulamaya özel klasör, aramada otomatik duraklatma.
  - Arayüz iskeleti: Bugün başlığı + tarih, büyük kayıt düğmesi, kayıt ekranı (kayan dalga, süre, Duraklat/Bitir/İptal), mikrofon izni akışı, kayıtları dinleme/silme (veritabanı gelene kadar geçici liste).
  - APK 71 KB (Whisper ve sherpa-onnx henüz yok).

## Sıradaki adım
**2. Veritabanı ve veri modeli** — SQLite: `notes, tasks, categories, reminders, learning_events, keyword_weights, user_dictionary, settings` (plan 5. bölüm). Sabit yedi kategori ilk açılışta oluşur. Kayıtlar listesi `notes` tablosuna taşınır.

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
- **Kalıcı imza anahtarı:** 0.1 deneme anahtarıyla imzalandı. Kalıcı anahtar kullanıcıda oluşturulup saklanmalı; ona geçişte deneme sürümü bir kez kaldırılıp kurulur.

## Bilinen hatalar / riskler
- Cihazda henüz denenmedi (kullanıcı test edecek): kayıt, ekran kapalıyken kayıt, aramada duraklatma, dinleme.
- `/__audio/` yanıtı bayt aralığı (Range) desteklemiyor; uzun kayıtlarda ileri sarma çalışmayabilir (dinleme ekranı 5. ekranla, görev detayında ele alınacak).
