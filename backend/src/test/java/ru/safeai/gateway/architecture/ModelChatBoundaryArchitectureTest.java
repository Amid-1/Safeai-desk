package ru.safeai.gateway.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "ru.safeai.gateway.model",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ModelChatBoundaryArchitectureTest {

    @ArchTest
    static final ArchRule modelMustNotDependOnChat =
            noClasses()
                    .that()
                    .resideInAPackage("ru.safeai.gateway.model..")
                    .should()
                    .dependOnClassesThat(
                            JavaClass.Predicates.resideInAPackage(
                                    "ru.safeai.gateway.chat.."
                            )
                    )
                    .because("model must not depend on chat implementation");
}
