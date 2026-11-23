const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const membership = require('../styly_mdm/static/group-membership.js');

const source = fs.readFileSync(path.join(__dirname, '..', 'styly_mdm', 'static', 'index.html'), 'utf8').replace(/\r\n/g, '\n');

function functionSource(name) {
  const start = source.indexOf('function ' + name + '(');
  assert.notEqual(start, -1, 'function ' + name + ' exists');
  const end = source.indexOf('\n      }', start);
  assert.notEqual(end, -1, 'function ' + name + ' has a closing boundary');
  return source.slice(start, end + '\n      }'.length);
}

function makeManageContext(overrides = {}) {
  const context = vm.createContext({
    groups: {},
    manageSelectedGroup: null,
    memberEdits: new Map(),
    membership,
    manageSearch: '',
    manageSort: 'label-asc',
    membershipSave: null,
    pendingSelection: null,
    membershipNotice: { textContent: '' },
    pendingGroupCreates: new Set(),
    mgDetail: { querySelector() { return null; } },
    renamingManageGroup: false,
    renameGroupDraft: '',
    view: 'manage',
    wsSend() { return true; },
    configurationController: { renderGroup() {} },
    addLog() {},
    render() {},
    ...overrides,
  });
  if (!Object.hasOwn(overrides, 'showMembershipFeedback')) {
    context.showMembershipFeedback = (name, message, state) => {
      context.membershipSave = { name, message, state };
    };
  }
  for (const name of [
    'selectedManageMembers', 'selectManageGroup', 'applyPendingManageGroupSelection',
    'sendCreateGroup', 'backToConsole', 'openManage',
  ]) {
    vm.runInContext(functionSource(name), context);
  }
  vm.runInContext(functionSource('handleMessage'), context);
  return context;
}

function makeGroupSnapshotContext(overrides = {}) {
  const context = makeManageContext({
    groups: { Studio: ['online'], Other: ['offline'] },
    manageSelectedGroup: 'Studio',
    activeGroup: 'Studio',
    memberEdits: new Map(),
    selectedIds: new Set(),
    powerConfirm: { checked: false },
    membershipSnapshotReady: false,
    groupsLoaded: false,
    document: { getElementById() { return { hidden: false }; } },
    window: { MdmGroupMembership: membership },
    syncActiveGroupSelection() {},
    powerDeviceTargets() { return new Set(); },
    sameSet(a, b) { return a.size === b.size && [...a].every(id => b.has(id)); },
    render() {},
    ...overrides,
  });
  vm.runInContext(functionSource('applyGroupListSnapshot'), context);
  context.configurationController.updateSnapshot = (message, source) => {
    context.applyGroupListSnapshot(message, source);
  };
  return context;
}

function selectedMembers(context) {
  return membership.selectedMembers(context.groups[context.manageSelectedGroup] || [], context.memberEdits);
}

function receiveGroupList(context, groups, source = 'ws') {
  const message = {
    type: 'GROUP_LIST',
    groups,
    registry_writable: true,
    configuration_available: true,
  };
  if (source === 'http') context.configurationController.updateSnapshot(message, source);
  else context.handleMessage(message);
}

function installSocketCloseHandler(context) {
  const close = source.match(/socket\.onclose = function \(\) \{([\s\S]*?)\n        \};/);
  assert.ok(close, 'actual WebSocket close handler exists');
  vm.runInContext(
    'function simulateSocketClose(socket) { ws = socket; socket.onclose = function () {' +
      close[1] + '\n}; socket.onclose(); }',
    context,
  );
}

