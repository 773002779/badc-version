package com.sf.release.control;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The control slice's architecture contract (P5-B design §7 item 8): the two new web classes carry
 * machine values as strings and must not import a domain or infrastructure enum, exactly like the
 * daily, audit and rollback pages. The broader {@code web -> application/common only} rule lives in
 * {@code ArchitectureTest}; this rule keeps the P5-B page from becoming an exception to it.
 */
@AnalyzeClasses(packages = "com.sf.release")
class ControlArchitectureTest {

    @ArchTest
    static final ArchRule control_web_does_not_import_domain_or_infra =
        noClasses().that().resideInAPackage("..web..")
            .and().haveSimpleNameStartingWith("ReleaseControl")
            .or().haveSimpleName("ControlDtos")
            .should().dependOnClassesThat().resideInAnyPackage("..domain..", "..infra..");

    @ArchTest
    static final ArchRule control_application_does_not_import_the_web_layer =
        noClasses().that().haveSimpleName("ReleaseControlService")
            .or().haveSimpleName("PipelineTerminationService")
            .should().dependOnClassesThat().resideInAPackage("..web..");
}