const assert = require('node:assert/strict');
const test = require('node:test');

const wifi = require('../styly_mdm/static/wifi-console-v1.js');

test('profile payload keeps a blank saved PSK and submits a replacement when entered', () => {
  const saved = [{ ssid: 'Office', security: 'WPA_PSK', password_present: true }];
  assert.deepEqual(wifi.buildProfilePayload([
    { ssid: 'Office', security: 'WPA_PSK', password: '', password_present: true },
  ], saved).payload.networks[0].password, null);
  assert.equal(wifi.buildProfilePayload([
    { ssid: 'Office', security: 'WPA_PSK', password: 'new-secret', password_present: true },
  ], saved).payload.networks[0].password, 'new-secret');
});

test('profile validation requires a PSK and accepts the same SSID with different security', () => {
  assert.match(wifi.buildProfilePayload([
    { ssid: 'New', security: 'WPA_PSK', password: '', password_present: false },
  ], []).error, /Enter a password/);
  assert.match(wifi.buildProfilePayload([
    { ssid: 'Legacy', security: 'EAP', password: '' },
  ], []).error, /Open or WPA\/WPA2/);
  assert.equal(wifi.buildProfilePayload([
    { ssid: 'Studio', security: 'OPEN' },
    { ssid: 'Studio', security: 'WPA_PSK', password: 'secret' },
  ], []).error, undefined);
});

class FakeElement {
  constructor() {
    this.dataset = {};
    this.style = {};
    this.listeners = new Map();
    this.controls = [];
    this.value = '';
    this.checked = false;
    this.disabled = false;
    this.hidden = false;
    this.innerHTML = '';
    this.transient = { textContent: '', style: {} };
    this.stale = { style: {} };
    this.meta = { textContent: '' };
    this.passwordInput = null;
    this.actionButtons = {};
    this.secretHelp = { textContent: '' };
  }

  set innerHTML(value) { this._innerHTML = value; }
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
    if (selector.includes('data-wifi-field="password"')) return this.passwordInput;
    if (selector === '.wifi-action-right .wifi-meta') return this.meta;
    if (selector === 'article[data-wifi-row="wifi-0"] small') return this.secretHelp;
    return this.actionButtons[selector] || null;
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

function withController(fetchHandler, confirmApply = () => true) {
  const previous = { fetch: global.fetch, confirm: global.confirm };
  global.confirm = () => true;
  global.fetch = fetchHandler;
  const statusPanel = new FakeElement();
  const groupPanel = new FakeElement();
  groupPanel.actionButtons = {
    '[data-wifi-action="save"]': new FakeElement(),
    '[data-wifi-action="apply"]': new FakeElement(),
  };
  const password = new FakeInput('password');
  groupPanel.passwordInput = password;
  groupPanel.controls = [password];
  const controller = wifi.createController({ statusPanel, groupPanel, confirmApply });
  return {
    statusPanel, groupPanel, password, controller,
    restore() {
      if (previous.fetch === undefined) delete global.fetch;
      else global.fetch = previous.fetch;
      if (previous.confirm === undefined) delete global.confirm;
      else global.confirm = previous.confirm;
    },
  };
}

test('HTTP console saves without login and does not require a transport opt-in', async () => {
  let writes = 0;
  const requests = [];
  const harness = withController(async (url, init = {}) => {
    requests.push({ url, init });
    if (url === '/api/wifi/state') return response({ enabled: true, groups: { Lab: { revision: 'r1' } } });
    if (url === '/api/wifi/groups/Lab' && init.method === 'PUT') {
      writes++;
      return response({ revision: 'r2', mode: 'managed', networks: [] });
    }
    if (url === '/api/wifi/groups/Lab') {
      return response({ revision: 'r1', mode: 'managed', networks: [
        { ssid: 'Office', security: 'WPA_PSK', password_present: true },
      ] });
    }
    throw new Error('unexpected Wi-Fi route: ' + url);
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab');
    await tick();
    assert.match(harness.groupPanel.innerHTML, /Save this group’s Wi-Fi policy/);
    harness.password.value = 'new-secret';
    harness.groupPanel.dispatch('input', { target: harness.password });
    harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'save' } }) } });
    await tick();
    assert.equal(writes, 1);
    assert.equal(requests.some(({ init }) => init.headers && init.headers.Authorization), false);
    assert.equal(requests[0].url, '/api/wifi/state');
  } finally { harness.restore(); }
});

