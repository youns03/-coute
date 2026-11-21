import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.io.File
import java.net.URI
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

val ciVersionCode =
  System.getenv("ANDROID_VERSION_CODE")?.let { value ->
    value.toIntOrNull()
      ?: throw GradleException("ANDROID_VERSION_CODE must be a valid integer")
  } ?: 1
if (ciVersionCode !in 1..2_100_000_000) {
  throw GradleException("ANDROID_VERSION_CODE must be between 1 and 2100000000")
}
val ciVersionName = System.getenv("ANDROID_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0"
val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val releaseSigningValues = listOf(
  "ANDROID_KEYSTORE_PATH" to releaseKeystorePath,
  "ANDROID_KEYSTORE_PASSWORD" to releaseStorePassword,
  "ANDROID_KEY_ALIAS" to releaseKeyAlias,
  "ANDROID_KEY_PASSWORD" to releaseKeyPassword,
)
val releaseBuildRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (releaseBuildRequested) {
  val missingSigningValues = releaseSigningValues.filter { it.second.isNullOrBlank() }.map { it.first }
  if (missingSigningValues.isNotEmpty()) {
    throw GradleException(
      "Release signing is missing required environment variables: ${missingSigningValues.joinToString()}",
    )
  }
  if (!File(requireNotNull(releaseKeystorePath)).isFile) {
    throw GradleException("The file configured by ANDROID_KEYSTORE_PATH does not exist")
  }
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.ecoute.kxmprq"
    minSdk = 24
    targetSdk = 36
    versionCode = ciVersionCode
    versionName = ciVersionName

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      storeFile = releaseKeystorePath?.let { file(it) }
      storePassword = releaseStorePassword
      keyAlias = releaseKeyAlias
      keyPassword = releaseKeyPassword
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = true
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  val splitApkByAbi = project.findProperty("splitApkByAbi")?.toString()?.toBoolean() == true
  val compressReleaseJni = project.findProperty("compressReleaseJni")?.toString()?.toBoolean() ?: true
  if (splitApkByAbi) {
    splits {
      abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        isUniversalApk = false
      }
    }
  }
  if (compressReleaseJni) {
    packaging {
      jniLibs {
        // Compress large C++ inference shared libraries inside release APK; AAB serves per-ABI slices on Play
        useLegacyPackaging = true
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Clean-clone self-healing dependency fetch: on fresh GitHub clones where app/libs/sherpa-onnx-2.0.2.aar
// is absent, Gradle automatically fetches the official v2.0.2 AAR and verifies its SHA-256 before compiling.
// When app/libs/sherpa-onnx-2.0.2.aar is already present on disk, this task is completely skipped.
abstract class DownloadSherpaAarTask : DefaultTask() {
  @get:Input abstract val downloadUrl: Property<String>
  @get:Input abstract val expectedSha256: Property<String>
  @get:OutputFile abstract val outputFile: RegularFileProperty

  @TaskAction
  fun fetchIfMissing() {
    val targetFile = outputFile.get().asFile
    if (targetFile.exists()) return
    logger.lifecycle("Downloading Sherpa-ONNX v2.0.2 AAR from official GitHub release...")
    targetFile.parentFile.mkdirs()
    val partFile = File("${targetFile.absolutePath}.part")
    if (partFile.exists()) partFile.delete()
    URI.create(downloadUrl.get()).toURL().openStream().use { input ->
      partFile.outputStream().use { output -> input.copyTo(output) }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    partFile.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
      }
    }
    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
    val expected = expectedSha256.get()
    if (!actualSha.equals(expected, ignoreCase = true)) {
      partFile.delete()
      throw GradleException(
        "Sherpa-ONNX AAR SHA-256 mismatch! Expected $expected, got $actualSha"
      )
    }
    if (!partFile.renameTo(targetFile)) {
      partFile.copyTo(targetFile, overwrite = true)
      partFile.delete()
    }
  }
}

val sherpaAarFile = layout.projectDirectory.file("libs/sherpa-onnx-2.0.2.aar")
val ensureSherpaAar =
  tasks.register<DownloadSherpaAarTask>("ensureSherpaAar") {
    downloadUrl.set(
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.2/sherpa-onnx-2.0.2.aar"
    )
    expectedSha256.set("6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e")
    outputFile.set(sherpaAarFile)
    onlyIf { !outputFile.get().asFile.exists() }
  }

tasks.matching {
  it.name == "preBuild" || it.name == "preDebugBuild" || it.name == "preReleaseBuild"
}.configureEach { dependsOn(ensureSherpaAar) }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material3.window.size)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.session)
  implementation(libs.mediapipe.tasks.audio)
  implementation(files("libs/sherpa-onnx-2.0.2.aar"))
  implementation(libs.mlkit.translate)
  implementation(libs.commons.compress)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.firestore)
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.kotlinx.serialization.json)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
