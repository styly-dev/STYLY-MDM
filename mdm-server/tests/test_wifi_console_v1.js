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

test('membership conflict accepts serial aliases and delivery never claims local application success', () => {
  assert.equal(wifi.membershipConflict('device-id', 'B', { A: ['serial-1'], B: [] }, ['serial-1']), 'A');
  assert.equal(wifi.membershipConflict('device-id', 'B', { A: ['serial-1'], B: [] }), null);
  assert.equal(wifi.deliveryStatus({ delivery: { status: 'sent', revision: 'r1' } }, 'r1').label, 'Sent');
  assert.equal(wifi.deliveryStatus({ delivery: { status: 'sent', revision: 'r1' } }, 'r2').label, 'Sent (previous profile)');
  assert.equal(wifi.deliveryStatus({ manual: { status: 'success', revision: 'r1' } }, 'r1').label, 'Not sent');
  assert.match(wifi.reasonLabel({ reason: 'unsupported' }), /Update this client/);
  assert.match(wifi.reasonLabel({ reason: 'permission_denied' }), /earlier deployment/);
});

test('send failures and interrupted sends remain distinct from device Wi-Fi results', () => {
  assert.equal(wifi.deliveryStatus({ delivery: { status: 'failed', reason: 'socket_send_failed' } }, 'r1').label, 'Send failed');
  assert.equal(wifi.deliveryStatus({ delivery: { status: 'unknown', reason: 'server_restarted' } }, 'r1').label, 'Send unconfirmed');
  assert.equal(wifi.reasonLabel({ reason: 'RESTORE_FAILED' }), '');
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

function withController(fetchHandler) {
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
  const statusPanel = new FakeElement();
  const groupPanel = new FakeElement();
  groupPanel.actionButtons = {
    '[data-wifi-action="save"]': new FakeElement(),
    '[data-wifi-action="apply"]': new FakeElement(),
  };
  const password = new FakeInput('password');
  groupPanel.passwordInput = password;
  groupPanel.controls = [password, new FakeElement(), new FakeElement(), new FakeElement()];
  const controller = wifi.createController({
    statusPanel,
    groupPanel,
    getGroups: () => ({}),
    getDevices: () => [],
    confirmApply: () => true,
  });
  return {
    statusPanel, groupPanel, password, controller,
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
    assert.deepEqual(submitted, { expected_revision: 'r1', networks: [
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

test('the console loads profiles and saves over managed-LAN HTTP without login or credentials', async () => {
  let writes = 0;
  const routes = [];
  const harness = withController(async (url, init = {}) => {
    routes.push(url);
    assert.equal(init.headers && init.headers.Authorization, undefined);
    if (url === '/api/wifi/state') return response({ enabled: true, allow_http: true, groups: { Lab: { revision: 'r1' } }, devices: {} });
    if (url === '/api/wifi/groups/Lab' && init.method === 'PUT') { writes++; return response({ revision: 'r2' }); }
    if (url === '/api/wifi/groups/Lab') return response({ revision: 'r1', networks: [{ ssid: 'Office', security: 'WPA_PSK', password_present: true }] });
    throw new Error('unexpected route');
  });
  global.location = { protocol: 'http:' };
  try {
    await tick();
    harness.controller.renderGroup('Lab', [], true);
    await tick();
    assert.match(harness.groupPanel.innerHTML, /data-wifi-action="save"/);
    assert.equal(harness.statusPanel.hidden, true);
    assert.doesNotMatch(harness.statusPanel.innerHTML + harness.groupPanel.innerHTML, /login|Log in|session active|Admin token/);
    harness.password.value = 'updated-secret';
    harness.groupPanel.dispatch('input', { target: harness.password });
    harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'save' } }) } });
    await tick();
    assert.equal(writes, 1);
    assert.equal(routes.some((url) => url.includes('/session')), false);
  } finally { harness.restore(); }
});

test('disabled Wi-Fi feature shows a graceful unavailable state', async () => {
  const harness = withController(async () => response({ enabled: false }));
  try {
    await tick();
    harness.controller.renderGroup('Lab', [], true);
    assert.match(harness.groupPanel.innerHTML, /unavailable on this server/);
    assert.equal(harness.statusPanel.hidden, true);
  } finally {
    harness.restore();
  }
});

test('a temporary status failure retains the unsaved draft and recovers without a login step', async () => {
  let unavailable = false;
  let profileReads = 0;
  const harness = withController(async (url) => {
    if (url === '/api/wifi/state') return unavailable
      ? response({ error: 'temporarily unavailable' }, 503)
      : response({ enabled: true, groups: { Lab: { revision: 'r1' } }, devices: {} });
    if (url === '/api/wifi/groups/Lab') {
      profileReads++;
      return response({ revision: 'r1', networks: [{ ssid: 'Office', security: 'WPA_PSK', password_present: true }] });
    }
    throw new Error('unexpected route');
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab', [], true);
    await tick();
    harness.password.value = 'unsaved-secret';
    harness.groupPanel.dispatch('input', { target: harness.password });
    const renders = harness.groupPanel.renderCount;
    unavailable = true;
    await harness.controller.refresh();
    assert.equal(harness.groupPanel.transient.style.display, '');
    unavailable = false;
    await harness.controller.refresh();
    assert.equal(harness.groupPanel.transient.style.display, 'none');
    assert.equal(harness.groupPanel.renderCount, renders);
    assert.equal(harness.password.value, 'unsaved-secret');
    assert.equal(profileReads, 1);
    assert.equal(harness.groupPanel.actionButtons['[data-wifi-action="save"]'].disabled, false);
  } finally { harness.restore(); }
});

test('disabling Wi-Fi removes editing controls', async () => {
  let enabled = true;
  const harness = withController(async (url) => {
    if (url === '/api/wifi/state') return response({ enabled, groups: { Lab: { revision: 'r1' } }, devices: {} });
    return response({ revision: 'r1', networks: [{ ssid: 'Office', security: 'OPEN' }] });
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab', [], true);
    await tick();
    assert.match(harness.groupPanel.innerHTML, /data-wifi-action="save"/);
    enabled = false;
    await harness.controller.refresh();
    assert.doesNotMatch(harness.groupPanel.innerHTML, /data-wifi-action="save"/);
    assert.equal(harness.groupPanel.actionButtons['[data-wifi-action="apply"]'].disabled, true);
  } finally { harness.restore(); }
});

test('HTTP writes remain disabled without the server managed-LAN transport opt-in', async () => {
  let writes = 0;
  const harness = withController(async (url, init = {}) => {
    if (url === '/api/wifi/state') return response({ enabled: true, allow_http: false, groups: { Lab: { revision: 'r1' } }, devices: {} });
    if (init.method === 'PUT') writes++;
    return response({ revision: 'r1', networks: [{ ssid: 'Office', security: 'WPA_PSK', password_present: true }] });
  });
  global.location = { protocol: 'http:' };
  try {
    await tick();
    harness.controller.renderGroup('Lab', [], true);
    await tick();
    harness.password.value = 'updated-secret';
    harness.groupPanel.dispatch('input', { target: harness.password });
    assert.equal(harness.groupPanel.actionButtons['[data-wifi-action="save"]'].disabled, true);
    harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'save' } }) } });
    await tick();
    assert.equal(writes, 0);
  } finally { harness.restore(); }
});

