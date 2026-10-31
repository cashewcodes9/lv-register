// Indexing step: loads companies, events, runs and marts through the `serving` port.
plugins { id("lv.java-conventions") }

dependencies {
    api(project(":domain"))
    implementation(project(":serving"))
    implementation(libs.slf4j.api)
}
