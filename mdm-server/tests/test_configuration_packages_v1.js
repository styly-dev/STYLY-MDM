const assert = require('node:assert/strict');
const test = require('node:test');
const {
  createController, loadDraft, buildPayload, deviceOutcome, retryEligible, syncMemberSelection,
  clearMembershipEditsThatMatch, expectedMembershipOwner, buildMembershipEdit,
} = require('../styly_mdm/static/configuration-packages-v1.js');

const sections = [
  { id: 'basic', title: 'Basic settings', description: 'Display and power', items: [
    { id: 'brightness', label: 'Screen brightness', kind: 'range', min: 1, max: 100, default: 50 },
    { id: 'autoSleep', label: 'Auto sleep', kind: 'bool', default: false },
    { id: 'sleepDelay', label: 'Sleep delay', kind: 'delay', default: 10, options: [
      { value: 10, label: '10 minutes' }, { value: 20, label: '20 minutes' },
    ] },
    { id: 'screenOff', label: 'Screen off delay', kind: 'delay', default: -1, options: [
      { value: -1, label: 'Never' }, { value: 5, label: '5 minutes' }, { value: 20, label: '20 minutes' },
    ] },
    { id: 'shutdown', label: 'Scheduled shutdown', kind: 'schedule', default: { enabled: false, hour: 0, minute: 0, repeat: 127 } },
  ] },
  { id: 'network', title: 'Network & connectivity', items: [
    { id: 'wifi', label: 'Wi-Fi networks', kind: 'wifi', default: { networks: [], auto_return: false } },
  ] },
  { id: 'advanced', title: 'USB & advanced', items: [
    { id: 'developerMenu', label: 'Developer options menu', kind: 'bool', default: false },
  ] },
];
const wifiNetwork = { ssid: 'Studio', security: 'WPA2_WPA3', hidden: false, password_present: true };

test('metadata defaults are visible in the draft and unmanaged', () => {
  const saved = loadDraft(sections, {});
  const draft = structuredClone(saved);
  assert.deepEqual(saved.brightness, { managed: false, value: 50 });
  assert.deepEqual(saved.wifi, { managed: false, value: { networks: [], auto_return: false } });
  assert.deepEqual(saved.developerMenu, { managed: false, value: false });
  draft.brightness.value = 64;
  draft.shutdown.value = { enabled: true, hour: 23, minute: 10, repeat: 65 };
  const payload = buildPayload(sections, draft, saved);
  assert.deepEqual(payload.brightness, { managed: false, value: 64 });
  assert.deepEqual(payload.shutdown, { managed: false, value: { enabled: true, hour: 23, minute: 10, repeat: 65 } });
  assert.deepEqual(payload.developerMenu, { managed: false, value: false });
  assert.deepEqual(payload.wifi.value, { networks: [], auto_return: false });
  assert.equal(saved.shutdown.value.repeat, 127);
  assert.equal(sections[0].items.find(item => item.id === 'shutdown').default.repeat, 127);
});

test('inactive schedules fill only cleared time parts from saved time or catalog defaults', () => {
  const saved = loadDraft(sections, { shutdown: { managed: true, value: {
    enabled: true, hour: 21, minute: 45, repeat: 127,
  } } });
  const draft = structuredClone(saved);
  draft.shutdown.value = { enabled: false, hour: null, minute: 30, repeat: 65 };
  assert.deepEqual(buildPayload(sections, draft, saved).shutdown.value,
    { enabled: false, hour: 21, minute: 30, repeat: 65 });

  draft.shutdown.managed = false;
  draft.shutdown.value = { enabled: true, hour: 8, minute: null, repeat: 0 };
  assert.deepEqual(buildPayload(sections, draft, saved).shutdown,
    { managed: false, value: { enabled: true, hour: 8, minute: 45, repeat: 127 } });

  const defaults = loadDraft(sections, {});
  const defaultDraft = structuredClone(defaults);
  defaultDraft.shutdown.value = { enabled: false, hour: null, minute: null, repeat: 127 };
  assert.deepEqual(buildPayload(sections, defaultDraft, defaults).shutdown.value,
    { enabled: false, hour: 0, minute: 0, repeat: 127 });

  draft.shutdown.managed = true;
  draft.shutdown.value = { enabled: true, hour: null, minute: 30, repeat: 127 };
  assert.throws(() => buildPayload(sections, draft, saved), /Enter a time for Scheduled shutdown/);
});

test('managed writes reject values outside the catalog', () => {
  for (const [id, value] of [['brightness', 0], ['sleepDelay', 15], ['screenOff', 10]]) {
    const saved = loadDraft(sections, {});
    const draft = structuredClone(saved);
    draft[id] = { managed: true, value };
    assert.throws(() => buildPayload(sections, draft, saved), /Select a supported value/);
  }
});

test('managed Wi-Fi preserves stored passwords without exposing them and reorders whole candidates', async () => {
  let latest = profile({ settings: { wifi: { managed: true, value: {
    auto_return: false,
    networks: [
      { ssid: 'Studio', security: 'WPA2_WPA3', hidden: false, password_present: true },
      { ssid: 'Backup', security: 'WPA2_WPA3', hidden: false, password_present: false },
    ],
  } } } });
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    const previousNetworks = latest.settings.wifi.value.networks;
    const wifiValue = body.settings.wifi.value;
    const networks = wifiValue.networks.map(({ password, ...network }) => ({
      ...network,
      password_present: network.security === 'WPA2_WPA3' && Boolean(password || previousNetworks.some(candidate =>
        candidate.ssid === network.ssid && candidate.security === network.security && candidate.password_present)),
    }));
    latest = profile({ settings: { ...body.settings, wifi: { ...body.settings.wifi, value: { ...wifiValue, networks } } },
      draftRevision: 'draft2', publishedRevision: 'r1' });
    return response(latest);
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  assert.doesNotMatch(fixture.packagePanel.innerHTML, /stored-secret|backup-secret/);
  assert.match(fixture.packagePanel.innerHTML, /1–63 ASCII characters/);
  assert.match(fixture.packagePanel.innerHTML, /1–7 is WPA3-only/);
  assert.match(fixture.packagePanel.innerHTML, /aria-label="Move Wi-Fi 1 down"[^>]*>Down/);

  fixture.edit({ dataset: { setting: 'wifi', network: '1', field: 'password' }, type: 'password', value: 'backup-secret' });
  fixture.policyClick({ policyAction: 'move-network', setting: 'wifi', network: '0', direction: 'down' });
  await fixture.editClick('deploy');

  const posted = fixture.requests.at(-1).body.settings;
  assert.deepEqual(posted.wifi.value.networks.map(({ ssid, password }) => [ssid, password]), [
    ['Backup', 'backup-secret'], ['Studio', undefined],
  ]);
  assert.equal(posted.wifi.value.auto_return, false);
  assert.equal(Object.hasOwn(posted, 'wifiPriority'), false);
  assert.deepEqual(latest.settings.wifi.value.networks.map(({ ssid, password_present }) => [ssid, password_present]), [
    ['Backup', true], ['Studio', true],
  ]);
  assert.doesNotMatch(fixture.packagePanel.innerHTML, /backup-secret/);
});

