const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const nodeFetch = global.fetch;
const indexSource = fs.readFileSync(
  path.join(__dirname, '..', 'styly_mdm', 'static', 'index.html'),
  'utf8',
);

class FakeElement {
  constructor(tagName) {
    this.tagName = tagName;
    this.children = [];
    this.className = '';
    this.dataset = {};
    this.style = {};
    this.textContent = '';
    this.isConnected = true;
    this.parentNode = null;
    this.scrollHeight = 0;
    this.scrollTop = 0;
    this.listeners = new Map();
    this.classList = { toggle() {} };
  }

  appendChild(child) {
    if (child.parentNode) {
      const previous = child.parentNode.children.indexOf(child);
      if (previous >= 0) child.parentNode.children.splice(previous, 1);
    }
    this.children.push(child);
    child.parentNode = this;
    setConnected(child, this.isConnected);
    return child;
  }

  replaceChildren(...children) {
    this.children.forEach((child) => {
      child.parentNode = null;
      setConnected(child, false);
    });
    this.children = children;
    children.forEach((child) => {
      child.parentNode = this;
      setConnected(child, this.isConnected);
    });
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  click() {
    if (!this.isConnected) return;
    (this.listeners.get('click') || []).forEach((listener) => listener());
  }

  remove() {
    if (this.parentNode) {
      const index = this.parentNode.children.indexOf(this);
      if (index >= 0) this.parentNode.children.splice(index, 1);
      this.parentNode = null;
    }
    setConnected(this, false);
  }
}

function setConnected(node, isConnected) {
  node.isConnected = isConnected;
  (node.children || []).forEach((child) => setConnected(child, isConnected));
}

class FakeWebSocket {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;

  constructor(url) {
    this.url = url;
    this.readyState = FakeWebSocket.OPEN;
    this.listeners = new Map();
    this.sent = [];
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  send(message) {
    if (this.sendError) throw this.sendError;
    this.sent.push(JSON.parse(message));
  }

  emit(message) {
    const event = {
      data: JSON.stringify(message),
      stopped: false,
      stopImmediatePropagation() { this.stopped = true; },
    };
    (this.listeners.get('message') || []).forEach((listener) => listener(event));
    return event;
  }

  close() {
    this.readyState = FakeWebSocket.CLOSED;
    (this.listeners.get('close') || []).forEach((listener) => listener({}));
  }

  snapshot(...jobs) {
    return this.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs });
  }

  update(job) {
    return this.emit({ type: 'PUSH_JOB_UPDATED', job });
  }
}

function snapshot(jobId, revision, deviceId, state, enqueueSeq, options = {}) {
  const counts = {
    queued: 0,
    waiting_transfer: 0,
    dispatching: 0,
    downloading: 0,
    validating: 0,
    applying: 0,
    reconciling: 0,
    succeeded: 0,
    failed: 0,
    interrupted: 0,
    unconfirmed: 0,
    total: 1,
  };
  counts[state] = 1;
  return {
    job_id: jobId,
    client_request_id: options.clientRequestId || jobId + '-request',
    revision,
    state: options.jobState || (
      ['succeeded', 'failed', 'interrupted', 'unconfirmed'].includes(state)
        ? 'completed_with_errors' : 'running'
    ),
    mode: options.mode || 'push',
    dest_path: options.destPath || '/sdcard/job',
    updated_at: revision,
    aggregate: counts,
    devices: {
      [deviceId]: {
        state,
        attempt: 1,
        enqueue_seq: enqueueSeq,
        dispatch_revision: options.dispatchRevision !== undefined ? options.dispatchRevision : 1,
        resume_supported: options.resumeSupported !== undefined
          ? options.resumeSupported : options.dispatchRevision !== null,
        manual_wait: options.manualWait === true,
        resume_required: options.resumeRequired === true,
        cancellable: options.cancellable === true,
        retryable: options.retryable === true,
        result: options.result || null,
        failure: options.failure || null,
        reconciliation_reason: options.reconciliationReason || null,
      },
    },
    dispatch_enabled: options.dispatchEnabled !== undefined
      ? options.dispatchEnabled : true,
    dispatch_paused_reason: options.dispatchPausedReason || null,
  };
}

function operatorRequestFields(request) {
  const { request_id, ...fields } = request;
  assert.match(request_id, /^[0-9a-f-]{36}$/, 'every targeted action has a request ID');
  return fields;
}

function operatorAck(request, fields = {}) {
  return {
    ...request,
    action: request.type,
    ...(request.type === 'RETRY_PUSH_DEVICES' ? {
      type: 'PUSH_JOB_ACTION_SENT', source_job_id: request.job_id, job_id: 'created-retry',
    } : request.type === 'CANCEL_PUSH_JOB' ? {
      type: 'PUSH_JOB_ACTION_SENT', status: 'accepted',
      accepted_devices: request.target_devices, rejected_devices: {},
    } : {
      type: 'PUSH_FILES_SENT', resume: true, target_count: request.target_devices.length,
      accepted_devices: request.target_devices,
    }),
    ...fields,
  };
}

function loadAdapter(options = {}) {
  const logContainer = new FakeElement('div');
  const logEmpty = new FakeElement('div');
  const pushJobsAttention = new FakeElement('div');
  const pushJobsTabActions = new FakeElement('div');
  const tabAttention = new FakeElement('button');
  const tabGroups = new FakeElement('button');
  const tabDevices = new FakeElement('button');
  const cntGroups = new FakeElement('span');
  const cntDevices = new FakeElement('span');
  const cntAttention = new FakeElement('span');
  const bridgeState = new Map();
  const applied = [];
  const cleared = [];

  global.window = global;
  global.view = 'console';
  global.devices = [];
  global.provisionalConnections = [];
  global.selectedPushAttentionIds = new Set();
  global.targetTab = 'attention';
  global.groupNames = () => [];
  global.pushAttentionDevices = () => [];
  global.tabGroups = tabGroups;
  global.tabDevices = tabDevices;
  global.tabAttention = tabAttention;
  global.cntGroups = cntGroups;
  global.cntDevices = cntDevices;
  global.cntAttention = cntAttention;
  global.pushJobsTabActions = pushJobsTabActions;
  const renderTabs = indexSource.match(/function renderTabs\(\) \{[\s\S]*?\n      \}/);
  assert.ok(renderTabs, 'the console exposes its tab visibility renderer');
  vm.runInThisContext(renderTabs[0], { filename: 'index.html#renderTabs' });
  const refreshAttention = indexSource.match(/refreshAttention: function \(\) \{[\s\S]*?\n        \}/);
  assert.ok(refreshAttention, 'the console bridge refreshes attention UI from the adapter');
  global.renderTargets = () => {};
  vm.runInThisContext(
    'global.refreshPushJobAttention = (' + refreshAttention[0].replace(/^refreshAttention:\s*/, '') + ');',
    { filename: 'index.html#refreshAttention' },
  );
  global.confirm = options.confirm || (() => true);
  global.WebSocket = FakeWebSocket;
  global.fetch = options.fetch || nodeFetch;
  global.document = {
    getElementById(id) {
      if (id === 'logContainer') return logContainer;
      if (id === 'logEmpty') return logEmpty;
      if (id === 'pushJobsAttention') return pushJobsAttention;
      if (id === 'pushJobsTabActions') return pushJobsTabActions;
      if (id === 'tabAttention') return tabAttention;
      return null;
    },
    createElement(tagName) { return new FakeElement(tagName); },
    createTextNode(text) { return { textContent: text, isConnected: true }; },
  };
  const logging = vm.createContext({ document: global.document, logContainer, logEmpty });
  for (const name of ['makeLogEntry', 'addLog']) {
    vm.runInContext(consoleFunctionSource(name), logging);
  }
  global.__stylyPushJobsV1Bridge = {
    addLog: logging.addLog,
    isDeviceOnline: options.isDeviceOnline || (() => true),
    selectedAttentionDeviceIds() { return Array.from(global.selectedPushAttentionIds); },
    clearAttentionSelection(ids) { ids.forEach(id => global.selectedPushAttentionIds.delete(id)); },
    refreshAttention() { global.refreshPushJobAttention(); },
    invalidateDevice(deviceId) {
      const assignment = window.__stylyPushJobsV1Actions.assignmentFor(deviceId);
      if (assignment) {
        applied.push(assignment);
        bridgeState.set(deviceId, assignment);
      } else {
        const current = bridgeState.get(deviceId);
        cleared.push({ deviceId, jobId: current && current.job_id });
        bridgeState.delete(deviceId);
      }
    },
  };

  const adapterPath = path.join(
    __dirname, '..', 'styly_mdm', 'static', 'push-jobs-v1.js',
  );
  vm.runInThisContext(fs.readFileSync(adapterPath, 'utf8'), { filename: adapterPath });
  return {
    logContainer, pushJobsAttention, pushJobsTabActions, tabAttention, bridgeState, applied, cleared,
    selectAttention(...ids) {
      global.selectedPushAttentionIds = new Set(ids);
      window.__stylyPushJobsV1Actions.refreshAttentionActions();
    },
    setTargetTab(tab) { global.targetTab = tab; global.renderTabs(); },
  };
}

function findElementByText(root, text) {
  const pending = [root];
  while (pending.length) {
    const current = pending.shift();
    for (const child of current.children || []) {
      if (child.textContent === text) return child;
      pending.push(child);
    }
  }
  return undefined;
}

function openConsole(options = {}) {
  const harness = loadAdapter(options);
  return { ...harness, socket: new window.WebSocket('ws://localhost/ws/admin') };
}

function loadConsoleFunction(name, globals) {
  const source = indexSource.match(new RegExp('function ' + name + '\\([^)]*\\) \\{[\\s\\S]*?\\n      \\}'));
  assert.ok(source, 'the console exposes ' + name);
  const context = vm.createContext({ deviceTaskState: {}, selectedPushAttentionIds: new Set(), ...globals });
  if (name === 'taskCellHtml') {
    vm.runInContext(consoleFunctionSource('pushTaskCellHtml'), context);
  }
  if (name === 'renderAttentionHtml') {
    vm.runInContext(consoleFunctionSource('deviceIdHtml'), context);
  }
  vm.runInContext(source[0], context);
  return context;
}

function consoleFunctionSource(name) {
  const match = indexSource.match(new RegExp('function ' + name + '\\([^)]*\\) \\{[\\s\\S]*?\\n      \\}'));
  assert.ok(match, 'the console exposes ' + name);
  return match[0];
}

