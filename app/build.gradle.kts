plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.moneytask.ledger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moneytask.ledger"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // 发布签名（密钥详见 app/keystore/README.txt，务必自行备份；keystore 不入库）。
        create("release") {
            storeFile = file("keystore/moneytask-release.jks")
            storePassword = "Moneytask@2026Rel"
            keyAlias = "moneytask"
            keyPassword = "Moneytask@2026Rel"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true        // R8 精简代码
            isShrinkResources = true      // 精简资源
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        // 把导出的 Room schema 打进 androidTest assets，供 MigrationTestHelper 在真机/CI 升级回归读取。
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    // 导出 Room schema 到 app/schemas，供 MigrationTestHelper 做升级回归。
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // 核心逻辑（纯 JVM 模块）
    implementation(project(":ledger-core"))

    // AndroidX 基础
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Room（SQLite）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Room 迁移回归测试（androidTest，需真机/模拟器执行）
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
