# Security Policy

flixw-metrics is an unsandboxed plugin: installed code runs with the permissions of the user who
invokes flixw. Integrity, path handling, subprocess execution, compiler loading, report output,
and dependency or artifact verification issues may therefore have security impact.

## Supported versions

Security fixes are released on the latest published minor line only.

| Version | Supported |
| --- | --- |
| 0.2.x | yes |
| earlier versions | no |

Upgrade to the latest release before reporting behavior that may already have been corrected.

## Reporting a vulnerability

Do not open a public issue. Use
[GitHub private vulnerability reporting](https://github.com/wstein/flixw-metrics/security/advisories/new)
and include:

- the plugin, flixw, Flix compiler, Java, and operating-system versions;
- the command and configuration involved;
- the expected and observed security boundary;
- reproduction steps or a minimal project, with secrets removed; and
- the potential impact and any known workaround.

You should receive an acknowledgement within seven days. The maintainer will investigate,
coordinate a fix and release where appropriate, and credit reporters who want attribution.
Please allow time for a fix before public disclosure.

Reports about vulnerabilities in Flix, flixw, Java, or GitHub Actions should be sent to the
responsible upstream project unless flixw-metrics is required to trigger the issue.
