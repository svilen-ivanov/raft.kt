import dev.svilenivanov.raftkt.gradle.Version

plugins {
    apply {
        kotlin("jvm")
        kotlin("plugin.serialization")
        `java-library`
        id("kotlinx-atomicfu")
    }
}

val overrideVersions = mapOf(
    "net.bytebuddy" to "1.11.5" // bump bytebuddy to latest supported Java 16 version (mockk dep)
)

configurations.all {
    resolutionStrategy.eachDependency {
        overrideVersions[requested.module.group]?.let { v ->
            useVersion(v)
            because("Add Java 16 support (mockk)")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Version.coroutines}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:${Version.serialization}")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:${Version.dateTime}")
    api("org.slf4j:slf4j-api:${Version.slf4jApi}")

//    testImplementation(project(":inmem"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${Version.serialization}")
    testImplementation("io.kotest:kotest-assertions-core:${Version.kotest}")
    testImplementation("io.kotest:kotest-property:${Version.kotest}")
    testRuntimeOnly("org.apache.logging.log4j:log4j-slf4j-impl:${Version.log4j2}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Version.coroutines}")
    testImplementation("io.mockk:mockk:${Version.mockk}")
}
