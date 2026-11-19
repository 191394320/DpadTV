import java.util.Properties

plugins {
    id("com.android.application")
}

// 本机私有默认值（local.properties 不入库）。开源仓库里不含任何真实地址或密码。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.dpadtv.client"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dpadtv.client"
        // 19 = Android 4.4。内核已从 Media3（下限 23）换成 ExoPlayer 2.19.1（下限 16）。
        minSdk = 19
        // minSdk<21 走不了 native multidex（DexingImpl.canRunNativeMultiDex 只在 >=21 为真），
        // 不开这个开关，D8 只出一个 classes.dex，方法数直接撞 64K 上限。
        // 这个属性在 AGP 9 没被删，只是从通用的 BaseFlavor 挪到了 app 专属的
        // ApplicationVariantDimension 上（defaultConfig 的实际类型是 ApplicationDefaultConfig），
        // 只翻 BaseFlavor/ProductFlavor 会误判成"已删除"。
        // 配套的还有 androidx.multidex 依赖 + App 继承 MultiDexApplication，见 App.kt。
        multiDexEnabled = true
        targetSdk = 37
        versionCode = 112
        // 后缀 m19 = minSdk 19：装到盒子上用「关于」里的版本号就能分清是老包还是这份 4.4 构建
        versionName = "1.3.9-m19"
    }

    // release 签名从 local.properties 读（keystore 与密码都不入库）；
    // 没配置时退回未签名构建，不阻塞流水线
    val ksPath = localProps.getProperty("dpadtv.keystore")
    val ksStorePass = localProps.getProperty("dpadtv.storePassword")
    val ksKeyAlias = localProps.getProperty("dpadtv.keyAlias")
    val ksKeyPass = localProps.getProperty("dpadtv.keyPassword")
    val hasSigning = !ksPath.isNullOrBlank() && !ksStorePass.isNullOrBlank() &&
        !ksKeyAlias.isNullOrBlank() && !ksKeyPass.isNullOrBlank() &&
        rootProject.file(ksPath).exists()

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(ksPath)
                storePassword = ksStorePass
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    // 加 -Pdpadtv.release=true 构建就不带"测试版"字样；平时构建默认是测试版
    defaultConfig {
        buildConfigField(
            "boolean",
            "TEST_BUILD",
            if (project.findProperty("dpadtv.release") == "true") "false" else "true",
        )
    }

    defaultConfig {
        buildConfigField("String", "DEFAULT_URL", "\"${localProps.getProperty("dpadtv.defaultUrl") ?: ""}\"")
        buildConfigField("String", "DEFAULT_PASSWORD", "\"${localProps.getProperty("dpadtv.defaultPassword") ?: ""}\"")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // 原生播放：ExoPlayer 对 HLS 的容错远好于 MediaPlayer，H.265 取决于 SoC 硬解。
    // 停在 2.19.1（EP2 最后一版）是故意的：Media3 从 1.9.0 起要求 minSdk 23，
    // 而 4.4（API 19）只有 EP2 支持；4.4 的 SoC 也没有 HEVC 硬解（那是 API 21 才进的框架）。
    implementation("com.google.android.exoplayer:exoplayer-core:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")
    // minSdk < 21 必须用 legacy multidex，否则主 dex 超 64K 直接构建失败
    implementation("androidx.multidex:multidex:2.0.1")
    // 二维码生成（纯 Java，仅 core，不含 Android 集成包）
    implementation("com.google.zxing:core:3.5.3")
}
