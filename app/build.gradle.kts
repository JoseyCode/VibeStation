plugins {
    alias(libs.plugins.android.application)
    id("pmd")
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
        versionCode = 17
        versionName = "3.0.0"

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
    testImplementation("com.tngtech.archunit:archunit-junit4:1.3.0")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.palette:palette:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.jthink:jaudiotagger:3.0.1")
}

val cpdConfig: Configuration by configurations.creating

dependencies {
    cpdConfig("net.sourceforge.pmd:pmd-cli:7.10.0")
    cpdConfig("net.sourceforge.pmd:pmd-java:7.10.0")
    cpdConfig("net.sourceforge.pmd:pmd-kotlin:7.10.0")
    cpdConfig("org.slf4j:slf4j-api:2.0.12")
    cpdConfig("org.slf4j:slf4j-simple:2.0.12")
}

pmd {
    isConsoleOutput = true
    toolVersion = "7.10.0"
}

tasks.register<Pmd>("pmd") {
    description = "Run PMD code analysis on Java source files"
    group = "verification"
    ruleSetFiles = files("${project.rootDir}/config/pmd/ruleset.xml")
    ruleSets = listOf()
    source = fileTree("src/main/java") {
        include("**/*.java")
    }
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    ignoreFailures = true
}

tasks.register<JavaExec>("cpd") {
    description = "Run CPD (Copy-Paste Detector) analysis on Kotlin sources"
    group = "verification"
    classpath = cpdConfig
    mainClass.set("net.sourceforge.pmd.cli.PmdCli")
    args = listOf(
        "cpd",
        "--minimum-tokens", "50",
        "--dir", "${project.projectDir}/src/main/java",
        "--language", "kotlin",
        "--format", "text"
    )
    isIgnoreExitValue = true
}

tasks.register("checkQuality") {
    description = "Run all quality verification checks: unit tests, lint, PMD, and CPD"
    group = "verification"
    dependsOn("testDebugUnitTest", "lintDebug", "pmd", "cpd")
}