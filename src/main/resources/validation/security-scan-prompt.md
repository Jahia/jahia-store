Run the Jahia security scan on the module `${module}` version `${version}` (groupId `${groupId}`) before the Jahia Store publishes it.

Archive: ${artifactUrl}
Source repository: ${sourceUrl}

Open the archive and scan what it ships with the detection rules of the Jahia security audit: the authorization gates of its GraphQL and REST entry points, the JCR queries it builds, the files it reads and writes, the URLs it fetches, the commands it runs, the HTML it renders, the deserialization it performs, the cookies and tokens it sets, and the libraries it embeds with their known vulnerabilities.

Report every finding with its severity, the file and the line, the vulnerable pattern, and what fixes it. A finding of high or critical severity, or a library with a known vulnerability of CVSS 7 or more, rejects the version.

Answer with one JSON object and nothing else:

{"status": "approved" | "rejected" | "review", "comments": ["one finding per entry: severity, file, pattern, fix"]}

`approved` when no finding reaches medium severity, `rejected` when a finding blocks the publication, `review` when a finding needs a human to confirm it is exploitable.
