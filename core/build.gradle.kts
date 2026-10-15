plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

val operationalMemoryTest = tasks.register<Test>("operationalMemoryTest") {
    description = "Verifies the mobile backup ceiling in an isolated 96 MiB heap"
    group = "verification"
    useJUnit()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter.includeTestsMatching("dev.opentimemanager.core.backup.BackupOperationalMemoryTest")
    maxHeapSize = "96m"
    maxParallelForks = 1
}

tasks.test {
    useJUnit()
    filter.excludeTestsMatching("dev.opentimemanager.core.backup.BackupOperationalMemoryTest")
    dependsOn(operationalMemoryTest)
}
