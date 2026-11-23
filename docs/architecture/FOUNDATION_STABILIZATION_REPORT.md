# تقرير Foundation Stabilization & Model Integrity

**التاريخ:** 2026-11-23  
**المستودع:** [youns03/-coute](https://github.com/youns03/-coute)  
**الفرع الأساسي المفحوص:** `main` عند `502a71f`  
**فرع العمل:** `fix/model-storage-api24-stabilization` المبني على رأس PR #2 عند `4af8871e`  
**نوع العمل:** تنفيذ إصلاحات واختبارات فقط. لم يُدمج Pull Request ولم يُحذف فرع ولم يُنشأ commit.

## 1. الحالة الأصلية والتحقق الخارجي

تم التحقق من الحالة قبل التعديل عبر Git وGitHub API العام. كان `main` عند الالتزام `502a71f`، وكان PR #2 مفتوحاً ومسودة بعنوان **Fix model integrity paths for Android API 24**. رأس PR يحتوي إصلاحاً أولياً لاستبدال `java.nio.file.Path` بمسارات `File`، لكنه كان يتضمن أيضاً تغييرات CI واختبار API 24 واحداً فقط، ولا يعالج دورة الاستبدال الآمن أو الاستعادة.

آخر تشغيلات Android CI المتاحة لـPR #2 كانت مكتملة بحالة فشل في خطوة Gradle المجمعة. لم أعتبرها دليلاً على نجاح التغييرات الحالية، ولم أعدّل حالة PR أو أدمج شيئاً.

إعدادات الأدوات الفعلية التي تمت مراجعتها هي Java 21 وAGP `9.1.1` وGradle Wrapper `9.3.1` وRobolectric `4.16.1`، مع `minSdk = 24` و`targetSdk = 36`. لم تتم إضافة dependency جديدة ولم تتغير إصدارات الأدوات.

## 2. التغييرات المنفذة

### توافق API 24 و25

تم الإبقاء على الإصلاح الموجود في PR #2 لمسارات integrity، مع تقويته في `ModelStorageManager.kt`:

- أزيل الاعتماد المستهدف على `File.toPath()` و`Path.relativize()` من مسارات إنشاء والتحقق من manifest.
- أصبح بناء المسار النسبي معتمداً على `canonicalPath` مع اشتراط بقاء الملف تحت جذر النموذج.
- يرفض parser الخاص بالmanifest المسارات المطلقة، وفواصل Windows، ومكونات `..`، والمكونات الفارغة.
- يُعاد حساب المسار canonical للهدف ويُقارن بالمسار المسجل لمنع الخروج من الجذر.
- أضيف رفض لمعرّفات النماذج التي تحتوي فواصل مسار.
- لم تتغير صيغة `.integrity.sha256` أو بنية مجلدات النماذج الحالية.

### Commit آمن وتراجع

في `ModelStorageManager.installVerifiedPackage()` أصبح الترتيب:

1. فحص وجود الحزمة وحجمها وSHA-256 قبل لمس النسخة المثبتة.
2. نسخ أو فك الحزمة في `staging_<modelId>` مستقل.
3. فحص البنية وإنشاء integrity manifest داخل staging.
4. نقل النسخة القديمة إلى `backup_<modelId>` بدلاً من حذفها مباشرة.
5. اعتماد staging عبر rename، مع copy fallback عند الحاجة.
6. إعادة فحص النسخة النهائية وmanifest بعد الاعتماد.
7. إبقاء النسخة السابقة حتى نجاح الفحص النهائي.
8. عند الفشل حذف النسخة الجزئية واستعادة backup.
9. حذف backup والملف المؤقت فقط بعد نجاح commit.

أضيفت علامة `commit_<modelId>` حتى يستطيع التشغيل التالي التمييز بين استبدال انقطع أثناء التنفيذ وبين commit اكتمل وبقيت منه ملفات تنظيف. أضيفت `recoverPendingInstalls()` وتُستدعى قبل مصالحة Room في `ModelRepository.reconcileStorageOnStartup()`.

هذه العملية لا تدّعي أن `renameTo()` ذري في جميع أنظمة الملفات. التصميم يستخدم backup وعلامة commit واستعادة startup، ويعامل rename كعملية قد تفشل أو تنقطع.

### dependency المشتركة eSpeak

في `ModelDownloadWorker.kt` أصبحت مرحلة تنزيل وتثبيت `espeak-ng-data` محمية بقفل `Mutex` خاص بالمورد المشترك. الفحص يُعاد داخل القفل، لذلك لا يستطيع تنزيلان متوازيان تثبيت dependency نفسها في الوقت نفسه، بينما تبقى تنزيلات النماذج المستقلة خارج القفل.

### CI

تم تعديل `.github/workflows/android-ci.yml` لتشغيل المهام المطلوبة كلها:

```text
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug
```

لم تُخفف الاختبارات ولم تُضف شروط نجاح شكلية.

## 3. الاختبارات المضافة

تمت إضافة اختبارات إلى `ModelStorageAndDatabaseTest.kt` تغطي:

- تثبيت نموذج eSpeak والتحقق من manifest تحت `@Config(sdk = [24])`.
- رفض مسار manifest من نوع `../outside`.
- تثبيت نسخة قديمة ثم محاولة تحديث غير صالح، مع التأكد من بقاء النسخة القديمة سليمة.
- استعادة نسخة backup عند غياب المجلد النهائي مع وجود staging.
- تسلسل عمليات تثبيت eSpeak المتوازية عبر القفل المشترك.

الاختبارات الموجودة في PR #2 بقيت محفوظة، بما فيها اختبار Robolectric المخصص لـAPI 24.

## 4. نتائج التشغيل الفعلية

تم تشغيل المهام الثلاث محلياً، لكن البيئة لا تحتوي Android SDK ولا `javac` ولا `ANDROID_HOME` ولا `local.properties`:

- `./gradlew --no-daemon --console=plain :app:testDebugUnitTest` — **لم يصل إلى تنفيذ الاختبارات**؛ توقف بسبب `SDK location not found`.
- `./gradlew --no-daemon --console=plain :app:lintDebug` — **لم يصل إلى lint**؛ توقف بسبب `SDK location not found`.
- `./gradlew --no-daemon --console=plain :app:assembleDebug` — **لم يصل إلى البناء**؛ تعذر إنشاء Java compiler لأن البيئة لا توفر `javac`.

لذلك لا توجد نتيجة نجاح محلية يمكن الادعاء بها. تم تنفيذ مراجعة `git diff --check` بنجاح، كما تم فحص المسارات المستهدفة ولم يعد فيها `toPath()` أو `relativize()`.

لم يُشغّل CI جديد لهذه التغييرات لأن الفرع لم يُدفع ولم تُنشأ صلاحية أو عملية GitHub خارجية. يلزم تشغيل GitHub Actions بعد نشر الفرع أو فتح تحديث PR، ثم مراجعة السجل الكامل قبل اعتبار المرحلة ناجحة.

## 5. حالة البيانات والنماذج

لم تُحذف أي نماذج مستخدم أو بيانات Room. التعديلات لا تغير `minSdk` أو `targetSdk` أو Gradle dependencies أو صيغة manifest أو بنية `filesDir/models`. ملفات `backup_*` و`staging_*` و`commit_*` مؤقتة داخل `cacheDir` ومخصصة للاستعادة والتنظيف.

## 6. المشكلات غير المحسومة

لا يمكن من بيئة Sandbox الحالية إثبات:

- نجاح Robolectric فعلياً على API 24.
- نجاح Java/Kotlin compilation بعد التعديلات.
- نجاح lint وassembleDebug.
- محاكاة انقطاع الطاقة أو قتل العملية أثناء copy فعلياً على Android.
- ضمانات filesystem الخاصة بكل أجهزة Android المستهدفة.
- اختبار تنزيل HTTP متوازٍ كامل لـPiper؛ الاختبار المضاف يثبت تسلسل القفل المشترك، بينما اختبار الجهاز/الشبكة يحتاج بيئة Android وWorker فعلية.

كما أن PR #2 لا يزال مفتوحاً ومسودة، ولم يتغير وضعه.

## 7. قرار الانتقال

هذه المرحلة **ليست جاهزة بعد للاعتماد النهائي أو الانتقال إلى المرحلة المعمارية التالية**، لأن شرط القبول الأساسي هو نجاح الاختبارات وLint وassembleDebug فعلياً، وهو غير قابل للتحقق في البيئة الحالية.

الخطوة التالية الصحيحة هي تشغيل الفرع في CI أو على جهاز تطوير يحتوي Android SDK وJDK كامل، ثم معالجة أي أخطاء compilation أو test أو lint، ومراجعة diff وسجلات التشغيل. بعد نجاح ذلك يمكن مراجعة PR #2 واعتماد التغييرات كمرحلة مستقلة، دون بدء إعادة تنظيم المعمارية ضمن هذه المهمة.

## References

[1]: https://github.com/youns03/-coute "Écoute repository"
[2]: https://github.com/youns03/-coute/pull/2 "PR #2 API 24 model integrity paths"
[3]: https://github.com/youns03/-coute/actions "Écoute GitHub Actions"
