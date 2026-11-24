plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    // Runtime AGP APIs in buildSrc hide KSP's Android plugin callbacks in the app build.
    // The app's Android plugin supplies these types when it loads the visitor factory.
    compileOnly(libs.plugins.android.map { "com.android.tools.build:gradle-api:${it.version}" })
    implementation("org.ow2.asm:asm:9.9")
    testImplementation("junit:junit:4.13.2")
}

val verifyBuildLogicClasspath by tasks.registering {
    doLast {
        val modules = configurations.runtimeClasspath.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
        check(modules.none {
            it.group == "com.android.tools.build" || it.group == "org.jetbrains.kotlin"
        }) { "Build logic must not add Android or Kotlin APIs to the app's classpath" }
    }
}

tasks.test {
    dependsOn(verifyBuildLogicClasspath)
}
