package com.sf.release.daily;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The daily slice's architecture contract (P5-A design §7 item 8): the daily web classes carry
 * machine values as strings and must not import a domain or infrastructure enum, exactly like the
 * audit and rollback pages. The broader {@code web -> application/common only} rule lives in
 * {@code ArchitectureTest}; this rule keeps the daily page from becoming an exception to it.
 */
@AnalyzeClasses(packages = "com.sf.release")
class DailyArchitectureTest {

    @ArchTest
    static final ArchRule daily_web_does_not_import_domain_or_infra =
        noClasses().that().resideInAPackage("..web..")
            .and().haveSimpleNameStartingWith("Daily")
            .should().dependOnClassesThat().resideInAnyPackage("..domain..", "..infra..");

    @ArchTest
    static final ArchRule daily_application_does_not_import_the_web_layer =
        noClasses().that().haveSimpleName("DailyReleaseService")
            .should().dependOnClassesThat().resideInAPackage("..web..");
}