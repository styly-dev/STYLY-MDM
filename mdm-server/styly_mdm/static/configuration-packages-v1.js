(function (root) {
  'use strict';

  const CREATE_PACKAGE_OPTION = 'create-package';

  function membershipGroups(deviceId, groups) {
    return Object.keys(groups || {}).filter(function (name) {
      return (groups[name] || []).includes(deviceId);
    }).sort();
  }

  function syncMemberSelection(previous, next, selected, edited) {
    const previousMembers = new Set(previous);
    const editedMembers = edited || new Set();
    const selection = new Set(next.filter(id => editedMembers.has(id)
      ? selected.has(id) : !previousMembers.has(id) || selected.has(id)));
    for (const id of selected) {
      if (!previousMembers.has(id) || editedMembers.has(id)) selection.add(id);
    }
    return selection;
  }

  function clearMembershipEditsThatMatch(selected, current, edited, expectedOwners) {
    const members = new Set(current);
    for (const deviceId of edited) {
      if (selected.has(deviceId) === members.has(deviceId)) {
        edited.delete(deviceId);
        expectedOwners.delete(deviceId);
      }
    }
  }

  function expectedMembershipOwner(deviceId, name, groups) {
    const owners = membershipGroups(deviceId, groups);
    if (owners.length > 1) throw new Error('Resolve this device’s conflicting group memberships before editing it.');
    return owners[0] ?? null;
  }

  function buildMembershipEdit(name, currentMembers, selectedMembers, groups, expectedOwners) {
    const current = new Set();
    for (const deviceId of currentMembers) {
      if (current.has(deviceId)) throw new Error('Resolve duplicate device IDs in this group before editing it.');
      current.add(deviceId);
    }
    const selected = new Set(selectedMembers);
    const add = new Map();
    const remove = [];

    for (const deviceId of selected) {
      if (current.has(deviceId)) continue;
      if (!expectedOwners.has(deviceId)) throw new Error('Choose the source group for each device again before saving.');
      const owners = membershipGroups(deviceId, groups);
      if (owners.includes(name) || owners.filter(group => group !== name).length > 1 ||
          expectedMembershipOwner(deviceId, name, groups) !== expectedOwners.get(deviceId)) {
        throw new Error('Group membership changed. Remove and add the device again after reviewing its current group.');
      }
      add.set(deviceId, expectedOwners.get(deviceId));
    }

    for (const deviceId of current) {
      if (selected.has(deviceId)) continue;
      const owners = membershipGroups(deviceId, groups);
      if (!owners.includes(name) || owners.length !== 1) {
        throw new Error('Group membership changed. Review the current members before saving again.');
      }
      remove.push(deviceId);
    }

    return { type: 'UPDATE_GROUP_MEMBERS', name, add: Object.fromEntries(add), remove };
  }

  const MAX_WIFI_NETWORKS = 4;
  const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  const WIFI_ORDER_HELP = 'Earlier networks in this list have higher priority.';
  const WIFI_ANDROID_HELP = 'Android chooses among saved Wi-Fi networks. Listed SSID and security matches have auto-connect on. Other saved networks have auto-connect off.';

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (character) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character];
    });
  }

  function loadDraft(sections, settings) {
    const draft = {};
    for (const item of sections.flatMap(section => section.items)) {
      const saved = settings?.[item.id];
      let value = saved ? saved.value : item.default;
      if (item.kind === 'wifi') {
        value = {
          auto_return: value.auto_return === true,
          prioritize_networks: value.prioritize_networks !== false,
          networks: value.networks.map(({ ssid, security, hidden, password_present }) => ({
            ssid, security, hidden, password_present, password: '',
          })),
        };
      }
      draft[item.id] = { managed: saved?.managed === true, value };
    }
    return structuredClone(draft);
  }

  function hasSavedPassword(network, saved) {
    return saved.some(candidate => candidate.ssid === network.ssid &&
      candidate.security === network.security && candidate.password_present);
  }

  function supportedValue(item, value) {
    if (item.kind === 'range') {
      if (typeof value !== 'number' || !Number.isInteger(value)) return false;
      const min = Number(item.min ?? 0);
      const max = Number(item.max ?? 100);
      return value >= min && value <= max;
    }
    if (item.kind === 'delay') return item.options?.some(option => option.value === value) === true;
    if (item.kind === 'bool') return typeof value === 'boolean';
    return true;
  }

  function validScheduleTime(value) {
    return value && Number.isInteger(value.hour) && value.hour >= 0 && value.hour <= 23 &&
      Number.isInteger(value.minute) && value.minute >= 0 && value.minute <= 59;
  }

  function validScheduleRepeat(value) {
    return Number.isInteger(value) && value > 0 && value <= 127;
  }

  function schedulePayload(item, value, savedValue, active) {
    const schedule = { ...value };
    if (active) {
      if (!validScheduleTime(schedule)) throw new Error('Enter a time for ' + item.label + '.');
      if (!validScheduleRepeat(schedule.repeat)) throw new Error('Select at least one day for ' + item.label + '.');
      return schedule;
    }
    const fallback = validScheduleTime(savedValue) ? savedValue : item.default;
    if (schedule.hour == null) schedule.hour = fallback.hour;
    if (schedule.minute == null) schedule.minute = fallback.minute;
    if (!validScheduleRepeat(schedule.repeat)) {
      schedule.repeat = validScheduleRepeat(savedValue?.repeat) ? savedValue.repeat : item.default.repeat;
    }
    return schedule;
  }

  function wifiPayload(value, savedNetworks, managed) {
    const networks = value?.networks;
    if (!Array.isArray(networks)) throw new Error('Wi-Fi networks must be a list.');
    // buildPayload() has already rejected the wifiConflicts() cases (network limit, hidden + auto-return).
    if (managed && !networks.length) throw new Error('Add at least one Wi-Fi network when Wi-Fi is managed.');
    if (typeof value.auto_return !== 'boolean') throw new Error('Choose whether Wi-Fi should automatically return.');
    if (typeof value.prioritize_networks !== 'boolean') throw new Error('Choose whether package Wi-Fi priority is enabled.');
    if (!value.prioritize_networks && value.auto_return) {
      throw new Error('Turn off automatic return when Android selects Wi-Fi networks.');
    }
    const identities = new Set();
    const payloadNetworks = networks.map(function (network, index) {
      const { ssid, security, hidden, password } = network;
      if (!ssid) throw new Error('Enter an SSID for Wi-Fi ' + (index + 1) + '.');
      if (!['OPEN', 'WPA2_WPA3'].includes(security)) {
        throw new Error('Choose Open or WPA2/WPA3 security for Wi-Fi ' + (index + 1) + '.');
      }
      if (typeof hidden !== 'boolean') throw new Error('Choose whether Wi-Fi ' + (index + 1) + ' is hidden.');
      const identity = JSON.stringify([ssid, security]);
      if (identities.has(identity)) throw new Error('Each SSID and security combination must be unique.');
      identities.add(identity);
      const result = { ssid, security, hidden };
      if (security === 'WPA2_WPA3') {
        if (password != null && typeof password !== 'string') {
          throw new Error('Wi-Fi ' + (index + 1) + ' password must be text.');
        }
        if (password) {
          if (!/^[\x20-\x7e]{1,63}$/.test(password)) {
            throw new Error('Wi-Fi ' + (index + 1) + ' password must be 1 to 63 ASCII characters. Passwords of 1 to 7 characters require WPA3.');
          }
          result.password = password;
        } else if (!hasSavedPassword(network, savedNetworks)) {
          throw new Error('Enter a 1 to 63 character ASCII password for Wi-Fi ' + (index + 1) + '.');
        }
      }
      return result;
    });
    const result = { networks: payloadNetworks, auto_return: value.auto_return };
    if (!value.prioritize_networks) result.prioritize_networks = false;
    return result;
  }

  function moveWifiNetwork(networks, index, direction) {
    const destination = index + direction;
    if (!Number.isInteger(index) || !Number.isInteger(destination) ||
        index < 0 || destination < 0 || index >= networks.length || destination >= networks.length) return false;
    [networks[index], networks[destination]] = [networks[destination], networks[index]];
    return true;
  }

  // Draft-wide Wi-Fi rules shared by the editor warnings and buildPayload(). The
  // network limit applies even when Wi-Fi is unmanaged, as on the server.
  function wifiConflicts(entry) {
    const networks = Array.isArray(entry?.value?.networks) ? entry.value.networks : [];
    return {
      tooManyNetworks: networks.length > MAX_WIFI_NETWORKS,
      autoReturnWithHidden: entry?.managed === true && entry.value.prioritize_networks !== false && entry.value.auto_return === true &&
        networks.some(network => network.hidden),
    };
  }

  function updateWifiWarnings(valuePanel, draft) {
    const conflicts = wifiConflicts(draft.wifi);
    const warning = valuePanel?.querySelector('[data-wifi-hidden-warning]');
    if (warning) warning.hidden = !conflicts.autoReturnWithHidden;
    const limitWarning = valuePanel?.querySelector('[data-wifi-limit-warning]');
    if (limitWarning) limitWarning.hidden = !conflicts.tooManyNetworks;
    const addButton = valuePanel?.querySelector('[data-policy-action="add-network"]');
    const networks = draft.wifi?.value.networks || [];
    if (addButton) addButton.disabled = draft.wifi?.managed !== true || networks.length >= MAX_WIFI_NETWORKS;
  }

  function updateWifiMoveButtons(valuePanel, draft) {
    const networks = draft.wifi?.value.networks || [];
    valuePanel.querySelectorAll('[data-policy-action="move-network"]').forEach(button => {
      const index = Number(button.dataset.network);
      const boundary = button.dataset.direction === 'up' ? index === 0 : index === networks.length - 1;
      button.disabled = draft.wifi?.managed !== true || draft.wifi?.value.prioritize_networks === false || boundary;
    });
  }

  function buildPayload(sections, draft, saved) {
    const conflicts = wifiConflicts(draft.wifi);
    if (conflicts.autoReturnWithHidden) {
      throw new Error('Automatic return is not supported with hidden Wi-Fi networks. Turn off auto-return or clear Hidden network.');
    }
    if (conflicts.tooManyNetworks) {
      throw new Error('Wi-Fi supports up to 4 networks. Remove extra networks before saving.');
    }
    const settings = {};
    for (const item of sections.flatMap(section => section.items)) {
      const entry = draft[item.id];
      if (!entry) continue;
      const managed = entry.managed;
      const { value } = entry;
      let payloadValue = value;
      if (item.kind === 'schedule') {
        payloadValue = schedulePayload(item, value, saved[item.id]?.value, managed && value.enabled === true);
      } else if (managed && !supportedValue(item, value)) {
        throw new Error('Select a supported value for ' + item.label + '.');
      }
      settings[item.id] = {
        managed,
        value: item.kind === 'wifi'
          ? wifiPayload(value, saved[item.id].value.networks, managed)
          : payloadValue,
      };
    }
    return settings;
  }

  function matchesDeployment(report, deployment) {
    return Boolean(deployment && report?.package_id === deployment.package_id && report.revision === deployment.revision);
  }

  function wifiAwaitingReboot(report) {
    const result = report?.results?.wifi;
    return result?.status === 'failed' && result.reason === 'VERIFY_AFTER_REBOOT';
  }

  function wifiIsOnlyPendingResult(report, deployment) {
    return Boolean(deployment?.keys.includes('wifi') && wifiAwaitingReboot(report) &&
      deployment.keys.every(key => key === 'wifi' || report.results?.[key]?.status === 'applied'));
  }

  function retryEligible(device, deployment) {
    const report = device?.configuration_state;
    if (device?.status !== 'online' || device.configuration_supported !== true ||
        !matchesDeployment(report, deployment)) return false;
    if (report.status === 'interrupted') return true;
    if (report.status !== 'complete') return false;
    return deployment.keys.some(key => ['failed', 'unsupported'].includes(report.results?.[key]?.status) &&
      !(key === 'wifi' && wifiAwaitingReboot(report)));
  }

  function deviceOutcome(device, packageId, deployment) {
    const online = device?.status === 'online';
    const connection = online ? '' : ' · Offline';
    if (!packageId) return ['', 'No package' + connection, 'This group has no managed settings.'];
    if (!deployment) return ['pending', 'Waiting for deployment' + connection, 'Deploy the group package to send its settings to devices.'];
    if (online && device.configuration_supported !== true) {
      return ['unavailable', 'Client update needed', 'This client does not report support for configuration packages.'];
    }
    const report = device?.configuration_state;
    if (!matchesDeployment(report, deployment) || (!online && report.status === 'applying')) {
      return ['pending', 'Pending' + connection, 'Waiting for the device to report this deployment.'];
    }
    if (report.status === 'interrupted') {
      return ['failed', 'Interrupted' + connection, 'Some settings may have changed. Retry when the device is online.'];
    }
    if (report.status === 'applying') return ['pending', 'Applying', 'The device is applying and checking managed settings.'];
    if (report.status !== 'complete') {
      return ['pending', 'Pending' + connection, 'Waiting for the device to report the outcome.'];
    }
    if (wifiIsOnlyPendingResult(report, deployment)) {
      return ['pending', 'Saved. Verify after reboot.' + connection,
        'Wi-Fi settings were saved. Restart the headset to verify the connection.'];
    }
    const results = deployment.keys.map(key => report.results?.[key]?.status);
    const applied = results.filter(status => status === 'applied').length;
    if (results.every(status => status === 'applied')) {
      const wifiSaved = deployment.keys.includes('wifi') && report.results?.wifi?.reason === 'SAVED';
      return ['success', results.length ? 'Applied' + connection : 'No managed settings' + connection,
        results.length ? wifiSaved ? 'Wi-Fi profiles and auto-connect flags were read back. Android selects the network.' : 'All managed settings were verified.' : 'The device acknowledged this revision.'];
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
      ? 'Leave blank to keep the saved password. Use 1–63 ASCII characters; 1–7 is WPA3-only.'
      : 'Enter 1–63 ASCII characters. Passwords of 1–7 characters require WPA3.';
  }

  function renderWifi(item, value, saved, secretType, managed) {
    const networks = value.networks;
    const rows = networks.map(function (network, index) {
      const field = `data-setting="${esc(item.id)}" data-network="${index}"`;
      const isOpen = network.security === 'OPEN';
      const moveActions = value.prioritize_networks ? `
            <button class="btn-outline wifi-move" type="button" aria-label="Move Wi-Fi ${index + 1} up" data-policy-action="move-network" data-direction="up" ${field} ${!managed || index === 0 ? 'disabled' : ''}>Up</button>
            <button class="btn-outline wifi-move" type="button" aria-label="Move Wi-Fi ${index + 1} down" data-policy-action="move-network" data-direction="down" ${field} ${!managed || index === networks.length - 1 ? 'disabled' : ''}>Down</button>` : '';
      return `<article class="wifi-network">
        <div class="wifi-network-head">${value.prioritize_networks ? `<strong>Wi-Fi ${index + 1}</strong>` : ''}
          <div class="wifi-network-actions">
            ${moveActions}
          <button class="btn-outline btn-danger-outline wifi-remove" type="button" data-policy-action="remove-network" ${field}>Remove from package</button>
          </div>
        </div>
        <label>SSID<input class="field" ${field} data-field="ssid" value="${esc(network.ssid)}"></label>
        <div class="wifi-fields">
          <label>Security<select class="field" ${field} data-field="security">
            <option value="OPEN" ${isOpen ? 'selected' : ''}>Open</option>
            <option value="WPA2_WPA3" ${!isOpen ? 'selected' : ''}>WPA2/WPA3 Personal</option>
          </select></label>
          <label>Password<input class="field wifi-secret" type="${secretType}" ${field} data-field="password" autocomplete="off" spellcheck="false" ${isOpen ? 'disabled' : ''}></label>
        </div>
        <div class="wifi-row-foot">
          <small data-password-help="${index}">${wifiPasswordHelp(network, saved.networks)}</small>
          <label class="wifi-check"><input type="checkbox" ${field} data-field="hidden" ${network.hidden ? 'checked' : ''}> Hidden network</label>
        </div>
      </article>`;
    }).join('');
    const conflicts = wifiConflicts({ managed, value });
    const strategyControls = value.prioritize_networks ? `<small class="wifi-order-note" data-wifi-order-note>${WIFI_ORDER_HELP}</small>
      <label><input type="checkbox" data-setting="${esc(item.id)}" data-field="auto_return" ${value.auto_return ? 'checked' : ''}> Automatically return to a higher-priority network when it becomes available</label>
      <small>Checks for higher-priority networks about every 5 minutes while connected below the first choice, including after manual selection or fallback. If a move fails and the headset is still disconnected, it tries the previous network again. Android may delay detection.</small>` : '';
    const priorityControls = `<div class="wifi-options">
      <label><input type="checkbox" data-setting="${esc(item.id)}" data-field="prioritize_networks" ${value.prioritize_networks ? 'checked' : ''}> Prioritize package Wi-Fi networks</label>
      ${strategyControls}
      <small class="wifi-warning" data-wifi-limit-warning role="alert" ${conflicts.tooManyNetworks ? '' : 'hidden'}>Wi-Fi supports up to 4 networks. Remove extra networks before deploying.</small>
      <small class="wifi-warning" data-wifi-hidden-warning role="alert" ${conflicts.autoReturnWithHidden ? '' : 'hidden'}>Automatic return is not supported with hidden Wi-Fi networks. Turn off auto-return or clear Hidden network before deploying.</small>
    </div>`;
    return `<div class="settings-networks">
      <div class="push-note">${value.prioritize_networks ? 'Only the first listed Wi-Fi network is enabled for Android auto-connect. Other saved networks have auto-connect off. The headset tries listed networks in order when needed.' : WIFI_ANDROID_HELP} Devices can lose contact with MDM if none of the listed networks connects.</div>
      ${rows}
      ${priorityControls}
      <button type="button" class="btn-outline" data-policy-action="add-network" data-setting="${esc(item.id)}" ${!managed || networks.length >= MAX_WIFI_NETWORKS ? 'disabled' : ''}>Add Wi-Fi network</button>
    </div>`;
  }

  function scheduleTime(value) {
    return value.hour === null || value.minute === null ? '' :
      [value.hour, value.minute].map(number => String(number).padStart(2, '0')).join(':');
  }

  function renderControl(item, value, saved, secretType, managed) {
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
        return renderWifi(item, value, saved, secretType, managed);
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
      ${entry ? `<fieldset class="settings-value" aria-label="${label}" ${entry.managed ? '' : 'disabled'}>${renderControl(item, entry.value, saved[item.id].value, secretType, entry.managed)}</fieldset>` :
        '<span class="settings-meta">Unavailable</span>'}
    </div>`;
  }

  function sectionCount(section, draft) {
    const managed = section.items.filter(item => draft[item.id]?.managed).length;
    return managed + ' / ' + section.items.length + ' managed';
  }

  function renderSection(section, model, secretType) {
    return `<details class="settings-group" data-settings-section="${esc(section.id)}" ${model.expanded.has(section.id) ? 'open' : ''}>
      <summary class="settings-group-head">
        <span><strong>${esc(section.title)}</strong><small>${esc(section.description)}</small></span>
        <span class="settings-count">${sectionCount(section, model.draft)}</span>
        <span class="settings-chevron" aria-hidden="true"></span>
      </summary>
      <div class="settings-content">${section.items
        .map(item => renderItem(item, model.draft, model.saved, secretType)).join('')}</div>
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
      const deferredWifi = key === 'wifi' && wifiAwaitingReboot(report);
      const savedWifi = key === 'wifi' && result.status === 'applied' && result.reason === 'SAVED';
      const status = deferredWifi ? 'Saved. Verify after reboot.' : savedWifi ? 'Saved. Android selects the network.' :
        { applied: 'Applied', failed: 'Failed', unsupported: 'Unsupported' }[result.status];
      if (!status) return '';
      const actual = actualValue(item, result.value);
      const reason = result.reason && !deferredWifi && !savedWifi
        ? ' · ' + String(result.reason).replaceAll('_', ' ').toLowerCase() : '';
      return `<li>${esc(item?.label || key)} · ${status}${actual ? ' · ' + esc(actual) : ''}${esc(reason)}</li>`;
    }).filter(Boolean).join('');
    return lines ? `<details class="settings-readback" data-result-device="${esc(id)}" ${open ? 'open' : ''}><summary>Results at apply time</summary><ul>${lines}</ul></details>` : '';
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

  function wifiAutoReturnMonitor(device, deployment) {
    if (device?.status !== 'online' || !deployment?.keys.includes('wifi') ||
        deployment.wifi_mode === 'android_selection') return '';
    const labels = {
      off: 'Not scanning', monitoring: 'Monitoring', degraded: 'Degraded',
      switching: 'Switching', unsupported: 'Unsupported',
    };
    const label = labels[device.wifi_state?.priority_monitor];
    return label ? 'Wi-Fi auto-return monitor: ' + label + '.' : '';
  }

  function createController({
    groupPanel, packagePanel, packageList, createForm, getGroups, getDevice, openPackages,
    deviceIdHtml = (_, id) => esc(id), fetch = root.fetch.bind(root),
    confirmAction = message => root.confirm(message),
    onSnapshot = () => {},
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
    const resultSectionsByPackage = new Map();
    let retryFailedResultSectionsOnOpen = false;
    let creating = false;
    const packagePath = id => '/api/packages/' + encodeURIComponent(id);
    const packageById = id => catalog?.find(candidate => candidate.package_id === id);
    const assignment = name => Object.hasOwn(snapshot?.assignments || {}, name) ? snapshot.assignments[name] : null;
    const assignedPackage = model => assignment(model.name);
    function selectedPackage(model) {
      return model.selection === undefined ? assignedPackage(model) : model.selection;
    }
    function deploymentFor(name, packageId) {
      const deployment = Object.hasOwn(snapshot?.deployments || {}, name) ? snapshot.deployments[name] : null;
      return deployment?.package_id === packageId ? deployment : null;
    }
    function currentDeployment(model) {
      return deploymentFor(model.name, assignment(model.name));
    }

    function groupOutcomes(name, packageId, deployment) {
      const outcomes = new Map();
      for (const member of getGroups()[name] || []) {
        if (outcomes.has(member)) continue;
        const device = getDevice(member);
        outcomes.set(member, { device, outcome: deviceOutcome(device, packageId, deployment) });
      }
      return outcomes;
    }

    function deploymentCount(outcomes, deployment) {
      let applied = 0;
      let failed = 0;
      for (const { outcome: [state] } of outcomes.values()) {
        if (state === 'success') applied++;
        if (state === 'failed' || state === 'unavailable') failed++;
      }
      const total = outcomes.size;
      return {
        text: 'Applied ' + applied + '/' + total,
        state: !deployment || !total ? '' : applied === total ? 'success' : failed === total ? 'failed' : 'pending',
      };
    }

    function groupPackageSummary(name) {
      const packageId = assignment(name);
      if (!packageId) return null;
      const deployment = deploymentFor(name, packageId);
      return {
        name: packageById(packageId)?.name,
        count: deployment ? deploymentCount(groupOutcomes(name, packageId, deployment), deployment) : null,
      };
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

    function updateSnapshot(data, source = 'http') {
      if (!Number.isSafeInteger(data?.revision) || data.revision < 0 ||
          typeof data?.server_instance_id !== 'string' || !data.server_instance_id) return false;
      const instanceChanged = snapshot && data.server_instance_id !== snapshot.server_instance_id;
      if (instanceChanged && source !== 'ws') return false;
      if (!instanceChanged && snapshot && data.revision < snapshot.revision) return false;
      snapshot = { server_instance_id: data.server_instance_id, revision: data.revision,
        assignments: data.assignments || {}, deployments: data.deployments || {} };
      catalog = data.packages || [];
      catalogError = '';
      const currentGroups = data.groups || getGroups();
      for (const [name, model] of groupEdits) {
        if (!Object.hasOwn(currentGroups, name)) {
          groupEdits.delete(name);
        }
      }
      renderCatalog();
      renderPublication();
      onSnapshot(data);
      return true;
    }

    function renderCatalog() {
      const content = catalog?.map(pkg =>
        `<button type="button" class="mg-item settings-package-item${current?.id === pkg.package_id ? ' active' : ''}" data-package-id="${esc(pkg.package_id)}" aria-pressed="${current?.id === pkg.package_id}">
          <span class="mg-item-name">${esc(pkg.name)}</span>
          <span class="mg-item-count">${pkg.published_revision ? 'Deployed' : 'New'}</span>
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
      const deployment = revision ? 'Deployed version ' + revision : 'Not deployed yet';
      const refs = Object.values(snapshot?.assignments || {}).filter(id => id === current.id).length;
      target.textContent = deployment +
        (snapshot ? ' · Used by ' + refs + ' ' + (refs === 1 ? 'group' : 'groups') : '');
    }

    async function loadCatalog() {
      if (catalog || catalogLoading) return;
      catalogLoading = true;
      catalogError = '';
      renderCatalog();
      const before = snapshot;
      try {
        const data = await api('/api/packages');
        if (snapshot === before) {
          catalog = data.packages;
          catalogError = '';
        }
      } catch (_) {
        if (snapshot === before) catalogError = 'Could not load configuration packages.';
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
        catalog.push({ package_id: id, name: profile.name, published_revision: profile.published_revision });
        catalog.sort((a, b) => a.name.localeCompare(b.name));
      }
      renderCatalog();
      refreshGroup();
    }

    function hasChanges(model) {
      return model.name !== model.savedName || JSON.stringify(model.draft) !== JSON.stringify(model.saved);
    }

    function updateDeployAction(button, hint, reason) {
      button.disabled = Boolean(reason);
      hint.hidden = !reason;
      hint.textContent = reason;
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
      let deployReason = '';
      if (model.busy) deployReason = 'Wait for the current package operation to finish.';
      else if (model.conflict) deployReason = 'Select Reload package and review the latest version before deploying.';
      else if (!model.name.trim()) deployReason = 'Enter a package name before deploying.';
      updateDeployAction(footer.querySelector('[data-policy-action="deploy"]'), packagePanel.querySelector('[data-deploy-hint]'), deployReason);
      packagePanel.querySelector('[data-policy-action="delete"]').disabled = locked;
      footer.querySelector('.settings-meta').textContent = changed ? 'Unsaved changes' : '';
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
      const deployButton = '<button type="button" class="btn btn-success settings-deploy" data-policy-action="deploy" aria-describedby="packageDeployHint" disabled>Save &amp; deploy</button>';
      if (!model.saved) {
        const error = model.busy ? '' :
          `<p class="settings-error" role="status">${esc(model.message)}</p><button type="button" class="btn-outline" data-policy-action="reload">Reload package</button>`;
        packagePanel.innerHTML = `${error}<div class="settings-buttons settings-deploy-row">
          ${deployButton}
          <p class="settings-deploy-hint" id="packageDeployHint">${model.busy ? 'Waiting for the package to load.' : 'Select Reload package before deploying.'}</p>
        </div>`;
        return;
      }
      packagePanel.innerHTML = `<div class="settings-section">
        <header class="settings-package-head"><div><h3>Edit configuration package</h3><p class="settings-meta" data-package-publication></p></div>
          <button type="button" class="btn-outline btn-danger-outline" data-policy-action="delete">Delete package</button>
        </header>
        <label class="settings-name">Package name<input class="field" data-package-name maxlength="64" value="${esc(model.name)}"></label>
        <div class="push-note">Save &amp; deploy sends these settings to every group using this package. Clearing Manage leaves the device setting in place.</div>
        <fieldset class="settings-editor" aria-label="Package settings">${model.sections.map(section => renderSection(section, model, secretType)).join('')}</fieldset>
        <div class="settings-actions">
          <span class="settings-meta"></span>
          <div class="settings-buttons">
            <button type="button" class="btn-outline" data-policy-action="discard">Discard changes</button>
            ${deployButton}
          </div>
        </div>
        <p class="settings-deploy-hint" id="packageDeployHint" data-deploy-hint hidden></p>
        <div class="settings-feedback" data-settings-feedback role="status"></div>
        <button type="button" class="btn-outline" data-policy-action="reload" hidden>Reload package</button>
      </div>`;
      for (const input of packagePanel.querySelectorAll('[data-field="password"]')) {
        input.value = model.draft[input.dataset.setting].value.networks[Number(input.dataset.network)].password;
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
      Object.assign(model, { sections: data.sections, editRevision: data.edit_revision,
        publishedRevision: data.published_revision,
        savedName: data.name, saved, name, draft });
      readSections(data.sections);
    }

    async function readResultSections(id, retryFailed = false) {
      if (!id) return;
      const previous = resultSectionsByPackage.get(id);
      if (previous?.status === 'loading' || previous?.status === 'loaded' ||
          (previous?.status === 'failed' && !retryFailed)) return;
      const request = { status: 'loading' };
      resultSectionsByPackage.set(id, request);
      try {
        const data = await api(packagePath(id));
        if (resultSectionsByPackage.get(id) !== request) return;
        request.status = 'loaded';
        readSections(data.sections);
      } catch (_) {
        if (resultSectionsByPackage.get(id) === request) request.status = 'failed';
        // Report status is available independently of setting labels and formatters.
      }
    }

    function confirmDeployment(profile, { editorName, groupName } = {}) {
      const { groups, devices } = profile.impact;
      const groupCount = groups + ' ' + (groups === 1 ? 'group' : 'groups');
      const deviceCount = devices + ' ' + (devices === 1 ? 'device' : 'devices');
      const delivery = groups
        ? 'The count includes offline devices, which receive the deployed version when they connect.'
        : 'Devices will receive this version once a group uses this package.';
      if (profile.clear) {
        return confirmAction('Deploy No package to "' + groupName + '" (' + deviceCount + ')?\n\n' +
          'This stops managing all settings for this group and leaves current device values in place. ' +
          (profile.wifi_mode === 'priority' || profile.wifi_mode === 'android_selection'
            ? 'Wi-Fi auto-connect settings still apply after release. To let a network reconnect automatically, enable auto-connect for it on the headset. '
            : '') +
          'Offline devices receive the updated policy when they connect.');
      }
      if (profile.use_current) {
        return confirmAction('Deploy the current version of "' + profile.name + '" to "' + groupName +
          '" (' + deviceCount + ')?\n\n' + delivery);
      }
      const detail = groupName
        ? 'This deploys the saved settings again as a new version to all groups using it.'
        : 'This saves your edits and deploys them.';
      return confirmAction('Deploy "' + (editorName ?? profile.name) + '" to ' + groupCount + ' and ' + deviceCount +
        '?\n\n' + detail + ' ' + delivery);
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
        } else if (action === 'deploy') {
          const fresh = await api(path);
          if (fresh.edit_revision !== model.editRevision) {
            throw Object.assign(new Error('The package changed. Reload and review the latest version before deploying.'), { status: 409 });
          }
          if (!confirmDeployment(fresh, { editorName: model.name.trim() })) return;
          writing = true;
          data = await api(path + '/save-and-deploy', 'POST', { ...body, expected_impact: fresh.impact.token });
        } else {
          writing = true;
          data = await api(path, 'DELETE');
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
        model.message = { deploy: 'Package saved. Deployment started.',
          reload: hasChanges(model) ? 'Latest version loaded. Review your unsaved edits before deploying.' : '' }[action];
      } catch (error) {
        model.error = true;
        if (writing && (!error.status || error.status >= 500)) {
          model.conflict = true;
          model.message = 'Could not confirm the request. Reload to check the latest package.';
        } else if (error.status === 409 && action !== 'delete') {
          model.conflict = true;
          model.message = action === 'deploy' && writing ?
            'The package or deployment targets changed. Reload and review before deploying again.' : error.message;
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
        retryFailedResultSectionsOnOpen = Boolean(name);
        group = name ? groupEdits.get(name) || { name } : null;
        groupPanel.innerHTML = group ? `<section class="settings-section">
          <header><h3>Configuration package</h3><p>Deploy a package to this group, or deploy No package to stop managing settings and keep current device values.</p></header>
          <div class="settings-assignment">
            <label class="settings-name">Package for this group<select class="field" data-group-package></select></label>
            <div class="settings-buttons">
              <button type="button" class="btn-outline" data-group-action="edit">Edit package</button>
              <button type="button" class="btn btn-success settings-deploy" data-group-action="deploy" aria-describedby="groupDeployHint">Deploy</button>
            </div>
          </div>
          <p class="settings-deploy-hint" id="groupDeployHint" data-deploy-hint hidden></p>
          <div class="settings-feedback" data-assignment-feedback role="status"></div>
          <section class="settings-results">
            <div class="settings-results-heading">
              <h4>Device status</h4>
              <span class="settings-deployment-count" data-deployment-count role="status"></span>
            </div>
            <p class="settings-meta" data-group-publication></p>
            <div class="settings-device-results" data-settings-results></div>
          </section>
        </section>` : '';
      }
      refreshGroup();
    }

    function refreshGroup() {
      if (!group) return;
      const assigned = assignedPackage(group);
      const selected = selectedPackage(group);
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
      const edit = groupPanel.querySelector('[data-group-action="edit"]');
      edit.hidden = !known;
      edit.disabled = Boolean(group.busy);
      let deployReason = '';
      if (!snapshot) deployReason = 'Waiting for group details to load.';
      else if (group.busy) deployReason = group.busy;
      else if (selected && !known) deployReason = 'This package is unavailable. Choose another package.';
      else if (known && !known.published_revision) {
        deployReason = 'Open Edit package and select Save & deploy to deploy it first.';
      }
      updateDeployAction(groupPanel.querySelector('[data-group-action="deploy"]'), groupPanel.querySelector('[data-deploy-hint]'), deployReason);
      const feedback = groupPanel.querySelector('[data-assignment-feedback]');
      feedback.textContent = group.message || '';
      feedback.classList.toggle('settings-error', Boolean(group.error));
      const deployment = currentDeployment(group);
      groupPanel.querySelector('[data-group-publication]').textContent = deployment ? 'Deployed version ' + deployment.revision
        : assigned ? 'Not deployed yet.' : 'No package set for this group.';
      renderResults();
    }

    function retryKey(deviceId, deployment) {
      return JSON.stringify([deviceId, deployment.package_id, deployment.revision]);
    }

    function renderResults() {
      if (!group) return;
      const target = groupPanel.querySelector('[data-settings-results]');
      const count = groupPanel.querySelector('[data-deployment-count]');
      count.hidden = !assignedPackage(group);
      const deployment = currentDeployment(group);
      const assigned = assignedPackage(group);
      const openResults = new Set(Array.from(target.querySelectorAll('[data-result-device][open]'), result => result.dataset.resultDevice));
      const outcomes = groupOutcomes(group.name, assigned, deployment);
      const rows = [];
      for (const [id, { device, outcome: [state, label, detail] }] of outcomes) {
        const readiness = wifiReadiness(device, deployment);
        const priorityMonitor = wifiAutoReturnMonitor(device, deployment);
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
            ${priorityMonitor ? `<div class="settings-meta">${esc(priorityMonitor)}</div>` : ''}
            ${reportedResults(device?.configuration_state, deployment, items, id, openResults.has(id))}
          </div>${button}
        </article>`);
      }
      const summary = deploymentCount(outcomes, deployment);
      if (count.textContent !== summary.text) count.textContent = summary.text;
      count.dataset.state = summary.state;
      const content = rows.join('') || '<p class="settings-meta">This group has no devices.</p>';
      if (target.resultContent !== content) {
        target.resultContent = content;
        target.innerHTML = content;
      }
      if (deployment && assigned) {
        const retryFailed = retryFailedResultSectionsOnOpen;
        retryFailedResultSectionsOnOpen = false;
        readResultSections(assigned, retryFailed);
      }
    }

    async function deployPackage(model) {
      const assigned = assignedPackage(model);
      const id = selectedPackage(model);
      const submittedSelection = model.selection;
      const path = '/api/group-package/deploy?' + new URLSearchParams({ name: model.name, package_id: id ?? '' });
      groupEdits.set(model.name, model);
      model.busy = 'Checking deployment targets…';
      model.message = '';
      model.error = false;
      refreshGroup();
      let writing = false;
      try {
        const fresh = await api(path);
        if (group !== model) return;
        if (assignedPackage(model) !== assigned) {
          model.error = true;
          model.message = 'The group package changed. Check the current package before deploying.';
          return;
        }
        if (!confirmDeployment(fresh, { groupName: model.name })) return;
        writing = true;
        model.busy = 'Starting deployment…';
        refreshGroup();
        const data = await api(path, 'POST', { expected_impact: fresh.impact.token });
        const applied = updateSnapshot(data, 'http');
        if (!applied) {
          if (model.selection === submittedSelection) delete model.selection;
          model.message = 'A newer group state arrived while deployment was in progress.';
        } else if (assignment(model.name) !== id) {
          delete model.selection;
          model.error = true;
          model.message = 'The group changed again. Check the current package before deploying.';
        } else {
          delete model.selection;
          model.message = id === null
            ? 'No package deployed. This group has no managed settings.'
            : 'Deployment started. Check Device status for results.';
        }
      } catch (error) {
        model.error = true;
        model.message = writing && (!error.status || error.status >= 500)
          ? 'Could not confirm deployment. Check the group package before trying again.'
          : writing && error.status === 409 ? 'The package or deployment targets changed. Select Deploy to review them again.'
            : error.status ? error.message : 'Could not check the deployment targets.';
      } finally {
        model.busy = false;
        refreshGroup();
      }
    }

    async function retryDevice(model, deviceId) {
      const deployment = currentDeployment(model);
      const device = getDevice(deviceId);
      if (device?.device_id !== deviceId || !retryEligible(device, deployment) ||
          !(getGroups()[model.name] || []).includes(deviceId)) return;
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
        model.message = error.status === 409 ? 'The device or deployment changed. Check Device status before retrying.' :
          error.status ? error.message : 'Could not confirm the Retry request.';
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
        if (manage === 'wifi') {
          const valuePanel = target.closest('.settings-row').querySelector('.settings-value');
          updateWifiMoveButtons(valuePanel, model.draft);
          updateWifiWarnings(valuePanel, model.draft);
        }
        target.closest('.settings-row').querySelector('.settings-value').disabled = !target.checked;
        const section = target.closest('[data-settings-section]');
        section.querySelector('.settings-count').textContent =
          sectionCount(model.sections.find(candidate => candidate.id === section.dataset.settingsSection), model.draft);
      } else if (setting) {
        const entry = model.draft[setting];
        const value = entry.value;
        if (!entry.managed) return;
        if (network !== undefined) {
          const row = value.networks[Number(network)];
          row[field] = target.type === 'checkbox' ? target.checked : target.value;
          const article = target.closest('.wifi-network');
          if (field === 'security') {
            const password = article.querySelector('[data-field="password"]');
            password.disabled = row.security === 'OPEN';
            if (password.disabled) row.password = password.value = '';
          }
          article.querySelector('[data-password-help]').textContent = wifiPasswordHelp(row, model.saved[setting].value.networks);
          if (field === 'hidden') updateWifiWarnings(target.closest('.settings-value'), model.draft);
        } else if (setting === 'wifi' && field === 'auto_return') {
          value.auto_return = target.checked;
          updateWifiWarnings(target.closest('.settings-value'), model.draft);
        } else if (setting === 'wifi' && field === 'prioritize_networks') {
          value.prioritize_networks = target.checked;
          if (!value.prioritize_networks) value.auto_return = false;
          model.error = false;
          model.message = '';
          renderEditor();
          packagePanel.querySelector('[data-field="prioritize_networks"]')?.focus({ preventScroll: true });
          return;
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
      } else if (action === 'deploy') {
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
        return request(model, 'deploy', { expected_revision: model.editRevision, settings,
          ...(name !== model.savedName ? { name } : {}) });
      } else if (action === 'delete') {
        if (confirmAction('Delete configuration package "' + model.savedName + '"?')) return request(model, 'delete');
      } else if (action === 'add-network' || action === 'remove-network' || action === 'move-network') {
        const { setting, network } = button.dataset;
        const entry = model.draft[setting];
        if (!entry?.managed) return;
        if (action === 'add-network' && entry.value.networks.length < MAX_WIFI_NETWORKS) {
          entry.value.networks.push({ ssid: '', security: 'OPEN', hidden: false, password: '' });
        } else if (action === 'remove-network') {
          entry.value.networks.splice(Number(network), 1);
        } else if (action === 'move-network') {
          const direction = button.dataset.direction === 'up' ? -1 : 1;
          moveWifiNetwork(entry.value.networks, Number(network), direction);
        }
        model.error = false;
        model.message = '';
        renderEditor();
      }
    }

    groupPanel.addEventListener('change', event => {
      if (!group || group.busy || !event.target.matches('[data-group-package]')) return;
      const selected = event.target.value || null;
      if (selected === CREATE_PACKAGE_OPTION) {
        refreshGroup();
        showPackages();
        createForm.querySelector('input').focus();
        return;
      }
      if (selected === assignedPackage(group)) delete group.selection;
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
      if (action === 'deploy') return deployPackage(group);
      if (action === 'retry') return retryDevice(group, button.dataset.deviceId);
      if (action === 'edit') return showPackages(selectedPackage(group));
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
    return { renderGroup, updateSnapshot, showPackages, groupPackageSummary };
  }

  const api = { createController, membershipGroups, syncMemberSelection, clearMembershipEditsThatMatch,
    expectedMembershipOwner,
    buildMembershipEdit, loadDraft, buildPayload, deviceOutcome, retryEligible };
  root.MdmConfigurationPackages = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
