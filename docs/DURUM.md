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

- **4–5. Ayrıştırıcı (0.4, 25 Ekim 2026)**
  - `Parser.java` (saf Java, Android'e bağlı değil): cümleyi virgül/"ve"/"sonra" ile parçalar ("öğleden sonra", "3 gün sonra" bölmez); görev (gerek/lazım, -malı, -acağım, emir kipleri), tarih (bugün, yarın, öbür gün, haftaya, N gün/hafta/ay sonra, ay sonu, hafta sonu, ayın N'i, "25 Ekim", gün adları + ekleri, "cumaya kadar"), saat (saat 3, 3'te, 15:30, üç buçuk, dönemler; 1–7 arası öğleden sonra), tekrar ("her gün / her pazartesi / her ayın 1'inde"), kişi (unvan, sözlük, büyük harf + kesme işareti), öncelik, kategori (anahtar kelime puanı), başlık (emir kipine çevirir: "aramam lazım" → "ara"), bitti cümleleri ("aradım, ödedim, aldım") ve açık görevlerle eşleştirme. Her alanın güven puanı var.
  - Örnek set: `tests/ornekler.tsv` (201 cümle), çalıştırıcı `tests/run_parser.sh` (kullanıcı "test et" deyince; %90 ölçütünü raporlar).

- **6–12. v1'in geri kalanı (0.9, 25 Ekim 2026)**
  - Onay kartı (`Brain.java`): yazıya çevrilen her not ayrıştırılır, görevler "gelen" olarak bekler; kart "Şunları anladım" der, her alan düzenlenebilir, düşük güvenli alanlar sarı. Kaydet / Sadece not olarak kaydet / Metni düzelt (yeniden okur) / görevi çıkar (✕). Kart kapatılırsa önerildiği gibi kaydedilir. Her düzeltme öğrenme kaydına yazılır.
  - Hatırlatmalar (`Tasks`, `Alarms`, `AlarmReceiver`, `Notify`): saatli görev o saatte, saatsiz görev günün sabah saatinde. Bildirimde Bitti, "1 saat ertele", "Akşama ertele" (akşamdan sonra "Yarına ertele"); dokununca görev açılır. Yeniden başlatma, güncelleme ve saat değişiminde alarmlar veritabanından yeniden kurulur; uygulama her açılışta da kurar.
  - Bitti → Bitenler (geri alınabilir); tekrarlayan görevin bir sonrakisi kendiliğinden açılır; notun açık görevi kalmayınca not arşive gider.
  - Sesle bitirme: "Ahmet'i aradım" gibi cümle açık görevle eşleşirse kart "…görevini bitti sayayım mı?" diye sorar; birden çok aday varsa "Hangisi bitti?". Yalnızca komut olan not arşive gider.
  - Bugün ekranı: Onay bekleyen → Gecikenler → Bugün → Yakında (7 gün) → Daha sonra → Tarihsiz → Notlar (kategoriye göre) → Bitenler özeti; öncelik ve saate göre sıralı, yüksek öncelik kırmızı çizgili. Notlar sekmesi: kategori, filtre (hepsi/görevli/sadece not/arşiv), arama. Bitenler sekmesi: güne göre, 30 günden eskiler katlanmış.
  - Sabah özeti: ayardaki saatte tek bildirim (bugünkü, geciken, yüksek öncelikli); hiçbiri yoksa bildirim yok. Aynı anda otomatik yedek alınır.
  - Gecikme soruları (bildirim değil, Bugün ekranında): "Hâlâ geçerli mi?" → Evet, yarına / Bitti / Sil. 3 kez ertelenen görev: Böl / Yarına / Sil. 30 gündür dokunulmayan düşük öncelikli görev: Evet / Sil.
  - Widget (mikrofon + "Bugün N görev"), Hızlı Ayarlar kutucuğu "Not al" (kilit ekranından da; kilitliyken yalnızca kayıt ekranı görünür, sonra uygulama kilidin arkasına çekilir), uygulama simgesine uzun basınca "Not al" kısayolu.
  - Ayarlar: izinler (bildirim, tam zamanlı alarm, pil kısıtlaması), sabah özeti saati, dönem saatleri, kategori ekle/sil, ses modeli durumu, yedekleme.
  - Yedekleme (`Backup.java`): dışa aktarma tek .zip (data.json + audio/), kullanıcı yerini seçer; geri yükleme aynı dosyadan (önce mevcut hâl otomatik yedeğe alınır); her sabah otomatik yedek (son 7, uygulama klasöründe), "Geri dön".
  - Yazarak not alma (klavye düğmesi): sesli notla aynı yoldan geçer; model inmeden ayrıştırıcıyı denemeye de yarar.
  - İlk açılışta tek sayfa: mikrofon, bildirim izni, model indirme.
  - Öğrenme kaydı (`learning_events`): kategori/tarih/metin/kişi/öncelik düzeltmesi, "görev değil", bitirme (süresiyle), erteleme (saatiyle), bölme. v1 yalnızca kaydeder; uygulaması v2'de.
  - Veritabanı 2. sürüm: `notes.review` (kartın göstereceği), `tasks.conf` (güven puanları), `tasks.created_at`, `tasks.touched_at`.
  - Sürüm 0.9 (versionCode 4): kabul ölçütleri telefonda sağlanınca 1.0 olur.

- **Test (0.9.1, 26 Ekim 2026, kullanıcı "test et" dedi)**
  - Ayrıştırıcı, 201 cümlelik sette ilk çalıştırmada tarih/saat %98,9 (174/176), tam doğru cümle 197/201 çıktı. Hatalar: "önemli:" (iki nokta), tek başına "unutmayayım," parçası ayrı görev sayılıyordu, "servise bin" görev sayılmıyordu, "dişçi" kategorisi. Düzeltildi.
  - Sete girmemiş 20 yeni cümleyle denendi, 6 hata daha bulundu ve düzeltildi: "bu akşam"daki "bu" başlıkta kalıyordu; "kasımın 3'ünde" ve "20'sinde" okunmuyordu; fiilsiz randevular ("toplantı var", "dişçi randevum var", "15 kasım 14:30 noter") görev sayılmıyordu (artık plan kelimesi ya da saat varsa ve cümle geçmiş/duyulan zamanda değilse görev); "Ahmet'i aradım, yarın 3'te toplantı var"da toplantı kayboluyordu. Bu 12 cümle sete eklendi: **213/213, tarih/saat %100**. Uyarı: seti ayrıştırıcıyı yazan hazırladı; gerçek konuşmada oran daha düşük olabilir.
  - Arayüz, masaüstü Chromium'da sahte telefon köprüsüyle denendi: ilk açılış, onay kartı (düzeltme, ✕, sesle bitirme onayı, metni düzelt, geri tuşuyla kapatınca kaydetme), görev ekranı, erteleme, bitti + geri al, gecikenler soruları, böl, 30 gün sorusu, Notlar arama/filtre, not ekranı, Bitenler, ayarlar, yazarak not, kayıt, kilit ekranı kaydı. Sayfa hatası yok. Düzeltilen: kartta "Görev" başlığı iki kez yazıyordu; "Sadece not olarak kaydet" yine de bitti işaretli görevi kapatıyordu.
  - Veritabanındaki 38 SQL cümlesi gerçek SQLite'ta çalıştırıldı: sözdizimi hatası yok.
  - Denenemeyenler (telefon/emülatör yok): hatırlatma ve bildirim düğmeleri, yeniden başlatma, widget, kutucuk, gerçek kilit ekranı, yedek dosyası, ses kaydı, model indirme ve yazıya çevirme.

## Sıradaki adım
**v1 kabul ölçütlerinin telefonda denenmesi** (plan 10. bölüm). Hepsi işaretlenince sürüm 1.0 yapılır, ardından v2. Denenecekler:
- [ ] İnternet kapalıyken akışın tamamı (kayıt → yazı → kart → hatırlatma)
- [ ] Örnek cümle setinde tarih/saat %90 (kullanıcı "test et" derse Claude çalıştırır)
- [ ] Hatırlatma, telefon yeniden başlatıldıktan sonra da geliyor
- [ ] 1 dakikalık kaydın yazıya dönme süresi (orta seviye telefon)
- [ ] "Aradım / ödedim / aldım" doğru görevi kapatıyor
- [ ] Yedek dosyasından geri yükleme

## Verilen kararlar
- **Ayrı uygulama** (planın önerisi); kayıt kodu Ses Defteri'nden kopyalanarak paylaşılır.
- **Aynı dil ve arayüz altyapısı** (WebView + JS, Java 8, Gradle yok).
- **Sesten yazıya motoru: sherpa-onnx Whisper** (plandaki whisper.cpp yerine). Sebep: Ses Defteri'nde zaten çalışıyor (`Transcriber`, `AsrModels`, VAD); base int8 ~95 MB (whisper.cpp base ~140 MB); kod doğrudan alınır. Model ilk kullanımda bir kez indirilir.
- **Ad:** şimdilik "Not Defteri" (depo adı). Kesin ad açık karar; `android/res/values/strings.xml` ve `web/index.html` `<title>` değişir.
- **Dil:** sadece Türkçe (Ses Defteri'nin 9 dili yok).
- **Tema:** telefonun açık/koyu ayarını izler.
- **Gecikme soruları bildirim olarak gelmez**, Bugün ekranında sorulur (ilke 7: sabah özeti + zamanı gelen hatırlatma dışında bildirim yok).
- **Bildirim düğmeleri:** Bitti, 1 saat ertele, Akşama/Yarına ertele (Android en fazla 3 düğme gösterir; "Aç" bildirime dokunmak).
- **"Arşivle" = iptal** (30 gün kuralında "Sil" görevi iptale alır, Bitenler'de görünmez, geri alınabilir).
- **Kilit ekranından kayıt:** Hızlı Ayarlar kutucuğu + kilit ekranı üzerinde yalnızca kayıt ekranı. Notlar kilit açılmadan görünmez.
- **Pil muafiyeti** doğrudan istenir (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). Google Play'e çıkılırsa bu izin gerekçe ister; Play kararında yeniden bakılır.
- **Ertelenmiş görev:** ertelenen zaman görevin yeni zamanı olur (saatli).
- **v2'ye kalanlar:** "Uygulama ne öğrendi" ekranı, small model seçimi, öğrenilenlerin uygulanması.

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
- `/__audio/` yanıtı bayt aralığı (Range) desteklemiyor; uzun kayıtlarda ileri sarma çalışmayabilir (dinleme yalnızca baştan çal/durdur).
- 0.4–0.9 arasındaki her şey telefonda denenmedi: kart, hatırlatma, bildirim düğmeleri, widget, kutucuk, kilit ekranı, yedekleme.
- Bazı üreticiler (Xiaomi, Huawei, Oppo…) pil muafiyetine rağmen alarmları geciktirebilir; ayarlarda "otomatik başlatma" ayrıca açılmalı olabilir.
- Not ekranında "Metni düzelt", onaylanmış görevi olan notta görevleri yeniden önerir (kartta ✕ ile çıkarılır).
