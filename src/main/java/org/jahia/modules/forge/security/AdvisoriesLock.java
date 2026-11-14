package org.jahia.modules.forge.security;

import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.sites.JahiaSitesService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Locks store developers out of a site's security advisories folder
 * ({@code /sites/<site>/contents/security-advisories}), in the edit AND the live workspace, while they keep
 * their write access everywhere else.
 *
 * <p><b>Why a DENY of the role, per principal.</b> Store roles are granted on the site node, so the
 * {@code jcr:write_live} of {@code store-developer} covers the whole site. Jahia evaluates ACLs in
 * {@code AccessManagerUtils.recurseOnACPs}: walking from the node up to the root, it keys each ACE by
 * {@code principal + ":" + role}, keeps the closest one for each key, and a DENY only cancels that same key.
 * So, on the folder, a DENY of {@code store-developer} for every principal (user {@code u:} or group
 * {@code g:}) granted {@code store-developer} above it cancels exactly that grant below the folder. A
 * principal that also holds {@code store-administrator} or {@code site-administrator} keeps its rights
 * there: those grants have other keys ({@code u:x:store-administrator}), which the DENY does not touch.
 * A group grant needs a group DENY (key {@code g:devs:store-developer}), a direct user grant a user DENY,
 * which is why each principal is denied under its own key.
 *
 * <p><b>Which principals.</b> Those with an inherited GRANT of {@code store-developer} at the folder
 * ({@link JCRNodeWrapper#getAclEntries()} rows from an ancestor: the site node, and also {@code contents}),
 * in the edit OR the live workspace: the union, written to both workspaces, so a grant not yet published
 * to live (or made in live only) is never a gap. DENY rows of the role on the folder for a principal no
 * longer granted in either workspace are removed. A GRANT of the role made on the folder itself is an
 * administrator's explicit choice and is left alone.
 *
 * <p><b>Both workspaces.</b> ACL nodes reach live through publication, but the lock must hold in live at
 * once, so it is also written directly in live with a system session, as Jahia's own "live roles" do.
 * When the folder is published later, publication makes the live ACL equal to the edit one, which holds
 * the same DENY set. A folder not yet published is locked in edit only; its publication carries the lock.
 *
 * <p><b>When.</b> On activation for every site that has the folder; right after the store's
 * {@code forge { grantRole / revokeRole }} mutation ({@link #syncSite(String)}); and on any ACL change of
 * the site, its {@code contents} folder or the advisories folder, or the creation of the folder, in either
 * workspace ({@link AdvisoriesLockListener}). Idempotent: a folder already in sync is not written. Logs at
 * INFO what it adds and removes.
 */
@Component(service = AdvisoriesLock.class, immediate = true)
public class AdvisoriesLock {

    /** The role locked out of the advisories folder. */
    public static final String ROLE = "store-developer";

    private static final Logger LOGGER = LoggerFactory.getLogger(AdvisoriesLock.class);
    private static final List<String> WORKSPACES = Arrays.asList(Constants.EDIT_WORKSPACE, Constants.LIVE_WORKSPACE);
    private static final String GRANT = "GRANT";
    private static final String DENY = "DENY";
    private static final String REMOVE = "REMOVE";
    private static final String SITE_TYPE = "jnt:virtualsite";
    /** Our own saves fire the ACL listeners synchronously on this thread: those nested syncs are skipped. */
    private static final ThreadLocal<Boolean> SYNCING = new ThreadLocal<>();

    private final JCRTemplate jcrTemplate;

    public AdvisoriesLock() {
        this(null);
    }

    AdvisoriesLock(JCRTemplate jcrTemplate) {
        this.jcrTemplate = jcrTemplate;
    }

    /** Locks every site that has an advisories folder. Never fails the activation. */
    @Activate
    public void activate() {
        try {
            syncAllSites();
        } catch (RepositoryException | RuntimeException e) {
            LOGGER.error("Cannot lock the security advisories folders of the sites", e);
        }
    }

    /**
     * Brings one site's advisories lock in line with its {@code store-developer} grants.
     *
     * @param siteKey the site key
     * @throws RepositoryException when the ACLs cannot be read or written
     */
    public static void syncSite(String siteKey) throws RepositoryException {
        new AdvisoriesLock().sync(siteKey);
    }

    /**
     * @param siteKey the site key
     * @throws RepositoryException when the ACLs cannot be read or written
     */
    public void sync(String siteKey) throws RepositoryException {
        if (Boolean.TRUE.equals(SYNCING.get())) {
            return;
        }
        SYNCING.set(Boolean.TRUE);
        try {
            String folderPath = AdvisoriesFolder.path(siteKey);
            Set<String> granted = new TreeSet<>();
            for (String workspace : WORKSPACES) {
                granted.addAll(template().doExecuteWithSystemSessionAsUser(null, workspace, null,
                        session -> grantedPrincipals(session, folderPath)));
            }
            for (String workspace : WORKSPACES) {
                template().doExecuteWithSystemSessionAsUser(null, workspace, null, session -> {
                    apply(session, workspace, folderPath, granted);
                    return null;
                });
            }
        } finally {
            SYNCING.remove();
        }
    }

    void syncAllSites() throws RepositoryException {
        List<String> siteKeys = template().doExecuteWithSystemSessionAsUser(null, Constants.EDIT_WORKSPACE, null,
                AdvisoriesLock::siteKeys);
        for (String siteKey : siteKeys) {
            try {
                sync(siteKey);
            } catch (RepositoryException | RuntimeException e) {
                LOGGER.error("Cannot lock the security advisories folder of site {}", siteKey, e);
            }
        }
    }

    private static List<String> siteKeys(JCRSessionWrapper session) throws RepositoryException {
        List<String> keys = new ArrayList<>();
        JCRNodeIteratorWrapper sites = session.getNode(JahiaSitesService.SITES_JCR_PATH).getNodes();
        while (sites.hasNext()) {
            JCRNodeWrapper site = (JCRNodeWrapper) sites.nextNode();
            if (site.isNodeType(SITE_TYPE)) {
                keys.add(site.getName());
            }
        }
        return keys;
    }

    /** Principals granted the role by an ancestor of the folder, in this workspace. */
    private static Set<String> grantedPrincipals(JCRSessionWrapper session, String folderPath)
            throws RepositoryException {
        if (!session.nodeExists(folderPath)) {
            return Collections.emptySet();
        }
        Set<String> granted = new TreeSet<>();
        for (Map.Entry<String, List<String[]>> entry : aclEntries(session.getNode(folderPath)).entrySet()) {
            for (String[] row : entry.getValue()) {
                if (!folderPath.equals(row[0]) && GRANT.equals(row[1]) && ROLE.equals(row[2])) {
                    granted.add(entry.getKey());
                }
            }
        }
        return granted;
    }

    /** Principals the folder itself denies the role to. */
    private static Set<String> deniedPrincipals(JCRNodeWrapper folder, String folderPath) throws RepositoryException {
        Set<String> denied = new TreeSet<>();
        for (Map.Entry<String, List<String[]>> entry : aclEntries(folder).entrySet()) {
            for (String[] row : entry.getValue()) {
                if (folderPath.equals(row[0]) && DENY.equals(row[1]) && ROLE.equals(row[2])) {
                    denied.add(entry.getKey());
                }
            }
        }
        return denied;
    }

    private static Map<String, List<String[]>> aclEntries(JCRNodeWrapper node) throws RepositoryException {
        Map<String, List<String[]>> entries = node.getAclEntries();
        if (entries == null) {
            // getAclEntries() logs and answers null on a repository error: never read that as "no grant".
            throw new RepositoryException("Cannot read the ACL of " + node.getPath());
        }
        return entries;
    }

    private static void apply(JCRSessionWrapper session, String workspace, String folderPath, Set<String> granted)
            throws RepositoryException {
        if (!session.nodeExists(folderPath)) {
            return;
        }
        JCRNodeWrapper folder = session.getNode(folderPath);
        Set<String> denied = deniedPrincipals(folder, folderPath);
        Set<String> toDeny = new TreeSet<>(granted);
        toDeny.removeAll(denied);
        Set<String> toRelease = new TreeSet<>(denied);
        toRelease.removeAll(granted);
        if (toDeny.isEmpty() && toRelease.isEmpty()) {
            return;
        }
        for (String principal : toDeny) {
            folder.denyRoles(principal, Collections.singleton(ROLE));
        }
        for (String principal : toRelease) {
            folder.changeRoles(principal, Collections.singletonMap(ROLE, REMOVE));
        }
        session.save();
        LOGGER.info("Security advisories lock on {} ({} workspace): denied {} to {}, removed the denial for {}",
                folderPath, workspace, ROLE, toDeny, toRelease);
    }

    private JCRTemplate template() {
        return jcrTemplate != null ? jcrTemplate : JCRTemplate.getInstance();
    }
}