test('Wi-Fi header and targets use saved membership while Devices retains its draft', () => {
  const fs = require('node:fs');
  const vm = require('node:vm');
  const source = fs.readFileSync(require('node:path').join(__dirname, '../styly_mdm/static/index.html'), 'utf8');
  const renderSource = source.slice(source.indexOf('      function renderManageDetail()'), source.indexOf('      function renameInputFocused()'));
  const mgDetail = { innerHTML: '' };
  const panel = { hidden: true };
  let targets;
  const context = {
    mgDetail, wifiGroupPanel: panel,
    wifiController: { renderGroup(name, members) { targets = members; } },
    groups: { Lab: ['saved-1', 'saved-2'] }, manageMembers: new Set(['saved-1']),
    manageSelectedGroup: 'Lab', manageTab: 'wifi', manageSearch: '', manageSort: 'label-asc',
    renamingManageGroup: false, renameInputFocused: () => false, restoreRenameGroupFocus() {},
    deviceById: () => null, isOnline: () => false, esc: (x) => x, renderMgDevListHtml: () => '',
  };
  vm.runInNewContext(renderSource + '\nrenderManageDetail();', context);
  assert.match(mgDetail.innerHTML, /0 online, 2 offline/);
  assert.deepEqual(targets, ['saved-1', 'saved-2']);
  assert.equal(panel.hidden, false);
  context.manageTab = 'devices';
  vm.runInNewContext('renderManageDetail();', context);
  assert.match(mgDetail.innerHTML, /0 online, 1 offline/);
  assert.equal(panel.hidden, true);
  assert.match(mgDetail.innerHTML, /class="btn btn-primary" data-act="saveMembers"/);
});

