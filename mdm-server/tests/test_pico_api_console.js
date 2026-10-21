const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const indexSource = fs.readFileSync(
  path.join(__dirname, '..', 'styly_mdm', 'static', 'index.html'),
  'utf8',
);

function consoleFunction(name) {
  const match = indexSource.match(
    new RegExp(`function ${name}\\([^\\n]*\\) \\{[\\s\\S]*?\\n      \\}`, 'm'),
  );
  assert.ok(match, `the console defines ${name}`);
  return match[0];
}

function makeContext() {
  const messages = [];
  const logs = [];
  const clearedTimers = [];
  const timers = [];
  const devices = new Map([
    ['supported', { status: 'online', pico_startup_app_supported: true }],
    ['old', { status: 'online', pico_startup_app_supported: false }],
    ['offline', { status: 'offline', pico_startup_app_supported: true }],
  ]);
  const button = () => ({ disabled: false, textContent: '' });
  const context = {
    targetTab: 'devices',
    selectedIds: new Set(['supported', 'old', 'offline']),
    selectedConnectionIds: new Set(),
    picoApiAction: { value: 'setLauncher' },
    picoApiPkg: { value: 'com.example.app', hidden: false },
    picoStartupPending: new Map(),
    picoStartupSequence: 0,
    messages,
    logs,
    clearedTimers,
    timers,
    deviceById: (id) => devices.get(id),
    connectionById: () => null,
    isOnline: (device) => Boolean(device && device.status === 'online'),
    onlineTargets: (ids) => ids.filter((id) => {
      const device = devices.get(id);
      return device && device.status === 'online';
    }),
    attentionPowerSupported: () => false,
    addLog: (...args) => logs.push(args),
    wsSend: (message) => { messages.push(JSON.parse(JSON.stringify(message))); return true; },
    setTimeout: (callback, delay) => { timers.push({ callback, delay }); return timers.length; },
    clearTimeout: (id) => clearedTimers.push(id),
    labelFor: (id) => `Device ${id}`,
    Date: { now: () => 1_700_000_000_000 },
    uploadInProgress: false,
    apkFile: { files: [{}] },
    launchPkg: { value: 'com.example.app' },
    uninstallConfirm: { checked: true },
    uninstallPkg: { value: 'com.example.app' },
    startupPkg: { value: 'com.example.app' },
    verifyApkRef: true,
    verifyApkPkg: { value: 'com.example.app' },
    verifyDirRef: true,
    verifyDirPath: { value: '/sdcard/reference' },
    powerConfirm: { checked: true },
    retireConfirm: { checked: false },
    canUpdateDevice: () => false,
    pushPanel: { refreshButton() {} },
    syncPanel: { refreshButton() {} },
  };
  [
    'btnInstall', 'btnLaunch', 'btnUninstall', 'btnStartupSet', 'btnStartupClear',
    'btnPicoApi', 'btnVerifyApk', 'btnVerifyDir', 'btnReboot', 'btnPowerOff',
    'btnRetire', 'btnUpdateClients',
  ].forEach((name) => { context[name] = button(); });

  vm.createContext(context);
  for (const name of ['validPicoPackage', 'settlePicoStartup', 'sendPicoApi', 'updateButtons']) {
    vm.runInContext(`${consoleFunction(name)}\nthis.${name} = ${name};`, context, { filename: `index.html:${name}` });
  }
  return context;
}

test('PICO package validation accepts the 255-character package boundary only', () => {
  const context = makeContext();
  assert.equal(context.validPicoPackage('com.example.app'), true);
  assert.equal(context.validPicoPackage(`a.${'b'.repeat(253)}`), true);
  assert.equal(context.validPicoPackage(`a.${'b'.repeat(254)}`), false);
  assert.equal(context.validPicoPackage(''), false);
  assert.equal(context.validPicoPackage('com.example.bad-name'), false);
});

test('sendPicoApi targets supported online devices and turns literal null into JSON null', () => {
  const context = makeContext();
  context.picoApiPkg.value = '  com.example.app  ';
  context.sendPicoApi();

  assert.equal(context.messages.length, 1);
  assert.deepEqual(context.messages[0], {
    type: 'TRIGGER_PICO_API',
    api: 'setLauncher',
    request_id: 'pico-1700000000000-1',
    target_devices: ['supported'],
    package_name: 'com.example.app',
  });
  assert.ok(context.logs.some((entry) => entry[0].includes('Skipping devices without PICO startup API support')));
  assert.equal(context.picoStartupPending.get('pico-1700000000000-1').ids.size, 1);

  context.picoApiPkg.value = ' null ';
  context.sendPicoApi();
  assert.equal(context.messages[1].package_name, null);
  assert.deepEqual(context.messages[1].target_devices, ['supported']);
});

test('sendPicoApi refuses empty and malformed input and does not broaden unsupported selection', () => {
  const context = makeContext();
  context.picoApiPkg.value = '';
  context.sendPicoApi();
  context.picoApiPkg.value = 'com.bad value';
  context.sendPicoApi();
  assert.deepEqual(context.messages, []);

  context.selectedIds = new Set(['old', 'offline']);
  context.picoApiPkg.value = 'null';
  context.sendPicoApi();
  assert.deepEqual(context.messages, []);
  assert.ok(context.logs.some((entry) => entry[0].includes('No supported online devices selected')));

  context.targetTab = 'attention';
  context.selectedIds = new Set(['supported']);
  context.sendPicoApi();
  assert.deepEqual(context.messages, []);
});

test('settlePicoStartup removes each result and clears its timer only when complete', () => {
  const context = makeContext();
  const pending = { ids: new Set(['supported', 'old']), timer: 17 };
  context.picoStartupPending.set('request', pending);

  context.settlePicoStartup('request', 'supported');
  assert.equal(pending.ids.has('supported'), false);
  assert.equal(pending.ids.has('old'), true);
  assert.equal(context.picoStartupPending.has('request'), true);
  assert.deepEqual(context.clearedTimers, []);

  context.settlePicoStartup('request', 'old');
  assert.equal(context.picoStartupPending.has('request'), false);
  assert.deepEqual(context.clearedTimers, [17]);

  context.picoStartupPending.set('unconfirmed', { ids: new Set(['supported']), timer: 21 });
  context.settlePicoStartup('unconfirmed');
  assert.equal(context.picoStartupPending.has('unconfirmed'), false);
  assert.deepEqual(context.clearedTimers, [17, 21]);
});

test('updateButtons enables native PICO Home actions only for supported online devices and valid input', () => {
  const context = makeContext();

  context.selectedIds = new Set(['old']);
  context.updateButtons();
  assert.equal(context.btnPicoApi.disabled, true);

  context.selectedIds = new Set(['supported']);
  context.picoApiPkg.value = '';
  context.updateButtons();
  assert.equal(context.btnPicoApi.disabled, true);

  context.picoApiPkg.value = 'com.bad value';
  context.updateButtons();
  assert.equal(context.btnPicoApi.disabled, true);

  context.picoApiPkg.value = 'null';
  context.updateButtons();
  assert.equal(context.btnPicoApi.disabled, false);

  context.selectedIds = new Set(['supported', 'old']);
  context.picoApiPkg.value = 'com.example.app';
  context.updateButtons();
  assert.equal(context.btnPicoApi.disabled, false);
});
