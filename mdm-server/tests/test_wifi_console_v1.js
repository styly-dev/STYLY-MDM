const assert = require('node:assert/strict');
const test = require('node:test');

const wifi = require('../styly_mdm/static/wifi-console-v1.js');

test('profile payload preserves a saved PSK when the password field is blank', () => {
  const built = wifi.buildProfilePayload([
    { ssid: 'Office', security: 'WPA_PSK', password: '', password_present: true, hidden: true },
  ], [
    { ssid: 'Office', security: 'WPA_PSK', password_present: true },
  ]);

  assert.deepEqual(built, { payload: { networks: [
    { ssid: 'Office', security: 'WPA_PSK', password: null, hidden: true },
  ] } });
});

test('profile payload requires a password for a new WPA network and rejects unsupported modes', () => {
  assert.match(wifi.buildProfilePayload([
    { ssid: 'New', security: 'WPA_PSK', password: '', password_present: false },
  ], []).error, /Enter a password/);
  assert.match(wifi.buildProfilePayload([
    { ssid: 'Legacy', security: 'EAP', password: '', password_present: false },
  ], []).error, /Open or WPA\/WPA2/);
});

test('membership conflict accepts a serial alias and status keeps manual and boot results separate', () => {
  assert.equal(wifi.membershipConflict('device-id', 'B', { A: ['serial-1'], B: [] }, ['serial-1']), 'A');
  assert.equal(wifi.membershipConflict('device-id', 'B', { A: ['serial-1'], B: [] }), null);

  const device = {
    manual: { status: 'failed', revision: 'r1', reason: 'RESTORE_INFO_MISSING' },
    boot: { status: 'success', revision: 'r1' },
    applied_revision: 'r1',
  };
  assert.equal(wifi.manualStatus(device, 'r2').label, 'Failed');
  assert.match(wifi.reasonLabel(device.manual), /Restore information was unavailable/);
  assert.equal(wifi.bootStatus(device).label, 'Boot correction complete');
});

test('observation from an old revision cannot appear as a current match', () => {
  assert.deepEqual(wifi.observationPresentation({ revision: 'r1', status: 'match' }, 'r2'), {
    label: 'Previous revision observation matched', icon: '○', tone: 'muted',
  });
  assert.equal(wifi.observationPresentation({ revision: 'r2', status: 'mismatch' }, 'r2').tone, 'warning');
});

class FakeElement {
  constructor() {
    this.dataset = {};
    this.style = {};
    this.listeners = new Map();
    this.renderCount = 0;
    this.controls = [];
    this.value = '';
    this.checked = false;
    this.disabled = false;
    this.innerHTML = '';
    this.deviceList = { innerHTML: '' };
    this.transient = { textContent: '', style: {} };
    this.stale = { style: {} };
    this.meta = { textContent: '' };
    this.passwordInput = null;
  }

  set innerHTML(value) {
    this._innerHTML = value;
    this.renderCount++;
  }

  get innerHTML() { return this._innerHTML; }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  dispatch(type, event) {
    (this.listeners.get(type) || []).forEach((listener) => listener(event));
  }

  querySelector(selector) {
    if (selector === '[data-wifi-transient]') return this.transient;
    if (selector === '[data-wifi-stale]') return this.stale;
    if (selector === '[data-wifi-device-list]') return this.deviceList;
    if (selector.includes('data-wifi-field="password"')) return this.passwordInput;
    if (selector === '.wifi-action-right .wifi-meta') return this.meta;
    return this.actionButtons && this.actionButtons[selector] || null;
  }

  querySelectorAll() { return this.controls; }
}

class FakeInput extends FakeElement {
  constructor(field) {
    super();
    this.dataset.wifiRow = 'wifi-0';
    this.dataset.wifiField = field;
  }

  matches(selector) { return selector === '[data-wifi-row][data-wifi-field]'; }
}

function response(data, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => data };
}

async function tick() {
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
}

function withController(fetchHandler, options = {}) {
  const previous = {
    fetch: global.fetch,
    location: global.location,
    setInterval: global.setInterval,
    clearInterval: global.clearInterval,
  };
  global.location = { protocol: 'https:' };
  global.fetch = fetchHandler;
  global.setInterval = () => 1;
  global.clearInterval = () => {};
  const authPanel = new FakeElement();
  const groupPanel = new FakeElement();
  groupPanel.actionButtons = {
    '[data-wifi-action="save"]': new FakeElement(),
    '[data-wifi-action="apply"]': new FakeElement(),
  };
  const password = new FakeInput('password');
  groupPanel.passwordInput = password;
  groupPanel.controls = [password, new FakeElement(), new FakeElement(), new FakeElement()];
  const controller = wifi.createController({
    authPanel,
    groupPanel,
    getGroups: () => ({}),
    getDevices: () => [],
    confirmApply: () => true,
    onAuthenticated: options.onAuthenticated,
  });
  return {
    authPanel, groupPanel, password, controller,
    restore() {
      controller.stop();
      global.fetch = previous.fetch;
      if (previous.location === undefined) delete global.location;
      else global.location = previous.location;
      global.setInterval = previous.setInterval;
      global.clearInterval = previous.clearInterval;
    },
  };
}

