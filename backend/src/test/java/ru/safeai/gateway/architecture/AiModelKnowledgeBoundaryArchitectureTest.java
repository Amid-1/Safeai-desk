package ru.safeai.gateway.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "ru.safeai.gateway.ai",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class AiModelKnowledgeBoundaryArchitectureTest {

    @ArchTest
    static final ArchRule physicalAiMustNotDependOnModelOrKnowledge =
            noClasses()
                    .that()
                    .resideInAPackage("ru.safeai.gateway.ai..")
                    .should()
                    .dependOnClassesThat(
                            JavaClass.Predicates.resideInAnyPackage(
                                    "ru.safeai.gateway.model..",
                                    "ru.safeai.gateway.knowledge.."
                            )
                    )
                    .because("physical AI execution depends only on abstract execution ports");
}
