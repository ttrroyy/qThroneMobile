# CSQTT 2.5.0 Legacy in Android 2.0

Reference: [ttrroyy/csqtt-rebirth v2.5.0-legacy](https://github.com/ttrroyy/csqtt-rebirth/releases/tag/v2.5.0-legacy), source commit `2066156119a5afe7d63e104aefdc8e0849999d03`.

## Source and I/O

The Rust transport, authentication, call lifecycle, worker scheduler and captcha implementations come from that exact commit. `prepare_csqtt_rebirth.py` changes only argument/configuration I/O: secrets are read from a private per-launch JSON file instead of argv, and a link's Auto JS token supplies the original bootstrap structure.

The packet bridge uses an app-private filesystem Unix socket to pass a connected packet FD to Rust. The receiver verifies the peer UID before accepting SCM_RIGHTS. The SOCKS adapter still requires authentication and a pre-reserved source UDP port. Updating CSQTT does not restore the old unauthenticated loopback packet channel.

## Compatibility map

| Area | Integration |
| --- | --- |
| New links | Original AES-GCM decoder/encoder and public key derivation; independent Rust-generated fixture, tampering, duplicate-key and malformed-link tests. The optional name is retained for Rawline profile sharing. |
| Old links | Both plaintext `csqtt://password@host:port` and `csqtt://connect?v=2&...` remain importable. Stored JSON profiles remain readable. |
| Hash modes | Manual, shared-binding Auto API, and original Auto JS. A token carried by a link uses that token rather than another account's binding. Bound account credentials are added only to the runtime copy. |
| Workers | Original Android normalization: 5, then multiples of 10 through 100; the limit is independent of the number of manual hashes. Old stored worker counts are normalized on read. |
| Transport | Original UDP and TCP/TLS, unified/audio/video obfuscation, WRAP/DTLS/automatic SRTP, browser TLS profiles, VK client IDs and custom TURN addresses. |
| Captcha | Original Rust automatic chain, Rust slider solver and WebView request/result protocol; Rawline's restricted-domain WebView handles automatic and visible requests. |
| Tunnel configuration | Both the old five-field `TUNCONF` and 2.5.0's optional validated `workers=N` field are accepted. |
| Session ownership | 2.5.0 owns an epoch by password, rather than the old device ID. Probes with the same server/password cannot replace the active session. Shutdown allows the wrapper's original VK cleanup window. |
| Deployment | Original `deploy.sh` and architecture-specific servers extracted from the checksum-pinned published APK. Environment/overrides, Docker/systemd, SQLite migration and installer rollback remain original. |
| Panel | HTTPS, manual/default port, private UUID path and confirmed path regeneration. |
| Removal | Original installer's `uninstall` operation. |

The published APK SHA-256 is `bbc261eff2fce56b0ee774271416b0764b34ee16349e9e250ce3c8d45d660d0f`. Its installer matches the tag after normalizing line endings. Its three server binaries have their own published SHA-256/provenance entries.

## v2rayTun screenshot comparison

Rawline already provides SOCKS/HTTP ports, VPN/proxy modes, proxy authentication, credential copying/reset and editable VPN IPv4/IPv6 CIDRs. Its default virtual IPv4 range is `172.19.0.1/24`; changing it is useful when another network uses that range. A preset list is not required to support those addresses.

The phone-address row intentionally copies interface addresses assigned by Android/Wi-Fi/mobile connectivity. It is distinct from the editable virtual VPN address. LAN sharing requires authentication; ordinary VPN mode does not expose a public proxy listener.

## Verification boundary

CI runs the original Rust client/server suites, Go bridge/UDP-isolation tests and Android unit tests/builds. Long emulator/UI tests are intentionally excluded at the user's request. Real VPS deployment, real VK captcha and phone interaction still require device testing; passing unit tests does not prove those external services will accept every account/network.
