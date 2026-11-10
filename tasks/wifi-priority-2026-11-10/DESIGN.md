# Target design and route

## Target

Treat managed Wi-Fi as one package setting: an ordered `networks` list and an
`auto_return` boolean. The list always defines priority; operators do not
need a second toggle to enable priority. There is one Wi-Fi application result,
while the live automatic-return monitor remains separate telemetry. The maximum
is four networks. Open and WPA2/WPA3 Personal are the security choices.

For WPA2/WPA3 Personal, 8–63 printable ASCII characters allow WPA2 fallback;
1–7 characters require an SAE-capable device and WPA3. For 8–63 characters,
the candidate design configures PSK and uses Android's SAE auto-upgrade. The
device test must prove what this PUI build actually negotiates before that
behavior is treated as supported. A scan may match either compatible security
type, and a local connection is accepted when the SSID and usable IP match and
the negotiated security is allowed by the credential length.

Automatic-return scans run at a target interval of five minutes only while a
lower candidate is connected. Manual and automatic selection of that lower
candidate are intentionally treated alike; Android may delay scans.

The existing journal remains the owner of temporary Wi-Fi mutations, bounded
selection and recovery. Monitor events must not overwrite a newer policy. A
failed switch must retain enough information to restore known credentials and
all prior auto-join flags. When a previous connection existed, verify its SSID
and IP before reporting a successful rollback; unconfirmed reconnection remains
visible and is retried. Saved Wi-Fi configurations must be copied before
updates instead of constructing incomplete configurations; device validation is
required because the exact cause of the failed update is not yet established.

The user authorizes discarding existing development Wi-Fi settings and will
update APKs before deployment. Do not add old-APK projection, per-setting
compatibility gates, legacy priority modes or automatic migration solely to
preserve the abandoned format. Avoid changing unrelated configuration package
or device data; document the affected development contract and reset boundary.

## Alternatives and route

Keeping separate `wifi` and `wifiPriority` settings requires duplicated results,
capability checks, a managed flag and a legacy branch for behavior the user no
longer needs. A single Wi-Fi object has a clearer owner for the toggle and its
result. Use an incremental replacement across server validation, editor and
client rather than rewriting the existing package and recovery subsystems.
The target is based on this session's earlier implementation and observations;
it was not designed in a separate isolated session.

First probe PICO configuration update and WPA2/WPA3 negotiation without
altering unrelated saved profiles. Then implement the agreed contract and
verify host tests and the isolated-device scenarios in `SPEC.md`. Keep the
recorded baseline private and preserve all unrelated working-tree changes.
