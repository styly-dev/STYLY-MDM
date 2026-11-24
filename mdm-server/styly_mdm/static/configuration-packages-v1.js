(function (root) {
  'use strict';

  const CREATE_PACKAGE_OPTION = 'create-package';

  const MAX_WIFI_NETWORKS = 4;
  const MAX_LBE_MAP_BYTES = 512 * 1024 * 1024;
  const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  const WIFI_ANDROID_HELP = 'Android chooses the network. Auto-connect is on for listed SSID and security matches and off for other saved networks.';
  const WIFI_ACTION_ICONS = {
    up: 'M8 13V3m-4 4 4-4 4 4',
    down: 'M8 3v10m-4-4 4 4 4-4',
    remove: 'M2.5 4.5h11m-7-2h3m-5 2 .6 8a1 1 0 0 0 1 .9h3.8a1 1 0 0 0 1-.9l.6-8M7 7v3.5M9 7v3.5',
  };

  function wifiActionIcon(action) {
    return `<svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path d="${WIFI_ACTION_ICONS[action]}"/></svg>`;
  }

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (character) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character];
    });
  }

  function wifiPermissionWarningHtml(device) {
    return device?.status === 'online' && device.wifi_state?.unavailable_reason === 'DUMP_PERMISSION_MISSING'
      ? '<span class="wifi-warning">Wi-Fi management requires the DUMP permission.</span>' : '';
  }

  function currentWifiHtml(device) {
    const wifi = device?.current_wifi;
    if (device?.status !== 'online') return '';
    const warning = wifiPermissionWarningHtml(device);
    if (!wifi?.ssid) return warning;
    const title = wifi.bssid ? ' title="BSSID: ' + esc(wifi.bssid) + '"' : '';
    return '<span class="current-wifi"' + title + '>Wi-Fi: ' + esc(wifi.ssid) + '</span>' + (warning ? ' ' + warning : '');
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
          networks: value.networks.map(({ ssid, security, hidden, password }) => ({
            ssid, security, hidden, password: typeof password === 'string' ? password : '',
          })),
        };
      }
      draft[item.id] = { managed: saved?.managed === true, value };
    }
    return structuredClone(draft);
  }

  function savedWifiPassword(network, saved) {
    return saved.find(candidate => candidate.ssid === network.ssid && candidate.security === network.security)?.password || '';
  }

  function supportedValue(item, value) {
    if (item.kind === 'range') {
      if (typeof value !== 'number' || !Number.isFinite(value)) return false;
      const min = Number(item.min ?? 0);
      const max = Number(item.max ?? 100);
      const step = Number(item.step ?? 1);
      return value >= min && value <= max && (value - min) % step === 0;
    }
    if (item.kind === 'delay') return item.options?.some(option => option.value === value) === true;
    if (item.kind === 'bool') return typeof value === 'boolean';
    if (item.kind === 'package') return validAndroidPackageName(value, true);
    return true;
  }

  function isSettingManaged(id, draft) {
    if (id === 'lbeMap') return draft.lbeMode?.managed === true && draft.lbeMode.value === true;
    return draft[id]?.managed === true;
  }

  function validLbeMap(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value) ||
        Object.keys(value).sort().join(',') !== 'name,sha256,size') return false;
    return typeof value.sha256 === 'string' && /^[a-f0-9]{64}$/.test(value.sha256) &&
      typeof value.name === 'string' && Number.isSafeInteger(value.size) && value.size > 0;
  }

  function validAndroidPackageName(value, allowDefault = false) {
    return typeof value === 'string' && value.length <= 255 &&
      (allowDefault && value === '' || /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/.test(value));
  }

  function validScheduleTime(value) {
    return value && Number.isInteger(value.hour) && value.hour >= 0 && value.hour <= 23 &&
      Number.isInteger(value.minute) && value.minute >= 0 && value.minute <= 59;
  }

  function validScheduleRepeat(value) {
    return Number.isInteger(value) && value > 0 && value <= 127;
  }

  function validWifiSsid(ssid) {
    if (typeof ssid !== 'string' || !ssid.length) return 'Enter an SSID';
    let byteLength = 0;
    let unsupported = false;
    for (const character of ssid) {
      const code = character.codePointAt(0);
      if (code >= 0xd800 && code <= 0xdfff) return 'SSID must contain valid UTF-8 text';
      byteLength += code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4;
      if (code < 0x20 || (code >= 0x7f && code <= 0x9f) || character === '"' || character === '\\') {
        unsupported = true;
      }
    }
    if (byteLength > 32) return 'SSID must contain 1 to 32 UTF-8 bytes';
    if (unsupported) return 'SSID contains unsupported characters';
    return '';
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

  function wifiPayload(value, managed) {
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
      const ssidError = validWifiSsid(ssid);
      if (ssidError) throw new Error(ssidError + ' for Wi-Fi ' + (index + 1) + '.');
      if (!['OPEN', 'WPA2_WPA3'].includes(security)) {
        throw new Error('Choose Open or WPA2/WPA3 security for Wi-Fi ' + (index + 1) + '.');
      }
      if (typeof hidden !== 'boolean') throw new Error('Choose whether Wi-Fi ' + (index + 1) + ' is hidden.');
      const identity = JSON.stringify([ssid, security]);
      if (identities.has(identity)) throw new Error('Each SSID and security combination must be unique.');
      identities.add(identity);
      const result = { ssid, security, hidden };
      if (security === 'WPA2_WPA3') {
        if (typeof password !== 'string') {
          throw new Error('Wi-Fi ' + (index + 1) + ' password must be text.');
        }
        if (!password) {
          throw new Error('Enter a 1 to 63 character ASCII password for Wi-Fi ' + (index + 1) + '.');
        }
        if (!/^[\x20-\x7e]{1,63}$/.test(password)) {
          throw new Error('Wi-Fi ' + (index + 1) + ' password must be 1 to 63 ASCII characters. Passwords of 1 to 7 characters require WPA3.');
        }
        result.password = password;
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
      if (item.kind === 'package' && !entry.managed) {
        const value = validAndroidPackageName(entry.value, true) ? entry.value : saved[item.id]?.value;
        if (validAndroidPackageName(value, true) && (value !== '' || saved[item.id]?.managed || saved[item.id]?.value)) {
          settings[item.id] = { managed: false, value };
        }
        continue;
      }
      const managed = item.id === 'lbeMap' ? isSettingManaged(item.id, draft) : entry.managed;
      const value = item.kind === 'wifi' && !entry.managed
        ? saved[item.id].value : entry.value;
      let payloadValue = value;
      if (item.kind === 'schedule') {
        payloadValue = schedulePayload(item, value, saved[item.id]?.value, managed && value.enabled === true);
      } else if (managed && !supportedValue(item, value)) {
        throw new Error('Select a supported value for ' + item.label + '.');
      }
      if (item.id === 'lbeMap' && managed && !validLbeMap(value)) {
        throw new Error('Select a map ZIP before enabling LBE mode.');
      }
      settings[item.id] = {
        managed,
        value: item.kind === 'wifi'
          ? wifiPayload(value, managed)
          : payloadValue,
      };
    }
    return settings;
  }

  function matchesDeployment(report, deployment) {
    return Boolean(deployment && report?.package_id === deployment.package_id && report.revision === deployment.revision);
  }

  function retryEligible(device, deployment) {
    const report = device?.configuration_state;
    if (device?.status !== 'online' || device.configuration_supported !== true ||
        !matchesDeployment(report, deployment)) return false;
    if (report.status === 'interrupted') return true;
    if (report.status !== 'complete') return false;
    return deployment.keys.some(key => ['failed', 'unsupported'].includes(report.results?.[key]?.status));
  }

  function waitingHomeAppDetail(result) {
    return 'App not found on the headset (' + result.value + ').';
  }

  function lbeReasonDetail(key, reason) {
    if (key !== 'lbeMap' && key !== 'lbeMode') return '';
    if (reason === 'CALLBACK_TIMEOUT') {
      return key === 'lbeMode' ? 'PICO did not confirm the LBE mode in time.'
        : 'PICO did not confirm the import in time. The import may still be running.';
    }
    return ({
      MAP_ACTIVE_VERIFIED: 'PICO confirmed that the selected map is active.',
      MAP_ACTIVE_UNVERIFIED: 'Map import succeeded, but the active map cannot be confirmed.',
      MAP_ACTIVE_MISMATCH: 'PICO reports a different active map than the selected ZIP.',
      MAP_VERIFICATION_UNAVAILABLE: 'PICO could not query which map is active.',
      MAP_DOWNLOAD_FAILED: 'Map ZIP could not be downloaded or verified.',
      MAP_IMPORT_FAILED: 'PICO could not import the selected map ZIP.',
      MAP_MODE_NOT_APPLIED: 'PICO accepted the map import, but did not apply the requested LBE mode.',
      MAP_REQUIRED: 'The selected map must be imported before LBE mode can turn on.',
      MAP_NOT_LOADED: 'PICO did not confirm a loaded map.',
    })[reason] || '';
  }

  function resultStatusLabel(key, result) {
    const status = result
      ? { applied: 'Applied', failed: 'Failed', unsupported: 'Unsupported', waiting: 'Waiting' }[result.status]
      : '';
    if (status !== 'Applied') return status;
    if (key === 'startupApp' || key === 'ipd' ||
        (key === 'lbeMap' && result.reason !== 'MAP_ACTIVE_VERIFIED')) return 'Accepted';
    if (key === 'lbeMap') return 'Verified';
    return status;
  }

  function deviceOutcome(device, packageId, deployment) {
    const online = device?.status === 'online';
    const connection = online ? '' : ' · Offline';
    const report = device?.configuration_state;
    if (!packageId) {
      if (report) {
        if (online && device.configuration_supported !== true) {
          return ['unavailable', 'Client update needed', 'Update the client so it can report the release.'];
        }
        return ['pending', 'Release pending' + connection, 'Waiting for the device to report that it saved the release.'];
      }
      return ['', 'No managed settings' + connection, 'This group has no managed settings.'];
    }
    if (!deployment) return ['pending', 'Waiting for deployment' + connection, 'Deploy the group package to send its settings to devices.'];
    if (online && device.configuration_supported !== true) {
      return ['unavailable', 'Client update needed', 'This client does not report support for configuration packages.'];
    }
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
    const results = deployment.keys.map(key => report.results?.[key]?.status);
    const applied = results.filter(status => status === 'applied').length;
    if (!results.length) return ['success', 'No managed settings' + connection, 'The device acknowledged this revision.'];
    if (results.every(status => status === 'unsupported')) {
      return ['unavailable', 'Unsupported' + connection, 'This device could not apply the managed settings.'];
    }
    const details = [];
    let verified = 0;
    let hasVerifiedScalars = false;
    for (const key of deployment.keys) {
      const result = report.results?.[key];
      if (key === 'startupApp' && result?.status === 'waiting') {
        details.push(waitingHomeAppDetail(result));
        continue;
      }
      if (result?.status === 'failed') {
        const detail = lbeReasonDetail(key, result.reason);
        if (detail && !details.includes(detail)) details.push(detail);
      }
      if (result?.status !== 'applied') continue;
      if (key === 'startupApp') {
        details.push('The Home app setting was accepted and takes effect after reboot.');
      } else if (key === 'ipd') {
        details.push('PICO accepted the IPD setting, which cannot be read back.');
      } else if (key === 'lbeMap') {
        if (result.reason === 'MAP_ACTIVE_VERIFIED') {
          verified++;
          details.push(lbeReasonDetail(key, result.reason));
        } else {
          details.push(lbeReasonDetail(key, result.reason) ||
            'PICO accepted the map import, but the active map was not reported.');
        }
      } else {
        verified++;
        if (key === 'wifi' && result.reason === 'SAVED') {
          details.push(deployment.wifi_mode === 'android_selection'
            ? 'Wi-Fi profiles and auto-connect flags were read back. Android selects the network.'
            : 'Saved Wi-Fi profiles and auto-connect flags were read back.');
        } else {
          hasVerifiedScalars = true;
        }
      }
    }

    if (applied === results.length) {
      if (!details.length) details.push('All managed settings were verified.');
      else if (hasVerifiedScalars) details.push('Other managed settings were verified.');
      const accepted = deployment.keys.some(key =>
        resultStatusLabel(key, report.results?.[key]) === 'Accepted');
      return ['success', (accepted ? 'Accepted' : 'Applied') + connection, details.join(' ')];
    }
    if (results.includes('waiting') && results.every(status => status === 'applied' || status === 'waiting')) {
      return ['pending', 'Waiting' + connection, details.join(' ')];
    }
    details.unshift(verified + ' of ' + results.length + ' managed settings were read back and verified.');
    return ['failed', (applied ? 'Partially applied' : 'Apply failed') + connection, details.join(' ')];
  }

  function wifiPasswordHelp(network) {
    if (network.security === 'OPEN') return 'No password needed.';
    return '1–63 ASCII characters (1–7 require WPA3).';
  }

  function renderWifi(item, value, managed) {
    const networks = value.networks;
    const rows = networks.map(function (network, index) {
      const field = `data-setting="${esc(item.id)}" data-network="${index}"`;
      const isOpen = network.security === 'OPEN';
      const passwordHelpId = `wifi-password-help-${index}`;
      const ssid = `<label>SSID<input class="field" ${field} data-field="ssid" value="${esc(network.ssid)}"></label>`;
      const moveActions = value.prioritize_networks ? `
            <button class="btn-outline" type="button" aria-label="Move Wi-Fi ${index + 1} up" title="Move up" data-policy-action="move-network" data-direction="up" ${field} ${!managed || index === 0 ? 'disabled' : ''}>${wifiActionIcon('up')}</button>
            <button class="btn-outline" type="button" aria-label="Move Wi-Fi ${index + 1} down" title="Move down" data-policy-action="move-network" data-direction="down" ${field} ${!managed || index === networks.length - 1 ? 'disabled' : ''}>${wifiActionIcon('down')}</button>` : '';
      return `<article class="wifi-network">
        <div class="wifi-network-head${value.prioritize_networks ? '' : ' wifi-network-head-compact'}">
          ${value.prioritize_networks ? `<strong>Wi-Fi ${index + 1}</strong>` : ssid}
          <div class="wifi-network-actions">
            ${moveActions}
            <button class="btn-outline wifi-remove" type="button" aria-label="Remove Wi-Fi ${index + 1} from package" title="Remove from package" data-policy-action="remove-network" ${field}>${wifiActionIcon('remove')}</button>
          </div>
        </div>
        ${value.prioritize_networks ? ssid : ''}
        <div class="wifi-fields">
          <label>Security<select class="field" ${field} data-field="security">
            <option value="OPEN" ${isOpen ? 'selected' : ''}>Open</option>
            <option value="WPA2_WPA3" ${!isOpen ? 'selected' : ''}>WPA2/WPA3 Personal</option>
          </select></label>
          <label>Password<input class="field" type="text" ${field} data-field="password"
            aria-describedby="${passwordHelpId}" autocomplete="off" spellcheck="false" ${isOpen ? 'disabled' : ''}></label>
        </div>
        <div class="wifi-row-foot">
          <small id="${passwordHelpId}" data-password-help="${index}">${wifiPasswordHelp(network)}</small>
          <label class="wifi-check"><input type="checkbox" ${field} data-field="hidden" ${network.hidden ? 'checked' : ''}> Hidden network</label>
        </div>
      </article>`;
    }).join('');
    const conflicts = wifiConflicts({ managed, value });
    const strategyControls = value.prioritize_networks ? `
      <label><input type="checkbox" data-setting="${esc(item.id)}" data-field="auto_return" ${value.auto_return ? 'checked' : ''}> Auto-return to higher-priority Wi-Fi</label>
      <small>On lower-priority Wi-Fi, the headset scans within 30 seconds, then about every minute for 5 scans, then about every 5 minutes. Android may delay scans. If switching fails and Wi-Fi is still disconnected, the headset retries the previous network.</small>` : '';
    const priorityControls = `<div class="wifi-options">
      <label><input type="checkbox" data-setting="${esc(item.id)}" data-field="prioritize_networks" ${value.prioritize_networks ? 'checked' : ''}> Prioritize Wi-Fi by list order</label>
      ${strategyControls}
      <small class="wifi-warning" data-wifi-limit-warning role="alert" ${conflicts.tooManyNetworks ? '' : 'hidden'}>Remove extra networks to meet the 4-network limit.</small>
      <small class="wifi-warning" data-wifi-hidden-warning role="alert" ${conflicts.autoReturnWithHidden ? '' : 'hidden'}>Turn off auto-return or uncheck Hidden network.</small>
    </div>`;
    return `<div class="settings-networks">
      <div class="push-note">${value.prioritize_networks ? 'Android auto-connects only to the first network. The headset tries the list in order when needed.' : WIFI_ANDROID_HELP} If none connect, the headset may go offline in MDM.</div>
      ${rows}
      ${priorityControls}
      <button type="button" class="btn-outline" data-policy-action="add-network" data-setting="${esc(item.id)}" ${!managed || networks.length >= MAX_WIFI_NETWORKS ? 'disabled' : ''}>Add Wi-Fi network</button>
    </div>`;
  }

  function scheduleTime(value) {
    return value.hour === null || value.minute === null ? '' :
      [value.hour, value.minute].map(number => String(number).padStart(2, '0')).join(':');
  }

  function renderControl(item, value, managed, model) {
    const field = `data-setting="${esc(item.id)}"`;
    const label = esc(item.label);
    switch (item.kind) {
      case 'bool':
        return `<div class="settings-radios">${[true, false].map(on =>
          `<label><input type="radio" name="setting-${esc(item.id)}" ${field} value="${on}" ${value === on ? 'checked' : ''}> ${on ? 'On' : 'Off'}</label>`).join('')}</div>`;
      case 'range': {
        const formatted = esc(actualValue(item, value));
        return `<div class="settings-range"><input aria-label="${label}" aria-valuetext="${formatted}" type="range" min="${esc(item.min ?? 0)}" max="${esc(item.max ?? 100)}" step="${esc(item.step ?? 1)}" ${field} value="${esc(value)}"><output>${formatted}</output></div>`;
      }
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
        return renderWifi(item, value, managed);
      case 'package':
        return `<label class="settings-field-label">Android package name<input class="field" type="text" maxlength="255" autocomplete="off" ${field} value="${esc(value)}" placeholder="com.example.app"></label>`;
      case 'lbe-map':
        return renderLbeMap(item, value, managed, model);
      default:
        return '';
    }
  }

  function renderLbeMap(item, value, managed, model) {
    const maps = model.maps || [];
    const selected = validLbeMap(value) ? value.sha256 : '';
    const options = maps.map(map => `<option value="${esc(map.sha256)}" ${map.sha256 === selected ? 'selected' : ''}>${esc(map.name)} · ${formatBytes(map.size)}</option>`).join('');
    const missingSelection = selected && !maps.some(map => map.sha256 === selected);
    const selectedMapUnavailable = missingSelection && model.mapsStatus === 'loaded';
    const stored = missingSelection ? value : model.saved?.lbeMap?.value;
    const storedOption = validLbeMap(stored) && !maps.some(map => map.sha256 === stored.sha256)
      ? `<option value="${esc(stored.sha256)}" ${stored.sha256 === selected ? 'selected' : ''}>${esc(stored.name)} · ${model.mapsStatus === 'loaded' ? 'Not in map library' : 'Stored in this package'}</option>` : '';
    const error = model.uploadError ? `<small class="settings-error" role="alert">${esc(model.uploadError)}</small>` : '';
    const busy = model.uploading === true;
    const loading = model.mapsStatus === 'loading';
    const listFailed = model.mapsStatus === 'failed';
    const mapControlsDisabled = !managed || busy || loading;
    const retry = listFailed || loading
      ? `<button class="btn-outline" type="button" data-map-retry ${loading ? 'aria-disabled="true"' : ''}>Retry map list</button>` : '';
    const unavailable = selectedMapUnavailable
      ? '<small class="settings-error" role="alert">This ZIP is missing from the server library. Upload it again.</small>' : '';
    const status = busy ? 'Uploading…' : loading ? 'Loading map list…' :
      listFailed ? 'Could not load uploaded maps.' :
        managed && model.mapsStatus === 'loaded' && !maps.length ? 'No map ZIPs uploaded.' : '';
    const upload = managed || busy ? `<button class="btn-outline" type="button" data-map-choose ${mapControlsDisabled ? 'disabled' : ''}>Upload ZIP…</button>
      <input type="file" accept=".zip,application/zip" data-map-upload hidden ${mapControlsDisabled ? 'disabled' : ''}>` : '';
    const actions = upload || retry || status ? `<div class="settings-buttons settings-map-actions">
      ${upload}${retry}${status ? `<span class="settings-meta" data-map-status role="status">${status}</span>` : ''}</div>` : '';
    const control = managed
      ? `<select class="field" aria-label="Venue map ZIP" data-setting="${esc(item.id)}" ${mapControlsDisabled ? 'disabled' : ''}>
          <option value="" ${selected ? '' : 'selected'}>Select a map ZIP</option>${storedOption}${options}</select>`
      : `<div class="settings-map-summary" data-setting="${esc(item.id)}">
          ${selected
            ? `<span class="settings-map-filename">${esc(value.name)}</span><span class="settings-meta">${formatBytes(value.size)} · Kept in this package</span>`
            : '<span class="settings-meta">LBE On required</span>'}
        </div>`;
    return `<div class="settings-networks">
      ${control}${actions}${unavailable}${error}
    </div>`;
  }

  function formatBytes(size) {
    if (!Number.isFinite(size) || size < 1) return '';
    if (size < 1024 * 1024) return Math.ceil(size / 1024) + ' KB';
    return (size / (1024 * 1024)).toFixed(1) + ' MB';
  }

  function renderItem(item, model) {
    const draft = model.draft;
    const entry = draft[item.id];
    const wide = item.kind === 'wifi' || item.kind === 'schedule' || item.kind === 'package';
    const managed = item.kind === 'lbe-map' ? isSettingManaged(item.id, draft) : entry?.managed === true;
    const label = esc(item.label);
    const manageControl = item.kind === 'lbe-map' ? '<span aria-hidden="true"></span>' :
      `<label class="settings-manage"><input type="checkbox" data-manage="${esc(item.id)}" aria-label="Manage ${label}" ${entry?.managed ? 'checked' : ''} ${entry ? '' : 'disabled'}></label>`;
    return `<div class="settings-row${wide ? ' settings-wide' : ''}">
      ${manageControl}
      <div class="settings-label"><strong>${label}</strong><small>${esc(item.description)}</small>
        ${item.id === 'lbeMode' ? '<small>Turning LBE off requires a headset restart.</small>' : ''}</div>
      ${entry ? `<fieldset class="settings-value" aria-label="${label}" ${managed || item.kind === 'lbe-map' ? '' : 'disabled'}>${renderControl(item, entry.value, managed, model)}</fieldset>` :
        '<span class="settings-meta">Unavailable</span>'}
    </div>`;
  }

  function sectionCount(section, draft) {
    const manageable = section.items.filter(item => item.kind !== 'lbe-map');
    const managed = manageable.filter(item => draft[item.id]?.managed).length;
    return managed + ' / ' + manageable.length + ' managed';
  }

  function renderSection(section, model) {
    return `<details class="settings-group" data-settings-section="${esc(section.id)}" ${model.expanded.has(section.id) ? 'open' : ''}>
      <summary class="settings-group-head">
        <span><strong>${esc(section.title)}</strong><small>${esc(section.description)}</small></span>
        <span class="settings-count">${sectionCount(section, model.draft)}</span>
        <span class="settings-chevron" aria-hidden="true"></span>
      </summary>
      <div class="settings-content">${section.items
        .map(item => renderItem(item, model)).join('')}</div>
    </details>`;
  }

  function actualValue(item, value) {
    if (!item) return '';
    if (item.kind === 'range') {
      if (typeof value !== 'number') return '';
      return item.unit ? value.toFixed(1) + ' ' + item.unit : value + '%';
    }
    if (item.kind === 'lbe-map') return validLbeMap(value) ? value.name : '';
    if (item.kind === 'bool') return typeof value === 'boolean' ? value ? 'On' : 'Off' : '';
    if (item.kind === 'delay') return item.options.find(option => option.value === value)?.label || '';
    if (item.kind === 'schedule' && value && typeof value.enabled === 'boolean') {
      return value.enabled ? scheduleTime(value) + ' · ' + DAYS.filter((_, index) => value.repeat & (1 << index)).join(', ') : 'Off';
    }
    return '';
  }

  function reportedResults(report, deployment, items) {
    if (!matchesDeployment(report, deployment)) return '';
    return deployment.keys.map(function (key) {
      const item = items.get(key);
      const result = report.results?.[key];
      const status = resultStatusLabel(key, result) ||
        (report.status === 'complete' ? 'Not verified' : '');
      if (!status) return '';
      const applied = result?.status === 'applied';
      const waiting = key === 'startupApp' && result?.status === 'waiting';
      const savedWifi = key === 'wifi' && applied && result.reason === 'SAVED';
      const launcherAccepted = key === 'startupApp' && applied;
      const resultStatus = resultStatusLabel(key, result) || status;
      const actual = actualValue(item, result?.value);
      const reason = result?.reason ? String(result.reason).replaceAll('_', ' ').toLowerCase() : '';
      const homeAppDisabled = key === 'startupApp' && result?.reason === 'APP_DISABLED';
      const specialReasons = {
        DUMP_PERMISSION_MISSING: 'Grant the DUMP permission with ADB during device setup.',
        ACCEPTED_UNVERIFIED: 'PICO accepted the IPD setting. The headset does not expose an IPD readback.',
      };
      const explanation = homeAppDisabled
        ? 'App disabled on the headset' + (result.value ? ' (' + result.value + ')' : '')
        : lbeReasonDetail(key, result?.reason) || specialReasons[result?.reason] || reason.charAt(0).toUpperCase() + reason.slice(1);
      const detail = savedWifi
        ? (deployment.wifi_mode === 'android_selection' ? 'Saved. Android selects the network.' : 'Saved')
        : launcherAccepted
          ? [resultStatus, result.value === '' ? 'PICO default' : result.value, 'Takes effect after reboot'].filter(Boolean).join(' · ')
          : waiting ? 'Waiting · ' + waitingHomeAppDetail(result)
          : [actual, explanation || (key === 'lbeMap' && applied
            ? 'PICO accepted the import without reporting which map is active.' : '')].filter(Boolean).join(' · ') || status;
      return `<li class="${applied ? 'applied' : waiting ? 'waiting' : 'unverified'}">
        <span class="settings-readback-icon" role="img" aria-label="${resultStatus}">${applied ? '✓' : waiting ? '…' : '×'}</span>
        <span>${esc(item?.label || key)}</span><span class="settings-readback-value">${esc(detail)}</span>
      </li>`;
    }).filter(Boolean).join('');
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

  function wifiAutoReturnNotice(device, deployment) {
    if (device?.status !== 'online' || !deployment?.keys.includes('wifi') ||
        deployment.wifi_mode === 'android_selection') return '';
    const labels = {
      degraded: 'Wi-Fi auto-return is degraded.',
      switching: 'Switching to a higher-priority Wi-Fi network.',
      unsupported: 'Wi-Fi auto-return is unavailable.',
    };
    return labels[device.wifi_state?.priority_monitor] || '';
  }

  function createController({
    groupPanel, packagePanel, packageList, catalogNotice, createForm, getGroups, getDevice, openPackages,
    sortGroupMembers = members => members,
    deviceIdHtml = (_, id) => esc(id), fetch = root.fetch.bind(root),
    confirmAction = message => root.confirm(message),
    promptForValue = (message, value) => root.prompt(message, value),
    onSnapshot = () => {},
    requestGroupSnapshot = () => false,
  }) {
    const groupEdits = new Map();
    const retrying = new Map();
    let group = null;
    const expandedResults = new Set();
    let current = null;
    let snapshot = null;
    let catalog = null;
    let configurationAvailable = null;
    let snapshotConnectionReady = false;
    let catalogRefreshState = null;
    let catalogLoading = false;
    let catalogError = '';
    let catalogContent = '';
    let packageNameCatalog = null;
    let packageNames = new Map();
    let items = new Map();
    let resultSections = { status: 'idle' };
    let creating = false;
    const packagePath = id => '/api/packages/' + encodeURIComponent(id);
    const packageById = id => catalog?.find(candidate => candidate.package_id === id);
    const assignment = name => Object.hasOwn(snapshot?.assignments || {}, name) ? snapshot.assignments[name] : null;
    const assignedPackage = model => assignment(model.name);
    function selectedPackage(model) {
      return model.selection === undefined ? assignedPackage(model) : model.selection;
    }
    function packageNameCounts() {
      if (catalog === packageNameCatalog) return packageNames;
      const counts = new Map();
      for (const pkg of catalog || []) {
        let entry = counts.get(pkg.name);
        if (!entry) {
          entry = { count: 0, prefixes: new Map() };
          counts.set(pkg.name, entry);
        }
        entry.count++;
        const prefix = pkg.package_id.slice(0, 8);
        entry.prefixes.set(prefix, (entry.prefixes.get(prefix) || 0) + 1);
      }
      packageNameCatalog = catalog;
      packageNames = counts;
      return packageNames;
    }
    function packageDisplayName(packageId, name, nameCounts = packageNameCounts()) {
      const entry = nameCounts.get(name);
      if (!entry || entry.count < 2 || typeof packageId !== 'string') return name;
      const prefix = packageId.slice(0, 8);
      return name + ' (' + (entry.prefixes.get(prefix) > 1 ? packageId : prefix) + ')';
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
      if (configurationAvailable === false) return { name: null, count: null };
      const packageId = assignment(name);
      if (!packageId) return null;
      const deployment = deploymentFor(name, packageId);
      return {
        name: packageDisplayName(packageId, packageById(packageId)?.name),
        count: deployment ? deploymentCount(groupOutcomes(name, packageId, deployment), deployment) : null,
      };
    }

    async function api(path, method = 'GET', body, multipart = false) {
      const headers = { Accept: 'application/json' };
      const options = { method, headers };
      if (body !== undefined) {
        if (multipart) options.body = body;
        else {
          headers['Content-Type'] = 'application/json';
          options.body = JSON.stringify(body);
        }
      }
      const response = await fetch(path, options);
      if (!response.ok) {
        const data = await response.json().catch(() => ({}));
        throw Object.assign(new Error(data?.error || `Request failed (HTTP ${response.status}).`), {
          status: response.status, code: data?.code,
        });
      }
      return response.json();
    }

    async function loadLbeMaps(model, refresh = false) {
      if (!refresh && model.mapsStatus !== 'idle') return;
      const expectedInstanceId = snapshot?.server_instance_id ?? null;
      const requestId = model.mapsRequestId = (model.mapsRequestId || 0) + 1;
      model.mapsStatus = 'loading';
      renderLbeMapControl(model);
      try {
        const data = await api('/api/lbe-maps');
        if (current !== model || requestId !== model.mapsRequestId ||
            expectedInstanceId !== (snapshot?.server_instance_id ?? null)) return;
        model.maps = Array.isArray(data.maps) ? data.maps.filter(validLbeMap) : [];
        model.mapsStatus = 'loaded';
      } catch (_) {
        if (current !== model || requestId !== model.mapsRequestId ||
            expectedInstanceId !== (snapshot?.server_instance_id ?? null)) return;
        model.mapsStatus = 'failed';
      }
      renderLbeMapControl(model);
    }

    function refreshLbeMapLibrary() {
      if (current) loadLbeMaps(current, true);
    }

    function renderLbeMapControl(model) {
      const control = packagePanel.querySelector('[data-setting="lbeMap"]');
      const fieldset = control?.closest('.settings-value');
      const section = model.sections?.find(candidate => candidate.items.some(item => item.id === 'lbeMap'));
      const item = section?.items.find(candidate => candidate.id === 'lbeMap');
      if (!fieldset || !item) return;

      const active = root.document?.activeElement;
      const keepMapFocus = active && fieldset.contains?.(active);
      const focusKind = keepMapFocus
        ? active.hasAttribute?.('data-map-retry') ? 'retry'
          : active.hasAttribute?.('data-map-choose') ? 'choose'
            : active.matches?.('[data-setting="lbeMap"]') ? 'select' : null
        : null;
      fieldset.innerHTML = renderLbeMap(item, model.draft.lbeMap.value,
        isSettingManaged('lbeMap', model.draft), model);
      if (!focusKind) return;
      const replacement = focusKind === 'retry' ? fieldset.querySelector('[data-map-retry]')
        : focusKind === 'choose' ? fieldset.querySelector('[data-map-choose]')
          : fieldset.querySelector('[data-setting="lbeMap"]');
      replacement?.focus({ preventScroll: true });
    }

    async function uploadLbeMap(model, file) {
      if (!file || !isSettingManaged('lbeMap', model.draft) || current !== model || model.uploading || model.mapsStatus === 'loading') return;
      if (!file.name.toLowerCase().endsWith('.zip')) {
        model.uploadError = 'Select a ZIP file.';
        renderLbeMapControl(model);
        return;
      }
      if (!Number.isSafeInteger(file.size) || file.size === 0 || file.size > MAX_LBE_MAP_BYTES) {
        model.uploadError = file.size === 0
          ? 'The ZIP file is empty.' : 'The ZIP file must be no larger than 512 MiB.';
        renderLbeMapControl(model);
        return;
      }
      const expectedInstanceId = snapshot?.server_instance_id ?? null;
      const refreshAfterUpload = model.mapsStatus !== 'loaded';
      let refreshMapList = false;
      model.uploading = true;
      model.uploadError = '';
      model.error = false;
      model.message = '';
      updateActions();
      renderLbeMapControl(model);
      const formData = new root.FormData();
      formData.append('map', file, file.name);
      try {
        const data = await api('/api/lbe-maps', 'POST', formData, true);
        const map = data?.map;
        if (current !== model) return;
        if (expectedInstanceId !== (snapshot?.server_instance_id ?? null)) {
          model.uploadError = 'The server restarted during upload. Check the map list before retrying.';
          return;
        }
        if (!validLbeMap(map)) throw new Error('The server returned invalid map ZIP details.');
        model.maps = [...(model.maps || []).filter(candidate => candidate.sha256 !== map.sha256), map]
          .sort((left, right) => left.name.localeCompare(right.name));
        refreshMapList = refreshAfterUpload;
        model.draft.lbeMap.value = map;
        model.message = 'Map ZIP uploaded and selected.';
      } catch (error) {
        if (current !== model) return;
        if (expectedInstanceId !== (snapshot?.server_instance_id ?? null)) {
          model.uploadError = 'The server restarted during upload. Check the map list before retrying.';
          return;
        }
        model.uploadError = error.status ? error.message : 'Could not upload the map ZIP.';
      } finally {
        if (current === model) {
          model.uploading = false;
          updateActions();
          renderLbeMapControl(model);
          if (refreshMapList) loadLbeMaps(model, true);
        }
      }
    }

    function updateSnapshot(data, source = 'http') {
      if (!Number.isSafeInteger(data?.revision) || data.revision < 0 ||
          typeof data?.server_instance_id !== 'string' || !data.server_instance_id) return false;
      const previousInstanceId = snapshot?.server_instance_id ?? null;
      const instanceChanged = data.server_instance_id !== previousInstanceId;
      if (snapshot && instanceChanged && source !== 'ws') return false;
      if (!instanceChanged && snapshot && data.revision < snapshot.revision) return false;
      if (instanceChanged) {
        resultSections = { status: 'idle' };
        items = new Map();
        catalog = null;
        if (current) {
          current.maps = [];
          current.mapsStatus = 'idle';
          current.mapsRequestId = (current.mapsRequestId || 0) + 1;
          current.uploadError = '';
        }
      }
      snapshot = { server_instance_id: data.server_instance_id, revision: data.revision,
        assignments: data.assignments || {}, deployments: data.deployments || {} };
      configurationAvailable = data.configuration_available !== false;
      catalogRefreshState = null;
      updateCreateControls();
      if (configurationAvailable) catalog = Array.isArray(data.packages) ? data.packages : [];
      else if (Array.isArray(data.packages) && data.packages.length) catalog = data.packages;
      catalogError = '';
      const currentGroups = data.groups || getGroups();
      for (const [name, model] of groupEdits) {
        if (!Object.hasOwn(currentGroups, name)) {
          groupEdits.delete(name);
        }
      }
      renderCatalog();
      renderPublication();
      if (current) updateActions();
      onSnapshot(data, source);
      if (instanceChanged && current?.expanded.has('lbe')) loadLbeMaps(current);
      return true;
    }

    function renderCatalog() {
      const unavailable = configurationAvailable === false;
      const nameCounts = packageNameCounts();
      catalogNotice.hidden = !unavailable;
      if (unavailable) {
        catalogNotice.querySelector('[role="status"]').textContent = catalogRefreshState === 'waiting-snapshot'
          ? 'Checking configuration status with the server…'
          : catalogRefreshState === 'waiting-connection' || !snapshotConnectionReady
            ? 'Waiting for the server connection to check configuration status.'
            : 'The server could not load saved packages. Repair or restore the registry and restart the server, then reload packages.';
      }
      const entries = catalog?.map(pkg =>
        `<button type="button" class="mg-item settings-package-item${current?.id === pkg.package_id ? ' active' : ''}" data-package-id="${esc(pkg.package_id)}" aria-pressed="${current?.id === pkg.package_id}" ${unavailable ? 'disabled' : ''}>
          <span class="mg-item-name">${esc(packageDisplayName(pkg.package_id, pkg.name, nameCounts))}</span>
          <span class="mg-item-count">${pkg.published_revision ? 'Deployed' : 'New'}</span>
        </button>`).join('');
      const empty = unavailable || entries || catalog?.length
        ? ''
        : `<p class="empty small">${esc(catalog ? 'No packages yet.' : catalogError || 'Loading packages…')}</p>`;
      const reload = !unavailable && catalogError
        ? '<button type="button" class="btn-outline" data-refresh-packages>Reload packages</button>' : '';
      const content = (entries || '') + empty + reload;
      if (catalogContent !== content) {
        catalogContent = content;
        packageList.innerHTML = content;
      }
      updateCreateControls();
    }

    function renderPublication() {
      const target = packagePanel.querySelector('[data-package-publication]');
      if (!current || !target) return;
      const pkg = packageById(current.id);
      const state = currentCatalogState(current);
      const revision = current.publishedRevision;
      const deployment = revision ? 'Loaded version ' + revision : 'Not deployed yet';
      const refs = Object.values(snapshot?.assignments || {}).filter(id => id === current.id).length;
      const publicationChanged = Boolean(pkg && (pkg.published_revision ?? null) !== (revision ?? null));
      let status = '';
      if (current.busy && state.removed) status = ' · Checking package status…';
      else if (state.removed) status = ' · This package was removed elsewhere. Reload or select another package.';
      else if (current.busy && publicationChanged) status = ' · Checking latest deployed version ' + pkg.published_revision + '…';
      else if (state.stale) status = ' · Package changes are available. Reload package before deploying.';
      else if (publicationChanged) status = ' · Latest deployed version ' + pkg.published_revision;
      target.textContent = deployment + status +
        (snapshot ? ' · Used by ' + refs + ' ' + (refs === 1 ? 'group' : 'groups') : '');
    }

    async function loadCatalog(force = false) {
      if (configurationAvailable === false || (!force && catalog) || catalogLoading) return;
      catalogLoading = true;
      catalogError = '';
      renderCatalog();
      const before = snapshot;
      try {
        const data = await api('/api/packages');
        if (snapshot === before) {
          catalog = data.packages;
          catalogError = '';
          if (data.sections && resultSections.status !== 'loaded') readSections(data.sections);
        }
      } catch (_) {
        if (snapshot === before) catalogError = 'Could not load configuration packages.';
      } finally {
        catalogLoading = false;
        renderCatalog();
        renderPublication();
        if (current) updateActions();
        refreshGroup();
      }
    }

    function updateSummary(before, profile, id) {
      if (snapshot !== before) return;
      catalog = (catalog || []).filter(pkg => pkg.package_id !== id);
      if (profile) {
        catalog.push({ package_id: id, name: profile.name, edit_revision: profile.edit_revision,
          published_revision: profile.published_revision });
        catalog.sort((a, b) => a.name.localeCompare(b.name));
      }
      renderCatalog();
      renderPublication();
      if (current) updateActions();
      refreshGroup();
    }

    function currentCatalogState(model) {
      if (!model?.saved || configurationAvailable === false || !catalog) return { stale: false, removed: false };
      const pkg = packageById(model.id);
      return {
        stale: Boolean(pkg && pkg.edit_revision !== model.editRevision),
        removed: !pkg,
      };
    }

    function hasChanges(model) {
      if (model.name !== model.savedName) return true;
      return Object.keys(model.draft).some(key => key === 'lbeMap'
        ? !sameValue(model.draft[key]?.value, model.saved[key]?.value)
        : !sameValue(model.draft[key], model.saved[key]));
    }

    function updateDeployAction(button, hint, reason) {
      button.disabled = Boolean(reason);
      hint.hidden = !reason;
      hint.textContent = reason;
    }

    function updateCreateControls() {
      const disabled = creating || configurationAvailable === false;
      const input = createForm.querySelector('input');
      const button = createForm.querySelector('button');
      if (input) input.disabled = disabled;
      if (button) button.disabled = disabled;
    }

    function updateActions() {
      const model = current;
      const footer = packagePanel.querySelector('.settings-actions');
      if (!model || !footer) return;
      const catalogState = currentCatalogState(model);
      const changed = hasChanges(model);
      const locked = model.busy || model.uploading || model.conflict || catalogState.stale || catalogState.removed || configurationAvailable === false;
      packagePanel.querySelector('.settings-editor').disabled = locked;
      packagePanel.querySelector('[data-package-name]').disabled = locked;
      footer.querySelector('[data-policy-action="discard"]').disabled = locked || !changed;
      let deployReason = '';
      if (model.busy || model.uploading) deployReason = 'Wait for the current package operation to finish.';
      else if (model.conflict) deployReason = 'Select Reload package and review the latest version before deploying.';
      else if (catalogState.removed) deployReason = 'Select another package.';
      else if (catalogState.stale) deployReason = 'Reload package before deploying.';
      else if (configurationAvailable === false) deployReason = 'Configuration packages are unavailable. Wait for the server to recover.';
      else if (isSettingManaged('lbeMap', model.draft) && !validLbeMap(model.draft.lbeMap?.value)) deployReason = 'Select a map ZIP before enabling LBE mode.';
      else if (!model.name.trim()) deployReason = 'Enter a package name before deploying.';
      updateDeployAction(footer.querySelector('[data-policy-action="deploy"]'), packagePanel.querySelector('[data-deploy-hint]'), deployReason);
      packagePanel.querySelector('[data-policy-action="delete"]').disabled = locked;
      packagePanel.querySelector('[data-policy-action="duplicate"]').disabled = locked;
      footer.querySelector('.settings-meta').textContent = changed ? 'Unsaved changes' : '';
      const feedback = packagePanel.querySelector('[data-settings-feedback]');
      feedback.textContent = model.message || model.mergeNotice || (configurationAvailable === false
        ? 'Configuration packages are unavailable. Wait for the server to recover.' : '');
      feedback.classList.toggle('settings-error', model.error);
      packagePanel.querySelector('[data-policy-action="reload"]').hidden =
        !model.conflict && !catalogState.stale && !catalogState.removed;
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
          <div class="settings-buttons">
            <button type="button" class="btn-outline" data-policy-action="duplicate">Duplicate package</button>
            <button type="button" class="btn-outline btn-danger-outline" data-policy-action="delete">Delete package</button>
          </div>
        </header>
        <label class="settings-name">Package name<input class="field" data-package-name maxlength="64" value="${esc(model.name)}"></label>
        <div class="push-note">Save &amp; deploy sends these settings to every group using this package. Clearing Manage leaves the device setting in place.</div>
        <fieldset class="settings-editor" aria-label="Package settings">${model.sections.map(section => renderSection(section, model)).join('')}</fieldset>
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
        const network = model.draft[input.dataset.setting].value.networks[Number(input.dataset.network)];
        input.value = network.password;
      }
      renderPublication();
      updateActions();
    }

    function captureEditorFocus() {
      const active = root.document?.activeElement;
      if (!active || !packagePanel.contains?.(active)) return null;
      const data = active.dataset || {};
      if (Object.hasOwn(data, 'manage')) return { kind: 'manage', id: data.manage };
      if (Object.hasOwn(data, 'setting')) {
        return { kind: 'setting', id: data.setting, field: data.field, network: data.network,
          day: data.day, type: active.type, value: active.value };
      }
      if (active.hasAttribute?.('data-map-choose')) return { kind: 'map-choose' };
      if (active.hasAttribute?.('data-map-retry')) return { kind: 'map-retry' };
      if (active.hasAttribute?.('data-package-name')) return { kind: 'package-name' };
      if (data.policyAction) return { kind: 'policy-action', action: data.policyAction };
      const section = active.closest?.('[data-settings-section]');
      if (active.tagName === 'SUMMARY' && section?.dataset.settingsSection) {
        return { kind: 'summary', id: section.dataset.settingsSection };
      }
      return null;
    }

    function renderEditorPreservingFocus() {
      const focus = captureEditorFocus();
      renderEditor();
      if (!focus) return;
      const candidates = packagePanel.querySelectorAll(
        '[data-manage], [data-setting], [data-map-choose], [data-map-retry], [data-package-name], [data-policy-action], [data-settings-section] > summary');
      const target = Array.from(candidates).find(element => {
        const data = element.dataset || {};
        if (focus.kind === 'manage') return data.manage === focus.id;
        if (focus.kind === 'setting') return data.setting === focus.id && data.field === focus.field &&
          data.network === focus.network && data.day === focus.day &&
          (focus.type !== 'radio' || element.value === focus.value);
        if (focus.kind === 'map-choose') return element.hasAttribute('data-map-choose');
        if (focus.kind === 'map-retry') return element.hasAttribute('data-map-retry');
        if (focus.kind === 'package-name') return element.hasAttribute('data-package-name');
        if (focus.kind === 'policy-action') return data.policyAction === focus.action;
        return focus.kind === 'summary' && element.tagName === 'SUMMARY' &&
          element.closest('[data-settings-section]')?.dataset.settingsSection === focus.id;
      });
      if (target && !target.hidden) target.focus({ preventScroll: true });
      else if (focus.kind === 'map-retry') {
        const fallback = packagePanel.querySelector('[data-map-choose]:enabled') ||
          packagePanel.querySelector('[data-settings-section="lbe"] > summary');
        fallback?.focus({ preventScroll: true });
      }
      else if (focus.kind === 'policy-action' && focus.action === 'reload') packagePanel.querySelector('[data-package-name]')?.focus({ preventScroll: true });
    }

    function readSections(sections, expectedInstanceId = snapshot?.server_instance_id ?? null) {
      if ((snapshot?.server_instance_id ?? null) !== expectedInstanceId) return;
      resultSections = { status: 'loaded' };
      items = new Map(sections.flatMap(section => section.items.map(item => [item.id, item])));
      renderResults();
    }

    function sameValue(left, right) {
      return JSON.stringify(left) === JSON.stringify(right);
    }

    function readProfile(model, data, preserveEdits = false, expectedInstanceId = snapshot?.server_instance_id ?? null) {
      const saved = loadDraft(data.sections, data.settings);
      const draft = structuredClone(saved);
      let name = data.name;
      const conflicts = [];
      function keepLocalEdit(label, previous, edited, latest, compare = sameValue) {
        if (compare(previous, edited) || compare(latest, edited)) return false;
        if (compare(previous, latest)) return true;
        conflicts.push(label);
        return false;
      }
      if (preserveEdits && model.saved) {
        if (keepLocalEdit('package name', model.savedName, model.name, data.name)) name = model.name;
        for (const item of data.sections.flatMap(section => section.items)) {
          const key = item.id;
          if (!model.saved[key] || !model.draft[key]) continue;
          const compare = key === 'lbeMap'
            ? (left, right) => sameValue(left?.value, right?.value)
            : sameValue;
          if (keepLocalEdit(item.label, model.saved[key], model.draft[key], saved[key], compare)) {
            draft[key] = structuredClone(model.draft[key]);
            if (key === 'lbeMap') draft[key].managed = saved[key].managed;
          }
        }
      }
      if (draft.wifi?.managed === false && saved.wifi) {
        draft.wifi.value = structuredClone(saved.wifi.value);
      }
      Object.assign(model, { sections: data.sections, editRevision: data.edit_revision,
        publishedRevision: data.published_revision,
        savedName: data.name, saved, name, draft,
        mergeNotice: conflicts.length
          ? 'Some local edits overlapped newer server changes and were replaced with the latest values for ' +
            conflicts.join(', ') + '. Review before deploying.'
          : '' });
      readSections(data.sections, expectedInstanceId);
    }

    async function readResultSections() {
      if (resultSections.status !== 'idle') return;
      const request = { status: 'loading' };
      resultSections = request;
      try {
        const data = await api('/api/packages');
        if (resultSections !== request) return;
        readSections(data.sections || []);
      } catch (_) {
        if (resultSections === request) resultSections = { status: 'failed' };
        // Report status is available independently of setting labels and formatters.
      }
    }

    function confirmDeployment(profile, { editorName, groupName, packageId } = {}) {
      const { groups, devices } = profile.impact;
      const groupCount = groups + ' ' + (groups === 1 ? 'group' : 'groups');
      const deviceCount = devices + ' ' + (devices === 1 ? 'device' : 'devices');
      const delivery = groups
        ? 'The count includes offline devices, which receive the deployed version when they connect.'
        : 'Devices will receive this version once a group uses this package.';
      let lbeDetail = '';
      if (profile.lbe_mode === true) {
        lbeDetail = profile.lbe_map_name
          ? ' LBE mode will turn on and import ' + profile.lbe_map_name + '. The PICO LBE app may open in the foreground.'
          : ' LBE mode will turn on with its selected map ZIP. The PICO LBE app may open in the foreground.';
      } else if (profile.lbe_mode === false) {
        lbeDetail = ' LBE mode will turn off. Any selected ZIP stays in this package for the next On deployment.';
      }
      if (profile.clear) {
        return confirmAction('Stop managing settings for "' + groupName + '" (' + deviceCount + ')?\n\n' +
          'This stops managing all settings for this group and leaves current device values in place. ' +
          (profile.wifi_mode === 'priority' || profile.wifi_mode === 'android_selection'
            ? 'Wi-Fi auto-connect settings still apply after release. To let a network reconnect automatically, enable auto-connect for it on the headset. '
            : '') +
          'Offline devices receive the updated policy when they connect.');
      }
      if (profile.use_current) {
        return confirmAction('Deploy the current version of "' + packageDisplayName(packageId, profile.name) + '" to "' + groupName +
          '" (' + deviceCount + ')?\n\n' + delivery + lbeDetail);
      }
      const detail = groupName
        ? 'This deploys the saved settings again as a new version to all groups using it.'
        : 'This saves your edits and deploys them.';
      return confirmAction('Deploy "' + packageDisplayName(packageId, editorName ?? profile.name) + '" to ' + groupCount + ' and ' + deviceCount +
        '?\n\n' + detail + ' ' + delivery + lbeDetail);
    }

    async function request(model, action, body) {
      model.busy = true;
      model.message = '';
      model.error = false;
      if (action === 'reload') model.uploadError = '';
      if (model.saved) updateActions();
      else renderEditor();
      const path = packagePath(model.id);
      const before = snapshot;
      const beforeInstanceId = before?.server_instance_id ?? null;
      let writing = false;
      try {
        let data;
        if (action === 'reload') {
          data = await api(path);
        } else if (action === 'deploy') {
          const fresh = await api(path);
          if (fresh.edit_revision !== model.editRevision) {
            throw Object.assign(new Error('The package changed. Reload and review the latest version before deploying.'),
              { status: 409, code: 'revision_conflict' });
          }
          const lbeMode = body.settings.lbeMode?.managed === true ? body.settings.lbeMode.value : null;
          const lbeMap = lbeMode === true && body.settings.lbeMap?.managed === true
            ? body.settings.lbeMap.value : null;
          if (!confirmDeployment({ ...fresh, lbe_mode: lbeMode,
            lbe_map_name: validLbeMap(lbeMap) ? lbeMap.name : null },
          { editorName: model.name.trim(), packageId: model.id })) return;
          writing = true;
          data = await api(path + '/save-and-deploy', 'POST', { ...body, expected_impact: fresh.impact.token });
        } else if (action === 'delete') {
          writing = true;
          data = await api(path, 'DELETE', body);
        } else {
          throw new Error('Unknown package action.');
        }
        if ((snapshot?.server_instance_id ?? null) !== beforeInstanceId) {
          model.conflict = true;
          model.message = writing
            ? 'Could not confirm the request after the server restarted. Reload the package to check its current state.'
            : 'The server restarted while the package was loading. Reload to get the current version.';
          model.error = true;
          return;
        }
        if (action === 'delete') {
          current = null;
          updateSummary(before, null, model.id);
          renderCatalog();
          return;
        }
        readProfile(model, data, action === 'reload', beforeInstanceId);
        if (action === 'reload') {
          model.maps = [];
          model.mapsStatus = 'idle';
          model.mapsRequestId = (model.mapsRequestId || 0) + 1;
        }
        updateSummary(before, data, model.id);
        model.conflict = false;
        model.error = false;
        model.uploadError = '';
        model.message = action === 'deploy' ? 'Package saved. Deployment started.'
          : action === 'reload' && hasChanges(model) && !model.mergeNotice
            ? 'Latest version loaded. Review your unsaved edits before deploying.' : '';
      } catch (error) {
        model.error = true;
        if (writing && (!error.status || error.status >= 500)) {
          model.conflict = true;
          model.message = 'Could not confirm the request. Reload to check the latest package.';
        } else if (error.code === 'map_unavailable') {
          model.message = error.message;
          refreshLbeMapLibrary();
        } else if (action === 'reload' && error.status === 404) {
          catalog = (catalog || []).filter(pkg => pkg.package_id !== model.id);
          renderCatalog();
          model.message = 'This package was removed elsewhere. Select another package.';
        } else if (error.status === 409 &&
            ['revision_conflict', 'deployment_targets_changed'].includes(error.code)) {
          model.conflict = true;
          model.message = action === 'deploy' && writing
            ? 'The package or deployment targets changed. Reload and review before deploying again.'
            : error.message;
        } else {
          model.message = error.status ? error.message : 'Could not ' + action + ' the package.';
        }
      } finally {
        model.busy = false;
        renderEditorPreservingFocus();
        if (action === 'reload' && model.expanded.has('lbe') && current === model) loadLbeMaps(model);
      }
    }

    function canSwitchPackage(id) {
      if (!current || current.id === id) return true;
      if (current.busy || current.uploading) return false;
      return !current.saved || !hasChanges(current) || confirmAction('Discard the unsaved changes in "' + current.savedName + '"?');
    }

    async function duplicatePackage(model) {
      const catalogState = currentCatalogState(model);
      if (model.busy || model.uploading || model.conflict || catalogState.stale || catalogState.removed || configurationAvailable === false) return;
      if (hasChanges(model) && !confirmAction('Discard the unsaved changes in "' + model.savedName + '" before duplicating?')) return;
      const name = promptForValue('Name for the copied package:', Array.from(model.savedName).slice(0, 57).join('') + ' (copy)');
      if (name === null || !name.trim()) return;
      model.busy = true;
      model.message = 'Creating package copy…';
      model.error = false;
      updateActions();
      const before = snapshot;
      const beforeInstanceId = before?.server_instance_id ?? null;
      try {
        const profile = await api(packagePath(model.id) + '/duplicate', 'POST', {
          expected_revision: model.editRevision,
          name: name.trim(),
        });
        if ((snapshot?.server_instance_id ?? null) !== beforeInstanceId) {
          model.conflict = true;
          model.error = true;
          model.message = 'Could not confirm the copy after the server restarted. Reload the package to check its current state.';
          return;
        }
        updateSummary(before, profile, profile.package_id);
        if (current === model) selectPackage(profile.package_id, profile, beforeInstanceId);
      } catch (error) {
        model.error = true;
        model.message = error.status ? error.message : 'Could not duplicate the package.';
        if (error.status === 409 && error.code === 'revision_conflict') model.conflict = true;
      } finally {
        model.busy = false;
        if (current === model) renderEditor();
      }
    }

    function selectPackage(id, profile, expectedInstanceId = snapshot?.server_instance_id ?? null) {
      if (current?.id === id) return;
      current = { id, expanded: new Set(['basic']), maps: [], mapsStatus: 'idle' };
      renderCatalog();
      if (!profile || expectedInstanceId !== (snapshot?.server_instance_id ?? null)) return request(current, 'reload');
      readProfile(current, profile, false, expectedInstanceId);
      renderEditor();
    }

    async function createPackage(event) {
      event.preventDefault();
      const input = createForm.querySelector('input');
      const feedback = createForm.querySelector('[role="status"]');
      const name = input.value.trim();
      if (creating || !name) return;
      if (configurationAvailable === false) {
        feedback.textContent = 'Configuration packages are unavailable. Wait for the server to recover.';
        return;
      }
      creating = true;
      updateCreateControls();
      feedback.textContent = 'Creating package…';
      const before = snapshot;
      const beforeInstanceId = before?.server_instance_id ?? null;
      const editorBefore = current;
      try {
        const profile = await api('/api/packages', 'POST', { name });
        updateSummary(before, profile, profile.package_id);
        if (current === editorBefore && canSwitchPackage(profile.package_id)) {
          selectPackage(profile.package_id, profile, beforeInstanceId);
        }
        input.value = '';
        feedback.textContent = 'Package created.';
      } catch (error) {
        feedback.textContent = error.status ? error.message : 'Could not create the package.';
      } finally {
        creating = false;
        updateCreateControls();
      }
    }

    function renderGroup(name) {
      if (name !== (group?.name ?? null)) {
        expandedResults.clear();
        if (name && resultSections.status === 'failed') resultSections = { status: 'idle' };
        group = name ? groupEdits.get(name) || { name } : null;
        groupPanel.innerHTML = group ? `<section class="settings-section">
          <header><h3>Configuration package</h3><p>Deploy a package to this group, or choose No managed settings to stop managing settings and keep current device values.</p></header>
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
      const nameCounts = packageNameCounts();
      const options = '<option value="">No managed settings</option>' +
        (catalog || []).map(pkg => `<option value="${esc(pkg.package_id)}">${esc(packageDisplayName(pkg.package_id, pkg.name, nameCounts))}</option>`).join('') +
        (selected && !known ? `<option value="${esc(selected)}" disabled>Package unavailable</option>` : '') +
        `<option value="${CREATE_PACKAGE_OPTION}">Create package…</option>`;
      if (select.packageOptions !== options) {
        select.packageOptions = options;
        select.innerHTML = options;
      }
      if (select.value !== (selected || '')) select.value = selected || '';
      select.disabled = !snapshot || Boolean(group.busy) || configurationAvailable === false;
      const edit = groupPanel.querySelector('[data-group-action="edit"]');
      edit.hidden = !known;
      edit.disabled = Boolean(group.busy) || configurationAvailable === false;
      let deployReason = '';
      if (!snapshot) deployReason = 'Waiting for group details to load.';
      else if (group.busy) deployReason = group.busy;
      else if (configurationAvailable === false) deployReason = 'Configuration packages are unavailable. Wait for the server to recover.';
      else if (selected && !known) deployReason = 'This package is unavailable. Choose another package.';
      else if (known && !known.published_revision) {
        deployReason = 'Open Edit package and select Save & deploy to deploy it first.';
      }
      updateDeployAction(groupPanel.querySelector('[data-group-action="deploy"]'), groupPanel.querySelector('[data-deploy-hint]'), deployReason);
      const feedback = groupPanel.querySelector('[data-assignment-feedback]');
      feedback.textContent = group.message || '';
      feedback.classList.toggle('settings-error', Boolean(group.error));
      const deployment = currentDeployment(group);
      groupPanel.querySelector('[data-group-publication]').textContent = configurationAvailable === false
        ? 'Configuration packages unavailable.'
        : deployment ? 'Deployed version ' + deployment.revision
          : assigned ? 'Not deployed yet.' : 'This group has no managed settings.';
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
      const active = root.document?.activeElement;
      const focusSelector = ['[data-group-action="retry"]', '[data-group-action="toggle-result"]']
        .find(selector => active?.matches?.(selector));
      const outcomes = groupOutcomes(group.name, assigned, deployment);
      const rows = [];
      for (const id of sortGroupMembers(outcomes.keys())) {
        const { device, outcome: [state, label, detail] } = outcomes.get(id);
        const readiness = wifiReadiness(device, deployment);
        const autoReturnNotice = wifiAutoReturnNotice(device, deployment);
        const results = reportedResults(device?.configuration_state, deployment, items);
        const expanded = expandedResults.has(id);
        const name = esc(device?.label || 'Unlabeled device');
        const listId = 'group-device-results-' + esc(id);
        let button = '';
        if (deployment) {
          const key = retryKey(id, deployment);
          const pending = retrying.get(key);
          if (pending && device?.configuration_state && pending.report !== JSON.stringify(device.configuration_state)) {
            pending.reportChanged = true;
            if (!pending.sending) retrying.delete(key);
          }
          if (configurationAvailable !== false && retryEligible(device, deployment)) {
            const waiting = retrying.has(key);
            button = `<button type="button" class="btn-outline${waiting ? ' retry-pending' : ''}" data-group-action="retry" data-device-id="${esc(id)}" ${waiting ? 'aria-disabled="true"' : ''}>${waiting ? 'Retry requested' : 'Retry'}</button>`;
          }
        }
        rows.push(`<article class="settings-device-result ${state}"${results ? ` data-result-device="${esc(id)}"` : ''}>
          <div class="settings-result-head">
            ${results ? `<button type="button" class="settings-result-toggle" data-group-action="toggle-result"
              data-device-id="${esc(id)}" aria-expanded="${expanded}" aria-controls="${listId}">${name}</button>` : `<strong>${name}</strong>`}
            <div class="settings-result-actions"><span>${esc(label)}</span>${button}</div>
          </div>
          <div class="settings-meta">${deviceIdHtml(device || {}, id)} · ${esc(detail)} ${currentWifiHtml(device)}</div>
          ${readiness ? `<div class="settings-meta">${readiness}</div>` : ''}
          ${autoReturnNotice ? `<div class="settings-meta">${esc(autoReturnNotice)}</div>` : ''}
          ${results ? `<ul id="${listId}" class="settings-readback" role="list" aria-label="Results at apply time"${expanded ? '' : ' hidden'}>${results}</ul>` : ''}
        </article>`);
      }
      const summary = deploymentCount(outcomes, deployment);
      if (count.textContent !== summary.text) count.textContent = summary.text;
      count.dataset.state = summary.state;
      const content = rows.join('') || '<p class="settings-meta">This group has no devices.</p>';
      if (target.resultContent !== content) {
        target.resultContent = content;
        target.innerHTML = content;
        if (focusSelector) {
          Array.from(target.querySelectorAll(focusSelector))
            .find(button => button.dataset.deviceId === active.dataset.deviceId)?.focus({ preventScroll: true });
        }
      }
      if (deployment && assigned) {
        readResultSections();
      }
    }

    async function deployPackage(model) {
      if (configurationAvailable === false) {
        model.error = true;
        model.message = 'Configuration packages are unavailable. Wait for the server to recover.';
        refreshGroup();
        return;
      }
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
        if (!confirmDeployment(fresh, { groupName: model.name, packageId: id })) return;
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
            ? 'Release saved. Offline devices receive it when they reconnect.'
            : 'Deployment started. Check Device status for results.';
        }
      } catch (error) {
        model.error = true;
        if (error.code === 'map_unavailable') {
          model.message = error.message;
          refreshLbeMapLibrary();
        } else model.message = writing && (!error.status || error.status >= 500)
          ? 'Could not confirm deployment. Check the group package before trying again.'
          : writing && ['revision_conflict', 'deployment_targets_changed'].includes(error.code)
            ? 'The package or deployment targets changed. Select Deploy to review them again.'
            : error.status ? error.message : 'Could not check the deployment targets.';
      } finally {
        model.busy = false;
        refreshGroup();
      }
    }

    async function retryDevice(model, deviceId) {
      if (configurationAvailable === false) return;
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
        if (manage === 'lbeMode') {
          model.error = false;
          model.message = '';
          model.uploadError = '';
        }
        if (manage === 'wifi') {
          if (!target.checked) {
            model.draft.wifi.value = structuredClone(model.saved.wifi.value);
            model.error = false;
            model.message = '';
            renderEditor();
            packagePanel.querySelector('[data-manage="wifi"]')?.focus({ preventScroll: true });
            return;
          }
          const valuePanel = target.closest('.settings-row').querySelector('.settings-value');
          updateWifiMoveButtons(valuePanel, model.draft);
          updateWifiWarnings(valuePanel, model.draft);
        }
        target.closest('.settings-row').querySelector('.settings-value').disabled = !target.checked;
        if (manage === 'lbeMode') renderLbeMapControl(model);
        const section = target.closest('[data-settings-section]');
        section.querySelector('.settings-count').textContent =
          sectionCount(model.sections.find(candidate => candidate.id === section.dataset.settingsSection), model.draft);
      } else if (setting) {
        const entry = model.draft[setting];
        const value = entry.value;
        if (!isSettingManaged(setting, model.draft)) return;
        if (setting === 'lbeMap' || setting === 'lbeMode') model.uploadError = '';
        if (network !== undefined) {
          const row = value.networks[Number(network)];
          row[field] = target.type === 'checkbox' ? target.checked : target.value;
          if (field === 'ssid' || field === 'security') {
            const article = target.closest('.wifi-network');
            const password = article.querySelector('[data-field="password"]');
            password.disabled = row.security === 'OPEN';
            row.password = password.disabled ? '' : savedWifiPassword(row, model.saved.wifi.value.networks);
            password.value = row.password;
            article.querySelector('[data-password-help]').textContent = wifiPasswordHelp(row);
          }
          if (field === 'hidden') updateWifiWarnings(target.closest('.settings-value'), model.draft);
        } else if (setting === 'lbeMap') {
          entry.value = model.maps?.find(map => map.sha256 === target.value) ||
            (model.saved?.lbeMap?.value?.sha256 === target.value ? model.saved.lbeMap.value : null);
          renderLbeMapControl(model);
        } else if (setting === 'lbeMode' && target.type === 'radio') {
          entry.value = target.value === 'true';
          model.error = false;
          model.message = '';
          renderLbeMapControl(model);
        } else if (setting === 'startupApp') {
          entry.value = target.value;
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
          if (target.type === 'range') {
            const item = model.sections.flatMap(section => section.items).find(item => item.id === setting);
            const formatted = actualValue(item, entry.value);
            target.nextElementSibling.value = formatted;
            target.setAttribute('aria-valuetext', formatted);
          }
        }
      } else return;
      model.error = false;
      model.message = '';
      updateActions();
    }

    function handleEditorClick(event) {
      const retryMaps = event.target.closest('[data-map-retry]');
      if (retryMaps) {
        if (!retryMaps.disabled && current && current.mapsStatus !== 'loading') loadLbeMaps(current, true);
        return;
      }
      const mapChoose = event.target.closest('[data-map-choose]');
      if (mapChoose) {
        if (mapChoose.disabled || !current || !isSettingManaged('lbeMap', current.draft) || current.busy ||
            current.uploading || current.mapsStatus === 'loading' || current.conflict) return;
        packagePanel.querySelector('[data-map-upload]')?.click();
        return;
      }
      const button = event.target.closest('[data-policy-action]');
      const model = current;
      if (!button || button.disabled || !model || model.busy) return;
      const action = button.dataset.policyAction;
      if (action === 'reload') return request(model, 'reload');
      if (configurationAvailable === false) return;
      const catalogState = currentCatalogState(model);
      if (!model.saved || model.conflict || catalogState.stale || catalogState.removed) return;
      if (action === 'discard') {
        model.name = model.savedName;
        model.draft = structuredClone(model.saved);
        model.message = 'Changes discarded.';
        model.mergeNotice = '';
        model.error = false;
        model.uploadError = '';
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
        if (confirmAction('Delete configuration package "' + model.savedName + '"?')) {
          return request(model, 'delete', { expected_revision: model.editRevision });
        }
      } else if (action === 'duplicate') {
        return duplicatePackage(model);
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
      if (!group || group.busy || configurationAvailable === false || !event.target.matches('[data-group-package]')) return;
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
      if (!group) return;
      const button = event.target.closest('[data-group-action]');
      if (button) {
        if (button.disabled) return;
        const action = button.dataset.groupAction;
        if (action === 'deploy') return deployPackage(group);
        if (action === 'retry') return retryDevice(group, button.dataset.deviceId);
        if (action === 'edit') return showPackages(selectedPackage(group));
      } else if (event.target.closest('button, a')) return;
      const id = event.target.closest('[data-result-device]')?.dataset.resultDevice;
      if (!id) return;
      if (!expandedResults.delete(id)) expandedResults.add(id);
      renderResults();
    });
    function handleCatalogClick(event) {
      const button = event.target.closest('[data-package-id], [data-refresh-packages]');
      if (!button || button.disabled) return;
      const id = button.dataset.packageId;
      if (!id) {
        if (configurationAvailable === false) {
          const requested = requestGroupSnapshot();
          catalogRefreshState = requested ? 'waiting-snapshot' : 'waiting-connection';
          renderCatalog();
          refreshGroup();
          return;
        }
        return loadCatalog(true);
      }
      if (canSwitchPackage(id)) return selectPackage(id);
    }
    packageList.addEventListener('click', handleCatalogClick);
    catalogNotice.addEventListener('click', handleCatalogClick);
    createForm.addEventListener('submit', createPackage);
    packagePanel.addEventListener('input', handleInput);
    packagePanel.addEventListener('click', handleEditorClick);
    packagePanel.addEventListener('toggle', event => {
      const section = event.target.dataset.settingsSection;
      if (!section || !current) return;
      if (event.target.open) current.expanded.add(section);
      else current.expanded.delete(section);
      if (section === 'lbe' && event.target.open) loadLbeMaps(current);
    }, true);

    packagePanel.addEventListener('change', event => {
      const input = event.target.closest('[data-map-upload]');
      if (input) {
        if (!current || current.busy || current.conflict || input.matches(':disabled')) return;
        const file = input.files?.[0];
        input.value = '';
        if (file) return uploadLbeMap(current, file);
        return;
      }
      if (event.target.closest('[data-setting="lbeMap"]')) handleInput(event);
    });

    function showPackages(id) {
      if (id && !canSwitchPackage(id)) return;
      openPackages();
      loadCatalog();
      if (id) return selectPackage(id);
    }

    renderEditor();
    function setGroupSnapshotConnected(connected) {
      snapshotConnectionReady = connected;
      if (configurationAvailable !== false) return;
      if (!connected && catalogRefreshState === 'waiting-snapshot') {
        catalogRefreshState = 'waiting-connection';
      } else if (connected && catalogRefreshState === 'waiting-connection') {
        catalogRefreshState = requestGroupSnapshot() ? 'waiting-snapshot' : 'waiting-connection';
      }
      renderCatalog();
    }

    return { renderGroup, updateSnapshot, showPackages, groupPackageSummary, setGroupSnapshotConnected };
  }

  const api = { createController, currentWifiHtml, wifiPermissionWarningHtml, loadDraft, buildPayload, deviceOutcome, retryEligible };
  root.MdmConfigurationPackages = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