test('send response reports delivery counts and never renders application or boot results', async () => {
  let requests = 0;
  const harness = withController(async (url, init = {}) => {
    if (url === '/api/wifi/state') return response({ enabled: true, groups: { Lab: { revision: 'r1' } }, devices: {} });
    if (url === '/api/wifi/groups/Lab') return response({ revision: 'r1', networks: [{ ssid: 'Office', security: 'OPEN' }] });
    if (url === '/api/wifi/groups/Lab/deploy' && init.method === 'POST') {
      requests++;
      assert.deepEqual(JSON.parse(init.body), { expected_revision: 'r1' });
      return response({ online_target_count: 3, operations: [
        { status: 'sent' }, { status: 'failed', reason: 'unsupported' }, { status: 'unknown', reason: 'server_restarted' },
      ] });
    }
    throw new Error('unexpected route');
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab', ['offline-device'], true);
    await tick();
    harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'apply' } }) } });
    await tick();
    assert.equal(requests, 1);
    assert.match(harness.statusPanel.innerHTML, /1 sent, 1 failed, 1 unconfirmed/);
    assert.match(harness.groupPanel.innerHTML, /Delivery status/);
    assert.doesNotMatch(harness.groupPanel.innerHTML, /Manual:|Boot:|Device results/);
    assert.match(harness.groupPanel.deviceList.innerHTML, /Not sent/);
  } finally { harness.restore(); }
});

test('a sole network cannot be removed and an open network password stays disabled after updates', async () => {
  const harness = withController(async (url) => url === '/api/wifi/state'
    ? response({ enabled: true, groups: { Lab: { revision: 'r1' } }, devices: {} })
    : response({ revision: 'r1', networks: [{ ssid: 'Office', security: 'OPEN' }] }));
  const remove = new FakeElement(); remove.dataset.wifiAction = 'remove';
  const add = new FakeElement(); add.dataset.wifiAction = 'add';
  harness.groupPanel.controls.push(remove, add);
  try {
    await tick();
    harness.controller.renderGroup('Lab', [], true);
    await tick();
    assert.equal(remove.disabled, true);
    assert.equal(harness.password.disabled, true);
    assert.equal(add.disabled, false);
    for (let i = 1; i < wifi.MAX_NETWORKS; i++) {
      harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'add' } }) } });
    }
    assert.equal(remove.disabled, false);
    assert.equal(add.disabled, true);
  } finally { harness.restore(); }
});

