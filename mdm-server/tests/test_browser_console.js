const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const { webcrypto } = require('node:crypto');

const source = fs.readFileSync(path.join(__dirname, '..', 'styly_mdm', 'static', 'index.html'), 'utf8');
new vm.Script(source.match(/<script>\s*([\s\S]*?)<\/script>/)[1]);

function functionSource(name) {
  const start = source.indexOf('function ' + name + '(');
  const end = source.indexOf('\n      }', start);
  assert.ok(start >= 0 && end > start);
  return source.slice(start, end + '\n      }'.length);
}

function harness() {
  const context = vm.createContext({
    URL, Uint8Array, window: { crypto: webcrypto },
    targetTab: 'devices', selectedIds: new Set(['device']), browserPending: null,
    devices: [{ device_id: 'device', label: 'Headset A', status: 'online', open_url_supported: true }],
    browserUrl: { value: 'https://example.com/a?q=one%26two&token=secret#part' },
    browserStatus: { textContent: '', dataset: {} }, browserResults: { innerHTML: '' },
    messages: [], logs: [], timers: [], updateButtons() {}, clearTimeout() {},
    esc(text) { return String(text).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;'); },
  });
  context.deviceById = (id) => context.devices.find(d => d.device_id === id);
  context.labelFor = (id) => (context.deviceById(id) || {}).label || id;
  context.wsSend = (message) => { context.messages.push(message); return true; };
  context.addLog = (text) => context.logs.push(text);
  context.setTimeout = (callback) => { context.timers.push(callback); return context.timers.length; };
  for (const name of ['selectedBrowserTargets', 'renderBrowserProgress', 'browserRequestSent',
    'sendOpenUrl', 'finishBrowserRequest', 'failPendingBrowserRequests']) {
    vm.runInContext(functionSource(name), context);
  }
  return context;
}

function addDevice(c, id, changes = {}) {
  c.devices.push({ device_id: id, label: id, status: 'online', open_url_supported: true, ...changes });
  c.selectedIds.add(id);
}

function finish(c, id, ok = true, message = ok ? 'Browser launch requested.' : 'No browser.') {
  c.finishBrowserRequest(c.messages.at(-1).request_id, id, message, ok);
}

test('single-target compatibility preserves URL and requires exact result correlation', () => {
  const c = harness(); c.sendOpenUrl(); c.sendOpenUrl();
  assert.equal(c.messages.length, 1);
  assert.equal(c.messages[0].url, c.browserUrl.value);
  assert.equal(c.messages[0].type, 'OPEN_URL');
  assert.deepEqual([...c.messages[0].target_devices], ['device']);
  c.finishBrowserRequest('unrelated', 'device', 'Ignored', true);
  c.finishBrowserRequest(null, 'device', 'Ignored', true);
  assert.ok(c.browserPending);
  finish(c, 'device');
  assert.equal(c.browserPending, null);
  assert.equal(c.logs.length, 1);
  assert.ok(!c.logs[0].includes('secret'));
  assert.match(c.browserStatus.textContent, /1\/1 completed.*1 requested.*0 failed/);
});

test('multi/group request snapshots all targets and handles mixed out-of-order results', () => {
  const c = harness();
  c.targetTab = 'groups';
  addDevice(c, 'second', { label: '<Headset B>' });
  addDevice(c, 'old', { open_url_supported: false });
  addDevice(c, 'offline', { status: 'offline' });
  c.sendOpenUrl();
  assert.deepEqual([...c.messages[0].target_devices], ['device', 'second', 'old', 'offline']);
  assert.equal(c.browserPending.targets.size, 4);
  const requestId = c.messages[0].request_id;
  c.selectedIds = new Set(['other']);
  c.devices[1].label = 'Renamed after dispatch';
  finish(c, 'second');
  assert.match(c.browserStatus.textContent, /1\/4 completed.*3 pending/);
  assert.match(c.browserResults.innerHTML, /&lt;Headset B&gt;/);
  assert.ok(!c.browserResults.innerHTML.includes('Renamed'));
  c.browserRequestSent({ request_id: requestId, device_id: 'second' });
  c.finishBrowserRequest(requestId, 'unknown', 'Ignore', false);
  finish(c, 'second', false, 'Duplicate result');
  assert.equal(c.logs.length, 1);
  assert.equal(c.browserPending.targets.get('second').status, 'requested');
  finish(c, 'offline', false, 'Headset offline.');
  finish(c, 'old', false, 'Update the MDM client.');
  assert.ok(c.browserPending);
  finish(c, 'device');
  assert.equal(c.browserPending, null);
  assert.match(c.browserStatus.textContent, /4\/4 completed.*2 requested.*2 failed/);
  assert.equal(c.logs.length, 4);
  assert.match(c.browserResults.innerHTML, /Headset offline/);
  assert.match(c.browserResults.innerHTML, /Update the MDM client/);
});

test('requires an eligible selected headset outside attention mode; unavailable peers are retained', () => {
  const cases = [c => c.selectedIds.clear(), c => c.devices[0].status = 'offline',
    c => c.devices[0].open_url_supported = false, c => c.targetTab = 'attention'];
  for (const change of cases) {
    const c = harness(); change(c); c.sendOpenUrl(); assert.equal(c.messages.length, 0);
  }
  const c = harness(); c.selectedIds.add('missing'); c.sendOpenUrl();
  assert.deepEqual([...c.messages[0].target_devices], ['device', 'missing']);
});

test('invalid web URLs do not dispatch', () => {
  for (const url of ['file:///sdcard/x', 'https://user:pass@example.com', 'https://@example.com',
    'https://example.com\\@evil.com', ' https://example.com', 'https://example.com/\n', 'example.com']) {
    const c = harness(); addDevice(c, 'second'); c.browserUrl.value = url; c.sendOpenUrl();
    assert.equal(c.messages.length, 0, url);
  }
});

test('timeout preserves completed peers, ignores late results and never resends', () => {
  const c = harness(); addDevice(c, 'second'); c.sendOpenUrl();
  const oldRequestId = c.messages[0].request_id;
  finish(c, 'second'); c.timers[0]();
  assert.equal(c.browserPending, null); assert.equal(c.messages.length, 1);
  assert.match(c.browserStatus.textContent, /2\/2 completed.*1 requested.*1 failed/);
  assert.match(c.browserResults.innerHTML, /not confirmed/);
  c.sendOpenUrl();
  c.finishBrowserRequest(oldRequestId, 'device', 'Late old result', true);
  assert.equal(c.browserPending.targets.get('device').status, 'pending');
  c.timers[0]();
  assert.equal(c.browserPending.targets.get('device').status, 'pending');
  assert.equal(c.messages.length, 2);
});

test('disconnect settles only unfinished peers and explicit retry gets a new request ID', () => {
  const c = harness(); addDevice(c, 'second'); c.sendOpenUrl(); finish(c, 'device');
  c.failPendingBrowserRequests('Connection lost.');
  assert.equal(c.browserPending, null);
  assert.equal(c.messages.length, 1);
  assert.match(c.browserStatus.textContent, /1 requested.*1 failed/);
  assert.match(c.browserResults.innerHTML, /Headset A<\/strong>: Browser launch requested/);
  c.sendOpenUrl(); assert.equal(c.messages.length, 2);
  assert.notEqual(c.messages[0].request_id, c.messages[1].request_id);
});

test('request-wide validation errors settle every pending target and failed send never waits', () => {
  const c = harness(); addDevice(c, 'second'); c.sendOpenUrl();
  finish(c, null, false, 'Invalid URL.');
  assert.equal(c.browserPending, null);
  assert.match(c.browserStatus.textContent, /0 requested.*2 failed/);
  c.wsSend = () => false;
  c.sendOpenUrl();
  assert.equal(c.browserPending, null);
  assert.match(c.browserResults.innerHTML, /not sent/);
  assert.equal(c.timers.length, 1);
});
