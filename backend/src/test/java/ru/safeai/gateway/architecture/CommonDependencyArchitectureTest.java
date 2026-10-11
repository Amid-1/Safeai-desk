package ru.safeai.gateway.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "ru.safeai.gateway.common",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class CommonDependencyArchitectureTest {

    @ArchTest
    static final ArchRule commonMustNotDependOnApplicationModules =
            noClasses()
                    .that()
                    .resideInAPackage(
                            "ru.safeai.gateway.common.."
                    )
                    .should()
                    .dependOnClassesThat(
                            JavaClass.Predicates
                                    .resideInAPackage(
                                            "ru.safeai.gateway.."
                                    )
                                    .and(
                                            JavaClass.Predicates
                                                    .resideOutsideOfPackage(
                                                            "ru.safeai.gateway.common.."
                                                    )
                                    )
                    )
                    .because(
                            "common must not depend on application modules"
                    );
}
