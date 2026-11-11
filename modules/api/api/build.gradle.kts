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

// Folder rule (ROADMAP.md section 5): the API depends on modules under modules/shared only,
// never on a pipeline module. Checked when the build is configured.
afterEvaluate {
    val shared = rootDir.resolve("modules/shared")
    configurations.flatMap { it.dependencies.withType<ProjectDependency>() }
        .map { it.path }
        .distinct()
        .forEach { path ->
            check(project(path).projectDir.startsWith(shared)) {
                "api must depend on modules/shared only, but depends on $path (${project(path).projectDir})"
            }
        }
}

// The plain jar is not needed; bootJar is the deliverable (Dockerfile target `api`).
tasks.jar { enabled = false }

// Production: the built React app (web/dist) is served by Spring Boot from classpath:/static.
// The Dockerfile sets LV_WEB_DIST after its `web` stage; local Gradle builds ship the API only.
tasks.processResources {
    providers.environmentVariable("LV_WEB_DIST").orNull?.let { dist ->
        from(dist) { into("static") }
    }
}
