// diff step: compare two snapshots (pure), derive events with deterministic ids, and write
// them as JSON Lines to the lake.
plugins {
    id("lv.java-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":domain"))
    api(project(":lake"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    testFixturesApi(project(":lake"))
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.logback.classic)
}
