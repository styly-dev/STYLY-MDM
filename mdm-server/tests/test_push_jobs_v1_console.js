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
    this.scrollHeight = 0;
    this.scrollTop = 0;
    this.listeners = new Map();
  }

  appendChild(child) {
    this.children.push(child);
    child.isConnected = true;
    return child;
  }

  replaceChildren(...children) {
    this.children = children;
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  click() {
    (this.listeners.get('click') || []).forEach((listener) => listener());
  }

  remove() {
    this.isConnected = false;
  }
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
        enqueue_seq: enqueueSeq,
        dispatch_revision: options.dispatchRevision !== undefined ? options.dispatchRevision : 1,
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

function loadAdapter(options = {}) {
  const logContainer = new FakeElement('div');
  const logEmpty = new FakeElement('div');
  const pushJobsAttention = new FakeElement('div');
  const pushJobsTabActions = new FakeElement('div');
  const tabAttention = new FakeElement('button');
  const bridgeState = new Map();
  const applied = [];
  const cleared = [];
  const clearedPendingRequests = [];

  global.window = global;
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
  global.__stylyPushJobsV1Bridge = {
    isDeviceOnline: options.isDeviceOnline || (() => true),
    applyAssignment(assignment) {
      applied.push(assignment);
      if (options.applyAssignment && !options.applyAssignment(assignment)) {
        return false;
      }
      bridgeState.set(assignment.device_id, assignment);
      return true;
    },
    clearAssignment(deviceId, jobId) {
      cleared.push({ deviceId, jobId });
      const current = bridgeState.get(deviceId);
      if (current && current.job_id === jobId) bridgeState.delete(deviceId);
      return true;
    },
    clearPendingRequest(requestId) {
      clearedPendingRequests.push(requestId);
      return true;
    },
  };

  const adapterPath = path.join(
    __dirname, '..', 'styly_mdm', 'static', 'push-jobs-v1.js',
  );
  vm.runInThisContext(fs.readFileSync(adapterPath, 'utf8'), { filename: adapterPath });
  return {
    logContainer, pushJobsAttention, pushJobsTabActions, tabAttention, bridgeState, applied, cleared,
    clearedPendingRequests,
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

test('failed job creation clears the matching optimistic request', async () => {
  const harness = loadAdapter({
    fetch: async () => new Response(JSON.stringify({ error: 'create failed' }), {
      status: 500,
      headers: { 'Content-Type': 'application/json' },
    }),
  });
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const form = new FormData();
  form.append('files', new File(['data'], 'content.txt'));
  const staged = await window.fetch('/api/bundles', { method: 'POST', body: form });
  const marker = await staged.json();
  const requestId = marker.bundle_url.slice('push-job://pending/'.length);

  socket.send(JSON.stringify({
    type: 'PUSH_FILES',
    target_devices: ['D1'],
    bundle_url: marker.bundle_url,
    dest_path: '/sdcard/job',
    delete_extras: false,
  }));
  await new Promise((resolve) => setImmediate(resolve));

  assert.deepEqual(harness.clearedPendingRequests, [requestId]);
});

test('upload dispatch uses the replacement admin socket', async () => {
  let finishUpload;
  const uploadReady = new Promise((resolve) => { finishUpload = resolve; });
  const harness = loadAdapter({
    fetch: async (url) => {
      if (url === '/api/push-jobs') {
        return new Response(JSON.stringify({
          job_id: '11111111-1111-4111-8111-111111111111',
          state: 'created',
          upload_url: '/api/push-jobs/upload',
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      if (url === '/api/push-jobs/upload') {
        await uploadReady;
        return new Response(JSON.stringify({
          job_id: '11111111-1111-4111-8111-111111111111',
          state: 'ready',
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      throw new Error('unexpected URL: ' + url);
    },
  });
  const firstSocket = new window.WebSocket('ws://localhost/ws/admin');
  const form = new FormData();
  form.append('files', new File(['data'], 'content.txt'));
  const staged = await window.fetch('/api/bundles', { method: 'POST', body: form });
  const marker = await staged.json();

  firstSocket.send(JSON.stringify({
    type: 'PUSH_FILES',
    target_devices: ['D1'],
    bundle_url: marker.bundle_url,
    dest_path: '/sdcard/job',
    delete_extras: false,
  }));
  await new Promise((resolve) => setImmediate(resolve));
  const replacementSocket = new window.WebSocket('ws://localhost/ws/admin');
  finishUpload();
  await new Promise((resolve) => setImmediate(resolve));

  assert.deepEqual(firstSocket.sent, []);
  assert.deepEqual(replacementSocket.sent, [{
    type: 'PUSH_FILES',
    job_id: '11111111-1111-4111-8111-111111111111',
  }]);
  assert.deepEqual(harness.clearedPendingRequests, []);
});

test('a rejected canonical assignment is not recorded as rendered', () => {
  const harness = loadAdapter({ applyAssignment: () => false });
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.emit({
    type: 'PUSH_JOBS_SNAPSHOT',
    jobs: [snapshot('active-job', 1, 'D1', 'downloading', 1)],
  });
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [] });

  assert.equal(harness.applied.length, 1);
  assert.deepEqual(harness.cleared, []);
});

test('optimistic paint preserves only active canonical Push assignments', () => {
  const match = indexSource.match(
    /function shouldPreserveCanonicalPushAssignment\(current\) \{[\s\S]*?\n        \}/,
  );
  assert.ok(match, 'the console exposes a testable optimistic-paint predicate');
  const shouldPreserve = vm.runInNewContext('(' + match[0] + ')');

  for (const status of ['queued', 'transferring', 'applying', 'reconciling', 'unconfirmed', 'resume_required']) {
    assert.equal(shouldPreserve({
      owner: 'push-job-v1', job_id: 'active-job', status,
    }), true, status);
  }
  for (const status of ['success', 'fail']) {
    assert.equal(shouldPreserve({
      owner: 'push-job-v1', job_id: 'terminal-job', status,
    }), false, status);
  }
  assert.equal(shouldPreserve({
    owner: 'push-job-v1', job_id: null, status: 'queued',
  }), false);
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
    ['validating', 'applying'],
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

test('restart-paused jobs expose the existing operator resume command', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.emit({
    type: 'PUSH_JOBS_SNAPSHOT',
    jobs: [snapshot('paused-job', 4, 'D1', 'queued', 1, {
      dispatchEnabled: false,
      dispatchPausedReason: 'server_restart',
    })],
  });

  assert.equal(harness.pushJobsAttention.style.display, '');
  assert.equal(harness.pushJobsAttention.children[0].textContent,
    'Push / Sync jobs need attention');
  assert.equal(harness.pushJobsAttention.children[1].children[0].textContent,
    '1 Push / Sync job(s) need review in the Needs attention tab.');
  const open = harness.pushJobsAttention.children[1].children[1];
  assert.equal(open.textContent, 'Open Needs attention');
  let opened = false;
  harness.tabAttention.addEventListener('click', () => { opened = true; });
  open.click();
  assert.equal(opened, true);
  const resume = findElementByText(harness.pushJobsTabActions, 'Resume all');
  assert.ok(resume);
  resume.click();

  assert.deepEqual(socket.sent, [{ type: 'PUSH_FILES', job_id: 'paused-job', target_devices: ['D1'] }]);
  assert.equal(resume.disabled, true);
  assert.equal(resume.textContent, 'Resuming…');
});

test('download retry exhaustion explains that Resume retains the partial', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.emit({
    type: 'PUSH_JOBS_SNAPSHOT',
    jobs: [snapshot('paused-job', 4, 'D1', 'queued', 1, {
      dispatchEnabled: false,
      dispatchPausedReason: 'download_retry_exhausted',
    })],
  });

  assert.match(harness.pushJobsTabActions.children[0].children[0].children[1].textContent,
    /eligible device/);
  assert.equal(findElementByText(harness.pushJobsTabActions, 'Resume all').disabled, false);
});

test('resume attention hides once the canonical job is enabled', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.emit({
    type: 'PUSH_JOBS_SNAPSHOT',
    jobs: [snapshot('paused-job', 4, 'D1', 'queued', 1, {
      dispatchEnabled: false,
      dispatchPausedReason: 'server_restart',
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
    })],
  });

  assert.equal(harness.pushJobsAttention.style.display, '');
  const resume = findElementByText(harness.pushJobsTabActions, 'Resume all');
  assert.ok(resume);
  resume.click();
  assert.deepEqual(socket.sent, [{ type: 'PUSH_FILES', job_id: 'ready-job', target_devices: ['D1'] }]);
  assert.equal(resume.textContent, 'Resuming…');
});

test('dispatch action becomes retryable when WebSocket send throws', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.emit({
    type: 'PUSH_JOBS_SNAPSHOT',
    jobs: [snapshot('ready-job', 4, 'D1', 'queued', 1, {
      dispatchEnabled: false,
      jobState: 'ready',
    })],
  });
  socket.sendError = new Error('socket closed');
  const dispatch = findElementByText(harness.pushJobsTabActions, 'Resume all');

  dispatch.click();

  assert.equal(dispatch.disabled, false);
  assert.equal(dispatch.textContent, 'Resume all');
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

test('Push state recovery warning preserves an independent install progress', () => {
  const match = indexSource.match(/function progressCellHtml\(id\) \{[\s\S]*?\n      \}/);
  assert.ok(match, 'the console exposes a testable progress renderer');
  const device = {
    device_id: 'D1',
    status: 'online',
    push_state_retry_supported: true,
    push_state_status: 'unavailable',
  };
  const context = vm.createContext({
    deviceById(id) { return id === device.device_id ? device : null; },
    canRetryPushState(candidate) {
      return !!(candidate && candidate.status === 'online' &&
        candidate.push_state_retry_supported === true &&
        candidate.push_state_status === 'unavailable');
    },
    taskCellHtml() { return '<span class="ins-installing">Installing…</span>'; },
    pushAssignmentFor() { return null; },
    esc(value) { return String(value); },
  });
  vm.runInContext(match[0], context);

  const html = context.progressCellHtml('D1');
  assert.match(html, /Installing/);
  assert.match(html, /Push state unavailable/);
  assert.match(html, /retryPushState/);
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
    assert.equal(context.activeGroup, cleared ? null : 'Room A', label);
    assert.equal(context.powerConfirm.checked, !cleared, label);
  }
});

test('offline push rows retain unfinished jobs without claiming current execution', () => {
  const match = indexSource.match(/function taskCellHtml\(id\) \{[\s\S]*?\n      \}/);
  assert.ok(match);
  const device = { status: 'online' };
  const state = { task: 'push', status: 'applying', verb: 'Push', owner: 'push-job-v1' };
  const context = vm.createContext({
    deviceTaskState: { D1: state },
    deviceById() { return device; },
    esc(value) { return String(value); },
  });
  vm.runInContext(match[0], context);
  assert.match(context.taskCellHtml('D1'), /Pushing/);
  device.status = 'offline';
  for (const status of ['queued', 'transferring', 'applying']) {
    state.status = status;
    const html = context.taskCellHtml('D1');
    assert.match(html, /Job pending · offline/);
    assert.doesNotMatch(html, /spinner|Pushing|Transferring|failed/);
    assert.equal(state.status, status, 'rendering must not mutate canonical state');
  }
  device.status = 'online';
  state.status = 'applying';
  assert.match(context.taskCellHtml('D1'), /Pushing/);
  for (const connectivity of ['online', 'offline']) {
    device.status = connectivity;
    state.status = 'reconciling';
    const html = context.taskCellHtml('D1');
    assert.match(html, /Awaiting status confirmation/);
    assert.equal(html.includes(' · offline'), connectivity === 'offline');
    assert.doesNotMatch(html, /Job pending|spinner|Pushing|failed/);
    state.status = 'unconfirmed';
    assert.match(context.taskCellHtml('D1'), /Status unknown/);
  }
  device.status = 'offline';
  state.status = 'success';
  assert.match(context.taskCellHtml('D1'), /pushed/);
  state.status = 'fail';
  assert.match(context.taskCellHtml('D1'), /failed/);
});

test('device list disconnect retains durable push assignment until authoritative snapshot', () => {
  const match = indexSource.match(/case 'DEVICE_LIST': \{([\s\S]*?)\n            break;/);
  assert.ok(match);
  const context = vm.createContext({
    msg: { devices: [{ device_id: 'D1', status: 'offline' }] },
    devices: [], selectedIds: new Set(), powerConfirm: {},
    knownIds() { return new Set(['D1']); },
    isOnline(device) { return device.status === 'online'; },
    getDeviceId(device) { return device.device_id; },
    deviceTaskState: {
      D1: { task: 'push', owner: 'push-job-v1', status: 'applying' },
      D2: { task: 'install', status: 'installing' },
    },
    syncActiveGroupSelection() {}, render() {}, window: {},
  });
  vm.runInContext(match[1], context);
  assert.equal(context.deviceTaskState.D1.status, 'applying');
  assert.equal(context.deviceTaskState.D2, undefined);
});


test('retry-exhausted queued assignment stays visibly paused across reconnection', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const job = snapshot('paused-device', 1, 'D1', 'queued', 1);
  job.devices.D1.queue_reason = 'download_retry_exhausted';
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [job] });
  assert.equal(harness.bridgeState.get('D1').status, 'resume_required');
  const match = indexSource.match(/function taskCellHtml\(id\) \{[\s\S]*?\n      \}/);
  for (const status of ['online', 'offline']) {
    const context = vm.createContext({
      deviceTaskState: { D1: { task: 'push', status: 'resume_required' } },
      deviceById() { return { status }; },
      esc(value) { return String(value); },
    });
    vm.runInContext(match[0], context);
    assert.match(context.taskCellHtml('D1'), /Resume required/);
    assert.doesNotMatch(context.taskCellHtml('D1'), /Pushing|spinner|failed/);
  }
});

test('per-device Resume remains available while other devices may dispatch', () => {
  const h = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const job = snapshot('partial-job', 4, 'D1', 'queued', 1, { dispatchEnabled: true });
  job.devices.D1.queue_reason = 'download_retry_exhausted';
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [job] });
  assert.equal(h.bridgeState.get('D1').status, 'resume_required');
  assert.equal(h.pushJobsAttention.style.display, '');
  findElementByText(h.pushJobsTabActions, 'Resume all').click();
  assert.deepEqual(socket.sent, [{ type: 'PUSH_FILES', job_id: 'partial-job', target_devices: ['D1'] }]);
});

test('job controls omit active cancellation and retry failures with a stable request id', () => {
  const h = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const running = snapshot('active-job', 4, 'D1', 'downloading', 1);
  const failed = snapshot('failed-job', 5, 'D2', 'failed', 2);
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [running, failed] });
  const retry = findElementByText(h.pushJobsTabActions, 'Retry failed devices');
  for (const entry of h.logContainer.children) {
    assert.equal(entry.children[1].children.some(e => e.tagName === 'BUTTON' || e.tagName === 'button'), false);
  }
  assert.equal(findElementByText(h.pushJobsTabActions, 'Cancel job'), undefined);
  retry.click();
  retry.click();
  assert.equal(socket.sent[0].type, 'RETRY_FAILED_PUSH_JOB');
  assert.equal(socket.sent[0].job_id, 'failed-job');
  assert.ok(socket.sent[0].client_request_id);
  assert.deepEqual(socket.sent[0], socket.sent[1]);
  const cancelled = snapshot('failed-job', 6, 'D2', 'failed', 2);
  cancelled.devices.D2.failure = { code: 'cancelled' };
  socket.emit({ type: 'PUSH_JOB_UPDATED', job: cancelled });
  assert.equal(findElementByText(h.pushJobsTabActions, 'Retry failed devices'), undefined);
  assert.equal(h.bridgeState.get('D2').status, 'cancelled');
});

test('inline actions send only the selected device while Resume all targets eligible online devices', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const job = snapshot('per-device-job', 1, 'D1', 'queued', 1);
  job.devices.D1.queue_reason = 'download_retry_exhausted';
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [job] });
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.sendDeviceAction('D1', job.job_id, 'resume'), true);
  assert.deepEqual(socket.sent.at(-1), {
    type: 'PUSH_FILES', job_id: job.job_id, target_devices: ['D1'],
  });
  assert.equal(api.sendDeviceAction('D2', job.job_id, 'cancel'), true);
  assert.deepEqual(socket.sent.at(-1), {
    type: 'CANCEL_PUSH_JOB', job_id: job.job_id, target_devices: ['D2'],
  });
  assert.equal(api.sendDeviceAction('D2', 'stale-job', 'cancel'), false);
  const all = findElementByText(harness.pushJobsTabActions, 'Resume all');
  assert.equal(all.textContent, 'Resume all');
  all.click();
  assert.deepEqual(socket.sent.at(-1), { type: 'PUSH_FILES', job_id: job.job_id, target_devices: ['D1', 'D2'] });
  findElementByText(harness.pushJobsTabActions, 'Cancel all').click();
  assert.deepEqual(socket.sent.at(-1), {
    type: 'CANCEL_PUSH_JOB', job_id: job.job_id, target_devices: ['D1', 'D2'],
  });
});

