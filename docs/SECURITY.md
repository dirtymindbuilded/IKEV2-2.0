# Security

- Credentials: AES-256-GCM, non-exportable Android Keystore key; only the encrypted blob is stored (`filesDir/credentials.bin`). Not in Room, preferences, logs, exports or crash text. `VpnCredentials.toString()` masks the password. Delete from Credentials/Settings.
- `allowBackup=false`; no analytics, no crash reporter, no backend.
- Server certificates: validated against the system trust store; no custom CA, no validation bypass.
- VPN consent: Android's own dialog via `provisionVpnProfile`; never bypassed.
- Network contacts: only the VPN servers you import, plus during tunnel tests Cloudflare 1.1.1.1:443, Google 8.8.8.8:443 and `connectivitycheck.gstatic.com`. No credentials or identifiers are sent.
- Exports contain no passwords; CSV cells starting with `= + - @` are prefixed to block spreadsheet formula injection.
- strongSwan `.sswan` profiles hold the username only, are written to cache and replaced on each hand-off.
- Not covered: rooted devices, screen capture of the password field.
