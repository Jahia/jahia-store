package org.jahia.modules.forge.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jackrabbit.core.fs.FileSystem;
import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.JCRValueWrapper;
import org.jahia.services.sites.JahiaSitesService;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;

/**
 * Builds the {@code securityUpdates.json} feed that Jahia instances read to warn administrators about
 * updates that fix security issues.
 *
 * <p>Two sources, two trust levels:
 * <ul>
 *   <li>{@code advisories}: {@code jnt:forgeSecurityAdvisory} nodes under
 *       {@code /sites/<site>/contents/security-advisories}, read from the EDIT workspace with a system
 *       session and emitted only when fully published. The folder's ACL keeps store developers out
 *       (repository.xml), and reading the edit workspace is a second barrier: store developers hold
 *       {@code jcr:write_live} on the rest of the site, and nothing written in live is read here.</li>
 *   <li>{@code flaggedVersions}: module versions whose owner set {@code securityFix}, read from the LIVE
 *       workspace in the caller's own session, with the visibility rules of {@code moduleList.json}
 *       (published modules, published versions). Any store developer can set these flags, so clients must
 *       present them as reported by the module owner, not as Jahia advisories.</li>
 * </ul>
 *
 * <p>Errors fail loud. A repository error, or a published advisory that is not valid, raises an exception
 * and the view answers 503: a feed that silently drops an advisory tells every client "nothing to fix".
 */
public final class SecurityFeed {

    static final int SCHEMA_VERSION = 1;
    static final String ADVISORY_TYPE = "jnt:forgeSecurityAdvisory";
    static final String ADVISORIES_FOLDER = "security-advisories";
    static final List<String> SEVERITIES = Collections.unmodifiableList(Arrays.asList("low", "medium", "high", "critical"));

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityFeed.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODULE_TYPE = "jnt:forgeModule";
    private static final String PUBLISHED = "published";

    private final JCRTemplate jcrTemplate;
    private final PublicationCheck publicationCheck;

    public SecurityFeed(JCRTemplate jcrTemplate, PublicationCheck publicationCheck) {
        this.jcrTemplate = jcrTemplate;
        this.publicationCheck = publicationCheck;
    }

    /**
     * Entry point for the {@code contentFolder.securityUpdates} JSP view.
     *
     * @param repositoryNode the site's {@code modules-repository} folder, in the caller's live session
     * @return the feed as a JSON string
     * @throws RepositoryException when the repository cannot be read or a published advisory is invalid
     */
    public static String render(JCRNodeWrapper repositoryNode) throws RepositoryException {
        return new SecurityFeed(JCRTemplate.getInstance(), new JahiaPublicationCheck()).build(repositoryNode);
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
        String folderPath = JahiaSitesService.SITES_JCR_PATH + FileSystem.SEPARATOR + siteKey
                + FileSystem.SEPARATOR + "contents" + FileSystem.SEPARATOR + ADVISORIES_FOLDER;
        return jcrTemplate.doExecuteWithSystemSessionAsUser(null, Constants.EDIT_WORKSPACE, Locale.ENGLISH,
                session -> readAdvisories(session, folderPath));
    }

    private List<Map<String, Object>> readAdvisories(JCRSessionWrapper session, String folderPath)
            throws RepositoryException {
        JCRNodeWrapper folder;
        try {
            folder = session.getNode(folderPath);
        } catch (PathNotFoundException e) {
            LOGGER.info("No security advisories folder at {}: the feed lists no advisory", folderPath);
            return Collections.emptyList();
        }
        List<Map<String, Object>> advisories = new ArrayList<>();
        JCRNodeIteratorWrapper children = folder.getNodes();
        while (children.hasNext()) {
            JCRNodeWrapper child = (JCRNodeWrapper) children.nextNode();
            if (child.isNodeType(ADVISORY_TYPE) && publicationCheck.isFullyPublished(child)) {
                advisories.add(toAdvisory(child));
            }
        }
        return advisories;
    }

    private static Map<String, Object> toAdvisory(JCRNodeWrapper node) throws RepositoryException {
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

    /** Groups the {@code fixedIn} entries by component, keeping their order. Any invalid entry is an error. */
    private static List<Map<String, Object>> fixes(JCRNodeWrapper node) throws RepositoryException {
        Map<String, FixGroup> byComponent = new LinkedHashMap<>();
        for (String value : strings(node, "fixedIn")) {
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
        List<Map<String, Object>> fixes = new ArrayList<>();
        for (FixGroup group : byComponent.values()) {
            fixes.add(group.toJson());
        }
        return fixes;
    }

    private static RepositoryException invalid(JCRNodeWrapper node, String reason) {
        return new RepositoryException("Published security advisory " + node.getPath() + " is invalid: " + reason);
    }

    /** The fixed versions of one component, one per maintained line. */
    private static final class FixGroup {
        private final String component;
        private final List<String> fixedVersions = new ArrayList<>();
        private String groupId;

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
