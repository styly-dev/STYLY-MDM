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
  const renderedAssignments = new Map();
  const pendingUploads = new Map();
  const retryRequestIds = new Map();
  let currentAdminSocket = null;
  let awaitingInitialSnapshot = false;
  let bufferedUpdates = new Map();

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

  function createUploadDispatch(legacyMessage, staged) {
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
      if (!currentAdminSocket || currentAdminSocket.readyState !== NativeWebSocket.OPEN) {
        throw new Error('admin WebSocket is not connected');
      }
      nativeSend.call(currentAdminSocket, JSON.stringify({
        type: 'PUSH_FILES',
        job_id: ready.job_id,
      }));
      appendLog('Dispatched job ' + ready.job_id.slice(0, 8) +
        ' after immutable artifact publication', 'success');
    }).catch(function (error) {
      const bridge = window.__stylyPushJobsV1Bridge;
      if (bridge && typeof bridge.clearPendingRequest === 'function') {
        bridge.clearPendingRequest(staged.requestId);
      }
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
            createUploadDispatch(message, staged);
            return;
          }
        }
      } catch (_ignored) {}
    }
    return nativeSend.call(this, data);
  };

  function validJob(job) {
    return !!job && !!job.job_id && typeof job.revision === 'number';
  }

  function applyJob(job) {
    if (!validJob(job)) return;
    const current = pushJobs.get(job.job_id);
    if (current && current.revision >= job.revision) return;
    pushJobs.set(job.job_id, job);
    renderJob(job);
    renderPausedJobs();
    syncDeviceAssignments();
  }

  function bufferJob(job) {
    if (!validJob(job)) return;
    const current = bufferedUpdates.get(job.job_id);
    if (!current || current.revision < job.revision) {
      bufferedUpdates.set(job.job_id, job);
    }
  }

  function replaceJobs(jobs) {
    const replacement = new Map();
    (Array.isArray(jobs) ? jobs : []).forEach(function (job) {
      if (!validJob(job)) return;
      const current = replacement.get(job.job_id);
      if (!current || current.revision < job.revision) replacement.set(job.job_id, job);
    });
    bufferedUpdates.forEach(function (job, jobId) {
      const current = replacement.get(jobId);
      if (!current || current.revision < job.revision) replacement.set(jobId, job);
    });
    bufferedUpdates = new Map();

    jobEntries.forEach(function (entry, jobId) {
      if (replacement.has(jobId)) return;
      if (entry.root && entry.root.isConnected) entry.root.remove();
      jobEntries.delete(jobId);
    });
    pushJobs.clear();
    replacement.forEach(function (job, jobId) { pushJobs.set(jobId, job); });
    pushJobs.forEach(renderJob);
    renderPausedJobs();
    syncDeviceAssignments();
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
    container.scrollTop = container.scrollHeight;
  }

  function needsDispatchAction(job) {
    const terminal = ['succeeded', 'completed_with_errors', 'failed', 'interrupted']
      .indexOf(job.state) >= 0;
    return !terminal && ['ready', 'running', 'reconciling'].indexOf(job.state) >= 0 &&
      Object.values(job.devices || {}).some(function (d) {
        return !d.cancel_requested && !d.retry_job_id && ['succeeded', 'failed', 'interrupted', 'unconfirmed'].indexOf(d.state) < 0 &&
          (job.dispatch_enabled === false ||
           (['queued', 'reconciling'].indexOf(d.state) >= 0 &&
            ['download_retry_exhausted', 'dispatch_paused', 'client_restarted'].indexOf(d.queue_reason) >= 0));
      });
  }

  function hasRetryTargets(job) {
    return Object.values(job.devices || {}).some(function (d) {
      return !d.cancel_requested && !d.retry_job_id && ['failed', 'interrupted', 'unconfirmed'].indexOf(d.state) >= 0 &&
        (!d.failure || d.failure.code !== 'cancelled');
    });
  }

  function actionTargetsForJob(job, action) {
    return Object.keys(job.devices || {}).filter(function (deviceId) {
      const assignment = job.devices[deviceId];
      const view = assignmentView({ job: job, assignment: assignment }, deviceId);
      return action === 'resume' ? view.canResume : action === 'cancel' ? view.canCancel : false;
    });
  }

  function bulkActionGroups(action) {
    return Array.from(pushJobs.values()).map(function (job) {
      return { job: job, targetDevices: actionTargetsForJob(job, action) };
    }).filter(function (group) {
      return group.targetDevices.length > 0;
    });
  }

  function sendBulkAction(action) {
    const groups = bulkActionGroups(action);
    if (!groups.length || !currentAdminSocket || currentAdminSocket.readyState !== NativeWebSocket.OPEN) return false;
    const messageType = action === 'resume' ? 'PUSH_FILES' : 'CANCEL_PUSH_JOB';
    const label = action === 'resume' ? 'Resume all' : 'Cancel all';
    const targetCount = groups.reduce(function (count, group) {
      return count + group.targetDevices.length;
    }, 0);
    if (action === 'cancel' && typeof window.confirm === 'function' &&
        !window.confirm('Cancel Push / Sync for ' + targetCount + ' device(s)?')) return false;
    try {
      groups.forEach(function (group) {
        nativeSend.call(currentAdminSocket, JSON.stringify({
          type: messageType,
          job_id: group.job.job_id,
          target_devices: group.targetDevices,
        }));
      });
    } catch (error) {
      appendLog('Could not ' + label.toLowerCase() + ': ' + error.message, 'fail');
      return false;
    }
    appendLog(label + ' requested for ' + targetCount + ' device(s)', 'info');
    return true;
  }

  function sendJobAttentionAction(job, type, button, label) {
    if (!currentAdminSocket || currentAdminSocket.readyState !== NativeWebSocket.OPEN) return false;
    const payload = { type: type, job_id: job.job_id };
    if (type === 'RETRY_FAILED_PUSH_JOB') {
      const key = job.job_id + ':' + job.revision;
      if (!retryRequestIds.has(key)) retryRequestIds.set(key, uuid());
      payload.client_request_id = retryRequestIds.get(key);
    }
    try {
      nativeSend.call(currentAdminSocket, JSON.stringify(payload));
    } catch (error) {
      appendLog('Could not ' + label.toLowerCase() + ': ' + error.message, 'fail');
      return false;
    }
    button.disabled = true;
    setTimeout(function () {
      if (button.isConnected) button.disabled = false;
    }, 5000);
    return true;
  }

  function renderAttentionTabActions(paused) {
    const container = document.getElementById('pushJobsTabActions');
    if (!container) return;
    container.replaceChildren();
    container.style.display = paused.length ? '' : 'none';
    if (!paused.length) return;

    const resumeTargets = bulkActionGroups('resume').reduce(function (count, group) {
      return count + group.targetDevices.length;
    }, 0);
    const cancelTargets = bulkActionGroups('cancel').reduce(function (count, group) {
      return count + group.targetDevices.length;
    }, 0);
    const head = document.createElement('div');
    head.className = 'attention-tab-actions-head';
    const copy = document.createElement('div');
    copy.className = 'attention-tab-actions-copy';
    const title = document.createElement('div');
    title.className = 'attention-tab-actions-title';
    title.textContent = 'Push / Sync actions';
    const note = document.createElement('div');
    note.className = 'attention-tab-actions-note';
    note.textContent = 'Apply an action to every eligible device in this list.';
    copy.appendChild(title);
    copy.appendChild(note);
    const buttons = document.createElement('div');
    buttons.className = 'attention-tab-actions-buttons';

    function bulkButton(action, label, pendingLabel, targetCount) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'push-job-action attention-tab-action';
      button.dataset.action = action;
      button.textContent = label;
      button.title = targetCount + ' eligible device(s)';
      button.disabled = targetCount === 0;
      button.addEventListener('click', function () {
        if (!sendBulkAction(action)) return;
        button.disabled = true;
        button.textContent = pendingLabel;
        setTimeout(function () {
          if (!button.isConnected) return;
          button.disabled = action === 'resume'
            ? bulkActionGroups('resume').length === 0
            : bulkActionGroups('cancel').length === 0;
          button.textContent = label;
        }, 5000);
      });
      return button;
    }

    buttons.appendChild(bulkButton('resume', 'Resume all', 'Resuming…', resumeTargets));
    buttons.appendChild(bulkButton('cancel', 'Cancel all', 'Cancelling…', cancelTargets));
    head.appendChild(copy);
    head.appendChild(buttons);
    container.appendChild(head);

    const actionJobs = paused.filter(function (job) {
      return hasRetryTargets(job) || Object.keys(job.devices || {}).some(function (deviceId) {
        const assignment = job.devices[deviceId];
        return !!assignment.device_fence && !assignment.cancel_requested && !assignment.retry_job_id;
      });
    });
    if (!actionJobs.length) return;
    const jobList = document.createElement('div');
    jobList.className = 'attention-tab-job-list';
    actionJobs.forEach(function (job) {
      const row = document.createElement('div');
      row.className = 'attention-tab-job';
      const label = document.createElement('span');
      label.className = 'attention-tab-job-label';
      label.textContent = (job.mode === 'sync' ? 'Sync' : 'Push') + ' #' +
        job.job_id.slice(0, 8) + ' → ' + job.dest_path;
      const rowButtons = document.createElement('div');
      rowButtons.className = 'attention-tab-job-buttons';
      if (hasRetryTargets(job)) {
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.className = 'push-job-action';
        retry.textContent = 'Retry failed devices';
        retry.addEventListener('click', function () {
          sendJobAttentionAction(job, 'RETRY_FAILED_PUSH_JOB', retry, 'Retry failed devices');
        });
        rowButtons.appendChild(retry);
      }
      const fenced = Object.keys(job.devices || {}).filter(function (deviceId) {
        const assignment = job.devices[deviceId];
        return !!assignment.device_fence && !assignment.cancel_requested && !assignment.retry_job_id;
      });
      if (fenced.length) {
        const reconcile = document.createElement('button');
        reconcile.type = 'button';
        reconcile.className = 'push-job-action';
        reconcile.textContent = 'Reconcile';
        reconcile.addEventListener('click', function () {
          if (!currentAdminSocket || currentAdminSocket.readyState !== NativeWebSocket.OPEN) return;
          try {
            fenced.forEach(function (deviceId) {
              nativeSend.call(currentAdminSocket, JSON.stringify({
                type: 'RECONCILE_PUSH_DEVICE',
                device_id: deviceId,
              }));
            });
            reconcile.disabled = true;
            setTimeout(function () { if (reconcile.isConnected) reconcile.disabled = false; }, 5000);
          } catch (error) {
            appendLog('Could not reconcile: ' + error.message, 'fail');
          }
        });
        rowButtons.appendChild(reconcile);
      }
      row.appendChild(label);
      row.appendChild(rowButtons);
      jobList.appendChild(row);
    });
    container.appendChild(jobList);
  }

  function renderPausedJobs() {
    const paused = Array.from(pushJobs.values()).filter(function (job) {
      return needsDispatchAction(job) || hasRetryTargets(job) ||
        Object.values(job.devices || {}).some(function (d) { return !!d.device_fence && !d.cancel_requested && !d.retry_job_id; });
    });
    renderAttentionTabActions(paused);
    const container = document.getElementById('pushJobsAttention');
    if (!container) return;
    container.replaceChildren();
    container.style.display = paused.length ? '' : 'none';
    if (!paused.length) return;

    const title = document.createElement('div');
    title.className = 'push-attention-title';
    title.textContent = 'Push / Sync jobs need attention';
    container.appendChild(title);
    const summary = document.createElement('div');
    summary.className = 'push-attention-summary';
    const message = document.createElement('span');
    message.className = 'push-attention-message';
    message.textContent = paused.length + ' Push / Sync job(s) need review in the Needs attention tab.';
    const open = document.createElement('button');
    open.type = 'button';
    open.className = 'push-job-action push-attention-open';
    open.textContent = 'Open Needs attention';
    open.addEventListener('click', function () {
      const tab = document.getElementById('tabAttention');
      if (tab) tab.click();
    });
    summary.appendChild(message);
    summary.appendChild(open);
    container.appendChild(summary);
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
      return activeStates.has(candidate.assignment.state) ||
        (candidate.assignment.cancel_requested && candidate.assignment.state === 'unconfirmed' &&
         !!candidate.assignment.device_fence);
    }).sort(function (left, right) {
      return left.assignment.enqueue_seq - right.assignment.enqueue_seq;
    });
    if (active.length) return active[0];
    const queued = candidates.filter(function (candidate) {
      return candidate.assignment.state === 'queued';
    }).sort(function (left, right) {
      return left.assignment.enqueue_seq - right.assignment.enqueue_seq;
    });
    if (queued.length) return queued[0];
    const terminalStates = new Set([
      'succeeded', 'failed', 'interrupted', 'unconfirmed',
    ]);
    const terminal = candidates.filter(function (candidate) {
      return terminalStates.has(candidate.assignment.state);
    }).sort(function (left, right) {
      const enqueueDelta = (right.assignment.enqueue_seq || 0) -
        (left.assignment.enqueue_seq || 0);
      if (enqueueDelta) return enqueueDelta;
      return (right.job.updated_at || 0) - (left.job.updated_at || 0);
    });
    return terminal.length ? terminal[0] : null;
  }

  function displayStatus(state) {
    if (['queued', 'waiting_transfer', 'dispatching'].indexOf(state) >= 0) {
      return 'queued';
    }
    if (state === 'downloading') return 'transferring';
    if (['validating', 'applying'].indexOf(state) >= 0) {
      return 'applying';
    }
    if (state === 'reconciling' || state === 'unconfirmed') return state;
    if (state === 'succeeded') return 'success';
    return 'fail';
  }

  function assignmentView(selected, deviceId) {
    const job = selected.job;
    const assignment = selected.assignment;
    const result = assignment.result || {};
    const failure = assignment.failure || {};
    const jobFailure = job.failure || {};
    const success = assignment.state === 'succeeded';
    const terminal = ['succeeded', 'failed', 'interrupted', 'unconfirmed'].indexOf(assignment.state) >= 0;
    const cancelled = failure.code === 'cancelled';
    const resumeRequired = !assignment.cancel_requested && !assignment.retry_job_id && !terminal &&
      ((['queued', 'reconciling'].indexOf(assignment.state) >= 0 &&
        ['download_retry_exhausted', 'dispatch_paused', 'client_restarted'].indexOf(assignment.queue_reason) >= 0) ||
       (job.dispatch_enabled === false && ['ready', 'running', 'reconciling'].indexOf(job.state) >= 0));
    const bridge = window.__stylyPushJobsV1Bridge;
    const online = !!(bridge && bridge.isDeviceOnline && bridge.isDeviceOnline(deviceId));
    const canResume = resumeRequired && online;
    const canCancel = !assignment.cancel_requested && !assignment.retry_job_id &&
      ((assignment.state === 'queued' && assignment.dispatch_revision != null &&
        ['download_retry_exhausted', 'dispatch_paused', 'client_restarted'].indexOf(assignment.queue_reason) >= 0) ||
       (!online && assignment.state === 'reconciling') ||
       (assignment.state === 'unconfirmed' && !!assignment.device_fence));
    const needsAttention = !assignment.cancel_requested && !assignment.retry_job_id && !success && !cancelled && (resumeRequired ||
      ['reconciling', 'unconfirmed', 'failed', 'interrupted'].indexOf(assignment.state) >= 0);
    return {
      device_id: deviceId,
      canResume: canResume,
      canCancel: canCancel,
      needsAttention: !!needsAttention,
      job_id: job.job_id,
      client_request_id: job.client_request_id,
      revision: job.revision,
      enqueue_seq: assignment.enqueue_seq,
      status: cancelled ? 'cancelled' : (assignment.cancel_requested && (!terminal || (assignment.state === 'unconfirmed' && assignment.device_fence))) ? 'cancel_pending' :
        ['queued', 'reconciling'].indexOf(assignment.state) >= 0 &&
        ['download_retry_exhausted', 'dispatch_paused', 'client_restarted'].indexOf(assignment.queue_reason) >= 0
          ? 'resume_required' : displayStatus(assignment.state),
      verb: job.mode === 'sync' ? 'Sync' : 'Push',
      filename: job.dest_path || '',
      note: success ? '+' + (result.added || 0) + ' ~' +
        (result.updated || 0) + ' -' + (result.deleted || 0) : '',
      detail: failure.detail || failure.code || jobFailure.detail || jobFailure.code ||
        assignment.reconciliation_reason ||
        ((assignment.state === 'unconfirmed') ? 'result unconfirmed' : ''),
    };
  }

  // Read directly from canonical snapshots; no second attention-state cache.
  window.__stylyPushJobsV1Actions = {
    refreshConnectivity: function () { renderPausedJobs(); syncDeviceAssignments(); },
    assignmentFor: function (deviceId) {
      const selected = selectedAssignmentFor(deviceId);
      return selected ? assignmentView(selected, deviceId) : null;
    },
    sendDeviceAction: function (deviceId, jobId, action) {
      const selected = selectedAssignmentFor(deviceId);
      if (!selected || selected.job.job_id !== jobId) return false;
      const view = assignmentView(selected, deviceId);
      if ((action === 'resume' && !view.canResume) ||
          (action === 'cancel' && !view.canCancel) ||
          ['resume', 'cancel'].indexOf(action) < 0) return false;
      if (!currentAdminSocket || currentAdminSocket.readyState !== NativeWebSocket.OPEN) return false;
      if (action === 'cancel' && typeof window.confirm === 'function' &&
          !window.confirm('Cancel Push / Sync for ' + deviceId + '?')) return false;
      try {
        nativeSend.call(currentAdminSocket, JSON.stringify({
          type: action === 'resume' ? 'PUSH_FILES' : 'CANCEL_PUSH_JOB',
          job_id: jobId,
          target_devices: [deviceId],
        }));
        return true;
      } catch (error) {
        appendLog('Could not ' + action + ' device job: ' + error.message, 'fail');
        return false;
      }
    },
  };

  function syncDeviceAssignments() {
    const bridge = window.__stylyPushJobsV1Bridge;
    if (!bridge) return;
    const deviceIds = new Set(renderedAssignments.keys());
    pushJobs.forEach(function (job) {
      Object.keys(job.devices || {}).forEach(function (deviceId) {
        deviceIds.add(deviceId);
      });
    });
    deviceIds.forEach(function (deviceId) {
      const selected = selectedAssignmentFor(deviceId);
      const renderedJobId = renderedAssignments.get(deviceId);
      if (selected) {
        if (bridge.applyAssignment(assignmentView(selected, deviceId))) {
          renderedAssignments.set(deviceId, selected.job.job_id);
        }
      } else {
        bridge.clearAssignment(deviceId, renderedJobId);
        renderedAssignments.delete(deviceId);
      }
    });
    if (typeof bridge.refreshAttention === 'function') bridge.refreshAttention();
  }

  function observeMessage(socket, event) {
    let message;
    try { message = JSON.parse(event.data); } catch (_ignored) { return; }
    if (!message || !message.type) return;
    if (message.type === 'PUSH_JOBS_SNAPSHOT') {
      event.stopImmediatePropagation();
      if (socket !== currentAdminSocket) return;
      replaceJobs(message.jobs);
      awaitingInitialSnapshot = false;
    } else if (message.type === 'PUSH_JOB_UPDATED') {
      event.stopImmediatePropagation();
      if (socket !== currentAdminSocket) return;
      if (awaitingInitialSnapshot) bufferJob(message.job);
      else applyJob(message.job);
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
    if (String(url).indexOf('/ws/admin') >= 0) {
      currentAdminSocket = socket;
      awaitingInitialSnapshot = true;
      bufferedUpdates = new Map();
    }
    socket.addEventListener('message', function (event) {
      observeMessage(socket, event);
    });
    return socket;
  }
  PatchedWebSocket.prototype = NativeWebSocket.prototype;
  Object.setPrototypeOf(PatchedWebSocket, NativeWebSocket);
  ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (name) {
    Object.defineProperty(PatchedWebSocket, name, { value: NativeWebSocket[name] });
  });
  window.WebSocket = PatchedWebSocket;
})();
