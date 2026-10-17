import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
}

extensions.configure<ApplicationExtension> {
    namespace = "io.relimus.zflow.compat"

    // libxposed 102 的 AAR 声明 minCompileSdk=37，低于此值 Gradle 会直接报错
    compileSdk = 37

    defaultConfig {
        applicationId = "io.relimus.zflow.compat"
        // 补丁依赖 API 26+ 的窗口 / Insets 行为
        minSdk = 26
        targetSdk = 37

        // CI 通过 -PcompatVersionCode / -PcompatVersionName 注入构建序号
        versionCode = (providers.gradleProperty("compatVersionCode").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("compatVersionName").orNull ?: "1.0.0"
    }

    // AGP 9 默认关闭 BuildConfig，设置页要用到 APPLICATION_ID / VERSION_NAME，这里显式打开
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // 关掉混淆：模块很小，而入口类一旦被改名/裁剪，LSPosed 就加载不到
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // 交给 AGP 9 内置的 Kotlin 支持对齐 jvmTarget，避免手写 Kotlin DSL 的版本差异
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.annotation)

    // 现代 API 编译期依赖；远程偏好（设置读写）由 service 库提供
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
}
