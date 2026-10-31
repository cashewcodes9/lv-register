package io.github.cashewcodes9.lvregister.cli;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Module boundaries from PLAN.md: Spring only in `api`, `domain` without dependencies. */
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

    @Test
    void domainDependsOnlyOnTheJdk() {
        classes().that().resideInAPackage("io.github.cashewcodes9.lvregister.domain..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "io.github.cashewcodes9.lvregister.domain..")
                .check(CLASSES);
    }
}
