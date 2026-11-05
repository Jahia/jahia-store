package org.jahia.modules.forge.actions;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jcr.Binary;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S16 / U1 — the stored-XSS neutralization DECISION body of {@code ForgeMediaMimeListener}
 * ({@code validateInSession}, private static, invoked by reflection). The pure path pre-filter is
 * covered by {@link ForgeMediaMimeListenerTest} and the byte sniffing by
 * {@link MagicByteImageValidatorTest} (used here for real):
 * <ul>
 *   <li>Rule A (anywhere under a forge element): a script-capable declared mime OR markup bytes ->
 *       the file is removed.</li>
 *   <li>Rule B (icon/screenshots): a non-raster upload is removed; a mismatched declared mime is
 *       pinned to the detected raster type (not removed).</li>
 *   <li>A legitimate PNG under {@code icon} is left untouched.</li>
 * </ul>
 */
class ForgeMediaMimeListenerDecisionTest {

    private static final String REPO = "/sites/s/contents/modules-repository/";
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    /** A jnt:file "icon/f" under a jmix:forgeElement, whose jnt:resource carries data + a mime. */
    private static class Fixture {
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        final JCRNodeWrapper resource = mock(JCRNodeWrapper.class);
        final JCRNodeWrapper file = mock(JCRNodeWrapper.class);
        final String path = REPO + "org/m/icon/f/jcr:content";

        Fixture(String declaredMime, byte[] data, String parentFolderName) throws Exception {
            JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
            JCRNodeWrapper forgeEl = mock(JCRNodeWrapper.class);

            when(session.nodeExists(path)).thenReturn(true);
            when(session.getNode(path)).thenReturn(resource);
            when(resource.isNodeType("jnt:resource")).thenReturn(true);
            when(resource.hasProperty("jcr:data")).thenReturn(true);
            when(resource.getParent()).thenReturn(file);

            when(file.isNodeType("jnt:file")).thenReturn(true);
            when(file.isNodeType("jmix:forgeElement")).thenReturn(false);
            when(file.getPath()).thenReturn(REPO + "org/m/icon/f");
            when(file.getParent()).thenReturn(folder);

            when(folder.getName()).thenReturn(parentFolderName);
            when(folder.isNodeType("jmix:forgeElement")).thenReturn(false);
            when(folder.getPath()).thenReturn(REPO + "org/m/icon");
            when(folder.getParent()).thenReturn(forgeEl);

            when(forgeEl.isNodeType("jmix:forgeElement")).thenReturn(true);

            // Declared mime property
            if (declaredMime != null) {
                JCRPropertyWrapper mimeProp = mock(JCRPropertyWrapper.class);
                when(mimeProp.getString()).thenReturn(declaredMime);
                when(resource.hasProperty("jcr:mimeType")).thenReturn(true);
                when(resource.getProperty("jcr:mimeType")).thenReturn(mimeProp);
            } else {
                when(resource.hasProperty("jcr:mimeType")).thenReturn(false);
            }

            // Binary data
            JCRPropertyWrapper dataProp = mock(JCRPropertyWrapper.class);
            Binary binary = mock(Binary.class);
            when(binary.getStream()).thenReturn(new ByteArrayInputStream(data));
            when(dataProp.getBinary()).thenReturn(binary);
            when(resource.getProperty("jcr:data")).thenReturn(dataProp);
        }
    }

    private static void validate(Fixture f) throws Exception {
        Method m = ForgeMediaMimeListener.class.getDeclaredMethod(
                "validateInSession", JCRSessionWrapper.class, String.class);
        m.setAccessible(true);
        m.invoke(null, f.session, f.path);
    }

    @Test
    @DisplayName("Rule A: a declared script-capable mime (image/svg+xml) is removed")
    void ruleA_scriptCapableMime_removed() throws Exception {
        Fixture f = new Fixture("image/svg+xml", PNG, "icon");
        validate(f);
        verify(f.file, times(1)).remove();
        verify(f.session).save();
    }

    @Test
    @DisplayName("Rule B: a non-raster upload in icon is removed")
    void ruleB_nonRaster_removed() throws Exception {
        Fixture f = new Fixture("image/png", "just plain text, not an image".getBytes(), "icon");
        validate(f);
        verify(f.file, times(1)).remove();
        verify(f.session).save();
    }

    @Test
    @DisplayName("Rule B: a mismatched declared mime is pinned to the detected raster type (not removed)")
    void ruleB_mismatch_pinned() throws Exception {
        Fixture f = new Fixture("image/gif", PNG, "icon");
        validate(f);
        verify(f.resource).setProperty("jcr:mimeType", "image/png");
        verify(f.session).save();
        verify(f.file, never()).remove();
    }

    @Test
    @DisplayName("legit PNG under icon with a matching mime is left untouched")
    void legitPng_untouched() throws Exception {
        Fixture f = new Fixture("image/png", PNG, "icon");
        validate(f);
        verify(f.file, never()).remove();
        verify(f.resource, never()).setProperty(eq("jcr:mimeType"), org.mockito.ArgumentMatchers.anyString());
        verify(f.session, never()).save();
    }
}
