const assert = require('node:assert/strict');
const test = require('node:test');
const {
  createController, loadDraft, buildPayload, deviceOutcome, retryEligible,
} = require('../styly_mdm/static/configuration-packages-v1.js');
const {
  membershipGroups, selectedMembers, toggleMember, reconcileMembers, buildMembershipEdit,
} = require('../styly_mdm/static/group-membership.js');

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
    { id: 'startupApp', label: 'PICO home app', kind: 'package', default: '' },
  ] },
  { id: 'network', title: 'Network & connectivity', items: [
    { id: 'wifi', label: 'Wi-Fi networks', kind: 'wifi', default: { networks: [], auto_return: false, prioritize_networks: true } },
  ] },
  { id: 'advanced', title: 'USB & advanced', items: [
    { id: 'developerMenu', label: 'Developer options menu', kind: 'bool', default: false },
  ] },
];
test('metadata defaults are visible in the draft and unmanaged', () => {
  const saved = loadDraft(sections, {});
  const draft = structuredClone(saved);
  assert.deepEqual(saved.brightness, { managed: false, value: 50 });
  assert.deepEqual(saved.wifi, { managed: false, value: { networks: [], auto_return: false, prioritize_networks: true } });
  assert.deepEqual(saved.developerMenu, { managed: false, value: false });
  draft.brightness.value = 64;
  draft.shutdown.value = { enabled: true, hour: 23, minute: 10, repeat: 65 };
  const payload = buildPayload(sections, draft, saved);
  assert.deepEqual(payload.brightness, { managed: false, value: 64 });
  assert.deepEqual(payload.shutdown, { managed: false, value: { enabled: true, hour: 23, minute: 10, repeat: 65 } });
  assert.deepEqual(payload.developerMenu, { managed: false, value: false });
  assert.deepEqual(payload.wifi.value, { networks: [], auto_return: false });
  assert.equal(Object.hasOwn(payload, 'startupApp'), false,
    'unmanaged default values are not added to packages that predate the setting');
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
  const saved = loadDraft(sections, {});
  const draft = structuredClone(saved);
  draft.startupApp = { managed: true, value: 'bad package' };
  assert.throws(() => buildPayload(sections, draft, saved), /Select a supported value for PICO home app/);
  draft.startupApp.value = '';
  assert.equal(buildPayload(sections, draft, saved).startupApp.value, '',
    'an empty managed value restores the PICO launcher');
  const legacy = loadDraft(sections, { startupApp: { managed: false, value: 'com.legacy.home' } });
  assert.deepEqual(buildPayload(sections, legacy, legacy).startupApp,
    { managed: false, value: 'com.legacy.home' }, 'saved unmanaged package IDs remain available');
});

test('PICO home app saves the Android package name entered in Basic settings', async () => {
  const latest = profile({ settings: {} });
  const fixture = createHarness(({ method, url, body }) => {
    if (method === 'GET') return response(latest);
    assert.equal(url, '/api/packages/pkg/save-and-deploy');
    assert.deepEqual(body.settings.startupApp, { managed: true, value: 'com.example.viewer' });
    return response({ ...latest, settings: body.settings, edit_revision: 'edit2', published_revision: 'r1' });
  });
  await fixture.controller.showPackages('pkg');
  assert.match(fixture.packagePanel.innerHTML, /Android package name<input[^>]*type="text"[^>]*data-setting="startupApp"/);
  assert.doesNotMatch(fixture.packagePanel.innerHTML, /type="file"/);
  fixture.edit({ dataset: { manage: 'startupApp' }, checked: true, type: 'checkbox' });
  fixture.edit({ dataset: { setting: 'startupApp' }, type: 'text', value: 'com.example.viewer' });
  await fixture.editClick('deploy');
  assert.equal(fixture.requests.at(-1).url, '/api/packages/pkg/save-and-deploy');
});

