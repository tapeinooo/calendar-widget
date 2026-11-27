plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版の番号は GitHub Actions の実行番号から自動で決める（共通ルール）。
// 手で番号を付けていた頃（v1.26・versionCode 27）より大きくするため、gradle.properties の versionOffset・versionMajor を使う
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
val versionOffset = (project.findProperty("versionOffset") as String?)?.toIntOrNull() ?: 0
val versionMajor = (project.findProperty("versionMajor") as String?)?.toIntOrNull() ?: 1
val appVersionName = "$versionMajor.0.$buildNumber"

// GitHub Actions がリリースのタグ（v＋versionName）を作るのに使う
tasks.register("printVersionName") { doLast { println(appVersionName) } }

android {
    namespace = "com.example.calendarwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.calendarwidget"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber + versionOffset
        // GitHub のリリースのタグは「v」＋ versionName（アプリの更新はタグで新しい版を見分ける）
        versionName = appVersionName
    }

    // 署名：前から使っている Mac の debug 鍵をリポジトリに置いて使う（共通ルール）。
    // 鍵が変わると、上書きで更新できないうえ、Google タスクの読み取り（鍵の指紋で登録してある）も止まる
    signingConfigs {
        create("release") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // 使っていない部品を削って小さくする
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Google タスクの読み取り許可（Authorization API）
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    testImplementation("junit:junit:4.13.2")
}
