// Picocli entry point with manual wiring. Never boots Spring.
plugins {
    id("lv.java-conventions")
    application
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":storage"))
    implementation(project(":ingest"))
    implementation(project(":lake"))
    implementation(project(":normalize"))
    implementation(project(":diff"))
    implementation(project(":quality"))
    implementation(project(":index"))
    implementation(project(":serving"))
    implementation(project(":serving-postgres"))
    implementation(project(":pipes"))
    implementation(libs.picocli)
    implementation(platform(libs.awssdk.bom))
    implementation(libs.awssdk.s3)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logstash.encoder)

    testImplementation(libs.archunit)
    testImplementation(testFixtures(project(":storage")))
    testImplementation(testFixtures(project(":diff")))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(platform(libs.awssdk.bom))
    testImplementation(libs.awssdk.s3)
    testImplementation(libs.logback.classic)
}

application {
    mainClass = "lvregister.cli.Main"
    applicationName = "lv-register"
    // DuckDB loads its native library through System.load.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// RealCaptureCheck holds a whole snapshot in memory; give it more heap when asked.
tasks.test {
    maxHeapSize = providers.environmentVariable("LV_TEST_HEAP").getOrElse("512m")
}
