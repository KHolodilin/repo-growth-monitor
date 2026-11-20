package com.kholodilin.collector;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Classic layered architecture of the collector: controller -> service -> repository/integration ->
 * model, with configuration wiring everything and security/logging as narrow side layers.
 * Violations are fixed in code; there are no ignored dependencies, frozen rules or excludes.
 */
@AnalyzeClasses(
    packages = "com.kholodilin.collector",
    importOptions = ImportOption.DoNotIncludeTests.class)
class CollectorArchitectureTest {
  private static final String ROOT = "com.kholodilin.collector";
  private static final String CONTROLLER = ROOT + ".controller..";
  private static final String SERVICE = ROOT + ".service..";
  private static final String REPOSITORY = ROOT + ".repository..";
  private static final String INTEGRATION = ROOT + ".integration..";
  private static final String MODEL = ROOT + ".model..";
  private static final String CONFIGURATION = ROOT + ".configuration..";
  private static final String SECURITY = ROOT + ".security..";
  private static final String LOGGING = ROOT + ".logging..";

  /** The root package holds only the application entry point. */
  private static final DescribedPredicate<JavaClass> APPLICATION =
      DescribedPredicate.describe(
          "the root GitHubCollectorApplication",
          c ->
              c.getPackageName().equals(ROOT)
                  && c.getSimpleName().equals("GitHubCollectorApplication"));

  @ArchTest
  static final ArchRule layers =
      layeredArchitecture()
          .consideringAllDependencies()
          .layer("Controller")
          .definedBy(CONTROLLER)
          .layer("Service")
          .definedBy(SERVICE)
          .layer("Repository")
          .definedBy(REPOSITORY)
          .layer("Integration")
          .definedBy(INTEGRATION)
          .layer("Model")
          .definedBy(MODEL)
          .layer("Configuration")
          .definedBy(CONFIGURATION)
          .layer("Security")
          .definedBy(SECURITY)
          .optionalLayer("Logging")
          .definedBy(LOGGING)
          .layer("Application")
          .definedBy(APPLICATION)
          // The application entry point only imports configuration; nobody depends on it.
          .whereLayer("Application")
          .mayNotBeAccessedByAnyLayer()
          // Configuration may access every layer; only the entry point may access configuration.
          .whereLayer("Configuration")
          .mayOnlyBeAccessedByLayers("Application")
          .whereLayer("Controller")
          .mayOnlyBeAccessedByLayers("Configuration")
          .whereLayer("Service")
          .mayOnlyBeAccessedByLayers("Controller", "Configuration")
          .whereLayer("Repository")
          .mayOnlyBeAccessedByLayers("Service", "Integration", "Configuration")
          .whereLayer("Integration")
          .mayOnlyBeAccessedByLayers("Service", "Configuration")
          .whereLayer("Security")
          .mayOnlyBeAccessedByLayers("Configuration")
          .whereLayer("Model")
          .mayOnlyBeAccessedByLayers(
              "Controller", "Service", "Repository", "Integration", "Security", "Configuration")
          // Logging is a narrow side layer: only the layers that correlate logs may use it.
          .whereLayer("Logging")
          .mayOnlyBeAccessedByLayers("Controller", "Service", "Security", "Configuration");

  @ArchTest
  static final ArchRule topLevelPackagesAreFreeOfCycles =
      slices().matching(ROOT + ".(*)..").should().beFreeOfCycles();

  @ArchTest
  static final ArchRule integrationSubpackagesAreFreeOfCycles =
      slices().matching(ROOT + ".integration.(*)..").should().beFreeOfCycles();

  @ArchTest
  static final ArchRule rootPackageHoldsOnlyTheApplication =
      classes()
          .that()
          .resideInAPackage(ROOT)
          .should()
          .haveSimpleName("GitHubCollectorApplication")
          .because("the root package is the entry point and nothing else");

  @ArchTest
  static final ArchRule applicationOnlyImportsConfiguration =
      classes()
          .that(APPLICATION)
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("java..", "org.springframework..", CONFIGURATION, ROOT);

  @ArchTest
  static final ArchRule modelIsFrameworkFree =
      noClasses()
          .that()
          .resideInAPackage(MODEL)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "tools.jackson..",
              "com.fasterxml..",
              "jakarta..",
              "javax..",
              "java.net.http..",
              "java.sql..",
              "org.slf4j..",
              "com.github.benmanes..",
              "io.micrometer..",
              "com.kholodilin.idempotency..",
              "com.kholodilin.collector.contracts..")
          .because(
              "model holds plain states, errors and settings: no Spring, JSON, HTTP, storage or wire contracts");

  @ArchTest
  static final ArchRule serviceKnowsNeitherHttpNorSecurityNorBoundProperties =
      noClasses()
          .that()
          .resideInAPackage(SERVICE)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.http..",
              "org.springframework.web..",
              "org.springframework.security..",
              "org.springframework.boot.context.properties..",
              "jakarta.servlet..")
          .because(
              "services decide on semantic outcomes, not on HTTP, security or bound properties");

  @ArchTest
  static final ArchRule controllersNeverReachExecutionOrDeliveryServices =
      noClasses()
          .that()
          .resideInAPackage(CONTROLLER)
          .should()
          .dependOnClassesThat()
          .haveSimpleNameEndingWith("ExecutionService")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleNameEndingWith("DeliveryService")
          .because("controllers use acceptance, recovery and invalidation services only");

  @ArchTest
  static final ArchRule repositoryKnowsNoWebTypes =
      noClasses()
          .that()
          .resideInAPackage(REPOSITORY)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.http..", "org.springframework.web..", "jakarta.servlet..");

  @ArchTest
  static final ArchRule loggingOnlyUsesJdkAndSlf4j =
      classes()
          .that()
          .resideInAPackage(LOGGING)
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("java..", "org.slf4j..", LOGGING);

  @ArchTest
  static final ArchRule configurationPropertiesLiveInConfiguration =
      classes()
          .that()
          .areAnnotatedWith(ConfigurationProperties.class)
          .or()
          .areAnnotatedWith(Configuration.class)
          .should()
          .resideInAPackage(CONFIGURATION);

  @ArchTest
  static final ArchRule configurationHoldsNoStaticMetricState =
      noFields()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage(CONFIGURATION)
          .should()
          .haveRawType(AtomicLong.class)
          .because("counters are an injectable CollectorJobMetrics bean, not static state");
}