function openIntegratedConsole(options = {}) {
  const harness = openConsole(options);
  const cells = [new FakeElement('div'), new FakeElement('span')];
  const consoleLogs = [];
  const fields = Object.fromEntries(['files', 'folder', 'name', 'dest', 'send'].map(id => [id, new FakeElement('input')]));
  fields.files.type = 'file';
  fields.folder.type = 'file';
  fields.files.files = [new File(['data'], 'content.txt')];
  fields.folder.files = [];
  fields.dest.value = '/sdcard/job';
  const context = vm.createContext({
    window, WebSocket: window.WebSocket, ws: harness.socket,
    view: 'console', targetTab: 'devices', deviceTaskState: {},
    document: { getElementById(id) { return fields[id]; } },
    targetsBody: { querySelectorAll() { return cells; } },
    deviceById(id) { return { device_id: id, status: options.isDeviceOnline && !options.isDeviceOnline(id) ? 'offline' : 'online' }; },
    pushStateUnavailable() { return false; },
    renderTabs() {}, renderTargets() {}, renderActions() {}, updateButtons() {},
    selectedIds: new Set(options.selectedIds || ['D1']),
    selectedPushAttentionIds: new Set(), pushAttentionSelectionAnchorId: null,
    onlineTargets(ids) {
      return options.onlineTargets ? options.onlineTargets(ids) : ids;
    },
    verifyDirRef: { tree_hash: 'same-hash' }, verifyApkRef: null,
    verifyDirPath: { value: '/sdcard/job' },
    FormData, fetch(...args) { return window.fetch(...args); },
    formatBytes(value) { return String(value); },
    esc(value) { return String(value || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;'); },
    labelFor(id) { return id; }, addLog(message) { consoleLogs.push(message); },
  });
  for (const name of ['pushAssignmentFor', 'pushTaskCellHtml', 'taskCellHtml', 'progressCellHtml',
    'updateTaskCell', 'wsSend', 'handleMessage', 'classifyDir', 'sendVerifyDir', 'createPushPanel']) {
    vm.runInContext(consoleFunctionSource(name), context);
  }
  const bridge = indexSource.match(/window\.__stylyPushJobsV1Bridge = \{[\s\S]*?\n      \};/);
  assert.ok(bridge, 'integration tests use the production bridge');
  vm.runInContext(bridge[0], context);
  const panel = vm.runInContext("createPushPanel({fileId:'files',folderId:'folder',nameId:'name',destId:'dest',btnId:'send',verb:'Push',deleteExtras:false,emptyLabel:'No files'})", context);
  return {
    ...harness, context, cells, fields, panel, consoleLogs,
    selectAttention(...ids) {
      context.selectedPushAttentionIds = new Set(ids);
      window.__stylyPushJobsV1Actions.refreshAttentionActions();
    },
    html() { return cells[0].innerHTML || ''; },
    verify() {
      context.sendVerifyDir();
      context.handleMessage({ type: 'VERIFY_DIR_RESULT', device_id: 'D1', status: 'ok', tree_hash: 'same-hash' });
    },
    sendPush() { return panel.send(); },
  };
}

test('store snapshot renders upload failure, later Resume, and unresolved fence in real cells', {
  skip: !process.env.PUSH_LIFECYCLE_FIXTURES,
}, () => {
  const { failed, paused, fenced, expectedDetail } = JSON.parse(process.env.PUSH_LIFECYCLE_FIXTURES);
  const h = openIntegratedConsole();
  h.socket.snapshot(failed);
  h.verify();
  assert.match(h.html(), /Push ·.*failed/);
  assert.ok(h.html().includes(expectedDetail), 'the store failure detail reaches the real cell');
  assert.match(h.html(), /Verify Dir:.*match/);
  assert.doesNotMatch(h.html(), /Queued|Resume required|data-action="resume"/);
  assert.equal(findElementByText(h.pushJobsTabActions, 'Retry'), undefined);
  assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').job_id, failed.job_id);

  h.socket.snapshot(failed, paused);
  assert.match(h.html(), /Push ·.*Resume required/);
  assert.match(h.html(), new RegExp('data-action="resume".*data-job-id="' + paused.job_id + '"'));
  assert.match(h.html(), /Verify Dir:.*match/);
  assert.equal(findElementByText(h.pushJobsTabActions, 'Retry').disabled, true);
  assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').job_id, paused.job_id);

  h.socket.snapshot(failed, paused, fenced);
  assert.match(h.html(), /Push ·.*Status unknown/);
  assert.match(h.html(), new RegExp('data-action="cancel".*data-job-id="' + fenced.job_id + '"'));
  assert.doesNotMatch(h.html(), /data-action="resume"/);
  assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').job_id, fenced.job_id);
});

test('real bridge keeps Verify Dir alongside Push when Resume advances the canonical job', () => {
  const h = openIntegratedConsole();
  h.socket.snapshot(snapshot('resume-job', 7, 'D1', 'queued', 1, { resumeRequired: true, cancellable: true }));
  h.verify();
  assert.match(h.html(), /Push ·.*Resume required/);
  assert.match(h.html(), /Verify Dir:.*match/);
  assert.match(h.html(), /data-job-id="resume-job"/);
  assert.equal(window.__stylyPushJobsV1Actions.sendDeviceAction('D1', 'resume-job', 'resume'), true);
  h.socket.update(snapshot('resume-job', 8, 'D1', 'downloading', 1));
  assert.match(h.html(), /Resuming…/, 'canonical progress does not acknowledge a Resume request');
  h.socket.emit(operatorAck(h.socket.sent.at(-1)));
  assert.match(h.html(), /Push ·.*Transferring/);
  assert.match(h.html(), /Verify Dir:.*match/);
  assert.doesNotMatch(h.html(), /data-action="resume"/);
  assert.equal(h.context.deviceTaskState.D1.task, 'verify', 'no Push copy owns the generic task state');
  assert.equal(h.cells[1].innerHTML, h.html(), 'all visible cells are invalidated');
});

test('real bridge selects a Retry job after Verify without comparing revisions across jobs', () => {
  const h = openIntegratedConsole();
  h.socket.snapshot(snapshot('failed-job', 90, 'D1', 'failed', 1, { retryable: true }));
  h.verify();
  assert.equal(window.__stylyPushJobsV1Actions.sendDeviceAction('D1', 'failed-job', 'retry'), true);
  assert.equal(h.socket.sent.at(-1).type, 'RETRY_PUSH_DEVICES');
  const source = snapshot('failed-job', 91, 'D1', 'failed', 1);
  source.devices.D1.retry_job_id = 'retry-job';
  h.socket.update(source);
  h.socket.update(snapshot('retry-job', 1, 'D1', 'downloading', 2, { clientRequestId: h.socket.sent.at(-1).client_request_id }));
  assert.match(h.html(), /Push ·.*Transferring/);
  assert.match(h.html(), /Verify Dir:.*match/);
  h.socket.update(snapshot('retry-job', 2, 'D1', 'succeeded', 2));
  assert.match(h.html(), /Push ·.*pushed/);
  assert.match(h.html(), /Verify Dir:.*match/);
});

test('late Install and Verify results never hide canonical Push status or controls', () => {
  const h = openIntegratedConsole();
  h.socket.snapshot(snapshot('active-job', 1, 'D1', 'queued', 1, { resumeRequired: true, cancellable: true }));
  for (const message of [
    { type: 'INSTALL_DEVICE_STATE', device_ids: ['D1'], state: 'installing' },
    { type: 'INSTALL_RESULT', device_id: 'D1', status: 'success', apk_filename: 'app.apk' },
    { type: 'VERIFY_APK_RESULT', device_id: 'D1' },
    { type: 'VERIFY_DIR_RESULT', device_id: 'D1', status: 'ok', tree_hash: 'same-hash' },
  ]) {
    h.context.handleMessage(message);
    assert.match(h.html(), /Push ·.*Resume required/);
    assert.match(h.html(), /data-action="resume".*data-job-id="active-job"/);
    assert.match(h.html(), /data-action="cancel".*data-job-id="active-job"/);
    assert.match(h.html(), /(?:Install|Verify APK|Verify Dir):/);
  }
});

test('canonical refresh invalidates real cells and preserves the independent result when Push disappears', () => {
  const h = openIntegratedConsole();
  h.socket.snapshot(snapshot('active-job', 1, 'D1', 'downloading', 1));
  h.verify();
  h.socket.update(snapshot('active-job', 2, 'D1', 'validating', 1));
  assert.match(h.html(), /Push ·.*Validating/);
  h.socket.snapshot();
  assert.match(h.html(), /match/);
  assert.doesNotMatch(h.html(), /Push ·|Validating|data-job-id/);
  assert.equal(h.context.deviceTaskState.D1.task, 'verify');
});

test('real cells preserve snapshot buffering, revision filtering, and retired socket ordering after Verify', () => {
  const h = openIntegratedConsole();
  h.verify();
  h.socket.update(snapshot('ordered', 3, 'D1', 'validating', 1));
  assert.doesNotMatch(h.html(), /Validating/);
  h.socket.snapshot(snapshot('ordered', 1, 'D1', 'downloading', 1));
  assert.match(h.html(), /Push ·.*Validating.*Verify Dir:.*match/);
  h.socket.update(snapshot('ordered', 2, 'D1', 'applying', 1));
  assert.match(h.html(), /Validating/);
  const replacement = new window.WebSocket('ws://localhost/ws/admin');
  h.socket.update(snapshot('ordered', 99, 'D1', 'succeeded', 1));
  h.socket.snapshot();
  assert.match(h.html(), /Validating/);
  replacement.update(snapshot('ordered', 4, 'D1', 'applying', 1));
  assert.match(h.html(), /Validating/);
  replacement.snapshot(snapshot('ordered', 3, 'D1', 'validating', 1));
  assert.match(h.html(), /Push ·.*Pushing.*Verify Dir:.*match/);
});

test('connectivity invalidation keeps canonical Push separate from Verify', () => {
  let online = true;
  const h = openIntegratedConsole({ isDeviceOnline: () => online });
  h.socket.snapshot(snapshot('offline-job', 1, 'D1', 'queued', 1, { resumeRequired: true }));
  h.verify();
  online = false;
  window.__stylyPushJobsV1Actions.refreshConnectivity();
  assert.match(h.html(), /Resume required · offline.*Verify Dir:.*match/);
  assert.doesNotMatch(h.html(), /data-action="resume"/);
  online = true;
  window.__stylyPushJobsV1Actions.refreshConnectivity();
  assert.match(h.html(), /data-action="resume".*data-job-id="offline-job"/);
  assert.match(h.html(), /Verify Dir:.*match/);
});

test('real bridge preserves old unresolved fence ahead of queued Retry and aligns its controls', () => {
  const h = openIntegratedConsole();
  const old = snapshot('old-fence', 80, 'D1', 'unconfirmed', 1, { cancellable: true });
  old.devices.D1.device_fence = { blocking_job_id: old.job_id };
  const retry = snapshot('retry', 1, 'D1', 'queued', 2, { resumeRequired: true });
  h.socket.snapshot(old, retry);
  h.verify();
  assert.match(h.html(), /Status unknown/);
  assert.match(h.html(), /data-action="cancel".*data-job-id="old-fence"/);
  assert.doesNotMatch(h.html(), /data-action="resume"/);
  h.socket.update({ ...old, revision: 81, devices: { D1: { ...old.devices.D1, device_fence: null } } });
  assert.match(h.html(), /Resume required/);
  assert.match(h.html(), /data-action="resume".*data-job-id="retry"/);
  assert.doesNotMatch(h.html(), /Status unknown|data-job-id="old-fence"/);
});

for (const failure of ['create', 'upload', 'dispatch', 'disconnected']) {
  test('failed ' + failure + ' preserves canonical Push and unrelated results through the real panel', async () => {
    const h = openIntegratedConsole({ fetch: async url => {
      if ((failure === 'create' && url === '/api/push-jobs') ||
          (failure === 'upload' && url === '/upload')) throw new Error(failure + ' failed');
      const body = url === '/api/push-jobs'
        ? { job_id: 'new-job', state: 'created', upload_url: '/upload' }
        : { job_id: 'new-job', state: 'ready' };
      return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
    } });
    h.socket.snapshot(snapshot('existing-job', 10, 'D1', 'succeeded', 1));
    h.verify();
    const before = h.html();
    const other = h.context.deviceTaskState.D1;
    if (failure === 'dispatch') h.socket.sendError = new Error('send failed');
    if (failure === 'disconnected') h.socket.readyState = FakeWebSocket.CLOSED;
    await h.sendPush();
    if (failure === 'disconnected') {
      assert.ok(h.consoleLogs.some(message => message.includes('Admin WebSocket is disconnected')));
    } else {
      assert.ok(h.consoleLogs.some(message => message === 'Push files failed: ' +
        (failure === 'dispatch' ? 'send failed' : failure + ' failed')));
    }
    assert.equal(h.html(), before);
    assert.equal(h.context.deviceTaskState.D1, other);
    assert.match(h.html(), /Push ·.*pushed/);
    assert.match(h.html(), /Verify Dir:.*match/);
    // A failed request must also preserve unrelated state when no Push job exists.
    h.socket.snapshot();
    await h.sendPush();
    assert.equal(h.context.deviceTaskState.D1, other);
    assert.match(h.html(), /match/);
  });
}

test('upload dispatch uses the replacement admin socket', async () => {
  let finishUpload;
  const uploadReady = new Promise((resolve) => { finishUpload = resolve; });
  let uploadCount = 0;
  let createCount = 0;
  const clientRequestId = 'aaaaaaaa-1111-4111-8111-111111111111';
  const jobId = '11111111-1111-4111-8111-111111111111';
  const file = new File(['data'], 'content.txt', { lastModified: 1 });
  const harness = loadAdapter({
    fetch: async (url) => {
      if (url === '/api/bundles') {
        return new Response(JSON.stringify({ bundle_filename: 'legacy.zip' }), { status: 200 });
      }
      if (url === '/api/push-jobs') {
        createCount += 1;
        return new Response(JSON.stringify({
          job_id: jobId,
          state: 'created',
          upload_url: '/api/push-jobs/upload',
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      if (url === '/api/push-jobs/upload') {
        uploadCount += 1;
        await uploadReady;
        return new Response(JSON.stringify({
          job_id: jobId,
          state: 'ready',
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      throw new Error('unexpected URL: ' + url);
    },
  });
  const firstSocket = new window.WebSocket('ws://localhost/ws/admin');
  const legacy = await window.fetch('/api/bundles', { method: 'POST', body: new FormData() });
  assert.equal((await legacy.json()).bundle_filename, 'legacy.zip', 'standalone bundle uploads still use the real fetch implementation');
  const submit = () => window.__stylyPushJobsV1Actions.submitPushJob({
    files: [file],
    targets: ['D1'],
    destPath: '/sdcard/job',
    mode: 'push',
    clientRequestId,
  });
  const firstAttempt = submit();
  await new Promise((resolve) => setImmediate(resolve));
  const replacementSocket = new window.WebSocket('ws://localhost/ws/admin');
  finishUpload();
  await new Promise((resolve) => setImmediate(resolve));

  assert.deepEqual(firstSocket.sent, []);
  assert.deepEqual(replacementSocket.sent, [{
    type: 'PUSH_FILES',
    job_id: jobId,
  }]);
  replacementSocket.close();
  await assert.rejects(firstAttempt, /disconnected/);

  const finalSocket = new window.WebSocket('ws://localhost/ws/admin');
  const retry = submit();
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(finalSocket.sent, [{ type: 'PUSH_FILES', job_id: jobId }]);
  assert.equal(createCount, 1);
  assert.equal(uploadCount, 1, 'a ready job is redispatched without uploading its artifact again');
  finalSocket.emit({ type: 'PUSH_FILES_SENT', job_id: jobId });
  assert.deepEqual(await retry, { job_id: jobId, state: 'dispatched' });
});

test('a terminal snapshot prevents redispatch of a ready job and rotates the panel request ID', async () => {
  const requestIds = [];
  let creates = 0;
  let uploads = 0;
  const firstJobId = '55555555-5555-4555-8555-555555555555';
  const retryJobId = '66666666-6666-4666-8666-666666666666';
  const h = openIntegratedConsole({
    fetch: async (url, init) => {
      if (url === '/api/push-jobs') {
        requestIds.push(JSON.parse(init.body).client_request_id);
        creates += 1;
        const jobId = creates === 1 ? firstJobId : retryJobId;
        return new Response(JSON.stringify({
          job_id: jobId,
          state: 'created',
          upload_url: '/upload-' + creates,
        }), { status: 201, headers: { 'Content-Type': 'application/json' } });
      }
      if (url === '/upload-1' || url === '/upload-2') {
        uploads += 1;
        const jobId = url === '/upload-1' ? firstJobId : retryJobId;
        return new Response(JSON.stringify({ job_id: jobId, state: 'ready' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      throw new Error('unexpected URL: ' + url);
    },
  });
  const selected = h.fields.files.files[0];
  const firstAttempt = h.sendPush();
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(h.socket.sent, [{ type: 'PUSH_FILES', job_id: firstJobId }]);
  h.socket.close();
  assert.equal(await firstAttempt, false);

  const replacementSocket = new window.WebSocket('ws://localhost/ws/admin');
  replacementSocket.snapshot(snapshot(firstJobId, 8, 'D1', 'failed', 1, {
    clientRequestId: requestIds[0],
    dispatchEnabled: false,
    jobState: 'failed',
  }));
  assert.equal(await h.sendPush(), false, 'the terminal job is not redispatched under the old request ID');
  assert.equal(h.fields.files.files[0], selected);
  assert.equal(creates, 1, 'terminal state is surfaced before another create request');
  assert.equal(uploads, 1);
  assert.deepEqual(replacementSocket.sent, []);

  const retryAttempt = h.sendPush();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(creates, 2);
  assert.notEqual(requestIds[1], requestIds[0], 'the panel retries terminal work with a fresh idempotency key');
  assert.equal(uploads, 2);
  assert.deepEqual(replacementSocket.sent, [{ type: 'PUSH_FILES', job_id: retryJobId }]);
  replacementSocket.emit({ type: 'PUSH_FILES_SENT', job_id: retryJobId });
  assert.equal((await retryAttempt).job_id, retryJobId);
});

test('a terminal snapshot settles submission successfully when dispatch was accepted', async () => {
  for (const terminal of [
    { state: 'succeeded', dispatchEnabled: false },
    { state: 'completed_with_errors', dispatchEnabled: false },
    { state: 'failed', dispatchEnabled: true },
  ]) {
    const jobId = '77777777-7777-4777-8777-' + String(terminal.state.length).padStart(12, '0');
    const h = loadAdapter({
      fetch: async (url) => {
        const body = url === '/api/push-jobs'
          ? { job_id: jobId, state: 'created', upload_url: '/upload' }
          : { job_id: jobId, state: 'ready' };
        return new Response(JSON.stringify(body), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      },
    });
    const socket = new window.WebSocket('ws://localhost/ws/admin');
    socket.snapshot();
    const requestId = 'bbbbbbbb-1111-4111-8111-' + String(terminal.state.length).padStart(12, '0');
    const request = window.__stylyPushJobsV1Actions.submitPushJob({
      files: [new File(['data'], 'content.txt', { lastModified: 1 })],
      targets: ['D1'],
      destPath: '/sdcard/job',
      mode: 'push',
      clientRequestId: requestId,
    });
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(socket.sent, [{ type: 'PUSH_FILES', job_id: jobId }]);
    socket.update(snapshot(jobId, 9, 'D1', terminal.state === 'succeeded' ? 'succeeded' : 'failed', 1, {
      clientRequestId: requestId,
      dispatchEnabled: terminal.dispatchEnabled,
      jobState: terminal.state,
    }));
    assert.deepEqual(await request, { job_id: jobId, state: 'dispatched' },
      terminal.state + ' records a successful dispatch independently of its final job result');
  }
});

test('Push panel keeps the selected files after create failure for retry', async () => {
  const h = openIntegratedConsole({
    fetch: async (url) => {
      if (url === '/api/push-jobs') {
        return new Response(JSON.stringify({ error: 'target device is not online' }), {
          status: 422,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      throw new Error('unexpected URL: ' + url);
    },
  });
  const selected = h.fields.files.files[0];

  assert.equal(await h.sendPush(), false);
  assert.equal(h.fields.files.files[0], selected);
  assert.match(h.fields.name.textContent, /selection retained for retry/);
  assert.ok(h.consoleLogs.some((line) => line.includes('target device is not online')));
});

test('target connectivity flaps during a lost upload response reuse the same request and artifact', async () => {
  let finishLostResponse;
  const lostResponse = new Promise((resolve) => { finishLostResponse = resolve; });
  const online = new Set(['D1', 'D2']);
  const jobsByRequestId = new Map();
  const requestIds = [];
  const targetSets = [];
  let artifactCount = 0;
  const h = openIntegratedConsole({
    selectedIds: ['D1', 'D2'],
    onlineTargets(ids) { return ids.filter((id) => online.has(id)); },
    fetch: async (url, init) => {
      if (url === '/api/push-jobs') {
        const body = JSON.parse(init.body);
        requestIds.push(body.client_request_id);
        targetSets.push(body.target_devices);
        const existing = jobsByRequestId.get(body.client_request_id);
        if (existing) {
          return new Response(JSON.stringify({ job_id: existing, state: 'ready' }), {
            status: 200, headers: { 'Content-Type': 'application/json' },
          });
        }
        const jobId = 'flap-job-' + (jobsByRequestId.size + 1);
        jobsByRequestId.set(body.client_request_id, jobId);
        return new Response(JSON.stringify({
          job_id: jobId, state: 'created', upload_url: '/flap-upload',
        }), { status: 201, headers: { 'Content-Type': 'application/json' } });
      }
      if (url === '/flap-upload') {
        artifactCount += 1;
        await lostResponse;
        throw new Error('upload response lost after the server stored the artifact');
      }
      throw new Error('unexpected URL: ' + url);
    },
  });

  const firstAttempt = h.sendPush();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(artifactCount, 1, 'the first upload is in flight before connectivity changes');

  online.delete('D2');
  h.panel.refreshButton(true);
  online.add('D2');
  h.panel.refreshButton(true);
  finishLostResponse();
  assert.equal(await firstAttempt, false);

  const retry = h.sendPush();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(requestIds.length, 2);
  assert.equal(requestIds[1], requestIds[0], 'connectivity-only redraws preserve the idempotency key');
  assert.deepEqual(targetSets, [['D1', 'D2'], ['D1', 'D2']],
    'the retry keeps the targets captured when the operation first started');
  assert.equal(jobsByRequestId.size, 1, 'the retry resolves to the existing server job');
  assert.equal(artifactCount, 1, 'the immutable artifact is not uploaded a second time');
  assert.deepEqual(h.socket.sent, [{ type: 'PUSH_FILES', job_id: 'flap-job-1' }]);
  h.socket.emit({ type: 'PUSH_FILES_SENT', job_id: 'flap-job-1' });
  assert.deepEqual(await retry, { job_id: 'flap-job-1', state: 'dispatched' });
});

test('released submissions stay settleable while active and discard abandoned fingerprints afterward', async () => {
  let finishUpload;
  const uploadReady = new Promise((resolve) => { finishUpload = resolve; });
  const requestId = 'eeeeeeee-1111-4111-8111-111111111111';
  let creates = 0;
  const h = loadAdapter({
    fetch: async (url) => {
      if (url === '/api/push-jobs') {
        creates += 1;
        const jobId = creates === 1 ? 'released-old-job' : 'released-new-job';
        return new Response(JSON.stringify({
          job_id: jobId, state: 'created', upload_url: '/upload-' + creates,
        }), { status: 201, headers: { 'Content-Type': 'application/json' } });
      }
      if (url === '/upload-1') {
        await uploadReady;
        return new Response(JSON.stringify({ job_id: 'released-old-job', state: 'ready' }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        });
      }
      if (url === '/upload-2') {
        return new Response(JSON.stringify({ job_id: 'released-new-job', state: 'ready' }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        });
      }
      throw new Error('unexpected URL: ' + url);
    },
  });
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.snapshot();
  const first = window.__stylyPushJobsV1Actions.submitPushJob({
    files: [new File(['old'], 'old.txt', { lastModified: 1 })],
    targets: ['D1'], destPath: '/sdcard/job', mode: 'push', clientRequestId: requestId,
  });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(window.__stylyPushJobsV1Actions.releaseSubmission(requestId), true);
  finishUpload();
  await new Promise((resolve) => setImmediate(resolve));
  socket.update(snapshot('released-old-job', 2, 'D1', 'downloading', 1, {
    clientRequestId: requestId, dispatchEnabled: true,
  }));
  assert.deepEqual(await first, { job_id: 'released-old-job', state: 'dispatched' },
    'the active request still settles from canonical job state');

  const second = window.__stylyPushJobsV1Actions.submitPushJob({
    files: [new File(['new'], 'new.txt', { lastModified: 2 })],
    targets: ['D1'], destPath: '/sdcard/job', mode: 'push', clientRequestId: requestId,
  });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(creates, 2, 'the abandoned fingerprint was removed after its active promise settled');
  assert.deepEqual(socket.sent, [
    { type: 'PUSH_FILES', job_id: 'released-old-job' },
    { type: 'PUSH_FILES', job_id: 'released-new-job' },
  ]);
  socket.emit({ type: 'PUSH_FILES_SENT', job_id: 'released-new-job' });
  assert.deepEqual(await second, { job_id: 'released-new-job', state: 'dispatched' });
});

test('terminal upload failure rotates the request ID while retaining the selected file', async () => {
  const requestIds = [];
  let creates = 0;
  const firstJobId = '33333333-3333-4333-8333-333333333333';
  const secondJobId = '44444444-4444-4444-8444-444444444444';
  const h = openIntegratedConsole({
    fetch: async (url, init) => {
      if (url === '/api/push-jobs') {
        const body = JSON.parse(init.body);
        requestIds.push(body.client_request_id);
        creates += 1;
        if (creates === 1) {
          return new Response(JSON.stringify({ job_id: firstJobId, state: 'created', upload_url: '/first-upload' }), {
            status: 201, headers: { 'Content-Type': 'application/json' },
          });
        }
        if (creates === 2) {
          return new Response(JSON.stringify({ job_id: firstJobId, state: 'interrupted' }), {
            status: 200, headers: { 'Content-Type': 'application/json' },
          });
        }
        return new Response(JSON.stringify({ job_id: secondJobId, state: 'created', upload_url: '/second-upload' }), {
          status: 201, headers: { 'Content-Type': 'application/json' },
        });
      }
      if (url === '/first-upload') {
        return new Response(JSON.stringify({ error: 'upload interrupted' }), {
          status: 500, headers: { 'Content-Type': 'application/json' },
        });
      }
      if (url === '/second-upload') {
        return new Response(JSON.stringify({ job_id: secondJobId, state: 'ready' }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        });
      }
      throw new Error('unexpected URL: ' + url);
    },
  });
  const selected = h.fields.files.files[0];

  assert.equal(await h.sendPush(), false);
  assert.equal(await h.sendPush(), false);
  assert.equal(h.fields.files.files[0], selected);
  const successfulAttempt = h.sendPush();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(requestIds[1], requestIds[0], 'the in-flight job is looked up with its original idempotency key');
  assert.notEqual(requestIds[2], requestIds[0], 'a terminal upload job is retried under a new key');
  assert.deepEqual(h.socket.sent, [{ type: 'PUSH_FILES', job_id: secondJobId }]);
  h.socket.emit({ type: 'PUSH_FILES_SENT', job_id: secondJobId });
  assert.equal((await successfulAttempt).job_id, secondJobId);
});

test('an older async Push result does not reset a newer file selection', async () => {
  let finishUpload;
  const uploadReady = new Promise((resolve) => { finishUpload = resolve; });
  const h = openIntegratedConsole({
    fetch: async (url) => {
      if (url === '/api/push-jobs') {
        return new Response(JSON.stringify({
          job_id: '22222222-2222-4222-8222-222222222222',
          state: 'created',
          upload_url: '/api/push-jobs/upload',
        }), { status: 201, headers: { 'Content-Type': 'application/json' } });
      }
      if (url === '/api/push-jobs/upload') {
        await uploadReady;
        return new Response(JSON.stringify({ job_id: '22222222-2222-4222-8222-222222222222', state: 'ready' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      throw new Error('unexpected URL: ' + url);
    },
  });
  const oldSelection = h.fields.files.files[0];
  const request = h.sendPush();
  await new Promise((resolve) => setImmediate(resolve));
  const newSelection = new File(['new'], 'new.txt', { lastModified: 2 });
  h.fields.files.files = [newSelection];
  h.fields.files.listeners.get('change')[0]();
  finishUpload();
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(h.socket.sent, [{ type: 'PUSH_FILES', job_id: '22222222-2222-4222-8222-222222222222' }]);
  h.socket.emit({ type: 'PUSH_FILES_SENT', job_id: '22222222-2222-4222-8222-222222222222' });
  await request;

  assert.notEqual(h.fields.files.files[0], oldSelection);
  assert.equal(h.fields.files.files[0], newSelection);
  assert.match(h.fields.name.textContent, /new\.txt/);
});

test('bulk action pending state survives redraw and clears on disconnect', () => {
  const h = openConsole();
  h.socket.snapshot(snapshot('pending-resume', 1, 'D1', 'queued', 1, {
    resumeRequired: true,
    dispatchEnabled: false,
  }));
  h.selectAttention('D1');
  const firstButton = findElementByText(h.pushJobsTabActions, 'Resume');
  assert.ok(firstButton);
  firstButton.click();
  assert.equal(h.socket.sent.length, 1);

  h.socket.update(snapshot('unrelated-job', 1, 'D2', 'downloading', 2));
  const redrawnButton = findElementByText(h.pushJobsTabActions, 'Resume');
  assert.ok(redrawnButton);
  assert.equal(redrawnButton.disabled, true);
  assert.equal(firstButton.isConnected, false, 'replaceChildren detaches the prior button');

  h.socket.close();
  const afterDisconnect = findElementByText(h.pushJobsTabActions, 'Resume');
  assert.ok(afterDisconnect);
  assert.equal(afterDisconnect.disabled, false);
});

test('inline action pending state survives a canonical cell redraw and clears on acknowledgement', () => {
  const h = openIntegratedConsole();
  const job = snapshot('inline-pending-action', 1, 'D1', 'queued', 1, {
    resumeRequired: true, dispatchEnabled: false,
  });
  h.socket.snapshot(job);
  assert.match(h.html(), /data-action="resume"/);

  assert.equal(window.__stylyPushJobsV1Actions.sendDeviceAction('D1', job.job_id, 'resume'), true);
  assert.match(h.html(), /disabled>Resuming…<\/button>/);
  assert.equal(window.__stylyPushJobsV1Actions.sendDeviceAction('D1', job.job_id, 'resume'), false);
  assert.equal(findElementByText(h.pushJobsTabActions, 'Resume').disabled, true,
    'no selected targets are available while the inline action is pending');
  h.socket.update(snapshot('unrelated-inline-job', 1, 'D2', 'downloading', 2));
  h.socket.update({ ...job, revision: 2 });
  assert.match(h.html(), /disabled>Resuming…<\/button>/,
    'a later canonical redraw keeps the action pending');

  h.socket.emit(operatorAck(h.socket.sent.at(-1)));
  assert.match(h.html(), /data-action="resume"/);
  assert.doesNotMatch(h.html(), /Resuming…/);
});

test('a replaced socket closing settles only actions sent on that socket', () => {
  const { socket: oldSocket, ...h } = openConsole();
  const job = snapshot('socket-owned-action', 1, 'D1', 'queued', 1, {
    resumeRequired: true, cancellable: true,
  });
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  oldSocket.snapshot(job);
  h.selectAttention('D1');
  findElementByText(h.pushJobsTabActions, 'Resume').click();

  const replacementSocket = new window.WebSocket('ws://localhost/ws/admin');
  assert.equal(window.__stylyPushJobsV1Actions.sendDeviceAction('D2', job.job_id, 'cancel'), true);
  oldSocket.close();

  assert.ok(findElementByText(h.pushJobsTabActions, 'Resume'));
  assert.equal(window.__stylyPushJobsV1Actions.actionPending('cancel', job.job_id, 'D2'), true,
    'the new socket action stays pending after the old socket closes');
  assert.deepEqual(replacementSocket.sent.map(operatorRequestFields), [{
    type: 'CANCEL_PUSH_JOB', job_id: job.job_id, target_devices: ['D2'],
  }]);
});

test('an unscoped Cancel acknowledgement does not clear ambiguous same-job actions', () => {
  const { socket, ...h } = openConsole();
  const job = snapshot('same-job-actions', 1, 'D1', 'queued', 1, {
    cancellable: true,
  });
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  socket.snapshot(job);
  const api = window.__stylyPushJobsV1Actions;

  assert.equal(api.sendDeviceAction('D1', job.job_id, 'cancel'), true);
  assert.equal(api.sendDeviceAction('D2', job.job_id, 'cancel'), true);
  socket.emit({ type: 'PUSH_JOB_ACTION_SENT', job_id: job.job_id, action: 'CANCEL_PUSH_JOB' });
  assert.equal(api.actionPending('cancel', job.job_id, 'D1'), true);
  assert.equal(api.actionPending('cancel', job.job_id, 'D2'), true,
    'the acknowledgement has no target ID, so it cannot identify either request');
});

test('full snapshots merge pre-snapshot updates and remove absent jobs', () => {
  const harness = loadAdapter();
  const firstSocket = new window.WebSocket('ws://localhost/ws/admin');

  firstSocket.emit({
    type: 'PUSH_JOB_UPDATED',
    job: snapshot('job-current', 3, 'D1', 'succeeded', 10, {
      mode: 'sync',
      result: { added: 1, updated: 2, deleted: 3 },
    }),
  });
  assert.equal(harness.applied.length, 0, 'updates wait for the initial snapshot');

  firstSocket.emit({
    type: 'PUSH_JOBS_SNAPSHOT',
    jobs: [
      snapshot('job-current', 2, 'D1', 'downloading', 10),
      snapshot('job-removed', 1, 'D2', 'succeeded', 5, {
        result: { added: 4, updated: 0, deleted: 0 },
      }),
    ],
  });
  assert.equal(harness.bridgeState.get('D1').revision, 3);
  assert.equal(harness.bridgeState.get('D1').status, 'success');
  assert.equal(harness.bridgeState.get('D1').verb, 'Sync');
  assert.equal(harness.bridgeState.get('D1').note, '+1 ~2 -3');

  const removedRoot = harness.logContainer.children.find(
    (entry) => entry.dataset.pushJobId === 'job-removed',
  );
  assert.ok(removedRoot);

  const secondSocket = new window.WebSocket('ws://localhost/ws/admin');
  firstSocket.emit({
    type: 'PUSH_JOB_UPDATED',
    job: snapshot('job-current', 99, 'D1', 'succeeded', 10),
  });
  secondSocket.emit({
    type: 'PUSH_JOB_UPDATED',
    job: snapshot('job-current', 5, 'D1', 'failed', 10, {
      failure: { code: 'copy_failed', detail: 'copy failed' },
    }),
  });
  secondSocket.emit({
    type: 'PUSH_JOBS_SNAPSHOT',
    jobs: [snapshot('job-current', 4, 'D1', 'downloading', 10)],
  });

  assert.equal(harness.bridgeState.get('D1').revision, 5);
  assert.equal(harness.bridgeState.get('D1').status, 'fail');
  assert.equal(harness.bridgeState.get('D1').detail, 'copy failed');
  assert.equal(harness.bridgeState.has('D2'), false);
  assert.deepEqual(harness.cleared.at(-1), {
    deviceId: 'D2', jobId: 'job-removed',
  });
  assert.equal(removedRoot.isConnected, false, 'absent snapshot jobs leave the DOM');

  secondSocket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [] });
  assert.equal(harness.bridgeState.has('D1'), false);
  assert.deepEqual(harness.cleared.at(-1), {
    deviceId: 'D1', jobId: 'job-current',
  });
});

test('canonical assignment states map to the established task-cell states', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [] });
  const expected = new Map([
    ['queued', 'queued'],
    ['waiting_transfer', 'queued'],
    ['dispatching', 'queued'],
    ['downloading', 'transferring'],
    ['validating', 'validating'],
    ['applying', 'applying'],
    ['reconciling', 'reconciling'],
    ['succeeded', 'success'],
    ['failed', 'fail'],
    ['interrupted', 'fail'],
    ['unconfirmed', 'unconfirmed'],
  ]);
  let revision = 1;
  expected.forEach((display, state) => {
    socket.emit({
      type: 'PUSH_JOB_UPDATED',
      job: snapshot('mapping-job', revision++, 'D1', state, 1),
    });
    assert.equal(harness.bridgeState.get('D1').status, display, state);
  });
});

test('canonical validation renders separately from transfer and apply for Push and Sync', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [] });
  const device = { status: 'online' };
  const context = loadConsoleFunction('pushTaskCellHtml', {
    deviceById() { return device; },
    esc(value) { return String(value); },
  });
  let revision = 1;
  for (const mode of ['push', 'sync']) {
    for (const [state, label] of [
      ['downloading', 'Transferring…'],
      ['validating', 'Validating…'],
      ['applying', mode === 'push' ? 'Pushing…' : 'Syncing…'],
    ]) {
      socket.emit({
        type: 'PUSH_JOB_UPDATED',
        job: snapshot('phase-job', revision++, 'D1', state, 1, { mode }),
      });
      assert.match(
        context.pushTaskCellHtml('D1', harness.bridgeState.get('D1')),
        new RegExp(label), mode + ': ' + state,
      );
    }
  }
});

test('restart-paused jobs expose the existing operator resume command', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.emit({
    type: 'PUSH_JOBS_SNAPSHOT',
    jobs: [snapshot('paused-job', 4, 'D1', 'queued', 1, {
      dispatchEnabled: false,
      dispatchPausedReason: 'server_restart',
      resumeRequired: true,
    })],
  });

  assert.equal(harness.pushJobsAttention.style.display, '');
  assert.equal(harness.pushJobsAttention.children[0].textContent,
    'Devices need attention');
  assert.equal(harness.pushJobsAttention.children[1].children[0].textContent,
    '1 device(s) need review in the Needs attention tab.');
  const open = harness.pushJobsAttention.children[1].children[1];
  assert.equal(open.textContent, 'Open Needs attention');
  let opened = false;
  harness.tabAttention.addEventListener('click', () => { opened = true; });
  open.click();
  assert.equal(opened, true);
  harness.selectAttention('D1');
  const resume = findElementByText(harness.pushJobsTabActions, 'Resume');
  assert.ok(resume);
  resume.click();

  assert.deepEqual(socket.sent.map(operatorRequestFields), [{ type: 'PUSH_FILES', job_id: 'paused-job', target_devices: ['D1'] }]);
  assert.equal(resume.isConnected, false);
  const pendingResume = findElementByText(harness.pushJobsTabActions, 'Resume');
  assert.ok(pendingResume);
  assert.equal(pendingResume.disabled, true);
});

test('download retry exhaustion explains that Resume retains the partial', () => {
  const { socket, ...harness } = openConsole();
  socket.snapshot(snapshot('paused-job', 4, 'D1', 'queued', 1, {
    dispatchEnabled: false,
    dispatchPausedReason: 'download_interrupted',
    resumeRequired: true,
  }));

  assert.ok(findElementByText(harness.pushJobsTabActions,
    'Select devices to resume, retry, or cancel.'));
  harness.selectAttention('D1');
  assert.equal(findElementByText(harness.pushJobsTabActions, 'Resume').disabled, false);
});

test('resume attention hides once the canonical job is enabled', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.emit({
    type: 'PUSH_JOBS_SNAPSHOT',
    jobs: [snapshot('paused-job', 4, 'D1', 'queued', 1, {
      dispatchEnabled: false,
      dispatchPausedReason: 'server_restart',
      resumeRequired: true,
    })],
  });
  socket.emit({
    type: 'PUSH_JOB_UPDATED',
    job: snapshot('paused-job', 5, 'D1', 'queued', 1, {
      dispatchEnabled: true,
    }),
  });

  assert.equal(harness.pushJobsAttention.style.display, 'none');
  assert.equal(harness.pushJobsAttention.children.length, 0);
});

test('dispatch attention includes durable ready jobs without a pause reason', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.emit({
    type: 'PUSH_JOBS_SNAPSHOT',
    jobs: [snapshot('ready-job', 4, 'D1', 'queued', 1, {
      dispatchEnabled: false,
      jobState: 'ready',
      resumeRequired: true,
    })],
  });

  assert.equal(harness.pushJobsAttention.style.display, '');
  harness.selectAttention('D1');
  const resume = findElementByText(harness.pushJobsTabActions, 'Resume');
  assert.ok(resume);
  resume.click();
  assert.deepEqual(socket.sent.map(operatorRequestFields), [{ type: 'PUSH_FILES', job_id: 'ready-job', target_devices: ['D1'] }]);
  assert.equal(resume.isConnected, false);
  assert.ok(findElementByText(harness.pushJobsTabActions, 'Resume'));
});

test('dispatch action becomes retryable when WebSocket send throws', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.emit({
    type: 'PUSH_JOBS_SNAPSHOT',
    jobs: [snapshot('ready-job', 4, 'D1', 'queued', 1, {
      dispatchEnabled: false,
      jobState: 'ready',
      resumeRequired: true,
    })],
  });
  harness.selectAttention('D1');
  socket.sendError = new Error('socket closed');
  const dispatch = findElementByText(harness.pushJobsTabActions, 'Resume');

  dispatch.click();

  assert.equal(dispatch.disabled, false);
  assert.equal(dispatch.textContent, 'Resume');
});

test('dispatch attention ignores terminal and nondispatchable jobs', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.emit({
    type: 'PUSH_JOBS_SNAPSHOT',
    jobs: [
      snapshot('terminal-job', 4, 'D1', 'succeeded', 1, {
        dispatchEnabled: false,
        dispatchPausedReason: 'server_restart',
      }),
      snapshot('other-pause', 4, 'D2', 'queued', 2, {
        dispatchEnabled: false,
        dispatchPausedReason: 'operator_hold',
        jobState: 'uploading',
      }),
    ],
  });

  assert.equal(harness.pushJobsAttention.style.display, 'none');
  assert.equal(harness.pushJobsAttention.children.length, 0);
});

test('Push state unavailable warning preserves an independent install progress', () => {
  const device = {
    device_id: 'D1',
    status: 'online',
    push_state_status: 'unavailable',
  };
  const context = loadConsoleFunction('progressCellHtml', {
    deviceById(id) { return id === device.device_id ? device : null; },
    pushStateUnavailable(candidate) {
      return !!(candidate && candidate.status === 'online' &&
        candidate.push_state_status === 'unavailable');
    },
    taskCellHtml() { return '<span class="ins-installing">Installing…</span>'; },
    pushAssignmentFor() { return null; },
    esc(value) { return String(value); },
  });

  const html = context.progressCellHtml('D1');
  assert.match(html, /Installing/);
  assert.match(html, /Push state unavailable/);
  assert.match(html, /free storage, then Reboot/);
  // The device recovers by itself; the console offers no retry command.
  assert.doesNotMatch(html, /<button/);
});

test('Needs attention includes devices with unavailable Push state once', () => {
  const devices = [
    { device_id: 'job', status: 'online' },
    { device_id: 'state', status: 'online' },
    { device_id: 'both', status: 'online' },
    { device_id: 'healthy', status: 'online' },
  ];
  const context = loadConsoleFunction('pushAttentionDevices', {
    devices,
    getDeviceId(device) { return device.device_id; },
    pushAssignmentFor(id) { return { needsAttention: id === 'job' || id === 'both' }; },
    pushStateUnavailable(device) { return device.device_id === 'state' || device.device_id === 'both'; },
  });
  assert.deepEqual(Array.from(context.pushAttentionDevices(), device => device.device_id),
    ['job', 'state', 'both']);
});

test('target tabs preserve normal selection and clear it across Need attention', () => {
  const match = indexSource.match(/function switchTargetTab\(tab\) \{[\s\S]*?\n      \}/);
  assert.ok(match);
  for (const [from, to, cleared] of [
    ['groups', 'devices', false], ['devices', 'groups', false],
    ['groups', 'attention', true], ['devices', 'attention', true],
    ['attention', 'groups', true], ['attention', 'devices', true],
    ['attention', 'attention', false],
  ]) {
    const context = vm.createContext({
      targetTab: from,
      selectedIds: new Set(['device-1', 'device-2']),
      selectedConnectionIds: new Set(['connection-1']),
      selectedPushAttentionIds: new Set(['push-device-1']),
      activeGroup: 'Room A',
      powerConfirm: { checked: true },
      render() {},
    });
    vm.runInContext(match[0], context);
    context.switchTargetTab(to);
    const label = from + ' -> ' + to;
    assert.equal(context.targetTab, to, label);
    assert.deepEqual([...context.selectedIds], cleared ? [] : ['device-1', 'device-2'], label);
    assert.deepEqual([...context.selectedConnectionIds], cleared ? [] : ['connection-1'], label);
    assert.deepEqual([...context.selectedPushAttentionIds], cleared ? [] : ['push-device-1'], label);
    assert.equal(context.activeGroup, cleared ? null : 'Room A', label);
    assert.equal(context.powerConfirm.checked, !cleared, label);
  }
});

test('offline push rows retain unfinished jobs without claiming current execution', () => {
  const device = { status: 'online' };
  const state = { task: 'push', status: 'applying', verb: 'Push', owner: 'push-job-v1' };
  const context = loadConsoleFunction('pushTaskCellHtml', {
    deviceById() { return device; },
    esc(value) { return String(value); },
  });
  assert.match(context.pushTaskCellHtml('D1', state), /Pushing/);
  device.status = 'offline';
  for (const status of ['queued', 'transferring', 'validating', 'applying']) {
    state.status = status;
    const html = context.pushTaskCellHtml('D1', state);
    assert.match(html, /Job pending · offline/);
    assert.doesNotMatch(html, /spinner|Pushing|Transferring|Validating|failed/);
    assert.equal(state.status, status, 'rendering must not mutate canonical state');
  }
  device.status = 'online';
  state.status = 'applying';
  assert.match(context.pushTaskCellHtml('D1', state), /Pushing/);
  for (const connectivity of ['online', 'offline']) {
    device.status = connectivity;
    state.status = 'reconciling';
    const html = context.pushTaskCellHtml('D1', state);
    assert.match(html, connectivity === 'offline' ? /Waiting for device to reconnect/ : /Awaiting status confirmation/);
    assert.doesNotMatch(html, /Job pending|spinner|Pushing|failed/);
    state.status = 'unconfirmed';
    assert.match(context.pushTaskCellHtml('D1', state), connectivity === 'offline' ? /Waiting for device to reconnect/ : /Status unknown/);
  }
  device.status = 'offline';
  state.status = 'success';
  assert.match(context.pushTaskCellHtml('D1', state), /pushed/);
  state.status = 'fail';
  assert.match(context.pushTaskCellHtml('D1', state), /failed/);
});

test('device list disconnect clears only generic task state', () => {
  const match = indexSource.match(/case 'DEVICE_LIST': \{([\s\S]*?)\n            break;/);
  assert.ok(match);
  const context = vm.createContext({
    msg: { devices: [{ device_id: 'D1', status: 'offline' }] },
    devices: [], selectedIds: new Set(), powerConfirm: {},
    deviceSelectionAnchorId: null,
    knownIds() { return new Set(['D1']); },
    isOnline(device) { return device.status === 'online'; },
    getDeviceId(device) { return device.device_id; },
    deviceTaskState: {
      D1: { task: 'verify', kind: 'dir', status: 'match' },
      D2: { task: 'install', status: 'installing' },
    },
    syncActiveGroupSelection() {}, render() {}, window: {},
  });
  vm.runInContext(match[1], context);
  assert.equal(context.deviceTaskState.D1, undefined);
  assert.equal(context.deviceTaskState.D2, undefined);
});


test('retry-exhausted queued assignment stays visibly paused across reconnection', () => {
  const { socket, ...harness } = openConsole();
  const job = snapshot('paused-device', 1, 'D1', 'queued', 1);
  job.devices.D1.queue_reason = 'download_interrupted';
  job.devices.D1.resume_required = true;
  socket.snapshot(job);
  assert.equal(harness.bridgeState.get('D1').status, 'resume_required');
  for (const status of ['online', 'offline']) {
    const context = loadConsoleFunction('pushTaskCellHtml', {
      deviceById() { return { status }; },
      esc(value) { return String(value); },
    });
    const html = context.pushTaskCellHtml('D1', harness.bridgeState.get('D1'));
    assert.match(html, /Resume required/);
    assert.doesNotMatch(html, /Pushing|spinner|failed/);
    assert.equal(html.includes('Resume required · offline'), status === 'offline', status);
  }
});

test('per-device Resume remains available while other devices may dispatch', () => {
  const { socket, ...h } = openConsole();
  const job = snapshot('partial-job', 4, 'D1', 'queued', 1, {
    dispatchEnabled: true, resumeRequired: true, cancellable: true,
  });
  job.devices.D1.queue_reason = 'download_interrupted';
  socket.snapshot(job);
  assert.equal(h.bridgeState.get('D1').status, 'resume_required');
  assert.equal(h.pushJobsAttention.style.display, '');
  h.selectAttention('D1');
  findElementByText(h.pushJobsTabActions, 'Resume').click();
  assert.deepEqual(socket.sent.map(operatorRequestFields), [{ type: 'PUSH_FILES', job_id: 'partial-job', target_devices: ['D1'] }]);
});

test('device controls keep actions out of logs and remove Retry after cancellation', () => {
  const { socket, ...h } = openConsole();
  const running = snapshot('active-job', 4, 'D1', 'downloading', 1);
  const failed = snapshot('failed-job', 5, 'D2', 'failed', 2, { retryable: true });
  socket.snapshot(running, failed);
  h.selectAttention('D2');
  const retry = findElementByText(h.pushJobsTabActions, 'Retry');
  for (const entry of h.logContainer.children) {
    assert.equal(entry.children[1].children.some(e => e.tagName === 'BUTTON' || e.tagName === 'button'), false);
  }
  assert.equal(findElementByText(h.pushJobsTabActions, 'Cancel job'), undefined);
  retry.click();
  assert.equal(socket.sent[0].type, 'RETRY_PUSH_DEVICES');
  assert.equal(socket.sent[0].job_id, 'failed-job');
  assert.ok(socket.sent[0].client_request_id);
  const cancelled = snapshot('failed-job', 6, 'D2', 'failed', 2);
  cancelled.devices.D2.failure = { code: 'cancelled' };
  socket.update(cancelled);
  assert.equal(findElementByText(h.pushJobsTabActions, 'Retry'), undefined);
  assert.equal(h.bridgeState.get('D2').status, 'cancelled');
});

test('retry request IDs remain stable per target set and revision and prune on replacement', () => {
  const { socket, ...h } = openConsole();
  const job = revision => snapshot('retry-request-lifecycle', revision, 'D1', 'failed', 1, { retryable: true });
  const api = window.__stylyPushJobsV1Actions;
  socket.snapshot(job(1));
  assert.equal(api.sendDeviceAction('D1', job(1).job_id, 'retry'), true);
  const first = socket.sent.at(-1);
  socket.emit({ type: 'ERROR', message: 'temporarily rejected' });
  socket.emit({ type: 'PUSH_JOB_ACTION_SENT', action: 'RETRY_PUSH_DEVICES', job_id: 'unscoped-child' });
  assert.equal(api.actionPending('retry', first.job_id, 'D1'), true, 'uncorrelated responses cannot settle Retry');
  socket.emit({ ...first, type: 'ERROR', action: first.type, message: 'temporarily rejected' });
  assert.equal(api.sendDeviceAction('D1', first.job_id, 'retry'), true);
  assert.deepEqual(socket.sent.at(-1), first);
  socket.emit(operatorAck(first));
  socket.update(job(2));
  api.sendDeviceAction('D1', first.job_id, 'retry');
  const second = socket.sent.at(-1);
  assert.notEqual(second.client_request_id, first.client_request_id);
  socket.emit(operatorAck(second));
  const reconnect = new window.WebSocket('ws://localhost/ws/admin');
  reconnect.update(job(2));
  reconnect.snapshot(job(1));
  api.sendDeviceAction('D1', first.job_id, 'retry');
  assert.equal(reconnect.sent.at(-1).client_request_id, second.client_request_id);
  reconnect.emit(operatorAck(reconnect.sent.at(-1)));
  reconnect.snapshot();
  reconnect.snapshot(job(2));
  api.sendDeviceAction('D1', first.job_id, 'retry');
  assert.notEqual(reconnect.sent.at(-1).client_request_id, second.client_request_id);
});

test('inline actions send only the selected device while Resume targets eligible online devices', () => {
  const { socket, ...harness } = openConsole();
  const job = snapshot('per-device-job', 1, 'D1', 'queued', 1, {
    resumeRequired: true, cancellable: true,
  });
  job.devices.D1.queue_reason = 'download_interrupted';
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  socket.snapshot(job);
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.sendDeviceAction('D1', job.job_id, 'resume'), true);
  assert.deepEqual(operatorRequestFields(socket.sent.at(-1)), {
    type: 'PUSH_FILES', job_id: job.job_id, target_devices: ['D1'],
  });
  socket.emit(operatorAck(socket.sent.at(-1)));
  assert.equal(api.sendDeviceAction('D2', job.job_id, 'cancel'), true);
  assert.deepEqual(operatorRequestFields(socket.sent.at(-1)), {
    type: 'CANCEL_PUSH_JOB', job_id: job.job_id, target_devices: ['D2'],
  });
  socket.emit(operatorAck(socket.sent.at(-1)));
  assert.equal(api.sendDeviceAction('D2', 'stale-job', 'cancel'), false);
  harness.selectAttention('D1', 'D2');
  const all = findElementByText(harness.pushJobsTabActions, 'Resume');
  assert.equal(all.textContent, 'Resume');
  all.click();
  assert.deepEqual(operatorRequestFields(socket.sent.at(-1)), { type: 'PUSH_FILES', job_id: job.job_id, target_devices: ['D1', 'D2'] });
  socket.emit(operatorAck(socket.sent.at(-1)));
  harness.selectAttention('D1', 'D2');
  findElementByText(harness.pushJobsTabActions, 'Cancel').click();
  assert.deepEqual(operatorRequestFields(socket.sent.at(-1)), {
    type: 'CANCEL_PUSH_JOB', job_id: job.job_id, target_devices: ['D1', 'D2'],
  });
});

test('never-dispatched paused device follows server Cancel permission without resume support', () => {
  const { socket, ...h } = openConsole({ isDeviceOnline: () => false });
  const job = snapshot('never-dispatched', 1, 'D1', 'queued', 1, {
    dispatchRevision: null, resumeSupported: false, resumeRequired: true,
  });
  job.devices.D1.queue_reason = 'dispatch_paused';
  socket.snapshot(job);
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.assignmentFor('D1').canCancel, false);
  assert.equal(api.sendDeviceAction('D1', job.job_id, 'cancel'), false);
  assert.equal(findElementByText(h.pushJobsTabActions, 'Cancel').disabled, true);
  assert.equal(socket.sent.length, 0);

  // The server can prove no command was sent, even while the target is offline.
  job.revision++;
  job.devices.D1.cancellable = true;
  socket.update(job);
  assert.equal(api.assignmentFor('D1').canResume, false);
  assert.equal(api.assignmentFor('D1').canCancel, true);
  assert.equal(api.sendDeviceAction('D1', job.job_id, 'cancel'), true);
  assert.deepEqual(operatorRequestFields(socket.sent.at(-1)), {
    type: 'CANCEL_PUSH_JOB', job_id: job.job_id, target_devices: ['D1'],
  });
  socket.emit(operatorAck(socket.sent.at(-1)));
  findElementByText(h.pushJobsTabActions, 'Cancel').click();
  assert.deepEqual(operatorRequestFields(socket.sent.at(-1)), {
    type: 'CANCEL_PUSH_JOB', job_id: job.job_id, target_devices: ['D1'],
  });
});

test('non-resumable dispatch never exposes Cancel', () => {
  const { socket, ...h } = openConsole();
  const offline = snapshot('non-resumable', 1, 'D1', 'reconciling', 1, { resumeSupported: false });
  socket.snapshot(offline);
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.assignmentFor('D1').canCancel, false);
  const fenced = snapshot('non-resumable', 2, 'D1', 'unconfirmed', 1, { resumeSupported: false });
  fenced.devices.D1.device_fence = { blocking_job_id: 'non-resumable', blocking_attempt: 1 };
  socket.update(fenced);
  assert.equal(api.assignmentFor('D1').canCancel, false);
  assert.equal(api.sendDeviceAction('D1', fenced.job_id, 'cancel'), false);
  assert.equal(findElementByText(h.pushJobsTabActions, 'Cancel').disabled, true);
  assert.equal(socket.sent.length, 0);
});

test('individual Cancel requires confirmation', () => {
  let prompt = '';
  const { socket, ...h } = openConsole({ confirm: message => { prompt = message; return false; } });
  const job = snapshot('confirm-cancel', 1, 'D1', 'queued', 1, { cancellable: true });
  job.devices.D1.queue_reason = 'download_interrupted';
  socket.snapshot(job);
  assert.equal(window.__stylyPushJobsV1Actions.sendDeviceAction('D1', job.job_id, 'cancel'), false);
  assert.match(prompt, /1 selected eligible device/);
  assert.equal(socket.sent.length, 0);
  assert.ok(h.pushJobsTabActions);
});

test('attention derives latest canonical assignment and clears after resume, retry, or confirmed stop', () => {
  loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const job = snapshot('attention-job', 1, 'D1', 'queued', 1, { resumeRequired: true });
  job.devices.D1.queue_reason = 'dispatch_paused';
  socket.snapshot(job);
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.assignmentFor('D1').canResume, true);
  assert.equal(api.assignmentFor('D1').needsAttention, true);
  const running = snapshot(job.job_id, 2, 'D1', 'downloading', 1);
  socket.update(running);
  assert.equal(api.assignmentFor('D1').needsAttention, false);
  assert.equal(api.assignmentFor('D1').canResume, false);
  assert.equal(api.assignmentFor('D1').canCancel, false);
  const failed = snapshot(job.job_id, 3, 'D1', 'failed', 1, { retryable: true });
  socket.update(failed);
  assert.equal(api.assignmentFor('D1').needsAttention, true);
  socket.update({ ...failed, revision: 4, devices: {
    D1: { ...failed.devices.D1, retryable: false, retry_job_id: 'retry-job' },
  } });
  socket.update(snapshot('retry-job', 1, 'D1', 'queued', 2));
  assert.equal(api.assignmentFor('D1').needsAttention, false, 'new retry suppresses old failure');
  const stopped = snapshot('retry-job', 2, 'D1', 'failed', 2);
  stopped.devices.D1.failure = { code: 'cancelled' };
  socket.update(stopped);
  assert.equal(api.assignmentFor('D1').needsAttention, false);
  assert.equal(api.assignmentFor('D1').status, 'cancelled');
});

test('Needs attention keeps canonical jobs separate from provisional power selection', () => {
  const canonical = [{ device_id: 'canonical-1', label: 'Interrupted headset', status: 'online', ip: '192.0.2.1' }];
  const provisional = [{ connection_id: 'socket-1', model: 'PICO', identity_status: 'unavailable' }];
  const context = loadConsoleFunction('renderAttentionHtml', {
    pushAttentionDevices() { return canonical; },
    provisionalConnections: provisional, selectedConnectionIds: new Set(['socket-1']),
    attentionSelectAllState() { return 'on'; }, attentionSelectableCount() { return 1; },
    attentionSelectAllGlyph() { return '✓'; }, attentionPowerSupported() { return true; },
    getDeviceId(device) { return device.device_id; },
    labelFor(id) { return canonical.find(d => d.device_id === id)?.label || id; },
    shortConnectionId(id) { return id.slice(0, 8); },
    progressCellHtml() { return '<button data-act="pushDeviceAction">Resume</button>'; },
    esc(value) { return String(value); },
  });
  const html = context.renderAttentionHtml();
  assert.match(html, /Interrupted headset/);
  assert.match(html, /data-task-id="canonical-1"/);
  assert.match(html, /data-act="toggleAttention" data-id="socket-1"/);
  assert.doesNotMatch(html, /data-act="toggleAttention" data-id="canonical-1"/);
  canonical.length = 0;
  assert.doesNotMatch(context.renderAttentionHtml(), /Interrupted headset/);
  assert.match(context.renderAttentionHtml(), /PICO/);
});

test('device progress exposes escaped inline action identities', () => {
  const context = loadConsoleFunction('progressCellHtml', {
    taskCellHtml() { return 'Resume required'; },
    pushTaskCellHtml() { return 'Resume required'; },
    pushAssignmentFor() { return { job_id: 'job"x', canResume: true, canCancel: true }; },
    pushStateUnavailable() { return false; }, deviceById() { return {}; },
    esc(value) { return String(value).replace(/"/g, '&quot;'); },
  });
  const html = context.progressCellHtml('device"x');
  assert.match(html, /class="push-job-actions"/);
  assert.match(html, /data-action="resume"/);
  assert.match(html, /data-action="cancel"/);
  assert.match(html, /data-id="device&quot;x"/);
  assert.match(html, /data-job-id="job&quot;x"/);
});

test('Cancel action keeps a danger treatment', () => {
  const cancelRule = indexSource.match(/\.push-job-action\[data-action="cancel"\]\s*\{([^}]*)\}/);
  assert.ok(cancelRule, 'Cancel has a dedicated style rule');
  assert.match(cancelRule[1], /(?:^|;)\s*color:\s*var\(--danger\)\s*(?:;|$)/);
});

test('Push job action visibility follows the console tab renderer', () => {
  const { socket, ...harness } = openConsole();
  socket.snapshot(snapshot('tab-visibility', 1, 'D1', 'failed', 1, { retryable: true }));
  assert.equal(harness.pushJobsTabActions.style.display, '');
  harness.setTargetTab('devices');
  assert.equal(harness.pushJobsTabActions.style.display, 'none');
  harness.setTargetTab('attention');
  assert.equal(harness.pushJobsTabActions.style.display, '');
});

test('PUSH_STATE_RESET is received and logged with the device and recovery detail', () => {
  const logs = [];
  const context = loadConsoleFunction('handleMessage', {
    addLog(message, level) { logs.push({ message, level }); },
    labelFor(id) { return id === 'D1' ? 'Headset 1' : id; },
  });
  context.handleMessage({ type: 'PUSH_STATE_RESET', device_id: 'D1', detail: 'invalid JSON' });
  assert.deepEqual(logs, [{
    message: 'Push state on Headset 1 was unreadable and has been reset - invalid JSON; unfinished Push/Sync status on it may show as interrupted',
    level: 'warn',
  }]);
});

test('Resume remains for nonselected paused devices and hides once all are finished', () => {
  const { socket, ...harness } = openConsole();
  const job = snapshot('partial-resume-job', 1, 'D1', 'downloading', 1);
  job.devices.D2 = {
    ...job.devices.D1, state: 'reconciling', queue_reason: 'dispatch_paused',
    resume_required: true, enqueue_seq: 2,
  };
  socket.snapshot(job);
  assert.equal(harness.pushJobsAttention.style.display, '');
  assert.equal(findElementByText(harness.pushJobsTabActions, 'Resume').textContent, 'Resume');
  socket.emit({ type: 'PUSH_JOB_UPDATED', job: {
    ...job, revision: 2, dispatch_enabled: false,
    devices: Object.fromEntries(Object.entries(job.devices).map(([id, d]) => [id, {
      ...d, state: 'failed', failure: { code: 'cancelled' },
      resume_required: false, retryable: false,
    }])),
  } });
  assert.equal(harness.pushJobsAttention.style.display, 'none');
});

test('only a timed-out queued assignment exposes or sends Cancel', () => {
  loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.snapshot();
  let revision = 0;
  for (const state of ['queued', 'waiting_transfer', 'dispatching', 'downloading', 'validating', 'applying', 'reconciling', 'unconfirmed']) {
    const job = snapshot('cancel-scope', ++revision, 'D1', state, 1);
    socket.emit({ type: 'PUSH_JOB_UPDATED', job });
    assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').canCancel, false, state);
    assert.equal(window.__stylyPushJobsV1Actions.sendDeviceAction('D1', job.job_id, 'cancel'), false, state);
  }
  const paused = snapshot('cancel-scope', ++revision, 'D1', 'queued', 1, { cancellable: true });
  paused.devices.D1.queue_reason = 'download_interrupted';
  socket.update(paused);
  assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').canCancel, true);
  assert.equal(window.__stylyPushJobsV1Actions.sendDeviceAction('D1', paused.job_id, 'cancel'), true);
  assert.equal(socket.sent.length, 1);
});


test('attention reuses canonical label resolution and provisional IDs only when no model exists', () => {
  const resolve = indexSource.match(/function labelFor\(id\) \{[^\n]+/)[0];
  const render = indexSource.match(/function renderAttentionHtml\(\) \{[\s\S]*?\n      \}/)[0];
  const named = { device_id: 'known-device', label: '<Named headset>' };
  const unnamed = { device_id: 'unlabelled-device' };
  const devices = [named, unnamed];
  const context = vm.createContext({
    deviceById(id) { return devices.find(d => d.device_id === id); },
    pushAttentionDevices() { return devices; }, getDeviceId(d) { return d.device_id; },
    provisionalConnections: [{ connection_id: 'unresolved-connection', model: '' }],
    selectedPushAttentionIds: new Set(), pushAttentionSelectionAnchorId: null,
    selectedConnectionIds: new Set(), attentionSelectAllState() { return ''; },
    attentionSelectableCount() { return 0; }, attentionSelectAllGlyph() { return ''; },
    attentionPowerSupported() { return false; }, shortConnectionId(id) { return id.slice(0, 8); },
    progressCellHtml() { return ''; },
    esc(value) { return String(value).replace(/</g, '&lt;').replace(/>/g, '&gt;'); },
  });
  vm.runInContext(resolve + '\n' + consoleFunctionSource('deviceIdHtml') + '\n' + render, context);
  const html = context.renderAttentionHtml();
  assert.match(html, /&lt;Named headset&gt;/);
  assert.doesNotMatch(html, /<Named headset>/);
  assert.match(html, /unlabelled-device/);
  assert.match(html, /unresolv/);
  named.label = 'Renamed headset';
  assert.match(context.renderAttentionHtml(), /Renamed headset/);
});

test('offline timeout keeps attention and Cancel but excludes Resume and refreshes on connection change', () => {
  const online = new Set(['D2']);
  const { socket, ...h } = openConsole({ isDeviceOnline: id => online.has(id) });
  const job = snapshot('offline-resume', 1, 'D1', 'queued', 1, {
    resumeRequired: true, cancellable: true,
  });
  job.devices.D1.queue_reason = 'download_interrupted';
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  socket.snapshot(job);
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.assignmentFor('D1').needsAttention, true);
  assert.equal(api.assignmentFor('D1').canResume, false);
  assert.equal(api.assignmentFor('D1').canCancel, true);
  assert.equal(api.sendDeviceAction('D1', job.job_id, 'resume'), false);
  h.selectAttention('D1', 'D2');
  findElementByText(h.pushJobsTabActions, 'Resume').click();
  assert.deepEqual(socket.sent.at(-1).target_devices, ['D2']);
  socket.emit(operatorAck(socket.sent.at(-1)));
  h.selectAttention('D1', 'D2');
  online.clear();
  api.refreshConnectivity();
  assert.equal(findElementByText(h.pushJobsTabActions, 'Resume').disabled, true);
  assert.equal(h.bridgeState.get('D2').canResume, false);
  online.add('D1');
  api.refreshConnectivity();
  assert.equal(findElementByText(h.pushJobsTabActions, 'Resume').disabled, false);
  assert.equal(h.bridgeState.get('D1').canResume, true);
});


test('fenced jobs remain visible without a Reconcile control', () => {
  const { socket, ...h } = openConsole();
  const job = snapshot('fenced-job', 2, 'D1', 'unconfirmed', 1);
  job.devices.D1.device_fence = { reason: 'result_unconfirmed' };
  socket.snapshot(job);
  assert.equal(findElementByText(h.pushJobsTabActions, 'Reconcile'), undefined);
  assert.deepEqual(socket.sent, []);
  for (const entry of h.logContainer.children) {
    assert.equal(entry.children[1].children.some(e => e.tagName === 'button'), false);
  }
});

test('offline reconciling cancel becomes pending without human attention or repeat controls', () => {
  const { socket, ...h } = openConsole({ isDeviceOnline: () => false });
  const job = snapshot('offline-cancel', 1, 'D1', 'reconciling', 1, { cancellable: true });
  socket.snapshot(job);
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.assignmentFor('D1').canCancel, true);
  assert.equal(api.assignmentFor('D1').canResume, false);
  assert.equal(api.sendDeviceAction('D1', job.job_id, 'cancel'), true);
  job.devices.D1.cancel_requested = true;
  job.devices.D1.cancellable = false;
  socket.update({ ...job, revision: 2 });
  const view = api.assignmentFor('D1');
  assert.equal(view.status, 'cancel_pending');
  assert.equal(view.needsAttention, false);
  assert.equal(view.canCancel, false);
  assert.equal(view.canResume, false);
  assert.equal(h.pushJobsAttention.style.display, 'none');
});

test('retried targets leave old attention and retry controls while a new failure stays actionable', () => {
  const { socket, ...h } = openConsole();
  const old = snapshot('original-failure', 1, 'D1', 'failed', 1, { retryable: true });
  old.devices.D1.retry_job_id = 'new-retry';
  old.devices.D1.retryable = false;
  socket.snapshot(old);
  assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').needsAttention, false);
  assert.equal(h.pushJobsAttention.style.display, 'none');
  socket.update(snapshot('new-retry', 1, 'D1', 'failed', 2, { retryable: true }));
  assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').needsAttention, true);
  assert.equal(h.pushJobsAttention.style.display, '');
  assert.equal(h.pushJobsAttention.children.length, 2, 'only new failed job needs attention');
});

test('Retry preserves device attention until the old fence is cleared', () => {
  const { socket, ...h } = openConsole();
  const old = snapshot('fenced-original', 1, 'D1', 'unconfirmed', 1);
  old.devices.D1.retry_job_id = 'fenced-retry';
  old.devices.D1.device_fence = {
    blocking_job_id: old.job_id, blocking_attempt: 1, reason: 'result_unconfirmed',
  };
  const retry = snapshot('fenced-retry', 1, 'D1', 'queued', 2);
  retry.dispatch_enabled = true;
  socket.snapshot(old, retry);
  const api = window.__stylyPushJobsV1Actions;
  const view = api.assignmentFor('D1');
  assert.equal(view.job_id, old.job_id);
  assert.equal(view.status, 'unconfirmed');
  assert.equal(view.needsAttention, true);
  assert.equal(h.bridgeState.get('D1').needsAttention, true);
  assert.equal(view.canResume, false);
  assert.equal(view.canCancel, false);
  assert.equal(h.pushJobsAttention.style.display, '');
  assert.equal(h.pushJobsTabActions.style.display, '');
  assert.equal(findElementByText(h.pushJobsTabActions, 'Retry').disabled, true);
  assert.equal(findElementByText(h.pushJobsTabActions, 'Reconcile'), undefined);
  assert.deepEqual(socket.sent, []);

  socket.emit({ type: 'PUSH_JOB_UPDATED', job: {
    ...old, revision: 2, devices: { D1: { ...old.devices.D1, device_fence: null } },
  } });
  assert.equal(api.assignmentFor('D1').job_id, retry.job_id);
  assert.equal(api.assignmentFor('D1').needsAttention, false);
  assert.equal(h.pushJobsAttention.style.display, 'none');
  assert.equal(h.pushJobsTabActions.style.display, 'none');
  assert.equal(findElementByText(h.pushJobsTabActions, 'Reconcile'), undefined);
});

test('Retry and device review ignore targets the server does not mark retryable', () => {
  const { socket, ...h } = openConsole();
  const superseded = snapshot('superseded-failure', 1, 'D1', 'failed', 1);
  superseded.devices.D1.retryable = false;
  const later = snapshot('later-success', 1, 'D1', 'succeeded', 2);
  socket.snapshot(superseded, later);
  assert.equal(findElementByText(h.pushJobsTabActions, 'Retry'), undefined);
  assert.equal(h.pushJobsAttention.style.display, 'none');
  assert.equal(h.pushJobsTabActions.style.display, 'none');
  assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').needsAttention, false);
});

test('Retry is disabled while every retryable target is offline', () => {
  const online = new Set();
  const { socket, ...h } = openConsole({ isDeviceOnline: id => online.has(id) });
  const job = snapshot('offline-failure', 1, 'D1', 'failed', 1, { retryable: true });
  socket.snapshot(job);
  h.selectAttention('D1');
  assert.equal(h.pushJobsAttention.children[1].children[0].textContent,
    '1 device(s) need review in the Needs attention tab.');
  assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').needsAttention, true);
  let retry = findElementByText(h.pushJobsTabActions, 'Retry');
  assert.equal(retry.disabled, true);
  assert.equal(retry.title, '0 eligible selected device(s)');
  online.add('D1');
  window.__stylyPushJobsV1Actions.refreshConnectivity();
  retry = findElementByText(h.pushJobsTabActions, 'Retry');
  assert.equal(retry.disabled, false);
  retry.click();
  assert.equal(socket.sent.at(-1).type, 'RETRY_PUSH_DEVICES');
});

test('dispatch-disabled ready job shows Resume required instead of Waiting', () => {
  const { socket, ...h } = openConsole();
  const job = snapshot('awaiting-dispatch', 1, 'D1', 'queued', 1, {
    dispatchEnabled: false, jobState: 'ready', resumeRequired: true,
  });
  job.devices.D1.queue_reason = 'awaiting_dispatch';
  socket.snapshot(job);
  assert.equal(h.bridgeState.get('D1').status, 'resume_required');
  assert.equal(h.bridgeState.get('D1').canResume, true);
});

test('Cancel confirmation states the selected device count', () => {
  let prompt = '';
  const { socket, ...h } = openConsole({ confirm: message => { prompt = message; return false; } });
  const first = snapshot('cancel-scope-a', 1, 'D1', 'queued', 1, {
    cancellable: true, resumeRequired: true,
  });
  first.devices.D1.queue_reason = 'download_interrupted';
  const second = snapshot('cancel-scope-b', 1, 'D2', 'queued', 2, {
    cancellable: true, resumeRequired: true,
  });
  second.devices.D2.queue_reason = 'download_interrupted';
  second.devices.D3 = { ...second.devices.D2, enqueue_seq: 3 };
  socket.snapshot(first, second);
  h.selectAttention('D1', 'D2', 'D3');
  findElementByText(h.pushJobsTabActions, 'Cancel').click();
  assert.equal(prompt, 'Cancel Push / Sync for 3 selected eligible device(s)?');
  assert.equal(socket.sent.length, 0);
});

test('attention selection limits Resume to selected eligible devices across jobs', () => {
  const h = openConsole();
  const first = snapshot('selected-first', 1, 'D1', 'queued', 1, { resumeRequired: true });
  first.devices.D2 = { ...first.devices.D1, enqueue_seq: 2 };
  const second = snapshot('selected-second', 1, 'D3', 'queued', 3, { resumeRequired: true });
  const excluded = snapshot('unselected-job', 1, 'D4', 'queued', 4, { resumeRequired: true });
  h.socket.snapshot(first, second, excluded);
  assert.ok(findElementByText(h.pushJobsTabActions, 'Resume'));
  h.selectAttention('D1', 'D3');
  assert.equal(findElementByText(h.pushJobsTabActions, 'Resume').disabled, false);
  findElementByText(h.pushJobsTabActions, 'Resume').click();
  assert.deepEqual(h.socket.sent.map(operatorRequestFields), [
    { type: 'PUSH_FILES', job_id: first.job_id, target_devices: ['D1'] },
    { type: 'PUSH_FILES', job_id: second.job_id, target_devices: ['D3'] },
  ]);
  assert.equal(findElementByText(h.pushJobsTabActions, 'Resume').disabled, true);
});

test('offline selection disables Resume but Cancel confirms and targets only the selection', () => {
  const prompts = [];
  let approved = false;
  const h = openConsole({
    isDeviceOnline: id => id !== 'D2',
    confirm: message => { prompts.push(message); return approved; },
  });
  const job = snapshot('selected-cancel', 1, 'D1', 'queued', 1, { resumeRequired: true, cancellable: true });
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  h.socket.snapshot(job);
  h.selectAttention('D2');
  assert.equal(findElementByText(h.pushJobsTabActions, 'Resume').disabled, true);
  const cancel = findElementByText(h.pushJobsTabActions, 'Cancel');
  assert.equal(cancel.disabled, false);
  cancel.click();
  assert.equal(h.socket.sent.length, 0, 'declining confirmation sends nothing');
  assert.match(prompts[0], /1 selected eligible device\(s\)/);
  approved = true;
  cancel.click();
  assert.deepEqual(h.socket.sent.map(operatorRequestFields), [{ type: 'CANCEL_PUSH_JOB', job_id: job.job_id, target_devices: ['D2'] }]);
});

test('ineligible selection never falls back to all, including after replacement snapshots', () => {
  const h = openConsole();
  const job = snapshot('still-paused', 1, 'D1', 'queued', 1, { resumeRequired: true, cancellable: true });
  h.socket.snapshot(job);
  h.selectAttention('unavailable-device');
  for (const label of ['Resume', 'Cancel']) {
    assert.equal(findElementByText(h.pushJobsTabActions, label).disabled, true);
    findElementByText(h.pushJobsTabActions, label).click();
  }
  h.socket.snapshot({ ...job, revision: 2 });
  assert.equal(findElementByText(h.pushJobsTabActions, 'Resume').disabled, true);
  assert.equal(findElementByText(h.pushJobsTabActions, 'Cancel').disabled, true);
  assert.equal(h.socket.sent.length, 0);
  h.selectAttention('D1');
  findElementByText(h.pushJobsTabActions, 'Resume').click();
  assert.deepEqual(h.socket.sent.at(-1).target_devices, ['D1']);
});

test('a resumed selection stays scoped when its row leaves attention and another device remains', () => {
  const h = openConsole();
  const job = snapshot('live-selection', 1, 'D1', 'queued', 1, { resumeRequired: true });
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  h.socket.snapshot(job);
  h.selectAttention('D1');
  findElementByText(h.pushJobsTabActions, 'Resume').click();
  h.socket.emit(operatorAck(h.socket.sent.at(-1)));
  h.socket.update({ ...job, revision: 2, devices: {
    ...job.devices, D1: { ...job.devices.D1, state: 'downloading', resume_required: false },
  } });
  assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').needsAttention, false);
  const resume = findElementByText(h.pushJobsTabActions, 'Resume');
  assert.equal(resume.disabled, true);
  resume.click();
  assert.equal(h.socket.sent.length, 1, 'the unselected paused device must not resume');
  h.selectAttention('D2');
  findElementByText(h.pushJobsTabActions, 'Resume').click();
  assert.deepEqual(h.socket.sent.at(-1).target_devices, ['D2']);
});

test('one selected-device toolbar scopes Retry without any job card', () => {
  const h = openConsole();
  const job = snapshot('retry-scope', 1, 'D1', 'failed', 1, { retryable: true });
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  h.socket.snapshot(job);
  assert.equal(h.pushJobsTabActions.children.length, 1);
  for (const action of ['Resume', 'Retry', 'Cancel']) {
    assert.equal(findElementByText(h.pushJobsTabActions, action).disabled, true);
    findElementByText(h.pushJobsTabActions, action).click();
  }
  assert.equal(h.socket.sent.length, 0, 'no selection never falls back to every device');
  h.selectAttention('D1');
  assert.ok(findElementByText(h.pushJobsTabActions, 'Selected devices · 1'));
  assert.equal(findElementByText(h.pushJobsTabActions, 'Retry by job'), undefined);
  findElementByText(h.pushJobsTabActions, 'Retry').click();
  assert.equal(h.socket.sent.at(-1).type, 'RETRY_PUSH_DEVICES');
  assert.equal(h.socket.sent.at(-1).job_id, job.job_id);
  assert.deepEqual(h.socket.sent.at(-1).target_devices, ['D1']);
  assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D2').canRetry, true);
});

test('Push attention checkboxes support partial/select-all state without selecting power controls', () => {
  const rows = ['D1', 'D2'].map(id => ({ dataset: { id }, checked: false }));
  const header = {};
  const listed = rows.map(row => ({ device_id: row.dataset.id }));
  const selected = new Set();
  const power = new Set(['connection-1']);
  let refreshes = 0;
  const context = vm.createContext({
    selectedPushAttentionIds: selected, selectedConnectionIds: power,
    selectedIds: new Set(['normal-device']), activeGroup: null, deviceSelectionAnchorId: null,
    pushAttentionSelectionAnchorId: null, connectionSelectionAnchorId: null,
    powerConfirm: { checked: true },
    pushAttentionDevices() { return listed; }, getDeviceId(device) { return device.device_id; },
    targetsBody: { querySelectorAll() { return rows; }, querySelector() { return header; } },
    renderActions() { refreshes++; }, render() {},
  });
  for (const name of ['toggleRangeSelection', 'syncPushAttentionCheckboxes', 'togglePushAttention', 'selectAllPushAttention', 'clearSelection']) {
    vm.runInContext(consoleFunctionSource(name), context);
  }
  context.togglePushAttention('D1', false);
  assert.equal(rows[0].checked, true);
  assert.equal(header.indeterminate, true);
  assert.equal(header.checked, false);
  assert.deepEqual([...power], ['connection-1']);
  context.selectAllPushAttention(true);
  assert.deepEqual([...selected], ['D1', 'D2']);
  assert.equal(header.checked, true);
  assert.equal(header.indeterminate, false);
  context.selectAllPushAttention(false);
  assert.equal(selected.size, 0);
  assert.deepEqual([...power], ['connection-1']);
  context.togglePushAttention('unknown', true);
  assert.equal(selected.size, 0);
  context.togglePushAttention('D1', false);
  listed.shift();
  context.syncPushAttentionCheckboxes();
  assert.equal(selected.has('D1'), true, 'live updates cannot silently broaden scope to all');
  assert.equal(context.pushAttentionSelectionAnchorId, null, 'removed rows clear only the range anchor');
  context.clearSelection();
  assert.equal(selected.size, 0);
  assert.equal(power.size, 0);
  assert.equal(context.powerConfirm.checked, false);
  assert.equal(refreshes, 4);
});

test('Push attention Shift selection uses current visible order and keeps selection scopes independent', () => {
  const rows = ['D1', 'D2', 'D3', 'D4'].map(id => ({ dataset: { id }, checked: false }));
  const header = {};
  let listed = rows.map(row => ({ device_id: row.dataset.id }));
  const selectedPush = new Set();
  const selectedDevices = new Set(['device-selection']);
  const selectedPower = new Set(['connection-selection']);
  const context = vm.createContext({
    selectedPushAttentionIds: selectedPush,
    selectedIds: selectedDevices,
    selectedConnectionIds: selectedPower,
    deviceSelectionAnchorId: 'device-anchor', connectionSelectionAnchorId: 'power-anchor',
    pushAttentionSelectionAnchorId: null,
    pushAttentionDevices() { return listed; }, getDeviceId(device) { return device.device_id; },
    targetsBody: { querySelectorAll() { return rows; }, querySelector() { return header; } },
    renderActions() {}, render() {},
  });
  for (const name of ['toggleRangeSelection', 'syncPushAttentionCheckboxes', 'togglePushAttention', 'selectAllPushAttention']) {
    vm.runInContext(consoleFunctionSource(name), context);
  }

  context.togglePushAttention('D2', false);
  context.togglePushAttention('D4', true);
  assert.deepEqual([...selectedPush].sort(), ['D2', 'D3', 'D4']);
  context.togglePushAttention('D3', true); // The clicked item was selected, so clear D3..D4.
  assert.deepEqual([...selectedPush].sort(), ['D2']);
  context.togglePushAttention('D1', true); // The new anchor is D3; D2 remains selected outside the range.
  assert.deepEqual([...selectedPush].sort(), ['D1', 'D2', 'D3']);
  assert.equal(context.pushAttentionSelectionAnchorId, 'D1');

  // A current display reorder, rather than insertion order, defines the next range.
  selectedPush.clear();
  context.pushAttentionSelectionAnchorId = null;
  listed = ['D4', 'D3', 'D2', 'D1'].map(device_id => ({ device_id }));
  context.togglePushAttention('D2', false);
  context.togglePushAttention('D4', true);
  assert.deepEqual([...selectedPush].sort(), ['D2', 'D3', 'D4']);
  assert.equal(selectedPush.has('D1'), false);

  // A missing anchor falls back to one item, and a vanished live row clears the anchor
  // while preserving selected IDs so a refresh cannot broaden the action scope.
  listed = listed.filter(device => device.device_id !== 'D2');
  context.syncPushAttentionCheckboxes();
  assert.equal(context.pushAttentionSelectionAnchorId, 'D4');
  listed = listed.filter(device => device.device_id !== 'D4');
  context.syncPushAttentionCheckboxes();
  assert.equal(context.pushAttentionSelectionAnchorId, null);
  const before = [...selectedPush].sort();
  context.togglePushAttention('D1', true);
  assert.deepEqual([...selectedPush].sort(), [...before, 'D1'].sort());

  context.selectAllPushAttention(true);
  assert.equal(context.pushAttentionSelectionAnchorId, null);
  assert.deepEqual([...selectedDevices], ['device-selection']);
  assert.deepEqual([...selectedPower], ['connection-selection']);
});

test('provisional power Shift ranges skip unsupported rows and reset stale anchors', () => {
  const entries = [
    { connection_id: 'C1', power_control_supported: true },
    { connection_id: 'unsupported', power_control_supported: false },
    { connection_id: 'C2', power_control_supported: true },
    { connection_id: 'C3', power_control_supported: true },
  ];
  const selectedConnections = new Set();
  const selectedPush = new Set(['push-device']);
  const selectedDevices = new Set(['normal-device']);
  const context = vm.createContext({
    provisionalConnections: entries,
    selectedConnectionIds: selectedConnections,
    selectedPushAttentionIds: selectedPush,
    selectedIds: selectedDevices,
    connectionSelectionAnchorId: null,
    connectionById(id) { return entries.find(entry => entry.connection_id === id); },
    attentionPowerSupported(entry) { return !!entry && entry.power_control_supported === true; },
    render() {},
  });
  for (const name of ['toggleRangeSelection', 'attentionSelectableEntries', 'toggleAttention',
    'toggleSelectAllAttention', 'pruneAttentionSelection']) {
    vm.runInContext(consoleFunctionSource(name), context);
  }

  context.toggleAttention('C1', false);
  context.toggleAttention('C3', true);
  assert.deepEqual([...selectedConnections].sort(), ['C1', 'C2', 'C3']);
  assert.equal(selectedConnections.has('unsupported'), false);
  context.toggleAttention('C2', true); // The anchor advanced to C3; clear C2..C3.
  assert.deepEqual([...selectedConnections].sort(), ['C1']);
  assert.equal(context.connectionSelectionAnchorId, 'C2');
  context.toggleAttention('unsupported', true);
  assert.deepEqual([...selectedConnections].sort(), ['C1']);

  // Unsupported rows do not participate in range positions. Shift from C3 back to C1
  // selects exactly the three power-capable entries in their displayed order.
  context.toggleAttention('C1', false);
  context.toggleAttention('C3', false);
  context.toggleAttention('C1', true);
  assert.deepEqual([...selectedConnections].sort(), ['C1', 'C2', 'C3']);
  assert.deepEqual([...selectedPush], ['push-device']);
  assert.deepEqual([...selectedDevices], ['normal-device']);

  context.toggleAttention('C3', false);
  assert.equal(context.connectionSelectionAnchorId, 'C3');
  context.toggleAttention('C3', false);
  assert.equal(selectedConnections.has('C3'), true);
  entries.splice(entries.findIndex(entry => entry.connection_id === 'C3'), 1);
  assert.equal(context.pruneAttentionSelection(), true);
  assert.equal(context.connectionSelectionAnchorId, null, 'removed anchor is cleared without clearing still-supported selections');
  assert.deepEqual([...selectedConnections].sort(), ['C1', 'C2']);

  context.toggleSelectAllAttention();
  assert.deepEqual([...selectedConnections], []);
  assert.equal(context.connectionSelectionAnchorId, null);
});

test('Push checkbox selection is delegated through one native click handler', () => {
  const eventBlock = indexSource.match(/targetsBody\.addEventListener\('click', function \(e\) \{[\s\S]*?\n      \}\);/);
  assert.ok(eventBlock, 'selection event delegation is wired on click');
  assert.match(eventBlock[0], /selectPushAttention'\) togglePushAttention\(t\.dataset\.id, e\.shiftKey\)/);
  assert.match(eventBlock[0], /toggleAttention\(t\.dataset\.id, e\.shiftKey\)/);
  assert.doesNotMatch(indexSource, /targetsBody\.addEventListener\('change'/,
    'selection checkboxes do not have a second change handler that would toggle twice');
});

test('client restart requires operator Resume and allows Cancel', () => {
  loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const job = snapshot('restart-manual', 1, 'D1', 'queued', 1, {
    resumeRequired: true, cancellable: true,
  });
  job.devices.D1.queue_reason = 'client_restarted';
  socket.snapshot(job);
  const view = window.__stylyPushJobsV1Actions.assignmentFor('D1');
  assert.equal(view.status, 'resume_required');
  assert.equal(view.needsAttention, true);
  assert.equal(view.canResume, true);
  assert.equal(view.canCancel, true);
});

test('unconfirmed pending cancellation remains visible ahead of queued work until confirmed', () => {
  loadAdapter({ isDeviceOnline: () => false });
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const pending = snapshot('pending-cancel', 1, 'D1', 'unconfirmed', 1);
  pending.devices.D1.cancel_requested = true;
  pending.devices.D1.device_fence = { blocking_job_id: pending.job_id, blocking_attempt: 1 };
  const next = snapshot('next-job', 1, 'D1', 'queued', 2);
  socket.snapshot(pending, next);
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.assignmentFor('D1').job_id, pending.job_id);
  assert.equal(api.assignmentFor('D1').status, 'cancel_pending');
  assert.equal(api.assignmentFor('D1').needsAttention, false);
  socket.emit({ type: 'PUSH_JOB_UPDATED', job: {
    ...pending, revision: 2, devices: { D1: {
      ...pending.devices.D1, state: 'failed', failure: { code: 'cancelled' }, device_fence: null,
    } },
  } });
  assert.equal(api.assignmentFor('D1').job_id, next.job_id);
  assert.equal(api.assignmentFor('D1').status, 'queued');
});

test('job action acknowledgements become readable log lines', () => {
  const { socket, ...harness } = openConsole();
  const cancel = socket.emit({
    type: 'PUSH_JOB_ACTION_SENT',
    job_id: 'aaaaaaaa-1111-4111-8111-111111111111',
    action: 'CANCEL_PUSH_JOB',
    status: 'accepted', accepted_devices: ['D1'], rejected_devices: {},
  });
  const retry = socket.emit({
    type: 'PUSH_JOB_ACTION_SENT',
    job_id: 'bbbbbbbb-1111-4111-8111-111111111111',
    action: 'RETRY_PUSH_DEVICES', target_devices: ['D1'],
  });

  // The legacy handler must not also print its raw "Received: TYPE" fallback.
  assert.equal(cancel.stopped, true);
  assert.equal(retry.stopped, true);
  const lines = harness.logContainer.children.map((entry) => entry.children[1].textContent);
  assert.match(lines[0], /^Cancel recorded for job #aaaaaaaa/);
  assert.equal(lines[1], 'Created retry job #bbbbbbbb for device(s): D1');
});

for (const action of ['resume', 'cancel']) {
  test(action + ' never guesses a pending request from a same-job acknowledgement or count', () => {
    const { socket } = openConsole();
    const job = snapshot('unscoped-action', 1, 'D1', 'queued', 1, {
      resumeRequired: true, cancellable: true,
    });
    socket.snapshot(job);
    const api = window.__stylyPushJobsV1Actions;
    assert.equal(api.sendDeviceAction('D1', job.job_id, action), true);
    const response = operatorAck(socket.sent[0]);
    delete response.request_id;
    delete response.target_devices;
    socket.emit(response);
    assert.equal(api.actionPending(action, job.job_id, 'D1'), true);
  });

  test(action + ' correlates simultaneous same-job actions only by request ID', () => {
    const { socket } = openConsole();
    const job = snapshot('concurrent-actions', 1, 'D1', 'queued', 1, {
      resumeRequired: true, cancellable: true,
    });
    job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
    socket.snapshot(job);
    const api = window.__stylyPushJobsV1Actions;
    for (const device of ['D1', 'D2']) {
      assert.equal(api.sendDeviceAction(device, job.job_id, action), true);
    }
    const [first, second] = socket.sent;
    assert.notEqual(first.request_id, second.request_id);
    socket.emit(operatorAck(first));
    assert.equal(api.actionPending(action, job.job_id, 'D1'), false);
    assert.equal(api.actionPending(action, job.job_id, 'D2'), true);

    // Even an exact target set cannot identify which request produced a reply.
    const noId = operatorAck(second);
    delete noId.request_id;
    socket.emit(noId);
    socket.emit({ ...noId, type: 'ERROR', message: 'Uncorrelated failure' });
    assert.equal(api.actionPending(action, job.job_id, 'D2'), true);
    socket.emit(operatorAck(second));
    assert.equal(api.actionPending(action, job.job_id, 'D2'), false);
  });

  test(action + ' ignores delayed old responses after a new request for the same target', () => {
    const { socket } = openConsole();
    const job = snapshot('repeated-action', 1, 'D1', 'queued', 1, {
      resumeRequired: true, cancellable: true,
    });
    socket.snapshot(job);
    const api = window.__stylyPushJobsV1Actions;
    assert.equal(api.sendDeviceAction('D1', job.job_id, action), true);
    const first = socket.sent[0];
    const failure = {
      ...operatorAck(first), type: 'ERROR', status: 'rejected',
      accepted_devices: [], rejected_devices: { D1: 'State changed' }, message: 'State changed',
    };
    socket.emit(failure);
    assert.equal(api.actionPending(action, job.job_id, 'D1'), false);
    assert.equal(api.sendDeviceAction('D1', job.job_id, action), true);
    const second = socket.sent[1];
    assert.notEqual(first.request_id, second.request_id);

    socket.emit(operatorAck(first));
    socket.emit(failure);
    socket.emit({ ...operatorAck(second), job_id: 'different-job' });
    socket.emit({ ...operatorAck(second), action: 'different-action' });
    socket.emit({ ...failure, request_id: second.request_id,
      action: action === 'cancel' ? 'PUSH_FILES' : 'CANCEL_PUSH_JOB' });
    assert.equal(api.actionPending(action, job.job_id, 'D1'), true);
    socket.emit({ ...failure, request_id: second.request_id });
    assert.equal(api.actionPending(action, job.job_id, 'D1'), false);
  });

  test(action + ' ignores acknowledgements and errors from a replaced socket', () => {
    const { socket: oldSocket, ...h } = openConsole();
    const job = snapshot('stale-socket-action', 1, 'D1', 'queued', 1, {
      resumeRequired: true, cancellable: true,
    });
    oldSocket.snapshot(job);
    const api = window.__stylyPushJobsV1Actions;
    assert.equal(api.sendDeviceAction('D1', job.job_id, action), true);
    const oldRequest = oldSocket.sent[0];
    oldSocket.close();
    const current = new window.WebSocket('ws://localhost/ws/admin');
    current.snapshot(job);
    assert.equal(api.sendDeviceAction('D1', job.job_id, action), true);
    const request = current.sent[0];
    const logCount = h.logContainer.children.length;
    const oldAck = oldSocket.emit(operatorAck(request));
    const oldError = oldSocket.emit({ ...operatorAck(request), type: 'ERROR', message: 'Old socket' });
    assert.equal(oldAck.stopped, true);
    assert.equal(oldError.stopped, true);
    assert.equal(h.logContainer.children.length, logCount);
    assert.equal(api.actionPending(action, job.job_id, 'D1'), true);
    current.emit(operatorAck(oldRequest));
    assert.equal(api.actionPending(action, job.job_id, 'D1'), true);
    current.emit(operatorAck(request));
    assert.equal(api.actionPending(action, job.job_id, 'D1'), false);
  });
}

test('partial Cancel clears its complete requested scope and names accepted and rejected targets once', () => {
  const { socket, ...h } = openConsole();
  const job = snapshot('partial-cancel', 1, 'D1', 'queued', 1, {
    resumeRequired: true, cancellable: true,
  });
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  socket.snapshot(job);
  h.selectAttention('D1', 'D2');
  findElementByText(h.pushJobsTabActions, 'Cancel').click();
  const api = window.__stylyPushJobsV1Actions;
  const response = socket.emit(operatorAck(socket.sent[0], {
    status: 'partial', accepted_devices: ['D1'], rejected_devices: { D2: 'Already resumed' },
  }));
  assert.equal(response.stopped, true, 'the legacy handler must not log a second acknowledgement');
  assert.equal(api.actionPending('cancel', job.job_id, 'D1'), false);
  assert.equal(api.actionPending('cancel', job.job_id, 'D2'), false);
  const lines = h.logContainer.children.map(entry => entry.children[1].textContent);
  assert.equal(lines.filter(line => line.startsWith('Cancel partially recorded')).length, 1);
  assert.ok(lines.some(line => /Cancel partially recorded.*D1.*rejected: D2 \(Already resumed\)/.test(line)));
  assert.equal(lines.some(line => line.startsWith('Cancel recorded')), false);
});

test('all-rejected Cancel clears only its request and never logs cancellation recorded', () => {
  const { socket, ...h } = openConsole();
  const job = snapshot('rejected-cancel', 1, 'D1', 'queued', 1, { cancellable: true });
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  socket.snapshot(job);
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.sendDeviceAction('D1', job.job_id, 'cancel'), true);
  assert.equal(api.sendDeviceAction('D2', job.job_id, 'cancel'), true);
  const response = socket.emit(operatorAck(socket.sent[0], {
    type: 'ERROR', status: 'rejected', accepted_devices: [],
    rejected_devices: { D1: 'Already resumed' }, message: 'Cancel rejected: D1: Already resumed',
  }));
  assert.equal(response.stopped, true);
  assert.equal(api.actionPending('cancel', job.job_id, 'D1'), false);
  assert.equal(api.actionPending('cancel', job.job_id, 'D2'), true);
  const lines = h.logContainer.children.map(entry => entry.children[1].textContent);
  assert.ok(lines.some(line => /Cancel failed.*D1: Already resumed/.test(line)));
  assert.equal(lines.some(line => /Cancel (?:partially )?recorded/.test(line)), false);
});

test('bounded status checks render checking, exhaustion and reconnect wait without granting actions', () => {
  let online = true;
  const h = openIntegratedConsole({ isDeviceOnline: () => online });
  const job = snapshot('status-job', 1, 'D1', 'reconciling', 1);
  job.artifact = { artifact_id: 'artifact' };
  job.devices.D1.attempt = 1;
  h.socket.snapshot(job);
  const state = (revision, status, jobId = job.job_id) => ({
    type: 'PUSH_RECONCILIATION_STATE', revision,
    devices: [{ device_id: 'D1', job_id: jobId, attempt: 1, artifact_id: 'artifact', status }],
  });
  h.socket.emit(state(1, 'checking'));
  assert.match(h.html(), /Checking device status/);
  h.socket.emit(state(2, 'unavailable'));
  assert.match(h.html(), /Unable to confirm device status/);
  assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').canResume, false);
  assert.deepEqual(h.socket.sent, []);
  h.socket.emit(state(1, 'checking'));
  assert.match(h.html(), /Unable to confirm device status/, 'late status must not restart checking');
  online = false;
  window.__stylyPushJobsV1Actions.refreshConnectivity();
  assert.match(h.html(), /Waiting for device to reconnect/);
  online = true;
  h.socket.emit(state(3, 'checking', 'different-job'));
  assert.match(h.html(), /Awaiting status confirmation/);
  assert.doesNotMatch(h.html(), /Checking device status|Unable to confirm/);
});

test('status snapshots survive browser reconnect and ignore older initial snapshots and retired sockets', () => {
  const h = openConsole();
  const job = snapshot('status-snapshot', 1, 'D1', 'unconfirmed', 1);
  job.devices.D1.attempt = 1;
  const entry = { device_id: 'D1', job_id: job.job_id, attempt: 1, artifact_id: null, status: 'unavailable' };
  const fresh = new window.WebSocket('ws://server/ws/admin');
  fresh.emit({ type: 'PUSH_RECONCILIATION_STATE', revision: 5, devices: [entry] });
  fresh.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [job], reconciliation: { revision: 4, devices: [] } });
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.assignmentFor('D1').reconciliationStatus, 'unavailable');
  h.socket.emit({ type: 'PUSH_RECONCILIATION_STATE', revision: 50, devices: [] });
  assert.equal(api.assignmentFor('D1').reconciliationStatus, 'unavailable');
  fresh.emit({ type: 'PUSH_RECONCILIATION_STATE', revision: 6, devices: [] });
  assert.equal(api.assignmentFor('D1').reconciliationStatus, null);
  const afterRestart = new window.WebSocket('ws://server/ws/admin');
  afterRestart.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [job], reconciliation: { revision: 1, devices: [entry] } });
  assert.equal(api.assignmentFor('D1').reconciliationStatus, 'unavailable');
});

test('status checks for an unknown old execution appear on its blocked device row', () => {
  let online = true;
  const h = openIntegratedConsole({ isDeviceOnline: () => online });
  const job = snapshot('blocked-job', 1, 'D1', 'failed', 1);
  job.devices.D1.device_fence = {
    blocking_opaque_identity: JSON.stringify({ job_id: 'unknown-old-job', attempt: 1, artifact_id: 'old-artifact' }),
  };
  h.socket.snapshot(job);
  h.socket.emit({ type: 'PUSH_RECONCILIATION_STATE', revision: 1, devices: [{
    device_id: 'D1', job_id: 'unknown-old-job', attempt: 1, artifact_id: 'old-artifact', status: 'unavailable',
  }] });
  assert.match(h.html(), /Unable to confirm device status/);
  online = false;
  h.socket.emit({ type: 'PUSH_RECONCILIATION_STATE', revision: 2, devices: [] });
  window.__stylyPushJobsV1Actions.refreshConnectivity();
  assert.match(h.html(), /Waiting for device to reconnect/);
  h.socket.update({ ...job, revision: 2, devices: { D1: { ...job.devices.D1, device_fence: null } } });
  assert.doesNotMatch(h.html(), /Unable to confirm device status|Waiting for device to reconnect/);
});

test('Retry correlates independent same-job targets by source, request and owning socket', () => {
  const h = openConsole();
  const job = snapshot('retry-peers', 1, 'D1', 'failed', 1, { retryable: true, cancellable: true });
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  h.socket.snapshot(job);
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.sendDeviceAction('D1', job.job_id, 'retry'), true);
  assert.equal(api.sendDeviceAction('D1', job.job_id, 'cancel'), false, 'one target cannot race its pending Retry');
  assert.equal(api.sendDeviceAction('D2', job.job_id, 'retry'), true);
  const [first, second] = h.socket.sent;
  assert.notEqual(first.client_request_id, second.client_request_id, 'target sets have independent idempotency keys');
  h.socket.emit(operatorAck(second, { source_job_id: 'other-source' }));
  assert.equal(api.actionPending('retry', job.job_id, 'D2'), true);
  h.socket.emit(operatorAck(first));
  assert.equal(api.actionPending('retry', job.job_id, 'D1'), false);
  assert.equal(api.actionPending('retry', job.job_id, 'D2'), true);
  const replacement = new window.WebSocket('ws://localhost/ws/admin');
  h.socket.close();
  replacement.snapshot(job);
  assert.equal(api.sendDeviceAction('D2', job.job_id, 'retry'), true);
  h.socket.emit({ ...second, type: 'ERROR', action: second.type, message: 'retired socket' });
  assert.equal(api.actionPending('retry', job.job_id, 'D2'), true);
  replacement.emit({ ...replacement.sent[0], type: 'ERROR', action: second.type, message: 'rejected' });
  assert.equal(api.actionPending('retry', job.job_id, 'D2'), false);
});

test('bulk actions touch only the displayed assignment and never an older hidden job', () => {
  const h = openConsole();
  const old = snapshot('hidden-old-failure', 1, 'D1', 'failed', 1, { retryable: true, cancellable: true });
  const current = snapshot('displayed-paused', 1, 'D1', 'queued', 2, { resumeRequired: true, cancellable: true });
  h.socket.snapshot(old, current);
  h.selectAttention('D1');
  assert.equal(findElementByText(h.pushJobsTabActions, 'Retry').disabled, true);
  findElementByText(h.pushJobsTabActions, 'Cancel').click();
  assert.deepEqual(h.socket.sent.map(operatorRequestFields), [{
    type: 'CANCEL_PUSH_JOB', job_id: current.job_id, target_devices: ['D1'],
  }]);
});

test('Cancel withdraws a failed candidate while preserving its result and peer selection', () => {
  const h = openConsole();
  const job = snapshot('withdrawal', 1, 'D1', 'failed', 1, {
    retryable: true, cancellable: true, failure: { code: 'download_failed', detail: 'Checksum mismatch' },
  });
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  h.socket.snapshot(job);
  h.selectAttention('D1', 'D2');
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.sendDeviceAction('D1', job.job_id, 'cancel'), true);
  h.socket.update({ ...job, revision: 2, devices: {
    ...job.devices, D1: { ...job.devices.D1, cancel_requested: true, cancellable: false, retryable: false },
  } });
  assert.equal(api.assignmentFor('D1').status, 'fail');
  assert.equal(api.assignmentFor('D1').detail, 'Checksum mismatch');
  assert.equal(api.assignmentFor('D1').needsAttention, false);
  assert.equal(api.assignmentFor('D1').canRetry, false);
  assert.equal(api.assignmentFor('D2').canRetry, true);
  assert.deepEqual(Array.from(global.selectedPushAttentionIds), ['D2']);
  assert.equal(h.pushJobsAttention.children[1].children[0].textContent,
    '1 device(s) need review in the Needs attention tab.');
});

