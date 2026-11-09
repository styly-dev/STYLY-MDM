const assert = require('node:assert/strict');
const test = require('node:test');
const {
  createController, loadDraft, buildPayload, deviceOutcome, retryEligible, syncMemberSelection,
} = require('../styly_mdm/static/configuration-packages-v1.js');

const sections = [
  { id: 'basic', title: 'Basic settings', description: 'Display and power', items: [
    { id: 'brightness', label: 'Screen brightness', kind: 'range', default: 50 },
    { id: 'autoSleep', label: 'Auto sleep', kind: 'bool', default: false },
    { id: 'screenOff', label: 'Screen off delay', kind: 'delay', default: -1, options: [{ value: -1, label: 'Never' }] },
    { id: 'shutdown', label: 'Scheduled shutdown', kind: 'schedule', default: { enabled: false, hour: 0, minute: 0, repeat: 127 } },
  ] },
  { id: 'network', title: 'Network & connectivity', items: [
    { id: 'wifi', label: 'Wi-Fi networks', kind: 'wifi', default: [] },
  ] },
  { id: 'advanced', title: 'USB & advanced', items: [
    { id: 'developerMenu', label: 'Developer options menu', kind: 'bool', default: false },
    { id: 'unavailableExample', label: 'Unavailable example', kind: 'unavailable', default: null },
  ] },
];
const wifiNetwork = { ssid: 'Studio', security: 'WPA_PSK', hidden: false, password_present: true };

test('metadata defaults are visible in the draft, unmanaged, and exclude unsupported controls from writes', () => {
  const saved = loadDraft(sections, {});
  const draft = structuredClone(saved);
  assert.deepEqual(saved.brightness, { managed: false, value: 50 });
  assert.deepEqual(saved.wifi, { managed: false, value: [] });
  assert.deepEqual(saved.developerMenu, { managed: false, value: false });
  assert.equal(saved.unavailableExample, undefined);
  draft.brightness.value = 64;
  draft.shutdown.value = { enabled: true, hour: 23, minute: 10, repeat: 65 };
  const payload = buildPayload(sections, draft, saved);
  assert.deepEqual(payload.brightness, { managed: false, value: 64 });
  assert.deepEqual(payload.shutdown, { managed: false, value: { enabled: true, hour: 23, minute: 10, repeat: 65 } });
  assert.deepEqual(payload.developerMenu, { managed: false, value: false });
  assert.equal(payload.unavailableExample, undefined);
  assert.equal(saved.shutdown.value.repeat, 127);
  assert.equal(sections[0].items[3].default.repeat, 127);
});

test('Wi-Fi keeps an existing PSK by omitting the password, even when unmanaged', () => {
  const saved = loadDraft(sections, { wifi: { managed: true, value: [wifiNetwork] } });
  const draft = structuredClone(saved);
  draft.wifi.managed = false;
  assert.deepEqual(buildPayload(sections, draft, saved).wifi, {
    managed: false, value: [{ ssid: 'Studio', security: 'WPA_PSK', hidden: false }],
  });
  draft.wifi.value[0].ssid = 'Other';
  assert.throws(() => buildPayload(sections, draft, saved), /Enter a password/);
  draft.wifi.value[0].password = 'new-secret';
  assert.equal(buildPayload(sections, draft, saved).wifi.value[0].password, 'new-secret');
  draft.wifi.value = [];
  draft.wifi.managed = true;
  assert.throws(() => buildPayload(sections, draft, saved), /Add at least one Wi-Fi network/);
});

const pkg = { package_id: 'pkg', name: 'Studio', has_draft: true, published_revision: 'r0' };
const pkg2 = { package_id: 'pkg2', name: 'Night studio', has_draft: false, published_revision: null };
const d1 = { package_id: 'pkg', revision: 'r1', keys: ['brightness'] };
const online = { device_id: 'headset', label: 'DEV-003', status: 'online', configuration_supported: true };
const applied = { ...d1, status: 'complete', results: { brightness: { status: 'applied', value: 60 } } };
const failed = { ...applied, results: { brightness: { status: 'failed', reason: 'WRITE_FAILED' } } };
const unsupported = { ...applied, results: { brightness: { status: 'unsupported' } } };

