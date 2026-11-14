package org.jahia.modules.forge.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.JCRValueWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;

/**
 * Builds the {@code securityUpdates.json} feed that Jahia instances read to warn administrators about
 * updates that fix security issues.
 *
 * <p>Two sources, two trust levels:
 * <ul>
 *   <li>{@code advisories}: {@code jnt:forgeSecurityAdvisory} nodes under
 *       {@code /sites/<site>/contents/security-advisories} (created by the jahia-store-template site import),
 *       read from the LIVE workspace with a system session. An advisory is listed only when BOTH it is
 *       published with Jahia's publication (it exists in live and its live {@code j:published} is not false)
 *       AND its "Published in the security feed" box ({@code published}) is checked in that published
 *       version. So an edit made in the edit workspace changes nothing until the advisory is published
 *       again; unpublishing it in Jahia, or unchecking the box and publishing, withdraws it; an edit never
 *       withdraws it in between. Store developers cannot write in the folder ({@link AdvisoriesLock}), and
 *       a live node written in live rather than published from edit is ignored
 *       ({@link LiveChangeDetector}).</li>
 *   <li>{@code flaggedVersions}: module versions whose owner set {@code securityFix}, read from the LIVE
 *       workspace in the caller's own session, with the visibility rules of {@code moduleList.json}
 *       (published modules, published versions). Any store developer can set these flags, so clients must
 *       present them as reported by the module owner, not as Jahia advisories.</li>
 * </ul>
 *
 * <p>Errors fail loud. A repository error, or a published advisory that is not valid, raises an exception
 * and the view answers 503: a feed that silently drops an advisory tells every client "nothing to fix".
 * The one exception is the optional {@code introducedIn}: an invalid value is ignored for its component
 * and logged at WARN, so a typo there makes clients over-report (every version below a fix is affected)
 * instead of stopping the whole feed. The CND cannot enforce its cross-value rules at write time.
 */
public final class SecurityFeed {

    static final int SCHEMA_VERSION = 1;
    static final String ADVISORY_TYPE = "jnt:forgeSecurityAdvisory";
    static final List<String> SEVERITIES = Collections.unmodifiableList(Arrays.asList("low", "medium", "high", "critical"));

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityFeed.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODULE_TYPE = "jnt:forgeModule";
    private static final String PUBLISHED = "published";
    private static final String JAHIA_PUBLISHED = Constants.PUBLISHED;

    private static final String MARKED_FOR_DELETION = "jmix:markedForDeletion";
    private static final String FIXED_IN = "fixedIn";
    private static final String INTRODUCED_IN = "introducedIn";

    private final JCRTemplate jcrTemplate;
    private final Logger logger;

    public SecurityFeed(JCRTemplate jcrTemplate) {
        this(jcrTemplate, LOGGER);
    }

    /** For tests: the logger receives the warnings about ignored {@code introducedIn} values. */
    SecurityFeed(JCRTemplate jcrTemplate, Logger logger) {
        this.jcrTemplate = jcrTemplate;
        this.logger = logger;
    }

    /**
     * Entry point for the {@code contentFolder.securityUpdates} JSP view.
     *
     * @param repositoryNode the site's {@code modules-repository} folder, in the caller's live session
     * @return the feed as a JSON string
     * @throws RepositoryException when the repository cannot be read or a published advisory is invalid
     */
    public static String render(JCRNodeWrapper repositoryNode) throws RepositoryException {
        return new SecurityFeed(JCRTemplate.getInstance()).build(repositoryNode);
    }

    /**
     * @param repositoryNode the site's {@code modules-repository} folder, in the caller's live session
     * @return the feed as a JSON string
     * @throws RepositoryException when the repository cannot be read or a published advisory is invalid
     */
    public String build(JCRNodeWrapper repositoryNode) throws RepositoryException {
        String siteKey = repositoryNode.getResolveSite().getSiteKey();
        Map<String, Object> feed = new LinkedHashMap<>();
        feed.put("schemaVersion", SCHEMA_VERSION);
        feed.put("advisories", readAdvisories(siteKey));
        feed.put("flaggedVersions", readFlaggedVersions(repositoryNode));
        try {
            return JSON.writeValueAsString(feed);
        } catch (JsonProcessingException e) {
            throw new RepositoryException("Cannot serialize the security feed", e);
        }
    }

    // ------------------------------------------------------------------------------------ advisories

    private List<Map<String, Object>> readAdvisories(String siteKey) throws RepositoryException {
        String folderPath = AdvisoriesFolder.path(siteKey);
        return jcrTemplate.doExecuteWithSystemSessionAsUser(null, Constants.LIVE_WORKSPACE, Locale.ENGLISH,
                live -> jcrTemplate.doExecuteWithSystemSessionAsUser(null, Constants.EDIT_WORKSPACE, null,
                        edit -> readAdvisories(live, edit, folderPath)));
    }

