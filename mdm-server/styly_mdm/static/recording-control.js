/* State-aware controls for one online headset. Commands are never retried. */
(function (root) {
  'use strict';
  function createRecordingControls(options) {
    const stateLabel = options.document.getElementById('recordingState');
    const start = options.document.getElementById('btnRecordingStart');
    const stop = options.document.getElementById('btnRecordingStop');
    const refreshButton = options.document.getElementById('btnRecordingRefresh');
    let deviceId = null, state = 'unknown', observedAt = 0, lastPoll = 0, pending = null;
    function selected() {
      const value = options.selection();
      return value && value.status === 'online' && value.identity_kind === 'canonical' &&
        value.recording_control_supported === true ? value : null;
    }
    function refresh() {
      const target = selected();
      const id = target ? target.device_id : null;
      if (deviceId !== id) {
        deviceId = id; state = 'unknown'; observedAt = 0; lastPoll = 0;
        stateLabel.textContent = id ? 'Checking recording state…' : 'Select one online headset with an updated recording-capable client.';
      }
      const connected = options.connected();
      const fresh = observedAt > 0 && options.now() - observedAt < 7_000;
      start.disabled = !connected || !id || !!pending || !fresh || state !== 'stopped';
      stop.disabled = !connected || !id || !!pending || !fresh || state !== 'recording';
      refreshButton.disabled = !connected || !id || !!pending;
      if (!connected) stateLabel.textContent = 'Disconnected. Reconnect and refresh recording state.';
      else if (!id) stateLabel.textContent = 'Select one online headset with an updated recording-capable client.';
      return target;
    }
    function request(action) {
      const target = refresh();
      if (!target || pending || !options.connected()) return;
      if ((action === 'start' && start.disabled) || (action === 'stop' && stop.disabled)) return;
      const requestId = options.requestId();
      pending = { requestId: requestId, deviceId: target.device_id, action: action, at: options.now() };
      state = 'unknown'; observedAt = 0; lastPoll = options.now();
      stateLabel.textContent = action === 'status' ? 'Checking recording state…' : 'Waiting for headset confirmation…';
      if (!options.send({type: 'RECORDING_CONTROL', request_id: requestId, target_devices: [target.device_id], action: action})) {
        disconnected();
      }
      refresh();
    }
    function receive(message) {
      if (!pending || message.request_id !== pending.requestId || message.device_id !== pending.deviceId || message.action !== pending.action) return;
      const action = pending.action;
      const target = pending.deviceId;
      pending = null;
      if (target === deviceId) {
        const ok = message.status === 'success' && (message.state === 'stopped' || message.state === 'recording');
        state = ok ? message.state : 'unknown'; observedAt = ok ? options.now() : 0;
        stateLabel.textContent = ok ? (state === 'recording' ? 'Recording' : 'Stopped') : (message.detail || 'Recording state unavailable. Check the headset.');
      }
      if (action !== 'status') {
        const ok = message.status === 'success';
        options.log((ok ? '[OK] ' : '[FAIL] ') + 'Recording ' + action + ' on ' + options.label(target) +
          (ok ? (message.changed ? ' confirmed' : ': already in the requested state') : ': ' + (message.detail || 'Outcome unknown')), ok ? 'success' : 'fail');
      }
      refresh();
    }
    function disconnected() {
      if (pending && pending.action !== 'status') options.log('Recording outcome unknown after disconnection; refresh state before another action.', 'fail');
      pending = null; state = 'unknown'; observedAt = 0; lastPoll = 0;
      refresh();
    }
    function tick() {
      refresh();
      if (pending && options.now() - pending.at >= 22_000) {
        const action = pending.action;
        pending = null; state = 'unknown'; observedAt = 0;
        stateLabel.textContent = 'Recording request timed out. No retry was sent; refresh state and check the headset.';
        if (action !== 'status') options.log(stateLabel.textContent, 'fail');
        lastPoll = options.now();
        refresh();
      }
      if (options.visible() && !pending && deviceId && options.connected() && options.now() - lastPoll >= 5_000) request('status');
    }
    start.addEventListener('click', function () { request('start'); });
    stop.addEventListener('click', function () { request('stop'); });
    refreshButton.addEventListener('click', function () { request('status'); });
    options.setInterval(tick, 1_000);
    refresh();
    return { refresh: refresh, receive: receive, disconnected: disconnected, tick: tick };
  }
  root.createRecordingControls = createRecordingControls;
  if (typeof module !== 'undefined') module.exports = createRecordingControls;
})(typeof window !== 'undefined' ? window : globalThis);
