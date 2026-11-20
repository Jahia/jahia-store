# Validation of the versions

A version of a module reaches the public once it is validated: every check of the store runs on
it, and the version is approved when every check approves it. The checks run when a version
enters the store, and again on demand. A store administrator reads the versions waiting for a
check and the rejected ones on a dashboard, answers the checks that need a human, and
invalidates a version at any time.

This document is the contract: the checks, the record of a version, the gate on its publication,
the entry points and the dashboard. `git-backed-modules.md` states how a git-backed module
enters the store.

## A check

A check is an OSGi service of `ModuleValidator`, which the store collects: whoever registers one
adds a check to every validation. A check has a key, stable across releases, a label that says
what the check means to a developer, a description of one sentence on what it looks at, an order
that places it among the steps of a version, and a method that validates one version. The label
never names the tool behind the check.

| Key | Order | Label | What it is |
|---|---|---|---|
| `quality` | 10 | Code quality | A quality analysis of the archive. Not wired yet: every version passes, and the comment says so. |
| `code-review` | 20 | Code review | A review of the archive by an assistant, from a prompt. |
| `security` | 30 | Security | A security scan of the archive by an assistant, from a prompt. |

A check answers one of:

- `approved`, with comments;
- `rejected`, with the comments that say why;
- `review`, waiting for a human, with the prompt that human follows and the box for the answer.

A check that throws reads as `review`, with the message of the failure as its comment.

The two assistant checks are manual for now: they answer `review` with a prompt that names the
version and where its archive is, and an administrator gives the prompt to the assistant and
pastes its answer on the dashboard. The answer is the JSON the prompt asks for,
`{"status": "approved" | "rejected" | "review", "comments": ["..."]}`, or a text whose first
word is the verdict and whose remaining lines are the comments. A service that runs the
assistant itself replaces the manual step without a change of the contract: it answers the check
directly.

## The record of a version

The validation of a version is a `jnt:forgeValidation` node under
`<site>/contents/validations/<module>/<version>`, with one `jnt:forgeValidationCheck` child per
check. The store writes it in a system session, in the default workspace and in the live one, so
the storefront reads it beside the version whatever the rights of the user who asked, and whether
the version is a local node or one of a git-backed module, which the store never writes. A record
is the store's own: no publication is involved.

| Property of the record | Meaning |
|---|---|
| `status` | `pending`, `approved`, `review` or `rejected`: the status of the version, from its checks. |
| `moduleName`, `versionNumber` | The version. |
| `artifactUrl` | Where the archive is: the declared URL of a git-backed version, the attached file of an uploaded one, or the Maven proxy of the site. |
| `requestedBy`, `requestedAt` | Who asked for the validation, and when. |
| `reason` | The reason an administrator gave when invalidating the version. |

| Property of a check | Meaning |
|---|---|
| `checkKey`, `label`, `description`, `stepOrder` | The check, as its service declared it when the record was written. |
| `status` | `pending`, `approved`, `review` or `rejected`. |
| `comments` | What the check says. |
| `prompt` | The prompt a human follows when the check waits for one. |
| `updatedBy`, `updatedAt` | Who answered last, and when: the check itself, or the administrator. |

The status of the version is the worst of its checks: rejected when one check rejects, else
waiting for review when one check waits, else pending when one check has not run, else approved.
An invalidation sets the status to rejected whatever the checks say.

## The gate on the publication

A version reaches the public once approved, and the gate holds on every path:

- A local version saved with `published` set to true must be approved: a validator of the
  version type refuses the save otherwise, whoever writes it and through whatever API. The
  storefront hides the publish control of a version that is not approved and says why.
- A version of a git-backed module is served as published only when its file does not disable
  it and the validation approves it. A refresh of the module validates the versions that were
  never validated.
- An invalidation unpublishes a local version in both workspaces, and stops a git-backed one
  from being served as published.

The configuration of the module, `org.jahia.modules.forge.validation.cfg`, holds
`requireApproval`, true by default. A store that sets it to false publishes a version whatever
its checks say, and keeps the records and the dashboard.

## The entry points

The validation of a version is requested:

- by the store itself, when a version is uploaded, when a git-backed module is registered, and
  when it is refreshed with a version the store never validated;
- by the owner of the module, who starts the validation on the version, or by a store
  administrator; the control calls the GraphQL mutation `forge { requestValidation }`;
- by a process outside the store, which calls the same mutation with the credentials of the
  owner or of an administrator.

A request runs every check again and replaces the record.

The GraphQL API under `forge` carries the whole contract:

| Field | Who | What |
|---|---|---|
| `validation(siteKey, moduleName, version)` | the owner, an administrator | The record of a version. |
| `validations(siteKey, status)` | an administrator | Every record of the site, the newest request first. |
| `requestValidation(siteKey, moduleName, version)` | the owner, an administrator | Validate the version, now. |
| `answerValidationCheck(siteKey, moduleName, version, check, answer)` | an administrator | Answer a check that waits for a human. |
| `invalidateVersion(siteKey, moduleName, version, reason)` | an administrator | Reject the version and withdraw it from the public. |

The owner of a module is the developer who uploaded it or who registered its repository. An
administrator holds `jahiaForgeModerateModule` on the site, which the store administrator role
carries.

## The storefront

The card of a version shows the validation to everyone as a chain of steps: one step per check,
in the order of the checks, with its label, its description, its status and its comments, then a
last step, "Validated". A step is grey while it has not run, orange while it waits for an answer,
green once it passes and red once it fails; the last step turns green when every check passed,
red when the version is rejected. A version never validated shows the steps of the store, all
grey. The owner of the module and an administrator get the control that starts the validation,
or runs it again once it is done; nothing is offered while a check waits for a human.

The validation dashboard is the page `validations` of the site, which the seed of the template
set creates, shown to administrators in their account menu. It lists the versions waiting for a
check or a review, each check that waits for a human with its prompt to copy and a box for the
answer, the rejected versions with their reasons, and the approved versions, each with a control
that invalidates it with a reason.