test('device status uses published keys and requires both IDs for current results and Retry', () => {
  const device = (report, overrides) => ({ ...online, configuration_state: report, ...overrides });
  const status = (report, deployment = d1, overrides) => deviceOutcome(device(report, overrides), pkg.package_id, deployment)[1];
  assert.equal(status(applied), 'Applied');
  assert.equal(status(applied, { ...d1, keys: ['brightness', 'wifi'] }), 'Partially applied');
  assert.equal(status(applied, d1, { status: 'offline' }), 'Applied · Offline');
  assert.equal(status(applied, d1, { configuration_supported: false }), 'Client update needed');
  assert.equal(status({ ...applied, package_id: 'other' }), 'Pending');
  assert.equal(status({ ...applied, revision: 'old' }, d1, { status: 'offline' }), 'Pending · Offline');
  assert.equal(status({ ...applied, status: 'interrupted' }), 'Interrupted');
  assert.equal(status({ ...applied, status: 'applying' }), 'Applying');
  assert.deepEqual(deviceOutcome(device({ ...applied, status: 'already_attempted' }), pkg.package_id, d1).slice(0, 2),
    ['', 'Already attempted this boot']);
  assert.equal(status(applied, null), 'Awaiting publication');
  assert.equal(status(unsupported), 'Unsupported');
  assert.equal(deviceOutcome(online, null, null)[1], 'No package');
  for (const report of [failed, unsupported, { ...applied, status: 'interrupted' }]) {
    assert.equal(retryEligible(device(report), d1), true);
    assert.equal(retryEligible(device({ ...report, package_id: 'other' }), d1), false);
    assert.equal(retryEligible(device({ ...report, revision: 'old' }), d1), false);
    assert.equal(retryEligible(device(report, { status: 'offline' }), d1), false);
    assert.equal(retryEligible(device(report, { configuration_supported: false }), d1), false);
  }
  assert.equal(retryEligible(device(applied), d1), false);
  assert.equal(retryEligible(device({ ...failed, status: 'applying' }), d1), false);
  assert.equal(retryEligible(device({ ...failed, status: 'already_attempted' }), d1), false);
});

test('a broadcast does not reselect an untouched device moved to another group', () => {
  assert.deepEqual(syncMemberSelection(['moved', 'remove'], ['remove', 'remote-add'], new Set(['moved', 'local-add'])),
    new Set(['remote-add', 'local-add']));
});

function response(data, status = 200) {
  return { status, ok: status < 400, json: async () => data };
}

function profile({
  id = 'pkg', name = 'Studio', draftRevision = 'draft1', publishedRevision = 'r0',
  settings = { brightness: { managed: true, value: 60 } },
  impact = { groups: 2, devices: 5, token: 'impact1' },
} = {}) {
  return { package_id: id, name, draft_revision: draftRevision, published_revision: publishedRevision,
    published_keys: publishedRevision ? ['brightness'] : [], settings, sections, impact };
}

