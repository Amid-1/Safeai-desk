package ru.safeai.gateway.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "ru.safeai.gateway.knowledge",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class KnowledgeChatBoundaryArchitectureTest {

    @ArchTest
    static final ArchRule knowledgeMustNotDependOnChat =
            noClasses()
                    .that()
                    .resideInAPackage("ru.safeai.gateway.knowledge..")
                    .should()
                    .dependOnClassesThat(
                            JavaClass.Predicates.resideInAPackage(
                                    "ru.safeai.gateway.chat.."
                            )
                    )
                    .because("knowledge must not depend on chat implementation");
}
