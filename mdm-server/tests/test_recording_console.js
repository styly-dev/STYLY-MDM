const test = require('node:test');
const assert = require('node:assert/strict');
const create = require('../styly_mdm/static/recording-control.js');
const target = id => ({device_id: id, status: 'online', identity_kind: 'canonical', recording_control_supported: true});
function fixture(ids = ['one']) {
  const elements = {};
  for (const id of ['recordingState', 'btnRecordingStart', 'btnRecordingStop', 'btnRecordingRefresh']) {
    elements[id] = {disabled: true, textContent: '', addEventListener(event, fn) { this.click = fn; }};
  }
  const f = {elements, time: 10_000, connected: true, visible: true, sent: [], logs: [], id: 0, targets: ids.map(target)};
  f.control = create({document: {getElementById: id => elements[id]}, selection: () => f.targets,
    now: () => f.time, connected: () => f.connected, visible: () => f.visible,
    requestId: () => String(++f.id), send: msg => {f.sent.push(msg); return true;},
    label: id => id, log: (...args) => f.logs.push(args), setInterval: () => {}});
  f.result = (deviceId, extra = {}, req = f.sent.at(-1)) => {
    f.control.receive({request_id: req.request_id, device_id: deviceId, action: req.action,
      status: 'success', state: 'stopped', changed: false, ...extra});
  };
  return f;
}

test('single selection remains compatible and confirms start', () => {
  const f = fixture(); f.control.tick();
  assert.deepEqual(f.sent[0].target_devices, ['one']);
  assert.equal(f.elements.btnRecordingStart.disabled, true);
  f.result('one'); assert.equal(f.elements.btnRecordingStart.disabled, false);
  f.elements.btnRecordingStart.click();
  assert.equal(f.sent[1].action, 'start');
  f.elements.btnRecordingStop.click(); assert.equal(f.sent.length, 2);
  f.result('one', {state: 'recording', changed: true});
  assert.equal(f.elements.btnRecordingStop.disabled, false);
  assert.equal(f.logs.length, 1);
});

test('mixed states enable both actions and send one desired state to each known headset', () => {
  const f = fixture(['one', 'two']); f.control.tick();
  assert.deepEqual(f.sent[0].target_devices, ['one', 'two']);
  f.result('two', {state: 'recording'}); f.result('one');
  assert.equal(f.elements.btnRecordingStart.disabled, false);
  assert.equal(f.elements.btnRecordingStop.disabled, false);
  assert.match(f.elements.recordingState.textContent, /one: Stopped/);
  assert.match(f.elements.recordingState.textContent, /two: Recording/);
  f.elements.btnRecordingStart.click();
  const batch = f.sent.at(-1);
  assert.deepEqual(batch.target_devices, ['one', 'two']);
  assert.equal(batch.action, 'start');
  f.result('two', {state: 'recording', changed: false}, batch);
  assert.equal(f.elements.btnRecordingStop.disabled, false);
  assert.match(f.elements.recordingState.textContent, /one: Waiting for start/);
  f.result('one', {state: 'recording', changed: true}, batch);
  assert.equal(f.elements.btnRecordingStart.disabled, true);
  f.elements.btnRecordingStop.click();
  assert.deepEqual(f.sent.at(-1).target_devices, ['one', 'two']);
  assert.equal(f.sent.at(-1).action, 'stop');
});

test('offline unsupported and unknown devices are shown and skipped independently', () => {
  const f = fixture(['one', 'unknown']);
  f.targets.push({...target('offline'), status: 'offline'}, {...target('old'), recording_control_supported: false});
  f.control.tick();
  assert.deepEqual(f.sent[0].target_devices, ['one', 'unknown']);
  f.result('one');
  f.result('unknown', {status: 'fail', state: 'unknown', detail: 'Native state unavailable'});
  assert.equal(f.elements.btnRecordingStart.disabled, false);
  assert.match(f.elements.recordingState.textContent, /unknown: Native state unavailable/);
  assert.match(f.elements.recordingState.textContent, /offline: Offline/);
  f.elements.btnRecordingStart.click();
  assert.deepEqual(f.sent.at(-1).target_devices, ['one']);
  assert.equal(f.logs.filter(entry => entry[0].includes('[SKIPPED]')).length, 3);
});

test('partial failure does not overwrite another device or block its controls', () => {
  const f = fixture(['one', 'two']); f.control.tick(); f.result('one'); f.result('two');
  f.elements.btnRecordingStart.click(); const batch = f.sent.at(-1);
  f.result('one', {status: 'fail', state: 'unknown', detail: 'PICO rejected recording'}, batch);
  f.result('two', {state: 'recording', changed: true}, batch);
  assert.match(f.elements.recordingState.textContent, /one: PICO rejected recording/);
  assert.match(f.elements.recordingState.textContent, /two: Recording/);
  assert.equal(f.elements.btnRecordingStop.disabled, false);
  f.elements.btnRecordingStop.click();
  assert.deepEqual(f.sent.at(-1).target_devices, ['two']);
});

