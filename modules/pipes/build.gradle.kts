// Dagster Pipes from the Java side: reads the context Dagster injects and writes the JSON
// messages (materializations, asset checks) it reads back. Protocol version 0.1, see
// docs/architecture.md "Orchestration with Dagster". No Dagster library: the archived official
// Java prototype pulls in Jackson 2 and the AWS SDK (owner decision 2026-11-01).
plugins { id("lv.java-conventions") }

dependencies {
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}
