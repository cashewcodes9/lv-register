// PostgreSQL adapter for the `serving` port: plain JDBC + HikariCP, Flyway migrations,
// pg_trgm + unaccent search. Passes the serving contract suite against a PostgreSQL Testcontainer.
plugins { id("lv.java-conventions") }

dependencies {
    api(project(":serving"))
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    testImplementation(testFixtures(project(":serving")))
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.logback.classic)
}
