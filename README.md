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

أما لبناء Release، فعيّن متغيرات البيئة التالية (أو أسرار CI):
- `KEYSTORE_PATH`: المسار إلى ملف upload keystore (الافتراضي `${rootDir}/my-upload-key.jks`)
- `STORE_PASSWORD`: كلمة مرور keystore
- `KEY_PASSWORD`: كلمة مرور المفتاح (`keyAlias = "upload"`)

### 4. أوامر التحقق والبناء

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
