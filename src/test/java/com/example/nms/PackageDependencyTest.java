package com.example.nms;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.example.nms.security.SourceSystem;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * ADR-0005: feature packages never import {@code api} (domain code returns outcomes; controllers
 * translate them to {@code ApiException}), and from {@code security} only {@link SourceSystem}.
 */
class PackageDependencyTest {

  private static final String[] FEATURES = {
    "com.example.nms.intake..",
    "com.example.nms.routing..",
    "com.example.nms.delivery..",
    "com.example.nms.provider..",
    "com.example.nms.callback..",
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
}