test('unsaved edits are discarded when switching groups and are fetched again on return', async () => {
  const reads = { Lab: 0, Other: 0 };
  const harness = withController(async (url) => {
    if (url === '/api/wifi/state') return response({ enabled: true, groups: {
      Lab: { revision: 'r1' }, Other: { revision: 'r2' },
    } });
    const name = decodeURIComponent(url.split('/').pop());
    reads[name]++;
    return response({ revision: name === 'Lab' ? 'r1' : 'r2', mode: 'managed', networks: [
      { ssid: name, security: 'WPA_PSK', password_present: true },
    ] });
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab');
    await tick();
    harness.password.value = 'unsaved-secret';
    harness.groupPanel.dispatch('input', { target: harness.password });
    harness.controller.renderGroup('Other');
    await tick();
    harness.controller.renderGroup('Lab');
    await tick();
    assert.equal(reads.Lab, 2);
    assert.equal(harness.password.value, '');
    assert.doesNotMatch(harness.groupPanel.innerHTML, /unsaved-secret/);
    harness.password.value = 'discard-on-leave';
    harness.groupPanel.dispatch('input', { target: harness.password });
    harness.controller.renderGroup(null);
    harness.controller.renderGroup('Lab');
    await tick();
    assert.equal(reads.Lab, 3);
    assert.equal(harness.password.value, '');
    assert.doesNotMatch(harness.groupPanel.innerHTML, /discard-on-leave/);
  } finally { harness.restore(); }
});

test('deleting the server policy leaves device settings alone until unmanaged is deployed', async () => {
  const requests = [];
  let deleteWarning = '';
  let revision = 'r1';
  let mode = 'managed';
  const harness = withController(async (url, init = {}) => {
    if (url === '/api/wifi/state') return response({ enabled: true, groups: { Lab: { revision, mode } } });
    if (url === '/api/wifi/groups/Lab' && !init.method) {
      return response({ revision, mode, networks: mode === 'managed' ? [{ ssid: 'Office', security: 'OPEN' }] : [] });
    }
    requests.push({ url, method: init.method, body: init.body && JSON.parse(init.body) });
    if (init.method === 'DELETE') {
      revision = 'r2'; mode = 'unmanaged';
      return response({ revision, mode, networks: [] });
    }
    return response({ online_target_count: 1, operations: [{ status: 'sent' }] });
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab');
    await tick();
    global.confirm = (message) => { deleteWarning = message; return true; };
    harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'unmanage' } }) } });
    await tick();
    assert.match(deleteWarning, /Existing Wi-Fi networks will stay saved/);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].method, 'DELETE');
    assert.match(harness.groupPanel.innerHTML, /Deploy this state explicitly/);

    global.confirm = () => true;
    harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'apply' } }) } });
    await tick();
    assert.equal(requests.length, 2);
    assert.equal(requests[1].url, '/api/wifi/groups/Lab/deploy');
    assert.deepEqual(requests[1].body, { expected_revision: 'r2', mode: 'unmanaged' });
    assert.match(harness.statusPanel.innerHTML, /1 sent, 0 failed, 0 unconfirmed/);
  } finally { harness.restore(); }
});

test('managed deployment sends only the saved profile and reports this response once', async () => {
  let stateReads = 0;
  let post = null;
  let confirmation = '';
  const harness = withController(async (url, init = {}) => {
    if (url === '/api/wifi/state') {
      stateReads++;
      return response({ enabled: true, groups: { Lab: { revision: 'r1', mode: 'managed' } } });
    }
    if (url === '/api/wifi/groups/Lab') return response({ revision: 'r1', mode: 'managed', networks: [
      { ssid: 'Office', security: 'WPA_PSK', password_present: true },
    ] });
    if (url === '/api/wifi/groups/Lab/deploy') {
      post = { body: JSON.parse(init.body), headers: init.headers };
      return response({ online_target_count: 2, operations: [
        { status: 'sent' }, { status: 'failed', reason: 'unsupported' },
      ] });
    }
    throw new Error('unexpected Wi-Fi route: ' + url);
  }, (message) => { confirmation = message; return true; });
  try {
    await tick();
    harness.controller.renderGroup('Lab');
    await tick();
    harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'apply' } }) } });
    await tick();
    assert.deepEqual(post.body, { expected_revision: 'r1', mode: 'managed' });
    assert.equal(post.headers.Authorization, undefined);
    assert.match(confirmation, /auto-connect enabled/);
    assert.match(confirmation, /No saved networks are deleted/);
    assert.match(confirmation, /only when it holds that password/);
    assert.match(harness.statusPanel.innerHTML, /1 sent, 1 failed, 0 unconfirmed/);
    assert.equal(stateReads, 1);
    assert.doesNotMatch(harness.groupPanel.innerHTML, /Delivery status|Boot:|Device results/);
  } finally { harness.restore(); }
});

test('saving freezes the submitted draft until the request completes', async () => {
  let finishSave;
  let submitted;
  const harness = withController(async (url, init = {}) => {
    if (url === '/api/wifi/state') return response({ enabled: true, groups: { Lab: { revision: 'r1' } } });
    if (url === '/api/wifi/groups/Lab' && init.method === 'PUT') {
      submitted = JSON.parse(init.body);
      return new Promise((resolve) => { finishSave = () => resolve(response({ revision: 'r2', mode: 'managed' })); });
    }
    return response({ revision: 'r1', mode: 'managed', networks: [
      { ssid: 'Office', security: 'WPA_PSK', password_present: true },
    ] });
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab');
    await tick();
    harness.password.value = 'submitted-secret';
    harness.groupPanel.dispatch('input', { target: harness.password });
    harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'save' } }) } });
    await tick();
    assert.equal(harness.password.disabled, true);
    harness.password.value = 'late-edit';
    harness.groupPanel.dispatch('input', { target: harness.password });
    assert.equal(submitted.networks[0].password, 'submitted-secret');
    finishSave();
    await tick();
    assert.equal(harness.password.value, '');
    assert.equal(harness.groupPanel.actionButtons['[data-wifi-action="save"]'].disabled, true);
  } finally { harness.restore(); }
});

