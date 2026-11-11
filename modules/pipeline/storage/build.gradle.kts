// Object storage: one interface, an implementation for the S3-compatible store (SeaweedFS,
// through the AWS SDK v2 as protocol client) and a local-directory implementation for tests.
// Test fixtures: a SeaweedFS Testcontainer shared by the modules that test against S3.
plugins {
    id("lv.java-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":domain"))
    implementation(platform(libs.awssdk.bom))
    implementation(libs.awssdk.s3) {
        // Use the small JDK-based HTTP client instead of Apache and Netty.
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.awssdk.url.connection)
    implementation(libs.slf4j.api)

    testFixturesApi(libs.testcontainers)
    testFixturesApi(platform(libs.awssdk.bom))
    testFixturesApi(libs.awssdk.s3)
    testRuntimeOnly(libs.logback.classic)
}
