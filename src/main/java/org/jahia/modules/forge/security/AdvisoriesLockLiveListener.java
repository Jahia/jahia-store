package org.jahia.modules.forge.security;

import org.jahia.api.Constants;
import org.jahia.services.content.DefaultEventListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/** {@link AdvisoriesLockListener} on the live workspace. */
@Component(service = DefaultEventListener.class, immediate = true)
public class AdvisoriesLockLiveListener extends AdvisoriesLockListener {

    @Activate
    public AdvisoriesLockLiveListener(@Reference AdvisoriesLock lock) {
        super(lock, Constants.LIVE_WORKSPACE);
    }
}
