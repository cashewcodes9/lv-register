rootProject.name = "lv-register"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

// Pipeline modules (plain Java, no Spring). `serving` is the serving-store port and
// `serving-postgres` its PostgreSQL adapter. The Spring Boot `api` module joins in Milestone 8.
listOf(
    "domain", "storage", "ingest", "lake", "normalize", "diff", "quality", "index",
    "serving", "serving-postgres", "pipes", "cli",
)
    .forEach { name ->
        include(name)
        project(":$name").projectDir = file("modules/$name")
    }
