// download step: CKAN lookup of the UR files, download with sha256, raw cache and manifest
// in s3://lv-raw.
plugins { id("lv.java-conventions") }

dependencies {
    api(project(":domain"))
    api(project(":storage"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    testRuntimeOnly(libs.logback.classic)
}