test('unresolved terminal candidates at other destinations remain reachable without job cards', () => {
  const h = openConsole();
  const old = snapshot('other-destination-failure', 1, 'D1', 'failed', 1, { retryable: true });
  const newer = snapshot('newer-success', 1, 'D1', 'succeeded', 2);
  h.socket.snapshot(old, newer);
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.assignmentFor('D1').job_id, old.job_id);
  assert.equal(api.assignmentFor('D1').needsAttention, true);
  h.socket.update({ ...old, revision: 2, devices: {
    D1: { ...old.devices.D1, cancel_requested: true, retryable: false },
  } });
  assert.equal(api.assignmentFor('D1').job_id, newer.job_id);
  assert.equal(api.assignmentFor('D1').needsAttention, false);
});

for (const order of ['ack-first', 'state-first']) {
  test('partial Resume clears only accepted selection with ' + order, () => {
    const online = new Set(['D1', 'D2']);
    const h = openIntegratedConsole({ isDeviceOnline: id => online.has(id) });
    const job = snapshot('partial-resume-selection', 1, 'D1', 'queued', 1, { resumeRequired: true });
    job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
    h.socket.snapshot(job);
    h.selectAttention('D1', 'D2');
    findElementByText(h.pushJobsTabActions, 'Resume').click();
    const request = h.socket.sent[0];
    online.delete('D2');
    window.__stylyPushJobsV1Actions.refreshConnectivity();
    const updated = { ...job, revision: 2, devices: {
      ...job.devices, D1: { ...job.devices.D1, state: 'downloading', resume_required: false },
    } };
    const ack = operatorAck(request, { target_count: 1, accepted_devices: ['D1'] });
    if (order === 'ack-first') {
      h.socket.emit(ack);
      h.socket.update(updated);
    } else {
      h.socket.update(updated);
      h.socket.emit(ack);
    }
    assert.deepEqual(Array.from(h.context.selectedPushAttentionIds), ['D2']);
    assert.equal(window.__stylyPushJobsV1Actions.actionPending('resume', job.job_id, 'D1'), false);
    h.socket.update({ ...updated, revision: 3, devices: {
      ...updated.devices, D1: { ...updated.devices.D1, state: 'failed', retryable: true },
    } });
    assert.equal(findElementByText(h.pushJobsTabActions, 'Retry').disabled, true);
    findElementByText(h.pushJobsTabActions, 'Retry').click();
    assert.equal(h.socket.sent.length, 1, 'an accepted target must not join a later bulk Retry');
    h.socket.close();
  });
}

