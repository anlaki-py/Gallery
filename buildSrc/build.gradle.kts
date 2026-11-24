plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    // The full plugin adds its own Kotlin plugin to every app build's classpath.
    implementation(libs.plugins.android.map { "com.android.tools.build:gradle-api:${it.version}" }) {
        isTransitive = false
    }
    implementation("org.ow2.asm:asm:9.9")
    testImplementation("junit:junit:4.13.2")
}

val verifyBuildLogicClasspath by tasks.registering {
    doLast {
        val modules = configurations.runtimeClasspath.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
        check(modules.none {
            (it.group == "com.android.tools.build" && it.name == "gradle") ||
                (it.group == "org.jetbrains.kotlin" && it.name == "kotlin-gradle-plugin")
        }) { "Build logic must not add Android or Kotlin plugins to the app's classpath" }
    }
}

tasks.test {
    dependsOn(verifyBuildLogicClasspath)
}