test('never-dispatched paused device is excluded from Cancel and Cancel all', () => {
  const h = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const job = snapshot('never-dispatched', 1, 'D1', 'queued', 1, { dispatchRevision: null });
  job.devices.D1.queue_reason = 'dispatch_paused';
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [job] });
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.assignmentFor('D1').canCancel, false);
  assert.equal(api.sendDeviceAction('D1', job.job_id, 'cancel'), false);
  assert.equal(findElementByText(h.pushJobsTabActions, 'Cancel all').disabled, true);
  assert.equal(socket.sent.length, 0);
});

test('individual Cancel requires confirmation', () => {
  let prompt = '';
  const h = loadAdapter({ confirm: message => { prompt = message; return false; } });
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const job = snapshot('confirm-cancel', 1, 'D1', 'queued', 1);
  job.devices.D1.queue_reason = 'download_retry_exhausted';
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [job] });
  assert.equal(window.__stylyPushJobsV1Actions.sendDeviceAction('D1', job.job_id, 'cancel'), false);
  assert.match(prompt, /D1/);
  assert.equal(socket.sent.length, 0);
  assert.ok(h.pushJobsTabActions);
});

test('attention derives latest canonical assignment and clears after resume, retry, or confirmed stop', () => {
  loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const job = snapshot('attention-job', 1, 'D1', 'queued', 1);
  job.devices.D1.queue_reason = 'dispatch_paused';
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [job] });
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.assignmentFor('D1').canResume, true);
  assert.equal(api.assignmentFor('D1').needsAttention, true);
  const running = snapshot(job.job_id, 2, 'D1', 'downloading', 1);
  socket.emit({ type: 'PUSH_JOB_UPDATED', job: running });
  assert.equal(api.assignmentFor('D1').needsAttention, false);
  assert.equal(api.assignmentFor('D1').canResume, false);
  assert.equal(api.assignmentFor('D1').canCancel, false);
  const failed = snapshot(job.job_id, 3, 'D1', 'failed', 1);
  socket.emit({ type: 'PUSH_JOB_UPDATED', job: failed });
  assert.equal(api.assignmentFor('D1').needsAttention, true);
  socket.emit({ type: 'PUSH_JOB_UPDATED', job: snapshot('retry-job', 1, 'D1', 'queued', 2) });
  assert.equal(api.assignmentFor('D1').needsAttention, false, 'new retry suppresses old failure');
  const stopped = snapshot('retry-job', 2, 'D1', 'failed', 2);
  stopped.devices.D1.failure = { code: 'cancelled' };
  socket.emit({ type: 'PUSH_JOB_UPDATED', job: stopped });
  assert.equal(api.assignmentFor('D1').needsAttention, false);
  assert.equal(api.assignmentFor('D1').status, 'cancelled');
});

