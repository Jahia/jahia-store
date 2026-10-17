/*
 * ==========================================================================================
 * =                            JAHIA'S ENTERPRISE DISTRIBUTION                             =
 * ==========================================================================================
 *
 *                                  http://www.jahia.com
 *
 * JAHIA'S ENTERPRISE DISTRIBUTIONS LICENSING - IMPORTANT INFORMATION
 * ==========================================================================================
 *
 *     Copyright (C) 2002-2026 Jahia Solutions Group. All rights reserved.
 *
 *     This file is part of a Jahia's Enterprise Distribution.
 *
 *     Jahia's Enterprise Distributions must be used in accordance with the terms
 *     contained in the Jahia Solutions Group Terms & Conditions as well as
 *     the Jahia Sustainable Enterprise License (JSEL).
 *
 *     For questions regarding licensing, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.jahia.modules.forge.git;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import javax.jcr.Binary;

/** A JCR binary over a byte array read from the repository, such as the icon of a module. */
final class BytesBinary implements Binary {

    private final byte[] bytes;

    BytesBinary(byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public InputStream getStream() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public int read(byte[] b, long position) {
        if (position >= bytes.length) {
            return -1;
        }
        int length = (int) Math.min(b.length, bytes.length - position);
        System.arraycopy(bytes, (int) position, b, 0, length);
        return length;
    }

    @Override
    public long getSize() {
        return bytes.length;
    }

    @Override
    public void dispose() {
        // Nothing to release: the bytes live with the snapshot.
    }
}
