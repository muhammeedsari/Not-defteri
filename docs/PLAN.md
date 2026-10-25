# Sesli Not Asistanı — Ürün ve Geliştirme Planı

25 Ekim 2026 · Doktor. Özgün belge: `docs/plan.pdf`. (Mimari şema ve durum diyagramı yalnızca PDF'te.)

## 1. Ürün özeti
Vaat tek cümle: "Konuş, unut; gerisini uygulama düzenler." Uygulama çevrimdışı ve Türkçe çalışan, sesle kullanılan bir kişisel not ve görev asistanıdır.

Hedef kullanıcı, not almak için telefonu açıp yazmaya vakti olmayan ve notları dağınık kalan kişidir. İlk sürümün odaklanacağı kitle henüz açık karar (bkz. 13. bölüm).

| Sorun | Çoğu not uygulamasında | Bizde |
|---|---|---|
| Notlar mezarlığı | Notlar birikir, bir daha açılmaz | Not doğru zamanda kendisi geri gelir |
| Yakalama zahmeti | Yazmak gerekir; sesli not çoğunlukla İngilizce odaklı ya da bulutta | Tek dokunuşla ses, telefonda Türkçe yazıya çeviri |
| Düzenleme yükü | Klasör ve etiket işi kullanıcıda | Kategori ve hiyerarşi otomatik |
| Not içinde kaybolan işler | Görev ve tarih elle çıkarılır | Görev, tarih ve kişi otomatik çıkar, hatırlatma kurulur |
| Bitirme | Elle işaretleme | "Ahmet'i aradım" demek görevi kapatır |
| Gizlilik | Veri sunucuya gider | Her şey telefonda kalır |

## 2. Temel ilkeler
Her tasarım ve kod kararı bu ilkelere göre verilir; çakışmada üstteki kazanır.
1. **Güven önce:** Her otomatik karar onay kartında görünür, tek dokunuşla düzeltilir ve geri alınabilir.
2. **Çevrimdışı:** Hiçbir özellik internete bağlı değildir. Model dosyaları yalnızca bir kez indirilir.
3. **Hafif:** Kod en optimize ve en hafif biçimde yazılır. Ağır modeller isteğe bağlı eklentidir.
4. **Türkçe öncelikli:** Tarih, görev ve kişi kalıpları Türkçenin ek yapısına göre tasarlanır.
5. **Önce kural, sonra yapay zekâ:** Kuralla çözülen iş modele verilmez. Model yalnızca kuralın yetmediği yerde devreye girer.
6. **Az ve sabit kategori:** İş, Ev, Alışveriş, Para, Sağlık, Fikir ve Diğer. Kullanıcı ekleyebilir ama uygulama kendiliğinden yeni kategori açmaz.
7. **Sessiz asistan:** Günde bir sabah özeti ve gerçekten zamanı gelen hatırlatmalar dışında bildirim gönderilmez.

## 3. Ana kullanıcı akışları
Altı akış; ilk beşi birinci sürümde.

**A. Kayıt**
1. Kullanıcı widget'tan, kilit ekranından ya da ana ekrandaki büyük düğmeden kayda başlar.
2. Kayıt bitince ses telefonda yazıya çevrilir.
3. Ayrıştırma motoru görevleri, tarihleri, kişileri, önceliği ve kategoriyi çıkarır.
4. Onay kartı açılır: "Şunları anladım: Perşembe 15:00 – Ahmet'i ara (İş, yüksek)".
5. Kullanıcı Kaydet'e basar ya da bir alana dokunup düzeltir. Kart kapatılırsa her şey önerildiği gibi kaydedilir.

**B. Hatırlatma** — Zamanı gelen görev bildirim olarak düşer. Bildirimde üç düğme: Bitti, Ertele (1 saat / akşam / yarın), Aç.

**C. Sesle bitirme** — Kullanıcı "Ahmet'i aradım" ya da "faturayı ödedim" diye kayıt alır. Motor geçmiş zaman kalıbını yakalar ve açık görevlerle eşleştirir. Kart sorar: "'Ahmet'i ara' görevini bitti sayayım mı?" Onaylanırsa görev Bitenler'e taşınır.

**D. Sabah özeti** — Belirlenen saatte tek bildirim: bugünkü görevler, gecikenler ve yüksek öncelikliler. Dokunmak Bugün ekranını açar.

**E. Gecikenler** — Tarihi geçen ve dokunulmayan görev için: "Bu hâlâ geçerli mi?" Seçenekler: Ertele, Bitti, Sil.

**F. Arama ve göz atma** — Kategori, durum ve tarih filtreleriyle liste (v1). "Geçen hafta kira hakkında ne demiştim" gibi anlamsal arama (v2).

## 4. Mimari ve teknoloji
Karar veren bir ajan yok; her kayıt sırayla çalışan bir boru hattından geçer (kayıt → sesten yazıya → ayrıştırma → onay kartı → veritabanı → zamanlayıcı → bildirim). Öğrenme döngüsü: onay kartındaki düzeltmeler ve ertelemeler kaydedilir, ayrıştırma kurallarını ve Whisper sözlüğünü besler.

| Bileşen | Görevi | Teknoloji | Sürüm |
|---|---|---|---|
| Kayıt | Ses yakalama | Ses Defteri kayıt modülü | v1 |
| Sesten yazıya | Türkçe transkripsiyon | Whisper base; isteğe bağlı small (plan: whisper.cpp; karar: sherpa-onnx, bkz. DURUM.md) | v1 |
| Ayrıştırma motoru | Tarih, görev, kişi, öncelik, kategori, tekrar | Kural tabanlı kod (6. bölüm) | v1 |
| Eşleştirici | "Aradım" cümlesini açık görevle eşleştirme | Fiil kökü + kelime benzerliği | v1 |
| Veritabanı | Not, görev, hatırlatma, öğrenme olayları | SQLite | v1 |
| Zamanlayıcı | Hatırlatma, sabah özeti, gecikme soruları | AlarmManager + WorkManager | v1 |
| Öğrenme katmanı | Düzeltmelerden kural ağırlığı çıkarma | SQLite'ta sayaçlar | v1 kayıt, v2 uygulama |
| Gömme modeli | Anlamsal arama ve otomatik gruplama | Küçük çok dilli model (~100 MB) | v2 |
| Konum | Yere bağlı hatırlatma | Android geofencing | v2 |
| Küçük dil modeli | Özet, temize çekme, liste çıkarma, serbest soru | 1B sınıfı model, llama.cpp, ayrı indirme | v3 |

Uzun kayıtlar parça parça yazıya çevrilir; işlem arka planda yürür ve arayüzü kilitlemez.

## 5. Veri modeli
Bir not birden çok görev üretebilir; ham ses, ham metin ve kullanıcının düzeltmesi ayrı saklanır ki öğrenme katmanı farkı görebilsin.

| Tablo | Alanlar | Not |
|---|---|---|
| notes | id, created_at, audio_path, transcript, edited_text, category_id, source (ses/yazı), archived | Her kayıt bir not olur |
| tasks | id, note_id, title, due_at, has_time, priority (1–3), status, recurrence, person, snooze_count, completed_at, category_id | status: gelen, açık, ertelendi, bitti, iptal |
| categories | id, name, is_fixed, icon | Sabit yedi kategori ilk açılışta oluşur |
| reminders | id, task_id, trigger_at, kind (zaman/konum/özet), state | Telefon yeniden başlayınca buradan yeniden kurulur |
| learning_events | id, kind, payload (JSON), created_at | kind: kategori düzeltme, tarih düzeltme, erteleme, bitirme, metin düzeltme |
| keyword_weights | word, category_id, weight | Kategori düzeltmelerinden beslenir |
| user_dictionary | term, kind (kişi/yer/kısaltma), count | Whisper'a ipucu olarak verilir |
| settings | key, value | Sabah özeti saati, dönem saatleri, model seçimi |

Gecikti durumu ayrı saklanmaz; açık bir görevin `due_at` alanı geçmişteyse sorguda hesaplanır.

## 6. Ayrıştırma motoru
Birinci sürümde motor tamamen kural tabanlıdır ve her çıktıya bir güven puanı verir. Düşük güvenli alanlar onay kartında vurgulanır.

**Tarih ve saat**
- Göreli ifadeler: bugün, yarın, öbür gün, haftaya, "3 gün sonra", "ay sonunda".
- Gün adları ve ekleri: "perşembe", "perşembeye", "bu cuma", "gelecek cuma", "cumaya kadar" (son tarih).
- Dönemler: sabah 09:00, öğlen 12:00, akşam 19:00, gece 22:00. Varsayılandır ve öğrenmeyle kayar.
- Saatler: "3'te", "15:30'da", "üç buçukta". 1 ile 7 arası saat, "sabah" denmedikçe öğleden sonra sayılır.
- Tarihler: "25 Ekim", "ayın 1'i", "ayın 15'inde".

**Görev tespiti**
- Gereklilik kalıpları: -meli/-malı, lazım, gerek, unutma, hatırlat.
- Emir kipindeki iş fiilleri: ara, al, öde, gönder, götür, yaz, git, randevu al.
- Bir kayıtta birden çok görev "ve", "sonra" ve virgülden bölünür.
- Görev kalıbı yoksa kayıt yalnızca not olarak saklanır.

**Kişi** — Büyük harfle başlayan kelime + ek: "Ahmet'i", "Ayşe'ye". Hitaplar: abi, abla, hoca, bey, hanım. Kişisel sözlükteki isimler önceliklidir.

**Öncelik (Yüksek / Normal / Düşük)** — Tarih yakınlığı (bugün ve yarın puanı yükseltir); vurgu kelimeleri: acil, mutlaka, hemen, kesin, önemli, unutma; aynı konunun bir hafta içinde yeniden söylenmesi; kullanıcının önceki düzeltmeleri (v2).

**Kategori** — Anahtar kelime sözlüğü: fatura, kira, ödeme → Para; market, süt, ekmek → Alışveriş; toplantı, müşteri → İş. Öğrenilen ağırlıklar bu sözlüğe eklenir. Toplam puan eşiğin altındaysa kategori Diğer olur.

**Tekrar** — "Her gün", "her pazartesi", "her hafta", "ayın 1'inde", "her sabah".

**Bitirme cümleleri** — Geçmiş zaman ekleri: -dı/-di/-du/-dü, -tı/-ti/-tu/-tü ("aradım", "ödedim", "aldım"). Kapanış kelimeleri: hallettim, bitti, tamam, yaptım. Fiil kökü + nesne açık görevlerle eşleşir; birden çok aday varsa kart seçim sunar.

**Örnek cümle seti** — Beklenen çıktısıyla en az 200 Türkçe örnek cümle bir dosyada tutulur. Set kullanıcı "test et" dediğinde çalıştırılır.

## 7. Hatırlatma, geri getirme ve hiyerarşi
Her görev beş durumdan birindedir (gelen, açık, ertelendi, bitti, iptal); geçişlerin çoğu otomatiktir, kullanıcı her geçişi geri alabilir. Gecikti ayrı bir durum değildir; tarihi geçen açık görev sorguda böyle gösterilir. Kullanıcı her durumdan Sil ile İptal'e geçebilir.

**Ana ekran sırası (Bugün ekranı)**
1. Gecikenler
2. Bugün
3. Yakında (önümüzdeki 7 gün)
4. Tarihsiz açık görevler, önceliğe göre
5. Notlar (görev içermeyenler), kategoriye göre
6. Bitenler, en yeni üstte; 30 günden eskiler daraltılır

Her bölümün içinde sıra önce önceliğe, sonra tarihe göredir.

**Otomatik taşıma kuralları**
- Sesle bitirme onaylanınca ya da bildirimde Bitti'ye basılınca görev Bitenler'e taşınır.
- Tekrarlayan görev bitince bir sonraki tekrarı kendiliğinden oluşur.
- Bitti işaretlenen görevin notu, başka açık görevi kalmadıysa arşive geçer.

**Gecikme yönetimi**
- Tarihi bir gün geçmiş ve dokunulmamış görev için "Hâlâ geçerli mi?" sorulur.
- Üç kez ertelenen görev için "Bunu bölelim mi, silelim mi?" önerisi gelir.
- 30 gündür dokunulmayan düşük öncelikli görev arşive önerilir; kendiliğinden silinmez.

## 8. Kullanıcıdan öğrenme
Öğrenme model eğitimi değil, cihazda tutulan sayaçlardır: v1 olayları kaydeder, v2 bu kayıtlardan kural çıkarır.

| Sinyal | Öğrenilen | Devreye girme koşulu |
|---|---|---|
| Kategori düzeltmesi | Kelime → kategori ağırlığı | Aynı düzeltme 3 kez |
| Tarih/saat düzeltmesi | Dönem saatleri ("akşam" kaçta) | Aynı yöne 3 düzeltme |
| Ertelemeler | Saat belirtilmeyen görevlerin varsayılan saati | Son 10 ertelemenin çoğunluğu aynı dilimde |
| Bitirme hızı | Görev türüne göre gerçek önem | Türde en az 5 görev |
| Metin düzeltmesi | Kişisel sözlük (isim, yer, kısaltma) → Whisper ipucu | Aynı kelime 2 kez |
| Not alma saatleri | Sabah özeti saati | Öneri olarak sorulur, kendiliğinden değişmez |

- Öğrenilen her kural ilk uygulandığında kısa bir bilgi satırı gösterilir: "Fatura notlarını artık Para'ya koyuyorum".
- Ayarlarda "Uygulama ne öğrendi" ekranı vardır; her kural tek tek ya da hepsi birden sıfırlanabilir.
- Hiçbir öğrenme verisi telefondan çıkmaz.

## 9. Ekranlar
Birinci sürüm dokuz ekran ve yüzeyden oluşur; uygulama her zaman Bugün ekranında açılır.
1. **İlk açılış:** İzinler (mikrofon, bildirim, tam zamanlı alarm, pil optimizasyonu muafiyeti) ve Whisper modelinin indirilmesi.
2. **Bugün:** Altta büyük kayıt düğmesi, üstte 7. bölümdeki hiyerarşik liste.
3. **Kayıt:** Kayan dalga formu ve süre; bitince "İşleniyor" göstergesi.
4. **Onay kartı (alt sayfa):** Anlaşılan görevler, tarih, kişi, kategori ve öncelik. Her alan dokunarak değişir. Düğmeler: Kaydet, Sadece not olarak kaydet.
5. **Görev detayı:** Metin, sesi dinleme, tarih, tekrar, ilgili not, Bitti/Ertele/Sil.
6. **Notlar:** Kategori sekmeleri, filtreler, arama.
7. **Bitenler:** Tarihe göre gruplanmış liste, geri alma.
8. **Ayarlar:** Sabah özeti saati, dönem saatleri, kategoriler, model seçimi (base/small), yedekleme ve dışa aktarma, "Uygulama ne öğrendi".
9. **Widget'lar:** Tek dokunuşla kayıt ve bugünkü görev sayısı.

## 10. Sürüm yol haritası
Bir sürüm, kabul ölçütlerinin hepsi işaretlenmeden bir sonrakine geçilmez.

**v1 — Sesle çalışan asistan (MVP).** Kapsam: kayıt + Whisper base, kural tabanlı ayrıştırma, onay kartı, hatırlatma ve bildirim düğmeleri, sesle bitirme, sabah özeti, gecikme soruları, tekrarlayan görevler, hiyerarşik Bugün ekranı, widget, yerel yedek ve dışa aktarma (JSON + ses dosyaları), öğrenme olaylarının kaydı.
- [ ] İnternet kapalıyken akışın tamamı çalışıyor
- [ ] Örnek cümle setinde tarih ve saatlerin en az %90'ı doğru çıkıyor
- [ ] Hatırlatmalar telefon yeniden başlatıldıktan sonra da çalışıyor
- [ ] 1 dakikalık kaydın yazıya dönme süresi orta seviye telefonda ölçüldü ve kabul edilebilir bulundu
- [ ] "Aradım / ödedim / aldım" cümleleri doğru görevi kapatıyor
- [ ] Yedek dosyasından geri yükleme çalışıyor

**v2 — Uygulama kullanıcıyı öğrenir.** Kapsam: öğrenme kurallarının devreye girmesi, "Uygulama ne öğrendi" ekranı, anlamsal arama ve otomatik gruplama, gömme modeliyle daha iyi eşleştirme, konum hatırlatmaları, Whisper small seçeneği.
- [ ] Kategori düzeltmeleri 3 tekrardan sonra kendiliğinden uygulanıyor
- [ ] Anlamsal arama, kelimesi geçmeyen ilgili notu buluyor
- [ ] Öğrenilenler tek tek sıfırlanabiliyor

**v3 — Özet ve temize çekme.** Kapsam: isteğe bağlı indirilen küçük dil modeli; haftalık özet, notu temize çekme, liste çıkarma ("market listemi çıkar"), serbest soru ("Ahmet'le ne konuşmuştuk"). Premium paket adayıdır.
- [ ] Model indirilmeden uygulama eksiksiz çalışmaya devam ediyor
- [ ] Eski telefonlarda model kapalı tutulabiliyor

## 11. Riskler ve önlemler
En büyük risk teknik değil güvenle ilgili: uygulama birkaç kez yanlış anlarsa kullanıcı onu bırakır.

| Risk | Etkisi | Önlem |
|---|---|---|
| Yanlış otomatik karar | Güven kaybı | Onay kartı, geri alma, öğrenme katmanı |
| Whisper'ın Türkçe hataları (isim, gürültü) | Yanlış görev ya da kişi | Kişisel sözlük ipucu, small model seçeneği, metni düzenleyebilme |
| Android alarm kısıtları (tam zamanlı alarm izni, üreticilerin pil tasarrufu) | Hatırlatma kaçar | İzin akışı, pil muafiyeti rehberi, açılışta alarmları yeniden kurma |
| Eski telefonlarda yavaşlık ve pil | Kötü deneyim | Base model, parça parça işleme, arka plan iş kuyruğu |
| Bildirim yorgunluğu | Kullanıcı bildirimleri kapatır | Tek sabah özeti, sessiz saatler |
| Veri kaybı | Güven kaybı | Otomatik yerel yedek, dışa aktarma |
| Kapsam büyümesi | v1 gecikir | v1 listesi dışında özellik eklenmez |
| Model dosyalarının boyutu | Uygulama ağırlaşır | Modeller uygulamadan ayrı, ilk açılışta indirilir |

## 12. Çalışma düzeni
Her sohbet tek bir adımı bitirir. Kurallar `CLAUDE.md`'de.

**Sohbet sırası (v1)**
1. Teknoloji kararı ve proje iskeleti; Ses Defteri'nden kayıt modülünün alınması
2. Veritabanı ve veri modeli
3. Whisper entegrasyonu ve parça parça yazıya çevirme
4. Ayrıştırma: tarih ve saat
5. Ayrıştırma: görev, kişi, öncelik, kategori, tekrar ("her ayın 1'inde kira")
6. Onay kartı
7. Hatırlatmalar ve bildirim düğmeleri (Bitti / Ertele / Aç); Bitti → Bitenler'e otomatik taşıma, tekrarlayan görevin sonraki tekrarı
8. Bugün ekranı ve hiyerarşi (Gecikenler → Bugün → Yakında → Tarihsiz → Notlar → Bitenler)
9. Sesle bitirme ve eşleştirici (onaylanınca Bitenler'e taşıma, notun arşivlenmesi)
10. Sabah özeti ve gecikme soruları ("Hâlâ geçerli mi?", 3 kez ertelenen görev için bölme/silme önerisi)
11. Widget, kilit ekranından kayıt, ayarlar, yedekleme ve dışa aktarma
12. Öğrenme olaylarının kaydı ve v1 yayını

**Sürüm kuralı (kullanıcı, 25 Ekim 2026):** Her sürüm bir öncekinin üstüne eklenir. Bir sürümün 10. bölümdeki kabul ölçütlerinin hepsi sağlanmadan bir sonrakine geçilmez. v2/v3 özellikleri v1 içine alınmaz.

## 13. Açık kararlar
İlk sohbete başlamadan önce ilk iki karar verilmeli; diğerleri v1 bitmeden netleşir.
- Ayrı uygulama mı, Ses Defteri içinde modül mü? Öneri: ayrı uygulama, kayıt kodu paylaşılır. → **Karar: ayrı uygulama**
- Dil ve arayüz altyapısı Ses Defteri ile aynı mı olacak? → **Karar: aynı**
- İlk hedef kitle: öğrenci, esnaf ya da genel kullanıcı
- Uygulamanın adı
- Whisper modeli uygulamanın içinde mi gelecek, ilk açılışta mı indirilecek?
- Gelir modeli: ücretsiz, tek seferlik ödeme ya da premium (v3 özellikleri)
- Dağıtım: GitHub Releases mı, Google Play kapalı test mi?
