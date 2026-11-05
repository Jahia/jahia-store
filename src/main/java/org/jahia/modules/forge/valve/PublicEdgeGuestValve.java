package org.jahia.modules.forge.valve;

import javax.servlet.http.HttpServletRequest;
import org.jahia.modules.forge.cache.EdgeOriginCacheKeyPartGenerator;
import org.jahia.params.valves.AuthValveContext;
import org.jahia.params.valves.BaseAuthValve;
import org.jahia.pipelines.Pipeline;
import org.jahia.pipelines.PipelineException;
import org.jahia.pipelines.valves.ValveContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes every request that arrived through the public CDN anonymous, whatever credentials
 * or session the browser presents.
 *
 * <p>Background (SUPPORT-687): the store can be deployed so that authoring happens only
 * from a trusted network, with the public internet limited to anonymous, read-only use.
 * A reverse proxy in front marks each request with its originating network. This valve is
 * the part of that model that only Jahia can implement: making a request anonymous
 * regardless of what session it carries.
 *
 * <p>It cannot be done in the proxy. Stripping the session cookie there would be the
 * obvious move, but <b>Jahia issues a JSESSIONID to guests too</b>, so such a rule cannot
 * tell an authenticated session from an ordinary anonymous one - it would strip every
 * visitor's session, Jahia would mint a fresh one per request, and previously-cacheable
 * pages would start carrying a Set-Cookie. Deciding "this request is not authenticated"
 * requires knowing whether the session is authenticated, which only Jahia knows.
 *
 * <p>So it is decided here, at the front of the authentication pipeline. Terminating the
 * pipeline without calling {@link ValveContext#invokeNext(Object)} means no valve ever
 * runs - not the session valve, not the cookie valve, not basic auth, not token auth - and
 * the request is therefore guest. Nothing is mutated: the session object is left exactly as
 * it was, so the same session used from the VPN is still authenticated there. That is the
 * decisive advantage over removing {@code Constants.SESSION_USER} from a servlet filter,
 * which would log the user out everywhere at once.
 *
 * <p>The header is set by the proxy, which deletes any inbound copy first, so a client
 * cannot present it. An absent header means privileged - local development, CI and the
 * Cypress harness run with no proxy in front and must behave exactly as before, which is
 * why this check fails open.
 *
 * <p>Registered as a plain OSGi component that inserts itself at position 0 of the
 * authentication pipeline. {@code service = {}} because nothing looks this up as a
 * service - it registers itself with the pipeline in {@link #start()}.
 */
@Component(service = {}, immediate = true)
public final class PublicEdgeGuestValve extends BaseAuthValve {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicEdgeGuestValve.class);

    /** Must be unique in the pipeline; {@code removeValve} matches on it. */
    public static final String AUTH_VALVE_ID = "forgePublicEdgeGuestValve";

    @Reference(target = "(type=authentication)")
    private Pipeline authPipeline;

    /**
     * Position 0: ahead of every authenticating valve, so none of them gets the chance to
     * restore an identity for a public request.
     */
    @Activate
    public void start() {
        setId(AUTH_VALVE_ID);
        removeValve(authPipeline);
        addValve(authPipeline, 0, null, null);
        LOGGER.info("{} registered at the front of the authentication pipeline", AUTH_VALVE_ID);
    }

    @Deactivate
    public void stop() {
        removeValve(authPipeline);
    }

    @Override
    public void invoke(Object context, ValveContext valveContext) throws PipelineException {
        AuthValveContext authContext = (AuthValveContext) context;
        HttpServletRequest request = authContext.getRequest();

        if (EdgeOriginCacheKeyPartGenerator.isPublicEdge(request)) {
            // This request must be anonymous, but the SESSION must not change: the same
            // session is the operator's, and they are expected to still be signed in the
            // moment they are back on the trusted network. Say explicitly that nothing
            // from this request's (non-)authentication may be written back, so a guest
            // resolution cannot be persisted over the stored user and turn a per-request
            // decision into a permanent logout.
            authContext.setShouldStoreAuthInSession(false);

            // Terminate the pipeline. No valve authenticates, so the request is guest.
            // Deliberately no VALVE_RESULT is set: this is not a failed login, it is a
            // request that may not be authenticated at all, and marking it BAD_PASSWORD
            // would surface a spurious credentials error on an ordinary public page.
            return;
        }

        valveContext.invokeNext(context);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PublicEdgeGuestValve)) {
            return false;
        }
        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
