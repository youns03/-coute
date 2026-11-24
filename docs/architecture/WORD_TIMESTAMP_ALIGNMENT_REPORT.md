# تقرير محاذاة توقيتات الكلمات

**المشروع:** Écoute  
**المرحلة:** مراجعة PR #3 وإصلاح مزامنة تتبع الكلمات  
**التاريخ:** 2026-11-24  
**PR:** [#3 — Redesign reader paragraphs, translation layout, and playback controls](https://github.com/youns03/-coute/pull/3)

## النتيجة المختصرة

تم التحقق من أن تحسينات شاشة القراءة والفقرات والترجمة والتحكم بالتشغيل موجودة فعلياً في رأس PR #3 المنشور، ثم عولج خلل حقيقي في مزامنة الكلمات. كان محرك النطق يعيد `WordTimestampRange.startChar/endChar` بالنسبة إلى `spokenText`، بينما كان `ReaderScreen` يطبق هذه المواضع مباشرة على `originalText`. عند توسيع اختصار أو رقم، تصبح المواضع غير صالحة أو تشير إلى كلمة مختلفة.

أضيفت طبقة محاذاة محافظة لا تغيّر النص المعروض ولا محركات النطق. تحتفظ الـcache بالتوقيتات الخام في إحداثيات `spokenText`، وتحوّلها إلى نطاقات `originalText` فقط قبل إبراز الكلمة أثناء التشغيل.

## التشخيص الفعلي

مسار التشغيل هو:

`playChapterFrom()` → `spokenText` → Kokoro/Piper → `WordTimestampRange` → `AudioPlaybackController` → حالة التتبع → `ReaderScreen`.

في النسخة السابقة كان التحقق في `AudioPlaybackController` يقارن `endChar` بطول `currentSentenceText`، وهو النص الأصلي المعروض. لكن التوقيتات الصادرة عن Kokoro قد تكون محسوبة على نص موسع، مثل:

- `Dr. 12` → `Doctor twelve`
- `M. Dupont a 12 ans.` → `Monsieur Dupont a douze ans.`

لذلك لم يكن تحسين لون الـhighlight كافياً؛ المطلوب تحويل فضاء الإحداثيات نفسه.

## التغييرات المنفذة

### 1. محاذاة مستقلة

أضيف الملف:

`app/src/main/java/com/example/domain/SpeechTimestampAligner.kt`

ويقوم بـ:

- استخراج tokens من `originalText` و`spokenText` مع دعم الحروف والأرقام والواصلات والفواصل العليا.
- تطبيع المقارنة دون تعديل النص الأصلي: حالة الأحرف، العلامات الصوتية، والفواصل داخل الكلمات.
- محاذاة monotonic تحفظ ترتيب الكلمات.
- إسناد عدة tokens منطوقة إلى token أصلي واحد عند التوسعة، مثل الرقم أو الاختصار.
- إعادة `startChar/endChar` إلى نطاق الكلمة في النص الأصلي، مع إبقاء `startSec/endSec` كما صدرا من المحرك.
- استخدام fallback رتيب عند عدم وجود تطابق نصي مباشر، بدلاً من إسقاط التوقيت بالكامل.

### 2. ربطها بمساري الصوت

تم تعديل:

`app/src/main/java/com/example/audio/AudioPlaybackController.kt`

لتطبيق المحاذاة في:

- البث الحي قبل تحديث الـhighlight.
- نهاية البث قبل drain الصوت.
- تشغيل الـcache قبل التتبع.

وتبقى التوقيتات الخام في ملفات الـcache، مما يحافظ على توافق الـcache القديم والجديد ويمنع ربط cache الصوت بنص عرض بعينه.

### 3. اختبارات حالات التوسعة

أضيف الملف:

`app/src/test/java/com/example/SpeechTimestampAlignerTest.kt`

ويغطي:

- توسعة اختصار ورقم في الفرنسية.
- توسعة اختصار ورقم في الإنجليزية.
- الحفاظ على النطاقات الدقيقة عندما يتطابق `originalText` و`spokenText`.

## commits المنشورة

- `f3d42a529bb8d82d1fdbdfb456eecc89f298836a` — `fix(reader): align speech timestamps with original text`
- `47a1322272b6e31abcff24857d460800c1496665` — `fix(reader): correct timestamp alignment token regex`

الرأس الحالي المنشور على فرع `feature/reader-experience-redesign` هو `47a1322`.

## التحقق والنتائج

### GitHub Actions

نجح التشغيل المرتبط بالرأس الحالي:

- [Android CI run 45377199041](https://github.com/youns03/-coute/actions/runs/45377199041)
- `Unit tests and Android lint`: ناجح
- شمل التشغيل:
  - `:app:testDebugUnitTest`
  - `:app:lintDebug`
  - `:app:compileDebugKotlin`

قبل التصحيح النهائي، فشل تشغيل وسيط لأن regex اختبار المحاذاة كان مكتوباً بـescaping مزدوج داخل Kotlin raw string. تم تحديد السبب من سجل CI وإصلاحه، ثم نجح التشغيل الكامل أعلاه.

### البيئة المحلية

تعذر تشغيل Gradle محلياً لأن Sandbox الحالية لا تحتوي Android SDK، وGradle أبلغ صراحة عن غياب `ANDROID_HOME` و`local.properties`. هذا قيد في البيئة المحلية فقط؛ GitHub Actions أعدّ Android SDK وشغّل الاختبارات والـlint والتجميع بنجاح.

## حدود المرحلة

- لم يتم دمج PR #3 في `main`.
- لم يتم إنشاء APK.
- لم يتم تغيير Kokoro LiteRT أو Piper/Sherpa-ONNX أو ML Kit.
- لم تتغير واجهة القراءة أو Navigation؛ التغيير محصور في تحويل إحداثيات التتبع قبل العرض.
- ما زال PR #3 مفتوحاً ومسودة، وهو جاهز للمراجعة التقنية بناءً على CI الناجح، وليس للدمج التلقائي.
