plugins { id("lv.java-conventions") }

dependencies {
    api(project(":domain"))
    implementation(libs.slf4j.api)
}
