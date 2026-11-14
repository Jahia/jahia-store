package org.jahia.modules.forge.security;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One value of an advisory's {@code fixedIn} or {@code introducedIn} property:
 * {@code [groupId:]component@version}, for example {@code graphql-dxm-provider@3.10.0},
 * {@code org.jahia.modules:graphql-dxm-provider@2.24.0} or {@code jahia-core@8.2.3.7}. Both properties
 * share the format. The same pattern is enforced by the CND constraint on each property; it is checked
 * again here because the feed must never emit a value a client could misread.
 * <p>
 * The version follows the rule of the security-updates client ({@code matching/Versions.isWellFormed}): a
 * number, then optionally one of {@code . - _ +} followed by a letter or digit, then letters, digits and those
 * separators; at most {@value #MAX_VERSION_LENGTH} characters; at most {@value #MAX_SEGMENT_DIGITS} digits
 * in each dot-separated number at its start. A value with ".." is refused as well (stricter than the client).
 */
public final class FixedInEntry {

    /** Longest version the client accepts. */
    static final int MAX_VERSION_LENGTH = 64;
    /** Most digits the client accepts in one leading numeric segment (so that it fits an int). */
    static final int MAX_SEGMENT_DIGITS = 9;

    private static final Pattern FORMAT = Pattern.compile(
            "^(?:([A-Za-z0-9_.-]+):)?([A-Za-z0-9_.-]+)@([0-9]+(?:[-._+][0-9A-Za-z][0-9A-Za-z._+-]*)?)$");

    private final String groupId;
    private final String component;
    private final String version;

    private FixedInEntry(String groupId, String component, String version) {
        this.groupId = groupId;
        this.component = component;
        this.version = version;
    }

    /**
     * @param value a raw {@code fixedIn} or {@code introducedIn} value
     * @return the parsed entry, or empty when the value is null, blank, malformed, contains ".." or has a
     *         version the security-updates client would refuse.
     */
    public static Optional<FixedInEntry> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        Matcher matcher = FORMAT.matcher(trimmed);
        if (!matcher.matches() || trimmed.contains("..") || !withinClientLimits(matcher.group(3))) {
            return Optional.empty();
        }
        return Optional.of(new FixedInEntry(matcher.group(1), matcher.group(2), matcher.group(3)));
    }

    /**
     * The client's limits that {@link #FORMAT} does not express: the length, and the digits of each number
     * in the leading run of digits and dots (the qualifier after it is not limited).
     */
    private static boolean withinClientLimits(String version) {
        if (version.length() > MAX_VERSION_LENGTH) {
            return false;
        }
        int digits = 0;
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '.') {
                digits = 0;
            } else if (c >= '0' && c <= '9') {
                digits++;
                if (digits > MAX_SEGMENT_DIGITS) {
                    return false;
                }
            } else {
                return true;
            }
        }
        return true;
    }

    /** @return the Maven groupId, or null when the entry names the component only */
    public String getGroupId() {
        return groupId;
    }

    /** @return the Store module name, or {@code jahia-core} for the platform */
    public String getComponent() {
        return component;
    }

    /**
     * @return for {@code fixedIn}, the first version of the component's line that contains the fix; for
     *         {@code introducedIn}, the first version of the component that has the flaw
     */
    public String getVersion() {
        return version;
    }
}
