const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const nodeFetch = global.fetch;

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
  const bridgeState = new Map();
  const applied = [];
  const cleared = [];
  const clearedPendingRequests = [];

  global.window = global;
  global.WebSocket = FakeWebSocket;
  global.fetch = options.fetch || nodeFetch;
  global.document = {
    getElementById(id) {
      if (id === 'logContainer') return logContainer;
      if (id === 'logEmpty') return logEmpty;
      if (id === 'pushJobsAttention') return pushJobsAttention;
      return null;
    },
    createElement(tagName) { return new FakeElement(tagName); },
    createTextNode(text) { return { textContent: text, isConnected: true }; },
  };
  global.__stylyPushJobsV1Bridge = {
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
    logContainer, pushJobsAttention, bridgeState, applied, cleared,
    clearedPendingRequests,
  };
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
    ['reconciling', 'applying'],
    ['succeeded', 'success'],
    ['failed', 'fail'],
    ['interrupted', 'fail'],
    ['unconfirmed', 'fail'],
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
  const resume = harness.pushJobsAttention.children[1].children[1];
  assert.ok(resume);
  resume.click();

  assert.deepEqual(socket.sent, [{ type: 'PUSH_FILES', job_id: 'paused-job' }]);
  assert.equal(resume.disabled, true);
  assert.equal(resume.textContent, 'Resuming…');
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
  const dispatch = harness.pushJobsAttention.children[1].children[1];
  assert.equal(dispatch.textContent, 'Dispatch');
  dispatch.click();
  assert.deepEqual(socket.sent, [{ type: 'PUSH_FILES', job_id: 'ready-job' }]);
  assert.equal(dispatch.textContent, 'Dispatching…');
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
  const dispatch = harness.pushJobsAttention.children[1].children[1];

  dispatch.click();

  assert.equal(dispatch.disabled, false);
  assert.equal(dispatch.textContent, 'Dispatch');
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
