package com.boogie.vibestation.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.core.importer.Location
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Architectural tests enforcing layer boundaries and package dependency rules across VibeStation.
 * Rules run against compiled bytecode, so Kotlin artifacts (FooKt, Companion, lambdas) are covered
 * by the package selectors rather than by class name.
 */
class ArchitectureTest {

    private lateinit var importedClasses: JavaClasses

    /**
     * Imports compiled classes from the VibeStation package, excluding test classes.
     */
    @BeforeTest
    fun setUp() {
        importedClasses = ClassFileImporter()
            .withImportOption(ExcludeAndroidTestOutput)
            .importPackages("com.boogie.vibestation")
    }

    /**
     * Guards the rules below against passing vacuously: production classes must be imported and
     * test output must not be. (Predefined.DO_NOT_INCLUDE_TESTS matches /test/ paths, but AGP writes
     * test classes under .../debugUnitTest/ and .../debugAndroidTest/, so it excludes nothing here.)
     */
    @Test
    fun importsProductionClassesOnly() {
        val names = importedClasses.map { it.name }
        assertTrue("com.boogie.vibestation.MainActivity" in names, "production classes not imported")
        assertTrue(names.none { it.endsWith("Test") }, "test classes leaked into the import")
    }

    /**
     * Verifies that custom views do not depend on the AudioService or Activities.
     */
    @Test
    fun viewsShouldNotDependOnServiceOrActivities() {
        noClasses()
            .that().resideInAPackage("..vibestation.views..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Activity")
            .orShould().dependOnClassesThat().haveSimpleName("AudioService")
            .check(importedClasses)
    }

    /**
     * Verifies that data models do not depend on custom views or UI components.
     */
    @Test
    fun modelsShouldNotDependOnViews() {
        noClasses()
            .that().resideInAPackage("..vibestation.models..")
            .should().dependOnClassesThat().resideInAPackage("..vibestation.views..")
            .check(importedClasses)
    }

    /**
     * Verifies that models stay plain data: only other models, the JDK, Kotlin, and android.net.Uri.
     */
    @Test
    fun modelsShouldOnlyDependOnModelsAndPlatform() {
        classes()
            .that().resideInAPackage("..vibestation.models..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "..vibestation.models..",
                "java..",
                "kotlin..",
                "android.net..",
                "org.jetbrains.annotations.."
            )
            .check(importedClasses)
    }

    /**
     * Verifies that stateless utilities do not reach up into views, the service, or Activities.
     */
    @Test
    fun utilShouldNotDependOnUiLayer() {
        noClasses()
            .that().resideInAPackage("..vibestation.util..")
            .should().dependOnClassesThat().resideInAPackage("..vibestation.views..")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Activity")
            .orShould().dependOnClassesThat().haveSimpleName("AudioService")
            .check(importedClasses)
    }

    private object ExcludeAndroidTestOutput : ImportOption {
        override fun includes(location: Location): Boolean =
            !location.contains("UnitTest") && !location.contains("AndroidTest")
    }
}
