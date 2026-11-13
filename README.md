# SlowDNS Lite

Android client project for manual SSH-over-DNS profiles.

## Current status
This is the first project scaffold: Android app shell, manual profile fields, and GitHub Actions build pipeline. The SlowDNS native tunnel engine and Android VPN routing are not integrated yet, so Connect is not a working tunnel at this stage.

## Intended compatibility
The planned engine is the SLDNS SSH-over-DNS client used by common SlowDNS deployments. Typical profile inputs are DNS resolver, nameserver/tunnel domain, server public key, and SSH credentials. Compatibility must be verified against the exact server implementation and key format before claiming interoperability.

Reference engine: https://github.com/fisabiliyusri/SLDNS

## Build
Pushes to main and manual workflow dispatch build a debug APK. Download it from the Actions run artifact.