function makeManageDetailContext() {
  let context;
  const makeSearchInput = () => ({
    selectionStart: 0,
    selectionEnd: 0,
    isComposing: true,
    matches(selector) { return selector === '[data-mg-search]'; },
    focus() { context.document.activeElement = this; },
    setSelectionRange(start, end) { this.selectionStart = start; this.selectionEnd = end; },
  });
  const detail = {
    html: '',
    list: null,
    search: null,
    subtitle: null,
    selectedCount: null,
    set innerHTML(html) {
      this.html = html;
      this.list = html.includes('id="mgDevList"') ? { scrollTop: 0, innerHTML: '' } : null;
      this.search = html.includes('data-mg-search') ? makeSearchInput() : null;
      this.subtitle = html.includes('class="mg-sub"') ? { textContent: '' } : null;
      this.selectedCount = html.includes('mg-foot-count') ? { textContent: '' } : null;
    },
    get innerHTML() { return this.html; },
    querySelector(selector) {
      return selector === '[data-mg-search]' ? this.search :
        selector === '.mg-sub' ? this.subtitle :
          selector === '.mg-foot-count' ? this.selectedCount : null;
    },
  };
  const devices = { online: { status: 'online' }, offline: { status: 'offline' } };
  const contextObject = {
    groups: { Studio: ['online'], Gallery: ['offline'] },
    devices,
    manageSelectedGroup: 'Studio',
    manageTab: 'devices',
    manageSearch: 'on',
    manageSort: 'label-asc',
    manageRenderedLayout: null,
    renderedMemberList: null,
    renderedMemberListContent: null,
    renamingManageGroup: false,
    renameGroupDraft: '',
    memberEdits: new Map(),
    membership,
    document: {
      activeElement: null,
      getElementById(id) { return id === 'mgDevList' ? detail.list : null; },
    },
    mgDetail: detail,
    groupSettingsPanel: { hidden: false },
    deviceById(id) { return devices[id] || null; },
    isOnline(device) { return device?.status === 'online'; },
    esc(value) { return String(value); },
    renderMgDevListHtml() { return '<div>members</div>'; },
    renderMembershipFeedback() {},
    configurationController: { renderGroup() {} },
  };
  context = vm.createContext(contextObject);
  for (const name of ['selectedManageMembers', 'renderManageDetail', 'refreshManageDetailContents', 'refreshMgDevList', 'updateMgCounts']) {
    vm.runInContext(functionSource(name), context);
  }
  return context;
}

test('a created group becomes the selection after its confirming snapshot', () => {
  const context = makeGroupSnapshotContext({
    groups: {},
    manageSelectedGroup: null,
  });

  context.sendCreateGroup('New group');
  context.handleMessage({ type: 'GROUP_CREATED', name: 'New group' });
  assert.equal(context.manageSelectedGroup, null);

  receiveGroupList(context, { 'New group': [] });
  assert.equal(context.manageSelectedGroup, 'New group');
});

test('a duplicate in-flight create is rejected without replacing its selection intent', () => {
  const sent = [];
  const context = makeManageContext({
    groups: {},
    wsSend(message) { sent.push(message); return true; },
  });
  assert.equal(context.sendCreateGroup('Same name'), true);
  assert.equal(context.sendCreateGroup('Same name'), false);
  assert.equal(sent.filter(message => message.type === 'CREATE_GROUP').length, 1);
});

test('a create response only advances the latest create intent', () => {
  const context = makeGroupSnapshotContext({ groups: {}, manageSelectedGroup: null });

  context.sendCreateGroup('First');
  context.sendCreateGroup('Latest');
  context.handleMessage({ type: 'GROUP_CREATED', name: 'First' });
  assert.equal(context.manageSelectedGroup, null);

  context.handleMessage({ type: 'GROUP_CREATED', name: 'Latest' });
  receiveGroupList(context, { Latest: [] });
  assert.equal(context.manageSelectedGroup, 'Latest');
});

test('a later manual re-selection supersedes an outstanding create intent', () => {
  const context = makeGroupSnapshotContext({ groups: { Studio: [] }, manageSelectedGroup: 'Studio' });
  membership.toggleMember('Studio', context.groups, context.memberEdits, 'draft-device');
  context.selectManageGroup('Studio');
  assert.deepEqual([...selectedMembers(context)], ['draft-device']);
  context.sendCreateGroup('New group');
  context.selectManageGroup('Studio');
  context.handleMessage({ type: 'GROUP_CREATED', name: 'New group' });

  receiveGroupList(context, { Studio: [], 'New group': [] });
  assert.equal(context.manageSelectedGroup, 'Studio');
  assert.deepEqual([...selectedMembers(context)], ['draft-device']);
});

