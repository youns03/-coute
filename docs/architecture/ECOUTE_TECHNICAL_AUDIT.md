# التدقيق الهندسي الشامل لمشروع Écoute

**تاريخ التدقيق:** 2026-11-23  
**النطاق:** فرع `main` عند الالتزام `502a71f`، الوسم `v1.0.1`  
**المستودع:** [youns03/-coute](https://github.com/youns03/-coute)  
**نوع العمل:** تدقيق وتشخيص وتخطيط فقط. لم تُجرَ تغييرات وظيفية، ولم تُعدَّل ملفات Gradle أو قاعدة البيانات، ولم يُدمج أي Pull Request.

## A. الملخص التنفيذي

### الحالة الحقيقية

Écoute ليس هيكلاً فارغاً أو واجهة شكلية. هو تطبيق Android أصلي يملك مساراً فعلياً من الواجهة إلى قاعدة البيانات ومحركات النطق والترجمة والتخزين المؤقت وخدمات الخلفية. توجد فيه طبقات واضحة نسبياً: Compose للواجهة، ViewModels للحالة، مستودعات للبيانات والملفات، Room وDataStore للاستمرارية، WorkManager للتنزيل والترجمة الطويلة، ومدير مركزي للنماذج ومحركات TTS.

لكن الجاهزية الحالية أقرب إلى **نواة تشغيل متقدمة تحتاج تثبيت الأساس التشغيلي والتحقق على أجهزة حقيقية**، وليست بعد منصة إنتاج مستقرة يمكن إثبات عملها محلياً على جميع الأجهزة المستهدفة. السبب ليس غياب الوظائف، بل وجود فجوات في التحقق، ومشكلة توافق مؤكدة في فرع `main` مع API 24/25، واعتماد بعض الاستمرارية على `CoroutineScope` داخل العملية، إضافة إلى نقاط غير ذرية في تثبيت النماذج ومسارات لم تُختبر ميدانياً.

التقييم العام:

- **البنية الحالية:** جيدة وقابلة للتنظيم، وليست بحاجة إلى هدم شامل.
- **إدارة النماذج:** مركزية إلى حد كبير، مع حاجة إلى تقوية الذرية والتزامن والإصدارات.
- **إدارة المحركات:** موحدة عبر `TtsEngineManager`، مع احتفاظ Kokoro وPiper بخصوصيتهما الداخلية.
- **المهام الطويلة:** موثوقة نسبياً للتنزيل والترجمة، لكنها ليست نظام مهام موحداً لكل العمليات.
- **العمل دون اتصال:** متاح بعد التجهيز للنطق والترجمة المحلية والقراءة، لكنه غير مثبت باختبار Offline حقيقي، وبعض المكتبات الخارجية لا يمكن إثبات سلوكها الشبكي من الكود وحده.
- **الاختبارات:** تغطية JVM/Robolectric قوية نسبياً للمنطق، لكنها لا تثبت النطق الحقيقي أو GPU أو الصوت أو استمرارية العملية بعد قتل التطبيق.
- **الحاجة المعمارية:** إعادة تنظيم تدريجية حول مديري الموارد والتشغيل والمهام، لا إعادة بناء من الصفر.

### أخطر خمس مشكلات

1. **توافق API 24/25 مكسور في `main`:** يستخدم `File.toPath()` في `ModelStorageManager` رغم أن `minSdk = 24`. طلب الدمج المفتوح رقم 2 يعالج هذه النقطة، لكنه غير مدمج، وCI الخاص به فشل.
2. **تثبيت النموذج ليس Commit ذرياً بالكامل:** يتم حذف مجلد النموذج النهائي قبل نجاح `renameTo` أو `copyRecursively`، ما قد يفقد نسخة سليمة إذا فشل النقل النهائي.
3. **تشغيل الصوت ليس مهمة قابلة للاستعادة بعد قتل العملية:** التشغيل يعتمد على `CoroutineScope` داخل `AudioPlaybackController`؛ `PlaybackService` يوفّر MediaSession وإشعاراً لكنه لا يملك Queue تشغيل مستقلة قابلة لإعادة البناء بعد موت العملية.
4. **لا يوجد اختبار Offline فعلي شامل:** الكود يثبت أن النماذج موجودة قبل التشغيل، لكن لا توجد اختبارات تمنع أو ترصد اتصالات الشبكة أثناء القراءة والنطق والترجمة بعد التجهيز.
5. **المسارات الطويلة والموارد غير مقاسة على جهاز حقيقي:** حجم Kokoro، الذاكرة، OpenCL، TTFA، الحرارة، أداء `AudioTrack`، واستئناف التنزيل لم تُثبت على ARM64 فعلي وفق ما يذكره README ووفق عدم توفر Android SDK/جهاز في بيئة التدقيق.

### القرار المعماري

لا أوصي بهدم الأساس. أوصي بمرحلة أولى لتثبيت التوافق والاختبارات والذرية، ثم إعادة تنظيم تدريجية حول ستة مكونات منطقية: `Model & Resource Manager`، `Local Runtime Manager`، `Engine Adapters`، `Local Task Orchestrator`، `Offline Capability Layer`، و`Persistence Layer`.

## B. حالة المستودع والفروع وCI

تم فحص حالة Git المحلية:

- الفرع المفحوص: `main`.
- `main` متطابق مع `origin/main` عند `502a71f`.
- الفروع المتاحة في النسخة المستنسخة: `main` فقط، مع `origin/main` ومرجع `origin/HEAD`.
- الوسومان الظاهران: `v1.0.1` و`v1.0.0`.
- لا توجد تغييرات محلية وظيفية في المستودع أثناء الفحص.

من GitHub API:

- Pull Request رقم 2 مفتوح ومسودة بعنوان **Fix model integrity paths for Android API 24**. فرعه `fix/model-storage-api24-paths` مبني على `main`، ولم يُدمج.
- Pull Request رقم 1 مغلق.
- آخر Release workflow للوسم `v1.0.1` ناجح.
- تشغيلات Android CI الخاصة بالـPR رقم 2 انتهت بفشل أو إلغاء. وصف الـPR نفسه يذكر أن اختبارات Gradle وlint لم تكن مؤكدة النجاح، وآخر تشغيل مكتمل فشل في خطوة `Run unit tests and lint`.
- لم أستخدم `gh` للمصادقة أو لتغيير أي شيء. استخدمت GitHub API العام للقراءة فقط.

### تحقق البناء المحلي

حاولت تشغيل:

```text
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
```

التنفيذ لم يصل إلى الاختبارات. توقف Gradle قبل بناء المهام بسبب عدم وجود Android SDK:

```text
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable
or by setting the sdk.dir path in local.properties.
```

لذلك لا يجوز اعتبار الاختبارات المحلية ناجحة أو فاشلة؛ هي **لم تُنفذ** في بيئة التدقيق. هذه نتيجة بيئية وليست دليلاً على عطل في الاختبار نفسه.

## C. خريطة البنية الحالية

### الطبقات والحزم

```text
Compose UI
  └── MainActivity / Screens
        └── ViewModels
              └── Repositories / Managers
                    ├── Room DAOs + AppDatabase
                    ├── DataStore SettingsRepository
                    ├── WorkManager Workers
                    ├── TtsEngineManager
                    │     ├── KokoroLiteRtTtsEngine
                    │     └── PiperSherpaTtsEngine
                    ├── TranslationEngineManager
                    │     └── MlKitTranslationEngine
                    ├── AudioPlaybackController
                    └── ModelStorageManager
```

### نقطة الدخول والتجميع

`EcouteApplication.onCreate()` ينشئ قاعدة البيانات والمستودعات والمديرين ويربطهم يدوياً. هذا يوفر خدمة مركزية فعلية داخل التطبيق، لكنه يجعل `Application` حاوية اعتماديات يدوية لا حاوية حقن اعتماديات مستقلة.

المسار موجود في `app/src/main/java/com/example/EcouteApplication.kt:42-82`:

- `AppDatabase`.
- `SettingsRepository`.
- `ModelStorageManager`.
- `BookImporter`.
- `TtsEngineManager`.
- `ModelRepository`.
- `LibraryRepository`.
- `TranslationEngineManager`.
- `FileTranslationRepository`.
- `AudioPlaybackController`.

### الواجهة والحالة

`MainActivity.kt:91-420` يربط جميع الشاشات بالـViewModels. الواجهات لا تستدعي محركات TTS أو ML Kit مباشرة؛ الاستدعاء يمر عبر ViewModels، ثم المستودعات والمديرين. هذه نقطة جيدة وتحقق فصلاً معقولاً بين العرض والتشغيل.

الاستثناء العملي هو أن `MainActivity` ينشئ عدة ViewModels مباشرة عبر `viewModel()`، بينما الاعتماديات الحقيقية تأتي من cast إلى `EcouteApplication` داخل كل ViewModel. هذا يعمل، لكنه يربط ViewModels بتطبيق محدد ويصعّب اختبارها بمعزل عن `Application`.

### هل توجد بنية موحدة؟

الإجابة الدقيقة: **توجد بنية موحدة جزئياً وليست منصة تشغيل موحدة بالكامل**.

ما هو مركزي فعلاً:

- اختيار النماذج وتوافقها وتحميل TTS: `TtsEngineManager`.
- تخزين النماذج وفحص سلامتها: `ModelStorageManager`.
- حالة النماذج والتنزيل: `ModelRepository` و`ModelDownloadWorker`.
- الترجمة المحلية: `TranslationEngineManager`.
- قاعدة البيانات: `AppDatabase` وDAOs.

ما هو مستقل أو مكرر:

- تنزيل نماذج TTS يمر عبر WorkManager، بينما تنزيل حزم ML Kit يمر مباشرة عبر `MlKitTranslationEngine.downloadModel()` من ViewModel coroutine، وليس عبر نفس نظام المهام.
- تشغيل الصوت له `CoroutineScope` خاص وQueue داخل `AudioPlaybackController`، ولا يدخل في `Local Task Orchestrator` موحد.
- معالجة واستيراد الكتب عملية coroutine عادية وليست مهمة قابلة للاستعادة.
- كل محرك يحتفظ بتفاصيل دورة حياته داخلياً، وهو صحيح من ناحية الخصوصية، لكن بعض سياسات الحالة والأخطاء موزعة بين المحرك والمدير.

## D. خريطة تدفق الوظائف الرئيسية

### القراءة والاستيراد

1. `LibraryScreen` يرسل URI إلى `LibraryAndVocabViewModel`.
2. الـViewModel يستدعي `LibraryRepository.importBook()`.
3. `BookImporter.importFromUri()` يقرأ TXT أو EPUB في `Dispatchers.IO`.
4. يتم كشف اللغة، استخراج الأقسام، تقسيمها بواسطة `SpeechTextProcessor`، ثم إنشاء `BookEntity` و`ChapterEntity` و`SentenceEntity`.
5. `BookDao.insertFullBook()` يحفظها داخل Transaction.
6. الواجهة تنتقل إلى `Reader`.

الدليل: `BookImporter.kt:60-109, 315-367` و`LibraryRepository.kt:69-75` و`AppDaos.kt:34-43`.

الاستيراد محمي بحدود حجم الملف والنص وعدد عناصر EPUB. لا يوجد دعم PDF؛ الرفض مقصود في `BookImporter.kt:67-69`.

### القارئ والترجمة الفورية

1. `ReaderViewModel.openBook()` يضبط الكتاب والفصل الحالي ويقرأ موضع القراءة المحفوظ.
2. `ReaderScreen` يعرض `readerState` القادم من Room عبر Flow.
3. ترجمة الجملة تمر عبر `TranslationEngineManager.translateSentence()`.
4. المدير يختار `FR_TO_AR` أو `EN_TO_AR` ثم يستدعي ML Kit.
5. عند النجاح، تُخزن الترجمة في `sentences.cachedArabicTranslation`.
6. ترجمة الفقرة تنفذ جملة بعد جملة داخل coroutine.

الدليل: `ReaderViewModel.kt:134-145, 223-285` و`TranslationEngineManager.kt:123-159`.

### تشغيل فصل صوتي

1. `ReaderViewModel.playFromSentence()` أو `togglePlayCurrentChapter()` يستدعي `AudioPlaybackController`.
2. المتحكم ينشئ جلسة جديدة عبر `claimNewSession()`، يلغي الجلسات القديمة ويفرغ AudioTrack.
3. يقرأ الجمل من `BookDao` ويحدد اللغة والنموذج من الإعدادات.
4. `TtsEngineManager.resolveModelForLanguage()` يرفض النموذج غير المتوافق أو يختار بديلاً مثبتاً.
5. الجملة تُقرأ من `AudioChunkCache` إن وجدت، وإلا تُولد عبر `synthesizeSentenceStream()`.
6. الصوت يكتب تدريجياً إلى `AudioTrack`، مع تخزين النسخة وإبراز الكلمات عند توفر timestamps.
7. يتم التحضير المسبق لجملة تالية واحدة.
8. تحفظ مواضع القراءة في `books.currentChapterIndex/currentSentenceIndex`.

الدليل: `AudioPlaybackController.kt:271-354, 421-637, 681-785` و`TtsEngineManager.kt:90-140, 293-338`.

### تنزيل وتثبيت نموذج TTS

1. الواجهة تستدعي `ModelsAndSettingsViewModel.startOrResumeDownload()`.
2. `ModelRepository.startOrResumeDownload()` ينشئ OneTime Work فريداً باسم النموذج.
3. `ModelDownloadWorker` يفحص توافق الجهاز والاتصال والمساحة.
4. يستخدم HTTP Range لاستئناف الجزء المؤقت.
5. `ModelStorageManager.installVerifiedPackage()` يتحقق من الحجم وSHA-256، ثم يفك الأرشيف أو ينقل ملف LiteRT.
6. يكتب `.integrity.sha256` ويفحص بنية الملفات.
7. `TtsEngineManager.verifyModelLoadable()` يقوم بتحميل النموذج وتوليد `Bonjour.`.
8. فقط بعد نجاح الاختبار يسجل Worker النموذج كـ`INSTALLED`.

الدليل: `ModelRepository.kt:188-200` و`ModelDownloadWorker.kt:64-154, 157-275` و`ModelStorageManager.kt:95-153`.

### ترجمة ملف طويلة

1. المستخدم يختار ملفاً من `FileTranslatorScreen`.
2. `FileTranslatorViewModel` يستدعي `FileTranslationRepository.createJobFromUri()`.
3. يتم تحليل الملف وتقسيمه بواسطة `DocumentChunker` إلى chunks محفوظة في Room.
4. `startOrResumeJob()` يضع المهمة في WorkManager باسم فريد.
5. `FileTranslationWorker` يقرأ chunks غير المكتملة، يترجمها، ويحفظ كل نتيجة مع فحص `jobEpoch`.
6. بعد الإكمال يحدّث حالة المهمة إلى `COMPLETED`.
7. `exportJobToUri()` ينسق الناتج ويكتب BOM UTF-8.

الدليل: `FileTranslationRepository.kt:36-59, 98-115, 129-147` و`FileTranslationWorker.kt:31-101` و`DocumentChunker.kt:142-215`.

## E. سجل المشكلات والمخاطر

درجات الخطورة: **حرج** يمنع جاهزية أساسية، **عالٍ** يؤثر في أجهزة أو بيانات أو تشغيل موثوق، **متوسط** خطر قابل للإدارة، **منخفض** تحسين أو دين تقني.

### E-1. استخدام API 26+ مع minSdk 24 — عالٍ، مثبت من الكود

- **الموقع:** `app/src/main/java/com/example/models/ModelStorageManager.kt:170-183, 245-249`.
- **الدليل:** الكود يستخدم `dir.canonicalFile.toPath()` و`rootPath.relativize(...)`، بينما `app/build.gradle.kts:58-63` يحدد `minSdk = 24`.
- **الأثر:** قد يفشل فحص سلامة النموذج أو التثبيت على API 24 و25، وهما ضمن الأجهزة المعلنة.
- **حالة المعالجة:** PR #2 المفتوح يستبدل ذلك بمسارات canonical string ويضيف اختبار API 24، لكنه ليس في `main`. لا ينبغي اعتبار الإصلاح موجوداً في النسخة المدققة.
- **التوصية:** اعتماد الإصلاح بعد مراجعة CI وتشغيل اختبار API 24، دون تغيير المحركات أو صيغة manifest.

### E-2. تثبيت النموذج ليس ذرياً عند استبدال نسخة موجودة — عالٍ، مثبت من الكود

- **الموقع:** `ModelStorageManager.kt:144-151`.
- **الدليل:** `finalDir.deleteRecursively()` يحدث قبل `stagingDir.renameTo(finalDir)` وقبل نجاح `copyRecursively`.
- **الأثر:** إذا فشل النقل أو نفدت المساحة أثناء النسخ، قد تختفي نسخة سليمة كانت مثبتة سابقاً. هذا يضر قابلية الترقية دون فقد وظيفة سابقة.
- **السبب الجذري:** لا يوجد commit عبر مجلد جديد ثم swap/rollback، ولا backup مؤقت للنسخة السابقة.
- **التوصية:** في مرحلة التنفيذ، اعتماد commit ذري أو rollback واضح، مع عدم تغيير صيغة التخزين قبل وضع اختبارات استعادة.

### E-3. التشغيل الصوتي ليس مهمة قابلة للاستعادة بعد موت العملية — عالٍ، مثبت من الكود

- **الموقع:** `AudioPlaybackController.kt:213-214, 421-637` و`PlaybackService.kt:44-90`.
- **الدليل:** Queue وJobs داخل `AudioPlaybackController`، بينما `PlaybackService` ينشئ MediaSession وجسراً لحالة المتحكم ويعرض الإشعار.
- **الأثر:** إيقاف العملية أو قتلها لا يملك مساراً موثقاً لإعادة بناء Queue الحالية واستئناف الجملة. الخدمة ليست مالكة مستقلة لحالة التشغيل؛ هي واجهة تحكم وإشعار حول متحكم Application.
- **التوصية:** لا يلزم WorkManager للصوت التفاعلي، لكن يلزم تعريف واضح لنموذج الاستعادة: إما قبول فقدان الجلسة، أو حفظ PlaySession/queue/position وإعادة البناء من الخدمة عند الحاجة.

### E-4. لا يوجد إثبات آلي لاستقلال التشغيل عن الإنترنت — عالٍ، فجوة تحقق

- **المواقع:** `MlKitTranslationEngine.kt:43-99`، `ModelDownloadWorker.kt:176-207`، `ModelCatalog.kt:19-145`.
- **الدليل:** تنزيل النماذج واضح أنه شبكي، بينما النطق بعد التثبيت يستخدم ملفات محلية. ML Kit يفحص `isModelDownloaded` ثم ينادي `translate`; لا يوجد Network Security test أو fake network interceptor أو اختبار airplane mode.
- **الأثر:** لا يمكن من الكود وحده إثبات أن كل مسار بعد التجهيز لا يسبب اتصالاً أو لا يحتاج خدمة خارجية داخل مكتبة ML Kit.
- **التوصية:** إضافة اختبار Offline على جهاز/محاكي مضبوط الشبكة، ومراقبة traffic، مع فصل حالة `READY_OFFLINE` عن مجرد `isModelDownloaded` إن لزم.

### E-5. احتمال تنافس تنزيل eSpeak المشترك — متوسط، مستنتج من المسار

- **الموقع:** `ModelDownloadWorker.kt:98-121` و`ModelStorageManager.kt:40, 288-293`.
- **الدليل:** كل نموذج Piper يملك unique work خاصاً به، لكن بيانات `espeak-ng-data` مشتركة. عاملان لنموذجين مختلفين يمكن أن يريا dependency ناقصة ويبدآ تنزيلها في الوقت نفسه.
- **الأثر:** تكرار تنزيل أو سباق على مجلد التثبيت المشترك، خصوصاً إذا بدأ المستخدم تنزيل SIWIS وTom معاً.
- **حالة الإثبات:** لم أُشغّل WorkManager فعلياً، لذلك هذا خطر مسار وليس فشلاً مثبتاً على جهاز.
- **التوصية:** مهمة dependency فريدة مستقلة أو قفل مشترك، مع اختبار تنزيلين متوازيين.

### E-6. `ExistingWorkPolicy.REPLACE` قد يعيد تشغيل تنزيل بدلاً من استئنافه من نفس Worker — متوسط

- **الموقع:** `ModelRepository.kt:188-200` و`FileTranslationRepository.kt:98-115`.
- **الدليل:** كل ضغط متكرر ينشئ Work جديداً بسياسة `REPLACE`. الجزء المؤقت يحفظ bytes، لذلك تنزيل النموذج قد يستأنف من الملف، لكن حالة العمل نفسها تُلغى وتستبدل.
- **الأثر:** ضغط متكرر أو تحديث UI قد يلغي Worker فعالاً ويؤدي إلى إشعارات وحالات انتقال غير ضرورية.
- **التوصية:** استخدام سياسة تمنع التكرار عندما تكون المهمة موجودة، مع جعل الاستئناف عملية صريحة.

### E-7. حفظ الكتاب Transaction لا يغطي كل الملفات الخارجية — متوسط، مثبت جزئياً

- **الموقع:** `LibraryRepository.kt:69-75` و`AppDaos.kt:34-43`.
- **الدليل:** بيانات الكتاب والجمل داخل Room Transaction، لكن EPUB المؤقت يحذف بعد التحليل ولا توجد نسخة أصلية محفوظة. هذا مقصود للتقليل من التخزين، لكنه يعني أن إعادة التحليل غير ممكنة من التطبيق بعد الاستيراد.
- **الأثر:** حذف قاعدة البيانات أو فشل ترقية غير متوقعة لا يملك مساراً لاستعادة النص الأصلي إلا من الملف الخارجي لدى المستخدم.
- **التوصية:** توثيق القرار. لا حاجة لحفظ الأصل إن كان التصميم يعتمد على استيراد غير قابل لإعادة البناء، لكن يجب تضمين ذلك في سياسة النسخ الاحتياطي.

### E-8. بناء حجم الملف قد يستهلك RAM في التصدير ومعالجة الصوت — متوسط، محتمل

- **المواقع:** `DocumentChunker.kt:142-198`، `TtsEngineManager.kt:400-436`.
- **الدليل:** التصدير يبني String كاملة، والـbenchmark يجمع كل samples في `MutableList<Float>` ثم يحولها إلى `FloatArray`. القراءة والنطق العاديان أكثر تحفظاً، لكن الملفات الطويلة وتقارير benchmark قد ترفع الذاكرة.
- **الأثر:** OOM أو ضغط GC على الأجهزة الضعيفة.
- **حالة الإثبات:** توجد حماية OOM في بعض مسارات TTS، لكن لم تُقَس الحدود على أجهزة فعلية.
- **التوصية:** قياس peak RSS/PSS مع ملفات كبيرة، ثم اعتماد streaming export أو حدود صريحة إذا ثبتت المشكلة.

### E-9. تحديثات الإعدادات قد تفصل الاختيار المخزن عن النموذج المحمل — متوسط، مثبت مسارياً

- **الموقع:** `ModelsAndSettingsViewModel.kt:77-97, 111-121` و`SettingsDataStore.kt:63-77`.
- **الدليل:** تفعيل النموذج يحمّله ثم يكتب `activeModelId` عند النجاح، وهذا جيد. لكن تغيير وضع accelerator يعيد تحميل النموذج الحالي مباشرة؛ إذا فشل يعرض رسالة، بينما الإعداد الجديد يكون قد حُفظ قبل فشل إعادة التحميل.
- **الأثر:** قد يصبح DataStore يقول `GPU_ONLY` بينما المحرك السابق بقي محملاً بوضع مختلف أو تم تفريغه حسب مسار الخطأ.
- **التوصية:** حفظ الإعداد بعد نجاح التطبيق، أو حفظ runtime/applied state منفصلاً عن requested state.

### E-10. التوافق القانوني والتجاري للنماذج غير محسوم بالتدقيق القانوني — متوسط

- **الموقع:** `ModelCatalog.kt:163-223`.
- **الدليل:** المشروع يوثق Apache وMIT وCC-BY وGPL وشروط ML Kit، ويشير صراحة إلى أن eSpeak-ng قد يفرض GPL review.
- **الأثر:** لا يمنع التشغيل التقني، لكنه قد يمنع توزيع نسخة تجارية أو إعادة توزيع بعض الملفات.
- **التوصية:** مراجعة قانونية منفصلة قبل الإصدار التجاري، مع حزمة notices وآلية عدم تضمين مكونات غير مسموح بها عند الحاجة.

## F. مصفوفة العمل المحلي والإنترنت

| الوظيفة | المتطلبات المحلية بعد التجهيز | الاعتماد على الإنترنت | قابلة للعمل دون اتصال؟ |
|---|---|---|---|
| فتح الكتب الموجودة في Room | قاعدة البيانات والبيانات المخزنة | لا يظهر طلب شبكي في مسار القراءة | نعم، مثبت من مسار الكود، مع ضرورة اختبار التطبيق في airplane mode |
| استيراد TXT | URI يمكن فتحه، ذاكرة ومساحة مؤقتة | لا | نعم أثناء الاستيراد من ملف محلي |
| استيراد EPUB | ZIP/XML/HTML محلي وحدود التخزين | لا | نعم أثناء الاستيراد من ملف محلي |
| قراءة كتاب مستورد | Room + Flow | لا يظهر طلب شبكي | نعم منطقياً |
| Kokoro TTS | نموذج `.litertlm` مثبت ومتحقق، LiteRT/MediaPipe داخل APK | لا يظهر طلب شبكي بعد التثبيت | نعم من مسار التطبيق، لكن GPU/Runtime يحتاج اختبار جهاز |
| Piper TTS | ONNX و`tokens.txt` وeSpeak data مثبتة ومتحققة | لا يظهر طلب شبكي بعد التثبيت | نعم من مسار التطبيق |
| تشغيل الصوت | TTS + AudioTrack + cache | لا | نعم من مسار الكود |
| ترجمة جملة عبر ML Kit | حزمة المصدر + العربية مثبتتان | `downloadModelIfNeeded` عند الإعداد الأول | بعد اكتمال الحزم: مصممة للعمل محلياً، لكن لم يُثبت ذلك باختبار منع الشبكة |
| ترجمة ملف طويلة | Room chunks + حزمة ML Kit | تنزيل الحزمة أول مرة فقط، حسب التصميم | بعد التجهيز: مصممة للعمل محلياً، غير مثبتة باختبار Offline فعلي |
| تنزيل نموذج TTS | اتصال، HTTP، مساحة مؤقتة | مطلوب | لا أثناء التنزيل |
| تنزيل حزمة ML Kit | اتصال وخدمة ML Kit | مطلوب | لا أثناء التنزيل |
| تصدير نتيجة الترجمة | URI قابل للكتابة وRoom | لا | نعم |
| benchmark وحفظ WAV | نموذج مثبت ومساحة `filesDir` | لا يظهر طلب شبكي | نعم من مسار الكود |

التمييز المهم: `ModelCatalog` يحتوي روابط GitHub/Hugging Face للتنزيل، و`ModelDownloadWorker` يستخدم OkHttp صراحة. هذه تبعية تجهيز أولي وليست دليلاً على أن TTS يحتاج الشبكة أثناء التشغيل. أما ML Kit فهو مكتبة خارجية؛ الكود يتحقق من تنزيل الحزم، لكن لا توجد في المستودع آلية اختبار تمنع المكتبة من أي سلوك داخلي غير مرئي.

## G. تقييم إدارة النماذج والموارد

### ما هو جيد

`ModelStorageManager` يقدم نقطة تخزين مركزية:

- النماذج الدائمة في `context.filesDir/models`.
- الأجزاء المؤقتة في `cacheDir/model_downloads`.
- cache الصوت في `cacheDir/audio_cache`.
- عينات benchmark في `filesDir/benchmark_samples`.

كما يوفر:

- SHA-256 للحزمة والملفات.
- manifest داخلياً يسجل الإصدار والبصمة وحجم وبصمة كل ملف.
- رفض الملفات غير المسجلة أو المعدلة.
- فحص بنية Kokoro وPiper.
- فحص المساحة المطلوبة مع هامش أمان.
- فحص ABI وRAM وSDK في `MemoryDiagnostics`.

`ModelRepository.reconcileStorageOnStartup()` يعيد مطابقة Room مع filesystem عند بدء التطبيق، ويحوّل الحالات التالفة إلى `CORRUPTED` أو `FAILED` بدلاً من الثقة بسجل قديم.

`TtsEngineManager` يمنع تحميل محركين معاً عبر `Mutex` ويفرغ النموذج السابق قبل التحميل. كما يدعم fallback من GPU إلى CPU في Kokoro ويجمع حالة runtime للواجهة.

### ما يحتاج تقوية

- لا توجد طبقة إصدارات متعددة أو rollback للنماذج؛ يوجد `version` داخل `ModelSpec` وmanifest، لكن الترقية تستبدل المجلد النهائي.
- لا يوجد lock عام لعمليات تثبيت أو حذف dependency المشتركة.
- `ModelRecordEntity` يخزن حالة التثبيت، لكنه ليس سجل lifecycle كاملاً للتحميل/التحقق/التحميل في الذاكرة/آخر استخدام.
- `verifyInstalledPackage()` يفحص الملفات عند الحاجة، لكنه قد يعيد hashing لملفات كبيرة على مسار UI إذا استُدعي من مكان غير مناسب؛ معظم الاستدعاءات الحالية تأتي من IO أو Worker، ويجب الحفاظ على ذلك.
- حجم cache الصوت مضبوط عبر pruning، لكن benchmark samples لا يظهر له pruning تلقائي.

### الحكم

توجد **إدارة مركزية حقيقية للنماذج**، وليست كل محركات النطق تتعامل مع الملفات مستقلة. لكن المركزية الحالية هي `ModelStorageManager + ModelRepository + TtsEngineManager` وليست مدير موارد موحداً يشمل ML Kit والصوت والمهام في عقد واحد.

## H. تقييم المحركات

### Kokoro LiteRT

- **الاختيار:** `TtsEngineManager` يختار ModelSpec حسب اللغة والتوافق والحالة.
- **التحقق:** `ModelStorageManager.verifyInstalledPackage`، ثم `verifyModelLoadable`.
- **التهيئة:** `KokoroLiteRtTtsEngine.initialize` يحدد ملف LiteRT، locale، voice، وDelegate.
- **التحميل:** `TextToSpeech.create` عبر `DefaultKokoroSessionFactory`.
- **التنفيذ:** synthesis كامل أو stream.
- **الأخطاء:** GPU fallback إلى CPU عند init أو runtime قبل خروج audio؛ OOM يتحول إلى Result فاشل ويحرر المحرك في المدير.
- **التحرير:** `release()` يغلق session.
- **إعادة الاستخدام:** نعم؛ session يبقى محملاً ويعاد تهيئته فقط عند تغير voice/locale/speed.
- **التزامن:** `TtsEngineManager.mutex` يمنع تضارب الاستخدام.
- **الشبكة:** لا يظهر طلب شبكي في المحرك نفسه.
- **المخاطر:** GPU/OpenCL وLiteRT وذاكرة Kokoro تحتاج اختباراً فعلياً؛ `GPU_ONLY` لا يملك fallback حسب المقصود، وهذا سلوك صحيح لكنه يحتاج UI واضحاً.

### Piper عبر Sherpa-ONNX

- **الاختيار والتحقق:** نفس المدير والتخزين، مع فحص ONNX/tokens/eSpeak.
- **التهيئة:** إنشاء `OfflineTts` مع provider CPU و2-4 threads.
- **التنفيذ:** `generate` أو callback streaming.
- **الأخطاء:** Result failures وتحقق locale يمنع تشغيل نموذج فرنسي على الإنجليزية.
- **التحرير:** `release()` يحرر session.
- **إعادة الاستخدام:** نعم، الجلسة تبقى محملة.
- **التزامن:** يقع تحت mutex المدير عند الاستخدام من مسار TTS المركزي.
- **الشبكة:** لا يظهر طلب شبكي.
- **المخاطر:** `generateWithCallback` native operation طويلة؛ الإلغاء يضبط flag، لكن مقدار الإلغاء الفعلي يعتمد على Sherpa native callback. يجب اختباره تحت إيقاف سريع.

### ML Kit On-Device Translation

- **الاختيار:** اتجاهان ثابتان `FR_TO_AR` و`EN_TO_AR`.
- **التحقق:** `RemoteModelManager.isModelDownloaded` للمصدر والعربية.
- **التهيئة:** `Translation.getClient` كسولاً.
- **التنفيذ:** `translate(clean).await()`.
- **الأخطاء:** Result failure برسالة عن الحزمة أو فشل ML Kit.
- **التحرير:** `Translator.close()` في `close()` وحذف الحزم عند الطلب.
- **إعادة الاستخدام:** نعم؛ translator يعاد استخدامه مع Mutex.
- **التزامن:** mutex لكل اتجاه داخل كل محرك، لكن TranslationEngineManager يملك محركاً لكل اتجاه.
- **الشبكة:** التنزيل واضح شبكياً؛ التشغيل بعد التنزيل مصمم محلياً، غير مثبت باختبار Offline.
- **المخاطر:** تنزيل الحزمة ليس WorkManager job، لذلك حالة التنزيل واستمراريته أقل توحيداً من TTS.

### الخلاصة

لا ينبغي استبدال Kokoro أو Piper أو Sherpa-ONNX أو ML Kit. المطلوب توحيد العقد المحيط بها: `prepare/verify/load/run/release/capabilities`، مع إبقاء تفاصيل LiteRT وSherpa وML Kit داخل Adapters مستقلة.

## I. تقييم نظام المهام والخدمات الخلفية

### WorkManager

يُستخدم بشكل مناسب لتنزيل النماذج وترجمة الملفات الطويلة. كلا المسارين يحفظان التقدم في Room، ويستخدمان unique work، ويقدمان إشعارات foreground.

ترجمة الملفات أقوى من ناحية التزامن: `jobEpoch` في `TranslationJobEntity` وQueries ذات `expectedEpoch` تمنع Worker قديم من الكتابة فوق مهمة أعيد ضبطها. `reconcileOnStartup()` يحول المهام `RUNNING` إلى `PAUSED`.

تنزيل النماذج يملك استئناف Range وفحص `Content-Range` وحذفاً للجزء التالف، لكنه يحتاج قفل dependency وCommit ذري كما سبق.

### Coroutines

تستخدم Coroutines بشكل صحيح في كثير من عمليات IO، لكن هناك عدة Scopes طويلة العمر:

- `EcouteApplication.appScope` لتهيئة startup.
- `AudioPlaybackController.scope` للتشغيل.
- `viewModelScope` لأحداث الواجهة.
- Scope جديد داخل `ModelRepository.cancelAndDiscardDownload` لحذف Room بعد إلغاء Work.

الـScope الجديد في `cancelAndDiscardDownload` لا يعاد إلى lifecycle واضح، لكنه عملية قصيرة. أما `AudioPlaybackController.scope` فهو أهم Scope تشغيلي ويحتاج سياسة lifecycle عند خروج التطبيق أو إغلاق الخدمة.

### Services

`PlaybackService` هو `MediaSessionService` ويعرض MediaSession وتحكماً خارجياً وإشعار foreground. هذا مناسب للتحكم من النظام، لكنه لا يحول التشغيل إلى pipeline مستقلة قابلة للاستعادة بعد قتل العملية.

### الحكم

يوجد نظامان موثوقان نسبياً للمهام: تنزيل TTS وترجمة الملفات. لا يوجد `Local Task Orchestrator` موحد لكل المهام. هذا ليس سبباً لهدم البنية، لكنه سبب كافٍ لإضافة طبقة تنسيق تدريجية قبل توسيع الميزات.

## J. التخزين واستمرارية البيانات

### Room

`AppDatabase` في الإصدار 3 ويضم:

- الكتب والفصول والجمل.
- المفردات.
- سجلات النماذج.
- تقارير benchmark.
- مهام الترجمة وأجزاؤها.

الترحيلات `1 -> 2` و`2 -> 3` موجودة في `AppDatabase.kt:31-78`. لا توجد Migration جديدة في هذه المهمة، ولم أعدلها.

نقاط إيجابية:

- إدخال الكتاب الكامل داخل Transaction.
- Foreign keys مع cascade للفصول والجمل وأجزاء الترجمة.
- فهارس مناسبة للجمل والـchunks.
- تحديث موضع القراءة في Room.
- حفظ الترجمة المؤقتة داخل الجملة.
- epoch لحماية مهام الترجمة من السباق.

نقاط تحتاج قياساً أو قراراً:

- البحث يستخدم `LOWER(originalText) LIKE` بحد 100 نتيجة، وهو مناسب للحجم الصغير لكنه ليس بحثاً نصياً كاملاً لمكتبة كبيرة.
- لا يظهر exportSchema لملفات Room (`exportSchema = false`)، ما يقلل قدرة مراجعة المخطط آلياً.
- حالات النماذج والملفات الخارجية موزعة بين Room وfilesystem، مع reconciliation جيد لكنه يحتاج اختبارات فشل النقل والانقطاع.

### DataStore

`SettingsRepository` يحفظ النموذج النشط، وضع التسريع، السرعة، إعدادات القراءة، الوضع الليلي، وactive book. يوجد fallback للقيم الافتراضية، لكن requested/applied runtime state غير منفصل تماماً عند فشل تغيير accelerator.

### الملفات الخارجية

- النماذج: `filesDir/models`.
- عينات WAV: `filesDir/benchmark_samples`.
- التنزيلات الجزئية وaudio cache: `cacheDir`، وبالتالي يمكن للنظام حذفها. هذا مقبول للـcache والجزء المؤقت، لكنه يعني أن استئناف تنزيل أو الاحتفاظ بالصوت المؤقت غير مضمون بعد ضغط التخزين.

## K. الأداء والجودة

### مثبت من التصميم أو الاختبارات الموجودة

- تحميل TTS يتم على IO، وليس Main.
- البث الصوتي لا ينتظر الجملة كاملة قبل بدء AudioTrack في المسار الحي.
- يوجد prefetch لجملة واحدة.
- هناك pruning لـaudio cache.
- يوجد fallback من GPU إلى CPU.
- توجد حماية من OOM في TTS manager وPlaybackController.
- استيراد EPUB/TXT يضع حدوداً للحجم والنص والملفات.
- التحقق التشفيري يرفض ملفات غير مسجلة أو معدلة.

### مخاطر محتملة تحتاج قياساً

- Kokoro قد يستهلك أكثر من الحدود المتاحة على أجهزة 2GB، خصوصاً عند fallback أو إعادة تهيئة session.
- `TtsEngineManager.verifyModelLoadable` يحمّل النموذج ويولد صوتاً بعد التنزيل؛ هذا صحيح للسلامة لكنه يضاعف زمن الإعداد واستهلاك الذاكرة في أول تثبيت.
- `TtsEngineManager.runBenchmarkItem` يجمع samples كلها في الذاكرة.
- `AudioPlaybackController` يستخدم `Thread.sleep` أثناء pause/write داخل مسار IO؛ لا يحجب Main، لكنه يستهلك thread ويحتاج قياساً عند كثرة الجلسات.
- قراءة manifest وحساب SHA-256 للملفات الكبيرة قد تكون مكلفة عند startup reconciliation.
- `folderSize` يمشي شجرة الملفات عند تحديثات UI، ويجب التأكد أنه لا يستدعى على Main من مسارات كثيرة.
- تشغيل نموذجين أو تغيير النموذج أثناء prefetch يعتمد على الإلغاء وmutex، ويحتاج اختبار ضغط حقيقي.

### ما لم أستطع إثباته

لم أستطع إثبات TTFA أو RTF أو peak RAM أو حرارة الجهاز أو نجاح OpenCL أو جودة الصوت. README نفسه يذكر أن هذه البنود تحتاج جهاز Android ARM64 حقيقياً، وبيئة التدقيق لا تحتوي Android SDK أو ADB أو جهازاً فعلياً.

## L. تقييم الواجهات وتجربة الاستخدام

لم أعدّل الواجهات. فحصت فقط ربطها بالسلوك.

نقاط جيدة:

- التنقل Responsive بين NavigationBar وNavigationRail.
- حالات النماذج والتنزيل والأخطاء تصل إلى UI عبر StateFlow.
- القارئ يعرض حالة الترجمة والقراءة من Flow.
- نموذج اللغة يظهر في اختيار القراءة، والنموذج غير المتوافق ينتج رسالة واضحة.
- الإشعارات موجودة للتنزيل والترجمة والتشغيل.
- الواجهة RTL والـstrings عربية.
- الوضع الليلي وإعدادات الخط وارتفاع السطر محفوظة في DataStore.

نقاط تحتاج تدقيقاً لاحقاً:

- تحديثات ML Kit من `ModelsAndSettingsViewModel.downloadTranslationPack()` تحدث داخل `viewModelScope`، لكنها ليست مهمة قابلة للاستعادة أو إشعار foreground مثل تنزيل TTS.
- `refreshAllStatuses()` يستدعى عند فتح القارئ وبعض الشاشات، وقد ينتج فحوصاً متكررة.
- تفعيل نموذج TTS يوقف playback ثم يحمّل النموذج، وهو سلوك منطقي لكنه يحتاج منع نقرات متكررة ورسالة تقدم واضحة.
- `jumpToSearchResult()` يغير الفصل ثم يبدأ التشغيل، بدلاً من الاكتفاء بالانتقال إلى النتيجة؛ هذا قد يكون مقصوداً لكنه يستحق مراجعة UX.
- لا يمكن من قراءة Compose وحدها إثبات أن جميع أزرار الحالات الطرفية تعرض loading/error الصحيح على جهاز فعلي.

## M. تقييم المكتبات والتقنيات المقترحة

### Kotlin Coroutines وFlow

**القرار:** الإبقاء عليها.  
**المشكلة الحالية:** بعض Scopes وسياسات الإلغاء موزعة، لا أن Coroutines غير كافية.  
**الفائدة:** توحيد dispatchers، cancellation، وstate flows.  
**التكلفة:** منخفضة.  
**البديل الأبسط:** إنشاء `AppCoroutineDispatchers` وواجهات task state دون إضافة مكتبة.

### WorkManager

**القرار:** الإبقاء والتوسيع تدريجياً.  
**المشكلة الحالية:** ML Kit download وبعض المعالجة ليست مهام قابلة للاستعادة.  
**الفائدة:** الاستمرارية بعد background/reboot وسياسة retry وإشعارات موحدة.  
**التكلفة:** موجود أصلاً ولا يحتاج تبعية جديدة.  
**البديل:** إبقاء الترجمة الفورية في coroutine، وعدم تحويل كل عملية قصيرة إلى Worker.

### Room

**القرار:** الإبقاء.  
**المشكلة الحالية:** reconciliation بين Room وfilesystem وغياب export schema.  
**الفائدة:** مصدر حالة موثوق للمهام والنتائج.  
**التكلفة:** لا حاجة لتغيير قاعدة البيانات الآن.  
**البديل:** لا يوجد بديل أبسط مناسب للمهام القابلة للاستعادة.

### DataStore

**القرار:** الإبقاء.  
**المشكلة الحالية:** requested/applied accelerator غير مفصولين.  
**الفائدة:** فصل الحالة المطلوبة عن حالة runtime دون مكتبة جديدة.  
**التكلفة:** تعديل مفاتيح لاحقاً يحتاج Migration/compatibility policy، لذلك يؤجل إلى خطة تنفيذ مستقلة.

### ONNX Runtime

**القرار:** عدم إضافة ONNX Runtime جديد.  
**السبب:** Piper يعمل عبر Sherpa-ONNX الموجود، وإضافة runtime آخر ستزيد حجم APK والـABI والذاكرة وتخلق مسارين للتشغيل دون مشكلة مثبتة تستحق ذلك.  
**البديل:** تحسين Adapter الحالي وقياسه أولاً.

### Android Media3

**القرار:** الإبقاء.  
**السبب:** Media3 مستخدم للـMediaSession والتحكم النظامي، بينما AudioTrack يستخدم للبث الدقيق. لا توجد أدلة أن استبدال أحدهما سيحل مشكلة مؤكدة.  
**البديل:** توثيق حدود BridgeMediaPlayer وتحسين lifecycle.

### أدوات القياس

**القرار:** إضافة أدوات قياس في مرحلة التنفيذ فقط، وليس الآن.  
**المطلوب:** Macrobenchmark/Perfetto أو أدوات Android القياسية، وقياس PSS/CPU/TTFA/RTF/thermal على أجهزة محددة.  
**السبب:** المخاطر الأهم حالياً غير قابلة للحسم من static review وحده.

## N. البنية المعمارية المستهدفة

المقترح هو إعادة تنظيم حول عقود واضحة مع الحفاظ على المحركات الحالية.

### 1. Model & Resource Manager

مسؤول عن:

- ModelSpec وversion وcompatibility.
- download/resume/verify/install/upgrade/rollback.
- shared dependencies مثل eSpeak.
- disk budget وstorage breakdown.
- حالة filesystem وRoom.

لا ينفذ inference ولا يعرف تفاصيل LiteRT أو Sherpa إلا عبر metadata/adapter.

### 2. Local Runtime Manager

مسؤول عن:

- اختيار runtime مناسب للغة والجهاز.
- load/unload/reuse.
- حجز الذاكرة المنطقية ومنع تحميل متضارب.
- fallback policy.
- runtime state وapplied settings.

`TtsEngineManager` هو النواة الحالية لهذا المكون ويمكن إعادة تسميته/تقسيمه تدريجياً دون هدمه.

### 3. Engine Adapters

عقود موحدة مثل:

```text
prepare(spec)
verifyReady(spec)
load(spec, runtimeOptions)
run(request)
stream(request)
cancel()
release()
capabilities()
```

Adapters مستقلة لـ:

- Kokoro LiteRT.
- Piper Sherpa-ONNX.
- ML Kit Translation.

لا يجب إجبار جميع المحركات على نفس أسلوب streaming أو نفس نوع timestamps؛ العقد الأساسي موحد والقدرات اختيارية.

### 4. Local Task Orchestrator

مسؤول عن lifecycle المهام الطويلة:

- تنزيل نموذج.
- تنزيل dependency.
- ترجمة ملف.
- تصدير نتيجة.
- benchmark اختياري.

يعتمد على WorkManager للمهام التي يجب أن تستمر بعد مغادرة التطبيق، ويترك القراءة والنطق التفاعلي في runtime/session controller.

### 5. Offline Capability Layer

يوفر حالة صريحة لكل قدرة:

```text
NEEDS_NETWORK
DOWNLOADING
READY_OFFLINE
RUNTIME_BLOCKED
CORRUPTED
```

ويربطها باللغة والنموذج والإصدار. هذا أفضل من استنتاج الجاهزية من عدة Flags متفرقة.

### 6. Persistence Layer

تظل Room وDataStore وfilesystem، لكن مع قواعد ملكية واضحة:

- Room: metadata، progress، results، user state.
- filesDir: immutable verified model packages ونتائج مهمة دائمة.
- cacheDir: cache قابل للحذف فقط.
- DataStore: requested user settings، مع runtime state منفصل في الذاكرة.

## O. خطة الانتقال والتنفيذ

### المرحلة 0 — بوابة السلامة والتوافق

الأولوية الأعلى:

1. مراجعة وإدماج إصلاح API 24 بعد نجاح CI، دون تغيير الميزات.
2. تشغيل build/test/lint في بيئة Android SDK مثبتة.
3. إضافة اختبار يثبت عدم فقد النموذج القديم عند فشل commit الجديد.
4. توثيق حالة `main` مقابل PRs المفتوحة.

**معيار الخروج:** `main` يبني، وminSdk 24 يمر، ولا يوجد API غير متوافق في المسارات الأساسية.

### المرحلة 1 — تثبيت Model & Resource Manager

1. فصل download state عن installed state.
2. إضافة unique dependency coordination لـeSpeak.
3. جعل install/upgrade قابلاً للrollback.
4. إضافة اختبار interruption عند كل مرحلة: download، hash، extract، commit.
5. تثبيت سياسة version migration للنماذج.

**المخاطر:** تغيير filesystem قد يؤثر في نماذج مثبتة؛ يجب دعم layout القديم أثناء الانتقال.

### المرحلة 2 — Offline Capability وML Kit lifecycle

1. تعريف حالة صريحة لكل اتجاه ترجمة.
2. تحديد ما إذا كان تنزيل ML Kit يجب أن ينتقل إلى WorkManager.
3. إضافة اختبار airplane mode بعد تجهيز الحزم.
4. إضافة اختبار أن كل feature يعطي رسالة واضحة عند غياب الحزمة.

### المرحلة 3 — Runtime Manager وAdapters

1. استخراج واجهة القدرات من `TtsEngine` الحالي.
2. إبقاء Kokoro وPiper كما هما داخلياً.
3. فصل requested accelerator عن applied accelerator.
4. إضافة load/unload stress tests.
5. اختبار GPU fail ثم CPU fallback ثم release.

### المرحلة 4 — Task Orchestrator

1. توحيد أسماء unique work وحالات retry.
2. منع `REPLACE` غير الضروري عند الضغط المتكرر.
3. إضافة task reconciliation موحد.
4. إبقاء الترجمة الجملية والتشغيل التفاعلي خارج Worker عندما لا يحتاجان استمرارية.

### المرحلة 5 — القياس على أجهزة حقيقية

اختيار ثلاثة أجهزة على الأقل:

- ARM64 منخفض الذاكرة.
- ARM64 متوسط.
- جهاز يدعم OpenCL جيداً.

قياس:

- زمن تحميل كل نموذج.
- TTFA.
- RTF.
- PSS قبل وبعد التحميل وأثناء synthesis.
- الحرارة والبطارية.
- جودة الصوت والـtimestamps.
- فشل/نجاح fallback.
- استئناف التنزيل.
- تشغيل airplane mode.

### المرحلة 6 — تحسينات UX دون تغيير عقد التشغيل

بعد ثبات الأساس فقط:

- توحيد loading/error/progress.
- إظهار الفرق بين جاهز للتنزيل وجاهز Offline.
- منع النقر المكرر على تفعيل/تنزيل.
- توضيح أن PDF غير مدعوم.
- تحسين حالات إعادة تشغيل التطبيق واستعادة آخر جلسة.

## P. معايير القبول

### البناء والتوافق

- `:app:testDebugUnitTest` ناجح في CI.
- `:app:lintDebug` ناجح.
- `:app:assembleDebug` ناجح.
- اختبار Robolectric أو instrumented لمسارات API 24.
- لا توجد استدعاءات API أعلى من minSdk دون guard أو desugaring مناسب.

### استقلال الإنترنت

بعد تنزيل نموذج TTS وحزم الترجمة:

- تشغيل القراءة الفرنسية والإنجليزية في airplane mode.
- تشغيل Kokoro وPiper في airplane mode.
- ترجمة جملة وفقرة وملف في airplane mode.
- تصدير النتائج في airplane mode.
- إثبات عدم وجود request شبكي أثناء هذه السيناريوهات عبر network monitor أو test harness.
- عند غياب الحزمة، تظهر حالة `NEEDS_DOWNLOAD` ولا يحدث crash.

### إدارة النماذج

- تنزيل أولي مع SHA-256 صحيح يسجل Installed.
- Hash خاطئ يحذف الجزء التالف ولا يلمس نموذجاً سليماً سابقاً.
- توقف التنزيل يستأنف دون إعادة bytes صحيحة.
- تنزيل نموذجين Piper بالتوازي لا يكرر أو يفسد eSpeak.
- ترقية فاشلة تحفظ النسخة السابقة أو تعيدها.
- manifest يرفض ملفاً معدلاً وملفاً غير مسجل.

### المحركات

- Kokoro GPU يعمل أو يسجل fallback واضحاً.
- فشل GPU قبل الصوت ينتقل إلى CPU مرة واحدة.
- Piper يرفض الإنجليزية برسالة واضحة.
- `release()` يحرر native resources ولا يترك model runtime محملاً.
- لا يُحمّل نموذجان صوتيان معاً.

### المهام

- ترجمة ملف طويلة تستأنف بعد إغلاق التطبيق.
- إعادة تشغيل الجهاز لا تعيد chunk مكتمل إلى pending.
- تغيير اللغة يزيد epoch ويمنع Worker القديم من الكتابة.
- إلغاء التنزيل أو الترجمة لا يترك حالة RUNNING دائمة.
- retry لا ينتج مهمة مكررة.

### الأداء

- لا يوجد Main-thread I/O في مسارات التشغيل والتحميل.
- يتم تحديد حدود ذاكرة واضحة لكل نموذج.
- تُقاس peak PSS وTTFA وRTF على الأجهزة المستهدفة.
- لا تتجاوز cache حدودها المعلنة.
- benchmark طويل لا يسبب OOM ضمن حدود المنتج.

## Q. ما لم يتمكن التدقيق من فحصه

1. لم أتمكن من تشغيل Gradle tests محلياً لأن Android SDK غير موجود في بيئة التدقيق.
2. لم أستخدم جهاز Android أو ADB؛ لذلك لم أتحقق من LiteRT الحقيقي أو Sherpa native أو AudioTrack أو OpenCL.
3. لم أتحقق من جودة الصوت أو صحة word timestamps على جهاز فعلي.
4. لم أتحقق من thermal throttling أو battery drain.
5. لم أختبر airplane mode أو traffic فعلياً.
6. لم أدمج PR #2 ولم أعتبر تعديلاته جزءاً من `main`.
7. لم أفحص أسرار GitHub Actions أو keystore الفعلية؛ راجعت workflow والتقارير العامة فقط.
8. لم أراجع النصوص القانونية الأصلية لكل نموذج؛ راجعت سجل التراخيص الموجود في `ModelCatalog` فقط.

## R. الخلاصة النهائية

المشروع يحتاج **تثبيتاً وتنظيماً تدريجياً للأساس التشغيلي**، لا إعادة بناء كاملة. توجد نواة مركزية حقيقية لإدارة TTS والنماذج، وRoom/WorkManager يقدمان أساساً جيداً للمهام والاستمرارية، كما أن فصل Kokoro وPiper وML Kit داخل محركات مستقلة قرار صحيح.

أول مرحلة تنفيذية مقترحة هي **بوابة السلامة والتوافق**: معالجة توافق API 24 في `main` عبر مراجعة PR #2، تشغيل CI فعلياً، ثم اختبار الذرية واستعادة النموذج. بعد ذلك فقط يبدأ توحيد Offline Capability وTask Orchestrator، ثم القياس على أجهزة ARM64. لا ينبغي إضافة محرك جديد أو مكتبة runtime جديدة قبل حسم هذه المراحل وقياس الاختناقات الفعلية.

## References

[1]: https://github.com/youns03/-coute "Écoute repository"
[2]: https://github.com/youns03/-coute/blob/main/README.md "Écoute README"
[3]: https://github.com/youns03/-coute/blob/main/app/src/main/java/com/example/EcouteApplication.kt "Application composition root"
[4]: https://github.com/youns03/-coute/blob/main/app/src/main/java/com/example/MainActivity.kt "MainActivity and Compose navigation"
[5]: https://github.com/youns03/-coute/blob/main/app/src/main/java/com/example/models/ModelStorageManager.kt "Model storage and integrity verification"
[6]: https://github.com/youns03/-coute/blob/main/app/src/main/java/com/example/models/ModelRepository.kt "Model repository and WorkManager integration"
[7]: https://github.com/youns03/-coute/blob/main/app/src/main/java/com/example/models/ModelDownloadWorker.kt "Model download worker"
[8]: https://github.com/youns03/-coute/blob/main/app/src/main/java/com/example/tts/TtsEngineManager.kt "TTS runtime manager"
[9]: https://github.com/youns03/-coute/blob/main/app/src/main/java/com/example/tts/KokoroLiteRtTtsEngine.kt "Kokoro LiteRT adapter"
[10]: https://github.com/youns03/-coute/blob/main/app/src/main/java/com/example/tts/PiperSherpaTtsEngine.kt "Piper Sherpa-ONNX adapter"
[11]: https://github.com/youns03/-coute/blob/main/app/src/main/java/com/example/translation/MlKitTranslationEngine.kt "ML Kit translation adapter"
[12]: https://github.com/youns03/-coute/blob/main/app/src/main/java/com/example/translation/FileTranslationWorker.kt "Background file translation worker"
[13]: https://github.com/youns03/-coute/blob/main/app/src/main/java/com/example/audio/AudioPlaybackController.kt "Audio playback controller"
[14]: https://github.com/youns03/-coute/blob/main/app/src/main/java/com/example/audio/PlaybackService.kt "Media playback service"
[15]: https://github.com/youns03/-coute/blob/main/app/src/main/java/com/example/data/AppDatabase.kt "Room database and migrations"
[16]: https://github.com/youns03/-coute/blob/main/app/src/test/java/com/example/PhaseFourArchitectureIntegrationTest.kt "Architecture integration tests"
[17]: https://github.com/youns03/-coute/pull/2 "Open API 24 compatibility pull request"
[18]: https://github.com/youns03/-coute/actions "GitHub Actions runs"


---

## تحديث لاحق للتدقيق — 2026-11-23

هذا التدقيق يوثق حالة المستودع وقت إجرائه، ولذلك تبقى ملاحظاته التاريخية كما هي. بعد ذلك اكتملت بوابة Foundation Stabilization:

- دُمج PR #2 **Fix model integrity paths for Android API 24** في `main`.
- Commit الدمج: `93d59197c7696fd516a65649ba2e3234048aa65f`.
- نجح GitHub Actions بعد الدمج على `main` في التشغيل [45234447152](https://github.com/youns03/-coute/actions/runs/45234447152).
- اجتازت مهام `:app:testDebugUnitTest` و`:app:lintDebug` و`:app:compileDebugKotlin`.
- شمل الإصلاح توافق مسارات integrity مع API 24/25، ومعاملات تثبيت النماذج واستعادتها، وقفل dependency المشتركة eSpeak-ng، واختبارات حالات التراجع والانقطاع.
- لم يُنشأ APK في هذه المرحلة، ولم تُجرَ اختبارات على جهاز Android فعلي؛ لذلك لا تزال اختبارات الانقطاع المادي والأداء والمحركات الأصلية على أجهزة ARM64 ضمن التحقق الميداني اللاحق.

وعليه، فإن توصية التدقيق القديمة بدمج PR #2 قد نُفذت بالفعل. أما المراحل المعمارية اللاحقة المذكورة في هذا التدقيق فلم تُنفذ ضمن هذا الإصلاح.
