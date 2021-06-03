import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import dev.svilenivanov.raftkt.gradle.Version
import org.gradle.jvm.tasks.Jar

buildscript {
    dependencies {
        @Suppress("RemoveRedundantQualifierName")
        val atomicfuVersion = dev.svilenivanov.raftkt.gradle.Version.atomicfu
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:${atomicfuVersion}")
    }
}
plugins {
    @Suppress("RemoveRedundantQualifierName")
    val kotlinVersion = dev.svilenivanov.raftkt.gradle.Version.kotlin

    kotlin("jvm") version kotlinVersion apply false
    kotlin("plugin.serialization") version kotlinVersion apply false
}

version = Version.library

subprojects {
    repositories {
        mavenCentral()
    }


    tasks {
        withType<KotlinCompile>().configureEach {
            kotlinOptions {
                allWarningsAsErrors = true
                jvmTarget = Version.java
                freeCompilerArgs = listOf("-Xopt-in=kotlin.RequiresOptIn", "-Xopt-in=kotlin.time.ExperimentalTime", "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }

        withType<JavaCompile>().configureEach {
            sourceCompatibility = Version.java
            targetCompatibility = Version.java
        }
    }

    tasks.withType<Jar>().configureEach {
        archiveBaseName.set(rootProject.name)
        archiveAppendix.set(project.name)
        archiveVersion.set(rootProject.version.toString())
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