test('leaving manage prevents a late create response from selecting on re-entry', () => {
  const context = makeGroupSnapshotContext({
    groups: { Studio: [] },
    manageSelectedGroup: 'Studio',
  });
  context.sendCreateGroup('New group');
  context.backToConsole();
  context.openManage(null);
  context.handleMessage({ type: 'GROUP_CREATED', name: 'New group' });
  receiveGroupList(context, { Studio: [], 'New group': [] });

  assert.equal(context.manageSelectedGroup, 'Studio');
});

test('the latest manual choice wins over a create response', () => {
  const context = makeGroupSnapshotContext({
    groups: { Existing: [], Other: [] },
    manageSelectedGroup: 'Existing',
    membershipSave: { name: 'Existing', state: 'saving' },
  });

  context.sendCreateGroup('New group');
  context.selectManageGroup('Other');
  context.handleMessage({ type: 'GROUP_CREATED', name: 'New group' });
  receiveGroupList(context, { Existing: [], Other: [], 'New group': [] });
  assert.equal(context.manageSelectedGroup, 'Existing');
  context.handleMessage({ type: 'GROUP_MEMBERS_SET', operation: 'UPDATE_GROUP_MEMBERS', name: 'Existing', members: [] });
  assert.equal(context.manageSelectedGroup, 'Other');
});

test('manual group selection is deferred until an in-flight save completes', () => {
  const context = makeGroupSnapshotContext({
    groups: { Current: [], Other: [] },
    manageSelectedGroup: 'Current',
    membershipSave: { name: 'Current', state: 'saving' },
  });

  context.selectManageGroup('Other');
  assert.equal(context.manageSelectedGroup, 'Current');
  context.handleMessage({ type: 'GROUP_MEMBERS_SET', operation: 'UPDATE_GROUP_MEMBERS', name: 'Current', members: [] });
  assert.equal(context.manageSelectedGroup, 'Other');
});

test('a confirmed created group is selected after the membership save acknowledgement', () => {
  const context = makeGroupSnapshotContext({
    groups: { Studio: [] },
    manageSelectedGroup: 'Studio',
    membershipSave: { name: 'Studio', state: 'saving' },
  });
  context.sendCreateGroup('New group');
  context.handleMessage({ type: 'GROUP_CREATED', name: 'New group' });
  receiveGroupList(context, { Studio: [], 'New group': [] });
  assert.equal(context.manageSelectedGroup, 'Studio');

  context.handleMessage({
    type: 'GROUP_MEMBERS_SET',
    operation: 'UPDATE_GROUP_MEMBERS',
    name: 'Studio',
    members: [],
  });
  assert.equal(context.manageSelectedGroup, 'New group');
});

test('a confirmed create removed before save completion cannot select a later same-name group', () => {
  const context = makeGroupSnapshotContext({
    groups: { Studio: [] },
    manageSelectedGroup: 'Studio',
    membershipSave: { name: 'Studio', state: 'saving' },
  });
  context.sendCreateGroup('New group');
  context.handleMessage({ type: 'GROUP_CREATED', name: 'New group' });
  receiveGroupList(context, { Studio: [], 'New group': [] });
  receiveGroupList(context, { Studio: [] });
  receiveGroupList(context, { Studio: [], 'New group': [] });

  context.handleMessage({
    type: 'GROUP_MEMBERS_SET',
    operation: 'UPDATE_GROUP_MEMBERS',
    name: 'Studio',
    members: [],
  });
  assert.equal(context.manageSelectedGroup, 'Studio');
});

test('a create error prevents a later snapshot from selecting the rejected group', () => {
  const context = makeGroupSnapshotContext({ groups: {}, manageSelectedGroup: null });
  context.sendCreateGroup('Duplicate');
  context.handleMessage({ type: 'ERROR', operation: 'CREATE_GROUP', name: 'Duplicate', message: 'Already exists' });
  receiveGroupList(context, { Duplicate: [] });
  assert.equal(context.manageSelectedGroup, null);
});