test('state polling updates device results without rebuilding the Wi-Fi form or losing input focus/value', async () => {
  let stateRevision = 'r1';
  const harness = withController(async (url) => {
    if (url === '/api/wifi/state') return response({ enabled: true, groups: { Lab: { revision: stateRevision } }, devices: {} });
    if (url === '/api/wifi/groups/Lab') return response({
      revision: stateRevision,
      networks: [{ ssid: 'Office', security: 'WPA_PSK', password_present: true, hidden: false }],
    });
    throw new Error('unexpected Wi-Fi route');
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab', [], true);
    await tick();
    harness.password.value = 'typing-but-not-submitted';
    harness.groupPanel.dispatch('input', { target: harness.password });
    const formRenderCount = harness.groupPanel.renderCount;
    stateRevision = 'r1';
    await harness.controller.refresh();
    assert.equal(harness.groupPanel.renderCount, formRenderCount);
    assert.equal(harness.password.value, 'typing-but-not-submitted');
  } finally {
    harness.restore();
  }
});

test('save freezes edits while the request is pending and submits only the captured draft', async () => {
  let finishSave;
  let submitted = null;
  const harness = withController(async (url, init = {}) => {
    if (url === '/api/wifi/state') return response({ enabled: true, groups: { Lab: { revision: 'r1' } }, devices: {} });
    if (url === '/api/wifi/groups/Lab' && !init.method) return response({
      revision: 'r1', networks: [{ ssid: 'Office', security: 'WPA_PSK', password_present: true, hidden: false }],
    });
    if (url === '/api/wifi/groups/Lab' && init.method === 'PUT') {
      submitted = JSON.parse(init.body);
      return new Promise((resolve) => { finishSave = () => resolve(response({ revision: 'r2' })); });
    }
    throw new Error('unexpected Wi-Fi route');
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab', [], true);
    await tick();
    harness.password.value = 'new-wifi-secret';
    harness.groupPanel.dispatch('input', { target: harness.password });
    const saveButton = harness.groupPanel.actionButtons['[data-wifi-action="save"]'];
    harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'save' } }) } });
    await tick();
    assert.equal(harness.password.disabled, true);

    harness.password.value = 'late-unsent-value';
    harness.groupPanel.dispatch('input', { target: harness.password });
    assert.deepEqual(submitted, { networks: [
      { ssid: 'Office', security: 'WPA_PSK', password: 'new-wifi-secret', hidden: false },
    ] });
    finishSave();
    await tick();
    assert.equal(harness.password.value, '');
    assert.equal(saveButton.disabled, true);
  } finally {
    harness.restore();
  }
});

test('admin token is cleared before login request and a successful login reconnects the admin socket', async () => {
  let tokenWasClearedBeforeSend = false;
  let authorization = '';
  let reconnects = 0;
  const harness = withController(async (url, init = {}) => {
    if (url === '/api/wifi/state') return response({ enabled: true });
    if (url === '/api/wifi/session') {
      tokenWasClearedBeforeSend = tokenInput.value === '';
      authorization = init.headers.Authorization;
      return response({ ok: true });
    }
    throw new Error('unexpected Wi-Fi route');
  }, { onAuthenticated: () => { reconnects++; } });
  const tokenInput = new FakeInput('token');
  tokenInput.value = 'admin-secret';
  tokenInput.matches = (selector) => selector === '[data-wifi-token]';
  const form = {
    querySelector: () => tokenInput,
    matches: (selector) => selector === '[data-wifi-login-form]',
  };
  try {
    await tick();
    harness.authPanel.dispatch('submit', { target: form, preventDefault() {} });
    await tick();
    assert.equal(tokenWasClearedBeforeSend, true);
    assert.equal(tokenInput.value, '');
    assert.equal(authorization, 'Bearer admin-secret');
    assert.equal(reconnects, 1);
  } finally {
    harness.restore();
  }
});

test('disabled Wi-Fi feature shows a graceful unavailable state', async () => {
  const harness = withController(async () => response({ enabled: false }));
  try {
    await tick();
    assert.match(harness.authPanel.innerHTML, /unavailable on this server/);
  } finally {
    harness.restore();
  }
});
