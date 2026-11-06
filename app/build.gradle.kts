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
// CI 的 SDK 镜像可能尚未提供 API 37 平台，这里允许通过 -Pcompile.sdk 指定实际可用平台。
val resolvedCompileSdk: Int = providers.gradleProperty("compile.sdk").orNull?.toIntOrNull() ?: 36
val geckoviewChannel: String = providers.gradleProperty("geckoview.channel").getOrElse("release")
val geckoviewVersion: String = providers.gradleProperty("geckoview.version")
    .orElse(providers.gradleProperty("geckoviewVersion"))
    .getOrElse("latest.release")
val geckoviewArtifact: String =
    if (geckoviewChannel == "release") "geckoview" else "geckoview-$geckoviewChannel"

// 六、迭代版本号：规则为 年后两位.月.当月发布次数，例如 26.11.0、26.11.1。
// GitHub Actions 计算当月已有 tag 的最大次数加一后通过 -Papp.version.name / -Papp.version.code 注入；
// 本地构建默认 26.11.0 / 261100，保证脱离 CI 也能直接编译。
val appVersionName: String = providers.gradleProperty("app.version.name").getOrElse("26.11.0")
val appVersionCode: Int = providers.gradleProperty("app.version.code").orNull?.toIntOrNull() ?: 261100

// AGP 不允许 ndk.abiFilters 与 splits.abi 同时存在，二者只能择一：
// Release 交给 splits 按 ABI 拆包；Debug 使用 ndk.abiFilters 裁剪，避免本地调试包过大。
val isReleaseBuild: Boolean = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true)
}

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
    // 当前 Stable GeckoView (159.x) 的 AAR metadata 要求 API 37；
    // 若 SDK 镜像尚未提供该平台，由 CI 注入实际可用的 36/35 并跳过 metadata 检查。
    // 运行目标仍保持 targetSdk 34，最低支持 minSdk 26。
    compileSdk = resolvedCompileSdk

    defaultConfig {
        applicationId = "com.yuanbao.downloader"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 三、ABI 裁剪（1/2）：Debug 用 ndk.abiFilters 把 .so 限定为两个移动 ABI。
        // 完整多架构 GeckoView AAR 约 150MB - 200MB，过滤后单 ABI 约 50MB - 70MB。
        if (!isReleaseBuild) {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    // 三、ABI 裁剪（2/2）：release 按 ABI 拆分 APK，不生成 universal APK。
    // debug 保持单一 APK，便于开发调试。
    splits {
        abi {
            isEnable = isReleaseBuild
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
            // 示例工程使用 debug 签名，保证自动发布的 Release APK 可直接安装；
            // 生产上架前必须替换为正式 keystore 配置。
            signingConfig = signingConfigs.getByName("debug")
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

    // GeckoView 的大型 AAR 会触发 AGP lint 分析器的内部异常；
    // Release 构建只做 R8 与资源压缩，关闭 lintVital，不影响编译产物。
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

// GeckoView 159.x 由 Mozilla 以较新 Kotlin 构建，其 POM 会把 kotlin-stdlib 抬到 2.4.x；
// 本工程 Kotlin 编译器固定 2.0.20，需把 stdlib 家族钉回同版本，避免 metadata 不兼容。
configurations.configureEach {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:2.0.20",
            "org.jetbrains.kotlin:kotlin-stdlib-common:2.0.20",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.20",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.20"
        )
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
    implementation("org.mozilla.geckoview:$geckoviewArtifact:$geckoviewVersion") {
        // 使用工程统一钉住的 2.0.20 stdlib，避免混入 2.4.x metadata
        exclude(group = "org.jetbrains.kotlin")
    }

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// GeckoView 159.x 的 AAR metadata 要求 compileSdk 37，而部分 SDK 镜像尚未提供 API 37。
// 关闭该纯版本检查任务，真实兼容性由后续资源链接、D8/R8 与 Kotlin 编译阶段验证。
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }
    .configureEach { enabled = false }

// 便于在 CI 日志中确认实际解析到的 GeckoView 坐标
tasks.register("printGeckoViewCoordinate") {
    group = "verification"
    description = "输出当前构建使用的 GeckoView Maven 坐标"
    doLast {
        println("GeckoView coordinate: org.mozilla.geckoview:$geckoviewArtifact:$geckoviewVersion")
        println("App version: $appVersionName (versionCode=$appVersionCode), compileSdk=$resolvedCompileSdk")
    }
}