function createHarness(respond, { devices = [], groups = { 'The Lab': ['headset'] }, confirm = () => true } = {}) {
  const createElement = overrides => ({
    innerHTML: '', textContent: '', value: '', hidden: false, disabled: false,
    classList: { toggle() {} },
    querySelectorAll: () => [],
    handlers: new Map(),
    addEventListener(type, handler) { this.handlers.set(type, handler); },
    ...overrides,
  });
  const editorButtons = Object.fromEntries(['save', 'publish', 'discard', 'reload', 'delete'].map(action =>
    [action, createElement({ dataset: { policyAction: action } })]));
  const groupButtons = Object.fromEntries(['assign', 'edit', 'retry'].map(action =>
    [action, createElement({ dataset: { groupAction: action, deviceId: 'headset' } })]));
  const editorControls = {
    '.settings-editor': createElement(),
    '.settings-actions': { querySelector: selector => selector === '.settings-meta' ? footerStatus :
      editorButtons[selector.match(/"([^"]+)"/)[1]] },
    '[data-package-name]': createElement(),
    '[data-package-publication]': createElement(),
    '[data-settings-feedback]': createElement(),
    ...Object.fromEntries(Object.entries(editorButtons).map(([key, button]) => [`[data-policy-action="${key}"]`, button])),
  };
  const footerStatus = createElement();
  const groupControls = {
    '[data-group-package]': createElement({ matches: () => true }),
    '[data-assignment-feedback]': createElement(),
    '[data-group-publication]': createElement(),
    '[data-settings-results]': createElement(),
    ...Object.fromEntries(Object.entries(groupButtons).map(([key, button]) => [`[data-group-action="${key}"]`, button])),
  };
  const packagePanel = createElement({
    querySelector: selector => packagePanel.innerHTML.includes('settings-editor') ? editorControls[selector] : null,
  });
  const groupPanel = createElement({ querySelector: selector => groupControls[selector] });
  const packageList = createElement();
  const createControls = {
    input: createElement({ focus() { this.focused = true; } }),
    button: createElement(),
    '[role="status"]': createElement(),
  };
  const createForm = createElement({ querySelector: selector => createControls[selector] });
  const requests = [];
  const confirmations = [];
  let opened = 0;
  const controller = createController({
    groupPanel, packagePanel, packageList, createForm,
    getGroups: () => groups, getDevices: () => devices,
    openPackages: () => { opened++; },
    confirmAction: message => { confirmations.push(message); return confirm(message); },
    fetch: async (url, init) => {
      const request = { url, method: init.method, body: init.body && JSON.parse(init.body) };
      requests.push(request);
      return respond(request);
    },
  });
  const valueField = { disabled: true };
  const edit = ({ dataset, value, checked, type }) => packagePanel.handlers.get('input')({ target: {
    dataset, value, checked, type, matches: () => false, nextElementSibling: {},
    closest: selector => selector === '.settings-row' ? { querySelector: () => valueField } :
      { dataset: { settingsSection: 'basic' }, querySelector: () => ({}) },
  } });
  const editClick = action => packagePanel.handlers.get('click')({ target: { closest: () => editorButtons[action] } });
  const groupClick = action => groupPanel.handlers.get('click')({ target: { closest: () => groupButtons[action] } });
  const select = id => {
    const control = groupControls['[data-group-package]'];
    control.value = id;
    groupPanel.handlers.get('change')({ target: control });
  };
  const broadcast = ({ packages = [pkg, pkg2], assignments = {}, deployments = {} } = {}) => {
    controller.updateSnapshot({ packages, assignments, deployments });
  };
  const create = async name => {
    createControls.input.value = name;
    await createForm.handlers.get('submit')({ preventDefault() {} });
  };
  return {
    controller, packagePanel, packageList, edit, editClick, editorButtons, editorControls, groupControls,
    groupClick, groupButtons, select, broadcast, create, createControls, requests, confirmations, valueField, footerStatus,
    opened: () => opened,
  };
}

test('create, edit the package name and settings, save the draft, then publish with confirmed current counts', async () => {
  let draft = profile({ draftRevision: null, publishedRevision: null, settings: {} });
  const fixture = createHarness(({ method, url, body }) => {
    if (url === '/api/packages' && method === 'POST') return response(draft);
    if (method === 'GET') return response(draft);
    if (method === 'PUT') {
      draft = { ...draft, name: body.name, settings: body.settings, draft_revision: 'draft2' };
    } else {
      draft = { ...draft, published_revision: 'r1' };
    }
    return response(draft);
  });
  fixture.broadcast({ packages: [] });
  fixture.controller.showPackages();
  await fixture.create(' Studio ');
  assert.match(fixture.packageList.innerHTML, /Studio.*New/s);
  assert.match(fixture.packagePanel.innerHTML, /data-manage="brightness".*?<\/label>\s*<div class="settings-label"><strong>Screen brightness<\/strong>.*?<\/div>\s*<fieldset class="settings-value" aria-label="Screen brightness" disabled>/s);
  assert.equal(fixture.editorButtons.publish.disabled, true);
  fixture.edit({ dataset: { packageName: '' }, value: 'Day studio' });
  fixture.edit({ dataset: { manage: 'brightness' }, checked: true });
  assert.equal(fixture.valueField.disabled, false);
  fixture.edit({ dataset: { setting: 'brightness' }, type: 'range', value: '81' });
  fixture.edit({ dataset: { manage: 'brightness' }, checked: false });
  assert.equal(fixture.valueField.disabled, true);
  await fixture.editClick('publish');
  assert.equal(fixture.requests.length, 1);
  await fixture.editClick('save');
  assert.deepEqual(fixture.requests[1].body.settings.brightness, { managed: false, value: 81 });
  assert.equal(fixture.requests[1].body.name, 'Day studio');
  assert.equal(fixture.requests[1].body.expected_revision, null);
  assert.equal(fixture.editorButtons.publish.disabled, false);
  await fixture.editClick('publish');
  assert.deepEqual(fixture.requests.map(({ url, method }) => [url, method]), [
    ['/api/packages', 'POST'], ['/api/packages/pkg', 'PUT'], ['/api/packages/pkg', 'GET'],
    ['/api/packages/pkg/publish', 'POST'],
  ]);
  assert.deepEqual(fixture.requests[0].body, { name: 'Studio' });
  assert.deepEqual(fixture.requests[3].body, { expected_revision: 'draft2', expected_impact: 'impact1' });
  assert.match(fixture.confirmations[0], /Day studio.*2 groups and 5 devices/);
  assert.match(fixture.confirmations[0], /includes offline devices/);
  assert.match(fixture.editorControls['[data-package-publication]'].textContent, /Published revision r1/);
});

test('a stale impact never republishes automatically and a subsequent confirmation uses the refreshed counts and token', async () => {
  let latest = profile();
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    if (body.expected_impact === 'impact1') {
      latest = profile({ impact: { groups: 1, devices: 4, token: 'impact2' } });
      return response({ error: 'Impact changed.' }, 409);
    }
    return response({ ...latest, published_revision: 'r2' });
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  await fixture.editClick('publish');
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /publish targets changed/);
  assert.equal(fixture.editorButtons.publish.disabled, true);
  await fixture.editClick('publish');
  assert.equal(fixture.requests.filter(request => request.method === 'POST').length, 1);
  await fixture.editClick('reload');
  await fixture.editClick('publish');
  assert.deepEqual(fixture.requests.at(-1).body, { expected_revision: 'draft1', expected_impact: 'impact2' });
  assert.match(fixture.confirmations.at(-1), /1 group and 4 devices/);
});

