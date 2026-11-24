plugins {
    `java-gradle-plugin`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    // Keep AGP out of the runtime; the app plugin supplies it in the shared plugin classloader.
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


gradlePlugin {
    plugins {
        create("forkNotice") {
            id = "gallery.fork-notice"
            implementationClass = "org.fossify.gallery.build.CommonsForkNoticePlugin"
        }
    }
}
