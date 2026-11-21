package com.sf.release;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.sf.release")
class ArchitectureTest {
    @ArchTest static final ArchRule domain_must_not_depend_on_infra =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..infra..", "..web..");

    @ArchTest static final ArchRule domain_must_not_use_spring_web =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework.web..", "org.springframework.stereotype..");

    @ArchTest static final ArchRule web_may_only_depend_on_application_and_common =
        noClasses().that().resideInAPackage("..web..")
            .should().dependOnClassesThat().resideInAnyPackage("..infra..", "..domain..");
}