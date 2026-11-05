import {defineConfig} from 'cypress';
import fs from 'fs';
import path from 'path';

/**
 * Version of the artifact staged in ./artifacts by ci.build.sh, i.e. exactly what the
 * snapshot provisioning manifest installs. Deriving it here keeps 01-modulesInstalled
 * in step with every version bump; a hardcoded default went stale on each release and
 * asserted a version nobody had built. The digit right after the prefix keeps
 * "jahia-store-" from matching "jahia-store-template-...".
 */
const stagedVersion = (pattern: RegExp): string | undefined => {
    const dir = path.resolve(__dirname, 'artifacts');
    if (!fs.existsSync(dir)) {
        return undefined;
    }

    const match = fs.readdirSync(dir).map(f => pattern.exec(f)).find(Boolean);
    return match ? match[1] : undefined;
};

// Staged artifact first (what is actually deployed), then an explicit env var (e.g. a
// run against released versions from Nexus). No default: an unset version must fail
// 01-modulesInstalled with its "no version configured" message, not test a stale one.
const PRIVATEAPPSTORE_VERSION =
    stagedVersion(/^jahia-store-(\d[\w.]*-SNAPSHOT)\.jar$/) || process.env.PRIVATEAPPSTORE_VERSION || '';
const STORE_TEMPLATE_VERSION =
    stagedVersion(/^jahia-store-template-(\d[\w.]*-SNAPSHOT)\.tgz$/) || process.env.STORE_TEMPLATE_VERSION || '';

export default defineConfig({
    // DefaultCommandTimeout: 10000,
    // videoUploadOnPasses: false,
    reporter: 'cypress-multi-reporters',
    reporterOptions: {
        configFile: 'reporter-config.json'
    },
    screenshotsFolder: './results/screenshots',
    video: true, // In Cypress, videos are disabled by default
    videosFolder: './results/videos',
    viewportWidth: 1366,
    viewportHeight: 768,
    watchForFileChanges: false,
    e2e: {
        // We've imported your old cypress plugins here.
        // You may want to clean this up later by importing these.
        setupNodeEvents(on, config) {
            // Delete videos for tests that did not fail
            on(
                'after:spec',
                (spec: Cypress.Spec, results: CypressCommandLine.RunResult) => {
                    if (results && results.video) {
                        // Do we have failures for any retry attempts?
                        const failures = results.tests.some(test =>
                            test.attempts.some(attempt => attempt.state === 'failed')
                        );
                        if (!failures) {
                            // Delete the video if the spec passed and no tests retried
                            fs.unlinkSync(results.video);
                        }
                    }
                }
            );

            return require('./cypress/plugins/index.js')(on, config);
        },
        excludeSpecPattern: '*.ignore.ts',
        baseUrl: 'http://localhost:8080'
    },
    env: {
        SUPER_USER_PASSWORD: process.env.SUPER_USER_PASSWORD || 'root1234',
        PRIVATEAPPSTORE_VERSION,
        STORE_TEMPLATE_VERSION
    }
});
