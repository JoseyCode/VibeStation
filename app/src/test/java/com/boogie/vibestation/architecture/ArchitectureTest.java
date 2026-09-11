package com.boogie.vibestation.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.Before;
import org.junit.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architectural tests enforcing layer boundaries and package dependency rules across VibeStation.
 */
public class ArchitectureTest {

    private JavaClasses importedClasses;

    /**
     * Imports compiled classes from the VibeStation package, excluding test classes.
     */
    @Before
    public void setUp() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.boogie.vibestation");
    }

    /**
     * Verifies that custom views do not depend on the AudioService or Activities.
     */
    @Test
    public void viewsShouldNotDependOnServiceOrActivities() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..vibestation.views..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..vibestation.AudioService..", "..vibestation.MainActivity..", "..vibestation.SplashActivity..");
        rule.check(importedClasses);
    }

    /**
     * Verifies that data models do not depend on custom views or UI components.
     */
    @Test
    public void modelsShouldNotDependOnViews() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..vibestation.models..")
                .should().dependOnClassesThat()
                .resideInAPackage("..vibestation.views..");
        rule.check(importedClasses);
    }
}
