# Security policy

## Supported versions

Security fixes are made on the `master` branch and included in the next release. Before the first release, only `master` is supported. Afterwards, support covers `master` and the latest release; older APKs should be upgraded before reporting an issue that may already be fixed.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Use [GitHub private vulnerability reporting](https://github.com/mateuszsikora/glance/security/advisories/new); a report filed there is visible only to the maintainers.

Please include:

- the affected version or commit;
- reproduction steps and the expected security boundary;
- the impact on a kiosk device, dashboard session, MQTT credentials, or signing process;
- any suggested mitigation.

Remove real dashboard URLs, broker addresses, device identifiers, credentials, cookies, tokens, and signing material from the report. You should receive an acknowledgement within seven days. Please allow time for a fix before publishing details.

## Security boundaries

Glance is a Device Owner-capable application and can lock or wake a managed Android device. Treat its APK signing key, settings PIN, dashboard session, and MQTT credentials as sensitive. The app is designed for trusted local networks; enabling plain HTTP or MQTT without TLS exposes traffic to that network.
