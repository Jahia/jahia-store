/**
 * Builds a bare git repository, as files, from a map of paths to contents, with no git binary:
 * the loose objects of one commit on `main`, and the files a dumb HTTP client reads. The store
 * fetches a repository with JGit, which falls back to the dumb protocol when the host is a plain
 * file server, so the files served by Jahia's own file servlet make a repository the store reads.
 *
 * Runs in the Cypress Node process as a task: the browser has neither zlib nor SHA-1.
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const AUTHOR = 'Cypress <cypress@example.com> 1700000000 +0000';

/** The SHA-1 of a git object and its loose, deflated form. */
function gitObject(type, content) {
    const store = Buffer.concat([Buffer.from(`${type} ${content.length}\0`), content]);
    return {sha: crypto.createHash('sha1').update(store).digest('hex'), loose: zlib.deflateSync(store)};
}

/**
 * Write the tree of a directory of the repository, recursively, into `objects`.
 *
 * @param {Map<string, Buffer>} blobs the files of the directory and below, by path relative to it
 * @param {Map<string, Buffer>} objects the loose objects, by their path under `objects/`
 * @returns {string} the SHA-1 of the tree
 */
function writeTree(blobs, objects) {
    const entries = new Map();
    for (const [filePath, content] of blobs) {
        const slash = filePath.indexOf('/');
        if (slash < 0) {
            const blob = gitObject('blob', content);
            objects.set(`${blob.sha.slice(0, 2)}/${blob.sha.slice(2)}`, blob.loose);
            entries.set(filePath, {mode: '100644', sha: blob.sha});
        } else {
            const dir = filePath.slice(0, slash);
            if (!entries.has(dir)) {
                entries.set(dir, {mode: '40000', children: new Map()});
            }

            entries.get(dir).children.set(filePath.slice(slash + 1), content);
        }
    }

    // Git sorts the entries by name, a directory as if its name ended with a slash.
    const sortKey = ([name, entry]) => (entry.children ? `${name}/` : name);
    const parts = [];
    for (const entry of [...entries.entries()].sort((a, b) => (sortKey(a) < sortKey(b) ? -1 : 1))) {
        const [name, value] = entry;
        const sha = value.children ? writeTree(value.children, objects) : value.sha;
        parts.push(Buffer.from(`${value.mode} ${name}\0`), Buffer.from(sha, 'hex'));
    }

    const tree = gitObject('tree', Buffer.concat(parts));
    objects.set(`${tree.sha.slice(0, 2)}/${tree.sha.slice(2)}`, tree.loose);
    return tree.sha;
}

/**
 * @param {Record<string, string | {file: string}>} files the files of the repository: a text, or
 *        a file of the tests folder to copy
 * @param {string} message the commit message
 * @param {string} testsDir the tests folder, which `{file}` values are relative to
 * @returns {Record<string, string>} the files of the bare repository, base64, by path
 */
function buildBareRepository(files, message, testsDir) {
    const blobs = new Map();
    for (const [filePath, value] of Object.entries(files)) {
        blobs.set(filePath, typeof value === 'string' ? Buffer.from(value, 'utf8') : fs.readFileSync(path.join(testsDir, value.file)));
    }

    const objects = new Map();
    const treeSha = writeTree(blobs, objects);
    const commit = gitObject('commit', Buffer.from(`tree ${treeSha}\nauthor ${AUTHOR}\ncommitter ${AUTHOR}\n\n${message}\n`));
    objects.set(`${commit.sha.slice(0, 2)}/${commit.sha.slice(2)}`, commit.loose);

    const repository = {
        HEAD: Buffer.from('ref: refs/heads/main\n'),
        'info/refs': Buffer.from(`${commit.sha}\trefs/heads/main\n`),
        'refs/heads/main': Buffer.from(`${commit.sha}\n`),
        // No pack: every object is loose. A line that does not name a pack is ignored.
        'objects/info/packs': Buffer.from('\n')
    };
    for (const [objectPath, loose] of objects) {
        repository[`objects/${objectPath}`] = loose;
    }

    return Object.fromEntries(Object.entries(repository).map(([p, content]) => [p, content.toString('base64')]));
}

/**
 * Build the repository and write its files under `cypress/fixtures/git/<name>`, where the
 * browser side reads them as fixtures to upload. Returns the relative paths of the files.
 */
function buildGitRepositoryTask(testsDir) {
    return ({name, files, message}) => {
        const repository = buildBareRepository(files, message || 'store data', testsDir);
        const root = path.join(testsDir, 'cypress', 'fixtures', 'git', name);
        fs.rmSync(root, {recursive: true, force: true});
        for (const [filePath, base64] of Object.entries(repository)) {
            const target = path.join(root, filePath);
            fs.mkdirSync(path.dirname(target), {recursive: true});
            fs.writeFileSync(target, Buffer.from(base64, 'base64'));
        }

        return Object.keys(repository);
    };
}

module.exports = {buildBareRepository, buildGitRepositoryTask};
