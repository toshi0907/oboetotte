plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 設定画面に「現在のビルド」として表示するためのgitコミットSHA(新旧判定には使わない。
// 判定には下のresolveBuildNumber()を使う)。GitHub Actions上のビルドでは環境変数
// GITHUB_SHAが自動的に設定されるためそれを使い、それが無いローカル/その他の環境では
// `git rev-parse HEAD`にフォールバックする。いずれも取得できない場合は"unknown"とする。
fun resolveGitCommitSha(): String {
    System.getenv("GITHUB_SHA")?.takeIf { it.isNotBlank() }?.let { return it }
    return try {
        val process = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && output.isNotBlank()) output else "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}

// アプリ内アップデート機能が「新しいビルドかどうか」を判定するための単調増加する番号。
// コミットSHAには順序が無く新旧を比較できないため、GitHub Actions上のビルドでは
// 実行のたびに増加するGITHUB_RUN_NUMBER(.github/workflows/android-build.ymlの
// リリース本文にも同じ値を"ビルド番号: "として埋め込む)を使う。ローカルビルド等
// GITHUB_RUN_NUMBERが無い環境では0とし、AppUpdateChecker側は0以下を「判定不能」として扱う。
fun resolveBuildNumber(): Int =
    System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

android {
    namespace = "com.toshi0907.oboetotte"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.toshi0907.oboetotte"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GIT_COMMIT_SHA", "\"${resolveGitCommitSha()}\"")
        buildConfigField("int", "BUILD_NUMBER", resolveBuildNumber().toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
