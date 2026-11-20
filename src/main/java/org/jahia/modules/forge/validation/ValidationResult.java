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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** The keys an assistant uses for its verdict, the one the prompt asks for first. */
    private static final String[] STATUS_KEYS = {"status", "verdict", "result", "decision"};
    /** The keys an assistant uses for its findings, the one the prompt asks for first. */
    private static final String[] COMMENT_KEYS = {"comments", "findings", "comment", "reasons", "notes"};
    /** The verdict of a JSON that does not parse, such as one with a quote left unescaped in a comment. */
    private static final Pattern BROKEN_STATUS = Pattern.compile(
            "[\"']?(?:status|verdict|result|decision)[\"']?\\s*:\\s*[\"']?([A-Za-z][A-Za-z ]*?)\\s*[\"']?\\s*[,}]", Pattern.CASE_INSENSITIVE);
    private static final Pattern BROKEN_COMMENTS = Pattern.compile("[\"']?(?:comments|findings)[\"']?\\s*:\\s*\\[(.*)]", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** A boundary between two entries of a broken array: a closing quote, a comma, an opening quote. */
    private static final Pattern BROKEN_ENTRY_BOUNDARY = Pattern.compile("\"\\s*,\\s*\"");

    /**
     * The answer a human pastes for a check: the JSON {@code {"status": "approved", "comments":
     * ["..."]}} that the prompt asks for, or a text whose first line starts with the verdict
     * (approved, rejected or review, in the words of the assistant) and whose remaining lines
     * are the comments. A JSON that does not parse, such as one with a quote left unescaped in
     * a comment, still gives its verdict and its comments, split at the quote-comma-quote
     * boundaries of the array.
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
            String object = text.substring(brace, text.lastIndexOf('}') + 1);
            ValidationResult result;
            try {
                result = fromJson(new JSONObject(object));
            } catch (JSONException e) {
                result = fromBrokenJson(object);
            }
            if (result != null) {
                return result;
            }
        }
        String[] lines = text.split("\r?\n");
        String first = lines[0].trim();
        int colon = first.indexOf(':');
        ValidationStatus status = verdict(colon > 0 ? first.substring(0, colon) : first);
        String rest = colon > 0 ? first.substring(colon + 1) : "";
        if (status == null && colon < 0) {
            // "approved nothing to report": the verdict is the first word, the comment follows.
            String[] words = first.split("[\\s,.;-]+", 2);
            status = verdict(words[0]);
            rest = words.length > 1 ? words[1] : "";
        }
        if (status == null) {
            throw new IllegalArgumentException("The answer must start with approved, rejected or review, or be the JSON the prompt asks for; it starts with \""
                    + StringUtils.abbreviate(first, 60) + "\"");
        }
        List<String> comments = new ArrayList<>();
        if (StringUtils.isNotBlank(rest)) {
            comments.add(rest.trim());
        }
        for (int i = 1; i < lines.length; i++) {
            if (StringUtils.isNotBlank(lines[i])) {
                comments.add(lines[i].trim());
            }
        }
        return new ValidationResult(status, comments, null);
    }

    /**
     * The verdict in the words of an assistant: "approved", "Not approved", "APPROVED with
     * comments", "waiting for human review", "rejected"; null when the words carry none.
     */
    static ValidationStatus verdict(String words) {
        String lower = StringUtils.trimToEmpty(words).toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return null;
        }
        if (lower.contains("reject") || lower.contains("not approved") || lower.contains("refus") || lower.contains("fail")) {
            return ValidationStatus.REJECTED;
        }
        if (lower.contains("review") || lower.contains("wait")) {
            return ValidationStatus.REVIEW;
        }
        if (lower.contains("approv") || lower.contains("accept") || lower.contains("pass")) {
            return ValidationStatus.APPROVED;
        }
        return null;
    }

    /** The result of a parsed JSON answer, or null when no key carries a verdict. */
    private static ValidationResult fromJson(JSONObject json) {
        ValidationStatus status = null;
        for (String key : STATUS_KEYS) {
            status = verdict(json.optString(key, null));
            if (status != null) {
                break;
            }
        }
        if (status == null) {
            return null;
        }
        List<String> comments = new ArrayList<>();
        for (String key : COMMENT_KEYS) {
            if (!json.has(key)) {
                continue;
            }
            JSONArray array = json.optJSONArray(key);
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    Object entry = array.opt(i);
                    String comment = StringUtils.trimToNull(entry == null ? null : entry.toString());
                    if (comment != null) {
                        comments.add(comment);
                    }
                }
            } else if (StringUtils.isNotBlank(json.optString(key, null))) {
                comments.add(json.optString(key).trim());
            }
            break;
        }
        return new ValidationResult(status, comments, null);
    }

    /** The result of a JSON answer that does not parse, read with patterns, or null without a verdict. */
    private static ValidationResult fromBrokenJson(String object) {
        Matcher status = BROKEN_STATUS.matcher(object);
        ValidationStatus verdict = status.find() ? verdict(status.group(1)) : null;
        if (verdict == null) {
            return null;
        }
        List<String> comments = new ArrayList<>();
        Matcher array = BROKEN_COMMENTS.matcher(object);
        if (array.find()) {
            for (String entry : BROKEN_ENTRY_BOUNDARY.split(array.group(1))) {
                String comment = StringUtils.trimToNull(StringUtils.strip(entry.trim(), "\"'"));
                if (comment != null) {
                    comments.add(comment);
                }
            }
        }
        return new ValidationResult(verdict, comments, null);
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
