# WDTT Plus integration, 1.9.2

Reference: [WDTT Plus v20 source](https://github.com/Ivan4537/WDTT-Plus/tree/f706ccd373685bbde20cae33b2b7f400e8335ecc), commit `f706ccd373685bbde20cae33b2b7f400e8335ecc`. The Android client build already pins this exact source in `scripts/build_carrier_clients.sh`. Deployment extracts the official v20 APK's `deploy.sh` and x86_64 server after checking the APK SHA-256.

The native client supplies the original VKCalls providers, fallback hashes, worker groups, channel recovery and transport selection. Rawline uses its authenticated loopback SOCKS5 mode to connect this client to the app's VPN core. Startup passwords, hashes and bridge credentials use the original `START_CONFIG` stdin protocol rather than command-line arguments.

1.9.2 aligns the Android adapter with v20 in these areas:

- UDP with TCP/TLS fallback, or TCP/TLS without external UDP; optional external TURN SNI and MASQUE fallback. MASQUE state persists in private storage excluded from Android backups. Registration is enabled only when the user enables this fallback. The optional first-registration SSH relay uses an unambiguous saved server with an approved host key and forwards only to Cloudflare's enrollment API; missing access or relay failure falls back to direct registration.
- Screen sleep/wake and underlying non-VPN network changes are forwarded to the native connection policy. A live native client is allowed to repair its own channels. An exited process receives the original 60-second observation period before recovery.
- Up to 108 workers in groups of nine, respecting a shared profile's smaller `max_workers` limit; multi-hash native fallback remains enabled.
- Connection status, serialized latency probes, startup budget and exclusion of duplicate sessions for the same server and device.
- Deployment offers preserving update and an explicitly confirmed full reset. An incomplete deployment can be reset even if its discarded database lacks `main_password`. The raw database and other original files are backed up before any reset. Preserving update recovers a missing owner password from the entered server parameters while retaining clients, keys and counters.
- Admin requests use v20's framed stdin, including root, cached sudo and passwordless sudo. Long deploy and exit-management scripts travel through stdin so shell argument limits do not truncate them.

Existing protections remain: server-ownership checks, host-key pinning for the main server, private staging, checked snapshots, update locking, binary identity/service verification and failure rollback. The original standalone installer's ownership is not claimed by Android deployment.

Security deviation from the pinned native source: authenticated local SOCKS UDP requires the client's prebound source port, rather than learning it from the first unauthenticated datagram. UDP remains enabled. The mobile core advertises its reserved port before the native client accepts the association.

Limits: Rawline supplies its own VPN interface, settings UI and shared VK account flow; this is not a port of every screen or account flow from the original app. Optional operations on a second VPS retain the original first-contact host-key behavior. Automated tests exercise database preservation, snapshots, link/runtime parameters, session conflicts, sudo stdin framing and SSH-bootstrap eligibility; a real VPS and Android network handover test still require a device and server credentials.
