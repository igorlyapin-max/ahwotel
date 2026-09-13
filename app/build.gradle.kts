plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}
val releaseVersion = providers.fileContents(rootProject.layout.projectDirectory.file("VERSION"))
    .asText.get().trim()
require(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{2}\\.[0-9]{2}").matches(releaseVersion) &&
    releaseVersion != "00.00.00.00") { "VERSION must contain a release version in XX.YY.ZZ.NN format" }
android {
    namespace = "com.ahwotel"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahwotel"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = releaseVersion
        testInstrumentationRunner = "com.ahwotel.SafeTestRunner"
    }
    buildTypes {
        create("acceptance") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".acceptance"
            matchingFallbacks += listOf("debug")
        }
    }
    // Instrumentation must never attach to the user's com.ahwotel process.
    testBuildType = "acceptance"
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").resources.srcDir("schemas")
    lint { abortOnError = true }
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    kapt("androidx.room:room-compiler:2.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.opentelemetry:opentelemetry-sdk:1.50.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.50.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp-common:1.50.0")
    implementation("io.opentelemetry:opentelemetry-exporter-common:1.50.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    add("acceptanceImplementation", "androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
