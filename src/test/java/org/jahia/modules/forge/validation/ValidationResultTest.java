/*
 * ==========================================================================================
 * =                            JAHIA'S ENTERPRISE DISTRIBUTION                             =
 * ==========================================================================================
 *
 *                                  http://www.jahia.com
 *
 * JAHIA'S ENTERPRISE DISTRIBUTIONS LICENSING - IMPORTANT INFORMATION
 * ==========================================================================================
 *
 *     Copyright (C) 2002-2026 Jahia Solutions Group. All rights reserved.
 *
 *     This file is part of a Jahia's Enterprise Distribution.
 *
 *     Jahia's Enterprise Distributions must be used in accordance with the terms
 *     contained in the Jahia Solutions Group Terms & Conditions as well as
 *     the Jahia Sustainable Enterprise License (JSEL).
 *
 *     For questions regarding licensing, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.jahia.modules.forge.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValidationResultTest {

    @Test
    @DisplayName("the answer of a human is the JSON the prompt asks for, wherever it sits in the pasted text")
    void parsesJsonAnswer() {
        ValidationResult result = ValidationResult.parseAnswer("Here is my verdict:\n{\"status\": \"rejected\", \"comments\": [\"XSS in a.jsp\", \" secret in cfg \"]}\nThanks");

        assertThat(result.getStatus()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(result.getComments()).containsExactly("XSS in a.jsp", "secret in cfg");
        assertThat(ValidationResult.parseAnswer("{\"status\":\"APPROVED\",\"comments\":\"all good\"}").getComments()).containsExactly("all good");
    }

    @Test
    @DisplayName("the answer may be a text whose first word is the verdict, the rest being the comments")
    void parsesTextAnswer() {
        ValidationResult result = ValidationResult.parseAnswer("Approved: nothing to report\nThe license file is present.");

        assertThat(result.getStatus()).isEqualTo(ValidationStatus.APPROVED);
        assertThat(result.getComments()).containsExactly("nothing to report", "The license file is present.");
        assertThat(ValidationResult.parseAnswer("review").getStatus()).isEqualTo(ValidationStatus.REVIEW);
    }

    @Test
    @DisplayName("a JSON with a quote left unescaped in a comment still gives its verdict and its comments")
    void salvagesBrokenJson() {
        ValidationResult result = ValidationResult.parseAnswer("{\"status\":\"rejected\",\"comments\":["
                + "\"META-INF/MANIFEST.MF: approving. The coordinates match.\","
                + "\"resources/sandbox.properties: must fix. Leftovers of the template (\"system.sandboxTitle=Sandbox\", \"Some Action\").\","
                + "\"renderingChain.jsp: human review. It ends with <jsp:include page=\"/modules/tools/index.jsp\"/>.\"]}");

        assertThat(result.getStatus()).isEqualTo(ValidationStatus.REJECTED);
        // The comment with the unescaped quotes splits at its inner quote-comma-quote: nothing is lost.
        assertThat(result.getComments()).hasSize(4)
                .contains("META-INF/MANIFEST.MF: approving. The coordinates match.")
                .contains("renderingChain.jsp: human review. It ends with <jsp:include page=\"/modules/tools/index.jsp\"/>.");
        assertThat(String.join(" ", result.getComments())).contains("Leftovers of the template").contains("Some Action");
    }

    @Test
    @DisplayName("the verdict reads in the words of the assistant, under its own key")
    void readsPhrasedVerdicts() {
        assertThat(ValidationResult.parseAnswer("Not approved: a secret in cfg").getStatus()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(ValidationResult.parseAnswer("Waiting for human review\nthe tools page").getStatus()).isEqualTo(ValidationStatus.REVIEW);
        ValidationResult result = ValidationResult.parseAnswer("{\"verdict\": \"Approved with comments\", \"findings\": [{\"file\": \"a.jsp\", \"note\": \"fine\"}]}");
        assertThat(result.getStatus()).isEqualTo(ValidationStatus.APPROVED);
        assertThat(result.getComments()).hasSize(1);
        assertThat(result.getComments().get(0)).contains("a.jsp").contains("fine");
    }

    @Test
    @DisplayName("an answer without a verdict is refused")
    void refusesNoVerdict() {
        assertThatThrownBy(() -> ValidationResult.parseAnswer("looks fine to me")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ValidationResult.parseAnswer("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ValidationResult.parseAnswer("pending")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ValidationResult.parseAnswer("{\"status\": \"unknown\"}")).hasMessageContaining("starts with \"{");
    }

    @Test
    @DisplayName("the status of a version is the worst of its checks: rejected, then review, then pending, then approved")
    void overall() {
        assertThat(ValidationStatus.overall(Arrays.asList(ValidationStatus.APPROVED, ValidationStatus.APPROVED))).isEqualTo(ValidationStatus.APPROVED);
        assertThat(ValidationStatus.overall(Arrays.asList(ValidationStatus.APPROVED, ValidationStatus.PENDING))).isEqualTo(ValidationStatus.PENDING);
        assertThat(ValidationStatus.overall(Arrays.asList(ValidationStatus.PENDING, ValidationStatus.REVIEW))).isEqualTo(ValidationStatus.REVIEW);
        assertThat(ValidationStatus.overall(Arrays.asList(ValidationStatus.REVIEW, ValidationStatus.REJECTED, ValidationStatus.APPROVED))).isEqualTo(ValidationStatus.REJECTED);
        assertThat(ValidationStatus.overall(Arrays.asList())).isEqualTo(ValidationStatus.PENDING);
    }

    @Test
    @DisplayName("a prompt check fills the placeholders of its prompt with the version")
    void promptPlaceholders() {
        String prompt = new CortexReviewValidator().prompt(new ModuleArtifact("store", "my-module", "1.2.0", "org.example",
                "https://downloads.example.com/my-module-1.2.0.jar", null));

        assertThat(prompt).contains("`my-module` version `1.2.0`").contains("https://downloads.example.com/my-module-1.2.0.jar")
                .contains("(no repository declared)").doesNotContain("${");
        assertThat(new SecurityScanValidator().validate(new ModuleArtifact("store", "m", "1", "g", "u", "s")).getStatus())
                .isEqualTo(ValidationStatus.REVIEW);
        assertThat(new SonarValidator().validate(null).getStatus()).isEqualTo(ValidationStatus.APPROVED);
    }
}
