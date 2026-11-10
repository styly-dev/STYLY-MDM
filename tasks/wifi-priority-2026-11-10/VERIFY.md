# Wi-Fi priority verification

## Decision

Adopt the single ordered Wi-Fi setting for the tested PICO 4 Ultra Enterprise
environment. The previous prototype had separate priority compatibility paths
and failed the first complete package test with `UPDATE_FAILED` followed by
`RESTORE_FAILED`. The replacement uses one package setting, copies existing
Android configurations before updating them, and identifies the prior
connection before mutation. It passed the host and physical checks below. This
decision does not extend the physical results to untested APs or timing cases.

## Checks

| Scope | Result | Boundary |
|---|---|---|
| Server | 926 Python tests passed (`.venv/bin/python -m pytest -q`). | Loopback tests needed execution outside the sandbox because its listener creation was denied. |
| Console | 145 Node tests passed, one existing test skipped (`node --test tests/*.js`). | The skipped test is unrelated to Wi-Fi. |
| Android | 341 unit tests passed (`./gradlew :app:testDevDebugUnitTest`), and `:app:assembleDevDebug` succeeded. | Host tests simulate timers, network callbacks, restarts and recovery. |
| Console workflow | Chrome against a loopback server and fake data: add four networks, reorder, enable auto-return, save and reload. | Short-password help was displayed; the browser probe did not submit a short password. |
| Package and security | DEV-005 received the nested Wi-Fi package and connected to the first test AP with a usable IP and SAE. The WPA2-only second AP was also used successfully. | The test used USB reverse for policy delivery, not to establish server reachability over Wi-Fi. |
| Priority and auto-return | After the prior-connection observation fix, manual selection of the second network with PSK and IP was followed by an app-recorded successful switch to the first with SAE and IP, with the probe activity in the background. With auto-return off in an earlier test, the manually selected second network remained connected during a short observation. | The later retry-delay change was host-tested only; the device test does not establish a five-minute schedule or continuous behavior during prolonged sleep. |
| Fallback | With a newly created preferred profile containing a wrong password, PICO reported wrong-password rejection and connected to the second AP with PSK and IP. | An earlier wrong-password attempt still connected; authentication caching is a possible but unproven explanation. |
| Failure and restoration | Two absent test SSIDs failed; the corrected client returned to the pre-attempt SSID with IP and reported `failed`, `UNREACHABLE`, `rolled_back=true`. | The earlier same scenario exposed a prior-SSID observation race; it was reproduced and fixed before this retest. |
| Cleanup | Original APK hash, original 6 GHz connection/IP, Device Owner, MDM service, private app state, original auto-join flags and removal of test networks were verified. | Original-server registration was not checked. |

The local registry was inspected without printing credentials. It contains old
development Wi-Fi values; it has not been changed. A server started with the
new format will retain that registry but report configuration packages
unavailable until the old entries are removed as documented in
`docs/WIFI_SETTINGS.md`. Update clients before distributing the new format.

## Remaining gaps and rollback

- Physical tests did not use a WPA3-only AP with a 1–7-character password or a
  client without SAE support. The transition AP negotiated SAE; the WPA2-only
  AP worked on the SAE-capable PICO.
- The five-minute scan cadence, prolonged device sleep, unavailable-original-AP
  retry, and restart during pending recovery were not exercised end to end on
  hardware. Relevant recovery and timer cases were exercised by host tests.
- A failed automatic return was found to trigger repeated immediate attempts in
  a host test. The final change holds further automatic-return attempts for five
  minutes after any attempt settles; a process restart clears this in-memory
  delay. The change was not installed on the physical device.
- A saved password unknown to MDM cannot be restored after it is overwritten;
  the user explicitly allowed the overwrite. Re-entering the old password can
  be necessary. An SSID/IP success does not prove the MDM server is reachable.
- The prior working diff and revision were retained in a private temporary
  baseline before simplification. To roll back after deployment, resolve any
  active Wi-Fi operation first and restore a compatible APK and registry backup;
  the new Wi-Fi format is not compatible with old clients.

The shared ignored `tasks/todo.md` was overwritten during the work. Its last
80 recorded lines and the new task plan were restored; earlier content could not
be recovered from the available logs.
