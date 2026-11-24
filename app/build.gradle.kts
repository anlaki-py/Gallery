import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android)
    id("gallery.fork-notice")
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use(keystoreProperties::load)
}

fun hasSigningVars(): Boolean {
    return providers.environmentVariable("KEY_ALIAS").orNull?.isNotBlank() == true
            && providers.environmentVariable("KEY_PASSWORD").orNull?.isNotBlank() == true
            && providers.environmentVariable("KEYSTORE_FILE").orNull?.isNotBlank() == true
            && providers.environmentVariable("KEYSTORE_PASSWORD").orNull?.isNotBlank() == true
}

val baseVersionCode = providers.gradleProperty("versionCode")
    .orElse(providers.gradleProperty("VERSION_CODE")).get().toInt()
val releaseVersionName = providers.gradleProperty("versionName")
    .orElse(providers.gradleProperty("VERSION_NAME")).get()
require(baseVersionCode in 1..2100000000) { "versionCode must be a valid Android version code" }
if (providers.gradleProperty("ciBuild").isPresent) {
    require(hasSigningVars()) { "CI release signing requires KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD" }
}
val supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val abiSplits = providers.gradleProperty("ABI_SPLITS").map(String::toBoolean).getOrElse(false)

val robolectricSdk by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val robolectricSdkDirectory = layout.buildDirectory.dir("robolectric-sdk")
val prepareRobolectricSdk by tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(robolectricSdkDirectory)
}

base {
    archivesName = "gallery-$baseVersionCode"
}

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = project.property("APPLICATION_ID").toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        versionName = releaseVersionName
        versionCode = baseVersionCode
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug-signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystorePropertiesFile.exists()) {
            register("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        } else if (hasSigningVars()) {
            register("release") {
                keyAlias = providers.environmentVariable("KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("KEY_PASSWORD").get()
                storeFile = file(providers.environmentVariable("KEYSTORE_FILE").get())
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").get()
            }
        } else {
            logger.warn("Warning: No signing config found. Build will be unsigned.")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1g"
            it.dependsOn(prepareRobolectricSdk)
            it.inputs.files(robolectricSdk).withPropertyName("robolectricSdk")
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", robolectricSdkDirectory.get().asFile.absolutePath)
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists() || hasSigningVars()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    flavorDimensions.add("licensing")
    productFlavors {
        register("foss")
        register("gplay")
    }

    splits {
        abi {
            isEnable = abiSplits
            reset()
            include(*supportedAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
    }

    compileOptions {
        val currentJavaVersionFromLibs =
            JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    namespace = project.property("APP_ID").toString()

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = rootProject.file("lint.xml")
    }

    packaging {
        resources {
            excludes += "META-INF/library_release.kotlin_module"
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release").withFlavor("licensing" to "foss")) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find {
                it.filterType == FilterConfiguration.FilterType.ABI
            }?.identifier ?: "universal"
            val unsigned = if (android.signingConfigs.findByName("release") == null) "-unsigned" else ""
            output.outputFileName.set("gallery-$abi-release$unsigned.apk")
        }
    }
}

detekt {
    baseline = file("detekt-baseline.xml")
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

dependencies {
    // All Robolectric tests use API 34. Resolve it through Gradle's cache, before the test JVM starts.
    add(robolectricSdk.name, "org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("com.google.mediapipe:tasks-retrieval:1.2.0")
    // Same explicit LiteRT-LM version used by the inspected AI Edge Gallery checkout.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.work:work-testing:2.11.1")
    implementation(libs.fossify.commons)
    implementation(libs.androidx.print)
    implementation(libs.android.image.cropper)
    implementation(libs.exif)
    implementation(libs.android.gif.drawable)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.sanselan)
    implementation(libs.androidphotofilters)
    implementation(libs.androidsvg.aar)
    implementation(libs.gestureviews)
    implementation(libs.subsamplingscaleimageview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.awebp)
    implementation(libs.apng)
    implementation(libs.avif)
    implementation(libs.avif.integration)
    implementation(libs.jxl.integration)
    implementation(libs.okio)
    implementation(libs.picasso) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
    }
    compileOnly(libs.okhttp)

    ksp(libs.glide.compiler)
    implementation(libs.zjupure.webpdecoder)

    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)
    detektPlugins(libs.compose.detekt)
}
