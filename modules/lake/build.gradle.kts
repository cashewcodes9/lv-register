// lake step: CSV in s3://lv-raw to Parquet in s3://lv-lake with DuckDB (JDBC, httpfs), and the
// reader that gives normalize the rows of one capture.
plugins { id("lv.java-conventions") }

dependencies {
    api(project(":domain"))
    api(project(":storage"))
    api(project(":ingest"))
    implementation(libs.duckdb.jdbc)
    implementation(libs.slf4j.api)

    testImplementation(testFixtures(project(":storage")))
    testRuntimeOnly(libs.logback.classic)
}
