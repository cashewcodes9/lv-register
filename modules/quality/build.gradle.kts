// quality step: run record and data quality report per capture (PLAN.md section 11).
plugins { id("lv.java-conventions") }

dependencies {
    api(project(":domain"))
    api(project(":lake"))
    implementation(project(":diff"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    testImplementation(testFixtures(project(":diff")))
    testRuntimeOnly(libs.logback.classic)
}
