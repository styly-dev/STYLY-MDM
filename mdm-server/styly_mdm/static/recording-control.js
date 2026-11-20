/* Per-headset desired-state controls. Mutating commands are never retried. */
(function (root) {
  'use strict';
  function createRecordingControls(options) {
    const stateLabel = options.document.getElementById('recordingState');
    const start = options.document.getElementById('btnRecordingStart');
    const stop = options.document.getElementById('btnRecordingStop');
    const refreshButton = options.document.getElementById('btnRecordingRefresh');
    const states = new Map();
    let selectedIds = new Set();
    function selection() {
      const values = options.selection() || [];
      const unique = new Map();
      values.forEach(function (value) { if (value && value.device_id) unique.set(value.device_id, value); });
      return Array.from(unique.values());
    }
    function record(id) {
      if (!states.has(id)) states.set(id, {state: 'unknown', observedAt: 0, lastPoll: 0, pending: null, detail: 'Checking recording state…'});
      return states.get(id);
    }
    function unavailable(target) {
      if (target.status !== 'online') return 'Offline';
      if (target.identity_kind !== 'canonical' || target.recording_control_supported !== true) return 'Recording control requires an updated, identified client';
      return '';
    }
    function fresh(value) {
      return value.observedAt > 0 && options.now() - value.observedAt < 7_000;
    }
    function reason(target, value, action) {
      if (unavailable(target)) return unavailable(target);
      if (value.pending) return 'Another recording request is in progress';
      if (action !== 'status' && (!fresh(value) || value.state === 'unknown')) return 'Recording state is unknown or stale; refresh state first';
      return '';
    }
    function refresh() {
      const targets = selection();
      const nextIds = new Set(targets.map(function (target) { return target.device_id; }));
      const connected = options.connected();
      let canStart = 0, canStop = 0, canRefresh = 0;
      const lines = [];
      targets.forEach(function (target) {
        const value = record(target.device_id);
        // Reselecting a headset requires a new status observation. Keep pending
        // ownership so an old response cannot settle another device's request.
        if (!selectedIds.has(target.device_id)) {
          value.state = 'unknown'; value.observedAt = 0; value.lastPoll = 0;
          value.detail = 'Checking recording state…';
        }
        const unsupported = unavailable(target);
        if (unsupported) { value.state = 'unknown'; value.observedAt = 0; value.lastPoll = 0; }
        if (!reason(target, value, 'status')) canRefresh++;
        if (!reason(target, value, 'start')) {
          if (value.state === 'stopped') canStart++;
          if (value.state === 'recording') canStop++;
        }
        let label = unsupported;
        if (!label && value.pending) label = value.pending.action === 'status' ? 'Checking recording state…' : 'Waiting for ' + value.pending.action + ' confirmation…';
        if (!label) label = fresh(value) ? (value.state === 'recording' ? 'Recording' : 'Stopped') : value.detail;
        if (!label || (!fresh(value) && value.state !== 'unknown')) label = 'Recording state is stale; refreshing…';
        lines.push(options.label(target.device_id) + ': ' + label);
      });
      selectedIds = nextIds;
      // Keep only pending unselected requests until their own result or timeout.
      states.forEach(function (value, id) { if (!nextIds.has(id) && !value.pending) states.delete(id); });
      start.disabled = !connected || canStart === 0;
      stop.disabled = !connected || canStop === 0;
      refreshButton.disabled = !connected || canRefresh === 0;
      stateLabel.textContent = !connected ? 'Disconnected. Reconnect and refresh recording state.' :
        (lines.length ? lines.join('\n') : 'Select headsets to control recording.');
      return targets;
    }
    function request(action, polling) {
      const targets = refresh();
      if (!options.connected()) return;
      if ((action === 'start' && start.disabled) || (action === 'stop' && stop.disabled)) return;
      const eligible = [];
      targets.forEach(function (target) {
        const value = record(target.device_id);
        const skip = reason(target, value, action);
        if (skip) {
          if (action !== 'status') options.log('[SKIPPED] Recording ' + action + ' on ' + options.label(target.device_id) + ': ' + skip, 'warn');
          return;
        }
        if (polling && options.now() - value.lastPoll < 5_000) return;
        eligible.push(target.device_id);
      });
      if (!eligible.length) return;
      const requestId = options.requestId();
      eligible.forEach(function (id) {
        const value = record(id);
        value.pending = {requestId: requestId, action: action, at: options.now()};
        value.state = 'unknown'; value.observedAt = 0; value.lastPoll = options.now();
      });
      // Snapshot targets now: future selection changes cannot alter this batch.
      if (!options.send({type: 'RECORDING_CONTROL', request_id: requestId, target_devices: eligible, action: action})) disconnected();
      refresh();
    }
    function receive(message) {
      const value = states.get(message.device_id);
      const pending = value && value.pending;
      if (!pending || message.request_id !== pending.requestId || message.action !== pending.action) return;
      const action = pending.action;
      value.pending = null;
      // A slow status response still gets a full fresh-action window before
      // the next poll; do not immediately replace its state with another query.
      value.lastPoll = options.now();
      const ok = message.status === 'success' && (message.state === 'stopped' || message.state === 'recording') &&
        (action !== 'start' || message.state === 'recording') && (action !== 'stop' || message.state === 'stopped');
      value.state = ok ? message.state : 'unknown'; value.observedAt = ok ? options.now() : 0;
      value.detail = ok ? '' : (message.detail || 'Recording state unavailable. Check the headset.');
      if (action !== 'status') {
        options.log((ok ? '[OK] ' : '[FAIL] ') + 'Recording ' + action + ' on ' + options.label(message.device_id) +
          (ok ? (message.changed ? ' confirmed' : ': already in the requested state') : ': ' + value.detail), ok ? 'success' : 'fail');
      }
      refresh();
    }
    function disconnected() {
      states.forEach(function (value, id) {
        if (value.pending && value.pending.action !== 'status') options.log('[FAIL] Recording outcome unknown on ' + options.label(id) + ' after disconnection; refresh state before another action.', 'fail');
        value.pending = null; value.state = 'unknown'; value.observedAt = 0; value.lastPoll = 0;
        value.detail = 'Refresh recording state after reconnecting.';
      });
      refresh();
    }
    function tick() {
      states.forEach(function (value, id) {
        if (!value.pending || options.now() - value.pending.at < 22_000) return;
        const action = value.pending.action;
        value.pending = null; value.state = 'unknown'; value.observedAt = 0; value.lastPoll = options.now();
        value.detail = 'Recording request timed out. No retry was sent; refresh state and check the headset.';
        if (action !== 'status') options.log('[FAIL] ' + options.label(id) + ': ' + value.detail, 'fail');
      });
      refresh();
      if (options.visible() && options.connected()) request('status', true);
    }
    start.addEventListener('click', function () { request('start'); });
    stop.addEventListener('click', function () { request('stop'); });
    refreshButton.addEventListener('click', function () { request('status'); });
    options.setInterval(tick, 1_000);
    refresh();
    return {refresh: refresh, receive: receive, disconnected: disconnected, tick: tick};
  }
  root.createRecordingControls = createRecordingControls;
  if (typeof module !== 'undefined') module.exports = createRecordingControls;
})(typeof window !== 'undefined' ? window : globalThis);