test('a delayed create error cannot clear a later manual selection', () => {
  const context = makeGroupSnapshotContext({
    groups: { Current: [], Other: [] },
    manageSelectedGroup: 'Current',
    membershipSave: { name: 'Current', state: 'saving' },
  });
  context.sendCreateGroup('Duplicate');
  context.selectManageGroup('Other');
  context.handleMessage({ type: 'ERROR', operation: 'CREATE_GROUP', name: 'Duplicate', message: 'Already exists' });
  receiveGroupList(context, { Current: [], Other: [] });
  context.handleMessage({ type: 'GROUP_MEMBERS_SET', operation: 'UPDATE_GROUP_MEMBERS', name: 'Current', members: [] });
  assert.equal(context.manageSelectedGroup, 'Other');
});

test('a selected renamed group stays selected until the updated group list arrives', () => {
  const context = makeGroupSnapshotContext();
  membership.toggleMember('Studio', context.groups, context.memberEdits, 'offline');
  membership.toggleMember('Studio', context.groups, context.memberEdits, 'online');

  context.handleMessage({ type: 'GROUP_RENAMED', name: 'Studio', new_name: 'Gallery' });
  assert.equal(context.manageSelectedGroup, 'Studio');
  assert.equal(context.activeGroup, 'Gallery');
  context.selectManageGroup('Studio');

  receiveGroupList(context, { Gallery: ['online'], Other: ['offline'] });

  assert.equal(context.manageSelectedGroup, 'Gallery');
  assert.deepEqual(membership.buildMembershipEdit('Gallery', context.groups, context.memberEdits),
    { type: 'UPDATE_GROUP_MEMBERS', name: 'Gallery', add: { offline: 'Other' }, remove: ['online'] });
  assert.equal(context.membershipNotice.textContent, '');
});

test('an HTTP snapshot before the rename acknowledgement preserves the current draft', () => {
  const context = makeGroupSnapshotContext({
    groups: { Studio: ['online'], Other: ['offline'] },
    manageSelectedGroup: 'Studio',
    memberEdits: new Map([['draft-device', { kind: 'add', expectedOwner: null }]]),
  });

  receiveGroupList(context, { Gallery: ['online'], Other: ['offline'] }, 'http');
  context.handleMessage({ type: 'GROUP_RENAMED', name: 'Studio', new_name: 'Gallery' });
  receiveGroupList(context, { Gallery: ['online'], Other: ['offline'] });

  assert.equal(context.manageSelectedGroup, 'Gallery');
  assert.deepEqual([...selectedMembers(context)], ['online', 'draft-device']);
  assert.equal(context.membershipNotice.textContent, '');
});

test('an external disappearance reports only discarded edits and never transfers them to another group', () => {
  for (const dirty of [false, true]) {
    const context = makeGroupSnapshotContext();
    if (dirty) membership.toggleMember('Studio', context.groups, context.memberEdits, 'offline');
    receiveGroupList(context, { Gallery: ['online'], Other: ['offline'] }, 'http');
    assert.equal(context.membershipNotice.textContent, '');
    receiveGroupList(context, { Gallery: ['online'], Other: ['offline'] });
    assert.equal(context.manageSelectedGroup, null);
    assert.equal(context.memberEdits.size, 0);
    if (dirty) assert.match(context.membershipNotice.textContent, /Studio.*unsaved membership changes were discarded/i);
    else assert.equal(context.membershipNotice.textContent, '');
    context.selectManageGroup('Gallery');
    assert.deepEqual([...selectedMembers(context)], ['online']);
    assert.equal(context.membershipNotice.textContent, '');
  }
});

