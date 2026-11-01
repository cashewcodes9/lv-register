rootProject.name = "lv-register"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

// Pipeline modules (plain Java, no Spring). `serving` is the serving-store port and
// `serving-postgres` its PostgreSQL adapter. `api` is the Spring Boot read API (the only Spring module).
listOf(
    "domain", "storage", "ingest", "lake", "normalize", "diff", "quality", "index",
    "serving", "serving-postgres", "pipes", "cli", "api",
)
    .forEach { name ->
        include(name)
        project(":$name").projectDir = file("modules/$name")
    }
