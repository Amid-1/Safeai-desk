package ru.safeai.gateway.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import ru.safeai.gateway.ai.pricing.PricingResolver;
import ru.safeai.gateway.model.pricing.VersionedCatalogPricingResolver;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = {"ru.safeai.gateway.ai", "ru.safeai.gateway.model"},
        importOptions = ImportOption.DoNotIncludeTests.class
)
class AiModelKnowledgeBoundaryArchitectureTest {

    @ArchTest
    static final ArchRule catalogIsOnlyPricingResolver =
            classes().that().implement(PricingResolver.class)
                    .should().haveFullyQualifiedName(VersionedCatalogPricingResolver.class.getName())
                    .because("all physical costs require operation-bound immutable catalog evidence");

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
