rootProject.name = "lv-register"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

// Module folders are grouped by who uses them (ROADMAP.md section 5). Gradle project names stay
// flat (`:domain`, `:cli`, ...); only the folder changes.
//   shared/    used by the CLI and the API: the domain and the `serving` port with its PostgreSQL adapter
//   pipeline/  plain Java pipeline steps and the CLI that runs them (no Spring)
//   api/       the Spring Boot read API (the only Spring module)
val groups = mapOf(
    "shared" to listOf("domain", "serving", "serving-postgres"),
    "pipeline" to listOf("storage", "ingest", "lake", "normalize", "diff", "quality", "index", "pipes", "cli"),
    "api" to listOf("api"),
)

groups.forEach { (group, names) ->
    names.forEach { name ->
        include(name)
        project(":$name").projectDir = file("modules/$group/$name")
    }
}