test('Wi-Fi accepts short WPA3-only ASCII passwords and rejects invalid values', () => {
  const saved = loadDraft(sections, { wifi: { managed: true, value: {
    auto_return: false,
    networks: [{ ssid: 'Short', security: 'WPA2_WPA3', hidden: false, password_present: false }],
  } } });
  const draft = structuredClone(saved);
  draft.wifi.value.networks[0].password = '1234567';
  assert.deepEqual(buildPayload(sections, draft, saved).wifi.value.networks, [{
    ssid: 'Short', security: 'WPA2_WPA3', hidden: false, password: '1234567',
  }]);

  draft.wifi.value.networks[0].password = 'éclair';
  assert.throws(() => buildPayload(sections, draft, saved), /1 to 63 ASCII characters/);
  draft.wifi.value.networks[0].password = 'a'.repeat(64);
  assert.throws(() => buildPayload(sections, draft, saved), /1 to 63 ASCII characters/);
  draft.wifi.value.networks[0].password = '';
  assert.throws(() => buildPayload(sections, draft, saved), /Enter a 1 to 63 character ASCII password/);
});

test('auto-return is stored inside the Wi-Fi setting and hidden networks are rejected', async () => {
  let latest = profile({ settings: { wifi: { managed: true, value: {
    auto_return: false,
    networks: [{ ssid: 'Studio', security: 'OPEN', hidden: false, password_present: false }],
  } } } });
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    latest = profile({ settings: body.settings, draftRevision: 'draft2', publishedRevision: 'r1' });
    return response(latest);
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  assert.match(fixture.packagePanel.innerHTML, /data-manage="wifi"/);
  assert.doesNotMatch(fixture.packagePanel.innerHTML, /wifiPriority|priorityManage|Enable Wi-Fi priority/);
  assert.match(fixture.packagePanel.innerHTML, /data-setting="wifi" data-field="auto_return"/);
  assert.match(fixture.packagePanel.innerHTML,
    /about every 5 minutes while connected below the first choice, including after manual selection or fallback\. The OS may delay switching; no priority scan is requested while on the first choice/);

  fixture.edit({ dataset: { setting: 'wifi', field: 'auto_return' }, checked: true, type: 'checkbox' });
  await fixture.editClick('deploy');
  assert.deepEqual(fixture.requests.at(-1).body.settings.wifi.value, {
    auto_return: true,
    networks: [{ ssid: 'Studio', security: 'OPEN', hidden: false }],
  });
  assert.equal(Object.hasOwn(fixture.requests.at(-1).body.settings, 'wifiPriority'), false);

  fixture.edit({ dataset: { setting: 'wifi', network: '0', field: 'hidden' }, checked: true, type: 'checkbox' });
  assert.equal(fixture.wifiHiddenWarning.hidden, false);
  const requestCount = fixture.requests.length;
  await fixture.editClick('deploy');
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent,
    /Automatic return is not supported with hidden Wi-Fi networks/);
  assert.equal(fixture.requests.length, requestCount);

  const hiddenSaved = loadDraft(sections, { wifi: { managed: true, value: {
    auto_return: false,
    networks: [{ ssid: 'Hidden', security: 'OPEN', hidden: true, password_present: false }],
  } } });
  const hiddenDraft = structuredClone(hiddenSaved);
  hiddenDraft.wifi.value.auto_return = true;
  assert.throws(() => buildPayload(sections, hiddenDraft, hiddenSaved), /not supported with hidden Wi-Fi networks/);
  hiddenDraft.wifi.value.auto_return = false;
  assert.doesNotThrow(() => buildPayload(sections, hiddenDraft, hiddenSaved));
});

test('managed Wi-Fi allows at most four candidates in the editor and payload', async () => {
  const networks = Array.from({ length: 3 }, (_, index) => ({
    ssid: `Network-${index + 1}`, security: 'OPEN', hidden: false, password_present: false,
  }));
  let latest = profile({ settings: { wifi: { managed: true, value: { auto_return: false, networks } } } });
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    latest = profile({ settings: body.settings, draftRevision: 'draft2', publishedRevision: 'r1' });
    return response(latest);
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  assert.equal(fixture.addNetworkButton.disabled, false);
  fixture.policyClick({ policyAction: 'add-network', setting: 'wifi' });
  assert.equal((fixture.packagePanel.innerHTML.match(/class="wifi-network"/g) || []).length, 4);
  assert.equal(fixture.addNetworkButton.disabled, true);
  fixture.policyClick({ policyAction: 'add-network', setting: 'wifi' });
  assert.equal((fixture.packagePanel.innerHTML.match(/class="wifi-network"/g) || []).length, 4);
  fixture.edit({ dataset: { setting: 'wifi', network: '3', field: 'ssid' }, value: 'Network-4', type: 'text' });
  await fixture.editClick('deploy');
  assert.deepEqual(fixture.requests.at(-1).body.settings.wifi.value.networks.map(network => network.ssid),
    ['Network-1', 'Network-2', 'Network-3', 'Network-4']);

  const saved = loadDraft(sections, { wifi: { managed: true, value: { auto_return: false, networks } } });
  const overflow = structuredClone(saved);
  overflow.wifi.value.networks.push(
    { ssid: 'Network-4', security: 'OPEN', hidden: false, password: '' },
    { ssid: 'Network-5', security: 'OPEN', hidden: false, password: '' },
  );
  assert.throws(() => buildPayload(sections, overflow, saved), /Wi-Fi supports up to 4 networks/);
});