test('target snapshots and reply ownership survive changed selection and old results', () => {
  const f = fixture(['one', 'two']); f.control.tick(); const first = f.sent[0];
  f.targets = [target('three')]; f.control.tick(); const next = f.sent[1];
  assert.deepEqual(first.target_devices, ['one', 'two']);
  assert.deepEqual(next.target_devices, ['three']);
  f.result('three', {}, next);
  f.result('one', {state: 'recording'}, first);
  f.result('two', {status: 'fail', detail: 'Old failure'}, first);
  assert.equal(f.elements.recordingState.textContent, 'three: Stopped');
  assert.equal(f.elements.btnRecordingStart.disabled, false);
  // A mismatched request ID cannot settle the currently pending mutation.
  f.elements.btnRecordingStart.click(); const mutation = f.sent.at(-1);
  f.result('three', {state: 'recording'}, next);
  assert.match(f.elements.recordingState.textContent, /Waiting for start/);
  f.result('three', {state: 'recording'}, mutation);
  assert.equal(f.elements.recordingState.textContent, 'three: Recording');
});

test('freshness is evaluated per headset and pending peers do not block healthy members', () => {
  const f = fixture(['one', 'two']); f.control.tick(); const batch = f.sent[0];
  f.result('one'); f.time += 6_000; f.result('two', {}, batch); f.time += 2_000;
  f.control.refresh();
  assert.equal(f.elements.btnRecordingStart.disabled, false);
  f.elements.btnRecordingStart.click();
  assert.deepEqual(f.sent.at(-1).target_devices, ['two']);
  assert.match(f.logs[0][0], /one: Recording state is unknown or stale/);
});

test('polling is bounded per device, respects visibility, and deduplicates selected IDs', () => {
  const f = fixture(['one', 'two', 'one']); f.visible = false; f.control.tick();
  assert.equal(f.sent.length, 0);
  f.visible = true; f.control.tick();
  assert.deepEqual(f.sent[0].target_devices, ['one', 'two']);
  f.result('one'); f.result('two');
  f.time += 4_000; f.control.tick(); assert.equal(f.sent.length, 1);
  f.time += 1_000; f.control.tick(); assert.equal(f.sent.length, 2);
  f.control.tick(); assert.equal(f.sent.length, 2);
});

test('one timeout never replays mutations or discards the other response', () => {
  const f = fixture(['one', 'two']); f.control.tick(); f.result('one'); f.result('two');
  f.elements.btnRecordingStart.click(); const batch = f.sent.at(-1);
  f.result('two', {state: 'recording'}, batch);
  f.visible = false; f.time += 23_000; f.control.tick();
  assert.equal(f.sent.filter(m => m.action === 'start').length, 1);
  assert.match(f.elements.recordingState.textContent, /one: Recording request timed out/);
  f.result('one', {state: 'recording'}, batch);
  assert.match(f.elements.recordingState.textContent, /one: Recording request timed out/);
  f.visible = true; f.time += 5_000; f.control.tick();
  assert.equal(f.sent.at(-1).action, 'status');
});

test('disconnection invalidates each headset and does not replay actions', () => {
  const f = fixture(['one', 'two']); f.control.tick(); f.result('one'); f.result('two');
  f.elements.btnRecordingStart.click();
  f.connected = false; f.control.disconnected(); f.time += 30_000; f.control.tick();
  assert.equal(f.sent.length, 2);
  assert.equal(f.logs.filter(entry => entry[0].includes('outcome unknown')).length, 2);
  assert.equal(f.elements.btnRecordingStart.disabled, true);
  assert.equal(f.elements.btnRecordingStop.disabled, true);
});

test('reselecting a device discards earlier cached state', () => {
  const f = fixture(); f.control.tick(); f.result('one');
  f.targets = []; f.control.refresh(); f.targets = [target('one')]; f.control.refresh();
  assert.equal(f.elements.btnRecordingStart.disabled, true);
  f.control.tick(); assert.equal(f.sent.at(-1).action, 'status');
});

test('a delayed status response has a fresh-action window before polling again', () => {
  const f = fixture(); f.control.tick();
  f.time += 6_000; f.result('one'); f.control.tick();
  assert.equal(f.sent.length, 1);
  assert.equal(f.elements.btnRecordingStart.disabled, false);
  f.time += 4_000; f.control.tick();
  assert.equal(f.sent.length, 1);
  assert.equal(f.elements.btnRecordingStart.disabled, false);
  f.time += 1_000; f.control.tick();
  assert.equal(f.sent.length, 2);
  assert.equal(f.sent[1].action, 'status');
});
