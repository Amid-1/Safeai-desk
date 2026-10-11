package ru.safeai.gateway.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "ru.safeai.gateway.audit",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class AuditUserBoundaryArchitectureTest {

    @ArchTest
    static final ArchRule auditMustNotDependOnUser =
            noClasses()
                    .that()
                    .resideInAPackage("ru.safeai.gateway.audit..")
                    .should()
                    .dependOnClassesThat(
                            JavaClass.Predicates.resideInAPackage(
                                    "ru.safeai.gateway.user.."
                            )
                    )
                    .because("audit must depend on user only through the audit-owned SPI");
}
