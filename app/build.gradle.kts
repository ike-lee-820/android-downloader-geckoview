plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.ksp)
}

// 一、GeckoView channel 与 version
// channel 固定 release（Stable）。version 默认为 latest.release：Gradle 在构建时读取
// https://maven.mozilla.org/maven2/org/mozilla/geckoview/<artifact>/maven-metadata.xml，
// 从 <versioning> 中取当前最高 Stable 版本，因此永远对齐 Mozilla 当前 Stable。
// 需要可复现构建时用 -Pgeckoview.version=<具体版本> 或 gradle.properties 钉住。
val geckoviewChannel: String = providers.gradleProperty("geckoview.channel").getOrElse("release")
val geckoviewVersion: String = providers.gradleProperty("geckoview.version")
    .orElse(providers.gradleProperty("geckoviewVersion"))
    .getOrElse("latest.release")
val geckoviewArtifact: String =
    if (geckoviewChannel == "release") "geckoview" else "geckoview-$geckoviewChannel"

// 二、Mozilla Maven 仓库（GeckoView 唯一来源，禁止 JCenter 与第三方镜像）
// google() 与 mavenCentral() 仅用于 AndroidX / Kotlin / OkHttp 等既有依赖。
repositories {
    google()
    mavenCentral()
    maven {
        url = uri("https://maven.mozilla.org/maven2")
        content {
            includeGroup("org.mozilla.geckoview")
        }
    }
}

android {
    namespace = "com.yuanbao.downloader"
    // 当前 Stable GeckoView (159.x) 的 AAR metadata 要求依赖方以 API 37 及以上编译；
    // 运行目标仍保持 targetSdk 34，最低支持 minSdk 26，均不随 compileSdk 改变。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yuanbao.downloader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 三、ABI 裁剪（1/2）：直接把 AAR 内的 .so 限定为 arm64-v8a 与 armeabi-v7a。
        // 完整多架构 GeckoView AAR 约 150MB - 200MB，过滤后单 ABI 约 50MB - 70MB。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 三、ABI 裁剪（2/2）：release 按 ABI 拆分 APK，不生成 universal APK。
    // debug 保持单一 APK，便于开发调试。
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any {
                it.contains("Release", ignoreCase = true)
            }
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            // 三、资源压缩与 R8
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 四、Java 17 兼容性
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    // 五、GeckoView（含 GeckoRuntime / GeckoSession / WebExtensionController）
    implementation("org.mozilla.geckoview:$geckoviewArtifact:$geckoviewVersion")

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// 便于在 CI 日志中确认实际解析到的 GeckoView 坐标
tasks.register("printGeckoViewCoordinate") {
    group = "verification"
    description = "输出当前构建使用的 GeckoView Maven 坐标"
    doLast {
        println("GeckoView coordinate: org.mozilla.geckoview:$geckoviewArtifact:$geckoviewVersion")
    }
}