test('a revision changed by another console blocks publication before confirmation', async () => {
  let latest = profile();
  const fixture = createHarness(() => response(latest));
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  latest = profile({ draftRevision: 'remote' });
  await fixture.editClick('publish');
  assert.equal(fixture.confirmations.length, 0);
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /The saved draft changed/);
  assert.equal(fixture.editorButtons.reload.hidden, false);
  assert.equal(fixture.editorButtons.publish.disabled, true);
});

test('a save conflict rebases unsaved name and setting edits onto the latest draft', async () => {
  let latest = profile();
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    if (body.expected_revision === 'draft1') {
      latest = profile({ draftRevision: 'remote', settings: {
        brightness: { managed: true, value: 62 }, autoSleep: { managed: true, value: true },
      } });
      return response({ error: 'Draft changed.' }, 409);
    }
    return response({ ...latest, draft_revision: 'draft2', settings: body.settings, name: body.name });
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  fixture.edit({ dataset: { packageName: '' }, value: 'Local name' });
  fixture.edit({ dataset: { setting: 'brightness' }, type: 'range', value: '86' });
  await fixture.editClick('save');
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /changed elsewhere/);
  assert.equal(fixture.editorButtons.save.disabled, true);
  await fixture.editClick('reload');
  assert.match(fixture.packagePanel.innerHTML, /data-package-name[^>]+value="Local name"/);
  assert.match(fixture.packagePanel.innerHTML, /type="range"[^>]+value="86"/);
  assert.equal(fixture.editorButtons.publish.disabled, true);
  await fixture.editClick('save');
  assert.equal(fixture.requests.at(-1).body.expected_revision, 'remote');
  assert.equal(fixture.requests.at(-1).body.name, 'Local name');
  assert.deepEqual(fixture.requests.at(-1).body.settings.autoSleep, { managed: true, value: true });
});

