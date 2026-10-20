(function (root) {
  'use strict';

  const MAX_NETWORKS = 32;
  const SECURITY_MODES = ['OPEN', 'WPA_PSK'];
  const REASON_LABELS = {
    unsupported: 'Update this client before sending Wi-Fi settings.',
    permission_denied: 'This connection is not authorized to receive Wi-Fi settings.',
    socket_unavailable: 'The device connection closed before sending.',
    socket_replaced: 'The device connection changed before sending. Send again if needed.',
    socket_send_failed: 'The server could not confirm that the send completed.',
    dispatch_not_confirmed: 'The send is not yet confirmed.',
    superseded: 'A newer deployment replaced this attempt before it was sent.',
    state_persist_failed: 'The server could not save the delivery record. Delivery is unconfirmed.',
    server_restarted: 'The server restarted before the send was confirmed. It will not retry automatically.',
    legacy_result_untracked: 'Earlier application results are not used to confirm delivery.',
  };

  function normalizeNetworks(networks) {
    if (!Array.isArray(networks)) return [];
    return networks.map(function (network, index) {
      return {
        id: 'wifi-' + index,
        ssid: typeof network.ssid === 'string' ? network.ssid : '',
        security: typeof network.security === 'string' ? network.security : '',
        password: '',
        password_present: network.password_present === true,
        hidden: network.hidden === true,
      };
    });
  }

  function buildProfilePayload(networks, savedNetworks) {
    if (!Array.isArray(networks) || networks.length === 0) {
      return { error: 'Add at least one Wi-Fi network before saving.' };
    }
    if (networks.length > MAX_NETWORKS) {
      return { error: 'A group can contain at most ' + MAX_NETWORKS + ' Wi-Fi networks.' };
    }

    const seenSsids = new Set();
    const payload = [];
    for (let index = 0; index < networks.length; index++) {
      const network = networks[index];
      const ssid = typeof network.ssid === 'string' ? network.ssid : '';
      if (!ssid.trim()) return { error: 'Enter an SSID for Wi-Fi ' + (index + 1) + '.' };
      if (SECURITY_MODES.indexOf(network.security) < 0) {
        return { error: 'Choose Open or WPA/WPA2 Personal security for Wi-Fi ' + (index + 1) + '.' };
      }
      if (seenSsids.has(ssid)) return { error: 'Each Wi-Fi network must have a unique SSID.' };
      seenSsids.add(ssid);

      let password = '';
      if (network.security === 'WPA_PSK') {
        const enteredPassword = typeof network.password === 'string' ? network.password : '';
        if (enteredPassword) {
          password = enteredPassword;
        } else {
          const saved = (savedNetworks || []).find(function (candidate) {
            return candidate.ssid === ssid && candidate.security === network.security;
          });
          if (!saved || saved.password_present !== true) {
            return { error: 'Enter a password for Wi-Fi ' + (index + 1) + '.' };
          }
          password = null;
        }
      }

      payload.push({
        ssid: ssid,
        security: network.security,
        password: password,
        hidden: network.hidden === true,
      });
    }
    return { payload: { networks: payload } };
  }

  function membershipConflict(deviceId, groupName, groups, aliases) {
    const identity = [deviceId].concat(aliases || []).filter(Boolean);
    const names = Object.keys(groups || {}).sort();
    for (let i = 0; i < names.length; i++) {
      const name = names[i];
      if (name !== groupName && identity.some(function (id) { return (groups[name] || []).indexOf(id) >= 0; })) return name;
    }
    return null;
  }

  function deliveryStatus(deviceState, currentRevision) {
    const delivery = deviceState && deviceState.delivery;
    if (!delivery) return { label: 'Not sent', icon: '○', tone: 'muted' };
    const previous = delivery.revision && currentRevision && delivery.revision !== currentRevision;
    if (delivery.status === 'sent') return previous
      ? { label: 'Sent (previous profile)', icon: '○', tone: 'muted' }
      : { label: 'Sent', icon: '↑', tone: 'info' };
    if (delivery.status === 'failed') return { label: 'Send failed', icon: '×', tone: 'danger' };
    return { label: 'Send unconfirmed', icon: '?', tone: 'warning' };
  }

  function reasonLabel(result) {
    return result && Object.prototype.hasOwnProperty.call(REASON_LABELS, result.reason)
      ? REASON_LABELS[result.reason] : '';
  }

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (character) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character];
    });
  }

  function createController(options) {
    const authPanel = options.authPanel;
    const groupPanel = options.groupPanel;
    const getGroups = options.getGroups;
    const getDevices = options.getDevices;
    const confirmApply = options.confirmApply || root.confirm.bind(root);
    let enabled = null;
    let allowHttp = false;
    let accessRenderKey = null;
    let authState = 'unknown';
    let authMessage = '';
    let state = null;
    let stateUnavailable = false;
    let stateError = false;
    let stateLoading = false;
    let activeGroup = null;
    let activeMembers = [];
    let profiles = Object.create(null);
    let lastRenderedMembers = '';
    let pollHandle = null;
    let authRenderKey = null;
    let authGeneration = 0;

    function profileFor(name) {
      if (!profiles[name]) {
        profiles[name] = {
          loaded: false,
          loading: false,
          saving: false,
          applying: false,
          dirty: false,
          conflicted: false,
          error: '',
          savedRevision: null,
          mode: 'managed',
          savedMode: 'managed',
          savedNetworks: [],
          draftNetworks: [],
        };
      }
      return profiles[name];
    }

    function apiUrl(path) { return path; }

    function transportAllowed() {
      return root.location && (root.location.protocol === 'https:' ||
        (allowHttp && root.location.protocol === 'http:'));
    }

    function renderAccess() {
      renderAuth();
      const key = [enabled, stateUnavailable, authState, allowHttp].join('|');
      if (key !== accessRenderKey) {
        accessRenderKey = key;
        renderProfile();
      }
      updateButtons();
    }

    function renderAuth() {
      if (!authPanel) return;
      authPanel.hidden = enabled !== true;
      const secure = transportAllowed();
      const key = [enabled, stateUnavailable, authState, authMessage, secure].join('|');
      if (authPanel.dataset.wifiAuthKey === key) return;
      authPanel.dataset.wifiAuthKey = key;
      if (enabled === false || stateUnavailable) {
        authPanel.innerHTML = '<div class="wifi-banner wifi-muted"><span class="wifi-symbol">○</span>' +
          '<span>Wi-Fi management is unavailable on this server.</span></div>';
        return;
      }
      if (enabled !== true) {
        authPanel.innerHTML = '<div class="wifi-banner wifi-muted"><span class="wifi-symbol">○</span>' +
          '<span>Checking Wi-Fi management availability…</span></div>';
        return;
      }
      if (authState === 'authenticated') {
        authPanel.innerHTML = '<div class="wifi-banner wifi-success"><span class="wifi-symbol">✓</span>' +
          '<span>Wi-Fi management session active</span></div>' +
          (authMessage ? '<div class="wifi-feedback" role="status">' + escapeHtml(authMessage) + '</div>' : '');
        return;
      }

      authPanel.innerHTML = '<section class="wifi-auth"><div><strong>Administrator login</strong>' +
        '<p>Enter the admin token. It is cleared after the login request.</p></div>' +
        '<form data-wifi-login-form>' +
        '<input class="field wifi-token" data-wifi-token type="password" autocomplete="current-password" aria-label="Admin token" placeholder="Admin token"' +
        (secure ? '' : ' disabled') + '>' +
        '<button class="btn-mini btn-save" type="submit"' + (secure ? '' : ' disabled') + '>Log in</button></form>' +
        (secure ? '' : '<div class="wifi-feedback wifi-danger" role="alert">Use HTTPS, or explicitly enable managed-LAN HTTP on the server.</div>') +
        (authMessage ? '<div class="wifi-feedback wifi-danger" role="alert">' + escapeHtml(authMessage) + '</div>' : '') +
        '</section>';
    }

    function requestLogin() {
      authState = 'required';
      if (!authMessage) authMessage = '';
      renderAccess();
    }

    async function refreshState() {
      if (stateLoading) return;
      stateLoading = true;
      try {
        const requestedAuthGeneration = authGeneration;
        const response = await root.fetch(apiUrl('/api/wifi/state'), { credentials: 'same-origin' });
        if (response.status === 401) {
          if (requestedAuthGeneration !== authGeneration) return;
          const access = await response.json();
          allowHttp = access.allow_http === true;
          enabled = true;
          stateError = false;
          stateUnavailable = false;
          requestLogin();
          return;
        }
        if (response.status === 404 || response.status === 501) {
          enabled = false;
          stateError = false;
          stateUnavailable = true;
          renderAccess();
          return;
        }
        if (!response.ok) {
          stateUnavailable = false;
          stateError = true;
          renderTransientStatus();
          return;
        }
        const nextState = await response.json();
        enabled = nextState && nextState.enabled === true;
        allowHttp = nextState && nextState.allow_http === true;
        stateUnavailable = false;
        stateError = false;
        if (enabled) {
          state = nextState;
          if (Object.prototype.hasOwnProperty.call(nextState, 'groups') ||
              Object.prototype.hasOwnProperty.call(nextState, 'devices')) {
            authState = 'authenticated';
          }
        }
        renderAccess();
        renderTransientStatus();
        renderDeviceResults();
        if (activeGroup && enabled && authState !== 'required' && !profileFor(activeGroup).loaded) {
          await loadProfile(activeGroup, true);
        }
      } catch (_) {
        stateUnavailable = false;
        stateError = true;
        renderTransientStatus();
      } finally {
        stateLoading = false;
      }
    }

    function renderTransientStatus() {
      if (!groupPanel) return;
      let banner = groupPanel.querySelector('[data-wifi-transient]');
      if (!banner) return;
      banner.textContent = state
        ? 'Wi-Fi status is temporarily unavailable. Showing the last received results.'
        : 'Unable to load Wi-Fi status.';
      banner.style.display = stateError ? '' : 'none';
    }

    function renderProfile() {
      if (!groupPanel) return;
      if (!activeGroup) {
        groupPanel.innerHTML = '<section class="wifi-section"><h3>Wi-Fi settings</h3><p>Select a group to manage its Wi-Fi settings.</p></section>';
        return;
      }
      const name = activeGroup;
      const model = profileFor(name);
      const groupStatus = state && state.groups && state.groups[name];
      const currentRevision = groupStatus && groupStatus.revision;
      model.stale = model.conflicted || !!(currentRevision && model.savedRevision && currentRevision !== model.savedRevision);
      const rowHtml = model.draftNetworks.map(function (network, index) {
        const modes = SECURITY_MODES.indexOf(network.security) < 0
          ? ['unsupported'].concat(SECURITY_MODES) : SECURITY_MODES;
        const securityOptions = modes.map(function (mode) {
          if (mode === 'unsupported') {
            return '<option value="' + escapeHtml(network.security) + '" selected disabled>Unsupported: ' + escapeHtml(network.security) + '</option>';
          }
          return '<option value="' + mode + '"' + (network.security === mode ? ' selected' : '') + '>' +
            (mode === 'OPEN' ? 'Open' : 'WPA/WPA2 Personal (PSK)') + '</option>';
        }).join('');
        const secretHelp = network.security === 'WPA_PSK'
          ? (network.password_present
            ? '<small>Leave blank to keep the saved password. Enter a new password to replace it.</small>'
            : '<small>Required for this saved network.</small>')
          : network.security === 'OPEN'
            ? '<small>Open networks do not use a password.</small>'
            : '<small>This saved security type is unsupported. Choose a supported type before saving.</small>';
        return '<article class="wifi-network" data-wifi-row="' + escapeHtml(network.id) + '">' +
          '<div class="wifi-network-head"><strong>Wi-Fi ' + (index + 1) + '</strong>' +
          '<button class="btn-outline btn-danger-outline wifi-remove" type="button" data-wifi-action="remove" data-wifi-row="' + escapeHtml(network.id) + '"' +
          (model.draftNetworks.length <= 1 ? ' disabled' : '') + '>Remove</button></div>' +
          '<label>SSID<input class="field" data-wifi-row="' + escapeHtml(network.id) + '" data-wifi-field="ssid" type="text" autocomplete="off" value="' + escapeHtml(network.ssid) + '"></label>' +
          '<div class="wifi-fields"><label>Security<select class="field" data-wifi-row="' + escapeHtml(network.id) + '" data-wifi-field="security">' + securityOptions + '</select></label>' +
          '<label>Password<input class="field" data-wifi-row="' + escapeHtml(network.id) + '" data-wifi-field="password" type="password" autocomplete="new-password" value=""></label></div>' +
          '<div class="wifi-row-foot">' + secretHelp + '<label class="wifi-check"><input type="checkbox" data-wifi-row="' + escapeHtml(network.id) + '" data-wifi-field="hidden"' +
          (network.hidden ? ' checked' : '') + '> Hidden network</label></div></article>';
      }).join('');

      const revisionText = model.savedRevision ? (model.savedMode === 'unmanaged' ? 'Saved: Do not manage Wi-Fi' : 'Saved profile') : 'No saved profile';
      const busy = model.loading || model.saving || model.applying;
      const profileError = model.error ? '<div class="wifi-feedback wifi-danger" role="alert">' + escapeHtml(model.error) + '</div>' : '';
      const unavailable = enabled === false || stateUnavailable
        ? '<div class="wifi-feedback wifi-muted">Wi-Fi management is unavailable on this server.</div>' : '';
      const authNeeded = enabled === true && authState !== 'authenticated'
        ? '<div class="wifi-feedback wifi-muted">Log in above to view or edit this group’s Wi-Fi settings.</div>' : '';
      const loading = model.loading && !model.loaded ? '<div class="wifi-feedback">Loading saved Wi-Fi settings…</div>' : '';
      const editable = model.loaded && enabled === true && authState === 'authenticated';
      groupPanel.innerHTML = '<section class="wifi-section"><div class="wifi-section-head"><div><h3>Wi-Fi settings</h3>' +
        '<p>Save this group’s networks, then send the profile to its online devices.</p>' +
        '<p>Devices handle connection checks and rollback locally. The server tracks sending only.</p>' +
        '<p class="wifi-meta">' + revisionText + '</p></div>' +
        (model.loaded && enabled === true && authState === 'authenticated' && model.mode !== 'unmanaged'
          ? '<button class="btn-outline btn-danger-outline" type="button" data-wifi-action="unmanage">' + (model.savedRevision ? 'Delete Wi-Fi settings' : 'Do not manage Wi-Fi') + '</button>' : '') + '</div>' +
        '<div data-wifi-transient class="wifi-feedback" role="status" style="display:none"></div>' +
        '<div data-wifi-stale class="wifi-feedback wifi-danger" role="alert" style="display:none">The saved profile changed on the server. Reload it before saving or sending. <button class="btn-text" type="button" data-wifi-action="reload">Reload saved profile</button></div>' +
        profileError + unavailable + authNeeded + loading +
        (editable ? '<div class="wifi-network-list">' + (model.mode === 'unmanaged'
          ? '<div class="push-note">Existing Wi-Fi networks on devices stay in place. Deploy this state explicitly to stop reapplying Wi-Fi settings at device startup. Offline devices keep their previous management policy until you deploy to them while connected.</div>'
          : (rowHtml || '<div class="wifi-empty">No networks saved yet.</div>')) + '</div>' +
          '<div class="wifi-actions"><button class="btn-outline" type="button" data-wifi-action="add"' + (model.draftNetworks.length >= MAX_NETWORKS || busy ? ' disabled' : '') + '>Add Wi-Fi network</button>' +
          '<div class="wifi-action-right"><span class="wifi-meta">' + (model.dirty ? 'Unsaved changes' : model.savedRevision ? 'Saved' : 'Not saved') + '</span>' +
          (model.mode === 'managed' ? '<button class="btn btn-primary" type="button" data-wifi-action="save"' + (!model.dirty || busy ? ' disabled' : '') + '>Save Wi-Fi settings</button>' : '') +
          '<button class="btn btn-success" type="button" data-wifi-action="apply"' + (model.dirty || !model.savedRevision || busy ? ' disabled' : '') + '>Send to whole group</button></div></div>' : '') +
        '<div class="wifi-device-results"><h4>Delivery status</h4><div data-wifi-device-list></div></div>' +
        '</section>';
      model.draftNetworks.forEach(function (network) {
        const input = groupPanel.querySelector('[data-wifi-row="' + network.id + '"][data-wifi-field="password"]');
        if (input) input.value = network.password || '';
      });
      renderTransientStatus();
      renderDeviceResults();
      updateButtons();
    }

    function findDevice(memberId, devices) {
      return (devices || []).find(function (device) {
        return device && (device.device_id === memberId || device.serial_number === memberId);
      }) || null;
    }

    function renderDeviceResults() {
      if (!groupPanel || !activeGroup) return;
      const list = groupPanel.querySelector('[data-wifi-device-list]');
      if (!list) return;
      const members = activeMembers || [];
      if (!members.length) {
        list.innerHTML = '<div class="wifi-empty">No devices are assigned to this group.</div>';
        updateButtons();
        return;
      }
      const currentRevision = state && state.groups && state.groups[activeGroup] && state.groups[activeGroup].revision;
      const devices = getDevices();
      const rows = members.map(function (memberId) {
        const device = findDevice(memberId, devices);
        const deviceId = device ? (device.device_id || device.serial_number || memberId) : memberId;
        const label = device && device.label ? device.label : memberId;
        const online = device && (device.status === 'online' || device.status === 'updating');
        const deviceState = state && state.devices && (state.devices[deviceId] || state.devices[memberId]);
        const delivery = deliveryStatus(deviceState, currentRevision);
        const deliveryReason = reasonLabel(deviceState && deviceState.delivery);
        const conflict = membershipConflict(memberId, activeGroup, getGroups(), device && [device.serial_number]);
        const membershipNote = conflict ? '<span class="wifi-membership-note">Also listed in ' + escapeHtml(conflict) + '</span>' : '';
        return '<div class="wifi-device-row"><div class="wifi-device-title"><strong>' + escapeHtml(label) + '</strong>' +
          '<span class="wifi-online ' + (online ? 'online' : 'offline') + '"><i></i>' + (online ? 'Online' : 'Offline') + '</span>' + membershipNote + '</div>' +
          '<div class="wifi-device-status"><div class="wifi-result ' + delivery.tone + '"><span aria-hidden="true">' + delivery.icon + '</span><span>' + delivery.label + '</span></div>' +
          (deliveryReason ? '<div class="wifi-reason">' + escapeHtml(deliveryReason) + '</div>' : '') +
          '</div></div>';
      });
      list.innerHTML = rows.join('');
      updateButtons();
    }

    function renderGroup(name, members, force) {
      activeMembers = Array.isArray(members) ? members.slice() : [];
      const changedGroup = name !== activeGroup;
      activeGroup = name || null;
      const signature = activeMembers.slice().sort().join('\u0000');
      if (force || changedGroup || !groupPanel.dataset.wifiRendered) {
        groupPanel.dataset.wifiRendered = 'true';
        lastRenderedMembers = signature;
        const model = activeGroup && profileFor(activeGroup);
        if (activeGroup && enabled === true && authState !== 'required' &&
            (!model.loaded || (changedGroup && !model.dirty))) {
          loadProfile(activeGroup, false);
        }
        renderProfile();
      } else {
        if (signature !== lastRenderedMembers) {
          lastRenderedMembers = signature;
          renderDeviceResults();
        }
        renderTransientStatus();
      }
    }

    async function loadProfile(name, quiet) {
      if (enabled !== true || authState === 'required' || !name) return;
      const model = profileFor(name);
      if (model.loading) return;
      const requestedAuthGeneration = authGeneration;
      model.loading = true;
      model.error = '';
      if (!quiet && name === activeGroup) renderProfile();
      updateButtons();
      try {
        const response = await root.fetch(apiUrl('/api/wifi/groups/' + encodeURIComponent(name)), {
          credentials: 'same-origin',
          headers: { Accept: 'application/json' },
        });
        if (response.status === 401) {
          if (requestedAuthGeneration !== authGeneration) return;
          authState = 'required';
          authMessage = '';
          requestLogin();
          if (name === activeGroup) renderProfile();
          return;
        }
        if (!response.ok) {
          model.error = response.status === 404
            ? 'This group has no Wi-Fi profile or is no longer available.'
            : 'Could not load saved Wi-Fi settings.';
          return;
        }
        const profile = await response.json();
        authState = 'authenticated';
        authGeneration++;
        authMessage = '';
        const previousRevision = model.savedRevision;
        model.savedRevision = profile && profile.revision ? profile.revision : null;
        model.conflicted = false;
        // Adopt this explicit read without overwriting a newer revision observed by polling.
        if (state && state.groups && state.groups[name] && state.groups[name].revision === previousRevision) {
          state.groups[name].revision = model.savedRevision;
        }
        model.savedNetworks = normalizeNetworks(profile && profile.networks);
        model.savedMode = profile && profile.mode === 'unmanaged' ? 'unmanaged' : 'managed';
        if (!model.dirty) model.mode = model.savedMode;
        model.loaded = true;
        if (!model.dirty) model.draftNetworks = model.savedNetworks.map(function (network, index) {
          return Object.assign({}, network, { id: 'wifi-' + index });
        });
        if (name === activeGroup) {
          renderAuth();
          renderProfile();
        }
      } catch (_) {
        model.error = 'Could not load saved Wi-Fi settings.';
      } finally {
        model.loading = false;
        if (name === activeGroup) {
          renderAuth();
          renderProfile();
        }
      }
    }

    function updateDraftFromInput(event) {
      const input = event.target;
      if (!input || !input.matches || !input.matches('[data-wifi-row][data-wifi-field]') || !activeGroup) return;
      const model = profileFor(activeGroup);
      if (model.loading || model.saving || model.applying || model.stale) return;
      const row = model.draftNetworks.find(function (network) { return network.id === input.dataset.wifiRow; });
      if (!row) return;
      const field = input.dataset.wifiField;
      if (field === 'hidden') row.hidden = input.checked;
      else if (field === 'security') {
        row.security = input.value;
        if (row.security === 'OPEN') {
          row.password = '';
          row.password_present = false;
          const passwordInput = groupPanel.querySelector('[data-wifi-row="' + row.id + '"][data-wifi-field="password"]');
          if (passwordInput) passwordInput.value = '';
        }
      } else row[field] = input.value;
      model.dirty = true;
      model.error = '';
      updateButtons();
    }

    function updateButtons() {
      if (!groupPanel || !activeGroup) return;
      const model = profileFor(activeGroup);
      const save = groupPanel.querySelector('[data-wifi-action="save"]');
      const apply = groupPanel.querySelector('[data-wifi-action="apply"]');
      const currentRevision = state && state.groups && state.groups[activeGroup] && state.groups[activeGroup].revision;
      model.stale = model.conflicted || !!(currentRevision && model.savedRevision && currentRevision !== model.savedRevision);
      const busy = model.loading || model.saving || model.applying || model.stale ||
        enabled !== true || authState !== 'authenticated' || !transportAllowed();
      groupPanel.querySelectorAll('[data-wifi-field], [data-wifi-action="add"], [data-wifi-action="remove"], [data-wifi-action="unmanage"]').forEach(function (control) {
        const action = control.dataset && control.dataset.wifiAction;
        const network = control.dataset && model.draftNetworks.find(function (row) {
          return row.id === control.dataset.wifiRow;
        });
        control.disabled = busy || (action === 'remove' && model.draftNetworks.length <= 1) ||
          (action === 'add' && model.draftNetworks.length >= MAX_NETWORKS) ||
          (control.dataset && control.dataset.wifiField === 'password' && network && network.security === 'OPEN');
      });
      if (save) save.disabled = model.mode !== 'managed' || !model.dirty || busy;
      if (apply) apply.disabled = model.dirty || !model.savedRevision || busy;
      const stale = groupPanel.querySelector('[data-wifi-stale]');
      if (stale) stale.style.display = model.stale ? '' : 'none';
      const hint = groupPanel.querySelector('.wifi-action-right .wifi-meta');
      if (hint) hint.textContent = model.dirty ? 'Unsaved changes' : model.savedRevision ? 'Saved' : 'Not saved';
    }

    async function saveProfile() {
      if (!activeGroup) return;
      const name = activeGroup;
      const model = profileFor(name);
      if (enabled !== true || authState !== 'authenticated' ||
          !model.loaded || model.stale || model.saving || model.applying) return;
      if (!transportAllowed()) {
        model.error = 'Wi-Fi changes require HTTPS or managed-LAN HTTP enabled on the server.';
        renderProfile();
        return;
      }
      const built = buildProfilePayload(model.draftNetworks, model.savedNetworks);
      if (built.error) {
        model.error = built.error;
        renderProfile();
        return;
      }
      model.saving = true;
      model.error = '';
      updateButtons();
      try {
        const response = await root.fetch(apiUrl('/api/wifi/groups/' + encodeURIComponent(name)), {
          method: 'PUT',
          credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify(Object.assign({ expected_revision: model.savedRevision }, built.payload)),
        });
        if (response.status === 401) {
          authState = 'required';
          authMessage = '';
          requestLogin();
          model.error = 'Your Wi-Fi administrator session expired. Log in again.';
        } else if (!response.ok) {
          if (response.status === 409) model.conflicted = true;
          model.error = response.status === 409
            ? 'The Wi-Fi profile changed elsewhere. Reload the group before saving again.'
            : 'Could not save Wi-Fi settings. Check the SSID and security details.';
        } else {
          const saved = await response.json();
          model.savedRevision = saved && saved.revision ? saved.revision : model.savedRevision;
          if (model.savedRevision) {
            if (!state) state = { enabled: true, groups: {}, devices: {} };
            if (!state.groups) state.groups = {};
            if (!state.groups[name]) state.groups[name] = {};
            state.groups[name].revision = model.savedRevision;
          }
          model.savedNetworks = model.draftNetworks.map(function (network) {
            return {
              ssid: network.ssid,
              security: network.security,
              password_present: network.security === 'WPA_PSK' && (network.password ? true : network.password_present),
              hidden: network.hidden === true,
            };
          });
          model.draftNetworks.forEach(function (network) {
            network.password = '';
            if (network.security === 'WPA_PSK') network.password_present = true;
          });
          model.mode = model.savedMode = 'managed';
          model.dirty = false;
          authState = 'authenticated';
        }
      } catch (_) {
        model.error = 'Could not save Wi-Fi settings.';
      } finally {
        model.saving = false;
        if (name === activeGroup) renderProfile();
      }
    }

    async function unmanageProfile() {
      if (!activeGroup || enabled !== true || authState !== 'authenticated' || !transportAllowed()) return;
      const name = activeGroup;
      const model = profileFor(name);
      if (!model.loaded || model.stale || model.loading || model.saving || model.applying) return;
      if (!root.confirm('Delete Wi-Fi settings for "' + name + '"?\n\n' +
          'Existing Wi-Fi networks on devices will remain unchanged. This only saves a Do not manage Wi-Fi state on the server. You must deploy it explicitly to stop reapplying Wi-Fi settings at device startup.\n\nUnsaved Wi-Fi edits will be discarded.')) return;
      model.saving = true;
      model.error = '';
      updateButtons();
      try {
        const response = await root.fetch(apiUrl('/api/wifi/groups/' + encodeURIComponent(name)), {
          method: 'DELETE', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify({ expected_revision: model.savedRevision }),
        });
        if (response.status === 401) {
          requestLogin();
          model.error = 'Your Wi-Fi administrator session expired. Log in again.';
        } else if (!response.ok) {
          if (response.status === 409) model.conflicted = true;
          model.error = response.status === 409
            ? 'The Wi-Fi profile changed elsewhere. Reload it before deleting.'
            : 'Could not save the unmanaged Wi-Fi state.';
        } else {
          const saved = await response.json();
          model.savedRevision = saved.revision;
          model.mode = model.savedMode = 'unmanaged';
          model.savedNetworks = [];
          model.draftNetworks = [];
          model.dirty = false;
          if (state && state.groups) state.groups[name] = { revision: saved.revision, mode: 'unmanaged' };
          authMessage = 'Do not manage Wi-Fi saved for ' + name + '. Deploy it explicitly; existing device Wi-Fi settings remain.';
          renderAuth();
        }
      } catch (_) {
        model.error = 'Could not save the unmanaged Wi-Fi state.';
      } finally {
        model.saving = false;
        if (name === activeGroup) renderProfile();
      }
    }

    async function applyProfile() {
      if (!activeGroup || !state || enabled !== true || authState !== 'authenticated') return;
      const name = activeGroup;
      const model = profileFor(name);
      if (!model.savedRevision || model.stale || model.dirty || model.applying || model.saving) return;
      if (!transportAllowed()) {
        model.error = 'Wi-Fi changes require HTTPS or managed-LAN HTTP enabled on the server.';
        renderProfile();
        return;
      }
      const onlineCount = activeMembers.filter(function (memberId) {
        const device = findDevice(memberId, getDevices());
        return device && (device.status === 'online' || device.status === 'updating');
      }).length;
      const accepted = confirmApply('Send the saved Wi-Fi settings to the whole group "' + name + '"?\n\n' +
        onlineCount + ' online device(s) are connected. Compatible clients check their Wi-Fi connection locally; the server does not wait for results. Offline devices are not queued. This may disconnect devices.');
      if (!accepted) return;
      model.applying = true;
      model.error = '';
      updateButtons();
      try {
        const response = await root.fetch(apiUrl('/api/wifi/groups/' + encodeURIComponent(name) + '/deploy'), {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify({ expected_revision: model.savedRevision }),
        });
        if (response.status === 401) {
          authState = 'required';
          requestLogin();
          model.error = 'Your Wi-Fi administrator session expired. Log in again.';
        } else if (!response.ok) {
          if (response.status === 409) model.conflicted = true;
          model.error = response.status === 409
            ? 'The saved group or profile changed. Reload it before sending.'
            : 'Could not send Wi-Fi settings to the group.';
        } else {
          const delivery = await response.json();
          const operations = Array.isArray(delivery.operations) ? delivery.operations : [];
          const sent = operations.filter(function (item) { return item.status === 'sent'; }).length;
          const failed = operations.filter(function (item) { return item.status === 'failed'; }).length;
          const unknown = operations.length - sent - failed;
          authMessage = 'Delivery for ' + name + ': ' + sent + ' sent, ' + failed + ' failed, ' + unknown + ' unconfirmed.';
          renderAuth();
          await refreshState();
        }
      } catch (_) {
        model.error = 'Could not send Wi-Fi settings to the group.';
      } finally {
        model.applying = false;
        if (name === activeGroup) renderProfile();
      }
    }

    async function login(form) {
      const input = form.querySelector('[data-wifi-token]');
      let token = input ? input.value : '';
      if (input) input.value = '';
      if (!transportAllowed()) {
        token = '';
        authMessage = 'Use HTTPS, or explicitly enable managed-LAN HTTP on the server.';
        renderAuth();
        return;
      }
      if (!token) {
        authMessage = 'Enter the admin token.';
        renderAuth();
        return;
      }
      authMessage = '';
      try {
        const response = await root.fetch(apiUrl('/api/wifi/session'), {
          method: 'POST',
          credentials: 'same-origin',
          headers: { Authorization: 'Bearer ' + token },
        });
        if (!response.ok) {
          authState = 'required';
          authMessage = response.status === 401 || response.status === 403
            ? 'The admin token was not accepted.'
            : 'Could not start a Wi-Fi administrator session.';
          return;
        }
        authState = 'authenticated';
        authMessage = '';
        renderAccess();
        if (typeof options.onAuthenticated === 'function') options.onAuthenticated();
        if (activeGroup && !profileFor(activeGroup).dirty) await loadProfile(activeGroup, false);
        await refreshState();
      } catch (_) {
        authState = 'required';
        authMessage = 'Could not start a Wi-Fi administrator session.';
      } finally {
        token = '';
        renderAccess();
      }
    }

    function handleGroupClick(event) {
      const target = event.target.closest('[data-wifi-action]');
      if (!target) return;
      const action = target.dataset.wifiAction;
      const model = activeGroup ? profileFor(activeGroup) : null;
      if (!model || model.loading || model.saving || model.applying) return;
      if (action === 'reload' && model.stale) {
        if (model.dirty && !root.confirm('Discard unsaved Wi-Fi changes and reload the saved profile?')) return;
        model.dirty = false;
        loadProfile(activeGroup, false);
      } else if (action === 'unmanage') {
        unmanageProfile();
      } else if (action === 'add' && !model.stale && model.draftNetworks.length < MAX_NETWORKS) {
        model.mode = 'managed';
        model.draftNetworks.push({
          id: 'wifi-new-' + Math.random().toString(36).slice(2),
          ssid: '', security: 'OPEN', password: '', password_present: false, hidden: false,
        });
        model.dirty = true;
        model.error = '';
        renderProfile();
      } else if (action === 'remove' && !model.stale && model.draftNetworks.length > 1) {
        model.draftNetworks = model.draftNetworks.filter(function (network) { return network.id !== target.dataset.wifiRow; });
        model.dirty = true;
        model.error = '';
        renderProfile();
      } else if (action === 'save') saveProfile();
      else if (action === 'apply') applyProfile();
    }

    if (authPanel) {
      authPanel.addEventListener('submit', function (event) {
        if (!event.target.matches('[data-wifi-login-form]')) return;
        event.preventDefault();
        login(event.target);
      });
    }
    if (groupPanel) {
      groupPanel.addEventListener('click', handleGroupClick);
      groupPanel.addEventListener('input', updateDraftFromInput);
      groupPanel.addEventListener('change', updateDraftFromInput);
    }

    renderAuth();
    refreshState();
    pollHandle = root.setInterval(refreshState, 10000);

    return {
      renderGroup: renderGroup,
      refresh: refreshState,
      renameGroup: function (oldName, newName) {
        if (profiles[oldName]) {
          profiles[newName] = profiles[oldName];
          delete profiles[oldName];
        }
      },
      membershipConflict: membershipConflict,
      stop: function () { if (pollHandle !== null) root.clearInterval(pollHandle); },
    };
  }

  const api = {
    MAX_NETWORKS: MAX_NETWORKS,
    normalizeNetworks: normalizeNetworks,
    buildProfilePayload: buildProfilePayload,
    membershipConflict: membershipConflict,
    deliveryStatus: deliveryStatus,
    reasonLabel: reasonLabel,
    createController: createController,
  };

  root.MdmWifiConsole = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
