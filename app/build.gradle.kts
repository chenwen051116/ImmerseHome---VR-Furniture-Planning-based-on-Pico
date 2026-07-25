import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProps =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

fun localProp(key: String, default: String): String {
    val value = (localProps.getProperty(key) ?: default).replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$value\""
}

android {
    namespace = "com.example.testfull"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.testfull"
        minSdk = 35
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add("arm64-v8a") }

        // AI Arrange feature: OpenAI-compatible relay settings from local.properties.
        buildConfigField("String", "AI_API_BASE", localProp("ai.api.base", "https://api.openai-next.com/v1"))
        buildConfigField("String", "AI_API_KEY", localProp("ai.api.key", ""))
        buildConfigField("String", "AI_API_MODEL", localProp("ai.api.model", "gpt-4o-mini"))
        // Comma-separated list of models offered in the Arrange panel's model selector.
        // Default = top 8 OpenAI performance models by BenchLM composite score (July 2026),
        // using the exact model ids verified against the api.openai-next.com /v1/models list:
        // gpt-5.2-pro and gpt-5.3-codex are not on the relay, so we use gpt-5.2 and
        // gpt-5.3-codex-spark (the relay's variants) instead.
        // Edit ai.api.models in local.properties to match what the relay actually supports;
        // the app rebuilds the chip row from this list at runtime.
        buildConfigField("String", "AI_API_MODELS", localProp("ai.api.models", "gpt-5.6-sol,gpt-5.4,gpt-5.5,gpt-5.6-terra,gpt-5.6-luna,gpt-5.2,gpt-5.4-nano,gpt-5.3-codex-spark"))
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

    androidResources {
        noCompress += listOf(".bundle")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.bom))
    implementation(libs.core)
    implementation(libs.platform)
    implementation(libs.foundation)
    implementation(libs.design)
    implementation(libs.sense)
    implementation(libs.tracking)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(project(":editor-asset"))
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
}

configurations.all {
    resolutionStrategy {
        exclude("androidx.compose.ui", "ui")
        exclude("androidx.compose.ui", "ui-graphics")
        exclude("androidx.compose.ui", "ui-text")
        exclude("androidx.compose.foundation", "foundation")
    }
}