    private List<Map<String, Object>> readAdvisories(JCRSessionWrapper live, JCRSessionWrapper edit, String folderPath)
            throws RepositoryException {
        JCRNodeWrapper folder;
        try {
            folder = live.getNode(folderPath);
        } catch (PathNotFoundException e) {
            logger.info("No published security advisories folder at {}: the feed lists no advisory", folderPath);
            return Collections.emptyList();
        }
        List<Map<String, Object>> advisories = new ArrayList<>();
        JCRNodeIteratorWrapper children = folder.getNodes();
        while (children.hasNext()) {
            JCRNodeWrapper child = (JCRNodeWrapper) children.nextNode();
            if (isListed(child) && !LiveChangeDetector.isWrittenInLive(child, edit)) {
                advisories.add(toAdvisory(child));
            }
        }
        return advisories;
    }

    /** An advisory, published in Jahia (live j:published not false), with its feed box checked, not deleted. */
    private static boolean isListed(JCRNodeWrapper node) throws RepositoryException {
        return node.isNodeType(ADVISORY_TYPE)
                && !node.isNodeType(MARKED_FOR_DELETION)
                && !(node.hasProperty(JAHIA_PUBLISHED) && !node.getProperty(JAHIA_PUBLISHED).getBoolean())
                && bool(node, PUBLISHED);
    }

    private Map<String, Object> toAdvisory(JCRNodeWrapper node) throws RepositoryException {
        String id = string(node, "advisoryId");
        String severity = string(node, "severity");
        if (id == null || !SEVERITIES.contains(severity)) {
            throw invalid(node, "missing advisoryId or unknown severity");
        }
        Map<String, Object> advisory = new LinkedHashMap<>();
        advisory.put("id", id);
        advisory.put("title", node.getDisplayableName());
        advisory.put("severity", severity);
        if (node.hasProperty("cvss")) {
            advisory.put("cvss", node.getProperty("cvss").getDouble());
        }
        advisory.put("cves", strings(node, "cves"));
        putIfPresent(advisory, "url", string(node, "advisoryUrl"));
        if (node.hasProperty("publishedDate")) {
            advisory.put("published", isoDate(node.getProperty("publishedDate").getDate()));
        }
        putIfPresent(advisory, "summary", string(node, "summary"));
        advisory.put("fixes", fixes(node));
        return advisory;
    }

    /**
     * Groups the {@code fixedIn} entries by component, keeping their order, and adds each component's
     * {@code introducedIn} version. Any invalid {@code fixedIn} entry is an error; an invalid
     * {@code introducedIn} entry is ignored (see {@link #addIntroducedVersions}).
     */
    private List<Map<String, Object>> fixes(JCRNodeWrapper node) throws RepositoryException {
        Map<String, FixGroup> byComponent = new LinkedHashMap<>();
        for (String value : strings(node, FIXED_IN)) {
            Optional<FixedInEntry> parsed = FixedInEntry.parse(value);
            if (!parsed.isPresent()) {
                throw invalid(node, "invalid fixedIn value");
            }
            FixedInEntry entry = parsed.get();
            byComponent.computeIfAbsent(entry.getComponent(), FixGroup::new).add(entry);
        }
        if (byComponent.isEmpty()) {
            throw invalid(node, "no fixedIn value");
        }
        addIntroducedVersions(node, byComponent);
        List<Map<String, Object>> fixes = new ArrayList<>();
        for (FixGroup group : byComponent.values()) {
            fixes.add(group.toJson());
        }
        return fixes;
    }

