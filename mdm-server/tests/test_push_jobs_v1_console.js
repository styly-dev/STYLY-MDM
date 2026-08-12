const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

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
  }

  appendChild(child) {
    this.children.push(child);
    child.isConnected = true;
    return child;
  }

  replaceChildren(...children) {
    this.children = children;
  }

  addEventListener() {}

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
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  send() {}

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
    state: ['succeeded', 'failed', 'interrupted', 'unconfirmed'].includes(state)
      ? 'completed_with_errors' : 'running',
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
  };
}

function loadAdapter() {
  const logContainer = new FakeElement('div');
  const logEmpty = new FakeElement('div');
  const bridgeState = new Map();
  const applied = [];
  const cleared = [];

  global.window = global;
  global.WebSocket = FakeWebSocket;
  global.document = {
    getElementById(id) {
      if (id === 'logContainer') return logContainer;
      if (id === 'logEmpty') return logEmpty;
      return null;
    },
    createElement(tagName) { return new FakeElement(tagName); },
    createTextNode(text) { return { textContent: text, isConnected: true }; },
  };
  global.__stylyPushJobsV1Bridge = {
    applyAssignment(assignment) {
      applied.push(assignment);
      bridgeState.set(assignment.device_id, assignment);
      return true;
    },
    clearAssignment(deviceId, jobId) {
      cleared.push({ deviceId, jobId });
      const current = bridgeState.get(deviceId);
      if (current && current.job_id === jobId) bridgeState.delete(deviceId);
      return true;
    },
  };

  const adapterPath = path.join(
    __dirname, '..', 'styly_mdm', 'static', 'push-jobs-v1.js',
  );
  vm.runInThisContext(fs.readFileSync(adapterPath, 'utf8'), { filename: adapterPath });
  return { logContainer, bridgeState, applied, cleared };
}

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
