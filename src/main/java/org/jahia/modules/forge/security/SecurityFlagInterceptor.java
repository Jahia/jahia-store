package org.jahia.modules.forge.security;

import org.jahia.api.Constants;
import org.jahia.modules.forge.actions.ActionSecurityUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRStoreService;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.interceptor.BaseInterceptor;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.usermanager.JahiaUser;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.time.Clock;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * JSTORE-SEC-017: only the owner of a module, or a store administrator, writes the security flags of its
 * versions, and every accepted write records who made it and when.
 *
 * <p>The flags ({@code securityFix}, {@code securitySeverity}, {@code securityReferences},
 * {@code securityNote} on {@code jnt:forgeModuleVersion}) are a claim that {@code securityUpdates.json}
 * republishes to every Jahia instance ({@link SecurityFeed}). The {@code store-developer} role holds
 * {@code jcr:write_live} on the whole site, and JCR has no per-property ACL, so the JCR alone would let any
 * developer flag, change or unflag the versions of any module. This Jahia property interceptor runs on every
 * write of these properties made through Jahia's content API (GraphQL mutations, jContent, the storefront,
 * copy and paste) and refuses it unless the caller:
 * <ul>
 *   <li>holds {@code jahiaForgeModerateModule} on the module (the {@code store-administrator} role), or</li>
 *   <li>holds the {@code owner} role on the module node, as a direct user grant, which is what the upload
 *       grants to the developer who created the module ({@code OwnerRoleGrants}). The owner of a version node
 *       only (a second uploader, SEC-381) is not the owner of the module.</li>
 * </ul>
 * System sessions (Jahia publication, imports) pass through untouched, so a flag published from the edit
 * workspace keeps the attribution it was written with.
 *
 * <p>An accepted write stamps the version with {@code securityFlaggedBy} (the caller's username) and
 * {@code securityFlaggedDate} (now). The stamps cannot be chosen: a write of either by anyone but a system
 * session is authorized like a flag and its value replaced by the caller's username or the current time,
 * and only a system session removes them. The feed lists a flag only with its stamp.
 *
 * <p>The owner role is read from the module's ACL in a system session of the caller's workspace, because a
 * store developer may not be allowed to read the ACL nodes, and so that only saved grants count.
 */
@Component(service = SecurityFlagInterceptor.class, immediate = true)
public class SecurityFlagInterceptor extends BaseInterceptor {

    static final String VERSION_TYPE = "jnt:forgeModuleVersion";
    static final String FLAGGED_BY = "securityFlaggedBy";
    static final String FLAGGED_DATE = "securityFlaggedDate";
    static final String MODERATE_PERMISSION = "jahiaForgeModerateModule";
    static final Set<String> FLAG_PROPERTIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "securityFix", "securitySeverity", "securityReferences", "securityNote")));
    static final Set<String> STAMP_PROPERTIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            FLAGGED_BY, FLAGGED_DATE)));

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityFlagInterceptor.class);
    private static final String OWNER_ROLE = "owner";
    private static final String USER_PRINCIPAL_PREFIX = "u:";
    private static final String GRANT = "GRANT";
    private static final String DENY = "DENY";
    private static final String REFUSED = "Only the owner of this module or a store administrator can change "
            + "the security information of its versions";

    private final JCRTemplate jcrTemplate;
    private final Clock clock;
    private JCRStoreService store;

    public SecurityFlagInterceptor() {
        this(JCRTemplate.getInstance(), Clock.systemUTC());
    }

    SecurityFlagInterceptor(JCRTemplate jcrTemplate, Clock clock) {
        this.jcrTemplate = jcrTemplate;
        this.clock = clock;
    }

    @Activate
    void activate() {
        register(JCRStoreService.getInstance());
    }

    /** Adds this interceptor to Jahia's chain, replacing an equal instance left by a previous activation. */
    void register(JCRStoreService jcrStoreService) {
        this.store = jcrStoreService;
        jcrStoreService.removeInterceptor(this);
        jcrStoreService.addInterceptor(this);
    }

    @Deactivate
    void deactivate() {
        if (store != null) {
            store.removeInterceptor(this);
            store = null;
        }
    }

    @Override
    public boolean canApplyOnProperty(JCRNodeWrapper node, ExtendedPropertyDefinition definition)
            throws RepositoryException {
        String name = definition.getName();
        return (FLAG_PROPERTIES.contains(name) || STAMP_PROPERTIES.contains(name)) && node.isNodeType(VERSION_TYPE);
    }

    @Override
    public Value beforeSetValue(JCRNodeWrapper node, String name, ExtendedPropertyDefinition definition,
                                Value originalValue) throws RepositoryException {
        JCRSessionWrapper session = node.getSession();
        if (session.isSystem()) {
            return originalValue;
        }
        String writer = authorizedWriter(node, name);
        if (STAMP_PROPERTIES.contains(name)) {
            if (originalValue == null) {
                throw refused(node, name, writer);
            }
            return FLAGGED_BY.equals(name)
                    ? session.getValueFactory().createValue(writer)
                    : session.getValueFactory().createValue(now());
        }
        stamp(node, writer);
        return originalValue;
    }

    @Override
    public Value[] beforeSetValues(JCRNodeWrapper node, String name, ExtendedPropertyDefinition definition,
                                   Value[] originalValues) throws RepositoryException {
        if (node.getSession().isSystem()) {
            return originalValues;
        }
        String writer = authorizedWriter(node, name);
        if (STAMP_PROPERTIES.contains(name)) {
            // The stamps are single-valued: nothing legitimate writes them as a list.
            throw refused(node, name, writer);
        }
        stamp(node, writer);
        return originalValues;
    }

    @Override
    public void beforeRemove(JCRNodeWrapper node, String name, ExtendedPropertyDefinition definition)
            throws RepositoryException {
        if (node.getSession().isSystem()) {
            return;
        }
        String writer = authorizedWriter(node, name);
        if (STAMP_PROPERTIES.contains(name)) {
            throw refused(node, name, writer);
        }
        stamp(node, writer);
    }

    /**
     * The caller's username when the caller may write the security information of this version's module.
     *
     * @throws AccessDeniedException otherwise
     */
    private String authorizedWriter(JCRNodeWrapper version, String property) throws RepositoryException {
        JahiaUser user = version.getSession().getUser();
        String username = user == null ? null : user.getUsername();
        if (username == null || Constants.GUEST_USERNAME.equals(username)) {
            throw refused(version, property, username);
        }
        JCRNodeWrapper module;
        try {
            module = version.getParent();
        } catch (ItemNotFoundException | AccessDeniedException e) {
            throw refused(version, property, username);
        }
        if (module.hasPermission(MODERATE_PERMISSION) || ownsModule(module, username, version.getSession())) {
            return username;
        }
        throw refused(version, property, username);
    }

    private boolean ownsModule(JCRNodeWrapper module, String username, JCRSessionWrapper callerSession)
            throws RepositoryException {
        String identifier = module.getIdentifier();
        String workspace = callerSession.getWorkspace().getName();
        Boolean owner = jcrTemplate.doExecuteWithSystemSessionAsUser(null, workspace, null, system -> {
            try {
                return isOwner(system.getNodeByIdentifier(identifier).getAclEntries(), username);
            } catch (ItemNotFoundException e) {
                // Not saved yet: nobody owns it until the upload's owner grant is applied.
                return false;
            }
        });
        return Boolean.TRUE.equals(owner);
    }

    /**
     * True when the user's own entries in {@code aclEntries} (a node's {@code getAclEntries()}: rows
     * {@code [path, GRANT|DENY|EXTERNAL, role]}, the node's own first, then each ancestor's) grant the
     * {@code owner} role: the nearest GRANT or DENY of that role decides.
     */
    static boolean isOwner(Map<String, List<String[]>> aclEntries, String username) {
        List<String[]> rows = aclEntries == null ? null : aclEntries.get(USER_PRINCIPAL_PREFIX + username);
        if (rows == null) {
            return false;
        }
        for (String[] row : rows) {
            if (row.length > 2 && OWNER_ROLE.equals(row[2])) {
                if (GRANT.equals(row[1])) {
                    return true;
                }
                if (DENY.equals(row[1])) {
                    return false;
                }
            }
        }
        return false;
    }

    /** Records who wrote the security information and when; re-enters this interceptor, which fixes the values. */
    private void stamp(JCRNodeWrapper version, String writer) throws RepositoryException {
        version.setProperty(FLAGGED_BY, writer);
        version.setProperty(FLAGGED_DATE, now());
    }

    private Calendar now() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(clock.millis());
        return calendar;
    }

    private static AccessDeniedException refused(JCRNodeWrapper version, String property, String username) {
        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn("JSTORE-SEC-017: refused a write of {} on {} by {} (only the module owner or a store "
                            + "administrator writes the security information, and only Jahia removes its stamps)",
                    property, ActionSecurityUtils.sanitizeForLog(version.getPath()),
                    ActionSecurityUtils.sanitizeForLog(String.valueOf(username)));
        }
        return new AccessDeniedException(REFUSED);
    }
}