test('Needs attention keeps canonical jobs separate from provisional power selection', () => {
  const render = indexSource.match(/function renderAttentionHtml\(\) \{[\s\S]*?\n      \}/);
  const canonical = [{ device_id: 'canonical-1', label: 'Interrupted headset', status: 'online', ip: '192.0.2.1' }];
  const provisional = [{ connection_id: 'socket-1', model: 'PICO', identity_status: 'unavailable' }];
  const context = vm.createContext({
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
  vm.runInContext(render[0], context);
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
  const render = indexSource.match(/function progressCellHtml\(id\) \{[\s\S]*?\n      \}/);
  const context = vm.createContext({
    taskCellHtml() { return 'Resume required'; },
    pushAssignmentFor() { return { job_id: 'job"x', canResume: true, canCancel: true }; },
    canRetryPushState() { return false; }, deviceById() { return {}; },
    esc(value) { return String(value).replace(/"/g, '&quot;'); },
  });
  vm.runInContext(render[0], context);
  const html = context.progressCellHtml('device"x');
  assert.match(html, /class="push-job-actions"/);
  assert.match(html, /data-action="resume"/);
  assert.match(html, /data-action="cancel"/);
  assert.match(html, /data-id="device&quot;x"/);
  assert.match(html, /data-job-id="job&quot;x"/);
});

test('inline Push/Sync actions share compact sizing and a danger treatment for Cancel', () => {
  assert.match(indexSource, /\.push-job-actions\s*\{[^}]*display:\s*inline-flex/);
  assert.match(indexSource, /\.push-job-action\s*\{[\s\S]*min-height:\s*28px/);
  assert.match(indexSource, /\.push-job-action\[data-action="cancel"\]\s*\{[\s\S]*color:\s*var\(--danger\)/);
});

test('Resume all remains for nonselected paused devices and hides once all are finished', () => {
  const harness = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const job = snapshot('partial-resume-job', 1, 'D1', 'downloading', 1);
  job.devices.D2 = { ...job.devices.D1, state: 'reconciling', queue_reason: 'dispatch_paused', enqueue_seq: 2 };
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [job] });
  assert.equal(harness.pushJobsAttention.style.display, '');
  assert.equal(findElementByText(harness.pushJobsTabActions, 'Resume all').textContent, 'Resume all');
  socket.emit({ type: 'PUSH_JOB_UPDATED', job: {
    ...job, revision: 2, dispatch_enabled: false,
    devices: Object.fromEntries(Object.entries(job.devices).map(([id, d]) => [id, { ...d, state: 'failed', failure: { code: 'cancelled' } }])),
  } });
  assert.equal(harness.pushJobsAttention.style.display, 'none');
});

test('only a timed-out queued assignment exposes or sends Cancel', () => {
  loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [] });
  let revision = 0;
  for (const state of ['queued', 'waiting_transfer', 'dispatching', 'downloading', 'validating', 'applying', 'reconciling', 'unconfirmed']) {
    const job = snapshot('cancel-scope', ++revision, 'D1', state, 1);
    socket.emit({ type: 'PUSH_JOB_UPDATED', job });
    assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').canCancel, false, state);
    assert.equal(window.__stylyPushJobsV1Actions.sendDeviceAction('D1', job.job_id, 'cancel'), false, state);
  }
  const paused = snapshot('cancel-scope', ++revision, 'D1', 'queued', 1);
  paused.devices.D1.queue_reason = 'download_retry_exhausted';
  socket.emit({ type: 'PUSH_JOB_UPDATED', job: paused });
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
    selectedConnectionIds: new Set(), attentionSelectAllState() { return ''; },
    attentionSelectableCount() { return 0; }, attentionSelectAllGlyph() { return ''; },
    attentionPowerSupported() { return false; }, shortConnectionId(id) { return id.slice(0, 8); },
    progressCellHtml() { return ''; },
    esc(value) { return String(value).replace(/</g, '&lt;').replace(/>/g, '&gt;'); },
  });
  vm.runInContext(resolve + '\n' + render, context);
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
  const h = loadAdapter({ isDeviceOnline: id => online.has(id) });
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const job = snapshot('offline-resume', 1, 'D1', 'queued', 1);
  job.devices.D1.queue_reason = 'download_retry_exhausted';
  job.devices.D2 = { ...job.devices.D1, enqueue_seq: 2 };
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [job] });
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.assignmentFor('D1').needsAttention, true);
  assert.equal(api.assignmentFor('D1').canResume, false);
  assert.equal(api.assignmentFor('D1').canCancel, true);
  assert.equal(api.sendDeviceAction('D1', job.job_id, 'resume'), false);
  findElementByText(h.pushJobsTabActions, 'Resume all').click();
  assert.deepEqual(socket.sent.at(-1).target_devices, ['D2']);
  online.clear();
  api.refreshConnectivity();
  assert.equal(findElementByText(h.pushJobsTabActions, 'Resume all').disabled, true);
  assert.equal(h.bridgeState.get('D2').canResume, false);
  online.add('D1');
  api.refreshConnectivity();
  assert.equal(findElementByText(h.pushJobsTabActions, 'Resume all').disabled, false);
  assert.equal(h.bridgeState.get('D1').canResume, true);
});


