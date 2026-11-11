package lvregister.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import lvregister.serving.ServingWriter;
import org.junit.jupiter.api.Test;

/** The API reads through the port only (PLAN.md section 13). */
class ApiArchitectureTest {

    private static final JavaClasses API = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("lvregister.api");

    @Test
    void onlyTheConfigurationKnowsTheAdapter() {
        noClasses().that().doNotHaveSimpleName("ServingConfig")
                .should().dependOnClassesThat().resideInAPackage("lvregister.serving.postgres..")
                .check(API);
    }

    @Test
    void theApiNeverWrites() {
        noClasses().that().doNotHaveSimpleName("ServingConfig")
                .should().dependOnClassesThat().areAssignableTo(ServingWriter.class).check(API);
    }

    @Test
    void theApiDoesNotTouchSqlOrThePipeline() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..",
                "lvregister.lake..", "lvregister.diff..", "lvregister.index..", "lvregister.storage..", "lvregister.cli..")
                .check(API);
    }
}
