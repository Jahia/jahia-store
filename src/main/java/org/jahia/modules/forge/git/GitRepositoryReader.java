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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads one remote git repository through a bare clone kept under the cache directory. The
 * clone is the store's own: nothing is ever pushed. Every fetch is shallow, so the clone holds
 * the tip of each ref it was asked for and nothing else.
 */
public class GitRepositoryReader implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitRepositoryReader.class);
    private static final String TAGS_PREFIX = Constants.R_TAGS;
    private static final String HEADS_PREFIX = Constants.R_HEADS;
    private static final String REMOTE_PREFIX = Constants.R_REMOTES + "origin/";

    private final String url;
    private final Repository repository;

    /**
     * @param cacheDir the directory under which the clone of this repository lives
     * @param url      the URL of the remote repository
     */
    public GitRepositoryReader(File cacheDir, String url) throws IOException {
        this.url = url;
        File dir = new File(cacheDir, cacheKey(url));
        if (new File(dir, "HEAD").exists()) {
            repository = new FileRepositoryBuilder().setGitDir(dir).setBare().build();
        } else {
            try {
                repository = Git.init().setBare(true).setDirectory(dir).call().getRepository();
            } catch (GitAPIException e) {
                throw new IOException("Cannot create the git cache at " + dir, e);
            }
        }
    }

    /** The directory name of the clone of a URL: a digest, so a URL never becomes a path. */
    static String cacheKey(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String getUrl() {
        return url;
    }

    /** The names of the tags of the remote, without the {@code refs/tags/} prefix. */
    public List<String> listRemoteTags() throws IOException {
        List<String> tags = new ArrayList<>();
        // Listed through the clone: the dumb HTTP transport walks objects and needs a local store.
        try (Git git = new Git(repository)) {
            Collection<Ref> refs = git.lsRemote().setRemote(url).setTags(true).setHeads(false).call();
            for (Ref ref : refs) {
                String name = ref.getName();
                if (name.startsWith(TAGS_PREFIX) && !name.endsWith("^{}")) {
                    tags.add(name.substring(TAGS_PREFIX.length()));
                }
            }
        } catch (GitAPIException e) {
            throw new IOException("Cannot list the tags of " + url + ": " + e.getMessage(), e);
        }
        Collections.sort(tags);
        return tags;
    }

    /**
     * The name of the default branch of the remote: the branch {@code HEAD} points to, or
     * {@code main} then {@code master} when the remote does not say.
     */
    public String defaultBranch() throws IOException {
        try (Git git = new Git(repository)) {
            Map<String, Ref> refs = git.lsRemote().setRemote(url).setHeads(true).setTags(false).callAsMap();
            Ref head = refs.get(Constants.HEAD);
            if (head != null) {
                if (head.isSymbolic() && head.getTarget().getName().startsWith(HEADS_PREFIX)) {
                    return head.getTarget().getName().substring(HEADS_PREFIX.length());
                }
                ObjectId headId = head.getObjectId();
                for (Map.Entry<String, Ref> entry : refs.entrySet()) {
                    if (entry.getKey().startsWith(HEADS_PREFIX) && headId != null && headId.equals(entry.getValue().getObjectId())) {
                        return entry.getKey().substring(HEADS_PREFIX.length());
                    }
                }
            }
            if (refs.containsKey(HEADS_PREFIX + "main")) {
                return "main";
            }
            if (refs.containsKey(HEADS_PREFIX + "master")) {
                return "master";
            }
            throw new IOException("The repository " + url + " has no default branch");
        } catch (GitAPIException e) {
            throw new IOException("Cannot read the branches of " + url + ": " + e.getMessage(), e);
        }
    }

    /** Fetch the tip of a branch into the clone, and return the local ref that holds it. */
    public String fetchBranch(String branch) throws IOException {
        String local = REMOTE_PREFIX + branch;
        fetch(new RefSpec("+" + HEADS_PREFIX + branch + ":" + local));
        return local;
    }

    /** Fetch a tag into the clone when the clone lacks it, and return the local ref that holds it. */
    public String fetchTag(String tag) throws IOException {
        String local = TAGS_PREFIX + tag;
        if (repository.exactRef(local) == null) {
            fetch(new RefSpec("+" + local + ":" + local));
        }
        return local;
    }

    private void fetch(RefSpec spec) throws IOException {
        try (Git git = new Git(repository)) {
            try {
                git.fetch().setRemote(url).setRefSpecs(spec).setTagOpt(TagOpt.NO_TAGS).setDepth(1).call();
            } catch (GitAPIException shallowFailure) {
                // A remote that refuses a shallow fetch still answers a full one.
                LOGGER.debug("Shallow fetch of {} refused by {}, fetching in full", spec, url);
                try {
                    git.fetch().setRemote(url).setRefSpecs(spec).setTagOpt(TagOpt.NO_TAGS).call();
                } catch (GitAPIException e) {
                    throw new IOException("Cannot fetch " + spec + " from " + url + ": " + e.getMessage(), e);
                }
            }
        }
    }

    /** The content of a file at a local ref, or null when the ref holds no such file. */
    public byte[] readFile(String ref, String path) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = commitOf(walk, ref);
            try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
                if (tree == null) {
                    return null;
                }
                ObjectLoader loader = repository.open(tree.getObjectId(0));
                return loader.getBytes();
            }
        }
    }

    /** The date of the commit a local ref points to. */
    public Instant commitDate(String ref) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return Instant.ofEpochSecond(commitOf(walk, ref).getCommitTime());
        }
    }

    private RevCommit commitOf(RevWalk walk, String ref) throws IOException {
        ObjectId id = repository.resolve(ref);
        if (id == null) {
            throw new IOException("The clone of " + url + " holds no ref " + ref);
        }
        return walk.parseCommit(walk.peel(walk.parseAny(id)));
    }

    @Override
    public void close() {
        repository.close();
    }
}
