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
 *     For questions regarding licensing, support, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
package org.jahia.modules.forge.actions;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Upload-time checks for the artifacts {@link CreateEntryFromJar} stores as {@code jnt:file} nodes under a
 * forge element (a package JAR, a JavaScript module tgz). JSTORE-SEC-012: the action used to store them
 * with the content type the browser sent and without looking at their bytes, so an uploader could store a
 * markup file (an SVG or HTML document that still opens as an archive) as {@code image/svg+xml} and have
 * the file servlet serve it inline.
 *
 * <ul>
 *   <li>The stored content type comes from the trusted extension, never from the request.</li>
 *   <li>The bytes must start with the signature of that archive format. A JAR built by Maven starts with
 *       a zip local file header, and a tgz with the gzip magic, so a markup prefix is refused.</li>
 * </ul>
 */
public final class ArtifactUploadGuard {

    /** Content type stored for a {@code .jar} upload. */
    public static final String JAR_CONTENT_TYPE = "application/java-archive";
    /** Content type stored for a {@code .tgz} upload. */
    public static final String TGZ_CONTENT_TYPE = "application/gzip";

    private static final String EXTENSION_JAR = "jar";
    private static final String EXTENSION_TGZ = "tgz";
    /** Zip local file header: {@code PK 03 04}. */
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};
    /** Gzip member header: {@code 1F 8B}. */
    private static final byte[] GZIP_SIGNATURE = {0x1F, (byte) 0x8B};

    private ArtifactUploadGuard() {
        // utility class
    }

    /**
     * @param extension a trusted extension ({@code jar} or {@code tgz})
     * @return the content type to store for that extension
     * @throws IllegalArgumentException for any other extension
     */
    public static String contentType(String extension) {
        if (EXTENSION_JAR.equals(extension)) {
            return JAR_CONTENT_TYPE;
        }
        if (EXTENSION_TGZ.equals(extension)) {
            return TGZ_CONTENT_TYPE;
        }
        throw new IllegalArgumentException("Not a trusted artifact extension");
    }

    /**
     * Reads the first bytes of {@code in} (the caller closes it).
     *
     * @param in        the uploaded bytes
     * @param extension a trusted extension ({@code jar} or {@code tgz})
     * @return true when the bytes start with the signature of that archive format
     * @throws IOException when the upload cannot be read
     */
    public static boolean hasArchiveSignature(InputStream in, String extension) throws IOException {
        final byte[] signature = signatureOf(extension);
        return signature.length > 0 && Arrays.equals(in.readNBytes(signature.length), signature);
    }

    private static byte[] signatureOf(String extension) {
        if (EXTENSION_JAR.equals(extension)) {
            return ZIP_SIGNATURE;
        }
        if (EXTENSION_TGZ.equals(extension)) {
            return GZIP_SIGNATURE;
        }
        return new byte[0];
    }
}