test('a lost publish response requires reloading the package before publishing again', async () => {
  let latest = profile();
  const fixture = createHarness(({ method }) => {
    if (method === 'GET') return response(latest);
    latest = profile({ publishedRevision: 'r1' });
    throw new Error('Connection closed before the response.');
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  await fixture.editClick('publish');
  assert.equal(fixture.editorButtons.publish.disabled, true);
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /Could not confirm/);
  fixture.broadcast({ packages: [{ ...pkg, published_revision: 'r1' }, pkg2] });
  await fixture.editClick('reload');
  assert.match(fixture.editorControls['[data-package-publication]'].textContent, /Published revision r1/);
  assert.equal(fixture.editorButtons.publish.disabled, false);
});

test('WS catalog updates do not repaint draft inputs or yield to an older HTTP save response', async () => {
  let finishSave;
  const fixture = createHarness(({ method }) => method === 'GET' ? response(profile()) :
    new Promise(resolve => { finishSave = resolve; }));
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  fixture.edit({ dataset: { setting: 'brightness' }, type: 'range', value: '63' });
  const saving = fixture.editClick('save');
  const before = fixture.packagePanel.innerHTML;
  fixture.broadcast({ packages: [{ ...pkg, name: 'Remote name', published_revision: 'newer' }, pkg2] });
  assert.equal(fixture.packagePanel.innerHTML, before);
  assert.match(fixture.editorControls['[data-package-publication]'].textContent, /Published revision newer/);
  finishSave(response(profile({ draftRevision: 'draft2', settings: { brightness: { managed: true, value: 63 } } })));
  await saving;
  assert.match(fixture.packagePanel.innerHTML, /type="range"[^>]+value="63"/);
  assert.match(fixture.packageList.innerHTML, /Remote name/);
  assert.match(fixture.editorControls['[data-package-publication]'].textContent, /Published revision newer/);
});

test('the final assignment option opens package creation and preserves a staged assignment', () => {
  const fixture = createHarness(() => assert.fail('Choosing the creation option should not call the API'));
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' } });
  fixture.controller.renderGroup('The Lab');
  const selection = fixture.groupControls['[data-group-package]'];
  const createOption = selection.innerHTML.match(/<option value="([^"]+)">Create package…<\/option>$/);
  assert.ok(createOption, 'Create package should be the last option');

  fixture.select('pkg2');
  fixture.select(createOption[1]);
  assert.equal(fixture.opened(), 1);
  assert.equal(fixture.createControls.input.focused, true);
  fixture.controller.renderGroup(null);
  fixture.controller.renderGroup('The Lab');
  assert.equal(selection.value, 'pkg2');
});

test('package creation shows the HTTP status of a non-JSON failure', async () => {
  const fixture = createHarness(() => ({
    ok: false,
    status: 404,
    json: async () => { throw new SyntaxError('Not JSON'); },
  }));
  fixture.broadcast({ packages: [] });
  await fixture.create('Studio');
  assert.equal(fixture.createControls['[role="status"]'].textContent, 'Request failed (HTTP 404).');
  assert.equal(fixture.createControls.input.value, 'Studio');
});

