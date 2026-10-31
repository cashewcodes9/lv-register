// PostgreSQL adapter for the `serving` port: plain JDBC + HikariCP, Flyway migrations.
plugins { id("lv.java-conventions") }

dependencies {
    api(project(":serving"))
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.slf4j.api)

    testImplementation(testFixtures(project(":serving")))
}
