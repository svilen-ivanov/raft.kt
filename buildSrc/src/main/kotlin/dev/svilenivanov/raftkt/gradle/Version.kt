package dev.svilenivanov.raftkt.gradle

object Version {

    const val library = "0.1-SNAPSHOT"

    const val java = "1.8"
    const val kotlin = "1.5.10"
    const val coroutines = "1.5.0" // https://github.com/Kotlin/kotlinx.coroutines/releases
    const val serialization = "1.2.1" // https://github.com/Kotlin/kotlinx.serialization

    const val slf4jApi = "1.7.30" // https://search.maven.org/artifact/org.slf4j/slf4j-api
    const val log4j2 = "2.14.1" // https://search.maven.org/artifact/org.apache.logging.log4j/log4j-slf4j-impl
    const val kotest = "4.6.0" // https://search.maven.org/search?q=g:io.kotest%20AND%20a:kotest-runner-junit5
    const val dateTime = "0.2.1" // https://search.maven.org/artifact/org.jetbrains.kotlinx/kotlinx-datetime
    const val atomicfu = "0.16.1" // https://search.maven.org/artifact/org.jetbrains.kotlinx/atomicfu
}
