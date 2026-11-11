// Serving-store port: `ServingWriter`, `ServingQueries` and their read records. No SQL, no
// search client. The contract test suite every adapter must pass lives in testFixtures.
plugins {
    id("lv.java-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":domain"))

    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.assertj)
}
