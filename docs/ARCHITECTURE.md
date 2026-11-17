# Architecture

Single module, package `app.ikev2manager`:

- `domain/` pure Kotlin: hostname validation + import parser, `Stats` (latency/loss/jitter/stability), `Ranking` + sort modes, `FailureCategory`/`ErrorClassifier`.
- `network/` Stage 1: `DnsProbe`, `PingProbe` (system `ping`), `UdpProbe` (IKE-shaped datagram to 500/4500), `TcpProbe`, `Stage1Scanner` (channelFlow + Semaphore concurrency limit, cancellable), `NetworkStatus`.
- `vpn/` `VpnEngine` interface; `NativeIkev2Engine` (VpnManager, Ikev2VpnProfile, VpnProfileState polling + VPN_MANAGER_EVENT receiver); `StrongSwanHandoffEngine` (.sswan hand-off); `TunnelDiagnostics` (probes bound to the VPN `Network`); `Stage2Runner` (strictly sequential, retries only TIMEOUT/NETWORK_UNAVAILABLE, always disconnects between servers); `VpnStateMachine`.
- `data/` Room (servers, scan_results, vpn_tests, settings KV), `CredentialStore` (Keystore AES-GCM blob in a private file), `Repository`, `AppSettings`.
- `export/` CSV/TXT reports.
- `ui/` Compose Material 3, one `MainViewModel`, Navigation Compose.

Ranking: overall = (1 - vpnShare) * preliminary + vpnShare * VPN score; untested servers are capped (default 40); failed tunnels score 0 on the VPN part. Preliminary and VPN scores are shown separately. Ranking is computed from latest results at read time (not stored); history rows are kept 90 days.

## Unverified assumptions (check on a device)
1. Literal action/category/extra strings of `VpnManager` events and the numeric codes in `ErrorClassifier` (network: 0 unknown host, 1 timeout; IKE: 24 auth failed). If wrong, only error classification degrades; state polling still decides success/timeout.
2. `Ikev2VpnProfile.Builder(server, identity)` uses the username as IKE identity; some servers expect another local ID.
3. Dynamic registration of the event receiver receives the broadcasts.
4. System `ping` is executable by the app on the target device; otherwise ICMP shows "n/a" (not an error).
5. strongSwan intent filter accepts `application/vnd.strongswan.profile`.
6. Compile-level: nothing was compiled locally; expect to fix small errors from the first CI run.
