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
 */
public final class FixedInEntry {

    private static final Pattern FORMAT =
            Pattern.compile("^(?:([A-Za-z0-9_.-]+):)?([A-Za-z0-9_.-]+)@([0-9][A-Za-z0-9_.-]*)$");

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
     * @return the parsed entry, or empty when the value is null, blank, malformed or contains "..".
     */
    public static Optional<FixedInEntry> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        Matcher matcher = FORMAT.matcher(trimmed);
        if (!matcher.matches() || trimmed.contains("..")) {
            return Optional.empty();
        }
        return Optional.of(new FixedInEntry(matcher.group(1), matcher.group(2), matcher.group(3)));
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