    /**
     * Sets the version that introduced the flaw, per component, from the optional {@code introducedIn}
     * values. A value is ignored, with one WARN naming the advisory and the reason, when it is malformed,
     * names a component that has no {@code fixedIn} value, or gives a groupId other than the one of that
     * component's {@code fixedIn} values (including when those give none). When several values name the
     * same component, all of them are ignored: the feed does not guess. An ignored value leaves the
     * component without {@code introducedVersion}, so clients report every version below its fixes.
     */
    private void addIntroducedVersions(JCRNodeWrapper node, Map<String, FixGroup> byComponent)
            throws RepositoryException {
        Map<String, FixedInEntry> single = new LinkedHashMap<>();
        Set<String> repeated = new LinkedHashSet<>();
        for (String value : strings(node, INTRODUCED_IN)) {
            Optional<FixedInEntry> parsed = FixedInEntry.parse(value);
            if (!parsed.isPresent()) {
                ignoreIntroducedIn(node, "malformed introducedIn value");
                continue;
            }
            String component = parsed.get().getComponent();
            if (!byComponent.containsKey(component)) {
                ignoreIntroducedIn(node, "introducedIn names " + component + ", which has no fixedIn value");
            } else if (repeated.contains(component) || single.remove(component) != null) {
                repeated.add(component);
            } else {
                single.put(component, parsed.get());
            }
        }
        for (String component : repeated) {
            ignoreIntroducedIn(node, "more than one introducedIn value for " + component);
        }
        for (FixedInEntry entry : single.values()) {
            FixGroup group = byComponent.get(entry.getComponent());
            if (entry.getGroupId() != null && !entry.getGroupId().equals(group.groupId)) {
                ignoreIntroducedIn(node, "the introducedIn groupId of " + group.component + " differs from its fixedIn groupId");
            } else {
                group.introducedVersion = entry.getVersion();
            }
        }
    }

    private void ignoreIntroducedIn(JCRNodeWrapper node, String reason) {
        logger.warn("Security advisory {}: introducedIn value ignored, clients will report every version below the fixes: {}",
                node.getPath(), reason);
    }

    private static RepositoryException invalid(JCRNodeWrapper node, String reason) {
        return new RepositoryException("Published security advisory " + node.getPath() + " is invalid: " + reason);
    }

    /** The fixed versions of one component, one per maintained line, and the version that introduced the flaw. */
    private static final class FixGroup {
        private final String component;
        private final List<String> fixedVersions = new ArrayList<>();
        private String groupId;
        private String introducedVersion;

        FixGroup(String component) {
            this.component = component;
        }

        void add(FixedInEntry entry) {
            if (groupId == null) {
                groupId = entry.getGroupId();
            }
            fixedVersions.add(entry.getVersion());
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("component", component);
            putIfPresent(json, "groupId", groupId);
            json.put("fixedVersions", fixedVersions);
            putIfPresent(json, "introducedVersion", introducedVersion);
            return json;
        }
    }

    // ------------------------------------------------------------------------------ flagged versions

    private static List<Map<String, Object>> readFlaggedVersions(JCRNodeWrapper repositoryNode)
            throws RepositoryException {
        String statement = "SELECT * FROM [jnt:forgeModuleVersion] AS v WHERE ISDESCENDANTNODE(v, '"
                + repositoryNode.getPath().replace("'", "''")
                + "') AND v.[securityFix] = true AND v.[published] = true";
        JCRNodeIteratorWrapper versions = repositoryNode.getSession().getWorkspace().getQueryManager()
                .createQuery(statement, Query.JCR_SQL2).execute().getNodes();
        List<Map<String, Object>> flagged = new ArrayList<>();
        while (versions.hasNext()) {
            JCRNodeWrapper version = (JCRNodeWrapper) versions.nextNode();
            JCRNodeWrapper module = version.getParent();
            if (module.isNodeType(MODULE_TYPE) && bool(module, PUBLISHED)) {
                flagged.add(toFlaggedVersion(module, version));
            }
        }
        return flagged;
    }

    private static Map<String, Object> toFlaggedVersion(JCRNodeWrapper module, JCRNodeWrapper version)
            throws RepositoryException {
        Map<String, Object> entry = new LinkedHashMap<>();
        putIfPresent(entry, "groupId", string(module, "groupId"));
        entry.put("name", module.getName());
        entry.put("version", string(version, "versionNumber"));
        String severity = string(version, "securitySeverity");
        if (SEVERITIES.contains(severity)) {
            entry.put("severity", severity);
        }
        entry.put("references", strings(version, "securityReferences"));
        putIfPresent(entry, "note", string(version, "securityNote"));
        return entry;
    }

    // --------------------------------------------------------------------------------------- helpers

    private static String string(JCRNodeWrapper node, String name) throws RepositoryException {
        if (!node.hasProperty(name)) {
            return null;
        }
        String value = node.getProperty(name).getString();
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static boolean bool(JCRNodeWrapper node, String name) throws RepositoryException {
        return node.hasProperty(name) && node.getProperty(name).getBoolean();
    }

    private static List<String> strings(JCRNodeWrapper node, String name) throws RepositoryException {
        List<String> values = new ArrayList<>();
        if (!node.hasProperty(name)) {
            return values;
        }
        JCRPropertyWrapper property = node.getProperty(name);
        if (!property.isMultiple()) {
            values.add(property.getString());
            return values;
        }
        for (JCRValueWrapper value : property.getValues()) {
            values.add(value.getString());
        }
        return values;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String isoDate(Calendar calendar) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(calendar.getTime());
    }
}
