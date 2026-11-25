package com.sf.release.emergency;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The P6-D architecture contract (design §5 item 7): the emergency-release web classes carry machine
 * values as strings and must not import a domain or infrastructure enum, exactly like the control,
 * daily, audit and rollback pages; the application service must not depend on the web layer.
 *
 * <p>The broader {@code web -> application/common only} rule lives in {@code ArchitectureTest}; this
 * rule keeps the new page from becoming an exception to it.
 */
@AnalyzeClasses(packages = "com.sf.release")
class EmergencyArchitectureTest {

    @ArchTest
    static final ArchRule emergency_web_does_not_import_domain_or_infra =
        noClasses().that().resideInAPackage("..web..")
            .and().haveSimpleNameStartingWith("EmergencyRelease")
            .or().haveSimpleName("EmergencyDtos")
            .should().dependOnClassesThat().resideInAnyPackage("..domain..", "..infra..");

    @ArchTest
    static final ArchRule emergency_application_does_not_import_the_web_layer =
        noClasses().that().haveSimpleName("EmergencyReleaseService")
            .should().dependOnClassesThat().resideInAPackage("..web..");
}