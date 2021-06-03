import dev.svilenivanov.raftkt.gradle.Version

plugins {
    apply {
        kotlin("jvm")
        kotlin("plugin.serialization")
        `java-library`
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Version.coroutines}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:${Version.serialization}")
    api("org.slf4j:slf4j-api:${Version.slf4jApi}")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${Version.serialization}")
    testImplementation("io.kotest:kotest-assertions-core:${Version.kotest}")
    testImplementation("io.kotest:kotest-property:${Version.kotest}")
    testRuntimeOnly("org.apache.logging.log4j:log4j-slf4j-impl:${Version.log4j2}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Version.coroutines}")
}
