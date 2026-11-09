(function (root) {
  'use strict';

  const CREATE_PACKAGE_OPTION = 'create-package';

  function membershipGroups(deviceId, groups, aliases) {
    const identity = new Set([deviceId].concat(aliases || []).filter(Boolean));
    return Object.keys(groups || {}).filter(function (name) {
      return (groups[name] || []).some(function (id) { return identity.has(id); });
    }).sort();
  }

  function syncMemberSelection(previous, next, selected) {
    const previousMembers = new Set(previous);
    const selection = new Set(next.filter(id => !previousMembers.has(id) || selected.has(id)));
    for (const id of selected) {
      if (!previousMembers.has(id)) selection.add(id);
    }
    return selection;
  }

  const MAX_NETWORKS = 32;
  const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (character) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character];
    });
  }

  function loadDraft(sections, settings) {
    const draft = {};
    for (const item of sections.flatMap(section => section.items)) {
      if (item.kind === 'unavailable') continue;
      const saved = settings[item.id];
      let value = saved ? saved.value : item.default;
      if (item.kind === 'wifi') {
        value = value.map(({ ssid, security, hidden, password_present }) => ({
          ssid, security, hidden, password_present, password: '',
        }));
      }
      draft[item.id] = { managed: saved?.managed === true, value };
    }
    return structuredClone(draft);
  }

  function hasSavedPassword(network, saved) {
    return saved.some(candidate => candidate.ssid === network.ssid &&
      candidate.security === network.security && candidate.password_present);
  }

  function wifiPayload(networks, saved, managed) {
    if (managed && !networks.length) throw new Error('Add at least one Wi-Fi network when Wi-Fi is managed.');
    const identities = new Set();
    return networks.map(function (network, index) {
      const { ssid, security, hidden, password } = network;
      if (!ssid) throw new Error('Enter an SSID for Wi-Fi ' + (index + 1) + '.');
      const identity = JSON.stringify([ssid, security]);
      if (identities.has(identity)) throw new Error('Each SSID and security combination must be unique.');
      identities.add(identity);
      const result = { ssid, security, hidden };
      if (security === 'WPA_PSK') {
        if (password) result.password = password;
        else if (!hasSavedPassword(network, saved)) throw new Error('Enter a password for Wi-Fi ' + (index + 1) + '.');
      }
      return result;
    });
  }

  function buildPayload(sections, draft, saved) {
    const settings = {};
    for (const item of sections.flatMap(section => section.items)) {
      const entry = draft[item.id];
      if (!entry) continue;
      const { managed, value } = entry;
      if (item.kind === 'schedule') {
        if (value.hour === null || value.minute === null) throw new Error('Enter a time for ' + item.label + '.');
        if (value.enabled && !value.repeat) throw new Error('Select at least one day for ' + item.label + '.');
      }
      settings[item.id] = {
        managed,
        value: item.kind === 'wifi' ? wifiPayload(value, saved[item.id].value, managed) : value,
      };
    }
    return settings;
  }

  function matchesDeployment(report, deployment) {
    return Boolean(deployment && report?.package_id === deployment.package_id && report.revision === deployment.revision);
  }

  function retryEligible(device, deployment) {
    const report = device?.configuration_state;
    return device?.status === 'online' && device.configuration_supported === true &&
      matchesDeployment(report, deployment) &&
      (report.status === 'interrupted' || (report.status === 'complete' && deployment.keys.some(key =>
        ['failed', 'unsupported'].includes(report.results?.[key]?.status))));
  }

  function deviceOutcome(device, packageId, deployment) {
    const online = device?.status === 'online';
    const connection = online ? '' : ' · Offline';
    if (!packageId) return ['', 'No package' + connection, 'Assign a configuration package to this group.'];
    if (!deployment) return ['pending', 'Awaiting publication' + connection, 'Publish the assigned package to start applying it.'];
    if (online && device.configuration_supported !== true) {
      return ['unavailable', 'Client update needed', 'This client does not report support for configuration packages.'];
    }
    const report = device?.configuration_state;
    if (!matchesDeployment(report, deployment) || (!online && report.status === 'applying')) {
      return ['pending', 'Pending' + connection, 'Waiting for the device to report the published revision.'];
    }
    if (report.status === 'interrupted') {
      return ['failed', 'Interrupted' + connection, 'Some settings may have changed. Retry when the device is online.'];
    }
    if (report.status === 'applying') return ['pending', 'Applying', 'The device is applying and checking managed settings.'];
    if (report.status === 'already_attempted') {
      return ['', 'Already attempted this boot' + connection, 'The device skipped reapplying this revision during the current boot.'];
    }
    if (report.status !== 'complete') {
      return ['pending', 'Pending' + connection, 'Waiting for the device to report the outcome.'];
    }
    const results = deployment.keys.map(key => report.results?.[key]?.status);
    const applied = results.filter(status => status === 'applied').length;
    if (results.every(status => status === 'applied')) {
      return ['success', results.length ? 'Applied' + connection : 'No managed settings' + connection,
        results.length ? 'All managed settings were verified.' : 'The device acknowledged this revision.'];
    }
    if (results.every(status => status === 'unsupported')) {
      return ['unavailable', 'Unsupported' + connection, 'This device could not apply the managed settings.'];
    }
    return ['failed', (applied ? 'Partially applied' : 'Apply failed') + connection,
      applied + ' of ' + results.length + ' managed settings verified.'];
  }

  function wifiPasswordHelp(network, saved) {
    if (network.security === 'OPEN') return 'Open networks do not use a password.';
    return hasSavedPassword(network, saved)
      ? 'Leave blank to keep the saved password.'
      : 'Enter a WPA/WPA2 password.';
  }

  function renderWifi(item, networks, saved, secretType) {
    const rows = networks.map(function (network, index) {
      const field = `data-setting="${esc(item.id)}" data-network="${index}"`;
      const isOpen = network.security === 'OPEN';
      return `<article class="wifi-network">
        <div class="wifi-network-head"><strong>Wi-Fi ${index + 1}</strong>
          <button class="btn-outline btn-danger-outline wifi-remove" type="button" data-policy-action="remove-network" ${field}>Remove from package</button>
        </div>
        <label>SSID<input class="field" ${field} data-field="ssid" value="${esc(network.ssid)}"></label>
        <div class="wifi-fields">
          <label>Security<select class="field" ${field} data-field="security">
            <option value="OPEN" ${isOpen ? 'selected' : ''}>Open</option>
            <option value="WPA_PSK" ${!isOpen ? 'selected' : ''}>WPA/WPA2 Personal (PSK)</option>
          </select></label>
          <label>Password<input class="field wifi-secret" type="${secretType}" ${field} data-field="password" autocomplete="off" spellcheck="false" ${isOpen ? 'disabled' : ''}></label>
        </div>
        <div class="wifi-row-foot">
          <small data-password-help="${index}">${wifiPasswordHelp(network, saved)}</small>
          <label class="wifi-check"><input type="checkbox" ${field} data-field="hidden" ${network.hidden ? 'checked' : ''}> Hidden network</label>
        </div>
      </article>`;
    }).join('');
    return `<div class="settings-networks">
      <div class="push-note">Publishing managed Wi-Fi enables auto-connect for listed networks and disables it for other saved networks. Devices keep their saved networks. Replacing a password can limit recovery to passwords already held by MDM.</div>
      ${rows}
      <button type="button" class="btn-outline" data-policy-action="add-network" data-setting="${esc(item.id)}" ${networks.length >= MAX_NETWORKS ? 'disabled' : ''}>Add Wi-Fi network</button>
    </div>`;
  }

  function scheduleTime(value) {
    return value.hour === null || value.minute === null ? '' :
      [value.hour, value.minute].map(number => String(number).padStart(2, '0')).join(':');
  }

  function renderControl(item, value, saved, secretType) {
    const field = `data-setting="${esc(item.id)}"`;
    const label = esc(item.label);
    switch (item.kind) {
      case 'bool':
        return `<div class="settings-radios">${[true, false].map(on =>
          `<label><input type="radio" name="setting-${esc(item.id)}" ${field} value="${on}" ${value === on ? 'checked' : ''}> ${on ? 'On' : 'Off'}</label>`).join('')}</div>`;
      case 'range':
        return `<div class="settings-range"><input aria-label="${label}" type="range" min="${esc(item.min ?? 0)}" max="${esc(item.max ?? 100)}" ${field} value="${esc(value)}"><output>${esc(value)}%</output></div>`;
      case 'delay':
        return `<select class="field" aria-label="${label}" ${field}>${item.options.map(option =>
          `<option value="${esc(option.value)}" ${option.value === value ? 'selected' : ''}>${esc(option.label)}</option>`).join('')}</select>`;
      case 'schedule':
        return `<div class="settings-schedule">
          <label><input type="checkbox" ${field} data-field="enabled" ${value.enabled ? 'checked' : ''}> Enable schedule</label>
          <input class="field" type="time" aria-label="Shutdown time" ${field} data-field="time" value="${esc(scheduleTime(value))}" ${value.enabled ? '' : 'disabled'}>
        </div><div class="settings-days">${DAYS.map((day, index) =>
          `<label><input type="checkbox" ${field} data-field="day" data-day="${index}" ${value.repeat & (1 << index) ? 'checked' : ''} ${value.enabled ? '' : 'disabled'}> ${day}</label>`).join('')}</div>`;
      case 'wifi':
        return renderWifi(item, value, saved, secretType);
      default:
        return '';
    }
  }

  function renderItem(item, draft, saved, secretType) {
    const entry = draft[item.id];
    const wide = item.kind === 'wifi' || item.kind === 'schedule';
    const label = esc(item.label);
    return `<div class="settings-row${wide ? ' settings-wide' : ''}">
      <label class="settings-manage"><input type="checkbox" data-manage="${esc(item.id)}" aria-label="Manage ${label}" ${entry?.managed ? 'checked' : ''} ${entry ? '' : 'disabled'}></label>
      <div class="settings-label"><strong>${label}</strong><small>${esc(item.description)}</small></div>
      ${entry ? `<fieldset class="settings-value" aria-label="${label}" ${entry.managed ? '' : 'disabled'}>${renderControl(item, entry.value, saved[item.id].value, secretType)}</fieldset>` :
        '<span class="settings-meta">Unavailable</span>'}
    </div>`;
  }

  function sectionCount(section, draft) {
    const managed = section.items.filter(item => draft[item.id]?.managed).length;
    const supported = section.items.filter(item => item.kind !== 'unavailable').length;
    return managed + ' / ' + supported + ' managed';
  }

  function renderSection(section, model, secretType) {
    return `<details class="settings-group" data-settings-section="${esc(section.id)}" ${model.expanded.has(section.id) ? 'open' : ''}>
      <summary class="settings-group-head">
        <span><strong>${esc(section.title)}</strong><small>${esc(section.description)}</small></span>
        <span class="settings-count">${sectionCount(section, model.draft)}</span>
        <span class="settings-chevron" aria-hidden="true"></span>
      </summary>
      <div class="settings-content">${section.items.map(item => renderItem(item, model.draft, model.saved, secretType)).join('')}</div>
    </details>`;
  }

  function actualValue(item, value) {
    if (!item) return '';
    if (item.kind === 'range') return typeof value === 'number' ? value + '%' : '';
    if (item.kind === 'bool') return typeof value === 'boolean' ? value ? 'On' : 'Off' : '';
    if (item.kind === 'delay') return item.options.find(option => option.value === value)?.label || '';
    if (item.kind === 'schedule' && value && typeof value.enabled === 'boolean') {
      return value.enabled ? scheduleTime(value) + ' · ' + DAYS.filter((_, index) => value.repeat & (1 << index)).join(', ') : 'Off';
    }
    return '';
  }

  function reportedResults(report, deployment, items, id, open) {
    if (!matchesDeployment(report, deployment)) return '';
    const lines = deployment.keys.map(function (key) {
      const item = items.get(key);
      const result = report.results?.[key];
      if (!result) return '';
      const status = { applied: 'Applied', failed: 'Failed', unsupported: 'Unsupported' }[result.status];
      if (!status) return '';
      const actual = actualValue(item, result.value);
      const reason = result.reason ? ' · ' + String(result.reason).replaceAll('_', ' ').toLowerCase() : '';
      const rollback = result.rolled_back ? ' · Rolled back' : '';
      return `<li>${esc(item?.label || key)} · ${status}${actual ? ' · ' + esc(actual) : ''}${esc(reason)}${rollback}</li>`;
    }).filter(Boolean).join('');
    return lines ? `<details class="settings-readback" data-result-device="${esc(id)}" ${open ? 'open' : ''}><summary>Reported settings</summary><ul>${lines}</ul></details>` : '';
  }

  function wifiReadiness(device, deployment) {
    const wifi = device?.wifi_state;
    const state = device?.configuration_state;
    if (device?.status !== 'online' || device.configuration_supported !== true ||
        !deployment?.keys.includes('wifi') || !wifi ||
        (matchesDeployment(state, deployment) && (state.status !== 'applying' || state.results?.wifi))) return '';
    if (wifi.unavailable_reason === 'INITIALIZING') return 'Wi-Fi is initializing.';
    if (wifi.available === false) return 'Wi-Fi management is unavailable.';
    if (wifi.busy) return 'A Wi-Fi operation is in progress.';
    return '';
  }

  function createController({
    groupPanel, packagePanel, packageList, createForm, getGroups, getDevices, openPackages,
    deviceIdHtml = (_, id) => esc(id), fetch = root.fetch.bind(root),
    confirmAction = message => root.confirm(message),
  }) {
    const secretType = root.CSS?.supports?.('-webkit-text-security', 'disc') ? 'text' : 'password';
    const groupEdits = new Map();
    const retrying = new Map();
    let group = null;
    let current = null;
    let snapshot = null;
    let catalog = null;
    let catalogLoading = false;
    let catalogError = '';
    let catalogContent = '';
    let items = new Map();
    let lastResultPackage = null;
    let creating = false;
    const packagePath = id => '/api/packages/' + encodeURIComponent(id);
    const packageById = id => catalog?.find(candidate => candidate.package_id === id);
    const assignment = name => Object.hasOwn(snapshot?.assignments || {}, name) ? snapshot.assignments[name] : null;
    function currentDeployment(name) {
      const deployment = Object.hasOwn(snapshot?.deployments || {}, name) ? snapshot.deployments[name] : null;
      return deployment?.package_id === assignment(name) ? deployment : null;
    }

    async function api(path, method = 'GET', body) {
      const headers = { Accept: 'application/json' };
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      const response = await fetch(path, { method, headers, body: JSON.stringify(body) });
      if (!response.ok) {
        const data = await response.json().catch(() => ({}));
        throw Object.assign(new Error(data?.error || `Request failed (HTTP ${response.status}).`), { status: response.status });
      }
      return response.json();
    }

    function updateSnapshot(data) {
      snapshot = { assignments: data.assignments || {}, deployments: data.deployments || {} };
      catalog = data.packages || [];
      for (const name of groupEdits.keys()) {
        if (!Object.hasOwn(getGroups(), name)) groupEdits.delete(name);
      }
      renderCatalog();
      renderPublication();
    }

    function renderCatalog() {
      const content = catalog?.map(pkg =>
        `<button type="button" class="mg-item settings-package-item${current?.id === pkg.package_id ? ' active' : ''}" data-package-id="${esc(pkg.package_id)}" aria-pressed="${current?.id === pkg.package_id}">
          <span class="mg-item-name">${esc(pkg.name)}</span>
          <span class="mg-item-count">${pkg.published_revision ? 'Published' : pkg.has_draft ? 'Draft' : 'New'}</span>
        </button>`).join('') || `<p class="empty small">${esc(catalog ? 'No packages yet.' : catalogError || 'Loading packages…')}</p>
        ${catalogError ? '<button type="button" class="btn-outline" data-refresh-packages>Reload packages</button>' : ''}`;
      if (catalogContent !== content) {
        catalogContent = content;
        packageList.innerHTML = content;
      }
    }

    function renderPublication() {
      const target = packagePanel.querySelector('[data-package-publication]');
      if (!current || !target) return;
      const pkg = packageById(current.id);
      const revision = pkg ? pkg.published_revision : current.publishedRevision;
      const published = revision ? 'Published revision ' + revision : 'Unpublished package';
      const refs = Object.values(snapshot?.assignments || {}).filter(id => id === current.id).length;
      target.textContent = published + (snapshot ? ' · Assigned to ' + refs + ' ' + (refs === 1 ? 'group' : 'groups') : '');
    }

    async function loadCatalog() {
      if (catalog || catalogLoading) return;
      catalogLoading = true;
      catalogError = '';
      renderCatalog();
      const before = snapshot;
      try {
        const data = await api('/api/packages');
        if (snapshot === before) catalog = data.packages;
      } catch (_) {
        catalogError = 'Could not load configuration packages.';
      } finally {
        catalogLoading = false;
        renderCatalog();
        refreshGroup();
      }
    }

    function updateSummary(before, profile, id) {
      if (snapshot !== before) return;
      catalog = (catalog || []).filter(pkg => pkg.package_id !== id);
      if (profile) {
        catalog.push({ package_id: id, name: profile.name, has_draft: Boolean(profile.draft_revision),
          published_revision: profile.published_revision });
        catalog.sort((a, b) => a.name.localeCompare(b.name));
      }
      renderCatalog();
      refreshGroup();
    }

    function hasChanges(model) {
      return model.name !== model.savedName || JSON.stringify(model.draft) !== JSON.stringify(model.saved);
    }

    function updateActions() {
      const model = current;
      const footer = packagePanel.querySelector('.settings-actions');
      if (!model || !footer) return;
      const changed = hasChanges(model);
      const locked = model.busy || model.conflict;
      packagePanel.querySelector('.settings-editor').disabled = locked;
      packagePanel.querySelector('[data-package-name]').disabled = locked;
      footer.querySelector('[data-policy-action="discard"]').disabled = locked || !changed;
      footer.querySelector('[data-policy-action="save"]').disabled = locked ||
        (!changed && Boolean(model.draftRevision)) || !model.name.trim();
      footer.querySelector('[data-policy-action="publish"]').disabled = locked || changed || !model.draftRevision;
      packagePanel.querySelector('[data-policy-action="delete"]').disabled = locked;
      footer.querySelector('.settings-meta').textContent = changed ? 'Unsaved changes' : model.draftRevision ? 'Draft saved' : 'No saved draft';
      const feedback = packagePanel.querySelector('[data-settings-feedback]');
      feedback.textContent = model.message;
      feedback.classList.toggle('settings-error', model.error);
      packagePanel.querySelector('[data-policy-action="reload"]').hidden = !model.conflict;
    }

    function renderEditor() {
      const model = current;
      if (!model) {
        packagePanel.innerHTML = '<div class="mg-detail-empty">Select a configuration package, or create one.</div>';
        return;
      }
      if (!model.saved) {
        packagePanel.innerHTML = model.busy ? '<p class="settings-meta" role="status">Loading package…</p>' :
          `<p class="settings-error" role="status">${esc(model.message)}</p><button type="button" class="btn-outline" data-policy-action="reload">Reload package</button>`;
        return;
      }
      packagePanel.innerHTML = `<div class="settings-section">
        <header class="settings-package-head"><div><h3>Edit configuration package</h3><p class="settings-meta" data-package-publication></p></div>
          <button type="button" class="btn-outline btn-danger-outline" data-policy-action="delete">Delete package</button>
        </header>
        <label class="settings-name">Package name<input class="field" data-package-name maxlength="64" value="${esc(model.name)}"></label>
        <div class="push-note">Save edits as a draft, then publish it to every group assigned this package. Devices apply managed settings on a new revision and once per boot after a successful apply. Failed or interrupted revisions require a device Retry. Clearing Manage leaves the device value in place.</div>
        <fieldset class="settings-editor" aria-label="Package settings">${model.sections.map(section => renderSection(section, model, secretType)).join('')}</fieldset>
        <div class="settings-actions">
          <span class="settings-meta"></span>
          <div class="settings-buttons">
            <button type="button" class="btn-outline" data-policy-action="discard">Discard changes</button>
            <button type="button" class="btn btn-primary" data-policy-action="save">Save draft</button>
            <button type="button" class="btn btn-success" data-policy-action="publish">Publish</button>
          </div>
        </div>
        <div class="settings-feedback" data-settings-feedback role="status"></div>
        <button type="button" class="btn-outline" data-policy-action="reload" hidden>Reload package</button>
      </div>`;
      for (const input of packagePanel.querySelectorAll('[data-field="password"]')) {
        input.value = model.draft[input.dataset.setting].value[Number(input.dataset.network)].password;
      }
      renderPublication();
      updateActions();
    }

    function readSections(sections) {
      items = new Map(sections.flatMap(section => section.items.map(item => [item.id, item])));
      renderResults();
    }

    function readProfile(model, data, preserveEdits = false) {
      const saved = loadDraft(data.sections, data.settings);
      const draft = structuredClone(saved);
      let name = data.name;
      if (preserveEdits && model.saved) {
        if (model.name !== model.savedName) name = model.name;
        for (const key of Object.keys(draft)) {
          const previous = model.saved[key];
          const edited = model.draft[key];
          if (!previous || !edited) continue;
          if (edited.managed !== previous.managed) draft[key].managed = edited.managed;
          if (JSON.stringify(edited.value) !== JSON.stringify(previous.value)) draft[key].value = edited.value;
        }
      }
      Object.assign(model, { sections: data.sections, draftRevision: data.draft_revision,
        publishedRevision: data.published_revision, savedName: data.name, saved, name, draft });
      readSections(data.sections);
    }

    async function readResultSections(id) {
      if (!id || items.size || lastResultPackage === id) return;
      lastResultPackage = id;
      try {
        const data = await api(packagePath(id));
        readSections(data.sections);
      } catch (_) {
        // Report status is available independently of setting labels and formatters.
      }
    }

    async function request(model, action, body) {
      model.busy = true;
      model.message = '';
      model.error = false;
      if (model.saved) updateActions();
      else renderEditor();
      const path = packagePath(model.id);
      const before = snapshot;
      let writing = false;
      try {
        let data;
        if (action === 'reload') {
          data = await api(path);
        } else if (action === 'publish') {
          const fresh = await api(path);
          if (fresh.draft_revision !== model.draftRevision) {
            throw Object.assign(new Error('The saved draft changed. Reload and review the latest draft before publishing.'), { status: 409 });
          }
          const { groups, devices, token } = fresh.impact;
          const groupCount = groups + ' ' + (groups === 1 ? 'group' : 'groups');
          const deviceCount = devices + ' ' + (devices === 1 ? 'device' : 'devices');
          if (!confirmAction('Publish "' + model.savedName + '" to ' + groupCount + ' and ' + deviceCount +
            '?\n\nThe count includes offline devices, which can receive this revision when they connect.')) return;
          writing = true;
          data = await api(path + '/publish', 'POST', { expected_revision: model.draftRevision, expected_impact: token });
        } else {
          writing = true;
          data = await api(path, action === 'delete' ? 'DELETE' : 'PUT', body);
        }
        if (action === 'delete') {
          current = null;
          updateSummary(before, null, model.id);
          renderCatalog();
          return;
        }
        readProfile(model, data, action === 'reload');
        updateSummary(before, data, model.id);
        model.conflict = false;
        model.message = { save: 'Draft saved.', publish: 'Package published.',
          reload: hasChanges(model) ? 'Latest draft loaded. Review your unsaved edits before saving.' : '' }[action];
      } catch (error) {
        model.error = true;
        if (writing && (!error.status || error.status >= 500)) {
          model.conflict = true;
          model.message = 'Could not confirm the request. Reload to check the latest package.';
        } else if (error.status === 409 && action !== 'delete') {
          model.conflict = true;
          model.message = action === 'publish' && writing ?
            'The draft or publish targets changed. Reload and review before publishing again.' :
            action === 'save' ? 'The draft changed elsewhere. Reload to review your unsaved edits.' : error.message;
        } else {
          model.message = error.status ? error.message : 'Could not ' + action + ' the package.';
        }
      } finally {
        model.busy = false;
        renderEditor();
      }
    }

    function canSwitchPackage(id) {
      if (!current || current.id === id) return true;
      if (current.busy) return false;
      return !current.saved || !hasChanges(current) || confirmAction('Discard the unsaved changes in "' + current.savedName + '"?');
    }

    function selectPackage(id, profile) {
      if (current?.id === id) return;
      current = { id, expanded: new Set(['basic']) };
      renderCatalog();
      if (!profile) return request(current, 'reload');
      readProfile(current, profile);
      renderEditor();
    }

    async function createPackage(event) {
      event.preventDefault();
      const input = createForm.querySelector('input');
      const feedback = createForm.querySelector('[role="status"]');
      const name = input.value.trim();
      if (creating || !name) return;
      creating = true;
      const button = createForm.querySelector('button');
      button.disabled = input.disabled = true;
      feedback.textContent = 'Creating package…';
      const before = snapshot;
      const editorBefore = current;
      try {
        const profile = await api('/api/packages', 'POST', { name });
        updateSummary(before, profile, profile.package_id);
        if (current === editorBefore && canSwitchPackage(profile.package_id)) {
          selectPackage(profile.package_id, profile);
        }
        input.value = '';
        feedback.textContent = 'Package created.';
      } catch (error) {
        feedback.textContent = error.status ? error.message : 'Could not create the package.';
      } finally {
        creating = false;
        button.disabled = input.disabled = false;
      }
    }

    function renderGroup(name) {
      if (name !== (group?.name ?? null)) {
        group = name ? groupEdits.get(name) || { name } : null;
        groupPanel.innerHTML = group ? `<section class="settings-section">
          <header><h3>Configuration package</h3><p>Choose which package this group receives. Saving applies its published revision to members. Clearing the assignment leaves device values in place.</p></header>
          <div class="settings-assignment">
            <label class="settings-name">Assigned package<select class="field" data-group-package></select></label>
            <div class="settings-buttons">
              <button type="button" class="btn btn-primary" data-group-action="assign">Save assignment</button>
              <button type="button" class="btn-outline" data-group-action="edit">Edit assigned package</button>
            </div>
          </div>
          <div class="settings-feedback" data-assignment-feedback role="status"></div>
          <section class="settings-results">
            <h4>Device status</h4>
            <p class="settings-meta" data-group-publication></p>
            <div class="settings-device-results" data-settings-results></div>
          </section>
        </section>` : '';
      }
      refreshGroup();
    }

    function refreshGroup() {
      if (!group) return;
      const assigned = assignment(group.name);
      const selected = group.selection === undefined ? assigned : group.selection;
      const select = groupPanel.querySelector('[data-group-package]');
      const known = packageById(selected);
      const options = '<option value="">No package</option>' +
        (catalog || []).map(pkg => `<option value="${esc(pkg.package_id)}">${esc(pkg.name)}</option>`).join('') +
        (selected && !known ? `<option value="${esc(selected)}" disabled>Package unavailable</option>` : '') +
        `<option value="${CREATE_PACKAGE_OPTION}">Create package…</option>`;
      if (select.packageOptions !== options) {
        select.packageOptions = options;
        select.innerHTML = options;
      }
      if (select.value !== (selected || '')) select.value = selected || '';
      select.disabled = !snapshot || Boolean(group.busy);
      groupPanel.querySelector('[data-group-action="assign"]').disabled =
        !snapshot || Boolean(group.busy) || selected === assigned || Boolean(selected && !known);
      groupPanel.querySelector('[data-group-action="edit"]').hidden = !packageById(assigned);
      const feedback = groupPanel.querySelector('[data-assignment-feedback]');
      feedback.textContent = group.message || '';
      feedback.classList.toggle('settings-error', Boolean(group.error));
      const deployment = currentDeployment(group.name);
      groupPanel.querySelector('[data-group-publication]').textContent = deployment
        ? 'Published revision ' + deployment.revision : assigned ? 'No revision published yet.' : 'No package assigned.';
      renderResults();
    }

    function retryKey(deviceId, deployment) {
      return JSON.stringify([deviceId, deployment.package_id, deployment.revision]);
    }

    function renderResults() {
      if (!group) return;
      const target = groupPanel.querySelector('[data-settings-results]');
      const deployment = currentDeployment(group.name);
      const assigned = assignment(group.name);
      const devices = getDevices();
      const openResults = new Set(Array.from(target.querySelectorAll('[data-result-device][open]'), result => result.dataset.resultDevice));
      const byId = new Map(devices.map(device => [device.device_id || device.serial_number, device]));
      for (const device of devices) {
        if (device.serial_number && !byId.has(device.serial_number)) byId.set(device.serial_number, device);
      }
      const seen = new Set();
      const rows = [];
      for (const member of getGroups()[group.name] || []) {
        const device = byId.get(member);
        const id = device?.device_id || device?.serial_number || member;
        if (seen.has(id)) continue;
        seen.add(id);
        const [state, label, detail] = deviceOutcome(device, assigned, deployment);
        const readiness = wifiReadiness(device, deployment);
        let button = '';
        if (deployment) {
          const key = retryKey(id, deployment);
          const pending = retrying.get(key);
          if (pending && device?.configuration_state && pending.report !== JSON.stringify(device.configuration_state)) {
            pending.reportChanged = true;
            if (!pending.sending) retrying.delete(key);
          }
          if (retryEligible(device, deployment)) {
            const waiting = retrying.has(key);
            button = `<button type="button" class="btn-outline" data-group-action="retry" data-device-id="${esc(id)}" ${waiting ? 'disabled' : ''}>${waiting ? 'Retry requested' : 'Retry'}</button>`;
          }
        }
        rows.push(`<article class="settings-device-result ${state}">
          <div class="settings-result-body">
            <div class="settings-result-head"><strong>${esc(device?.label || 'Unlabeled device')}</strong><span>${esc(label)}</span></div>
            <div class="settings-meta">${deviceIdHtml(device || {}, id)} · ${esc(detail)}</div>
            ${readiness ? `<div class="settings-meta">${readiness}</div>` : ''}
            ${reportedResults(device?.configuration_state, deployment, items, id, openResults.has(id))}
          </div>${button}
        </article>`);
      }
      const content = rows.join('') || '<p class="settings-meta">This group has no devices.</p>';
      if (target.resultContent !== content) {
        target.resultContent = content;
        target.innerHTML = content;
      }
      if (deployment) readResultSections(assigned);
    }

    async function saveAssignment(model) {
      const name = model.name;
      const selected = model.selection;
      if (selected === undefined || selected === assignment(name) || (selected && !packageById(selected))) return;
      model.busy = true;
      model.message = 'Saving assignment…';
      model.error = false;
      refreshGroup();
      const before = snapshot;
      try {
        const data = await api('/api/group-package?name=' + encodeURIComponent(name), 'PUT', { package_id: selected });
        if (snapshot === before) {
          const assignments = { ...snapshot.assignments, [name]: data.package_id };
          const deployments = { ...snapshot.deployments };
          if (!data.package_id) delete assignments[name];
          const revision = packageById(data.package_id)?.published_revision;
          // Only GROUP_LIST supplies the managed keys of the assigned revision.
          if (currentDeployment(name)?.package_id !== data.package_id) delete deployments[name];
          snapshot = { assignments, deployments };
          model.message = revision ? 'Assignment saved. Waiting for device status.' : 'Assignment saved.';
        } else {
          model.message = assignment(name) === selected ? 'Assignment saved.' : 'Check the current assignment.';
        }
        delete model.selection;
      } catch (error) {
        model.message = error.status ? error.message : 'Could not confirm the assignment. Check the current assignment before retrying.';
        model.error = true;
      } finally {
        model.busy = false;
        refreshGroup();
        renderPublication();
      }
    }

    async function retryDevice(model, deviceId) {
      const deployment = currentDeployment(model.name);
      const device = getDevices().find(candidate => candidate.device_id === deviceId);
      if (!retryEligible(device, deployment) ||
          !(getGroups()[model.name] || []).some(id => id === deviceId || id === device.serial_number)) return;
      const key = retryKey(deviceId, deployment);
      const report = JSON.stringify(device.configuration_state);
      if (retrying.has(key)) return;
      const pending = { sending: true, report };
      retrying.set(key, pending);
      renderResults();
      try {
        await api('/api/package-retry', 'POST', {
          device_id: deviceId, package_id: deployment.package_id, revision: deployment.revision,
        });
        pending.sending = false;
        if (pending.reportChanged) {
          retrying.delete(key);
          return;
        }
        setTimeout(() => {
          if (retrying.get(key) !== pending) return;
          retrying.delete(key);
          renderResults();
        }, 8000);
      } catch (error) {
        retrying.delete(key);
        model.error = true;
        model.message = error.status ? error.message : 'Could not confirm the Retry request.';
      } finally {
        refreshGroup();
      }
    }

    function handleInput(event) {
      const model = current;
      const target = event.target;
      if (!model?.draft || model.busy || model.conflict || target.matches(':disabled')) return;
      const { manage, setting, network, field, day } = target.dataset;
      if (Object.hasOwn(target.dataset, 'packageName')) {
        model.name = target.value;
      } else if (manage) {
        model.draft[manage].managed = target.checked;
        target.closest('.settings-row').querySelector('.settings-value').disabled = !target.checked;
        const section = target.closest('[data-settings-section]');
        section.querySelector('.settings-count').textContent =
          sectionCount(model.sections.find(candidate => candidate.id === section.dataset.settingsSection), model.draft);
      } else if (setting) {
        const entry = model.draft[setting];
        const value = entry.value;
        if (!entry.managed) return;
        if (network !== undefined) {
          const row = value[Number(network)];
          row[field] = target.type === 'checkbox' ? target.checked : target.value;
          const article = target.closest('.wifi-network');
          if (field === 'security') {
            const password = article.querySelector('[data-field="password"]');
            password.disabled = row.security === 'OPEN';
            if (password.disabled) row.password = password.value = '';
          }
          article.querySelector('[data-password-help]').textContent = wifiPasswordHelp(row, model.saved[setting].value);
        } else if (field === 'enabled') {
          value.enabled = target.checked;
          target.closest('.settings-value').querySelectorAll('[data-field="time"], [data-field="day"]')
            .forEach(input => { input.disabled = !value.enabled; });
        } else if (field === 'time') {
          const time = target.value;
          [value.hour, value.minute] = time ? time.split(':').map(Number) : [null, null];
        } else if (field === 'day') {
          const bit = 1 << Number(day);
          value.repeat = target.checked ? value.repeat | bit : value.repeat & ~bit;
        } else if (target.type === 'radio') {
          entry.value = target.value === 'true';
        } else {
          entry.value = Number(target.value);
          if (target.type === 'range') target.nextElementSibling.value = target.value + '%';
        }
      } else return;
      model.error = false;
      model.message = '';
      updateActions();
    }

    function handleEditorClick(event) {
      const button = event.target.closest('[data-policy-action]');
      const model = current;
      if (!button || button.disabled || !model || model.busy) return;
      const action = button.dataset.policyAction;
      if (action === 'reload') return request(model, 'reload');
      if (!model.saved || model.conflict) return;
      if (action === 'discard') {
        model.name = model.savedName;
        model.draft = structuredClone(model.saved);
        model.message = 'Changes discarded.';
        model.error = false;
        renderEditor();
      } else if (action === 'save' && (hasChanges(model) || !model.draftRevision)) {
        const name = model.name.trim();
        if (!name) return;
        let settings;
        try {
          settings = buildPayload(model.sections, model.draft, model.saved);
        } catch (error) {
          model.message = error.message;
          model.error = true;
          updateActions();
          return;
        }
        return request(model, 'save', { expected_revision: model.draftRevision, settings,
          ...(name !== model.savedName ? { name } : {}) });
      } else if (action === 'publish' && !hasChanges(model) && model.draftRevision) {
        return request(model, 'publish');
      } else if (action === 'delete') {
        if (confirmAction('Delete configuration package "' + model.savedName + '"?')) return request(model, 'delete');
      } else if (action === 'add-network' || action === 'remove-network') {
        const { setting, network } = button.dataset;
        const entry = model.draft[setting];
        if (!entry?.managed) return;
        if (action === 'add-network' && entry.value.length < MAX_NETWORKS) {
          entry.value.push({ ssid: '', security: 'OPEN', hidden: false, password: '' });
        } else if (action === 'remove-network') {
          entry.value.splice(Number(network), 1);
        }
        model.error = false;
        model.message = '';
        renderEditor();
      }
    }

    groupPanel.addEventListener('change', event => {
      if (!group || !event.target.matches('[data-group-package]')) return;
      const selected = event.target.value || null;
      if (selected === CREATE_PACKAGE_OPTION) {
        refreshGroup();
        showPackages();
        createForm.querySelector('input').focus();
        return;
      }
      if (selected === assignment(group.name)) delete group.selection;
      else group.selection = selected;
      group.error = false;
      group.message = '';
      groupEdits.set(group.name, group);
      refreshGroup();
    });
    groupPanel.addEventListener('click', event => {
      const button = event.target.closest('[data-group-action]');
      if (!group || !button || button.disabled) return;
      const action = button.dataset.groupAction;
      if (action === 'assign') return saveAssignment(group);
      if (action === 'retry') return retryDevice(group, button.dataset.deviceId);
      if (action === 'edit') return showPackages(assignment(group.name));
    });
    packageList.addEventListener('click', event => {
      const button = event.target.closest('[data-package-id], [data-refresh-packages]');
      if (!button) return;
      const id = button.dataset.packageId;
      if (!id) return loadCatalog();
      if (canSwitchPackage(id)) return selectPackage(id);
    });
    createForm.addEventListener('submit', createPackage);
    packagePanel.addEventListener('input', handleInput);
    packagePanel.addEventListener('click', handleEditorClick);
    packagePanel.addEventListener('toggle', event => {
      const section = event.target.dataset.settingsSection;
      if (!section || !current) return;
      if (event.target.open) current.expanded.add(section);
      else current.expanded.delete(section);
    }, true);

    function showPackages(id) {
      if (id && !canSwitchPackage(id)) return;
      openPackages();
      loadCatalog();
      if (id) return selectPackage(id);
    }

    renderEditor();
    return { renderGroup, updateSnapshot, showPackages };
  }

  const api = { createController, membershipGroups, syncMemberSelection, loadDraft, buildPayload, deviceOutcome, retryEligible };
  root.MdmConfigurationPackages = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
