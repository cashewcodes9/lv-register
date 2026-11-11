// index step: loads companies, events and the run report of a capture into the serving store,
// only through the `serving` port.
plugins { id("lv.java-conventions") }

dependencies {
    api(project(":domain"))
    api(project(":serving"))
    implementation(project(":lake"))
    implementation(project(":diff"))
    implementation(project(":quality"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    testImplementation(testFixtures(project(":diff")))
    testRuntimeOnly(libs.logback.classic)
}