test('Reconcile stays in job controls and never appears inside Activity log', () => {
  const h = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const job = snapshot('fenced-job', 2, 'D1', 'unconfirmed', 1);
  job.devices.D1.device_fence = { reason: 'result_unconfirmed' };
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [job] });
  findElementByText(h.pushJobsTabActions, 'Reconcile').click();
  assert.deepEqual(socket.sent, [{ type: 'RECONCILE_PUSH_DEVICE', device_id: 'D1' }]);
  for (const entry of h.logContainer.children) {
    assert.equal(entry.children[1].children.some(e => e.tagName === 'button'), false);
  }
});

test('offline reconciling cancel becomes pending without human attention or repeat controls', () => {
  const h = loadAdapter({ isDeviceOnline: () => false });
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const job = snapshot('offline-cancel', 1, 'D1', 'reconciling', 1);
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [job] });
  const api = window.__stylyPushJobsV1Actions;
  assert.equal(api.assignmentFor('D1').canCancel, true);
  assert.equal(api.assignmentFor('D1').canResume, false);
  assert.equal(api.sendDeviceAction('D1', job.job_id, 'cancel'), true);
  job.devices.D1.cancel_requested = true;
  socket.emit({ type: 'PUSH_JOB_UPDATED', job: { ...job, revision: 2 } });
  const view = api.assignmentFor('D1');
  assert.equal(view.status, 'cancel_pending');
  assert.equal(view.needsAttention, false);
  assert.equal(view.canCancel, false);
  assert.equal(view.canResume, false);
  assert.equal(h.pushJobsAttention.style.display, 'none');
});