test('managed Wi-Fi pre-fills saved passwords and reorders whole candidates', async () => {
  let latest = profile({ settings: { wifi: { managed: true, value: {
    auto_return: false,
    networks: [
      { ssid: 'Studio', security: 'WPA2_WPA3', hidden: false, password: 'stored-secret', password_present: true },
      { ssid: 'Backup', security: 'WPA2_WPA3', hidden: false, password_present: false },
    ],
  } } } });
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    const previousNetworks = latest.settings.wifi.value.networks;
    const wifiValue = body.settings.wifi.value;
    const networks = wifiValue.networks.map(({ password, ...network }) => {
      const previous = previousNetworks.find(candidate =>
        candidate.ssid === network.ssid && candidate.security === network.security);
      const savedPassword = password ?? previous?.password ?? '';
      return { ...network, password: savedPassword, password_present: Boolean(savedPassword) };
    });
    latest = profile({ settings: { ...body.settings, wifi: { ...body.settings.wifi, value: { ...wifiValue, networks } } },
      editRevision: 'edit2', publishedRevision: 'r1' });
    return response(latest);
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  assert.doesNotMatch(fixture.packagePanel.innerHTML, /stored-secret|backup-secret/);
  const [studioPassword, backupPassword] = fixture.passwordInputs();
  assert.deepEqual([studioPassword.value, backupPassword.value], ['stored-secret', '']);
  assert.match(fixture.packagePanel.innerHTML, /1–63 ASCII characters/);
  assert.match(fixture.packagePanel.innerHTML, /1–7 require WPA3/);
  assert.match(fixture.packagePanel.innerHTML, /aria-label="Move Wi-Fi 1 down"[^>]*><svg/);
  assert.match(fixture.packagePanel.innerHTML, /aria-label="Remove Wi-Fi 1 from package"[^>]*><svg/);

  fixture.edit({ dataset: { setting: 'wifi', network: '0', field: 'ssid' }, type: 'text', value: 'Renamed' });
  assert.equal(studioPassword.value, '');
  fixture.edit({ dataset: { setting: 'wifi', network: '0', field: 'ssid' }, type: 'text', value: 'Studio' });
  assert.equal(studioPassword.value, 'stored-secret');
  fixture.edit({ dataset: { setting: 'wifi', network: '0', field: 'security' }, type: 'select-one', value: 'OPEN' });
  assert.equal(studioPassword.value, '');
  fixture.edit({ dataset: { setting: 'wifi', network: '0', field: 'security' }, type: 'select-one', value: 'WPA2_WPA3' });
  assert.equal(studioPassword.value, 'stored-secret');

  fixture.edit({ dataset: { setting: 'wifi', network: '1', field: 'password' }, type: 'password', value: 'backup-secret' });
  fixture.policyClick({ policyAction: 'move-network', setting: 'wifi', network: '0', direction: 'down' });
  latest.settings.wifi.value.networks[0].password = 'rotated-secret';
  latest.edit_revision = 'rotated-revision';
  await fixture.editClick('reload');
  assert.deepEqual(fixture.passwordInputs().map(input => input.value), ['backup-secret', 'rotated-secret']);
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
  assert.deepEqual(fixture.passwordInputs().map(input => input.value), ['backup-secret', 'rotated-secret']);
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

test('unmanaging Wi-Fi discards an incomplete draft row and retains saved metadata and credentials', () => {
  const saved = loadDraft(sections, { wifi: { managed: true, value: {
    prioritize_networks: false,
    auto_return: false,
    networks: [{ ssid: '日本語', security: 'WPA2_WPA3', hidden: false, password_present: true }],
  } } });
  const draft = structuredClone(saved);
  draft.wifi.value.networks.push({ ssid: '', security: 'OPEN', hidden: false, password: '' });
  draft.wifi.managed = false;

  const payload = buildPayload(sections, draft, saved).wifi;
  assert.deepEqual(payload, { managed: false, value: {
    networks: [{ ssid: '日本語', security: 'WPA2_WPA3', hidden: false }],
    auto_return: false, prioritize_networks: false,
  } });
  assert.equal(Object.hasOwn(payload.value.networks[0], 'password'), false);
});

test('Wi-Fi edits stop counting as dirty after unmanaging an already unmanaged setting', async () => {
  const settings = { wifi: { managed: false, value: {
    auto_return: false,
    prioritize_networks: true,
    networks: [{ ssid: 'Office', security: 'OPEN', hidden: false }],
  } } };
  let latest = profile({ settings });
  let submitted;
  const fixture = createHarness(({ url, method, body }) => {
    if (url === '/api/packages') return response({ packages: [latest] });
    if (method === 'GET') return response(latest);
    submitted = body;
    latest = { ...latest, settings: body.settings, edit_revision: 'edit2', published_revision: 'r1' };
    return response(latest);
  });
  fixture.broadcast({ packages: [latest] });
  await fixture.controller.showPackages('pkg');

  fixture.edit({ dataset: { manage: 'wifi' }, checked: true });
  fixture.edit({ dataset: { setting: 'wifi', network: '0', field: 'ssid' }, value: 'Temporary', type: 'text' });
  fixture.edit({ dataset: { manage: 'wifi' }, checked: false });

  assert.match(fixture.packagePanel.innerHTML, /value="Office"/);
  assert.doesNotMatch(fixture.packagePanel.innerHTML, /value="Temporary"/);
  assert.equal(fixture.footerStatus.textContent, '');
  assert.equal(fixture.editorButtons.discard.disabled, true);
  assert.equal(fixture.editorButtons.deploy.disabled, false,
    'manual redeploy stays available even when there are no saved changes');
  await fixture.editClick('deploy');
  assert.equal(submitted.settings.wifi.managed, false);
  assert.equal(submitted.settings.wifi.value.networks[0].ssid, 'Office');
});

test('Wi-Fi SSIDs follow the server UTF-8 byte and character rules', () => {
  const saved = loadDraft(sections, { wifi: { managed: true, value: {
    auto_return: false,
    networks: [{ ssid: '日本語', security: 'OPEN', hidden: false }],
  } } });
  const draft = structuredClone(saved);
  assert.doesNotThrow(() => buildPayload(sections, draft, saved), '3 Japanese characters fit within 32 UTF-8 bytes');

  draft.wifi.value.networks[0].ssid = '界'.repeat(10) + 'ab';
  assert.doesNotThrow(() => buildPayload(sections, draft, saved), '32 UTF-8 bytes are accepted');
  draft.wifi.value.networks[0].ssid = '界'.repeat(11);
  assert.throws(() => buildPayload(sections, draft, saved), /1 to 32 UTF-8 bytes/);
  for (const ssid of ['bad"ssid', 'bad\\ssid', 'line\nfeed', '\u0085control', '\ud800']) {
    draft.wifi.value.networks[0].ssid = ssid;
    assert.throws(() => buildPayload(sections, draft, saved), /SSID/);
  }
});

test('auto-return is stored inside the Wi-Fi setting and hidden networks are rejected', async () => {
  let latest = profile({ settings: { wifi: { managed: true, value: {
    auto_return: false,
    networks: [{ ssid: 'Studio', security: 'OPEN', hidden: false, password_present: false }],
  } } } });
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    latest = profile({ settings: body.settings, editRevision: 'edit2', publishedRevision: 'r1' });
    return response(latest);
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  assert.match(fixture.packagePanel.innerHTML, /data-manage="wifi"/);
  assert.doesNotMatch(fixture.packagePanel.innerHTML, /wifiPriority|priorityManage|Enable Wi-Fi priority/);
  assert.match(fixture.packagePanel.innerHTML, /data-setting="wifi" data-field="auto_return"/);
  assert.match(fixture.packagePanel.innerHTML, /data-field="prioritize_networks" checked/);
  assert.match(fixture.packagePanel.innerHTML,
    /within 30 seconds, then about every minute for 5 scans, then about every 5 minutes/);
  assert.match(fixture.packagePanel.innerHTML, /retries the previous network/);
  assert.match(fixture.packagePanel.innerHTML, /Android auto-connects only to the first network/);

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

test('Wi-Fi Android selection disables ordering and auto-return, and saves explicit false', async () => {
  let latest = profile({ settings: { wifi: { managed: true, value: {
    prioritize_networks: true,
    auto_return: true,
    networks: [
      { ssid: 'Studio', security: 'OPEN', hidden: false, password_present: false },
      { ssid: 'Backup', security: 'OPEN', hidden: false, password_present: false },
    ],
  } } } });
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    latest = profile({ settings: body.settings, editRevision: 'edit2', publishedRevision: 'r1' });
    return response(latest);
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  assert.match(fixture.packagePanel.innerHTML, /Android auto-connects only to the first network/);
  assert.match(fixture.packagePanel.innerHTML, /data-policy-action="move-network"/);
  assert.match(fixture.packagePanel.innerHTML, /data-field="auto_return" checked/);

  fixture.edit({ dataset: { setting: 'wifi', field: 'prioritize_networks' }, checked: false, type: 'checkbox' });
  assert.doesNotMatch(fixture.packagePanel.innerHTML, /data-policy-action="move-network"/);
  assert.doesNotMatch(fixture.packagePanel.innerHTML, /data-field="auto_return"/);
  assert.match(fixture.packagePanel.innerHTML, /Android chooses the network/);
  assert.match(fixture.packagePanel.innerHTML, /Auto-connect is on for listed SSID and security matches and off for other saved networks/);
  await fixture.editClick('deploy');

  assert.deepEqual(fixture.requests.at(-1).body.settings.wifi.value, {
    networks: [
      { ssid: 'Studio', security: 'OPEN', hidden: false },
      { ssid: 'Backup', security: 'OPEN', hidden: false },
    ],
    auto_return: false,
    prioritize_networks: false,
  });
  const saved = loadDraft(sections, { wifi: { managed: true, value: {
    prioritize_networks: false, auto_return: false,
    networks: [{ ssid: 'Studio', security: 'OPEN', hidden: false, password_present: false }],
  } } });
  assert.equal(saved.wifi.value.prioritize_networks, false);
  assert.deepEqual(buildPayload(sections, saved, saved).wifi.value, {
    networks: [{ ssid: 'Studio', security: 'OPEN', hidden: false }],
    auto_return: false, prioritize_networks: false,
  });
  saved.wifi.value.auto_return = true;
  assert.throws(() => buildPayload(sections, saved, saved), /Turn off automatic return/);
});

test('managed Wi-Fi allows at most four candidates in the editor and payload', async () => {
  const networks = Array.from({ length: 3 }, (_, index) => ({
    ssid: `Network-${index + 1}`, security: 'OPEN', hidden: false, password_present: false,
  }));
  let latest = profile({ settings: { wifi: { managed: true, value: { auto_return: false, networks } } } });
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    latest = profile({ settings: body.settings, editRevision: 'edit2', publishedRevision: 'r1' });
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
      ...network, password, password_present: Boolean(password),
    }));
    latest = profile({ settings: { ...body.settings, wifi: { ...body.settings.wifi,
      value: { ...body.settings.wifi.value, networks } } }, editRevision: 'edit2', publishedRevision: 'r1' });
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
  assert.deepEqual(fixture.passwordInputs().map(input => input.value), ['studio-pass']);
  assert.equal(fixture.editorButtons.deploy.disabled, false);
  assert.doesNotMatch(fixture.packagePanel.innerHTML, /studio-pass/);
});

const pkg = { package_id: 'pkg', name: 'Studio', published_revision: 'r0' };
const pkg2 = { package_id: 'pkg2', name: 'Night studio', published_revision: null };
const d1 = { package_id: 'pkg', revision: 'r1', keys: ['brightness'] };

function groupSnapshot(revision, { packages = [pkg, pkg2], assignments = {}, deployments = {}, groups = { 'The Lab': ['headset'] },
  server_instance_id = 'server-a', configuration_available = true } = {}) {
  return { type: 'GROUP_LIST', revision, packages, assignments, deployments, groups,
    registry_writable: true, configuration_available, server_instance_id };
}
const online = { device_id: 'headset', label: 'DEV-003', status: 'online', configuration_supported: true };
const applied = { ...d1, status: 'complete', results: { brightness: { status: 'applied', value: 60 } } };
const failed = { ...applied, results: { brightness: { status: 'failed', reason: 'WRITE_FAILED' } } };
const unsupported = { ...applied, results: { brightness: { status: 'unsupported' } } };

test('device status uses deployment keys and requires both IDs for current results and Retry', () => {
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
  assert.equal(status(applied, null), 'Waiting for deployment');
  assert.equal(status(unsupported), 'Unsupported');
  assert.equal(deviceOutcome(online, null, null)[1], 'No managed settings');
  for (const report of [failed, unsupported, { ...applied, status: 'interrupted' }]) {
    assert.equal(retryEligible(device(report), d1), true);
    assert.equal(retryEligible(device({ ...report, package_id: 'other' }), d1), false);
    assert.equal(retryEligible(device({ ...report, revision: 'old' }), d1), false);
    assert.equal(retryEligible(device(report, { status: 'offline' }), d1), false);
    assert.equal(retryEligible(device(report, { configuration_supported: false }), d1), false);
  }
  assert.equal(retryEligible(device(applied), d1), false);
  assert.equal(retryEligible(device({ ...failed, status: 'applying' }), d1), false);
});

test('release stays pending for a device that still reports a previous deployment', () => {
  const device = { ...online, configuration_state: applied };
  assert.equal(deviceOutcome(device, null, null)[1], 'Release pending');
  assert.equal(deviceOutcome({ ...device, status: 'offline' }, null, null)[1], 'Release pending · Offline');
  assert.equal(deviceOutcome({ ...device, configuration_supported: false }, null, null)[1], 'Client update needed');
  assert.equal(deviceOutcome({ ...device, configuration_state: null }, null, null)[1], 'No managed settings');
});

test('Wi-Fi deployment does not use the removed Wi-Fi priority client gate', () => {
  const wifiDeployment = { ...d1, keys: ['wifi'] };
  const wifiApplied = { ...wifiDeployment, status: 'complete', results: { wifi: { status: 'applied' } } };
  const device = { ...online, configuration_state: wifiApplied };
  assert.equal(deviceOutcome(device, pkg.package_id, wifiDeployment)[1], 'Applied');
});

test('priority and Android selection deployments use package support without a separate Wi-Fi gate', () => {
  for (const wifiMode of ['priority', 'android_selection']) {
    const deployment = { ...d1, keys: ['wifi'], wifi_mode: wifiMode };
    const report = { ...deployment, status: 'complete', results: {
      wifi: { status: 'failed', reason: 'WRITE_FAILED' },
    } };
    const device = { ...online, configuration_state: report };
    assert.equal(deviceOutcome(device, pkg.package_id, deployment)[1], 'Apply failed');
    assert.equal(retryEligible(device, deployment), true);
  }
});

test('group results fetch only public sections and retry failed reads on reopen', async () => {
  let attempts = 0;
  const fixture = createHarness(request => {
    assert.equal(request.url, '/api/packages');
    attempts++;
    return attempts < 3 ? response({}, 503) : response({ packages: [pkg], sections });
  }, { devices: [{ ...online, configuration_state: applied }] });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': d1 } });

  fixture.controller.renderGroup('The Lab');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(attempts, 1);

  for (let index = 0; index < 4; index++) fixture.controller.renderGroup('The Lab');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(attempts, 1, 'repeated renders must not retry a failed request');

  fixture.controller.renderGroup(null);
  fixture.controller.renderGroup('The Lab');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(attempts, 2, 'reopening the group permits one retry');
  for (let index = 0; index < 4; index++) fixture.controller.renderGroup('The Lab');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(attempts, 2);
  fixture.controller.renderGroup(null);
  fixture.controller.renderGroup('The Lab');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(attempts, 3);
  assert.match(fixture.groupControls['[data-settings-results]'].innerHTML, /Screen brightness/);
});

test('Wi-Fi SAVED reports verified profiles and leaves connection selection to Android', () => {
  const deployment = { ...d1, keys: ['brightness', 'wifi'], wifi_mode: 'android_selection' };
  const report = { ...deployment, status: 'complete', results: {
    brightness: { status: 'applied', value: 60 },
    wifi: { status: 'applied', reason: 'SAVED' },
  } };
  const device = { ...online, configuration_state: report };
  assert.equal(deviceOutcome(device, pkg.package_id, deployment)[1], 'Applied');
  assert.match(deviceOutcome(device, pkg.package_id, deployment)[2], /Wi-Fi profiles and auto-connect flags were read back\. Android selects the network/);

  const fixture = createHarness(() => assert.fail('rendering results should not call the API'), { devices: [device] });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': deployment } });
  fixture.controller.renderGroup('The Lab');
  const results = fixture.groupControls['[data-settings-results]'].innerHTML;
  assert.match(results, /aria-label="Applied">✓<\/span>\s*<span>wifi<\/span><span class="settings-readback-value">Saved\. Android selects the network\./);
  assert.doesNotMatch(results, /auto-return|higher-priority/);
});

test('priority Wi-Fi deployments show a live notice when switching networks', () => {
  const deployment = { ...d1, keys: ['wifi'], wifi_mode: 'priority' };
  const device = { ...online, wifi_state: { priority_monitor: 'switching' } };
  const fixture = createHarness(() => assert.fail('rendering status should not call the API'), { devices: [device] });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': deployment } });
  fixture.controller.renderGroup('The Lab');
  assert.match(fixture.groupControls['[data-settings-results]'].innerHTML, /Switching to a higher-priority Wi-Fi network/);
});

test('Wi-Fi fallback is displayed as Applied', () => {
  const deployment = { ...d1, keys: ['brightness', 'wifi'] };
  const report = { ...deployment, status: 'complete', results: {
    brightness: { status: 'applied', value: 60 },
    wifi: { status: 'applied', reason: 'FALLBACK' },
  } };
  const devices = [{ ...online, configuration_state: report }];
  const fixture = createHarness(() => assert.fail('rendering results should not call the API'), { devices });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': deployment } });
  fixture.controller.renderGroup('The Lab');

  const results = fixture.groupControls['[data-settings-results]'].innerHTML;
  assert.match(results, /Applied/);
  assert.match(results, /Results at apply time/);
  assert.match(results, /aria-label="Applied">✓<\/span>\s*<span>wifi<\/span>/);
  assert.doesNotMatch(results, /rolled back|restored|reboot required/i);
  assert.equal(deviceOutcome(devices[0], 'pkg', deployment)[1], 'Applied');
});

test('device results start collapsed and reveal failed settings and verified values on card click', async () => {
  const deployment = { ...d1, keys: ['brightness', 'wifi', 'autoSleep', 'screenOff'] };
  const report = { ...deployment, status: 'complete', results: {
    brightness: { status: 'applied', value: 60 },
    wifi: { status: 'failed', reason: 'PERMISSION_DENIED' },
    autoSleep: { status: 'unsupported' },
  } };
  const devices = [{ ...online, configuration_state: report }];
  const fixture = createHarness(() => response(profile()), { devices });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': deployment } });
  fixture.controller.renderGroup('The Lab');
  await new Promise(resolve => setImmediate(resolve));

  const results = fixture.groupControls['[data-settings-results]'];
  assert.match(results.innerHTML, /Partially applied/);
  assert.match(results.innerHTML, /aria-expanded="false" aria-controls="group-device-results-headset"/);
  assert.match(results.innerHTML, /aria-label="Results at apply time" hidden/);
  const click = fixture.groupPanel.handlers.get('click');
  const card = { dataset: { resultDevice: 'headset' } };
  click({ target: { closest: selector => selector === '[data-result-device]' ? card : null } });
  assert.match(results.innerHTML, /aria-expanded="true"/);
  assert.doesNotMatch(results.innerHTML, /aria-label="Results at apply time" hidden/);
  assert.match(results.innerHTML, /aria-label="Applied">✓<\/span>\s*<span>Screen brightness<\/span><span class="settings-readback-value">60%/);
  assert.match(results.innerHTML, /aria-label="Failed">×<\/span>\s*<span>Wi-Fi networks<\/span><span class="settings-readback-value">Permission denied/);
  assert.match(results.innerHTML, /aria-label="Unsupported">×<\/span>\s*<span>Auto sleep<\/span>/);
  assert.match(results.innerHTML, /aria-label="Not verified">×<\/span>\s*<span>Screen off delay<\/span>/);
  fixture.controller.renderGroup('The Lab');
  assert.match(results.innerHTML, /aria-expanded="true"/);
  click({ target: { closest: selector => selector === 'button, a' ? {} :
    selector === '[data-result-device]' ? card : null } });
  assert.match(results.innerHTML, /aria-expanded="true"/);
  const toggle = { dataset: { groupAction: 'toggle-result', deviceId: 'headset' } };
  click({ target: { closest: selector => selector === '[data-group-action]' ? toggle : card } });
  assert.match(results.innerHTML, /aria-expanded="false"/);
  assert.equal(deviceOutcome(devices[0], 'pkg', deployment)[1], 'Partially applied');
  assert.equal(retryEligible(devices[0], deployment), true);
});

test('legacy saved Wi-Fi awaiting reboot counts as applied and has no Wi-Fi-only Retry', () => {
  const deployment = { ...d1, keys: ['brightness', 'wifi'] };
  const report = { ...deployment, status: 'complete', results: {
    brightness: { status: 'applied', value: 60 },
    wifi: { status: 'failed', reason: 'VERIFY_AFTER_REBOOT' },
  } };
  const device = { ...online, configuration_state: report };
  assert.equal(deviceOutcome(device, pkg.package_id, deployment)[0], 'success');
  assert.equal(deviceOutcome(device, pkg.package_id, deployment)[1], 'Applied');
  assert.match(deviceOutcome(device, pkg.package_id, deployment)[2], /Saved Wi-Fi profiles and auto-connect flags were read back/);
  assert.equal(retryEligible(device, deployment), false);

  const fixture = createHarness(() => assert.fail('rendering results should not call the API'), { devices: [device] });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': deployment } });
  fixture.controller.renderGroup('The Lab');
  const results = fixture.groupControls['[data-settings-results]'].innerHTML;
  assert.match(results, /aria-label="Applied">✓<\/span>\s*<span>wifi<\/span><span class="settings-readback-value">Saved<\/span>/);
  assert.doesNotMatch(results, /aria-label="Failed"|Verify after reboot|data-group-action="retry"/);

  const retryableMixed = { ...report, results: { ...report.results,
    brightness: { status: 'failed', reason: 'WRITE_FAILED' } } };
  assert.equal(deviceOutcome({ ...device, configuration_state: retryableMixed }, pkg.package_id, deployment)[1], 'Partially applied');
  assert.equal(retryEligible({ ...device, configuration_state: retryableMixed }, deployment), true);
});

test('Group Settings keeps only exceptional or switching live notices next to applied results', () => {
  const deployment = { ...d1, keys: ['brightness', 'wifi'] };
  const report = { ...deployment, status: 'complete', results: {
    brightness: { status: 'applied', value: 60 }, wifi: { status: 'applied' },
  } };
  const devices = [{ ...online, configuration_state: report, wifi_state: { priority_monitor: 'degraded' } }];
  const fixture = createHarness(() => assert.fail('rendering results should not call the API'), {
    groups: { 'The Lab': ['headset'] }, devices,
  });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': deployment } });

  for (const [monitor, notice] of [
    ['off', null], ['monitoring', null], ['degraded', 'Wi-Fi auto-return is degraded.'],
    ['switching', 'Switching to a higher-priority Wi-Fi network.'],
    ['unsupported', 'Wi-Fi auto-return is unavailable.'],
  ]) {
    devices[0].wifi_state = { priority_monitor: monitor };
    fixture.controller.renderGroup('The Lab');
    const results = fixture.groupControls['[data-settings-results]'].innerHTML;
    assert.match(results, /Applied/);
    if (notice) assert.ok(results.includes(notice));
    else assert.doesNotMatch(results, /auto-return|higher-priority/);
    assert.equal(fixture.groupControls['[data-deployment-count]'].textContent, 'Applied 1/1');
  }

  devices[0].wifi_state = {};
  fixture.controller.renderGroup('The Lab');
  assert.doesNotMatch(fixture.groupControls['[data-settings-results]'].innerHTML, /auto-return|higher-priority/);
});

test('membership edits derive the roster from the latest snapshot and one edit map', () => {
  const edits = new Map();
  const currentGroups = { Studio: ['kept'], Gallery: ['moved'] };
  edits.set('local-add', { kind: 'add', expectedOwner: null });

  assert.deepEqual(selectedMembers(currentGroups.Studio, edits), new Set(['kept', 'local-add']));
  assert.deepEqual(buildMembershipEdit('Studio', currentGroups, edits), {
    type: 'UPDATE_GROUP_MEMBERS', name: 'Studio', add: { 'local-add': null }, remove: [],
  });
  assert.deepEqual(membershipGroups('moved', currentGroups), ['Gallery']);
});

test('membership toggles add, remove, and clear an edit when reverted', () => {
  const groups = { Studio: ['kept'], Gallery: ['headset'] };
  const edits = new Map();

  assert.equal(toggleMember('Studio', groups, edits, 'headset'), true);
  assert.deepEqual(edits.get('headset'), { kind: 'add', expectedOwner: 'Gallery' });
  assert.equal(toggleMember('Studio', groups, edits, 'headset'), false);
  assert.equal(edits.has('headset'), false);

  assert.equal(toggleMember('Studio', groups, edits, 'kept'), false);
  assert.deepEqual(edits.get('kept'), { kind: 'remove' });
  assert.equal(toggleMember('Studio', groups, edits, 'kept'), true);
  assert.equal(edits.has('kept'), false);
});

test('a broadcast follows untouched moves while preserving staged additions and removals', () => {
  const edits = new Map([
    ['remove', { kind: 'remove' }],
    ['local-add', { kind: 'add', expectedOwner: null }],
  ]);
  const currentGroups = { Studio: ['remove', 'kept'], Gallery: ['moved'] };
  assert.deepEqual(selectedMembers(currentGroups.Studio, edits), new Set(['kept', 'local-add']));
  assert.deepEqual(buildMembershipEdit('Studio', currentGroups, edits), {
    type: 'UPDATE_GROUP_MEMBERS', name: 'Studio', add: { 'local-add': null }, remove: ['remove'],
  });

  const latestGroups = { Studio: ['kept', 'remote-add'], Gallery: ['moved'] };
  assert.deepEqual(selectedMembers(latestGroups.Studio, edits),
    new Set(['kept', 'remote-add', 'local-add']));
  assert.deepEqual(buildMembershipEdit('Studio', latestGroups, edits), {
    type: 'UPDATE_GROUP_MEMBERS', name: 'Studio', add: { 'local-add': null }, remove: [],
  });
});

test('membership edits already applied by another console are pruned from the map', () => {
  const edits = new Map([
    ['added', { kind: 'add', expectedOwner: null }],
    ['removed', { kind: 'remove' }],
    ['still-pending-add', { kind: 'add', expectedOwner: 'Gallery' }],
    ['still-pending-remove', { kind: 'remove' }],
  ]);

  reconcileMembers(['added', 'still-pending-remove'], edits);
  assert.deepEqual(edits, new Map([
    ['still-pending-add', { kind: 'add', expectedOwner: 'Gallery' }],
    ['still-pending-remove', { kind: 'remove' }],
  ]));

  const laterGroups = { Studio: [], Gallery: [] };
  assert.deepEqual(selectedMembers(laterGroups.Studio, edits), new Set(['still-pending-add']));
});

test('membership payload skips edits already satisfied by the latest group snapshot', () => {
  const groups = { Studio: ['already-added'] };
  const edits = new Map([
    ['already-added', { kind: 'add', expectedOwner: 'Gallery' }],
    ['already-removed', { kind: 'remove' }],
  ]);

  assert.deepEqual(buildMembershipEdit('Studio', groups, edits), {
    type: 'UPDATE_GROUP_MEMBERS', name: 'Studio', add: {}, remove: [],
  });
});

test('membership payload safely preserves device IDs that are object property names', () => {
  const payload = buildMembershipEdit('Studio', { Studio: [] }, new Map([
    ['__proto__', { kind: 'add', expectedOwner: null }],
  ]));

  assert.equal(Object.hasOwn(payload.add, '__proto__'), true);
  assert.equal(payload.add.__proto__, null);
});

test('membership additions reject changed or conflicting owners without changing the draft', () => {
  const edits = new Map();
  const initialGroups = { Studio: [], LabA: ['headset'] };
  toggleMember('Studio', initialGroups, edits, 'headset');

  const changedGroups = { Studio: [], LabB: ['headset'] };
  assert.throws(() => buildMembershipEdit('Studio', changedGroups, edits), /Group membership changed/);
  assert.deepEqual(edits.get('headset'), { kind: 'add', expectedOwner: 'LabA' });

  assert.throws(() => toggleMember('Studio', {
    Studio: [], LabA: ['other'], LabB: ['other'],
  }, edits, 'other'), /conflicting group memberships/);
  assert.equal(edits.has('other'), false);

  const reviewedEdits = new Map([['headset', { kind: 'add', expectedOwner: 'LabB' }]]);
  assert.deepEqual(buildMembershipEdit('Studio', changedGroups, reviewedEdits), {
    type: 'UPDATE_GROUP_MEMBERS', name: 'Studio', add: { headset: 'LabB' }, remove: [],
  });
});

test('membership payload rejects duplicate IDs and stale removals', () => {
  assert.throws(() => buildMembershipEdit('Studio', { Studio: ['headset', 'headset'] }, new Map()),
    /duplicate device IDs/);
  assert.throws(() => buildMembershipEdit('Studio', {
    Studio: ['headset'], Gallery: ['headset'],
  }, new Map([['headset', { kind: 'remove' }]])), /membership changed/);
});

function response(data, status = 200) {
  return { status, ok: status < 400, json: async () => data };
}

function profile({
  id = 'pkg', name = 'Studio', editRevision = 'edit1', publishedRevision = 'r0',
  settings = { brightness: { managed: true, value: 60 } },
  impact = { groups: 2, devices: 5, token: 'impact1' },
  itemSections = sections,
} = {}) {
  return { package_id: id, name, edit_revision: editRevision, published_revision: publishedRevision,
    settings, sections: itemSections, impact };
}

function createHarness(respond, { devices = [], groups = { 'The Lab': ['headset'] }, confirm = () => true,
  prompt = (_, value) => value,
  onSnapshot, requestGroupSnapshot = () => false } = {}) {
  const createElement = overrides => ({
    innerHTML: '', textContent: '', value: '', hidden: false, disabled: false,
    classList: { toggle() {} },
    querySelectorAll: () => [],
    handlers: new Map(),
    addEventListener(type, handler) { this.handlers.set(type, handler); },
    ...overrides,
  });
  const editorButtons = Object.fromEntries(['deploy', 'discard', 'reload', 'delete', 'duplicate'].map(action =>
    [action, createElement({ dataset: { policyAction: action } })]));
  const groupButtons = Object.fromEntries(['edit', 'deploy', 'retry'].map(action =>
    [action, createElement({ dataset: { groupAction: action, deviceId: 'headset' } })]));
  const editorControls = {
    '.settings-editor': createElement(),
    '.settings-actions': { querySelector: selector => selector === '.settings-meta' ? footerStatus :
      editorButtons[selector.match(/"([^"]+)"/)[1]] },
    '[data-package-name]': createElement(),
    '[data-setting="startupApp"]': createElement({ dataset: { setting: 'startupApp' } }),
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
      const packageName = html.match(/data-setting="startupApp" value="([^"]*)"/);
      editorControls['[data-setting="startupApp"]'].value = packageName ? packageName[1] : '';
      addNetworkButton.disabled = /data-policy-action="add-network"[^>]*disabled/.test(html);
    },
  });
  const groupPanel = createElement({ querySelector: selector => groupControls[selector] });
  const packageList = createElement();
  const catalogStatus = createElement();
  const catalogNotice = createElement({ querySelector: selector => selector === '[role="status"]' ? catalogStatus : null });
  const createControls = {
    input: createElement({ focus() { this.focused = true; } }),
    button: createElement(),
    '[role="status"]': createElement(),
  };
  const createForm = createElement({ querySelector: selector => createControls[selector] });
  const requests = [];
  const confirmations = [];
  let lastSnapshotRevision = 0;
  let opened = 0;
  const controller = createController({
    groupPanel, packagePanel, packageList, catalogNotice, createForm,
    getGroups: () => groups,
    getDevice: id => devices.find(device => device.device_id === id),
    openPackages: () => { opened++; },
    confirmAction: message => { confirmations.push(message); return confirm(message); },
    promptForValue: prompt,
    onSnapshot,
    requestGroupSnapshot,
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
  const wifiLimitWarning = { hidden: true };
  const wifiHiddenWarning = { hidden: true };
  const wifiArticle = dataset => ({
    querySelector: selector => selector === '[data-field="password"]'
      ? passwordInputs.find(input => input.dataset.setting === dataset.setting && input.dataset.network === dataset.network)
      : { textContent: '' },
  });
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
        selector === '.wifi-network' ? wifiArticle(dataset) :
        { dataset: { settingsSection: 'basic' }, querySelector: () => ({}), querySelectorAll: () => [] },
  } });
  const editClick = action => packagePanel.handlers.get('click')({ target: { closest: () => editorButtons[action] } });
  const groupClick = action => groupPanel.handlers.get('click')({ target: { closest: () => groupButtons[action] } });
  const select = id => {
    const control = groupControls['[data-group-package]'];
    control.value = id;
    groupPanel.handlers.get('change')({ target: control });
  };
  const broadcast = ({ revision, packages = [pkg, pkg2], assignments = {}, deployments = {}, groups: snapshotGroups,
    server_instance_id, configuration_available } = {}) => {
    const currentRevision = revision ?? lastSnapshotRevision + 1;
    lastSnapshotRevision = Math.max(lastSnapshotRevision, currentRevision);
    controller.updateSnapshot(groupSnapshot(currentRevision, { packages, assignments, deployments,
      groups: snapshotGroups || groups, server_instance_id, configuration_available }), 'ws');
  };
  const create = async name => {
    createControls.input.value = name;
    await createForm.handlers.get('submit')({ preventDefault() {} });
  };
  return {
    controller, packagePanel, packageList, catalogNotice, catalogStatus, groupPanel, edit, editClick, policyClick, editorButtons, editorControls, groupControls,
    groupClick, groupButtons, select, broadcast, create, createControls, requests, confirmations,
    passwordInputs: () => passwordInputs,
    valueField, moveButtons, wifiLimitWarning, wifiHiddenWarning, addNetworkButton, footerStatus,
    opened: () => opened,
  };
}