test('an HTTP snapshot cannot satisfy the websocket acknowledgement barrier', () => {
  const context = makeGroupSnapshotContext({ groups: { Studio: ['online'] }, manageSelectedGroup: 'Studio' });
  context.sendCreateGroup('New group');
  context.handleMessage({ type: 'GROUP_CREATED', name: 'New group' });

  receiveGroupList(context, { Studio: ['online'], 'New group': [] }, 'http');
  assert.equal(context.manageSelectedGroup, 'Studio');

  receiveGroupList(context, { Studio: ['online'], 'New group': [] });
  assert.equal(context.manageSelectedGroup, 'New group');
});

test('a manual group choice made while a rename snapshot is pending wins', () => {
  const context = makeGroupSnapshotContext();
  membership.toggleMember('Studio', context.groups, context.memberEdits, 'draft-device');

  context.handleMessage({ type: 'GROUP_RENAMED', name: 'Studio', new_name: 'Gallery' });
  context.selectManageGroup('Other');
  receiveGroupList(context, { Gallery: ['online'], Other: ['offline'] });

  assert.equal(context.manageSelectedGroup, 'Other');
  assert.deepEqual([...selectedMembers(context)], ['offline']);
});

test('a manual selection waiting during save follows its target when that group is renamed', () => {
  const context = makeGroupSnapshotContext({
    groups: { Studio: ['online'], Other: ['offline'] },
    manageSelectedGroup: 'Other',
    membershipSave: { name: 'Other', state: 'saving' },
  });

  context.selectManageGroup('Studio');
  context.handleMessage({ type: 'GROUP_RENAMED', name: 'Studio', new_name: 'Gallery' });

  receiveGroupList(context, { Gallery: ['online'], Other: ['offline'] });
  context.handleMessage({
    type: 'GROUP_MEMBERS_SET',
    operation: 'UPDATE_GROUP_MEMBERS',
    name: 'Other',
    members: ['offline'],
  });
  assert.equal(context.manageSelectedGroup, 'Gallery');
});

test('a rename acknowledgement cannot replace a manual choice already queued during save', () => {
  const context = makeGroupSnapshotContext({
    groups: { Studio: ['online'], Other: ['offline'] },
    manageSelectedGroup: 'Studio',
    membershipSave: { name: 'Studio', state: 'saving' },
  });

  context.selectManageGroup('Other');
  context.handleMessage({ type: 'GROUP_RENAMED', name: 'Studio', new_name: 'Renamed' });
  receiveGroupList(context, { Renamed: ['online'], Other: ['offline'] });

  assert.equal(context.manageSelectedGroup, 'Other');
  assert.deepEqual([...selectedMembers(context)], ['offline']);
});

test('a manual selection of a renamed group survives disconnect before its snapshot', () => {
  const context = makeGroupSnapshotContext({
    groups: { Current: [], Other: [] },
    manageSelectedGroup: 'Current',
    membershipSave: { name: 'Current', state: 'saving' },
    setConnStatus() {},
    scheduleReconnect() {},
  });

  context.selectManageGroup('Other');
  context.handleMessage({ type: 'GROUP_RENAMED', name: 'Other', new_name: 'Gallery' });
  assert.equal(context.pendingSelection.intent, 'manual');
  assert.equal(context.pendingSelection.phase, 'snapshot');

  installSocketCloseHandler(context);
  context.simulateSocketClose({});
  assert.equal(context.pendingSelection.name, 'Gallery');
  assert.equal(context.pendingSelection.intent, 'manual');
  assert.equal(context.manageSelectedGroup, 'Current');

  receiveGroupList(context, { Current: [], Gallery: ['offline'] });
  assert.equal(context.manageSelectedGroup, 'Gallery');
  assert.equal(context.pendingSelection, null);
  assert.deepEqual([...selectedMembers(context)], ['offline']);
});

test('a missing group in the confirming snapshot cancels the selection intent', () => {
  const context = makeGroupSnapshotContext({
    groups: { Studio: ['online'] },
    manageSelectedGroup: 'Studio',
  });
  context.sendCreateGroup('New group');
  context.handleMessage({ type: 'GROUP_CREATED', name: 'New group' });

  receiveGroupList(context, { Studio: ['online'] });

  receiveGroupList(context, { Studio: ['online'], 'New group': [] });
  assert.equal(context.manageSelectedGroup, 'Studio');
});

