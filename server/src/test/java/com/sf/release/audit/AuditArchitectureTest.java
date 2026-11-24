package com.sf.release.audit;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The audit-specific architecture contract (audit design section 6 item 7): the web classes of the
 * audit page must not import the domain enums or the mapper types — the action label travels as a
 * string through the application read model, exactly like the notification and release pages.
 *
 * <p>The broader {@code web -> application/common only} rule lives in {@code ArchitectureTest}; this
 * rule keeps the audit page from becoming the exception that erodes it.
 */
@AnalyzeClasses(packages = "com.sf.release")
class AuditArchitectureTest {

    @ArchTest
    static final ArchRule audit_web_does_not_import_audit_domain_or_infra =
        noClasses().that().resideInAPackage("..web..")
            .and().haveSimpleNameStartingWith("Audit")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..domain.audit..", "..infra.repo..", "..infra.security..");
}