test('retried targets leave old attention and retry controls while a new failure stays actionable', () => {
  const h = loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const old = snapshot('original-failure', 1, 'D1', 'failed', 1);
  old.devices.D1.retry_job_id = 'new-retry';
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [old] });
  assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').needsAttention, false);
  assert.equal(h.pushJobsAttention.style.display, 'none');
  socket.emit({ type: 'PUSH_JOB_UPDATED', job: snapshot('new-retry', 1, 'D1', 'failed', 2) });
  assert.equal(window.__stylyPushJobsV1Actions.assignmentFor('D1').needsAttention, true);
  assert.equal(h.pushJobsAttention.style.display, '');
  assert.equal(h.pushJobsAttention.children.length, 2, 'only new failed job needs attention');
});

test('client restart requires operator Resume and allows Cancel', () => {
  loadAdapter();
  const socket = new window.WebSocket('ws://localhost/ws/admin');
  const job = snapshot('restart-manual', 1, 'D1', 'queued', 1);
  job.devices.D1.queue_reason = 'client_restarted';
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [job] });
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
  pending.devices.D1.device_fence = { blocking_job_id: pending.job_id };
  const next = snapshot('next-job', 1, 'D1', 'queued', 2);
  socket.emit({ type: 'PUSH_JOBS_SNAPSHOT', jobs: [pending, next] });
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
