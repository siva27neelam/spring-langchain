package com.loanprocessing;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchitectureTest {
    @Test
    void decisionDoesNotDependOnLangChainOrSpringAndExtractionDoesNotDependOnMvc() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.loanprocessing");
        com.tngtech.archunit.lang.ArchRule decision = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.loanprocessing.decision..")
                .should().dependOnClassesThat().resideInAnyPackage("dev.langchain4j..", "org.springframework..");
        com.tngtech.archunit.lang.ArchRule extraction = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.loanprocessing.extraction..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "jakarta.servlet..");
        com.tngtech.archunit.lang.ArchRule stub = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.loanprocessing.credit.stub..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.net..", "java.net.http..", "org.springframework.web.client..", "dev.langchain4j..");
        decision.check(classes);
        extraction.check(classes);
        stub.check(classes);
    }

    @Test
    void experianAdapterIsNotOnTheClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.loanprocessing.credit.ExperianCreditReportAdapter"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.loanprocessing.credit.stub.ExperianCreditReportAdapter"));
    }
}
