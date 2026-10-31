rootProject.name = "lv-register"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

// Pipeline modules (plain Java, no Spring). The Spring Boot `api` module joins in Milestone 8.
listOf("domain", "storage", "ingest", "lake", "normalize", "diff", "quality", "index", "pipes", "cli")
    .forEach { name ->
        include(name)
        project(":$name").projectDir = file("modules/$name")
    }
