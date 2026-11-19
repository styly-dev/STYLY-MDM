const test = require('node:test');
const assert = require('node:assert/strict');
const create = require('../styly_mdm/static/recording-control.js');
function fixture() {
  const elements = {};
  for (const id of ['recordingState', 'btnRecordingStart', 'btnRecordingStop', 'btnRecordingRefresh']) {
    elements[id] = {disabled: true, textContent: '', addEventListener(event, fn) { this.click = fn; }};
  }
  const f = {elements, time: 10_000, connected: true, visible: true, sent: [], logs: [], id: 0,
    target: {device_id: 'one', status: 'online', identity_kind: 'canonical', recording_control_supported: true}};
  f.control = create({document: {getElementById: id => elements[id]}, selection: () => f.target,
    now: () => f.time, connected: () => f.connected, visible: () => f.visible,
    requestId: () => String(++f.id), send: msg => {f.sent.push(msg); return true;},
    label: id => id, log: (...args) => f.logs.push(args), setInterval: () => {}});
  f.result = (extra = {}) => {
    const req = f.sent.at(-1);
    f.control.receive({request_id: req.request_id, device_id: req.target_devices[0], action: req.action,
      status: 'success', state: 'stopped', changed: false, ...extra});
  };
  return f;
}

test('polls one eligible visible headset, uses native state, and confirms start', () => {
  const f = fixture(); f.control.tick();
  assert.equal(f.sent[0].action, 'status');
  assert.equal(f.elements.btnRecordingStart.disabled, true);
  f.result(); assert.equal(f.elements.btnRecordingStart.disabled, false);
  f.elements.btnRecordingStart.click();
  assert.equal(f.sent[1].action, 'start');
  assert.equal(f.elements.btnRecordingStart.disabled, true);
  f.elements.btnRecordingStop.click(); assert.equal(f.sent.length, 2);
  f.result({state: 'recording', changed: true});
  assert.equal(f.elements.btnRecordingStop.disabled, false);
  assert.equal(f.logs.length, 1);
});

test('hidden, offline, unsupported, multiple selection and stale state cannot mutate', () => {
  const f = fixture(); f.visible = false; f.control.tick(); assert.equal(f.sent.length, 0);
  f.visible = true; f.control.tick(); f.result();
  f.time += 8_000; f.control.refresh(); assert.equal(f.elements.btnRecordingStart.disabled, true);
  for (const target of [null, {...f.target, status: 'offline'}, {...f.target, recording_control_supported: false}, {...f.target, identity_kind: 'legacy'}]) {
    f.target = target; f.control.tick();
    assert.equal(f.elements.btnRecordingStart.disabled, true);
    assert.equal(f.elements.btnRecordingStop.disabled, true);
  }
});

test('mismatched or old target results do not change new target state', () => {
  const f = fixture(); f.control.tick();
  f.result({request_id: 'different'}); assert.equal(f.elements.btnRecordingStart.disabled, true);
  f.target = {...f.target, device_id: 'two'}; f.control.refresh(); f.result();
  assert.equal(f.elements.btnRecordingStart.disabled, true);
});

test('failure and disconnection invalidate state without replaying commands', () => {
  const f = fixture(); f.control.tick(); f.result(); f.elements.btnRecordingStart.click();
  f.result({status: 'fail', state: 'unknown', detail: 'Unknown native state'});
  assert.equal(f.elements.btnRecordingStart.disabled, true);
  assert.equal(f.elements.recordingState.textContent, 'Unknown native state');
  f.connected = false; f.control.disconnected(); f.time += 30_000; f.control.tick();
  assert.equal(f.sent.length, 2);
  assert.equal(f.elements.btnRecordingStop.disabled, true);
});

test('timeout does not replay mutations and only later polls status', () => {
  const f = fixture(); f.control.tick(); f.result(); f.elements.btnRecordingStart.click();
  f.time += 23_000; f.control.tick();
  assert.equal(f.sent.filter(m => m.action === 'start').length, 1);
  assert.equal(f.elements.btnRecordingStart.disabled, true);
  f.time += 5_000; f.control.tick(); assert.equal(f.sent.at(-1).action, 'status');
});
