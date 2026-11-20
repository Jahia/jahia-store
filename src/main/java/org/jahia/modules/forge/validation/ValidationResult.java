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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What a check says of a version: its status, its comments, and, when it waits for a human, the
 * instructions that human follows to answer. Immutable.
 */
public final class ValidationResult {

    private final ValidationStatus status;
    private final List<String> comments;
    private final String prompt;

    private ValidationResult(ValidationStatus status, List<String> comments, String prompt) {
        this.status = status;
        this.comments = comments == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(comments));
        this.prompt = StringUtils.trimToNull(prompt);
    }

    public static ValidationResult approved(String... comments) {
        return new ValidationResult(ValidationStatus.APPROVED, Arrays.asList(comments), null);
    }

    public static ValidationResult rejected(String... comments) {
        return new ValidationResult(ValidationStatus.REJECTED, Arrays.asList(comments), null);
    }

    /** Waiting for a human, who follows the prompt and answers as {@link #parseAnswer} reads it. */
    public static ValidationResult review(String prompt, String... comments) {
        return new ValidationResult(ValidationStatus.REVIEW, Arrays.asList(comments), prompt);
    }

    public static ValidationResult of(ValidationStatus status, List<String> comments) {
        return new ValidationResult(status, comments, null);
    }

    /**
     * The answer a human pastes for a check: the JSON {@code {"status": "approved", "comments":
     * ["..."]}} that the prompt asks for, or a text whose first word is the verdict (approved,
     * rejected or review) and whose remaining lines are the comments.
     *
     * @throws IllegalArgumentException when no verdict can be read
     */
    public static ValidationResult parseAnswer(String answer) {
        String text = StringUtils.trimToEmpty(answer);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("The answer is empty");
        }
        int brace = text.indexOf('{');
        if (brace >= 0 && text.lastIndexOf('}') > brace) {
            try {
                JSONObject json = new JSONObject(text.substring(brace, text.lastIndexOf('}') + 1));
                ValidationStatus status = ValidationStatus.of(json.optString("status", null));
                if (status != null) {
                    List<String> comments = new ArrayList<>();
                    JSONArray array = json.optJSONArray("comments");
                    if (array != null) {
                        for (int i = 0; i < array.length(); i++) {
                            String comment = StringUtils.trimToNull(array.optString(i, null));
                            if (comment != null) {
                                comments.add(comment);
                            }
                        }
                    } else if (StringUtils.isNotBlank(json.optString("comments", null))) {
                        comments.add(json.optString("comments").trim());
                    }
                    return new ValidationResult(status, comments, null);
                }
            } catch (JSONException e) {
                // Not JSON after all: read it as text.
            }
        }
        String[] lines = text.split("\r?\n");
        String[] firstWords = lines[0].trim().split("[\\s:,.;-]+", 2);
        ValidationStatus status = ValidationStatus.of(firstWords[0]);
        if (status == null || status == ValidationStatus.PENDING) {
            throw new IllegalArgumentException("The answer must start with approved, rejected or review, or be the JSON the prompt asks for");
        }
        List<String> comments = new ArrayList<>();
        if (firstWords.length > 1 && StringUtils.isNotBlank(firstWords[1])) {
            comments.add(firstWords[1].trim());
        }
        for (int i = 1; i < lines.length; i++) {
            if (StringUtils.isNotBlank(lines[i])) {
                comments.add(lines[i].trim());
            }
        }
        return new ValidationResult(status, comments, null);
    }

    public ValidationStatus getStatus() {
        return status;
    }

    public List<String> getComments() {
        return comments;
    }

    /** The instructions for the human who answers, or null when the check decided by itself. */
    public String getPrompt() {
        return prompt;
    }
}
