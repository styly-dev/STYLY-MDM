(function (root) {
  'use strict';

  const MAX_NETWORKS = 32;
  const SECURITY_MODES = ['OPEN', 'WPA_PSK'];
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

    const seenNetworks = new Set();
    const payload = [];
    for (let index = 0; index < networks.length; index++) {
      const network = networks[index];
      const ssid = typeof network.ssid === 'string' ? network.ssid : '';
      if (!ssid.trim()) return { error: 'Enter an SSID for Wi-Fi ' + (index + 1) + '.' };
      if (SECURITY_MODES.indexOf(network.security) < 0) {
        return { error: 'Choose Open or WPA/WPA2 Personal security for Wi-Fi ' + (index + 1) + '.' };
      }
      const identity = ssid + '\u0000' + network.security;
      if (seenNetworks.has(identity)) return { error: 'Each SSID and security combination must be unique.' };
      seenNetworks.add(identity);

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

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (character) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character];
    });
  }

  function passwordHelp(network) {
    if (network.security === 'WPA_PSK') return network.password_present
      ? 'Leave blank to keep the saved password. Enter a new password to replace it.'
      : 'Required for this saved network.';
    return network.security === 'OPEN'
      ? 'Open networks do not use a password.'
      : 'This saved security type is unsupported. Choose a supported type before saving.';
  }

  function createController(options) {
    const statusPanel = options.statusPanel;
    const groupPanel = options.groupPanel;
    const confirmApply = options.confirmApply || root.confirm.bind(root);
    // Wi-Fi keys are configuration values, not login credentials.
    const secretInputType = root.CSS && typeof root.CSS.supports === 'function' &&
      root.CSS.supports('-webkit-text-security', 'disc') ? 'text' : 'password';
    let enabled = null;
    let accessRenderKey = null;
    let statusMessage = '';
    let state = null;
    let stateUnavailable = false;
    let stateError = false;
    let stateLoading = false;
    let activeGroup = null;
    let profile = null;

    function isBusy(model) {
      return model.loading || model.saving || model.sending;
    }

    function profileFor(name) {
      if (!profile || profile.groupName !== name) {
        profile = {
          groupName: name,
          loaded: false,
          loading: false,
          saving: false,
          sending: false,
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
      return profile;
    }

    function renderAccess() {
      renderStatus();
      const key = [enabled, stateUnavailable].join('|');
      if (key !== accessRenderKey) {
        accessRenderKey = key;
        renderProfile();
      }
      updateButtons();
    }

    function renderStatus() {
      if (!statusPanel) return;
      statusPanel.hidden = !statusMessage;
      statusPanel.innerHTML = statusMessage
        ? '<div class="wifi-feedback" role="status">' + escapeHtml(statusMessage) + '</div>' : '';
    }

    async function refreshState() {
      if (stateLoading) return;
      stateLoading = true;
      try {
        const response = await root.fetch('/api/wifi/state');
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
        stateUnavailable = false;
        stateError = false;
        state = nextState;
        renderAccess();
        renderTransientStatus();
        if (activeGroup && enabled && !profileFor(activeGroup).loaded) {
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
        ? 'Wi-Fi status is temporarily unavailable. Showing the last received settings.'
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
      model.stale = model.conflicted;
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
        const secretHelp = '<small>' + escapeHtml(passwordHelp(network)) + '</small>';
        return '<article class="wifi-network" data-wifi-row="' + escapeHtml(network.id) + '">' +
          '<div class="wifi-network-head"><strong>Wi-Fi ' + (index + 1) + '</strong>' +
          '<button class="btn-outline btn-danger-outline wifi-remove" type="button" data-wifi-action="remove" data-wifi-row="' + escapeHtml(network.id) + '"' +
          (model.draftNetworks.length <= 1 ? ' disabled' : '') + '>Remove from policy</button></div>' +
          '<label>SSID<input class="field" data-wifi-row="' + escapeHtml(network.id) + '" data-wifi-field="ssid" type="text" autocomplete="off" value="' + escapeHtml(network.ssid) + '"></label>' +
          '<div class="wifi-fields"><label>Security<select class="field" data-wifi-row="' + escapeHtml(network.id) + '" data-wifi-field="security">' + securityOptions + '</select></label>' +
          '<label>Password<input class="field wifi-secret" data-wifi-row="' + escapeHtml(network.id) + '" data-wifi-field="password" type="' + secretInputType + '" autocomplete="off" autocapitalize="off" spellcheck="false" value=""></label></div>' +
          '<div class="wifi-row-foot">' + secretHelp + '<label class="wifi-check"><input type="checkbox" data-wifi-row="' + escapeHtml(network.id) + '" data-wifi-field="hidden"' +
          (network.hidden ? ' checked' : '') + '> Hidden network</label></div></article>';
      }).join('');

      const revisionText = model.savedRevision ? (model.savedMode === 'unmanaged' ? 'Saved: Do not manage Wi-Fi' : 'Saved profile') : 'No saved profile';
      const busy = isBusy(model);
      const profileError = model.error ? '<div class="wifi-feedback wifi-danger" role="alert">' + escapeHtml(model.error) + '</div>' : '';
      const unavailable = enabled === false || stateUnavailable
        ? '<div class="wifi-feedback wifi-muted">Wi-Fi management is unavailable on this server.</div>' : '';
      const loading = model.loading && !model.loaded ? '<div class="wifi-feedback">Loading saved Wi-Fi settings…</div>' : '';
      const editable = model.loaded && enabled === true;
      groupPanel.innerHTML = '<section class="wifi-section"><div class="wifi-section-head"><div><h3>Wi-Fi settings</h3>' +
        '<p>Save this group’s Wi-Fi policy, then send it to online devices.</p>' +
        '<p>Listed networks get auto-connect enabled; other saved networks stay on the device with auto-connect disabled.</p>' +
        '<p>If no listed network obtains an IP within 60 seconds, clients restore the previous auto-connect policy. The server does not track device results.</p>' +
        '<p class="wifi-meta">' + revisionText + '</p></div>' +
        (model.loaded && enabled === true && model.mode !== 'unmanaged'
          ? '<button class="btn-outline btn-danger-outline" type="button" data-wifi-action="unmanage">' + (model.savedRevision ? 'Delete Wi-Fi settings' : 'Do not manage Wi-Fi') + '</button>' : '') + '</div>' +
        '<div data-wifi-transient class="wifi-feedback" role="status" style="display:none"></div>' +
        '<div data-wifi-stale class="wifi-feedback wifi-danger" role="alert" style="display:none">The saved profile changed on the server. Reload it before saving or sending. <button class="btn-text" type="button" data-wifi-action="reload">Reload saved profile</button></div>' +
        profileError + unavailable + loading +
        (editable ? '<div class="wifi-network-list">' + (model.mode === 'unmanaged'
          ? '<div class="push-note">Existing Wi-Fi networks on devices stay in place. Deploy this state explicitly to stop reapplying Wi-Fi settings at device startup. Offline devices keep their previous management policy until you deploy to them while connected.</div>'
          : '<div class="push-note">Device Wi-Fi networks are never deleted. A listed network may have its saved password replaced. If connection fails, MDM can restore a previous password only when it already holds that password.</div>' +
            (rowHtml || '<div class="wifi-empty">No networks saved yet.</div>')) + '</div>' +
          '<div class="wifi-actions"><button class="btn-outline" type="button" data-wifi-action="add"' + (model.draftNetworks.length >= MAX_NETWORKS || busy ? ' disabled' : '') + '>Add Wi-Fi network</button>' +
          '<div class="wifi-action-right"><span class="wifi-meta">' + (model.dirty ? 'Unsaved changes' : model.savedRevision ? 'Saved' : 'Not saved') + '</span>' +
          (model.mode === 'managed' ? '<button class="btn btn-primary" type="button" data-wifi-action="save"' + (!model.dirty || busy ? ' disabled' : '') + '>Save Wi-Fi settings</button>' : '') +
          '<button class="btn btn-success" type="button" data-wifi-action="apply"' + (model.dirty || !model.savedRevision || busy ? ' disabled' : '') + '>Send to whole group</button></div></div>' : '') +
        '</section>';
      model.draftNetworks.forEach(function (network) {
        const input = groupPanel.querySelector('[data-wifi-row="' + network.id + '"][data-wifi-field="password"]');
        if (input) input.value = network.password || '';
      });
      renderTransientStatus();
      updateButtons();
    }

    function renderGroup(name) {
      const changedGroup = name !== activeGroup;
      activeGroup = name || null;
      if (changedGroup) profile = null;
      if (changedGroup || !groupPanel.dataset.wifiRendered) {
        groupPanel.dataset.wifiRendered = 'true';
        const model = activeGroup && profileFor(activeGroup);
        if (activeGroup && enabled === true &&
            (!model.loaded || (changedGroup && !model.dirty))) {
          loadProfile(activeGroup, false);
        }
        renderProfile();
      } else {
        renderTransientStatus();
      }
    }

    async function loadProfile(name, quiet) {
      if (enabled !== true || !name) return;
      const model = profileFor(name);
      if (model.loading) return;
      model.loading = true;
      model.error = '';
      if (!quiet && name === activeGroup) renderProfile();
      updateButtons();
      try {
        const response = await root.fetch('/api/wifi/groups/' + encodeURIComponent(name), {
          headers: { Accept: 'application/json' },
        });
        if (!response.ok) {
          model.error = response.status === 404
            ? 'This group has no Wi-Fi profile or is no longer available.'
            : 'Could not load saved Wi-Fi settings.';
          return;
        }
        const loadedProfile = await response.json();
        if (name !== activeGroup || profile !== model) return;
        statusMessage = '';
        model.savedRevision = loadedProfile && loadedProfile.revision ? loadedProfile.revision : null;
        model.conflicted = false;
        model.savedNetworks = normalizeNetworks(loadedProfile && loadedProfile.networks);
        model.savedMode = loadedProfile && loadedProfile.mode === 'unmanaged' ? 'unmanaged' : 'managed';
        if (!model.dirty) model.mode = model.savedMode;
        model.loaded = true;
        if (!model.dirty) model.draftNetworks = model.savedNetworks.map(function (network, index) {
          return Object.assign({}, network, { id: 'wifi-' + index });
        });
        if (name === activeGroup) {
          renderStatus();
          renderProfile();
        }
      } catch (_) {
        model.error = 'Could not load saved Wi-Fi settings.';
      } finally {
        model.loading = false;
        if (name === activeGroup) {
          renderStatus();
          renderProfile();
        }
      }
    }

    function updateDraftFromInput(event) {
      const input = event.target;
      if (!input || !input.matches || !input.matches('[data-wifi-row][data-wifi-field]') || !activeGroup) return;
      const model = profileFor(activeGroup);
      if (isBusy(model) || model.stale) return;
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
      if (field === 'security') {
        const help = groupPanel.querySelector('article[data-wifi-row="' + row.id + '"] small');
        if (help) help.textContent = passwordHelp(row);
      }
      updateButtons();
    }

    function updateButtons() {
      if (!groupPanel || !activeGroup) return;
      const model = profileFor(activeGroup);
      const save = groupPanel.querySelector('[data-wifi-action="save"]');
      const apply = groupPanel.querySelector('[data-wifi-action="apply"]');
      model.stale = model.conflicted;
      const busy = isBusy(model) || model.stale ||
        enabled !== true;
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
      if (enabled !== true ||
          !model.loaded || model.stale || isBusy(model)) return;
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
        const response = await root.fetch('/api/wifi/groups/' + encodeURIComponent(name), {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify(Object.assign({ expected_revision: model.savedRevision }, built.payload)),
        });
        if (!response.ok) {
          if (response.status === 409) model.conflicted = true;
          model.error = response.status === 409
            ? 'The Wi-Fi profile changed elsewhere. Reload the group before saving again.'
            : 'Could not save Wi-Fi settings. Check the SSID and security details.';
        } else {
          const saved = await response.json();
          model.savedRevision = saved && saved.revision ? saved.revision : model.savedRevision;
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
        }
      } catch (_) {
        model.error = 'Could not save Wi-Fi settings.';
      } finally {
        model.saving = false;
        if (name === activeGroup) renderProfile();
      }
    }

    async function unmanageProfile() {
      if (!activeGroup || enabled !== true) return;
      const name = activeGroup;
      const model = profileFor(name);
      if (!model.loaded || model.stale || isBusy(model)) return;
      if (!root.confirm('Delete the Wi-Fi policy for "' + name + '"?\n\n' +
          'Existing Wi-Fi networks will stay saved on devices, and their auto-connect settings stay unchanged. Deploy explicitly to stop boot reapplication.\n\nUnsaved edits will be discarded.')) return;
      model.saving = true;
      model.error = '';
      updateButtons();
      try {
        const response = await root.fetch('/api/wifi/groups/' + encodeURIComponent(name), {
          method: 'DELETE',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify({ expected_revision: model.savedRevision }),
        });
        if (!response.ok) {
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
          statusMessage = 'Do not manage Wi-Fi saved for ' + name + '. Deploy it explicitly; existing device Wi-Fi settings remain.';
          renderStatus();
        }
      } catch (_) {
        model.error = 'Could not save the unmanaged Wi-Fi state.';
      } finally {
        model.saving = false;
        if (name === activeGroup) renderProfile();
      }
    }

    async function sendProfile() {
      if (!activeGroup || !state || enabled !== true) return;
      const name = activeGroup;
      const model = profileFor(name);
      if (!model.savedRevision || model.stale || model.dirty || isBusy(model)) return;
      const confirmation = model.mode === 'unmanaged'
        ? 'Send the Do not manage Wi-Fi state to the whole group "' + name + '"?\n\n' +
          'Existing Wi-Fi networks stay saved. This stops changing auto-connect settings and stops reapplying the saved Wi-Fi policy at startup. Only online devices receive this state.'
        : 'Send the saved Wi-Fi policy to the whole group "' + name + '"?\n\n' +
          'Listed networks get auto-connect enabled; other saved networks get auto-connect disabled. No saved networks are deleted. An existing matching SSID may have its credentials replaced. If connection fails, MDM can restore a previous password only when it holds that password. Only online devices receive this policy.';
      const accepted = confirmApply(confirmation);
      if (!accepted) return;
      const sendError = 'Could not send Wi-Fi settings to the group.';
      model.sending = true;
      model.error = '';
      updateButtons();
      try {
        const response = await root.fetch('/api/wifi/groups/' + encodeURIComponent(name) + '/deploy', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify({ expected_revision: model.savedRevision, mode: model.mode }),
        });
        if (!response.ok) {
          if (response.status === 409) model.conflicted = true;
          model.error = response.status === 409
            ? 'The saved group or profile changed. Reload it before sending.'
            : sendError;
        } else {
          const result = await response.json();
          const operations = Array.isArray(result.operations) ? result.operations : [];
          const sent = operations.filter(function (item) { return item.status === 'sent'; }).length;
          const failed = operations.filter(function (item) { return item.status === 'failed'; }).length;
          const unknown = operations.length - sent - failed;
          statusMessage = 'Deploy sent for ' + name + ': ' + sent + ' sent, ' + failed + ' failed, ' + unknown + ' unconfirmed.';
          renderStatus();
        }
      } catch (_) {
        model.error = sendError;
      } finally {
        model.sending = false;
        if (name === activeGroup) renderProfile();
      }
    }

    function handleGroupClick(event) {
      const target = event.target.closest('[data-wifi-action]');
      if (!target) return;
      const action = target.dataset.wifiAction;
      const model = activeGroup ? profileFor(activeGroup) : null;
      if (!model || isBusy(model)) return;
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
      else if (action === 'apply') sendProfile();
    }

    if (groupPanel) {
      groupPanel.addEventListener('click', handleGroupClick);
      groupPanel.addEventListener('input', updateDraftFromInput);
      groupPanel.addEventListener('change', updateDraftFromInput);
    }

    renderStatus();
    refreshState();

    return {
      renderGroup: renderGroup,
      refresh: refreshState,
      renameGroup: function (oldName, newName) {
        if (profile && profile.groupName === oldName) profile.groupName = newName;
      },
    };
  }

  const api = {
    membershipConflict: membershipConflict,
    MAX_NETWORKS: MAX_NETWORKS,
    normalizeNetworks: normalizeNetworks,
    buildProfilePayload: buildProfilePayload,
    createController: createController,
  };

  root.MdmWifiConsole = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
