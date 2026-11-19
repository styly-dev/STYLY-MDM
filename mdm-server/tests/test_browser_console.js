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
    devices: [{ device_id: 'device', status: 'online', open_url_supported: true }],
    browserUrl: { value: 'https://example.com/a?q=one%26two&token=secret#part' },
    browserStatus: { textContent: '', dataset: {} }, messages: [], logs: [], timers: [],
    updateButtons() {}, labelFor(id) { return id; }, clearTimeout() {},
  });
  context.deviceById = (id) => context.devices.find(d => d.device_id === id);
  context.wsSend = (message) => { context.messages.push(message); return true; };
  context.addLog = (text) => context.logs.push(text);
  context.setTimeout = (callback) => { context.timers.push(callback); return context.timers.length; };
  for (const name of ['selectedBrowserDevice', 'sendOpenUrl', 'finishBrowserRequest']) {
    vm.runInContext(functionSource(name), context);
  }
  return context;
}

test('sends one preserved URL and settles only its correlated result without logging URL', () => {
  const c = harness(); c.sendOpenUrl(); c.sendOpenUrl();
  assert.equal(c.messages.length, 1);
  assert.equal(c.messages[0].url, c.browserUrl.value);
  assert.equal(c.messages[0].type, 'OPEN_URL');
  assert.deepEqual([...c.messages[0].target_devices], ['device']);
  c.finishBrowserRequest('unrelated', 'Ignored', true);
  assert.ok(c.browserPending);
  c.finishBrowserRequest(c.messages[0].request_id, 'Browser launch requested.', true);
  assert.equal(c.browserPending, null);
  assert.equal(c.logs.length, 1);
  assert.ok(!c.logs[0].includes('secret'));
});

test('requires exactly one online capable headset outside attention mode', () => {
  const cases = [c => c.selectedIds.clear(), c => c.selectedIds.add('other'),
    c => c.devices[0].status = 'offline', c => c.devices[0].open_url_supported = false,
    c => c.targetTab = 'attention'];
  for (const change of cases) {
    const c = harness(); change(c); c.sendOpenUrl(); assert.equal(c.messages.length, 0);
  }
});

test('invalid web URLs do not dispatch and timeout never retries', () => {
  for (const url of ['file:///sdcard/x', 'https://user:pass@example.com', 'https://@example.com',
    'https://example.com\\@evil.com', ' https://example.com', 'https://example.com/\n', 'example.com']) {
    const c = harness(); c.browserUrl.value = url; c.sendOpenUrl();
    assert.equal(c.messages.length, 0, url);
  }
  const c = harness(); c.sendOpenUrl(); c.timers[0]();
  assert.equal(c.browserPending, null); assert.equal(c.messages.length, 1);
  assert.match(c.browserStatus.textContent, /not confirmed/);
});

test('disconnect settles pending request without resending and permits explicit retry', () => {
  const c = harness(); c.sendOpenUrl();
  c.finishBrowserRequest(null, 'Connection lost.', false);
  assert.equal(c.browserPending, null);
  assert.equal(c.messages.length, 1);
  c.sendOpenUrl(); assert.equal(c.messages.length, 2);
  assert.notEqual(c.messages[0].request_id, c.messages[1].request_id);
});