test('a late profile response for a previous group cannot replace the active group', async () => {
  let finishLab;
  const harness = withController(async (url) => {
    if (url === '/api/wifi/state') return response({ enabled: true, groups: {
      Lab: { revision: 'r1' }, Other: { revision: 'r2' },
    } });
    if (url.endsWith('/Lab')) return new Promise((resolve) => {
      finishLab = () => resolve(response({ revision: 'r1', mode: 'managed', networks: [
        { ssid: 'Late Lab', security: 'OPEN' },
      ] }));
    });
    return response({ revision: 'r2', mode: 'managed', networks: [
      { ssid: 'Other', security: 'OPEN' },
    ] });
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab');
    harness.controller.renderGroup('Other');
    await tick();
    assert.match(harness.groupPanel.innerHTML, /value="Other"/);
    finishLab();
    await tick();
    assert.match(harness.groupPanel.innerHTML, /value="Other"/);
    assert.doesNotMatch(harness.groupPanel.innerHTML, /Late Lab/);
  } finally { harness.restore(); }
});

test('a 409 save locks the stale draft until the user explicitly reloads', async () => {
  let profileReads = 0;
  let writes = 0;
  const harness = withController(async (url, init = {}) => {
    if (url === '/api/wifi/state') return response({ enabled: true, groups: { Lab: { revision: 'r1' } } });
    if (init.method === 'PUT') { writes++; return response({ error: 'conflict' }, 409); }
    profileReads++;
    return response({ revision: profileReads === 1 ? 'r1' : 'r2', mode: 'managed', networks: [
      { ssid: 'Office', security: 'WPA_PSK', password_present: true },
    ] });
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab');
    await tick();
    harness.password.value = 'conflicted-edit';
    harness.groupPanel.dispatch('input', { target: harness.password });
    harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'save' } }) } });
    await tick();
    assert.equal(writes, 1);
    assert.equal(harness.password.disabled, true);
    assert.equal(harness.groupPanel.actionButtons['[data-wifi-action="save"]'].disabled, true);
    assert.equal(harness.groupPanel.stale.style.display, '');
    harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'reload' } }) } });
    await tick();
    assert.equal(profileReads, 2);
    assert.equal(harness.groupPanel.stale.style.display, 'none');
    assert.equal(harness.password.value, '');
    assert.equal(harness.groupPanel.actionButtons['[data-wifi-action="apply"]'].disabled, false);
  } finally { harness.restore(); }
});

test('security and network entry controls retain the existing masked style and supported options', async () => {
  const previousCss = global.CSS;
  global.CSS = { supports: () => true };
  const harness = withController(async (url) => url === '/api/wifi/state'
    ? response({ enabled: true, groups: { Lab: { revision: 'r1' } } })
    : response({ revision: 'r1', mode: 'managed', networks: [{ ssid: 'Office', security: 'WPA_PSK', password_present: true }] }));
  try {
    await tick();
    harness.controller.renderGroup('Lab');
    await tick();
    const input = harness.groupPanel.innerHTML.match(/<input[^>]*data-wifi-field="password"[^>]*>/)[0];
    assert.match(input, /type="text"/);
    assert.match(input, /class="field wifi-secret"/);
    assert.match(input, /autocomplete="off"/);
    assert.match(input, /autocapitalize="off" spellcheck="false"/);
    assert.match(harness.groupPanel.innerHTML, /Remove from policy/);
    assert.match(harness.groupPanel.innerHTML, /Device Wi-Fi networks are never deleted/);
    assert.doesNotMatch(harness.groupPanel.innerHTML, /Register recovery baseline/);
  } finally {
    harness.restore();
    if (previousCss === undefined) delete global.CSS;
    else global.CSS = previousCss;
  }
});


test('membership checks remain available to the existing Devices editor', () => {
  assert.equal(wifi.membershipConflict('device', 'B', { A: ['serial'], B: [] }, ['serial']), 'A');
  assert.equal(wifi.membershipConflict('device', 'A', { A: ['device'] }), null);
});

test('revisiting a group adopts its latest GET revision without stale initial state blocking deploy', async () => {
  let revision = 'r1';
  const harness = withController(async (url) => {
    if (url === '/api/wifi/state') return response({ enabled: true, groups: { Lab: { revision: 'r1' } } });
    return response({ revision, mode: 'managed', networks: [{ ssid: 'Office', security: 'OPEN' }] });
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab'); await tick();
    harness.controller.renderGroup(null);
    revision = 'r2';
    harness.controller.renderGroup('Lab'); await tick();
    assert.equal(harness.groupPanel.actionButtons['[data-wifi-action="apply"]'].disabled, false);
    assert.equal(harness.groupPanel.stale.style.display, 'none');
  } finally { harness.restore(); }
});
