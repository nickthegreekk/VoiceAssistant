// Top-level build file where you can add configuration options common to all subprojects/modules.

// Dependabot: AGP 9.5.0 brings org.bouncycastle:bcprov-jdk18on 1.80.2 onto the build script's
// classpath (via com.android.tools.build:gradle-api / lint). That version is vulnerable to the
// Name Constraints bypass fixed in 1.85. Force the patched version for the build tooling; this
// is a build-classpath dependency only, nothing here reaches the app.
buildscript {
    configurations.classpath {
        resolutionStrategy.force("org.bouncycastle:bcprov-jdk18on:1.85")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}