test('disconnect finishes the in-flight save and preserves a deferred manual choice', () => {
  let context;
  const feedback = [];
  context = makeGroupSnapshotContext({
    groups: { Current: [], Other: [] },
    manageSelectedGroup: 'Current',
    membershipSave: { name: 'Current', state: 'saving' },
    showMembershipFeedback(name, message, state) {
      feedback.push({ name, message, state });
      context.membershipSave = { name, message, state };
    },
    setConnStatus() {},
    scheduleReconnect() {},
  });
  context.sendCreateGroup('New group');
  context.selectManageGroup('Other');
  context.membershipSnapshotReady = true;
  installSocketCloseHandler(context);
  context.simulateSocketClose({});

  assert.equal(context.manageSelectedGroup, 'Other');
  assert.equal(feedback[0].state, 'error');
  assert.equal(context.membershipSave, null);
});

test('a snapshot that confirms a staged membership prunes it so it cannot reappear later', () => {
  const groups = { Studio: [], Other: ['headset'] };
  const context = makeGroupSnapshotContext({ groups, manageSelectedGroup: 'Studio' });
  membership.toggleMember('Studio', context.groups, context.memberEdits, 'headset');
  assert.equal(selectedMembers(context).has('headset'), true);

  receiveGroupList(context, { Studio: ['headset'], Other: [] });
  assert.equal(selectedMembers(context).has('headset'), true);

  receiveGroupList(context, { Studio: [], Other: [] });
  assert.equal(selectedMembers(context).has('headset'), false);
});

test('removing a group during member save clears the stale saving state', () => {
  const context = makeGroupSnapshotContext({
    groups: { Removed: ['ghost'] },
    manageSelectedGroup: 'Removed',
    memberEdits: new Map([['other', { kind: 'add', expectedOwner: null }]]),
    membershipSave: { name: 'Removed', state: 'saving' },
  });

  receiveGroupList(context, {});
  assert.equal(context.manageSelectedGroup, null);
  assert.equal(context.membershipSave, null);
});

test('existing ghost members are rendered as removable rows even with no live devices', () => {
  const context = makeManageContext({
    groups: { Studio: ['forgotten-device'] },
    manageSelectedGroup: 'Studio',
    memberEdits: new Map(),
    devices: [],
    manageSearch: '',
    manageSort: 'label-asc',
    deviceById() { return null; },
    filterSortDevices() { return []; },
    esc(value) { return String(value); },
    deviceIdHtml(_device, id) { return id; },
  });
  vm.runInContext(functionSource('renderMgDevListHtml'), context);

  const html = context.renderMgDevListHtml(context.selectedManageMembers());
  assert.match(html, /class="mg-dev mg-dev-ghost" data-act="toggleMember" data-id="forgotten-device"/);
  assert.match(html, /Unknown · select to remove/);
});

function makeMemberClickFixture(devices, members) {
  const context = makeManageDetailContext();
  let handler;
  Object.assign(context, {
    manageView: { addEventListener(_type, listener) { handler = listener; } },
    manageSearch: '',
    membershipSave: null,
    groups: { Studio: members },
    devices,
    getDeviceId(device) { return device.id; },
    deviceById(id) { return devices.find(device => device.id === id); },
    filterSortDevices() { return devices; },
    deviceIdHtml(_device, id) { return id; },
  });
  vm.runInContext(functionSource('renderMgDevListHtml'), context);
  const start = source.indexOf("manageView.addEventListener('click', function (e) {");
  const end = source.indexOf('\n      });', start);
  vm.runInContext(source.slice(start, end + '\n      });'.length), context);
  context.renderManageDetail();
  return { context, list: context.mgDetail.list, click: target => handler({ target: { closest: () => target } }) };
}

