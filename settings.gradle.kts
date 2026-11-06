pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// 仓库在模块级 app/build.gradle.kts 中声明，其中 Mozilla Maven 是 GeckoView 的唯一来源。
// 这里不再使用 FAIL_ON_PROJECT_REPOS，避免与模块级 repositories 块冲突。

rootProject.name = "AndroidDownloader"
include(":app")
