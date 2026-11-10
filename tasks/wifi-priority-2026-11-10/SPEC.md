# Wi-Fi priority rebuild specification

## Behavior ledger

| ID | Behavior | Evidence and certainty | Disposition | Verification |
|---|---|---|---|---|
| W1 | A managed package contains an ordered list of up to four Wi-Fi networks. The first is preferred. The automatic-return toggle is shared by all groups using the package. | User decision; confirmed. | Preserve. | Package and client tests; device deployment. |
| W2 | Applying a package and reconnecting from no usable connection tries candidates in order. Wi-Fi success means the selected SSID has a usable IP; it does not require reaching the MDM server. | User decision; confirmed. | Preserve. | Device switch and failure/fallback checks. |
| W3 | With automatic return off, an existing connection remains in place, including after app/device restart. With it on, scan for higher candidates about every five minutes while connected below the first candidate, regardless of whether the lower connection was selected manually. Do not scan for higher candidates while already on the first. OS restrictions may delay detection. | User decision and review; confirmed. | Preserve. | Controller lifecycle and device tests. |
| W4 | Reconnection and switching must have bounded recovery. If a prior Wi-Fi connection existed, rollback succeeds only after the original SSID has a usable IP. Otherwise keep the restored settings, report the connection as unconfirmed and retry; queue the report locally while offline. Never silently overwrite credentials or claim success. | User decision and observed device failure; confirmed. | Fix the observed failure. | Failure injection, device readback, and recovery tests. |
| W5 | A single WPA2/WPA3 Personal choice prefers WPA3 when possible and falls back to WPA2. A 1–7 character password is accepted but can connect only using WPA3; 8–63 printable ASCII characters support both. | User decision; confirmed. | Implement. | Android and API validation tests; real SAE and WPA2 checks. |
| W6 | Hidden SSIDs need not participate in automatic return. | User decision; confirmed for auto-return use. | Keep explicit rejection when that combination cannot be supported safely. | Validation test. |
| W7 | Existing development Wi-Fi configuration may be discarded. Old APK partial deployment and compatibility mechanisms are unnecessary; APKs are updated before deployment. Unrelated working-tree edits and non-test device data must be preserved. | User decision; confirmed. | Remove compatibility-only branches. | Contract and test review; recorded backup. |
| W8 | Android and PICO expose duplicate WPA2/SAE views with the same network ID. A partial `updateNetwork` for the existing 5 GHz profile returned -1; updating a copied original configuration with the same credential succeeded. The product package test also failed rollback. | DEV-005, Android 14 / PUI 5.15.7; observed. | Fix the update and recovery paths. | Repeat the complete package test with non-secret stage diagnostics. |
| W9 | A PSK configuration on the test transition AP negotiated SAE with a usable IP. A package with a wrong preferred credential fell back to the WPA2-only test AP. Short-password SAE and fallback on a non-SAE device remain unproven. | DEV-005, Android 14 / PUI 5.15.7; observed for these APs. | Implement with explicit limits. | Android tests and physical-device evidence. |
| W10 | A package may overwrite the currently connected saved network even when the MDM does not know its previous password. If a switch fails, an unknown overwritten password cannot be recovered automatically; record unconfirmed reconnection and retry, without claiming restoration. | User decision; confirmed. | Allow the overwrite and explain the limitation. | Recovery tests and operator documentation. |

## Baseline

The current repository revision, staged and unstaged diffs, and task-created
untracked Android files were saved under the private temporary
`before-simplification` directory. The working tree already contained unrelated
changes; they must not be reverted. The first priority implementation passed its
host tests but the physical package test returned `UPDATE_FAILED` followed by
`RESTORE_FAILED`. The original APK, device data, saved-network flags, 5 GHz
credential and 6 GHz connection were restored and checked afterward.

## Evaluation probes

1. A lower candidate remains connected when automatic return is off, then an
   explicit disconnection causes an ordered reconnect.
2. An unavailable first candidate falls back to the second; a failed switch
   leaves the original connection recoverable and reports the actual result.
3. A WPA2/WPA3 transition AP negotiates SAE when available; a WPA2-only AP
   remains usable with an eligible password. A short password is never presented
   as WPA2-capable.