test('group assignment uses the saved package for status and preserves a staged selection across broadcasts', async () => {
  let finishAssignment;
  const name = 'lab/+ Wi-Fi?&';
  const fixture = createHarness(({ method }) => method === 'GET' ? response(profile()) :
    new Promise(resolve => { finishAssignment = resolve; }), {
    groups: { [name]: ['headset'] }, devices: [{ ...online, configuration_state: applied }],
  });
  const groupBroadcast = (assigned, deployment) => fixture.broadcast({
    assignments: { [name]: assigned }, deployments: deployment ? { [name]: deployment } : {},
  });
  const selection = fixture.groupControls['[data-group-package]'];
  const results = fixture.groupControls['[data-settings-results]'];
  groupBroadcast('pkg', d1);
  fixture.controller.renderGroup(name);
  assert.equal(selection.value, 'pkg');
  assert.match(results.innerHTML, /All managed settings were verified/);
  fixture.select('pkg2');
  groupBroadcast('pkg', d1);
  fixture.controller.renderGroup(name);
  assert.equal(selection.value, 'pkg2');
  assert.match(results.innerHTML, /All managed settings were verified/);
  const saving = fixture.groupClick('assign');
  assert.deepEqual(fixture.requests.at(-1), {
    url: '/api/group-package?name=lab%2F%2B%20Wi-Fi%3F%26', method: 'PUT', body: { package_id: 'pkg2' },
  });
  groupBroadcast('pkg2');
  fixture.controller.renderGroup(name);
  assert.match(results.innerHTML, /Awaiting publication/);
  groupBroadcast('pkg', d1);
  fixture.controller.renderGroup(name);
  finishAssignment(response({ package_id: 'pkg2' }));
  await saving;
  assert.equal(selection.value, 'pkg');
  assert.match(results.innerHTML, /All managed settings were verified/);
  await fixture.groupClick('edit');
  assert.equal(fixture.opened(), 1);
});

test('Retry sends exactly the current device, package and revision and ignores repeat clicks in flight', async () => {
  const devices = [{ ...online, configuration_state: failed }];
  let finishRetry;
  const fixture = createHarness(({ method }) => method === 'GET' ? response(profile()) :
    new Promise(resolve => { finishRetry = resolve; }), { devices });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': d1 } });
  fixture.controller.renderGroup('The Lab');
  const results = fixture.groupControls['[data-settings-results]'];
  assert.match(results.innerHTML, /Apply failed/);
  assert.match(results.innerHTML, />Retry</);
  const retry = fixture.groupClick('retry');
  fixture.groupClick('retry');
  assert.equal(fixture.requests.filter(request => request.method === 'POST').length, 1);
  assert.deepEqual(fixture.requests.at(-1), {
    url: '/api/package-retry', method: 'POST',
    body: { device_id: 'headset', package_id: 'pkg', revision: 'r1' },
  });
  assert.match(results.innerHTML, /Retry requested/);
  devices[0].configuration_state = { ...failed, status: 'applying' };
  fixture.controller.renderGroup('The Lab');
  assert.doesNotMatch(results.innerHTML, /data-group-action="retry"/);
  finishRetry(response({ sent: true }));
  await retry;
  devices[0].configuration_state = { ...failed, package_id: 'pkg2' };
  fixture.controller.renderGroup('The Lab');
  assert.doesNotMatch(results.innerHTML, /WRITE_FAILED|write failed|data-group-action="retry"/);
});

test('deletion displays a server reference conflict and removes the package after successful deletion', async () => {
  let referenced = true;
  const fixture = createHarness(({ method }) => method === 'GET' ? response(profile()) :
    referenced ? response({ error: 'Unassign this package before deleting it.' }, 409) : response({ deleted: true }));
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  await fixture.editClick('delete');
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /Unassign this package/);
  assert.equal(fixture.editorButtons.reload.hidden, true);
  referenced = false;
  await fixture.editClick('delete');
  assert.equal(fixture.requests.at(-1).method, 'DELETE');
  assert.doesNotMatch(fixture.packageList.innerHTML, /data-package-id="pkg"/);
  assert.match(fixture.packagePanel.innerHTML, /Select a configuration package/);
});
