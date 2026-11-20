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

import org.apache.commons.lang.StringUtils;

/** A file read from the repository that the store serves as a {@code jnt:file}: its name, its MIME type and its bytes. */
public final class GitFile {

    private final String name;
    private final String mimeType;
    private final byte[] bytes;

    GitFile(String name, byte[] bytes) {
        this.name = name;
        this.mimeType = mimeType(name);
        this.bytes = bytes;
    }

    /** The MIME type of a file by its extension: the image formats a browser shows inline, and a few documents. */
    static String mimeType(String fileName) {
        String ext = StringUtils.substringAfterLast(fileName, ".").toLowerCase();
        switch (ext) {
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "svg":
                return "image/svg+xml";
            case "webp":
                return "image/webp";
            case "ico":
                return "image/x-icon";
            case "pdf":
                return "application/pdf";
            case "txt":
                return "text/plain";
            default:
                return "application/octet-stream";
        }
    }

    public String getName() {
        return name;
    }

    public String getMimeType() {
        return mimeType;
    }

    public boolean isImage() {
        return mimeType.startsWith("image/");
    }

    public byte[] getBytes() {
        return bytes;
    }
}
