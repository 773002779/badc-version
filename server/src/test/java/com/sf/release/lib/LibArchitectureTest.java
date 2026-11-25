package com.sf.release.lib;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The library slice's architecture contract (P6-A design §5 item 8): the web classes carry machine
 * values as strings and must not import a domain or infrastructure enum/type, the application
 * services never reach into the web layer, and the slice's tests do not live in a {@code web}
 * package (they own the HTTP contract through MockMvc instead).
 *
 * <p>The broader {@code web -> application/common only} rule lives in {@code ArchitectureTest};
 * these rules keep the library slice from becoming an exception to it.
 */
@AnalyzeClasses(packages = "com.sf.release")
class LibArchitectureTest {

    @ArchTest
    static final ArchRule lib_web_does_not_import_domain_or_infra =
        noClasses().that().resideInAPackage("..web..")
            .and().haveSimpleNameStartingWith("Lib")
            .should().dependOnClassesThat().resideInAnyPackage("..domain..", "..infra..");

    @ArchTest
    static final ArchRule lib_application_does_not_import_the_web_layer =
        noClasses().that().resideInAPackage("..application..")
            .and().haveSimpleNameStartingWith("Lib")
            .should().dependOnClassesThat().resideInAPackage("..web..");

    @ArchTest
    static final ArchRule lib_tests_do_not_live_in_the_web_package =
        noClasses().that().haveSimpleNameStartingWith("Lib")
            .and().haveSimpleNameEndingWith("Test")
            .should().resideInAPackage("..web..");
}