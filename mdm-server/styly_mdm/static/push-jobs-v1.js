/* Issue #91 console bridge.
 *
 * The existing console remains intentionally framework-free.  This script is
 * loaded before its inline code and upgrades only Push/Sync:
 *   - /api/bundles is staged in-browser instead of uploaded immediately;
 *   - the outgoing legacy PUSH_FILES frame supplies the target/destination;
 *   - a server-owned job is created, then uploaded, then dispatched by job_id;
 *   - canonical snapshots are merged by monotonically increasing revision.
 */
(function () {
  'use strict';

  const nativeFetch = window.fetch.bind(window);
  const NativeWebSocket = window.WebSocket;
  const nativeSend = NativeWebSocket.prototype.send;
  const pushJobs = new Map();
  const jobEntries = new Map();
  let pendingBundle = null;
  let currentAdminSocket = null;

  function uuid() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID();
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      const r = Math.random() * 16 | 0;
      return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
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
    const paths = files.map(function (file) { return file.name || 'file'; });
    let display = 'bundle';
    if (paths.length === 1) {
      display = paths[0].split('/').pop().replace(/\.[^.]*$/, '') || 'bundle';
    } else if (paths.length > 1) {
      const roots = new Set(paths.filter(function (p) { return p.indexOf('/') >= 0; }).map(function (p) { return p.split('/')[0]; }));
      if (roots.size === 1 && paths.every(function (p) { return p.indexOf('/') >= 0; })) display = Array.from(roots)[0];
    }
    const bytes = files.reduce(function (sum, file) { return sum + file.size; }, 0);
    return { files: files, display: display, bytes: bytes };
  }

  window.fetch = function (input, init) {
    const url = typeof input === 'string' ? input : (input && input.url) || '';
    if (url === '/api/bundles' && init && init.method === 'POST' && init.body instanceof FormData) {
      const info = bundleInfo(init.body);
      pendingBundle = { formData: init.body, info: info };
      // Return the shape the established UI expects.  No bytes have crossed the
      // network yet; the patched WebSocket send below creates the job first.
      return Promise.resolve(jsonResponse({
        bundle_filename: info.display + '.zip',
        bundle_url: 'pending://push-job',
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
      client_request_id: uuid(),
      target_devices: legacyMessage.target_devices || [],
      mode: legacyMessage.delete_extras ? 'sync' : 'push',
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
      return nativeFetch(created.upload_url, { method: 'POST', body: staged.formData })
        .then(checkedJson)
        .then(function () { return created; });
    }).then(function (created) {
      nativeSend.call(socket, JSON.stringify({ type: 'PUSH_FILES', job_id: created.job_id }));
      appendLog('Dispatched job ' + created.job_id.slice(0, 8) + ' after immutable artifact publication', 'success');
    }).catch(function (error) {
      appendLog('Push/Sync job failed before dispatch: ' + error.message, 'fail');
    });
  }

  NativeWebSocket.prototype.send = function (data) {
    if (typeof data === 'string') {
      try {
        const message = JSON.parse(data);
        if (message && message.type === 'PUSH_FILES' && !message.job_id && pendingBundle) {
          const staged = pendingBundle;
          pendingBundle = null;
          createUploadDispatch(this, message, staged);
          return;
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
  }

  function aggregateText(job) {
    const a = job.aggregate || {};
    const running = (a.waiting_transfer || 0) + (a.dispatching || 0) +
      (a.downloading || 0) + (a.validating || 0) + (a.applying || 0);
    const failed = (a.failed || 0) + (a.interrupted || 0) + (a.unconfirmed || 0);
    return (a.succeeded || 0) + ' succeeded, ' + running + ' active, ' +
      (a.queued || 0) + ' queued' + (failed ? ', ' + failed + ' non-success' : '') +
      ' (of ' + (a.total || 0) + ')';
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
    const fenced = Object.keys(job.devices || {}).filter(function (id) {
      return job.devices[id] && job.devices[id].device_fence;
    });
    const verb = job.mode === 'sync' ? 'Sync' : 'Push';
    const terminal = ['succeeded', 'completed_with_errors', 'failed', 'interrupted'].indexOf(job.state) >= 0;
    entry.time.textContent = new Date().toLocaleTimeString();
    entry.body.className = 'log-msg ' + (job.state === 'succeeded' ? 'success' : (terminal && job.state !== 'succeeded' ? 'warn' : 'info'));
    entry.body.textContent = verb + ' #' + job.job_id.slice(0, 8) + ' → ' + job.dest_path +
      ': ' + job.state + '; ' + aggregateText(job) + (fenced.length ? '; fenced: ' + fenced.join(', ') : '');
    if (fenced.length) {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = 'Reconcile';
      button.style.marginLeft = '8px';
      button.addEventListener('click', function () {
        if (!currentAdminSocket || currentAdminSocket.readyState !== NativeWebSocket.OPEN) return;
        fenced.forEach(function (deviceId) {
          nativeSend.call(currentAdminSocket, JSON.stringify({ type: 'RECONCILE_PUSH_DEVICE', device_id: deviceId }));
        });
      });
      entry.body.appendChild(button);
    }
    container.scrollTop = container.scrollHeight;
  }

  function observeMessage(socket, event) {
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
      // Canonical PUSH_JOB_UPDATED already renders each concurrent job separately;
      // suppress the old single-push progress line that would overwrite a sibling.
      event.stopImmediatePropagation();
    }
  }

  function PatchedWebSocket(url, protocols) {
    const socket = protocols === undefined ? new NativeWebSocket(url) : new NativeWebSocket(url, protocols);
    if (String(url).indexOf('/ws/admin') >= 0) currentAdminSocket = socket;
    socket.addEventListener('message', function (event) { observeMessage(socket, event); });
    return socket;
  }
  PatchedWebSocket.prototype = NativeWebSocket.prototype;
  Object.setPrototypeOf(PatchedWebSocket, NativeWebSocket);
  ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (name) {
    Object.defineProperty(PatchedWebSocket, name, { value: NativeWebSocket[name] });
  });
  window.WebSocket = PatchedWebSocket;
})();