test('Device status follows live Wi-Fi independently of any deployment', () => {
  const device = { ...online, current_wifi: { ssid: 'Hall', bssid: 'aa:bb:cc:dd:ee:ff' } };
  const fixture = createHarness(() => assert.fail('unexpected fetch'), { devices: [device] });
  fixture.broadcast();
  fixture.controller.renderGroup('The Lab');
  const results = fixture.groupControls['[data-settings-results]'];
  assert.match(results.innerHTML, /title="BSSID: aa:bb:cc:dd:ee:ff">Wi-Fi: Hall/);
  device.current_wifi = { ssid: 'Guest', bssid: null };
  fixture.controller.renderGroup('The Lab');
  assert.match(results.innerHTML, /Wi-Fi: Guest/);
  assert.doesNotMatch(results.innerHTML, /BSSID|Hall/);
  device.status = 'offline';
  fixture.controller.renderGroup('The Lab');
  assert.doesNotMatch(results.innerHTML, /Wi-Fi: Guest/);
});

test('group cards use the committed assignment and count only reports for its deployed revision', () => {
  const devices = [
    { ...online, status: 'offline', configuration_state: applied },
    { ...online, device_id: 'stale', configuration_state: { ...applied, revision: 'old' } },
    { ...online, device_id: 'other', configuration_state: { ...applied, package_id: 'other' } },
  ];
  const groups = { 'The Lab': ['headset', 'stale', 'other', 'missing'], Empty: [], Undeployed: [], None: [] };
  const fixture = createHarness(() => assert.fail('group cards should not fetch data'), { groups, devices });
  fixture.broadcast({
    assignments: { 'The Lab': 'pkg', Empty: 'pkg', Undeployed: 'pkg2' },
    deployments: { 'The Lab': d1, Empty: d1, Undeployed: d1 },
  });
  const summary = name => fixture.controller.groupPackageSummary(name);
  assert.deepEqual(summary('The Lab'), { name: 'Studio', count: { text: 'Applied 1/4', state: 'pending' } });
  assert.deepEqual(summary('Empty'), { name: 'Studio', count: { text: 'Applied 0/0', state: '' } });
  assert.deepEqual(summary('Undeployed'), { name: 'Night studio', count: null });
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

test('an authoritative WS catalog clears a previous package-list failure', async () => {
  const fixture = createHarness(() => response({ error: 'Unavailable' }, 503));
  fixture.controller.showPackages();
  await new Promise(resolve => setImmediate(resolve));
  assert.match(fixture.packageList.innerHTML, /data-refresh-packages/);

  fixture.broadcast({ packages: [] });
  assert.match(fixture.packageList.innerHTML, /No packages yet/);
  assert.doesNotMatch(fixture.packageList.innerHTML, /data-refresh-packages|Reload packages/);
});

test('an unavailable configuration snapshot keeps known packages visible but disables configuration actions', () => {
  const fixture = createHarness(() => assert.fail('unavailable configuration must not start a write'));
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': d1 } });
  fixture.broadcast({ packages: [], configuration_available: false,
    assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': d1 } });
  fixture.controller.renderGroup('The Lab');

  assert.equal(fixture.catalogNotice.hidden, false);
  assert.match(fixture.catalogStatus.textContent, /Waiting for the server connection/);
  assert.doesNotMatch(fixture.packageList.innerHTML, /Waiting for the server connection/);
  assert.match(fixture.packageList.innerHTML, /data-package-id="pkg"[^>]*disabled/);
  assert.equal(fixture.groupControls['[data-group-package]'].disabled, true);
  assert.equal(fixture.groupButtons.edit.disabled, true);
  assert.equal(fixture.groupButtons.deploy.disabled, true);
  assert.deepEqual(fixture.controller.groupPackageSummary('The Lab'), { name: null, count: null });
  assert.equal(fixture.controller.groupPackageSummary('Empty')?.name, null);
});

test('package Reload waits for a fresh group snapshot and retries after reconnect', async () => {
  const snapshotRequests = [];
  let socketConnected = false;
  const fixture = createHarness(({ url }) => url === '/api/packages'
    ? response({ packages: [pkg] })
    : response(profile()), { requestGroupSnapshot: () => {
      snapshotRequests.push('requested');
      return socketConnected;
    } });
  fixture.broadcast({ packages: [pkg], configuration_available: true,
    assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': d1 } });
  fixture.controller.renderGroup('The Lab');
  assert.equal(fixture.createControls.input.disabled, false);

  fixture.broadcast({ packages: [], configuration_available: false,
    assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': d1 } });
  fixture.controller.renderGroup('The Lab');
  assert.equal(fixture.createControls.input.disabled, true);
  assert.equal(fixture.createControls.button.disabled, true);

  const reload = { dataset: {} };
  const requestsBeforeReload = fixture.requests.length;
  await fixture.catalogNotice.handlers.get('click')({ target: { closest: () => reload } });
  assert.equal(snapshotRequests.length, 1);
  assert.equal(fixture.requests.length, requestsBeforeReload,
    'an unavailable server snapshot does not start a catalog GET while disconnected');
  assert.match(fixture.catalogStatus.textContent, /Waiting for the server connection/);
  assert.equal(fixture.createControls.input.disabled, true,
    'catalog contents do not determine configuration availability');
  assert.equal(fixture.groupControls['[data-group-package]'].disabled, true);

  socketConnected = true;
  fixture.controller.setGroupSnapshotConnected(true);
  assert.equal(snapshotRequests.length, 2, 'reconnection requests a fresh authoritative snapshot');
  assert.match(fixture.catalogStatus.textContent, /Checking configuration status with the server/);

  fixture.broadcast({ packages: [pkg], configuration_available: true,
    assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': d1 } });
  assert.equal(fixture.catalogNotice.hidden, true);
  assert.equal(fixture.createControls.input.disabled, false,
    'a later authoritative GROUP_LIST restores package actions');
});

test('opening the package catalog waits for configuration availability on the group snapshot', async () => {
  const fixture = createHarness(() => assert.fail('opening the catalog must wait for the group snapshot'));
  fixture.broadcast({ packages: [], configuration_available: false });

  await fixture.controller.showPackages();

  assert.equal(fixture.requests.length, 0);
  assert.match(fixture.catalogStatus.textContent, /Waiting for the server connection/);
  assert.equal(fixture.createControls.input.disabled, true);
});

test('a delayed successful catalog GET cannot replace a newer GROUP_LIST catalog', async () => {
  const oldPackage = { ...pkg, name: 'Stale package' };
  const currentPackage = { ...pkg2, name: 'Current package' };
  let resolveCatalog;
  const fixture = createHarness(({ url }) => url === '/api/packages'
    ? new Promise(resolve => { resolveCatalog = resolve; })
    : response(profile()));
  fixture.broadcast({ packages: [oldPackage], configuration_available: true });
  fixture.controller.renderGroup('The Lab');

  const reload = fixture.packageList.handlers.get('click')({ target: { closest: () => ({ dataset: {} }) } });
  assert.equal(fixture.requests.at(-1).url, '/api/packages');
  fixture.broadcast({ packages: [currentPackage], configuration_available: true });
  assert.match(fixture.packageList.innerHTML, /Current package/);

  resolveCatalog(response({ packages: [oldPackage] }));
  await reload;
  assert.match(fixture.packageList.innerHTML, /Current package/);
  assert.doesNotMatch(fixture.packageList.innerHTML, /Stale package/);
  assert.equal(fixture.createControls.button.disabled, false);
});

test('package creation finally keeps controls disabled after availability becomes false', async () => {
  let rejectCreate;
  const fixture = createHarness(({ url, method }) => {
    if (url === '/api/packages' && method === 'POST') {
      return new Promise((_, reject) => { rejectCreate = reject; });
    }
    return response({ packages: [] });
  });
  fixture.broadcast({ packages: [], configuration_available: true });

  const pendingCreate = fixture.create('New package');
  assert.equal(fixture.createControls.button.disabled, true);
  fixture.broadcast({ packages: [], configuration_available: false });
  rejectCreate(Object.assign(new Error('Configuration packages are unavailable'), { status: 503 }));
  await pendingCreate;

  assert.equal(fixture.createControls.input.disabled, true);
  assert.equal(fixture.createControls.button.disabled, true);
  const requestCount = fixture.requests.length;
  await fixture.create('Blocked package');
  assert.equal(fixture.requests.length, requestCount,
    'a re-enabled submit must not reach the API while the snapshot is unavailable');
});

test('a late package-list failure cannot override a newer WS catalog', async () => {
  let rejectCatalog;
  const fixture = createHarness(() => new Promise((_, reject) => { rejectCatalog = reject; }));
  fixture.controller.showPackages();
  assert.equal(fixture.requests.length, 1);

  fixture.broadcast({ packages: [] });
  rejectCatalog(new Error('network unavailable'));
  await new Promise(resolve => setImmediate(resolve));

  assert.match(fixture.packageList.innerHTML, /No packages yet/);
  assert.doesNotMatch(fixture.packageList.innerHTML, /data-refresh-packages|Reload packages/);
});

test('save and deploy sends the edited name and settings in one confirmed request', async () => {
  let approve = false;
  let draft = profile({ editRevision: null, publishedRevision: null, settings: {} });
  const fixture = createHarness(({ method, url, body }) => {
    if (url === '/api/packages' && method === 'POST') return response(draft);
    if (method === 'GET') return response(draft);
    draft = { ...draft, name: body.name, settings: body.settings, edit_revision: 'edit2', published_revision: 'r1' };
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
  assert.match(fixture.editorControls['[data-package-publication]'].textContent, /Loaded version r1/);
});

test('duplicating a package copies saved settings and opens an unpublished package', async () => {
  const source = profile({ name: 'Office', editRevision: 'edit1', publishedRevision: 'published1' });
  const duplicate = profile({ id: 'copy', name: 'Office (copy)', editRevision: 'edit2', publishedRevision: null });
  const fixture = createHarness(({ url, method, body }) => {
    if (url === '/api/packages') return response({ packages: [source] });
    if (url === '/api/packages/pkg') return response(source);
    if (url === '/api/packages/pkg/duplicate' && method === 'POST') return response(duplicate);
    assert.fail('unexpected request ' + method + ' ' + url + ' ' + JSON.stringify(body));
  });
  fixture.broadcast({ packages: [source] });
  fixture.controller.showPackages();
  fixture.packageList.handlers.get('click')({ target: { closest: () => ({
    dataset: { packageId: 'pkg' }, disabled: false,
  }) } });
  await new Promise(resolve => setImmediate(resolve));
  await fixture.editClick('duplicate');
  assert.deepEqual(fixture.requests.at(-1), {
    url: '/api/packages/pkg/duplicate', method: 'POST',
    body: { expected_revision: 'edit1', name: 'Office (copy)' },
  });
  assert.equal(fixture.packagePanel.innerHTML.includes('Edit configuration package'), true);
  assert.match(fixture.packageList.innerHTML, /Office \(copy\)/);
});

test('Save & deploy fills a cleared shutdown time when the schedule is disabled or unmanaged', async () => {
  for (const mode of ['disabled', 'unmanaged']) {
    let latest = profile({ settings: { shutdown: { managed: true, value: {
      enabled: true, hour: 22, minute: 15, repeat: 127,
    } } } });
    const fixture = createHarness(({ method, body }) => {
      if (method === 'GET') return response(latest);
      latest = profile({ settings: body.settings, editRevision: 'edit2', publishedRevision: 'r1' });
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
  assert.equal(fixture.requests.at(-1).body.expected_revision, 'edit1');
  assert.equal(fixture.requests.at(-1).body.expected_impact, 'impact2');
  assert.match(fixture.confirmations.at(-1), /1 group and 4 devices/);
});

test('a revision changed by another console blocks publication before confirmation', async () => {
  let latest = profile();
  const fixture = createHarness(() => response(latest));
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  latest = profile({ editRevision: 'remote' });
  await fixture.editClick('deploy');
  assert.equal(fixture.confirmations.length, 0);
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /The package changed/);
  assert.equal(fixture.editorButtons.reload.hidden, false);
  assert.equal(fixture.editorButtons.deploy.disabled, true);
});

test('a save conflict preserves independent edits and replaces overlapping values with the latest version', async () => {
  let latest = profile();
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    if (body.expected_revision === 'edit1') {
      latest = profile({ editRevision: 'remote', settings: {
        brightness: { managed: true, value: 62 }, autoSleep: { managed: true, value: true },
      } });
      return response({ error: 'Draft changed.' }, 409);
    }
    return response({ ...latest, edit_revision: 'edit2', settings: body.settings, name: body.name });
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  fixture.edit({ dataset: { packageName: '' }, value: 'Local name' });
  fixture.edit({ dataset: { setting: 'brightness' }, type: 'range', value: '86' });
  await fixture.editClick('deploy');
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /package or deployment targets changed/);
  assert.equal(fixture.editorButtons.deploy.disabled, true);
  await fixture.editClick('reload');
  assert.match(fixture.packagePanel.innerHTML, /data-package-name[^>]+value="Local name"/);
  assert.match(fixture.packagePanel.innerHTML, /type="range"[^>]+value="62"/);
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /overlapped newer server changes.*Screen brightness/);
  assert.equal(fixture.editorButtons.deploy.disabled, false);
  await fixture.editClick('deploy');
  assert.equal(fixture.requests.at(-1).body.expected_revision, 'remote');
  assert.equal(fixture.requests.at(-1).body.name, 'Local name');
  assert.deepEqual(fixture.requests.at(-1).body.settings.brightness, { managed: true, value: 62 });
  assert.deepEqual(fixture.requests.at(-1).body.settings.autoSleep, { managed: true, value: true });
});

test('a Wi-Fi strategy edit conflicts with a remote network addition', async () => {
  const initialWifi = { managed: true, value: { auto_return: false, prioritize_networks: true,
    networks: [{ ssid: 'Office', security: 'OPEN', hidden: false }] } };
  let latest = profile({ settings: { wifi: initialWifi } });
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    if (body.expected_revision === 'edit1') {
      latest = profile({ editRevision: 'remote', settings: { wifi: { managed: true, value: {
        ...initialWifi.value,
        networks: [...initialWifi.value.networks, { ssid: 'Backup', security: 'OPEN', hidden: false }],
      } } } });
      return response({ error: 'Draft changed.' }, 409);
    }
    latest = { ...latest, edit_revision: 'edit2', published_revision: 'r1', settings: body.settings };
    return response(latest);
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  fixture.edit({ dataset: { setting: 'wifi', field: 'auto_return' }, type: 'checkbox', checked: true });
  await fixture.editClick('deploy');
  await fixture.editClick('reload');

  assert.match(fixture.packagePanel.innerHTML, /value="Backup"/);
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /overlapped newer server changes.*Wi-Fi networks/);
  assert.equal(fixture.editorButtons.deploy.disabled, false);
  await fixture.editClick('deploy');
  assert.deepEqual(fixture.requests.at(-1).body.settings.wifi.value, {
    auto_return: false,
    networks: [
      { ssid: 'Office', security: 'OPEN', hidden: false },
      { ssid: 'Backup', security: 'OPEN', hidden: false },
    ],
  });
});

test('a masked local Wi-Fi password cannot overwrite a newer saved password', async () => {
  const initial = profile({ settings: { wifi: { managed: true, value: {
    auto_return: false, prioritize_networks: true,
    networks: [{ ssid: 'Studio', security: 'WPA2_WPA3', hidden: false,
      password: 'saved-secret', password_present: true }],
  } } } });
  let latest = initial;
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    if (body.expected_revision === 'edit1') {
      latest = profile({ editRevision: 'remote', settings: { wifi: { managed: true, value: {
        auto_return: false, prioritize_networks: true,
        networks: [{ ssid: 'Studio', security: 'WPA2_WPA3', hidden: false,
          password: 'remote-secret', password_present: true }],
      } } } });
      return response({ error: 'Draft changed.' }, 409);
    }
    return response({ ...latest, edit_revision: 'edit2', settings: body.settings });
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  fixture.edit({ dataset: { setting: 'wifi', network: '0', field: 'password' }, type: 'password', value: 'local-secret' });
  await fixture.editClick('deploy');
  await fixture.editClick('reload');

  assert.deepEqual(fixture.passwordInputs().map(input => input.value), ['remote-secret']);
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /overlapped newer server changes.*Wi-Fi networks/);
  await fixture.editClick('deploy');
  assert.equal(Object.hasOwn(fixture.requests.at(-1).body.settings.wifi.value.networks[0], 'password'), false,
    'the latest saved password is retained without resending a stale or conflicting secret');
});

test('concurrent edits to hour and minute cannot produce a third shutdown time', async () => {
  const initial = { managed: true, value: { enabled: true, hour: 22, minute: 15, repeat: 127 } };
  let latest = profile({ settings: { shutdown: initial } });
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    if (body.expected_revision === 'edit1') {
      latest = profile({ editRevision: 'remote', settings: { shutdown: { managed: true, value: {
        enabled: true, hour: 22, minute: 45, repeat: 127,
      } } } });
      return response({ error: 'Draft changed.' }, 409);
    }
    return response({ ...latest, settings: body.settings, edit_revision: 'edit2' });
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  fixture.edit({ dataset: { setting: 'shutdown', field: 'time' }, type: 'time', value: '20:15' });
  await fixture.editClick('deploy');
  await fixture.editClick('reload');

  assert.match(fixture.packagePanel.innerHTML, /data-field="time" value="22:45"/);
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /overlapped newer server changes.*Scheduled shutdown/);
  await fixture.editClick('deploy');
  assert.deepEqual(fixture.requests.at(-1).body.settings.shutdown.value,
    { enabled: true, hour: 22, minute: 45, repeat: 127 });
});

test('a newer or removed catalog package keeps the loaded revision visible and requires reload', async () => {
  let latest = profile();
  let removed = false;
  const fixture = createHarness(({ method }) => {
    if (method === 'GET') return removed
      ? response({ error: 'Package not found.' }, 404) : response(latest);
    assert.fail('stale or removed packages must not be deployed');
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');

  fixture.broadcast({ packages: [{ ...pkg, published_revision: 'r1' }, pkg2] });
  assert.match(fixture.editorControls['[data-package-publication]'].textContent,
    /Loaded version r0.*Latest deployed version r1 is available/);
  assert.match(fixture.editorControls['[data-deploy-hint]'].textContent, /Reload package before deploying/);
  assert.equal(fixture.editorButtons.reload.hidden, false);
  assert.equal(fixture.editorButtons.deploy.disabled, true);

  latest = profile({ publishedRevision: 'r1' });
  await fixture.editClick('reload');
  assert.match(fixture.editorControls['[data-package-publication]'].textContent, /Loaded version r1/);
  assert.equal(fixture.editorButtons.deploy.disabled, false);

  removed = true;
  fixture.broadcast({ packages: [pkg2] });
  assert.match(fixture.editorControls['[data-package-publication]'].textContent, /removed elsewhere/);
  assert.equal(fixture.editorButtons.reload.hidden, false);
  assert.equal(fixture.editorButtons.deploy.disabled, true);
  await fixture.editClick('reload');
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /removed elsewhere/);
  assert.equal(fixture.editorButtons.deploy.disabled, true);
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
  assert.match(fixture.editorControls['[data-package-publication]'].textContent, /Loaded version r1/);
  assert.equal(fixture.editorButtons.deploy.disabled, false);
});

test('a publish response after a server restart exposes reload before another deploy', async () => {
  let finishPublish;
  const fixture = createHarness(({ method }) => {
    if (method === 'GET') return response(profile());
    return new Promise(resolve => { finishPublish = resolve; });
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');

  const deploying = fixture.editClick('deploy');
  await new Promise(resolve => setImmediate(resolve));
  fixture.broadcast({ server_instance_id: 'server-b' });
  finishPublish(response(profile({ publishedRevision: 'r1' })));
  await deploying;

  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /server restarted/);
  assert.equal(fixture.editorButtons.reload.hidden, false);
  assert.equal(fixture.editorButtons.deploy.disabled, true);

  await fixture.editClick('reload');
  assert.equal(fixture.editorButtons.reload.hidden, true);
  assert.equal(fixture.editorButtons.deploy.disabled, false);
});

test('an own WS publication update is not shown as loaded until the matching HTTP profile arrives', async () => {
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
  assert.match(fixture.editorControls['[data-package-publication]'].textContent,
    /Loaded version r0.*Checking latest deployed version newer/);
  finishSave(response(profile({ editRevision: 'edit2', publishedRevision: 'newer', settings: { brightness: { managed: true, value: 63 } } })));
  await saving;
  assert.match(fixture.packagePanel.innerHTML, /type="range"[^>]+value="63"/);
  assert.match(fixture.packageList.innerHTML, /Remote name/);
  assert.match(fixture.editorControls['[data-package-publication]'].textContent, /Loaded version newer/);
  assert.equal(fixture.editorButtons.reload.hidden, true);
  assert.equal(fixture.editorButtons.deploy.disabled, false);
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

test('group deployment preserves a staged selection and ignores a delayed older group snapshot', async () => {
  let finishAssignment;
  const name = 'lab/+ Wi-Fi?&';
  const fixture = createHarness(({ method }) => method === 'GET' ? response(profile()) :
    new Promise(resolve => { finishAssignment = resolve; }), {
    groups: { [name]: ['headset'] }, devices: [{ ...online, configuration_state: applied }],
  });
  const groupBroadcast = (assigned, deployment) => fixture.broadcast({
    packages: [pkg, { ...pkg2, published_revision: 'r2' }],
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
  finishAssignment(response(groupSnapshot(3, {
    packages: [pkg, { ...pkg2, published_revision: 'r2' }],
    assignments: { [name]: 'pkg2' }, deployments: { [name]: { package_id: 'pkg2', revision: 'r2', keys: ['brightness'] } },
  })));
  await saving;
  assert.equal(selection.value, 'pkg');
  assert.match(results.innerHTML, /All managed settings were verified/);
  await fixture.groupClick('edit');
  assert.equal(fixture.opened(), 1);
});

test('group deploy applies its committed HTTP snapshot before a delayed older GROUP_LIST', async () => {
  const nextPackage = { ...pkg2, published_revision: 'r2' };
  const nextDeployment = { package_id: 'pkg2', revision: 'r2', keys: ['brightness'] };
  const acceptedSnapshots = [];
  const fixture = createHarness(({ method }) => method === 'POST'
    ? response(groupSnapshot(3, { packages: [pkg, nextPackage], assignments: { [name]: 'pkg2' },
      deployments: { [name]: nextDeployment } }))
    : response({ ...profile(), use_current: true, impact: { groups: 1, devices: 1, token: 'impact1' } }), {
    devices: [{ ...online, configuration_state: applied }],
    onSnapshot: (data, source) => acceptedSnapshots.push([data.revision, source]),
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
  assert.equal(publication.textContent, 'Deployed version r2');
  assert.match(results.innerHTML, /Waiting for the device to report this deployment/);

  fixture.broadcast({ revision: 2, packages: [pkg, nextPackage], assignments: { [name]: 'pkg' }, deployments: { [name]: d1 } });
  fixture.controller.renderGroup(name);
  assert.deepEqual(acceptedSnapshots, [[1, 'ws'], [3, 'http']]);
  assert.equal(publication.textContent, 'Deployed version r2');
  assert.match(results.innerHTML, /Waiting for the device to report this deployment/);
});

test('a newer GROUP_LIST received before the deploy response remains authoritative', async () => {
  const name = 'The Lab';
  const nextPackage = { ...pkg2, published_revision: 'r2' };
  const latestDeployment = { package_id: 'pkg', revision: 'r3', keys: ['brightness'] };
  let finishDeploy;
  const fixture = createHarness(({ method }) => method === 'POST'
    ? new Promise(resolve => { finishDeploy = resolve; })
    : response({ ...profile(), use_current: true, impact: { groups: 1, devices: 1, token: 'impact1' } }));
  fixture.broadcast({ revision: 10, packages: [pkg, nextPackage], assignments: { [name]: 'pkg' }, deployments: { [name]: d1 } });
  fixture.controller.renderGroup(name);
  fixture.select('pkg2');
  const deploying = fixture.groupClick('deploy');
  await new Promise(resolve => setImmediate(resolve));

  fixture.broadcast({ revision: 12, packages: [pkg, nextPackage], assignments: { [name]: 'pkg' },
    deployments: { [name]: latestDeployment } });
  finishDeploy(response(groupSnapshot(11, { packages: [pkg, nextPackage], assignments: { [name]: 'pkg2' },
    deployments: { [name]: { package_id: 'pkg2', revision: 'r2', keys: ['brightness'] } } })));
  await deploying;

  assert.equal(fixture.groupControls['[data-group-package]'].value, 'pkg');
  assert.equal(fixture.groupControls['[data-group-publication]'].textContent, 'Deployed version r3');
  assert.match(fixture.groupControls['[data-assignment-feedback]'].textContent, /newer group state/);
});

test('a new active server instance resets revision ordering and rejects an old in-flight HTTP response', async () => {
  let finishDeploy;
  const name = 'The Lab';
  const nextPackage = { ...pkg2, published_revision: 'r2' };
  const latestDeployment = { package_id: 'pkg', revision: 'r3', keys: ['brightness'] };
  const accepted = [];
  const fixture = createHarness(({ method }) => method === 'POST'
    ? new Promise(resolve => { finishDeploy = resolve; })
    : response({ ...profile(), use_current: true, impact: { groups: 1, devices: 1, token: 'impact1' } }), {
    onSnapshot: data => accepted.push([data.server_instance_id, data.revision]),
  });
  fixture.broadcast({ revision: 50, server_instance_id: 'server-old', packages: [pkg, nextPackage],
    assignments: { [name]: 'pkg' }, deployments: { [name]: d1 } });
  fixture.controller.renderGroup(name);
  fixture.select('pkg2');
  const deploying = fixture.groupClick('deploy');
  await new Promise(resolve => setImmediate(resolve));

  fixture.broadcast({ revision: 0, server_instance_id: 'server-new', assignments: { [name]: 'pkg' },
    deployments: { [name]: latestDeployment } });
  finishDeploy(response(groupSnapshot(51, { packages: [pkg, nextPackage], assignments: { [name]: 'pkg2' },
    deployments: { [name]: { package_id: 'pkg2', revision: 'r2', keys: ['brightness'] } },
    server_instance_id: 'server-old' })));
  await deploying;

  assert.deepEqual(accepted, [['server-old', 50], ['server-new', 0]]);
  assert.equal(fixture.groupControls['[data-group-package]'].value, 'pkg');
  assert.equal(fixture.groupControls['[data-group-publication]'].textContent, 'Deployed version r3');
});

test('Group Settings redeploys the assigned package only after confirming the shared targets', async () => {
  let allowDeploy = false;
  const fixture = createHarness(({ method }) => method === 'POST'
    ? response(groupSnapshot(2, { assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': { ...d1, revision: 'r2' } } }))
    : response(profile()), { confirm: () => allowDeploy });
  const name = 'The Lab';
  const hint = fixture.groupControls['[data-deploy-hint]'];
  const deployRequests = () => fixture.requests.filter(request => request.url.startsWith('/api/group-package/'));
  fixture.broadcast({ assignments: { [name]: 'pkg' }, deployments: { [name]: d1 } });
  fixture.controller.renderGroup(name);
  assert.equal(fixture.groupButtons.deploy.hidden, false);
  assert.equal(fixture.groupButtons.deploy.disabled, false);
  fixture.select('pkg2');
  assert.equal(fixture.groupButtons.deploy.disabled, true);
  assert.equal(hint.hidden, false);
  assert.match(hint.textContent, /Save & deploy/);
  assert.equal(fixture.groupButtons.edit.hidden, false);
  await fixture.groupClick('deploy');
  assert.deepEqual(deployRequests(), []);

  fixture.select('pkg');
  assert.equal(fixture.groupButtons.deploy.disabled, false);
  assert.equal(hint.hidden, true);
  await fixture.groupClick('deploy');
  assert.match(fixture.confirmations[0], /Deploy "Studio" to 2 groups and 5 devices\?[\s\S]*again as a new version[\s\S]*includes offline devices/);
  assert.deepEqual(deployRequests().map(request => request.method), ['GET']);

  allowDeploy = true;
  const deploying = fixture.groupClick('deploy');
  assert.equal(fixture.groupButtons.deploy.disabled, true);
  await fixture.groupClick('deploy');
  await deploying;
  const url = '/api/group-package/deploy?name=The+Lab&package_id=pkg';
  assert.deepEqual(deployRequests().slice(1), [
    { url, method: 'GET', body: undefined },
    { url, method: 'POST', body: { expected_impact: 'impact1' } },
  ]);
  assert.match(fixture.groupControls['[data-assignment-feedback]'].textContent, /Deployment started/);
});

test('Group Settings stops managing settings through the same confirmation flow', async () => {
  const url = '/api/group-package/deploy?name=The+Lab&package_id=';
  const fixture = createHarness(request => {
    if (request.url === '/api/packages/pkg') return response(profile());
    if (request.method === 'POST') return response(groupSnapshot(2));
    return response({ name: 'No managed settings', clear: true, wifi_mode: 'priority',
      impact: { groups: 1, devices: 1, token: 'clear1' } });
  });
  const name = 'The Lab';
  fixture.broadcast({ assignments: { [name]: 'pkg' }, deployments: { [name]: d1 } });
  fixture.controller.renderGroup(name);
  fixture.select('');
  assert.equal(fixture.groupButtons.edit.hidden, true);
  assert.equal(fixture.groupButtons.deploy.disabled, false);
  const requestCount = fixture.requests.length;
  await fixture.groupClick('deploy');
  assert.match(fixture.confirmations[0], /Stop managing settings for "The Lab" \(1 device\)\?[\s\S]*leaves current device values in place[\s\S]*Wi-Fi auto-connect settings still apply after release[\s\S]*enable auto-connect for it on the headset/);
  assert.deepEqual(fixture.requests.slice(requestCount), [
    { url, method: 'GET', body: undefined },
    { url, method: 'POST', body: { expected_impact: 'clear1' } },
  ]);
  assert.equal(fixture.groupButtons.deploy.disabled, false);
  fixture.controller.renderGroup(name);
  assert.equal(fixture.groupControls['[data-group-package]'].value, '');
  assert.equal(fixture.groupControls['[data-deployment-count]'].hidden, true);
  assert.equal(fixture.groupButtons.deploy.disabled, false);
  assert.equal(fixture.groupControls['[data-assignment-feedback]'].textContent, 'Release saved. Offline devices receive it when they reconnect.');
});

test('No managed settings for Android selection explains retained Wi-Fi auto-connect settings', async () => {
  const url = '/api/group-package/deploy?name=The+Lab&package_id=';
  const fixture = createHarness(request => {
    if (request.url === '/api/packages/pkg') return response(profile());
    if (request.method === 'POST') return response(groupSnapshot(2));
    return response({ name: 'No managed settings', clear: true, wifi_mode: 'android_selection',
      impact: { groups: 1, devices: 1, token: 'clear1' } });
  });
  const name = 'The Lab';
  fixture.broadcast({ assignments: { [name]: 'pkg' }, deployments: { [name]: { ...d1, keys: ['wifi'], wifi_mode: 'android_selection' } } });
  fixture.controller.renderGroup(name);
  fixture.select('');
  await fixture.groupClick('deploy');
  assert.match(fixture.confirmations[0], /leaves current device values in place/);
  assert.match(fixture.confirmations[0], /Wi-Fi auto-connect settings still apply after release/);
  assert.match(fixture.confirmations[0], /enable auto-connect for it on the headset/);
});

test('No managed settings for a scalar-only deployment has no Wi-Fi confirmation text', async () => {
  const fixture = createHarness(request => {
    if (request.method === 'POST') return response(groupSnapshot(2));
    return response({ name: 'No managed settings', clear: true, wifi_mode: null,
      impact: { groups: 1, devices: 1, token: 'clear1' } });
  });
  const name = 'The Lab';
  fixture.broadcast({ assignments: { [name]: 'pkg' }, deployments: { [name]: d1 } });
  fixture.controller.renderGroup(name);
  fixture.select('');
  await fixture.groupClick('deploy');
  assert.match(fixture.confirmations[0], /leaves current device values in place/);
  assert.doesNotMatch(fixture.confirmations[0], /first listed network|auto-connect state/);
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
  assert.match(results.innerHTML, /class="settings-result-actions"><span>Apply failed<\/span><button[^>]*>Retry</);
  const retry = fixture.groupClick('retry');
  fixture.groupClick('retry');
  assert.match(results.innerHTML, /aria-expanded="false"/);
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
  assert.doesNotMatch(results.innerHTML, /WRITE_FAILED|Write failed|data-group-action="retry"/);
});

test('Retry keeps focus on the pending result action when its row updates', async () => {
  const devices = [{ ...online, configuration_state: failed }];
  let finishRetry;
  const fixture = createHarness(request => request.url === '/api/packages/pkg'
    ? response(profile()) : new Promise(resolve => { finishRetry = resolve; }), { devices });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': d1 } });
  fixture.controller.renderGroup('The Lab');
  const results = fixture.groupControls['[data-settings-results]'];
  let html = '';
  let activeElement = null;
  let focusedAfterReplace = false;
  Object.defineProperty(results, 'innerHTML', {
    get() { return html; },
    set(value) { html = value; activeElement = null; },
  });
  results.querySelectorAll = selector => {
    if (selector === '[data-group-action="retry"]' && /data-group-action="retry"/.test(html)) {
      return [{ dataset: { deviceId: 'headset' }, focus(options) {
        activeElement = this;
        focusedAfterReplace = options.preventScroll === true;
      } }];
    }
    return [];
  };
  const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document');
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { get activeElement() { return activeElement; } },
  });
  try {
    activeElement = {
      dataset: { deviceId: 'headset' },
      matches(selector) { return selector === '[data-group-action="retry"]'; },
    };
    const handler = fixture.groupPanel.handlers.get('click');
    const retry = handler({ target: { closest: () => ({
      dataset: { groupAction: 'retry', deviceId: 'headset' }, disabled: false,
    }) } });

    assert.equal(focusedAfterReplace, true);
    assert.ok(activeElement);
    assert.match(results.innerHTML, /aria-disabled="true"/);
    devices[0].configuration_state = { ...failed, status: 'applying' };
    fixture.controller.renderGroup('The Lab');
    finishRetry(response({ sent: true }));
    await retry;
  } finally {
    if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument);
    else delete globalThis.document;
  }
});

test('deletion sends the loaded revision and displays a server reference conflict', async () => {
  let referenced = true;
  const fixture = createHarness(({ method }) => method === 'GET' ? response(profile()) :
    referenced ? response({ error: 'Configuration package is assigned to a group', code: 'package_assigned' }, 409) :
      response({ deleted: true }));
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');
  await fixture.editClick('delete');
  assert.deepEqual(fixture.requests.at(-1).body, { expected_revision: 'edit1' });
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /assigned to a group/);
  assert.equal(fixture.editorButtons.reload.hidden, true);
  referenced = false;
  await fixture.editClick('delete');
  assert.equal(fixture.requests.at(-1).method, 'DELETE');
  assert.doesNotMatch(fixture.packageList.innerHTML, /data-package-id="pkg"/);
  assert.match(fixture.packagePanel.innerHTML, /Select a configuration package/);
});

test('a stale delete revision requires reload before the latest package can be deleted', async () => {
  let latest = profile();
  const fixture = createHarness(({ method, body }) => {
    if (method === 'GET') return response(latest);
    if (body.expected_revision !== latest.edit_revision) {
      latest = profile({ editRevision: 'remote' });
      return response({ error: 'The package changed. Reload the package.' }, 409);
    }
    return response({ deleted: true });
  });
  fixture.broadcast();
  await fixture.controller.showPackages('pkg');

  latest = profile({ editRevision: 'remote' });
  await fixture.editClick('delete');
  assert.match(fixture.editorControls['[data-settings-feedback]'].textContent, /The package changed/);
  assert.equal(fixture.editorButtons.reload.hidden, false);
  assert.equal(fixture.editorButtons.deploy.disabled, true);
  assert.deepEqual(fixture.requests.filter(({ method }) => method === 'DELETE').map(({ body }) => body), [
    { expected_revision: 'edit1' },
  ]);

  await fixture.editClick('reload');
  assert.equal(fixture.editorButtons.deploy.disabled, false);
  await fixture.editClick('delete');
  assert.deepEqual(fixture.requests.filter(({ method }) => method === 'DELETE').map(({ body }) => body), [
    { expected_revision: 'edit1' }, { expected_revision: 'remote' },
  ]);
  assert.doesNotMatch(fixture.packageList.innerHTML, /data-package-id="pkg"/);
});

test('editor sections are shared with group results for other packages', async () => {
  const fixture = createHarness(({ url }) => {
    if (url === '/api/packages') return response({ packages: [pkg, pkg2] });
    if (url === '/api/packages/pkg') {
      return response(profile({ itemSections: [{ ...sections[0], items: [
        { ...sections[0].items[0], label: 'Editor brightness' },
      ] }] }));
    }
    assert.fail('group results should reuse the editor sections');
  }, { groups: { 'The Lab': ['headset'] }, devices: [{ ...online, configuration_state: {
    package_id: 'pkg2', revision: 'r2', status: 'complete', results: { brightness: { status: 'applied', value: 60 } },
  } }] });

  fixture.broadcast({ assignments: { 'The Lab': 'pkg2' }, deployments: { 'The Lab': {
    package_id: 'pkg2', revision: 'r2', keys: ['brightness'],
  } } });
  await fixture.controller.showPackages('pkg');
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
  fixture.controller.renderGroup('The Lab');

  assert.match(fixture.groupControls['[data-settings-results]'].innerHTML, /Editor brightness/);
  assert.equal(fixture.requests.filter(request => request.url.startsWith('/api/packages/')).length, 1);
});

test('old section responses cannot replace schema after a server instance change', async () => {
  const pending = [];
  const fixture = createHarness(({ url }) => {
    if (url !== '/api/packages') assert.fail('unexpected package request');
    return new Promise(resolve => pending.push(resolve));
  }, { devices: [{ ...online, configuration_state: applied }] });
  fixture.broadcast({ assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': d1 } });
  fixture.controller.renderGroup('The Lab');
  await new Promise(resolve => setImmediate(resolve));

  fixture.broadcast({ revision: 1, server_instance_id: 'server-b',
    assignments: { 'The Lab': 'pkg' }, deployments: { 'The Lab': d1 } });
  fixture.controller.renderGroup('The Lab');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(pending.length, 2);

  const namedSections = label => [{ ...sections[0], items: [
    { ...sections[0].items[0], label },
  ] }];
  pending[0](response({ packages: [pkg], sections: namedSections('Old server brightness') }));
  await new Promise(resolve => setImmediate(resolve));
  pending[1](response({ packages: [pkg], sections: namedSections('Current server brightness') }));
  await new Promise(resolve => setImmediate(resolve));

  const rendered = fixture.groupControls['[data-settings-results]'].innerHTML;
  assert.match(rendered, /Current server brightness/);
  assert.doesNotMatch(rendered, /Old server brightness/);
});
