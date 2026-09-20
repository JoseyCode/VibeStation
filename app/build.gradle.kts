plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.boogie.vibestation"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.retroclone"
        minSdk = 24
        targetSdk = 36
        versionCode = 19
        versionName = "3.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    testImplementation(libs.archunit.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
    detektPlugins(libs.detekt.ktlint)
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.palette:palette:1.0.0")
    implementation(libs.okhttp)
    implementation("net.jthink:jaudiotagger:3.0.1")
}

kover {
    reports {
        filters {
            excludes {
                classes("*.BuildConfig", "*.R", "*.R\$*")
            }
        }
        verify {
            // Ratchet: raise as tests land, never lower. Last measured 21.37% (after library assembly tests).
            rule {
                minBound(21)
            }
        }
    }
}

val cpdConfig: Configuration by configurations.creating

dependencies {
    cpdConfig("net.sourceforge.pmd:pmd-cli:7.10.0")
    cpdConfig("net.sourceforge.pmd:pmd-kotlin:7.10.0")
    cpdConfig("org.slf4j:slf4j-api:2.0.12")
    cpdConfig("org.slf4j:slf4j-simple:2.0.12")
}

detekt {
    config.setFrom("${project.rootDir}/config/detekt/detekt.yml")
    baseline = file("${project.rootDir}/config/detekt/baseline.xml")
    buildUponDefaultConfig = true
}

tasks.register<JavaExec>("cpd") {
    description = "Run CPD (Copy-Paste Detector) analysis on Kotlin sources"
    group = "verification"
    classpath = cpdConfig
    mainClass.set("net.sourceforge.pmd.cli.PmdCli")
    args = listOf(
        "cpd",
        "--minimum-tokens", "50",
        "--dir", "${project.projectDir}/src/main/kotlin",
        "--language", "kotlin",
        "--format", "text"
    )
    isIgnoreExitValue = true
}

tasks.register("checkQuality") {
    description = "Run all quality verification checks: unit tests, lint, detekt, coverage, and CPD"
    group = "verification"
    dependsOn("testDebugUnitTest", "lintDebug", "detekt", "koverVerifyDebug", "cpd")
}