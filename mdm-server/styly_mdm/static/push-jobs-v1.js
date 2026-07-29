/* Issue #91 console integration.
 *
 * The established console remains framework-free. This adapter preserves its file
 * picker and target controls while changing only the Push/Sync transaction to:
 * create job -> upload into that job -> dispatch by job_id. Canonical state is held
 * solely in a revisioned Map restored from the server snapshot.
 */
(function () {
  'use strict';

  const nativeFetch = window.fetch.bind(window);
  const NativeWebSocket = window.WebSocket;
  const nativeSend = NativeWebSocket.prototype.send;
  const pushJobs = new Map();
  const jobEntries = new Map();
  const pendingUploads = new Map();
  let currentAdminSocket = null;

  function uuid() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID();
    }
    const bytes = new Uint8Array(16);
    window.crypto.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, function (value) {
      return value.toString(16).padStart(2, '0');
    }).join('');
    return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' +
      hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
  }

  function jsonResponse(body, status) {
    return new Response(JSON.stringify(body), {
      status: status || 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }

  function bundleInfo(formData) {
    const files = [];
    formData.forEach(function (value, key) {
      if (key === 'files' && value instanceof File) files.push(value);
    });
    const paths = files.map(function (file) {
      return file.webkitRelativePath || file.name || 'file';
    });
    let display = 'bundle';
    if (paths.length === 1) {
      display = paths[0].split('/').pop().replace(/\.[^.]*$/, '') || 'bundle';
    } else if (paths.length > 1) {
      const roots = new Set(paths.filter(function (path) {
        return path.indexOf('/') >= 0;
      }).map(function (path) {
        return path.split('/')[0];
      }));
      if (roots.size === 1 && paths.every(function (path) {
        return path.indexOf('/') >= 0;
      })) {
        display = Array.from(roots)[0];
      }
    }
    const bytes = files.reduce(function (sum, file) { return sum + file.size; }, 0);
    return { files: files, display: display, bytes: bytes };
  }

  window.fetch = function (input, init) {
    const url = typeof input === 'string' ? input : (input && input.url) || '';
    if (url === '/api/bundles' && init && init.method === 'POST' &&
        init.body instanceof FormData) {
      const requestId = uuid();
      const info = bundleInfo(init.body);
      pendingUploads.set(requestId, {
        requestId: requestId,
        formData: init.body,
        info: info,
      });
      // The legacy inline UI will send this opaque marker in its next PUSH_FILES
      // frame. No bytes cross the network until the server has created a job_id.
      return Promise.resolve(jsonResponse({
        bundle_filename: info.display + '.zip',
        bundle_url: 'push-job://pending/' + requestId,
        size: info.bytes,
        entry_count: info.files.length,
        skipped_count: 0,
      }));
    }
    return nativeFetch(input, init);
  };

  function appendLog(text, level) {
    const container = document.getElementById('logContainer');
    const empty = document.getElementById('logEmpty');
    if (!container) return;
    if (empty) empty.style.display = 'none';
    const entry = document.createElement('div');
    entry.className = 'log-entry push-job-v1-entry';
    const time = document.createElement('span');
    time.className = 'log-time';
    time.textContent = new Date().toLocaleTimeString();
    const message = document.createElement('span');
    message.className = 'log-msg ' + (level || 'info');
    message.textContent = text;
    entry.appendChild(time);
    entry.appendChild(message);
    container.appendChild(entry);
    container.scrollTop = container.scrollHeight;
  }

  function checkedJson(response) {
    return response.json().catch(function () { return {}; }).then(function (body) {
      if (!response.ok) throw new Error(body.error || response.statusText);
      return body;
    });
  }

  function createUploadDispatch(socket, legacyMessage, staged) {
    const source = staged.info;
    const createBody = {
      client_request_id: staged.requestId,
      target_devices: legacyMessage.target_devices || [],
      mode: legacyMessage.delete_extras === true ? 'sync' : 'push',
      dest_path: legacyMessage.dest_path,
      source: {
        display_name: source.display,
        declared_file_count: source.files.length,
        declared_total_bytes: source.bytes,
      },
    };
    appendLog('Creating Push/Sync job before upload…', 'info');
    nativeFetch('/api/push-jobs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(createBody),
    }).then(checkedJson).then(function (created) {
      appendLog('Created job ' + created.job_id.slice(0, 8) + '; uploading artifact…', 'info');
      if (created.state !== 'created' && created.state !== 'uploading') return created;
      return nativeFetch(created.upload_url, { method: 'POST', body: staged.formData })
        .then(checkedJson);
    }).then(function (ready) {
      if (!ready || ['ready', 'running', 'reconciling'].indexOf(ready.state) < 0) {
        throw new Error('job is not dispatchable after upload');
      }
      nativeSend.call(socket, JSON.stringify({ type: 'PUSH_FILES', job_id: ready.job_id }));
      appendLog('Dispatched job ' + ready.job_id.slice(0, 8) +
        ' after immutable artifact publication', 'success');
    }).catch(function (error) {
      appendLog('Push/Sync job failed before dispatch: ' + error.message, 'fail');
    });
  }

  NativeWebSocket.prototype.send = function (data) {
    if (typeof data === 'string') {
      try {
        const message = JSON.parse(data);
        if (message && message.type === 'PUSH_FILES' && !message.job_id) {
          const match = /^push-job:\/\/pending\/([0-9a-f-]+)$/i.exec(
            String(message.bundle_url || ''),
          );
          const requestId = match && match[1];
          const staged = requestId ? pendingUploads.get(requestId) : null;
          if (staged) {
            pendingUploads.delete(requestId);
            createUploadDispatch(this, message, staged);
            return;
          }
        }
      } catch (_ignored) {}
    }
    return nativeSend.call(this, data);
  };

  function applyJob(job) {
    if (!job || !job.job_id || typeof job.revision !== 'number') return;
    const current = pushJobs.get(job.job_id);
    if (current && current.revision >= job.revision) return;
    pushJobs.set(job.job_id, job);
    renderJob(job);
    renderDeviceAssignments();
  }

  function aggregateText(job) {
    const aggregate = job.aggregate || {};
    const active = (aggregate.waiting_transfer || 0) +
      (aggregate.dispatching || 0) + (aggregate.downloading || 0) +
      (aggregate.validating || 0) + (aggregate.applying || 0) +
      (aggregate.reconciling || 0);
    const failed = (aggregate.failed || 0) + (aggregate.interrupted || 0) +
      (aggregate.unconfirmed || 0);
    return (aggregate.succeeded || 0) + ' succeeded, ' + active + ' active, ' +
      (aggregate.queued || 0) + ' queued' +
      (failed ? ', ' + failed + ' non-success' : '') +
      ' (of ' + (aggregate.total || 0) + ')';
  }

  function fenceText(fence) {
    if (!fence) return '';
    const identity = fence.blocking_job_id || fence.blocking_opaque_identity || 'unknown';
    return 'blocked by ' + String(identity).slice(0, 40);
  }

  function renderJob(job) {
    const container = document.getElementById('logContainer');
    const empty = document.getElementById('logEmpty');
    if (!container) return;
    if (empty) empty.style.display = 'none';
    let entry = jobEntries.get(job.job_id);
    if (!entry || !entry.root.isConnected) {
      const root = document.createElement('div');
      root.className = 'log-entry push-job-v1-entry';
      root.dataset.pushJobId = job.job_id;
      const time = document.createElement('span');
      time.className = 'log-time';
      const body = document.createElement('span');
      body.className = 'log-msg info';
      root.appendChild(time);
      root.appendChild(body);
      container.appendChild(root);
      entry = { root: root, time: time, body: body };
      jobEntries.set(job.job_id, entry);
    }
    const fenced = Object.keys(job.devices || {}).filter(function (deviceId) {
      return job.devices[deviceId] && job.devices[deviceId].device_fence;
    });
    const verb = job.mode === 'sync' ? 'Sync' : 'Push';
    const terminal = ['succeeded', 'completed_with_errors', 'failed', 'interrupted']
      .indexOf(job.state) >= 0;
    entry.time.textContent = new Date(job.updated_at || Date.now()).toLocaleTimeString();
    entry.body.className = 'log-msg ' +
      (job.state === 'succeeded' ? 'success' :
        (terminal && job.state !== 'succeeded' ? 'warn' : 'info'));
    entry.body.replaceChildren(document.createTextNode(
      verb + ' #' + job.job_id.slice(0, 8) + ' → ' + job.dest_path + ': ' +
      job.state + '; ' + aggregateText(job),
    ));
    fenced.forEach(function (deviceId) {
      const device = job.devices[deviceId];
      entry.body.appendChild(document.createTextNode(
        '; fenced ' + deviceId + ' (' + fenceText(device.device_fence) + ')',
      ));
    });
    if (fenced.length) {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = 'Reconcile';
      button.style.marginLeft = '8px';
      button.addEventListener('click', function () {
        if (!currentAdminSocket || currentAdminSocket.readyState !== NativeWebSocket.OPEN) return;
        fenced.forEach(function (deviceId) {
          nativeSend.call(currentAdminSocket, JSON.stringify({
            type: 'RECONCILE_PUSH_DEVICE',
            device_id: deviceId,
          }));
        });
      });
      entry.body.appendChild(button);
    }
    container.scrollTop = container.scrollHeight;
  }

  function selectedAssignmentFor(deviceId) {
    const candidates = [];
    pushJobs.forEach(function (job) {
      const assignment = job.devices && job.devices[deviceId];
      if (assignment) candidates.push({ job: job, assignment: assignment });
    });
    const activeStates = new Set([
      'waiting_transfer', 'dispatching', 'downloading', 'validating',
      'applying', 'reconciling',
    ]);
    const active = candidates.filter(function (candidate) {
      return activeStates.has(candidate.assignment.state);
    }).sort(function (left, right) {
      return left.assignment.enqueue_seq - right.assignment.enqueue_seq;
    });
    if (active.length) return active[0];
    const queued = candidates.filter(function (candidate) {
      return candidate.assignment.state === 'queued';
    }).sort(function (left, right) {
      return left.assignment.enqueue_seq - right.assignment.enqueue_seq;
    });
    return queued.length ? queued[0] : null;
  }

  function renderDeviceAssignments() {
    const cells = Array.from(document.querySelectorAll('[data-task-id]'));
    cells.forEach(function (cell) {
      cell.querySelectorAll('.push-job-v1-device').forEach(function (badge) {
        badge.remove();
      });
      const deviceId = cell.getAttribute('data-task-id');
      const selected = selectedAssignmentFor(deviceId);
      if (!selected) return;
      const badge = document.createElement('span');
      badge.className = 'push-job-v1-device';
      badge.style.marginLeft = '4px';
      badge.title = selected.job.job_id;
      badge.textContent = '#' + selected.job.job_id.slice(0, 8) +
        ' ' + selected.assignment.state;
      cell.appendChild(badge);
    });
  }

  function observeMessage(event) {
    let message;
    try { message = JSON.parse(event.data); } catch (_ignored) { return; }
    if (!message || !message.type) return;
    if (message.type === 'PUSH_JOBS_SNAPSHOT') {
      event.stopImmediatePropagation();
      (message.jobs || []).forEach(applyJob);
    } else if (message.type === 'PUSH_JOB_UPDATED') {
      event.stopImmediatePropagation();
      applyJob(message.job);
    } else if (message.type === 'PUSH_PROGRESS' && message.job_id) {
      // Full revisioned snapshots already render concurrent jobs independently.
      event.stopImmediatePropagation();
    } else if (message.type === 'PUSH_DEVICE_STATE' && message.job_id) {
      // Canonical device phases include waiting_transfer/downloading/validating and
      // are rendered from PUSH_JOB_UPDATED. The legacy handler understands only
      // queued/transferring/applying and would otherwise mislabel them as failures.
      event.stopImmediatePropagation();
    } else if (message.type === 'PUSH_FILES_RESULT' && message.job_id) {
      // The terminal full snapshot is canonical. Keep identity-less old-server
      // results on the established handler, but do not let a derived job-v1 result
      // overwrite the revisioned device-row rendering.
      event.stopImmediatePropagation();
    }
  }

  function PatchedWebSocket(url, protocols) {
    const socket = protocols === undefined
      ? new NativeWebSocket(url)
      : new NativeWebSocket(url, protocols);
    if (String(url).indexOf('/ws/admin') >= 0) currentAdminSocket = socket;
    socket.addEventListener('message', observeMessage);
    return socket;
  }
  PatchedWebSocket.prototype = NativeWebSocket.prototype;
  Object.setPrototypeOf(PatchedWebSocket, NativeWebSocket);
  ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (name) {
    Object.defineProperty(PatchedWebSocket, name, { value: NativeWebSocket[name] });
  });
  window.WebSocket = PatchedWebSocket;
})();
