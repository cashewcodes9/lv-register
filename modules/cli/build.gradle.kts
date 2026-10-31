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
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logstash.encoder)

    testImplementation(libs.archunit)
    testImplementation(libs.logback.classic)
}

application {
    mainClass = "lvregister.cli.Main"
    applicationName = "lv-register"
}
