# Écoute — قراءة الكتب الفرنسية والاستماع العصبي والترجمة المحلية دون إنترنت

تطبيق Android أصلي (Kotlin + Jetpack Compose + Material 3) لتعلّم اللغة الفرنسية (ودعم قراءة وترجمة الإنجليزية) للمتحدثين باللغة العربية، مع تشغيل نماذج النطق العصبي والترجمة على الجهاز بالكامل دون الحاجة إلى الإنترنت بعد تنزيل الحزم.

## المتطلبات وإعادة البناء من نسخة نظيفة (Clean Clone)

### 1. Gradle Wrapper وبيئة البناء
- يحتوي المستودع على Gradle Wrapper كامل (`gradlew` و`gradlew.bat` و`gradle/wrapper/gradle-wrapper.jar`) لإصدار **Gradle 9.3.1**.
- يتطلب البناء **JDK 17+** و**Android SDK (compileSdk 36, minSdk 24, targetSdk 36)**.

### 2. اعتماد Sherpa-ONNX (`app/libs/sherpa-onnx-2.0.2.aar`)
- يضيف ملف `app/build.gradle.kts` مهمة `ensureSherpaAar` مرتبطة بمهام ما قبل البناء (`preBuild` / `preDebugBuild` / `preReleaseBuild`).
- عند غياب الملف `app/libs/sherpa-onnx-2.0.2.aar` في نسخة GitHub نظيفة، يقوم Gradle تلقائياً بتنزيله من الإصدار الرسمي والتحقق من بصمة `SHA-256`:
  - المصدر: `https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.2/sherpa-onnx-2.0.2.aar`
  - الحجم: `52,321,252` بايت
  - البصمة (`SHA-256`): `6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e`
- إذا كان الملف موجوداً محلياً على القرص، يتجاوز Gradle التنزيل فوراً دون اتصال شبكي.

### 3. إعداد التوقيع (Debug وRelease) خارج المستودع
ملف `debug.keystore` مستثنى عمداً من Git لأسباب أمنية. قبل تشغيل البناء المحلي لأول مرة خارج بيئة AI Studio، أنشئ `debug.keystore` في جذر المشروع بالأمر التالي:

```bash
keytool -genkeypair -v \
  -keystore debug.keystore \
  -storepass android \
  -alias androiddebugkey \
  -keypass android \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

أما لبناء Release محلياً، فعيّن متغيرات البيئة `ANDROID_KEYSTORE_PATH` و`ANDROID_KEYSTORE_PASSWORD` و`ANDROID_KEY_ALIAS` و`ANDROID_KEY_PASSWORD`. لا توجد قيم افتراضية للمفتاح أو كلمات مروره؛ يجب توفيرها خارج المستودع.

### 4. إصدار GitHub Release

الـworkflow `.github/workflows/android-release.yml` يعمل عند دفع tag يبدأ بـ`v`، ويتحقق من تنسيق SemVer مثل `v1.0.0`، ثم يبني APK موقّعة ويرفقها بـGitHub Release مع ملاحظات إصدار تلقائية. يأخذ `versionName` من الـtag بعد حذف `v`، ويضبط `versionCode` إلى `100000 + GITHUB_RUN_NUMBER`، لذلك يبدأ فوق `versionCode` الحالي (1) ويزداد مع كل تشغيل إصدار جديد.

من صفحة المستودع افتح **Settings → Secrets and variables → Actions** وأضف هذه الأسرار الأربعة:

- `ANDROID_KEYSTORE_BASE64`: محتوى ملف keystore بعد ترميزه إلى Base64.
- `ANDROID_KEYSTORE_PASSWORD`: كلمة مرور ملف keystore.
- `ANDROID_KEY_ALIAS`: اسم المفتاح/alias داخل keystore.
- `ANDROID_KEY_PASSWORD`: كلمة مرور المفتاح.

يُنشأ `ANDROID_KEYSTORE_PATH` مؤقتاً بواسطة الـworkflow على runner، ولا يلزم إضافته كـSecret. لإنشاء مفتاح Release جديد باستخدام JDK، شغّل الأمر التالي؛ سيطلب `keytool` كلمات المرور تفاعلياً. استخدم alias `ecoute-upload` أو غيّره، مع إدخال القيمة المطابقة في `ANDROID_KEY_ALIAS`:

```bash
keytool -genkeypair -v \
  -keystore ecoute-release.jks \
  -storetype JKS \
  -alias ecoute-upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

حوّل الملف إلى Base64 في جذر المشروع ثم انسخ محتواه إلى السر `ANDROID_KEYSTORE_BASE64`:

```bash
base64 -w 0 ecoute-release.jks > ecoute-release.base64
```

على macOS استخدم `base64 < ecoute-release.jks | tr -d '\n' > ecoute-release.base64`، وفي PowerShell استخدم `[Convert]::ToBase64String([IO.File]::ReadAllBytes("ecoute-release.jks")) | Set-Content -NoNewline ecoute-release.base64`. ملفا keystore وBase64 مستثنيان من Git؛ لا ترفعهما للمستودع.

بعد حفظ الأسرار والتأكد من وصول تغييرات الـworkflow إلى `main`، ادفع tag الإصدار هكذا:

```bash
git switch main
git pull --ff-only origin main
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin v1.0.0
```

**مهم لاستبدال نسخة مثبتة:** يجب أن تكون كل تحديثات Android موقعة بالمفتاح الخاص نفسه. ملف APK السابق الذي بُني محلياً بتوقيع Debug لن يقبل التحديث فوقه إذا أنشأت الآن مفتاح Release جديداً؛ Android سيرفض التوقيع المختلف. يلزم استخدام keystore الأصلي الذي وقّع النسخة المثبتة، وإلا فسيتطلب الانتقال إلى المفتاح الجديد إزالة النسخة القديمة أولاً، وقد يؤدي ذلك إلى فقد بيانات التطبيق.

### 5. أوامر التحقق والبناء

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

## التحقق في بيئة التطوير مقابل الجهاز الفعلي
- **تم التحقق منها في بيئة البناء (JVM + Robolectric):**
  - ترجمة Kotlin/Compose وتجميع الموارد بدون أخطاء.
  - اختبارات الوحدة والمنطق في `app/src/test/java/com/example/` (تقطيع النصوص والترجمة، الاستيراد، Room v2 والترحيل، فحص المساحة، رفض ملفات SHA-256 التالفة وحذف الملف المؤقت).
- **يجب اختبارها على جهاز Android فعلي (ARM64) لعدم توفر محاكي فعلي أو ADB داخل حاوية البناء:**
  - جودة النطق الفعلي لـ Kokoro-82M (`ff_siwis`) وPiper (`siwis` / `tom`) على معالج الهاتف وقياس TTFA وRTF والذاكرة والحرارة.
  - تنزيل النماذج الكبيرة واستئناف HTTP 206 على شبكات حقيقية ووضع الطيران بعد التثبيت.
  - مسار التراجع من GPU إلى CPU على أجهزة OpenCL مختلفة.
  - تنزيل حزم ML Kit On-Device Translation (`fr -> ar`, `en -> ar`) وتشغيل خدمة الترجمة في الخلفية والإشعارات.
