# Contributing to Glance

Thanks for helping improve Glance. Small, focused pull requests are easiest to review.

## Development setup

Use JDK 17 and Android SDK platform 34 with build-tools 34.0.0. Point Gradle to the SDK in an untracked `local.properties` file, then run:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Release builds are unsigned unless you provide your own untracked `keystore.properties`. Never commit or share a signing key. See the README before installing an update on a Device Owner tablet.

## Pull requests

1. Fork the repository and create a topic branch.
2. Add or update tests for behavior changes.
3. Run the verification command above.
4. Explain the user-visible effect and any security or provisioning impact in the pull request.

Do not include real dashboard or broker addresses, device identifiers, credentials, cookies, tokens, private logs, or signing material in code, fixtures, screenshots, issues, or pull requests. Use clearly fake values such as `example.invalid`.

By submitting a contribution, you agree that it is licensed under the MIT License.

For vulnerabilities, follow [SECURITY.md](SECURITY.md) instead of opening a public issue.
