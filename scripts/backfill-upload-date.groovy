/*
 * backfill-upload-date.groovy
 * ---------------------------------------------------------------------------
 * Seed `uploadDate` on module/package VERSION nodes that predate the property, from their
 * current jcr:lastModified - the best record of the original release that exists. Not
 * jcr:created: that is the migration run date for content copied from the legacy store.
 *
 * SAVES WITH EVENT LISTENERS DISABLED - do not "simplify" this away. LastModifiedListener would
 * otherwise re-stamp jcr:lastModified on every version it touches, which resets the pubDate of,
 * and re-sorts, the module-list RSS feed (jnt_contentFolder/rss/...moduleList.jsp still dates and
 * orders by jcr:lastModified). Jackrabbit still indexes what is persisted.
 *
 * RUN ONCE PER WORKSPACE ('live', then 'default'). Versions uploaded from the live storefront are
 * created directly in LIVE and never published from EDIT, so a 'default'-only pass misses them.
 *
 * Idempotent. Run from Jahia Tools > Groovy Console or the provisioning API `executeScript`, with
 * DRY_RUN = true first. Afterwards spot-check that uploadDate matches the old jcr:lastModified and
 * that jcr:lastModified itself has NOT moved; if it has, the listener suppression did not take.
 * ---------------------------------------------------------------------------
 */

import org.jahia.services.content.JCRTemplate
import org.jahia.services.content.JCRSessionWrapper
import org.jahia.services.content.JCRNodeWrapper
import org.jahia.services.content.JCRCallback
import org.jahia.services.content.JCRObservationManager
import org.jahia.api.Constants
import javax.jcr.RepositoryException

// ============================ CONFIG ============================
def SITE_KEY  = 'store'            // store site holding the modules-repository
def WORKSPACE = 'live'             // run once with 'live', then once with 'default'
def DRY_RUN   = true               // true = preview only, no writes
// ================================================================

def REPO          = '/contents/modules-repository'
def VERSION_TYPES = ['jnt:forgeModuleVersion', 'jnt:forgePackageVersion'] as Set
def UPLOAD_DATE   = 'uploadDate'
def LAST_MODIFIED = 'jcr:lastModified'

def report = new StringBuilder()
def stats  = [visited: 0, seeded: 0, alreadySet: 0, noDate: 0, warnings: 0]
def log    = { String msg -> report.append(msg).append('\n'); println msg }
def warn   = { String msg -> stats.warnings++; log("  ! WARN: ${msg}") }

if (WORKSPACE != Constants.LIVE_WORKSPACE && WORKSPACE != Constants.EDIT_WORKSPACE) {
    throw new IllegalArgumentException("WORKSPACE must be 'live' or 'default', got: ${WORKSPACE}")
}

JCRTemplate.getInstance().doExecuteWithSystemSession(null, WORKSPACE, { JCRSessionWrapper session ->

    def collect
    collect = { JCRNodeWrapper node, Set types, List acc ->
        if (types.contains(node.getPrimaryNodeTypeName())) acc.add(node)
        node.getNodes().each { collect(it, types, acc) }
        acc
    }

    def repoPath = "/sites/${SITE_KEY}${REPO}".toString()
    if (!session.nodeExists(repoPath)) {
        throw new RepositoryException("No modules-repository in '${WORKSPACE}': ${repoPath}" +
            (WORKSPACE == Constants.LIVE_WORKSPACE ? " — if the store is edited in jContent and not published, try WORKSPACE='default'." : ""))
    }

    log("backfill uploadDate — site='${SITE_KEY}' workspace='${WORKSPACE}' dryRun=${DRY_RUN}")
    log("repository: ${repoPath}")

    collect(session.getNode(repoPath), VERSION_TYPES, []).each { JCRNodeWrapper version ->
        stats.visited++
        try {
            if (version.hasProperty(UPLOAD_DATE)) {
                stats.alreadySet++
                return
            }
            if (!version.hasProperty(LAST_MODIFIED)) {
                // Nothing to seed from; the storefront keeps falling back (and shows no date).
                stats.noDate++
                warn("no ${LAST_MODIFIED} on ${version.getPath()} — left without a release date")
                return
            }
            if (!DRY_RUN) {
                version.setProperty(UPLOAD_DATE, version.getProperty(LAST_MODIFIED).getDate())
            }
            stats.seeded++
        } catch (RepositoryException e) {
            warn("could not seed ${version.getPath()}: ${e.message}")
        }
    }

    if (DRY_RUN) {
        log("\nDRY-RUN: discarding session (no changes persisted)")
    } else {
        // Listeners OFF: keep LastModifiedListener from re-stamping jcr:lastModified on every
        // version we touch (that value still drives the module-list RSS feed's pubDate + order).
        JCRObservationManager.setAllEventListenersDisabled(Boolean.TRUE)
        try {
            session.save()
        } finally {
            JCRObservationManager.setAllEventListenersDisabled(Boolean.FALSE)
        }
        log("\nsaved ${WORKSPACE} workspace (event listeners disabled to preserve jcr:lastModified)")
    }

    log("\nvisited ${stats.visited} version(s): seeded ${stats.seeded}, already set ${stats.alreadySet}, " +
        "no date ${stats.noDate}, warnings ${stats.warnings}")
    if (WORKSPACE == Constants.LIVE_WORKSPACE) {
        log("reminder: run again with WORKSPACE='default' to cover the authoring tree.")
    }
    report.toString()
} as JCRCallback)