test('Cancel restores the checkbox after an in-place membership edit', () => {
  const { context, list, click } = makeMemberClickFixture([{ id: 'online', status: 'online' }], []);
  const initial = list.innerHTML;
  const box = {
    classList: { toggle(name, checked) { assert.equal(name, 'on'); assert.equal(checked, true); } },
    set textContent(mark) {
      list.innerHTML = list.innerHTML.replace('class="selbox "></div>', `class="selbox on">${mark}</div>`);
    },
  };
  const row = { dataset: { act: 'toggleMember', id: 'online' }, querySelector(selector) { return selector === '.selbox' ? box : null; } };
  click(row);
  assert.match(list.innerHTML, /class="selbox on">✓/);

  click({ dataset: { act: 'cancelMembers' } });

  assert.equal(context.memberEdits.size, 0);
  assert.equal(list.innerHTML, initial);
});

test('Cancel restores the last unknown member after removing it from the staged membership', () => {
  const { context, list, click } = makeMemberClickFixture([], ['forgotten-device']);
  const initial = list.innerHTML;
  const row = { dataset: { act: 'toggleMember', id: 'forgotten-device' }, remove() { list.innerHTML = ''; } };
  click(row);

  assert.equal(context.selectedManageMembers().has('forgotten-device'), false);
  assert.deepEqual(membership.buildMembershipEdit('Studio', context.groups, context.memberEdits).remove,
    ['forgotten-device']);
  const removedMarkup = list.innerHTML;

  click({ dataset: { act: 'cancelMembers' } });

  assert.equal(list.innerHTML, initial);
  assert.match(removedMarkup, /No devices yet/);
});

test('group device status follows the Devices sort for committed members', () => {
  const members = ['missing-2', 'room-10', 'unit-10', 'room-02', 'unit-2', 'room-2', 'missing-1'];
  const context = vm.createContext({
    devices: [
      { device_id: 'room-2', label: 'Room 2', status: 'offline' },
      { device_id: 'room-02', label: 'room 02', status: 'online' },
      { device_id: 'unit-10', label: '' },
      { device_id: 'unassigned', label: 'Room 1' },
      { device_id: 'room-10', label: 'Room 10', status: 'online' },
      { device_id: 'unit-2', label: '' },
    ],
    manageSort: 'label-asc',
    getDeviceId(device) { return device.device_id; },
  });
  for (const name of ['sortDevices', 'sortGroupMembers']) {
    vm.runInContext(functionSource(name), context);
  }

  const displayedIds = () => [...context.sortGroupMembers(members.values())];
  assert.deepEqual(displayedIds(), ['unit-2', 'unit-10', 'room-2', 'room-02', 'room-10', 'missing-2', 'missing-1']);
  context.manageSort = 'label-desc';
  assert.deepEqual(displayedIds(), ['unit-2', 'unit-10', 'room-10', 'room-2', 'room-02', 'missing-2', 'missing-1']);
});

test('expanded group rows sort by name and show the online access point', () => {
  const current_wifi = { ssid: 'Office <5G>', bssid: 'aa:bb:cc:dd:ee:ff' };
  const devices = [
    { device_id: 'b', label: 'Room 10', status: 'offline', current_wifi },
    { device_id: 'a', label: 'Room 2', status: 'online', current_wifi },
  ];
  const context = vm.createContext({
    devices, manageSort: 'label-desc', getDeviceId: d => d.device_id,
    deviceById: id => devices.find(d => d.device_id === id), isOnline: d => d.status === 'online',
    esc: String, progressCellHtml: () => '', window: { MdmConfigurationPackages: require('../styly_mdm/static/configuration-packages-v1.js') },
  });
  for (const name of ['sortDevices', 'sortGroupMembers', 'renderGroupDevsHtml']) {
    vm.runInContext(functionSource(name), context);
  }
  const html = context.renderGroupDevsHtml(['b', 'a']);
  assert.ok(html.indexOf('Room 2') < html.indexOf('Room 10'));
  assert.match(html, /title="BSSID: aa:bb:cc:dd:ee:ff">Wi-Fi: Office &lt;5G&gt;/);
  assert.equal(html.match(/current-wifi/g).length, 1);
});

