package org.jahia.modules.forge.security;

import org.apache.jackrabbit.core.fs.FileSystem;
import org.jahia.services.content.DefaultEventListener;
import org.jahia.services.sites.JahiaSitesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps {@link AdvisoriesLock} in sync when a site's ACL changes by any means (jContent permissions, a
 * {@code grantRoles} from a script or GraphQL, an import) and when an advisories folder is created.
 *
 * <p>Jahia binds a {@link DefaultEventListener} to ONE workspace ({@link #getWorkspace()}) and keeps one
 * listener per class and workspace ({@code TemplatePackageRegistry.handleJCREventListener}), hence one
 * subclass per workspace: {@link AdvisoriesLockEditListener} and {@link AdvisoriesLockLiveListener}. Jahia
 * dispatches these events synchronously at the end of the save, on the saving thread
 * ({@code JCRObservationManager.consume}); the lock's own writes fire them again, and
 * {@link AdvisoriesLock#sync(String)} skips that nested call. Listeners are not run during a publication
 * (not {@code availableDuringPublish}): the lock is written to both workspaces whenever an ACL changes in
 * either, so a publication only copies a lock that is already there.
 *
 * <p>Relevant events: any change under the {@code j:acl} of the site node, of its {@code contents} folder or
 * of the advisories folder itself (so a removed DENY is put back), and the creation of the advisories
 * folder. Never throws: errors are logged at ERROR.
 */
public abstract class AdvisoriesLockListener extends DefaultEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdvisoriesLockListener.class);
    private static final String SITES = JahiaSitesService.SITES_JCR_PATH;
    private static final String SEP = FileSystem.SEPARATOR;
    /** {@code /sites/<key>[/contents[/security-advisories]]/j:acl[/...]} */
    private static final Pattern ACL_CHANGE = Pattern.compile(Pattern.quote(SITES + SEP) + "([^/]+)"
            + "(?:" + Pattern.quote(SEP + AdvisoriesFolder.CONTENTS)
            + "(?:" + Pattern.quote(SEP + AdvisoriesFolder.NAME) + ")?)?"
            + Pattern.quote(SEP + "j:acl") + "(?:/.*)?");
    /** {@code /sites/<key>/contents/security-advisories} */
    private static final Pattern FOLDER = Pattern.compile(Pattern.quote(SITES + SEP) + "([^/]+)"
            + Pattern.quote(SEP + AdvisoriesFolder.CONTENTS + SEP + AdvisoriesFolder.NAME));

    private final AdvisoriesLock lock;

    protected AdvisoriesLockListener(AdvisoriesLock lock, String workspace) {
        this.lock = lock;
        setWorkspace(workspace);
    }

    @Override
    public int getEventTypes() {
        return Event.NODE_ADDED | Event.NODE_REMOVED | Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED
                | Event.PROPERTY_REMOVED;
    }

    @Override
    public String getPath() {
        return SITES;
    }

    @Override
    public boolean isDeep() {
        return true;
    }

    @Override
    public void onEvent(EventIterator events) {
        Set<String> siteKeys = new LinkedHashSet<>();
        while (events.hasNext()) {
            Event event = events.nextEvent();
            try {
                String siteKey = siteKeyToSync(event.getType(), event.getPath());
                if (siteKey != null) {
                    siteKeys.add(siteKey);
                }
            } catch (RepositoryException | RuntimeException e) {
                LOGGER.error("Cannot read an event for the security advisories lock", e);
            }
        }
        for (String siteKey : siteKeys) {
            try {
                lock.sync(siteKey);
            } catch (RepositoryException | RuntimeException e) {
                LOGGER.error("Cannot update the security advisories lock of site {} ({} workspace)",
                        siteKey, getWorkspace(), e);
            }
        }
    }

    /**
     * @param type an event type
     * @param path the event path
     * @return the key of the site whose lock this event may change, or null
     */
    static String siteKeyToSync(int type, String path) {
        if (path == null) {
            return null;
        }
        Matcher acl = ACL_CHANGE.matcher(path);
        if (acl.matches()) {
            return acl.group(1);
        }
        Matcher folder = FOLDER.matcher(path);
        if (type == Event.NODE_ADDED && folder.matches()) {
            return folder.group(1);
        }
        return null;
    }
}
