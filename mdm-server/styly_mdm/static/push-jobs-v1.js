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
  const renderedDeviceIds = new Set();
  const submissions = new Map();
  const pendingDispatches = new Map();
  const pendingActions = new Map();
  const retryRequestIds = new Map();
  const pendingActionTimeoutMs = 15000;
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

  function bundleInfo(files) {
    files = Array.from(files || []);
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
    return { fileCount: files.length, display: display, bytes: bytes };
  }

  function formDataFor(files) {
    const formData = new FormData();
    files.forEach(function (file) {
      formData.append('files', file, file.webkitRelativePath || file.name);
    });
    return formData;
  }

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

  function submitPushJob(options) {
    const files = Array.from(options && options.files || []);
    const targets = Array.from(options && options.targets || []);
    const mode = options && options.mode;
    const destPath = String(options && options.destPath || '').trim();
    const requestId = options && options.clientRequestId || uuid();
    if (!files.length) return Promise.reject(new Error('No files selected'));
    if (!targets.length) return Promise.reject(new Error('No online devices selected'));
    if (!destPath) return Promise.reject(new Error('Destination path is required'));
    if (mode !== 'push' && mode !== 'sync') return Promise.reject(new Error('Invalid Push/Sync mode'));

    const info = bundleInfo(files);
    const fingerprint = JSON.stringify({
      files: files.map(function (file) {
        return [file.webkitRelativePath || file.name, file.size, file.lastModified];
      }),
      targets: targets,
      mode: mode,
      destPath: destPath,
    });
    let submission = submissions.get(requestId);
    if (submission && submission.fingerprint !== fingerprint) {
      return Promise.reject(new Error('This request ID already belongs to a different Push/Sync selection'));
    }
    if (!submission) {
      submission = {
        requestId: requestId,
        fingerprint: fingerprint,
        formData: null,
        info: info,
        targets: targets,
        mode: mode,
        destPath: destPath,
        phase: 'new',
        jobId: null,
        promise: null,
        dispatchResolve: null,
        dispatchReject: null,
        dispatchTimer: null,
        dispatchSocket: null,
      };
      submissions.set(requestId, submission);
    }
    if (submission.phase === 'terminal') {
      return Promise.reject(terminalSubmissionError(submission, submission.terminalState));
    }
    if (submission.phase === 'dispatched') {
      return Promise.resolve({ job_id: submission.jobId, state: 'dispatched' });
    }
    if (submission.promise) return submission.promise;
    if (submission.phase !== 'ready') submission.formData = formDataFor(files);

    function dispatchReady() {
      if (submission.phase === 'dispatched') {
        return Promise.resolve({ job_id: submission.jobId, state: 'dispatched' });
      }
      if (submission.phase === 'terminal') {
        throw terminalSubmissionError(submission, submission.terminalState);
      }
      if (!currentAdminSocket || currentAdminSocket.readyState !== NativeWebSocket.OPEN) {
        const error = new Error('Push/Sync job is ready; admin WebSocket is disconnected. Reconnect and retry to dispatch without uploading again.');
        error.code = 'dispatch_disconnected';
        error.job_id = submission.jobId;
        throw error;
      }
      return new Promise(function (resolve, reject) {
        submission.dispatchResolve = resolve;
        submission.dispatchReject = reject;
        submission.dispatchSocket = currentAdminSocket;
        pendingDispatches.set(submission.jobId, submission);
        submission.dispatchTimer = setTimeout(function () {
          settleDispatch(submission, {
            message: 'Dispatch was sent but no server acknowledgement arrived. Retry to dispatch the same ready job without uploading again.',
            code: 'dispatch_unconfirmed',
          });
        }, 15000);
        if (submission.dispatchTimer && typeof submission.dispatchTimer.unref === 'function') {
          submission.dispatchTimer.unref();
        }
        try {
          nativeSend.call(currentAdminSocket, JSON.stringify({
            type: 'PUSH_FILES',
            job_id: submission.jobId,
          }));
        } catch (error) {
          settleDispatch(submission, error);
        }
      });
    }

    function reconcileCreateState(created) {
      if (submission.jobId && submission.jobId !== created.job_id) {
        throw new Error('Idempotent Push/Sync request returned a different job ID');
      }
      submission.jobId = created.job_id;
      if (submission.phase === 'dispatched') return Promise.resolve();
      if (submission.phase === 'terminal') {
        throw terminalSubmissionError(submission, submission.terminalState);
      }
      if (isTerminalJobState(created.state)) {
        if (dispatchAccepted(created)) {
          submission.phase = 'dispatched';
          return Promise.resolve();
        }
        markSubmissionTerminal(submission, created.state);
        throw terminalSubmissionError(submission, created.state);
      }
      if (submission.phase === 'ready') return Promise.resolve();
      if (created.state === 'ready' || created.dispatch_enabled === true ||
          created.state === 'running' || created.state === 'reconciling') {
        setSubmissionPhase(submission, 'ready');
        return Promise.resolve();
      }
      if (created.state === 'created') {
        setSubmissionPhase(submission, 'uploading');
        return nativeFetch(created.upload_url, {
          method: 'POST', body: submission.formData,
        }).then(checkedJson).then(function (uploaded) {
          if (uploaded.state !== 'ready' && uploaded.dispatch_enabled !== true) {
            setSubmissionPhase(submission, 'upload_unknown');
            throw new Error('job is not ready after upload');
          }
          setSubmissionPhase(submission, 'ready');
        }, function (error) {
          // The request may have reached the server even when its response was lost.
          // A retry re-reads the idempotent create response before sending any bytes.
          setSubmissionPhase(submission, 'upload_unknown');
          throw error;
        });
      }
      if (created.state === 'uploading' || created.state === 'packaging') {
        setSubmissionPhase(submission, 'upload_unknown');
        const error = new Error('Upload is still being processed by the server. Keep the selection and retry after its job snapshot changes.');
        error.code = 'upload_in_progress';
        error.job_id = submission.jobId;
        throw error;
      }
      const error = new Error('Push/Sync job cannot be uploaded or dispatched in state: ' + created.state);
      error.job_id = submission.jobId;
      error.retryWithNewRequestId = true;
      throw error;
    }

    submission.promise = Promise.resolve().then(function () {
      if (submission.phase === 'ready') return;
      if (!currentAdminSocket || currentAdminSocket.readyState !== NativeWebSocket.OPEN) {
        throw new Error('Admin WebSocket is disconnected');
      }
      const source = submission.info;
      const createBody = {
        client_request_id: submission.requestId,
        target_devices: submission.targets,
        mode: submission.mode,
        dest_path: submission.destPath,
        source: {
          display_name: source.display,
          declared_file_count: source.fileCount,
          declared_total_bytes: source.bytes,
        },
      };
      appendLog('Creating Push/Sync job before upload…', 'info');
      return nativeFetch('/api/push-jobs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(createBody),
      }).then(checkedJson).then(reconcileCreateState);
    }).then(function () {
      return dispatchReady();
    }).catch(function (error) {
      appendLog('Push/Sync job ' + (submission.jobId ? '#' + submission.jobId.slice(0, 8) : 'request') +
        ' failed: ' + error.message, 'fail');
      throw error;
    }).finally(function () {
      // Keep selected File objects only while an upload request is active. A later
      // retry can rebuild FormData from the panel's retained selection.
      submission.formData = null;
      submission.promise = null;
      if (submission.released && submissions.get(requestId) === submission) {
        submissions.delete(requestId);
      }
    });
    return submission.promise;
  }

  function settleDispatch(submission, error) {
    if (!submission || typeof submission.dispatchResolve !== 'function') return false;
    if (submission.dispatchTimer) clearTimeout(submission.dispatchTimer);
    if (pendingDispatches.get(submission.jobId) === submission) {
      pendingDispatches.delete(submission.jobId);
    }
    const resolve = submission.dispatchResolve;
    const reject = submission.dispatchReject;
    submission.dispatchResolve = null;
    submission.dispatchReject = null;
    submission.dispatchTimer = null;
    submission.dispatchSocket = null;
    if (error) {
      const dispatchError = new Error(error.message || String(error));
      dispatchError.code = error.code || 'dispatch_failed';
      dispatchError.job_id = submission.jobId;
      if (error.retryWithNewRequestId) dispatchError.retryWithNewRequestId = true;
      reject(dispatchError);
    } else {
      submission.phase = 'dispatched';
      appendLog('Dispatched job ' + submission.jobId.slice(0, 8) +
        ' after immutable artifact publication', 'success');
      resolve({ job_id: submission.jobId, state: 'dispatched' });
    }
    return true;
  }

  function settleSubmissionFromJob(job) {
    if (!validJob(job)) return;
    submissions.forEach(function (submission) {
      if (submission.jobId !== job.job_id &&
          !(submission.jobId === null && job.client_request_id === submission.requestId)) return;
      submission.jobId = job.job_id;
      if (isTerminalJobState(job.state)) {
        if (dispatchAccepted(job)) {
          submission.phase = 'dispatched';
          settleDispatch(submission, null);
        } else {
          markSubmissionTerminal(submission, job.state);
          settleDispatch(submission, {
            message: 'Push/Sync job is already terminal (' + job.state + '). Retry as a new job.',
            code: 'submission_terminal',
            retryWithNewRequestId: true,
          });
        }
        return;
      }
      if (job.state === 'ready' || job.dispatch_enabled === true ||
          job.state === 'running' || job.state === 'reconciling') {
        setSubmissionPhase(submission, 'ready');
      }
      if (job.dispatch_enabled === true || job.state === 'running' ||
          job.state === 'reconciling') {
        settleDispatch(submission, null);
      }
    });
  }

  function isTerminalJobState(state) {
    return ['succeeded', 'completed_with_errors', 'failed', 'interrupted'].indexOf(state) >= 0;
  }

  function dispatchAccepted(job) {
    return job.dispatch_enabled === true ||
      job.state === 'succeeded' || job.state === 'completed_with_errors';
  }

  function markSubmissionTerminal(submission, state) {
    submission.phase = 'terminal';
    submission.terminalState = state;
  }

  function releaseSubmission(requestId) {
    const submission = submissions.get(requestId);
    if (!submission) return false;
    if (submission.promise) {
      submission.released = true;
      return true;
    }
    submissions.delete(requestId);
    return true;
  }

  function setSubmissionPhase(submission, phase) {
    if (submission.phase !== 'terminal' && submission.phase !== 'dispatched') {
      submission.phase = phase;
    }
  }

  function terminalSubmissionError(submission, state) {
    const error = new Error('Push/Sync job is already terminal' +
      (state ? ' (' + state + ')' : '') + '. Retry as a new job.');
    error.code = 'submission_terminal';
    error.job_id = submission.jobId;
    error.retryWithNewRequestId = true;
    return error;
  }

  function settlePendingAction(key) {
    const pending = pendingActions.get(key);
    if (!pending) return false;
    clearTimeout(pending.timer);
    pendingActions.delete(key);
    refreshPendingActionDevices(pending.targets);
    renderPausedJobs();
    return true;
  }

  function refreshPendingActionDevices(deviceIds) {
    const bridge = window.__stylyPushJobsV1Bridge;
    if (!bridge || typeof bridge.invalidateDevice !== 'function') return;
    Array.from(new Set(deviceIds || [])).forEach(function (deviceId) {
      bridge.invalidateDevice(deviceId);
    });
  }

  function actionKey(action, jobId, targets) {
    return action + ':' + jobId + ':' + Array.from(targets || []).slice().sort().join(',');
  }

  function isActionPending(action, jobId) {
    let found = false;
    pendingActions.forEach(function (pending) {
      if (pending.action === action && (!jobId || pending.jobId === jobId)) found = true;
    });
    return found;
  }

  function isDeviceActionPending(action, jobId, deviceId) {
    let found = false;
    pendingActions.forEach(function (pending) {
      if (pending.action === action && pending.jobId === jobId &&
          pending.targets.indexOf(deviceId) >= 0) found = true;
    });
    return found;
  }

  function trackAction(action, jobId, targets, clientRequestId, shouldRender) {
    const key = actionKey(action, jobId, targets);
    if (pendingActions.has(key)) return false;
    const pending = {
      key: key,
      action: action,
      jobId: jobId,
      targets: Array.from(targets || []),
      clientRequestId: clientRequestId || null,
      socket: currentAdminSocket,
      timer: null,
    };
    pending.timer = setTimeout(function () { settlePendingAction(key); }, pendingActionTimeoutMs);
    if (pending.timer && typeof pending.timer.unref === 'function') pending.timer.unref();
    pendingActions.set(key, pending);
    refreshPendingActionDevices(pending.targets);
    if (shouldRender !== false) renderPausedJobs();
    return true;
  }

  function settleActionsFromJob(job) {
    pendingActions.forEach(function (pending, key) {
      if (pending.action === 'retry') {
        if (job.client_request_id === pending.clientRequestId) settlePendingAction(key);
        return;
      }
      if (pending.jobId !== job.job_id) return;
      const assignments = pending.targets.map(function (deviceId) {
        return job.devices && job.devices[deviceId];
      });
      if (pending.action === 'resume' && assignments.every(function (assignment) {
        return !!assignment && assignment.resume_required !== true;
      })) settlePendingAction(key);
      else if (pending.action === 'cancel' && assignments.every(function (assignment) {
        return !!assignment && (assignment.cancel_requested === true ||
          ['succeeded', 'failed', 'interrupted'].indexOf(assignment.state) >= 0);
      })) settlePendingAction(key);
      else if (pending.action === 'reconcile' && assignments.every(function (assignment) {
        return !!assignment && !assignment.device_fence;
      })) settlePendingAction(key);
    });
  }

  function clearPendingActionsOnDisconnect(socket) {
    Array.from(pendingActions.values()).filter(function (pending) {
      return pending.socket === socket;
    }).forEach(function (pending) { settlePendingAction(pending.key); });
    Array.from(pendingDispatches.values()).forEach(function (submission) {
      if (submission.dispatchSocket !== socket) return;
      settleDispatch(submission, {
        message: 'Admin WebSocket disconnected before the server confirmed dispatch. The ready job is retained; retry after reconnect.',
        code: 'dispatch_disconnected',
      });
    });
  }

  function validJob(job) {
    return !!job && !!job.job_id && typeof job.revision === 'number';
  }

  function applyJob(job) {
    if (!validJob(job)) return;
    const current = pushJobs.get(job.job_id);
    if (current && current.revision >= job.revision) return;
    pushJobs.set(job.job_id, job);
    settleSubmissionFromJob(job);
    settleActionsFromJob(job);
    pruneRetryRequestIds(pushJobs);
    renderJob(job);
    renderPausedJobs();
    const affectedDevices = new Set(Object.keys(job.devices || {}));
    if (current) Object.keys(current.devices || {}).forEach(function (deviceId) {
      affectedDevices.add(deviceId);
    });
    syncDeviceAssignments(affectedDevices);
  }

  function bufferJob(job) {
    if (!validJob(job)) return;
    const current = bufferedUpdates.get(job.job_id);
    if (!current || current.revision < job.revision) {
      bufferedUpdates.set(job.job_id, job);
    }
  }

  function pruneRetryRequestIds(jobs) {
    retryRequestIds.forEach(function (_requestId, key) {
      const separator = key.lastIndexOf(':');
      const jobId = key.slice(0, separator);
      const revision = Number(key.slice(separator + 1));
      const current = jobs.get(jobId);
      if (!current || (Number.isFinite(revision) && revision < current.revision)) {
        retryRequestIds.delete(key);
      }
    });
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
    pruneRetryRequestIds(replacement);

    jobEntries.forEach(function (entry, jobId) {
      if (replacement.has(jobId)) return;
      if (entry.root && entry.root.isConnected) entry.root.remove();
      jobEntries.delete(jobId);
    });
    pushJobs.clear();
    replacement.forEach(function (job, jobId) { pushJobs.set(jobId, job); });
    pushJobs.forEach(function (job) {
      settleSubmissionFromJob(job);
      settleActionsFromJob(job);
      renderJob(job);
    });
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

  // Operator-action rules live on the server (push_jobs.assignment_actions); the
  // console reads the snapshot flags and only adds live connectivity.
  function needsDispatchAction(job) {
    return ['ready', 'running', 'reconciling'].indexOf(job.state) >= 0 &&
      Object.values(job.devices || {}).some(function (d) { return d.resume_required === true; });
  }

  function isDeviceOnline(deviceId) {
    const bridge = window.__stylyPushJobsV1Bridge;
    return !!(bridge && bridge.isDeviceOnline && bridge.isDeviceOnline(deviceId));
  }

  // A job stays listed for review while any target is retryable, as offline
  // Resume targets do; the Retry command itself needs a connected target.
  function hasRetryableAssignments(job) {
    return Object.values(job.devices || {}).some(function (d) { return d.retryable === true; });
  }

  function hasRetryTargets(job) {
    return Object.keys(job.devices || {}).some(function (deviceId) {
      return job.devices[deviceId].retryable === true && isDeviceOnline(deviceId);
    });
  }

  function needsFenceReconciliation(assignment) {
    // Retry creates new work but does not prove that the old worker has stopped.
    return !!assignment.device_fence && !assignment.cancel_requested;
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
    if (isActionPending(action)) return false;
    const groups = bulkActionGroups(action);
    if (!groups.length || !currentAdminSocket || currentAdminSocket.readyState !== NativeWebSocket.OPEN) return false;
    const messageType = action === 'resume' ? 'PUSH_FILES' : 'CANCEL_PUSH_JOB';
    const label = action === 'resume' ? 'Resume all' : 'Cancel all';
    const targetCount = groups.reduce(function (count, group) {
      return count + group.targetDevices.length;
    }, 0);
    const scope = targetCount + ' device(s) across ' + groups.length + ' job(s)';
    if (action === 'cancel' && typeof window.confirm === 'function' &&
        !window.confirm('Cancel Push / Sync for ' + scope + '?')) return false;
    try {
      groups.forEach(function (group) {
        const key = actionKey(action, group.job.job_id, group.targetDevices);
        if (!trackAction(action, group.job.job_id, group.targetDevices, null, false)) return;
        try {
          nativeSend.call(currentAdminSocket, JSON.stringify({
            type: messageType,
            job_id: group.job.job_id,
            target_devices: group.targetDevices,
          }));
        } catch (error) {
          settlePendingAction(key);
          throw error;
        }
      });
      renderPausedJobs();
    } catch (error) {
      appendLog('Could not ' + label.toLowerCase() + ': ' + error.message, 'fail');
      return false;
    }
    appendLog(label + ' requested for ' + scope, 'info');
    return true;
  }

  function sendRetryJobAction(job) {
    if (!currentAdminSocket || currentAdminSocket.readyState !== NativeWebSocket.OPEN) return false;
    if (isActionPending('retry')) return false;
    const key = job.job_id + ':' + job.revision;
    if (!retryRequestIds.has(key)) retryRequestIds.set(key, uuid());
    const clientRequestId = retryRequestIds.get(key);
    if (!trackAction('retry', job.job_id, actionTargetsForJob(job, 'retry'), clientRequestId)) return false;
    const payload = {
      type: 'RETRY_FAILED_PUSH_JOB',
      job_id: job.job_id,
      client_request_id: clientRequestId,
    };
    try {
      nativeSend.call(currentAdminSocket, JSON.stringify(payload));
    } catch (error) {
      settlePendingAction(actionKey('retry', job.job_id, actionTargetsForJob(job, 'retry')));
      appendLog('Could not retry failed devices: ' + error.message, 'fail');
      return false;
    }
    return true;
  }

  function renderAttentionTabActions(paused, resumeGroups, cancelGroups) {
    const container = document.getElementById('pushJobsTabActions');
    if (!container) return;
    container.replaceChildren();
    if (!paused.length) return;

    resumeGroups = resumeGroups || bulkActionGroups('resume');
    cancelGroups = cancelGroups || bulkActionGroups('cancel');
    const resumeTargets = resumeGroups.reduce(function (count, group) {
      return count + group.targetDevices.length;
    }, 0);
    const cancelTargets = cancelGroups.reduce(function (count, group) {
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
    note.textContent = 'Apply an action to every eligible device across all Push / Sync jobs.';
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
      button.disabled = targetCount === 0 || isActionPending(action);
      if (isActionPending(action)) button.textContent = pendingLabel;
      button.addEventListener('click', function () {
        if (!sendBulkAction(action)) return;
      });
      return button;
    }

    buttons.appendChild(bulkButton('resume', 'Resume all', 'Resuming…', resumeTargets));
    buttons.appendChild(bulkButton('cancel', 'Cancel all', 'Cancelling…', cancelTargets));
    head.appendChild(copy);
    head.appendChild(buttons);
    container.appendChild(head);

    const actionJobs = paused.filter(function (job) {
      return hasRetryableAssignments(job) || Object.keys(job.devices || {}).some(function (deviceId) {
        const assignment = job.devices[deviceId];
        return needsFenceReconciliation(assignment);
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
      if (hasRetryableAssignments(job)) {
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.className = 'push-job-action';
        retry.textContent = 'Retry failed devices';
        if (isActionPending('retry', job.job_id)) {
          retry.disabled = true;
          retry.textContent = 'Retry requested…';
        } else if (!hasRetryTargets(job)) {
          retry.disabled = true;
          retry.title = 'Every device to retry is offline';
        }
        retry.addEventListener('click', function () {
          sendRetryJobAction(job, retry);
        });
        rowButtons.appendChild(retry);
      }
      const fenced = Object.keys(job.devices || {}).filter(function (deviceId) {
        const assignment = job.devices[deviceId];
        return needsFenceReconciliation(assignment);
      });
      if (fenced.length) {
        const reconcile = document.createElement('button');
        reconcile.type = 'button';
        reconcile.className = 'push-job-action';
        reconcile.textContent = 'Reconcile';
        if (isActionPending('reconcile', job.job_id)) {
          reconcile.disabled = true;
          reconcile.textContent = 'Reconciling…';
        }
        reconcile.addEventListener('click', function () {
          if (!currentAdminSocket || currentAdminSocket.readyState !== NativeWebSocket.OPEN) return;
          try {
            fenced.forEach(function (deviceId) {
              const key = actionKey('reconcile', job.job_id, [deviceId]);
              if (!trackAction('reconcile', job.job_id, [deviceId], null, false)) return;
              try {
                nativeSend.call(currentAdminSocket, JSON.stringify({
                  type: 'RECONCILE_PUSH_DEVICE',
                  device_id: deviceId,
                }));
              } catch (error) {
                settlePendingAction(key);
                throw error;
              }
            });
            renderPausedJobs();
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
      return needsDispatchAction(job) || hasRetryableAssignments(job) ||
        Object.values(job.devices || {}).some(needsFenceReconciliation);
    });
    const resumeGroups = bulkActionGroups('resume');
    const cancelGroups = bulkActionGroups('cancel');
    renderAttentionTabActions(paused, resumeGroups, cancelGroups);
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
        (candidate.assignment.state === 'unconfirmed' &&
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
    if (state === 'validating' || state === 'applying') return state;
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
    const resumeRequired = assignment.resume_required === true;
    const online = isDeviceOnline(deviceId);
    const resumePending = isDeviceActionPending('resume', job.job_id, deviceId);
    const cancelPending = isDeviceActionPending('cancel', job.job_id, deviceId);
    const canResume = resumeRequired && online;
    // The server refuses to cancel a reconciling assignment while its device is
    // online, because that device can still report its outcome.
    const canCancel = assignment.cancellable === true &&
      !(online && assignment.state === 'reconciling');
    const needsAttention = needsFenceReconciliation(assignment) ||
      (!assignment.cancel_requested && !assignment.retry_job_id && !success && !cancelled && (resumeRequired ||
        assignment.state === 'reconciling' || assignment.retryable === true));
    return {
      device_id: deviceId,
      canResume: canResume,
      canCancel: canCancel,
      resumePending: resumePending,
      cancelPending: cancelPending,
      needsAttention: !!needsAttention,
      job_id: job.job_id,
      client_request_id: job.client_request_id,
      revision: job.revision,
      enqueue_seq: assignment.enqueue_seq,
      status: cancelled ? 'cancelled' : (assignment.cancel_requested && (!terminal || (assignment.state === 'unconfirmed' && assignment.device_fence))) ? 'cancel_pending' :
        resumeRequired ? 'resume_required' : displayStatus(assignment.state),
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
    submitPushJob: submitPushJob,
    newSubmissionId: uuid,
    releaseSubmission: releaseSubmission,
    actionPending: isDeviceActionPending,
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
      if (isDeviceActionPending(action, jobId, deviceId)) return false;
      if (!currentAdminSocket || currentAdminSocket.readyState !== NativeWebSocket.OPEN) return false;
      if (action === 'cancel' && typeof window.confirm === 'function' &&
          !window.confirm('Cancel Push / Sync for ' + deviceId + '?')) return false;
      if (!trackAction(action, jobId, [deviceId], null, false)) return false;
      try {
        nativeSend.call(currentAdminSocket, JSON.stringify({
          type: action === 'resume' ? 'PUSH_FILES' : 'CANCEL_PUSH_JOB',
          job_id: jobId,
          target_devices: [deviceId],
        }));
        renderPausedJobs();
        return true;
      } catch (error) {
        settlePendingAction(actionKey(action, jobId, [deviceId]));
        appendLog('Could not ' + action + ' device job: ' + error.message, 'fail');
        return false;
      }
    },
  };

  function syncDeviceAssignments(affectedDeviceIds) {
    const bridge = window.__stylyPushJobsV1Bridge;
    if (!bridge) return;
    const fullRefresh = !affectedDeviceIds;
    const deviceIds = fullRefresh ? new Set(renderedDeviceIds) : new Set(affectedDeviceIds);
    if (fullRefresh) {
      renderedDeviceIds.clear();
      pushJobs.forEach(function (job) {
        Object.keys(job.devices || {}).forEach(function (deviceId) {
          deviceIds.add(deviceId);
          renderedDeviceIds.add(deviceId);
        });
      });
    } else {
      deviceIds.forEach(function (deviceId) {
        if (selectedAssignmentFor(deviceId)) renderedDeviceIds.add(deviceId);
        else renderedDeviceIds.delete(deviceId);
      });
    }
    // Invalidate removed assignments too, so a replacement snapshot reveals any
    // independent Install/Verify result without clearing it.
    deviceIds.forEach(function (deviceId) { bridge.invalidateDevice(deviceId); });
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
    } else if (message.type === 'PUSH_JOB_ACTION_SENT') {
      // Acknowledgement for job-level operator actions owned by this adapter.
      event.stopImmediatePropagation();
      if (socket !== currentAdminSocket) return;
      const shortId = String(message.job_id || '').slice(0, 8);
      if (message.action === 'CANCEL_PUSH_JOB') {
        const cancels = Array.from(pendingActions.values()).filter(function (pending) {
          return pending.action === 'cancel' && pending.jobId === message.job_id;
        });
        if (cancels.length === 1) settlePendingAction(cancels[0].key);
        appendLog('Cancel recorded for job #' + shortId +
          '; affected devices settle once they confirm the work is absent', 'info');
      } else if (message.action === 'RETRY_FAILED_PUSH_JOB') {
        const retries = Array.from(pendingActions.values()).filter(function (pending) {
          return pending.action === 'retry';
        });
        if (retries.length === 1) settlePendingAction(retries[0].key);
        appendLog('Created retry job #' + shortId + ' for unsuccessful devices', 'info');
      }
    } else if (message.type === 'PUSH_FILES_SENT' && message.job_id) {
      if (socket !== currentAdminSocket) return;
      const submission = pendingDispatches.get(message.job_id);
      if (submission) settleDispatch(submission, null);
      if (message.resume === true) {
        const resumes = Array.from(pendingActions.values()).filter(function (pending) {
          return pending.action === 'resume' && pending.jobId === message.job_id &&
            pending.targets.length === message.target_count;
        });
        if (resumes.length === 1) settlePendingAction(resumes[0].key);
      }
    } else if (message.type === 'ERROR') {
      if (socket !== currentAdminSocket) return;
      // ERROR currently contains only a message, so it cannot safely settle a
      // specific action. The legacy log handler shows the error; canonical or
      // typed acknowledgements and the timeout settle pending state.
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
    socket.addEventListener('close', function () {
      clearPendingActionsOnDisconnect(socket);
    });
    socket.addEventListener('open', function () {
      if (socket === currentAdminSocket) renderPausedJobs();
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