test('group member filtering updates only the scrollable list', () => {
  const createElement = () => ({
    scrollTop: 42,
    writes: 0,
    _html: 'old',
    get innerHTML() { return this._html.replace('&quot;', '"'); },
    set innerHTML(value) { this.writes++; this._html = value; this.scrollTop = 0; },
  });
  let element = createElement();
  const context = vm.createContext({
    document: { getElementById() { return element; } },
    renderMgDevListHtml() { return 'new &quot;label&quot;'; },
    renderedMemberList: null,
    renderedMemberListContent: null,
  });
  vm.runInContext(functionSource('refreshMgDevList'), context);

  context.refreshMgDevList();
  context.refreshMgDevList();
  assert.equal(element.scrollTop, 42);
  assert.equal(element.writes, 1, 'browser serialization does not trigger another write');

  element = createElement();
  context.refreshMgDevList();
  assert.equal(element.writes, 1, 'a replacement list element receives its initial markup');
});

test('same-group refresh keeps the search DOM and selection while a group switch starts at the top', () => {
  const context = makeManageDetailContext();
  context.renderManageDetail();
  const originalSearch = context.mgDetail.search;
  const originalList = context.mgDetail.list;
  originalSearch.selectionStart = 2;
  originalSearch.selectionEnd = 5;
  context.document.activeElement = originalSearch;
  originalList.scrollTop = 47;

  context.renderMgDevListHtml = () => '<div>updated members</div>';
  context.renderManageDetail();
  assert.equal(context.mgDetail.search, originalSearch,
    'a device refresh must not replace the input node or interrupt its IME composition');
  assert.equal(context.mgDetail.search.selectionStart, 2);
  assert.equal(context.mgDetail.search.selectionEnd, 5);
  assert.equal(context.mgDetail.search.isComposing, true);
  assert.equal(context.mgDetail.list.scrollTop, 47, 'the current group keeps its list position');

  context.manageSelectedGroup = 'Gallery';
  context.groups.Gallery = ['offline'];
  context.document.activeElement = null;
  context.renderManageDetail();
  assert.equal(context.mgDetail.list.scrollTop, 0, 'a different group starts at the top');
});

test('delete is disabled and guarded while the current group membership is saving', () => {
  const controls = {
    '[data-members-feedback]': { textContent: '', classList: { toggle() {} } },
    '[data-act="saveMembers"]': { disabled: false },
    '[data-act="cancelMembers"]': { disabled: false },
    '[data-act="deleteGroup"]': { disabled: false },
  };
  const feedbackContext = vm.createContext({
    mgDetail: { querySelector(selector) { return controls[selector]; } },
    manageSelectedGroup: 'Studio',
    membershipSave: { name: 'Studio', state: 'saving', message: 'Saving members…' },
  });
  vm.runInContext(functionSource('renderMembershipFeedback'), feedbackContext);
  feedbackContext.renderMembershipFeedback();
  assert.equal(controls['[data-act="deleteGroup"]'].disabled, true);

  let handler;
  const deleted = [];
  const clickContext = vm.createContext({
    manageView: { addEventListener(_type, listener) { handler = listener; } },
    membershipSave: { name: 'Studio', state: 'saving' },
    confirm() { return true; },
    sendDeleteGroup(name) { deleted.push(name); },
  });
  const start = source.indexOf("manageView.addEventListener('click', function (e) {");
  const end = source.indexOf('\n      });', start);
  assert.ok(start !== -1 && end !== -1, 'actual delegated manage click handler exists');
  vm.runInContext(source.slice(start, end + '\n      });'.length), clickContext);
  const target = { dataset: { act: 'deleteGroup', group: 'Studio' } };
  handler({ target: { closest() { return target; } } });
  assert.deepEqual(deleted, []);

  clickContext.membershipSave.state = 'saved';
  handler({ target: { closest() { return target; } } });
  assert.deepEqual(deleted, ['Studio']);
});
