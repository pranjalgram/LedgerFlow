package com.ledgerflow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ArchitectureTest {
    @Test
    void productionCodeUsesConstructorInjectionAndHasNoModuleCycles() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.ledgerflow");
        noFields().should().beAnnotatedWith(Autowired.class).allowEmptyShould(true).check(classes);
        slices().matching("com.ledgerflow.(*)..").should().beFreeOfCycles().check(classes);
        for (String module : new String[]{"identity", "merchant", "audit", "ledger", "wallet", "outbox", "payment", "refund", "notification"}) {
            noClasses().that().resideOutsideOfPackage("com.ledgerflow." + module + "..")
                    .should().dependOnClassesThat().resideInAPackage("com.ledgerflow." + module + ".internal..")
                    .check(classes);
        }
    }
}
