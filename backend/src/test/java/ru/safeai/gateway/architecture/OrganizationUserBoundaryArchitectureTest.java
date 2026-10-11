package ru.safeai.gateway.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "ru.safeai.gateway.organization",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class OrganizationUserBoundaryArchitectureTest {

    @ArchTest
    static final ArchRule organizationMustNotDependOnUser =
            noClasses()
                    .that()
                    .resideInAPackage(
                            "ru.safeai.gateway.organization.."
                    )
                    .should()
                    .dependOnClassesThat(
                            JavaClass.Predicates.resideInAPackage(
                                    "ru.safeai.gateway.user.."
                            )
                    )
                    .because(
                            "organization must not depend on user; "
                                    + "user may depend on organization"
                    );
}
