package com.sf.release.pipelineconfig;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The P6-C slice keeps the layered architecture of the project (design §5 item 7): the new web
 * controller/DTO must not import a domain or infra enum, because the DTO carries strings and the
 * application view's machine values. The global {@link com.sf.release.ArchitectureTest} already
 * enforces the rule for every web class; this rule pins it to the classes this slice added.
 *
 * <p>The test package itself is {@code com.sf.release.pipelineconfig}, deliberately outside
 * {@code ..web..} (the web package keeps production classes only).
 */
@AnalyzeClasses(packages = "com.sf.release")
class PipelineConfigArchitectureTest {

    @ArchTest
    static final ArchRule the_pipeline_config_web_classes_stay_free_of_domain_and_infra =
        noClasses().that().resideInAPackage("..web..")
            .and().haveSimpleNameStartingWith("PipelineConfig")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..domain..", "..infra..");

    @ArchTest
    static final ArchRule the_pipeline_config_tests_live_outside_the_web_package =
        noClasses().that().resideInAPackage("com.sf.release.pipelineconfig..")
            .should().resideInAPackage("..web..");
}