# Privacy

Glance does not contain analytics, advertising, telemetry, crash-reporting SDKs, or a project-operated cloud service. The project maintainer does not receive data from installed applications.

## Data stored on the device

Glance stores application settings, a generated MQTT device ID, scheduling state, settings PIN verification data, and MQTT connection settings in private application storage. The MQTT password is encrypted with an Android Keystore AES-GCM key. Android WebView separately manages dashboard browser data such as cookies, cache, and authenticated sessions.

Android backups are disabled for the application. Removing the application or resetting the device removes application data, subject to Android and device-vendor behavior.

## Network communication

Glance connects only to dashboard URLs and MQTT broker endpoints configured by the user. Dashboard providers, Home Assistant integrations, brokers, DNS services, and the local network may process connection metadata or page content under their own policies.

The application supports plain HTTP and MQTT for local installations. Those protocols can expose content and credentials to the network. Prefer HTTPS and MQTT over TLS, and use trusted networks.

## Public reports

Before posting an issue, pull request, log, or screenshot, remove dashboard and broker addresses, device IDs, credentials, cookies, tokens, PINs, and signing information. Report vulnerabilities using the private process in [SECURITY.md](SECURITY.md).
