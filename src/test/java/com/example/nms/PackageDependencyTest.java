package com.example.nms;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.example.nms.delivery.DeliveryCallbacks;
import com.example.nms.delivery.DeliveryRepository;
import com.example.nms.security.SourceSystem;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * ADR-0005 package rules: feature packages never import {@code api} (domain code returns outcomes;
 * controllers translate them to {@code ApiException}), and from {@code security} only {@link
 * SourceSystem}; {@code common} and {@code observability} depend on no feature or web package; only
 * {@link DeliveryCallbacks} writes callback outcomes; no package cycles except the documented
 * {@code api} / {@code security} pair.
 */
class PackageDependencyTest {

  private static final String[] FEATURES = {
    "com.example.nms.intake..",
    "com.example.nms.routing..",
    "com.example.nms.delivery..",
    "com.example.nms.provider..",
    "com.example.nms.status..",
    "com.example.nms.audit..",
    "com.example.nms.directory.."
  };

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.example.nms");

  @Test
  void featurePackagesDoNotDependOnTheWebLayer() {
    noClasses()
        .that()
        .resideInAnyPackage(FEATURES)
        .should()
        .dependOnClassesThat(
            resideInAPackage("com.example.nms.api..")
                .or(
                    resideInAPackage("com.example.nms.security..")
                        .and(not(type(SourceSystem.class)))))
        .because("domain code returns outcomes; only the web layer maps them to the error contract")
        .check(PRODUCTION);
  }

  @Test
  void sharedPackagesDependOnNoFeatureOrWebPackage() {
    noClasses()
        .that()
        .resideInAnyPackage("com.example.nms.common..", "com.example.nms.observability..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(FEATURES)
        .orShould()
        .dependOnClassesThat()
        .resideInAnyPackage("com.example.nms.api..", "com.example.nms.security..")
        .because("shared vocabulary and tracing are used by every package, so they import none")
        .check(PRODUCTION);
  }

  @Test
  void onlyDeliveryCallbacksAppliesCallbackOutcomes() {
    methods()
        .that()
        .areDeclaredIn(DeliveryRepository.class)
        .and()
        .haveName("applyCallback")
        .should()
        .onlyBeCalled()
        .byClassesThat()
        .belongToAnyOf(DeliveryCallbacks.class)
        .because(
            "DeliveryCallbacks.apply is the only callback writer (lock, update, audit, metric)")
        .check(PRODUCTION);
  }

  @Test
  void packagesAreFreeOfCycles() {
    slices()
        .matching("com.example.nms.(*)..")
        .should()
        .beFreeOfCycles()
        // Known exception (ADR-0005): security filters write the api error body, and api
        // controllers resolve the security principal.
        .ignoreDependency(
            resideInAPackage("com.example.nms.api.."),
            resideInAPackage("com.example.nms.security.."))
        .ignoreDependency(
            resideInAPackage("com.example.nms.security.."),
            resideInAPackage("com.example.nms.api.."))
        .check(PRODUCTION);
  }
}