test('canonical state cannot guess which Resume targets were accepted', () => {
  const h = openIntegratedConsole();
  const job = snapshot('resume-with-unrelated-failure', 1, 'D1', 'queued', 1, { resumeRequired: true });
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  h.socket.snapshot(job);
  h.selectAttention('D1', 'D2');
  findElementByText(h.pushJobsTabActions, 'Resume').click();
  h.socket.update({ ...job, revision: 2, devices: {
    D1: { ...job.devices.D1, state: 'downloading', resume_required: false },
    D2: { ...job.devices.D2, state: 'failed', resume_required: false, retryable: true },
  } });
  assert.deepEqual(Array.from(h.context.selectedPushAttentionIds), ['D1', 'D2']);
  h.socket.emit(operatorAck(h.socket.sent[0], { target_count: 1, accepted_devices: ['D1'] }));
  assert.deepEqual(Array.from(h.context.selectedPushAttentionIds), ['D2']);
  h.socket.close();
});

for (const state of ['failed', 'unconfirmed']) {
  test('withdrawing ' + state + ' keeps an unrelated opaque fence visible', () => {
    const h = openIntegratedConsole();
    const job = snapshot('withdraw-with-foreign-owner', 2, 'D1', state, 2, {
      retryable: true, cancellable: true, failure: { code: 'validation_failed' },
    });
    job.devices.D1.device_fence = {
      blocking_job_id: null, blocking_attempt: null,
      blocking_opaque_identity: JSON.stringify({ job_id: 'unknown-execution', attempt: 1 }),
      reason: 'unknown active job',
    };
    const older = snapshot('older-retry-candidate', 1, 'D1', 'failed', 1, { retryable: true });
    h.socket.snapshot(older, job);
    const api = window.__stylyPushJobsV1Actions;
    assert.equal(api.sendDeviceAction('D1', job.job_id, 'cancel'), true);
    h.socket.update({ ...job, revision: 3, devices: { D1: {
      ...job.devices.D1, cancel_requested: true, cancellable: false, retryable: false,
    } } });
    const view = api.assignmentFor('D1');
    assert.equal(view.job_id, job.job_id, 'the unresolved owner stays ahead of older Retry candidates');
    assert.equal(view.needsAttention, true);
    assert.equal(view.requiresReconciliation, true);
    assert.equal(view.status, 'unconfirmed', 'local candidate withdrawal is not foreign-owner cancellation');
    assert.equal(h.pushJobsAttention.style.display, '');
    assert.match(h.html(), /Status unknown/);
    assert.equal(view.canCancel, false);
    h.socket.close();
  });
}
