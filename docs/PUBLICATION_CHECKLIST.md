# Public repository checklist

Changing visibility is an operational action, separate from merging these files. Complete this checklist while the repository is still private and repeat the security checks immediately after publication.

## Before changing visibility

- [ ] Confirm the MIT License is the intended project license and the copyright holder is correct.
- [ ] Review every commit, branch, tag, issue, pull request, comment, attachment, Actions log, artifact, and release that will become visible.
- [ ] Run a full-history secret scan and manually check for dashboard URLs, broker hosts, device IDs, credentials, tokens, cookies, email addresses, private logs, keystores, and signing properties.
- [ ] Revoke and rotate any credential that ever entered Git history, even if it was later deleted.
- [ ] Confirm no signing key or recoverable build artifact is published.
- [ ] Make the `verify` workflow green on `master`; resolve account billing or Actions spending-limit failures first.
- [ ] Verify README setup instructions on a clean checkout and test Device Owner deployment on non-production hardware.
- [ ] Decide whether old project activity and contributor identity are suitable for public visibility.
- [ ] Back up repository settings and note that visibility changes can affect rulesets and fork relationships.

## Change visibility

- [ ] Use GitHub repository settings to change visibility only after the pre-publication review is signed off.
- [ ] Confirm the repository URL, default branch, license, description, website, and topics.

## Immediately after publication

- [ ] Re-run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` in GitHub Actions.
- [ ] Enable Dependabot alerts and security updates, secret scanning and push protection, and private vulnerability reporting.
- [ ] Enable CodeQL default setup for Kotlin/Java if available for the repository.
- [ ] Add a `master` ruleset requiring pull requests and the `verify` status check; block force pushes and branch deletion.
- [ ] Require CODEOWNERS review where appropriate and enable automatic deletion of merged branches.
- [ ] Inspect the Security tab, Dependabot pull requests, and secret-scanning results.
- [ ] Verify issue forms, the pull request template, security reporting link, and community profile.
- [ ] Create a signed or otherwise documented first public release without publishing signing material.
