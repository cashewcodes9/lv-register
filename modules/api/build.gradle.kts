// Spring Boot 4 read API under /api (PLAN.md section 13): the only module with Spring. Reads
// through the `serving` port; the adapter is chosen by SERVING_BACKEND.
plugins {
    id("lv.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.jackson.bom))
    implementation(project(":serving"))
    implementation(project(":serving-postgres"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.cache)
    implementation(libs.caffeine)
    implementation(libs.springdoc.webmvc.ui)
    implementation(libs.bucket4j)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(testFixtures(project(":serving")))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.archunit)
}

// The plain jar is not needed; bootJar is the deliverable (Dockerfile target `api`).
tasks.jar { enabled = false }
