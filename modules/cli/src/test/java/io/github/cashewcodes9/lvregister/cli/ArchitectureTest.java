package io.github.cashewcodes9.lvregister.cli;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Module boundaries from PLAN.md: Spring only in `api`, `domain` without dependencies, serving behind its port. */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.cashewcodes9.lvregister");

    @Test
    void onlyTheApiModuleUsesSpring() {
        noClasses().that().resideOutsideOfPackage("io.github.cashewcodes9.lvregister.api..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .check(CLASSES);
    }

    /** The port speaks domain and read records only, so any backend can implement it. */
    @Test
    void servingPortHasNoStoreTypes() {
        noClasses().that().resideInAPackage("io.github.cashewcodes9.lvregister.serving")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.sql..", "javax.sql..", "org.postgresql..", "com.zaxxer..",
                        "org.flywaydb..", "co.elastic..", "org.elasticsearch..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    /** Only the wiring (cli, api) knows which adapter is used; everything else sees the port. */
    @Test
    void onlyWiringDependsOnServingAdapters() {
        noClasses().that().resideOutsideOfPackages(
                        "io.github.cashewcodes9.lvregister.cli..",
                        "io.github.cashewcodes9.lvregister.api..",
                        "io.github.cashewcodes9.lvregister.serving.*..")
                .should().dependOnClassesThat().resideInAPackage("io.github.cashewcodes9.lvregister.serving.*..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void domainDependsOnlyOnTheJdk() {
        classes().that().resideInAPackage("io.github.cashewcodes9.lvregister.domain..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "io.github.cashewcodes9.lvregister.domain..")
                .check(CLASSES);
    }
}