test('a new package gets its first Wi-Fi network after Manage and reopens with deploy available', async () => {
  let latest = profile({ settings: {} });
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    const networks = body.settings.wifi.value.networks.map(({ password, ...network }) => ({
      ...network, password_present: Boolean(password),
    }));
    latest = profile({ settings: { ...body.settings, wifi: { ...body.settings.wifi,
      value: { ...body.settings.wifi.value, networks } } }, draftRevision: 'draft2', publishedRevision: 'r1' });
    return response(latest);
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  assert.equal(fixture.addNetworkButton.disabled, true);

  fixture.edit({ dataset: { manage: 'wifi' }, checked: true });
  assert.equal(fixture.valueField.disabled, false);
  assert.equal(fixture.addNetworkButton.disabled, false);

  fixture.policyClick({ policyAction: 'add-network', setting: 'wifi' });
  fixture.edit({ dataset: { setting: 'wifi', network: '0', field: 'ssid' }, value: 'Studio', type: 'text' });
  fixture.edit({ dataset: { setting: 'wifi', network: '0', field: 'security' }, value: 'WPA2_WPA3', type: 'select-one' });
  fixture.edit({ dataset: { setting: 'wifi', network: '0', field: 'password' }, value: 'studio-pass', type: 'password' });
  // Rendering a second row must restore the typed password into the first row's new input.
  fixture.policyClick({ policyAction: 'add-network', setting: 'wifi' });
  assert.deepEqual(fixture.passwordInputs().map(input => input.value), ['studio-pass', '']);
  fixture.policyClick({ policyAction: 'remove-network', setting: 'wifi', network: '1' });
  await fixture.editClick('deploy');
  assert.deepEqual(fixture.requests.at(-1).body.settings.wifi, { managed: true, value: {
    auto_return: false, networks: [{ ssid: 'Studio', security: 'WPA2_WPA3', hidden: false, password: 'studio-pass' }],
  } });

  await fixture.controller.showPackages('pkg');
  assert.deepEqual(fixture.passwordInputs().map(input => input.value), ['']);
  assert.equal(fixture.editorButtons.deploy.disabled, false);
  assert.doesNotMatch(fixture.packagePanel.innerHTML, /studio-pass/);
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
  assert.equal(status(applied, null), 'Waiting for deployment');
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

test('Wi-Fi deployment does not use the removed Wi-Fi priority client gate', () => {
  const wifiDeployment = { ...d1, keys: ['wifi'] };
  const wifiApplied = { ...wifiDeployment, status: 'complete', results: { wifi: { status: 'applied' } } };
  const device = { ...online, configuration_state: wifiApplied };
  assert.equal(deviceOutcome(device, pkg.package_id, wifiDeployment)[1], 'Applied');
});

test('Wi-Fi recovery status stays failed until the original connection is verified', () => {
  const deployment = { ...d1, keys: ['brightness', 'wifi'] };
  const report = { ...deployment, status: 'complete', results: {
    brightness: { status: 'applied', value: 60 },
    wifi: { status: 'failed', reason: 'RESTORE_UNCONFIRMED', rolled_back: false },
  } };
  const devices = [{ ...online, configuration_state: report, wifi_state: { managed: false, last_result: {
    operation_id: '11111111-1111-4111-8111-111111111111', revision: deployment.revision,
    status: 'failed', reason: 'RESTORE_UNCONFIRMED', rolled_back: false,
  } } }];
  const fixture = createHarness(() => assert.fail('rendering results should not call the API'), { devices });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': deployment } });
  fixture.controller.renderGroup('The Lab');

  let results = fixture.groupControls['[data-settings-results]'].innerHTML;
  assert.match(results, /Partially applied/);
  assert.match(results, /Results at apply time/);
  assert.match(results, /wifi · Failed · Wi-Fi settings restored; original SSID and usable IP not confirmed/);
  assert.match(results, /Latest Wi-Fi operation: recovery pending/);
  assert.doesNotMatch(results, /wifi · Applied|Rolled back/);
  assert.equal(deviceOutcome(devices[0], 'pkg', deployment)[1], 'Partially applied');
  assert.equal(retryEligible(devices[0], deployment), true);

  devices[0].wifi_state.last_result = {
    operation_id: '11111111-1111-4111-8111-111111111111', revision: deployment.revision,
    status: 'failed', reason: 'TIMEOUT_ROLLED_BACK', rolled_back: true,
  };
  fixture.controller.renderGroup('The Lab');
  results = fixture.groupControls['[data-settings-results]'].innerHTML;
  assert.match(results, /wifi · Failed · Wi-Fi settings restored; original SSID and usable IP not confirmed/);
  assert.match(results, /Latest Wi-Fi operation: recovery confirmed/);
  assert.doesNotMatch(results, /wifi · Failed · timeout rolled back|recovery pending/);
  assert.equal(deviceOutcome(devices[0], 'pkg', deployment)[1], 'Partially applied');
  assert.equal(retryEligible(devices[0], deployment), true);
});

test('auto-return selection recovery reports latest Wi-Fi state without changing apply-time results', () => {
  const deployment = { ...d1, keys: ['wifi'] };
  const report = { ...deployment, status: 'complete', results: { wifi: { status: 'applied' } } };
  const devices = [{ ...online, configuration_state: report, wifi_state: { last_result: {
    operation_id: '22222222-2222-4222-8222-222222222222', revision: deployment.revision,
    status: 'failed', reason: 'RESTORE_UNCONFIRMED', rolled_back: false,
  } } }];
  const fixture = createHarness(() => assert.fail('rendering results should not call the API'), { devices });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': deployment } });
  fixture.controller.renderGroup('The Lab');

  let results = fixture.groupControls['[data-settings-results]'].innerHTML;
  assert.match(results, /Applied/);
  assert.match(results, /Results at apply time/);
  assert.match(results, /Latest Wi-Fi operation: recovery pending; settings are restored, but the previous connection \(if one existed\) is not confirmed\./);

  devices[0].wifi_state.last_result = {
    operation_id: '22222222-2222-4222-8222-222222222222', revision: deployment.revision,
    status: 'failed', reason: 'TIMEOUT_ROLLED_BACK', rolled_back: true,
    ssid: 'Private-network-name', ip: '192.0.2.8',
  };
  fixture.controller.renderGroup('The Lab');
  results = fixture.groupControls['[data-settings-results]'].innerHTML;
  assert.match(results, /Latest Wi-Fi operation: recovery confirmed; settings are restored, and the previous connection \(if one existed\) was verified\./);
  assert.match(results, /Results at apply time/);
  assert.match(results, /wifi · Applied/);
  assert.doesNotMatch(results, /Private-network-name|192\.0\.2\.8/);
  assert.doesNotMatch(results, /recovery pending/);
});

test('latest Wi-Fi recovery status is hidden for stale, offline, or invalid telemetry', () => {
  const deployment = { ...d1, keys: ['wifi'] };
  const report = { ...deployment, status: 'complete', results: { wifi: { status: 'applied' } } };
  const validPending = {
    operation_id: '33333333-3333-4333-8333-333333333333', revision: deployment.revision,
    status: 'failed', reason: 'RESTORE_UNCONFIRMED', rolled_back: false,
  };
  const devices = [{ ...online, configuration_state: report, wifi_state: { last_result: validPending } }];
  const fixture = createHarness(() => assert.fail('rendering results should not call the API'), { devices });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': deployment } });
  const render = () => {
    fixture.controller.renderGroup('The Lab');
    return fixture.groupControls['[data-settings-results]'].innerHTML;
  };

  assert.match(render(), /Latest Wi-Fi operation: recovery pending/);
  const invalidResults = [
    { ...validPending, operation_id: 'not-a-uuid' },
    { ...validPending, revision: 'old-revision' },
    { ...validPending, status: 'unknown' },
    { ...validPending, reason: 'PRIVATE_VALUE' },
    { ...validPending, rolled_back: 'false' },
    { ...validPending, reason: 'RESTORE_UNCONFIRMED', rolled_back: true },
    { ...validPending, reason: 'PRIVATE_VALUE', rolled_back: true },
  ];
  for (const lastResult of invalidResults) {
    devices[0].wifi_state.last_result = lastResult;
    assert.doesNotMatch(render(), /Latest Wi-Fi operation:/);
  }
  devices[0].wifi_state.last_result = validPending;
  devices[0].status = 'offline';
  assert.doesNotMatch(render(), /Latest Wi-Fi operation:/);
});

test('Group Settings reports live auto-return monitor status separately from applied revision results', () => {
  const deployment = { ...d1, keys: ['brightness', 'wifi'] };
  const report = { ...deployment, status: 'complete', results: {
    brightness: { status: 'applied', value: 60 }, wifi: { status: 'applied' },
  } };
  const devices = [{ ...online, configuration_state: report, wifi_state: { priority_monitor: 'degraded' } }];
  const fixture = createHarness(() => assert.fail('rendering results should not call the API'), {
    groups: { 'The Lab': ['headset'] }, devices,
  });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': deployment } });

  for (const [monitor, label] of [
    ['off', 'Not scanning'], ['monitoring', 'Monitoring'], ['degraded', 'Degraded'],
    ['switching', 'Switching'], ['unsupported', 'Unsupported'],
  ]) {
    devices[0].wifi_state = { priority_monitor: monitor };
    fixture.controller.renderGroup('The Lab');
    const results = fixture.groupControls['[data-settings-results]'].innerHTML;
    assert.match(results, /Applied/);
    assert.match(results, new RegExp('Wi-Fi auto-return monitor: ' + label + '\\.'));
    if (monitor === 'off') assert.doesNotMatch(results, /Wi-Fi auto-return monitor: Off\\./);
    assert.equal(fixture.groupControls['[data-deployment-count]'].textContent, 'Applied 1/1');
  }

  devices[0].wifi_state = {};
  fixture.controller.renderGroup('The Lab');
  assert.doesNotMatch(fixture.groupControls['[data-settings-results]'].innerHTML, /Wi-Fi auto-return monitor:/);
});

test('a broadcast does not reselect an untouched device moved to another group', () => {
  assert.deepEqual(syncMemberSelection(['moved', 'remove'], ['remove', 'remote-add'], new Set(['moved', 'local-add'])),
    new Set(['remote-add', 'local-add']));
});

test('membership edits include only local changes and preserve untouched remote moves', () => {
  const previous = ['moved', 'kept'];
  const selected = new Set(['moved', 'kept', 'local-add']);
  const edited = new Set(['local-add']);
  const currentGroups = { Studio: ['kept'], Gallery: ['moved'] };
  const current = new Set(currentGroups.Studio);
  const selection = syncMemberSelection(previous, currentGroups.Studio, selected, edited);
  const stagedOwners = new Map([
    ['local-add', expectedMembershipOwner('local-add', 'Studio', currentGroups)],
  ]);
  const payload = buildMembershipEdit('Studio', current, selection, currentGroups, stagedOwners, () => []);
  assert.deepEqual(payload, {
    type: 'UPDATE_GROUP_MEMBERS', name: 'Studio', add: { 'local-add': null }, remove: [],
  });
  assert.deepEqual(selection, new Set(['kept', 'local-add']));
});

test('membership additions reject a changed source group until reviewed again', () => {
  const original = { Studio: [], LabA: ['headset'] };
  const expectedOwners = new Map([['headset', expectedMembershipOwner('headset', 'Studio', original)]]);
  const changed = { Studio: [], LabB: ['headset'] };
  assert.throws(() => buildMembershipEdit('Studio', [], ['headset'], changed, expectedOwners, () => []),
    /Group membership changed/);
  assert.throws(() => expectedMembershipOwner('headset', 'Studio', {
    Studio: [], LabA: ['headset'], LabB: ['headset'],
  }), /conflicting group memberships/);
  assert.deepEqual(buildMembershipEdit('Studio', [], ['headset'], changed,
    new Map([['headset', 'LabB']]), () => []), {
    type: 'UPDATE_GROUP_MEMBERS', name: 'Studio', add: { headset: 'LabB' }, remove: [],
  });
});

test('group selection follows fresh unedited members and keeps staged removals deselected', () => {
  const selection = syncMemberSelection(
    ['remove', 'untouched'], ['remove', 'remote-add'], new Set(['untouched']), new Set(['remove']));
  assert.deepEqual(selection, new Set(['remote-add']));
});

test('a reverted local toggle is cleared before a later remote add', () => {
  const selected = new Set();
  const edited = new Set(['headset']);
  const owners = new Map([['headset', null]]);
  clearMembershipEditsThatMatch(selected, [], edited, owners);
  assert.deepEqual(edited, new Set());
  assert.deepEqual(owners, new Map());

  const next = syncMemberSelection([], ['headset'], selected, edited);
  assert.deepEqual(next, new Set(['headset']));
});

test('membership edits normalize legacy IDs but send the stored ID for removal', () => {
  const groups = { Studio: ['legacy-serial'] };
  const canonicalId = id => id === 'legacy-serial' ? 'device-uuid' : id;
  assert.deepEqual(buildMembershipEdit('Studio', ['legacy-serial'], [], groups, new Map(), () => ['legacy-serial'], canonicalId), {
    type: 'UPDATE_GROUP_MEMBERS', name: 'Studio', add: {}, remove: ['legacy-serial'],
  });
});

function response(data, status = 200) {
  return { status, ok: status < 400, json: async () => data };
}

function profile({
  id = 'pkg', name = 'Studio', draftRevision = 'draft1', publishedRevision = 'r0',
  settings = { brightness: { managed: true, value: 60 } },
  impact = { groups: 2, devices: 5, token: 'impact1' },
  itemSections = sections,
} = {}) {
  return { package_id: id, name, draft_revision: draftRevision, published_revision: publishedRevision,
    published_keys: publishedRevision ? ['brightness'] : [], settings, sections: itemSections, impact };
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
  const editorButtons = Object.fromEntries(['deploy', 'discard', 'reload', 'delete'].map(action =>
    [action, createElement({ dataset: { policyAction: action } })]));
  const groupButtons = Object.fromEntries(['edit', 'deploy', 'retry'].map(action =>
    [action, createElement({ dataset: { groupAction: action, deviceId: 'headset' } })]));
  const editorControls = {
    '.settings-editor': createElement(),
    '.settings-actions': { querySelector: selector => selector === '.settings-meta' ? footerStatus :
      editorButtons[selector.match(/"([^"]+)"/)[1]] },
    '[data-package-name]': createElement(),
    '[data-package-publication]': createElement(),
    '[data-deploy-hint]': createElement(),
    '[data-settings-feedback]': createElement(),
    ...Object.fromEntries(Object.entries(editorButtons).map(([key, button]) => [`[data-policy-action="${key}"]`, button])),
  };
  const footerStatus = createElement();
  const groupControls = {
    '[data-group-package]': createElement({ matches: () => true }),
    '[data-assignment-feedback]': createElement(),
    '[data-deploy-hint]': createElement(),
    '[data-group-publication]': createElement(),
    '[data-deployment-count]': createElement({ dataset: {} }),
    '[data-settings-results]': createElement(),
    ...Object.fromEntries(Object.entries(groupButtons).map(([key, button]) => [`[data-group-action="${key}"]`, button])),
  };
  // The rendered markup decides which password inputs exist and whether Add starts disabled,
  // as in the browser. Each render replaces the inputs, so restored values are observable.
  const addNetworkButton = { disabled: false };
  let panelHtml = '';
  let passwordInputs = [];
  const packagePanel = createElement({
    querySelector: selector => packagePanel.innerHTML.includes('settings-editor') ? editorControls[selector] : null,
    querySelectorAll: selector => selector === '[data-field="password"]' ? passwordInputs : [],
  });
  Object.defineProperty(packagePanel, 'innerHTML', {
    get: () => panelHtml,
    set(html) {
      panelHtml = html;
      passwordInputs = [...html.matchAll(/<input[^>]*data-field="password"[^>]*>/g)].map(([tag]) => ({
        dataset: { setting: tag.match(/data-setting="([^"]*)"/)[1], network: tag.match(/data-network="([^"]*)"/)[1] },
        value: '',
      }));
      addNetworkButton.disabled = /data-policy-action="add-network"[^>]*disabled/.test(html);
    },
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
    getGroups: () => groups,
    getDevice: id => devices.find(device => device.device_id === id) ||
      devices.find(device => device.serial_number === id),
    openPackages: () => { opened++; },
    confirmAction: message => { confirmations.push(message); return confirm(message); },
    fetch: async (url, init) => {
      const request = { url, method: init.method, body: init.body && JSON.parse(init.body) };
      requests.push(request);
      return respond(request);
    },
  });
  const moveButtons = [
    { dataset: { network: '0', direction: 'up' }, disabled: true },
    { dataset: { network: '0', direction: 'down' }, disabled: true },
    { dataset: { network: '1', direction: 'up' }, disabled: true },
    { dataset: { network: '1', direction: 'down' }, disabled: true },
  ];
  const wifiOrderNote = { textContent: '' };
  const wifiAutoReturnControl = { disabled: false };
  const wifiLimitWarning = { hidden: true };
  const wifiHiddenWarning = { hidden: true };
  const wifiArticle = { querySelector: selector => selector === '[data-field="password"]' ? { disabled: false, value: '' } : ({ textContent: '' }) };
  const valueField = { disabled: true, querySelector: selector => selector === '[data-wifi-limit-warning]' ? wifiLimitWarning :
    selector === '[data-wifi-hidden-warning]' ? wifiHiddenWarning :
      selector === '[data-policy-action="add-network"]' ? addNetworkButton : ({}), querySelectorAll: selector =>
    selector.includes('move-network') ? moveButtons : [] };
  const policyClick = dataset => {
    const button = { dataset, disabled: dataset.policyAction === 'add-network' ? addNetworkButton.disabled : false };
    return packagePanel.handlers.get('click')({ target: { closest: () => button } });
  };
  // A Manage checkbox sits beside the value fieldset, not inside it.
  const edit = ({ dataset, value, checked, type }) => packagePanel.handlers.get('input')({ target: {
    dataset, value, checked, type, matches: () => false, nextElementSibling: {},
    closest: selector => selector === '.settings-row' ? { querySelector: () => valueField } :
      selector === '.settings-value' ? (dataset.manage ? null : valueField) :
        selector === '.wifi-network' ? wifiArticle :
        { dataset: { settingsSection: 'basic' }, querySelector: () => ({}), querySelectorAll: () => [] },
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
    controller, packagePanel, packageList, edit, editClick, policyClick, editorButtons, editorControls, groupControls,
    groupClick, groupButtons, select, broadcast, create, createControls, requests, confirmations,
    passwordInputs: () => passwordInputs,
    valueField, moveButtons, wifiOrderNote, wifiAutoReturnControl, wifiLimitWarning, wifiHiddenWarning, addNetworkButton, footerStatus,
    opened: () => opened,
  };
}

test('group cards use the committed assignment and count only reports for its deployed revision', () => {
  const devices = [
    { ...online, serial_number: 'legacy', status: 'offline', configuration_state: applied },
    { ...online, device_id: 'stale', configuration_state: { ...applied, revision: 'old' } },
    { ...online, device_id: 'other', configuration_state: { ...applied, package_id: 'other' } },
  ];
  const groups = { 'The Lab': ['headset', 'legacy', 'stale', 'other', 'missing'], Empty: [], Draft: [], None: [] };
  const fixture = createHarness(() => assert.fail('group cards should not fetch data'), { groups, devices });
  fixture.broadcast({
    assignments: { 'The Lab': 'pkg', Empty: 'pkg', Draft: 'pkg2' },
    deployments: { 'The Lab': d1, Empty: d1, Draft: d1 },
  });
  const summary = name => fixture.controller.groupPackageSummary(name);
  assert.deepEqual(summary('The Lab'), { name: 'Studio', count: { text: 'Applied 1/4', state: 'pending' } });
  assert.deepEqual(summary('Empty'), { name: 'Studio', count: { text: 'Applied 0/0', state: '' } });
  assert.deepEqual(summary('Draft'), { name: 'Night studio', count: null });
  assert.equal(summary('None'), null);

  devices[1].configuration_state = applied;
  assert.equal(summary('The Lab').count.text, 'Applied 2/4');
  fixture.broadcast({
    packages: [{ ...pkg, name: 'Renamed' }, pkg2],
    assignments: { 'The Lab': 'pkg' },
    deployments: { 'The Lab': { ...d1, revision: 'r2' } },
  });
  assert.deepEqual(summary('The Lab'), { name: 'Renamed', count: { text: 'Applied 0/4', state: 'pending' } });
  fixture.broadcast();
  assert.equal(summary('The Lab'), null);
});

test('save and deploy sends the edited name and settings in one confirmed request', async () => {
  let approve = false;
  let draft = profile({ draftRevision: null, publishedRevision: null, settings: {} });
  const fixture = createHarness(({ method, url, body }) => {
    if (url === '/api/packages' && method === 'POST') return response(draft);
    if (method === 'GET') return response(draft);
    draft = { ...draft, name: body.name, settings: body.settings, draft_revision: 'draft2', published_revision: 'r1' };
    return response(draft);
  }, { confirm: () => approve });
  fixture.broadcast({ packages: [] });
  fixture.controller.showPackages();
  await fixture.create(' Studio ');
  assert.match(fixture.packageList.innerHTML, /Studio.*New/s);
  assert.match(fixture.packagePanel.innerHTML, /data-manage="brightness".*?<\/label>\s*<div class="settings-label"><strong>Screen brightness<\/strong>.*?<\/div>\s*<fieldset class="settings-value" aria-label="Screen brightness" disabled>/s);
  fixture.edit({ dataset: { packageName: '' }, value: '   ' });
  assert.equal(fixture.editorButtons.deploy.disabled, true);
  assert.match(fixture.editorControls['[data-deploy-hint]'].textContent, /Enter a package name/);
  fixture.edit({ dataset: { packageName: '' }, value: 'Day studio' });
  fixture.edit({ dataset: { manage: 'brightness' }, checked: true });
  assert.equal(fixture.valueField.disabled, false);
  fixture.edit({ dataset: { setting: 'brightness' }, type: 'range', value: '81' });
  fixture.edit({ dataset: { manage: 'brightness' }, checked: false });
  assert.equal(fixture.valueField.disabled, true);
  await fixture.editClick('deploy');
  assert.equal(fixture.requests.length, 2);
  assert.equal(fixture.editorButtons.deploy.disabled, false);
  assert.equal(fixture.editorControls['[data-deploy-hint]'].hidden, true);
  approve = true;
  await fixture.editClick('deploy');
  assert.deepEqual(fixture.requests.map(({ url, method }) => [url, method]), [
    ['/api/packages', 'POST'], ['/api/packages/pkg', 'GET'], ['/api/packages/pkg', 'GET'],
    ['/api/packages/pkg/save-and-deploy', 'POST'],
  ]);
  assert.deepEqual(fixture.requests[0].body, { name: 'Studio' });
  const mutation = fixture.requests[3].body;
  assert.deepEqual(mutation.settings.brightness, { managed: false, value: 81 });
  assert.equal(mutation.name, 'Day studio');
  assert.equal(mutation.expected_revision, null);
  assert.equal(mutation.expected_impact, 'impact1');
  assert.match(fixture.confirmations[0], /Deploy "Day studio" to 2 groups and 5 devices/);
  assert.match(fixture.confirmations[0], /includes offline devices/);
  assert.match(fixture.editorControls['[data-package-publication]'].textContent, /Deployed version r1/);
});

test('Save & deploy fills a cleared shutdown time when the schedule is disabled or unmanaged', async () => {
  for (const mode of ['disabled', 'unmanaged']) {
    let latest = profile({ settings: { shutdown: { managed: true, value: {
      enabled: true, hour: 22, minute: 15, repeat: 127,
    } } } });
    const fixture = createHarness(({ method, body }) => {
      if (method === 'GET') return response(latest);
      latest = profile({ settings: body.settings, draftRevision: 'draft2', publishedRevision: 'r1' });
      return response(latest);
    });
    fixture.broadcast();
    await fixture.controller.showPackages('pkg');
    fixture.edit({ dataset: { setting: 'shutdown', field: 'time' }, type: 'time', value: '' });
    if (mode === 'disabled') {
      fixture.edit({ dataset: { setting: 'shutdown', field: 'enabled' }, type: 'checkbox', checked: false });
    } else {
      fixture.edit({ dataset: { manage: 'shutdown' }, checked: false });
    }
    await fixture.editClick('deploy');
    assert.deepEqual(fixture.requests.at(-1).body.settings.shutdown, {
      managed: mode !== 'unmanaged',
      value: { enabled: mode !== 'disabled', hour: 22, minute: 15, repeat: 127 },
    });
  }
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
  await fixture.editClick('deploy');
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /deployment targets changed/);
  assert.match(fixture.editorControls['[data-deploy-hint]'].textContent, /Select Reload package/);
  assert.equal(fixture.editorButtons.deploy.disabled, true);
  await fixture.editClick('deploy');
  assert.equal(fixture.requests.filter(request => request.method === 'POST').length, 1);
  await fixture.editClick('reload');
  await fixture.editClick('deploy');
  assert.equal(fixture.requests.at(-1).body.expected_revision, 'draft1');
  assert.equal(fixture.requests.at(-1).body.expected_impact, 'impact2');
  assert.match(fixture.confirmations.at(-1), /1 group and 4 devices/);
});

test('a revision changed by another console blocks publication before confirmation', async () => {
  let latest = profile();
  const fixture = createHarness(() => response(latest));
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  latest = profile({ draftRevision: 'remote' });
  await fixture.editClick('deploy');
  assert.equal(fixture.confirmations.length, 0);
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /The saved draft changed/);
  assert.equal(fixture.editorButtons.reload.hidden, false);
  assert.equal(fixture.editorButtons.deploy.disabled, true);
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
  await fixture.editClick('deploy');
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /saved draft or deployment targets changed/);
  assert.equal(fixture.editorButtons.deploy.disabled, true);
  await fixture.editClick('reload');
  assert.match(fixture.packagePanel.innerHTML, /data-package-name[^>]+value="Local name"/);
  assert.match(fixture.packagePanel.innerHTML, /type="range"[^>]+value="86"/);
  assert.equal(fixture.editorButtons.deploy.disabled, false);
  await fixture.editClick('deploy');
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
  await fixture.editClick('deploy');
  assert.equal(fixture.editorButtons.deploy.disabled, true);
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /Could not confirm/);
  fixture.broadcast({ packages: [{ ...pkg, published_revision: 'r1' }, pkg2] });
  await fixture.editClick('reload');
  assert.match(fixture.editorControls['[data-package-publication]'].textContent, /Deployed version r1/);
  assert.equal(fixture.editorButtons.deploy.disabled, false);
});

test('WS catalog updates do not repaint draft inputs or yield to an older HTTP save response', async () => {
  let finishSave;
  const fixture = createHarness(({ method }) => method === 'GET' ? response(profile()) :
    new Promise(resolve => { finishSave = resolve; }));
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  fixture.edit({ dataset: { setting: 'brightness' }, type: 'range', value: '63' });
  const saving = fixture.editClick('deploy');
  await new Promise(resolve => setImmediate(resolve));
  const before = fixture.packagePanel.innerHTML;
  fixture.broadcast({ packages: [{ ...pkg, name: 'Remote name', published_revision: 'newer' }, pkg2] });
  assert.equal(fixture.packagePanel.innerHTML, before);
  assert.match(fixture.editorControls['[data-package-publication]'].textContent, /Deployed version newer/);
  finishSave(response(profile({ draftRevision: 'draft2', settings: { brightness: { managed: true, value: 63 } } })));
  await saving;
  assert.match(fixture.packagePanel.innerHTML, /type="range"[^>]+value="63"/);
  assert.match(fixture.packageList.innerHTML, /Remote name/);
  assert.match(fixture.editorControls['[data-package-publication]'].textContent, /Deployed version newer/);
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

test('group deployment uses the assigned package for status and preserves a staged selection across broadcasts', async () => {
  let finishAssignment;
  const name = 'lab/+ Wi-Fi?&';
  const fixture = createHarness(({ method }) => method === 'GET' ? response(profile()) :
    new Promise(resolve => { finishAssignment = resolve; }), {
    groups: { [name]: ['headset'] }, devices: [{ ...online, configuration_state: applied }],
  });
  const groupBroadcast = (assigned, deployment) => fixture.broadcast({
    packages: [pkg, { ...pkg2, has_draft: true }],
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
  const saving = fixture.groupClick('deploy');
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(fixture.requests.at(-1), {
    url: '/api/group-package/deploy?name=lab%2F%2B+Wi-Fi%3F%26&package_id=pkg2', method: 'POST', body: { expected_impact: 'impact1' },
  });
  groupBroadcast('pkg2');
  fixture.controller.renderGroup(name);
  assert.match(results.innerHTML, /Waiting for deployment/);
  groupBroadcast('pkg', d1);
  fixture.controller.renderGroup(name);
  finishAssignment(response({ package_id: 'pkg2' }));
  await saving;
  assert.equal(selection.value, 'pkg');
  assert.match(results.innerHTML, /All managed settings were verified/);
  await fixture.groupClick('edit');
  assert.equal(fixture.opened(), 1);
});

test('an assignment acknowledgment waits for the matching group snapshot before showing deployment status', async () => {
  const nextPackage = { ...pkg2, published_revision: 'r2' };
  const nextDeployment = { package_id: 'pkg2', revision: 'r2', keys: ['brightness'] };
  const fixture = createHarness(({ method }) => method === 'POST'
    ? response({ package_id: 'pkg2' })
    : response({ ...profile(), use_current: true, impact: { groups: 1, devices: 1, token: 'impact1' } }), {
    devices: [{ ...online, configuration_state: applied }],
  });
  const name = 'The Lab';
  const publication = fixture.groupControls['[data-group-publication]'];
  const results = fixture.groupControls['[data-settings-results]'];
  fixture.broadcast({ packages: [pkg, nextPackage], assignments: { [name]: 'pkg' }, deployments: { [name]: d1 } });
  fixture.controller.renderGroup(name);
  fixture.select('pkg2');
  await fixture.groupClick('deploy');
  assert.match(fixture.confirmations[0], /Deploy the current version.* to "The Lab" \(1 device\)/);

  assert.equal(fixture.groupControls['[data-group-package]'].value, 'pkg2');
  assert.match(publication.textContent, /Waiting for the group update/);
  assert.equal(fixture.groupButtons.deploy.disabled, true);
  assert.match(fixture.groupControls['[data-deploy-hint]'].textContent, /Waiting for the group/);
  assert.match(results.innerHTML, /Waiting for the group update/);
  assert.doesNotMatch(results.innerHTML, /Waiting for deployment|All managed settings were verified/);

  fixture.broadcast({ packages: [pkg, nextPackage], assignments: { [name]: 'pkg2' }, deployments: { [name]: nextDeployment } });
  fixture.controller.renderGroup(name);
  assert.equal(publication.textContent, 'Deployed version r2');
  assert.match(results.innerHTML, /Waiting for the device to report this deployment/);
});

test('Group Settings deploys a newly selected package only after confirming the shared targets', async () => {
  let allowDeploy = false;
  const fixture = createHarness(({ method }) => method === 'POST'
    ? response({ package_id: 'pkg' }) : response(profile()), { confirm: () => allowDeploy });
  const name = 'The Lab';
  const hint = fixture.groupControls['[data-deploy-hint]'];
  fixture.broadcast();
  fixture.controller.renderGroup(name);
  assert.equal(fixture.groupButtons.deploy.hidden, false);
  assert.equal(fixture.groupButtons.deploy.disabled, false);
  fixture.select('pkg2');
  assert.equal(fixture.groupButtons.deploy.disabled, true);
  assert.equal(hint.hidden, false);
  assert.match(hint.textContent, /Save & deploy/);
  assert.equal(fixture.groupButtons.edit.hidden, false);
  await fixture.groupClick('deploy');
  assert.deepEqual(fixture.requests, []);

  fixture.select('pkg');
  assert.equal(fixture.groupButtons.deploy.disabled, false);
  assert.equal(hint.hidden, true);
  await fixture.groupClick('deploy');
  assert.match(fixture.confirmations[0], /Deploy "Studio" to 2 groups and 5 devices\?[\s\S]*"The Lab"[\s\S]*includes offline devices/);
  assert.deepEqual(fixture.requests.map(request => request.method), ['GET']);

  allowDeploy = true;
  const deploying = fixture.groupClick('deploy');
  assert.equal(fixture.groupButtons.deploy.disabled, true);
  await fixture.groupClick('deploy');
  await deploying;
  const url = '/api/group-package/deploy?name=The+Lab&package_id=pkg';
  assert.deepEqual(fixture.requests.slice(1), [
    { url, method: 'GET', body: undefined },
    { url, method: 'POST', body: { expected_impact: 'impact1' } },
  ]);
  assert.match(fixture.groupControls['[data-assignment-feedback]'].textContent, /Deployment started/);
});

test('Group Settings deploys No package through the same confirmation flow', async () => {
  const url = '/api/group-package/deploy?name=The+Lab&package_id=';
  const fixture = createHarness(request => {
    if (request.url === '/api/packages/pkg') return response(profile());
    if (request.method === 'POST') return response({ package_id: null });
    return response({ name: 'No package', clear: true, impact: { groups: 1, devices: 1, token: 'clear1' } });
  });
  const name = 'The Lab';
  fixture.broadcast({ assignments: { [name]: 'pkg' }, deployments: { [name]: d1 } });
  fixture.controller.renderGroup(name);
  fixture.select('');
  assert.equal(fixture.groupButtons.edit.hidden, true);
  assert.equal(fixture.groupButtons.deploy.disabled, false);
  const requestCount = fixture.requests.length;
  await fixture.groupClick('deploy');
  assert.match(fixture.confirmations[0], /Deploy No package to "The Lab" \(1 device\)\?[\s\S]*leaves current device values in place/);
  assert.deepEqual(fixture.requests.slice(requestCount), [
    { url, method: 'GET', body: undefined },
    { url, method: 'POST', body: { expected_impact: 'clear1' } },
  ]);
  assert.equal(fixture.groupButtons.deploy.disabled, true);
  fixture.broadcast();
  fixture.controller.renderGroup(name);
  assert.equal(fixture.groupControls['[data-group-package]'].value, '');
  assert.equal(fixture.groupControls['[data-deployment-count]'].hidden, true);
  assert.equal(fixture.groupButtons.deploy.disabled, false);
  assert.match(fixture.groupControls['[data-assignment-feedback]'].textContent, /No package deployed/);
});

test('deployment counts track current-revision results for online and offline members', () => {
  const name = 'The Lab';
  const members = ['headset', 'headset2'];
  const devices = [online, { ...online, device_id: 'headset2', status: 'offline' }];
  const fixture = createHarness(() => {}, { groups: { [name]: members }, devices });
  const count = fixture.groupControls['[data-deployment-count]'];
  const render = (deployment, first, second) => {
    devices[0].configuration_state = first;
    devices[1].configuration_state = second;
    fixture.broadcast({ assignments: { [name]: 'pkg' }, deployments: deployment ? { [name]: deployment } : {} });
    fixture.controller.renderGroup(name);
    return [count.textContent, count.dataset.state];
  };
  assert.deepEqual(render(null, applied, applied), ['Applied 0/2', '']);
  assert.deepEqual(render(d1, { ...applied, revision: 'r0' }, undefined), ['Applied 0/2', 'pending']);
  assert.deepEqual(render(d1, failed, undefined), ['Applied 0/2', 'pending']);
  assert.deepEqual(render(d1, failed, unsupported), ['Applied 0/2', 'failed']);
  assert.deepEqual(render(d1, failed, applied), ['Applied 1/2', 'pending']);
  assert.deepEqual(render(d1, applied, applied), ['Applied 2/2', 'success']);
  members.length = 0;
  assert.deepEqual(render(d1, applied, applied), ['Applied 0/0', '']);
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