test('a revision conflict locks the stale draft and offers immediate explicit reload without polling', async () => {
  let writes = 0;
  let profileReads = 0;
  const harness = withController(async (url, init = {}) => {
    if (url === '/api/wifi/state') return response({ enabled: true, groups: { Lab: { revision: 'r1' } }, devices: {} });
    if (init.method === 'PUT') {
      writes++;
      assert.equal(JSON.parse(init.body).expected_revision, 'r1');
      return response({ error: 'profile changed' }, 409);
    }
    profileReads++;
    return response({ revision: profileReads === 1 ? 'r1' : 'r2', networks: [{ ssid: 'Office', security: 'WPA_PSK', password_present: true }] });
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab', [], true);
    await tick();
    harness.password.value = 'conflicted-draft';
    harness.groupPanel.dispatch('input', { target: harness.password });
    harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'save' } }) } });
    await tick();
    assert.equal(writes, 1);
    assert.equal(harness.groupPanel.actionButtons['[data-wifi-action="save"]'].disabled, true);
    assert.equal(harness.groupPanel.actionButtons['[data-wifi-action="apply"]'].disabled, true);
    assert.equal(harness.groupPanel.stale.style.display, '');
    assert.equal(harness.password.value, 'conflicted-draft');
    const previousConfirm = global.confirm;
    global.confirm = () => true;
    try {
      harness.groupPanel.dispatch('click', { target: { closest: () => ({ dataset: { wifiAction: 'reload' } }) } });
      await tick();
      assert.equal(harness.groupPanel.stale.style.display, 'none');
      assert.equal(harness.groupPanel.actionButtons['[data-wifi-action="apply"]'].disabled, false);
      assert.equal(harness.password.value, '');
    } finally {
      if (previousConfirm === undefined) delete global.confirm;
      else global.confirm = previousConfirm;
    }
  } finally { harness.restore(); }
});

test('deleting Wi-Fi saves unmanaged with a warning, retains delivery, and requires an explicit send', async () => {
  let revision = 'r1';
  let mode = 'managed';
  const requests = [];
  const previousConfirm = global.confirm;
  let confirmed = false;
  let warning = '';
  global.confirm = (message) => { warning = message; return confirmed; };
  const harness = withController(async (url, init = {}) => {
    if (url === '/api/wifi/state') return response({ enabled: true, groups: { Lab: { revision, mode } }, devices: {
      device: { delivery: { status: 'sent', revision: 'r1' } },
    } });
    if (url.endsWith('/deploy')) {
      requests.push({ method: init.method, body: JSON.parse(init.body) });
      return response({ operations: [{ status: 'sent' }] });
    }
    if (init.method === 'DELETE') {
      requests.push({ method: init.method, body: JSON.parse(init.body) });
      revision = 'r2'; mode = 'unmanaged';
      return response({ revision, mode, networks: [] });
    }
    return response({ revision, mode, networks: [{ ssid: 'Office', security: 'OPEN' }] });
  });
  const click = (action) => harness.groupPanel.dispatch('click', {
    target: { closest: () => ({ dataset: { wifiAction: action } }) },
  });
  try {
    await tick();
    harness.controller.renderGroup('Lab', ['device'], true);
    await tick();
    click('unmanage');
    await tick();
    assert.match(warning, /Existing Wi-Fi networks on devices will remain unchanged/);
    assert.match(warning, /deploy it explicitly/);
    assert.equal(requests.length, 0);
    confirmed = true;
    click('unmanage');
    await tick();
    assert.deepEqual(requests, [{ method: 'DELETE', body: { expected_revision: 'r1' } }]);
    assert.match(harness.groupPanel.innerHTML, /Saved: Do not manage Wi-Fi/);
    assert.match(harness.groupPanel.innerHTML, /Deploy this state explicitly/);
    assert.doesNotMatch(harness.groupPanel.innerHTML, /data-wifi-action="save"/);
    assert.match(harness.groupPanel.deviceList.innerHTML, /Sent \(previous profile\)/);
    assert.equal(harness.groupPanel.actionButtons['[data-wifi-action="apply"]'].disabled, false);
    click('apply');
    await tick();
    assert.deepEqual(requests[1], { method: 'POST', body: { expected_revision: 'r2' } });
    click('add');
    assert.match(harness.groupPanel.innerHTML, /data-wifi-action="save"/);
    assert.equal(harness.groupPanel.actionButtons['[data-wifi-action="apply"]'].disabled, true);
  } finally {
    harness.restore();
    if (previousConfirm === undefined) delete global.confirm;
    else global.confirm = previousConfirm;
  }
});
