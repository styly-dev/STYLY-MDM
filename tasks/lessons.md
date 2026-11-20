# Lessons

## Fleet controls must preserve multi-device selection

- When porting device controls, do not inherit a source implementation's single-headset restriction without checking the intended fleet workflow.
- Open URL and recording Start/Stop must operate on multiple selected HMDs. Keep dispatch, state checks, failures, and results independent per device; one offline, unsupported, or failed headset must not prevent eligible headsets from receiving the action.
- Cover multi-device selection, mixed outcomes, out-of-order results, and per-device state in regression tests before calling the feature complete.
