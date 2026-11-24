plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    implementation(libs.plugins.android.map { "com.android.tools.build:gradle:${it.version}" })
    testImplementation("junit:junit:4.13.2")
}
