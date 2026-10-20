const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '..', 'styly_mdm', 'static', 'index.html'), 'utf8');

function functionSource(name) {
  const start = source.indexOf('function ' + name + '(');
  assert.notEqual(start, -1, 'function ' + name + ' exists');
  const end = source.indexOf('\n      }', start);
  assert.notEqual(end, -1, 'function ' + name + ' has a closing boundary');
  return source.slice(start, end + '\n      }'.length);
}

const listHelpers = source.slice(
  source.indexOf('// Shared filter/sort for both the main Devices list'),
  source.indexOf('function groupNames()', source.indexOf('// Shared filter/sort for both the main Devices list')),
);
assert.ok(listHelpers.length > 0, 'actual device list helpers exist');

function makeHarness({ order = [], selected = [], devices = null } = {}) {
  const context = vm.createContext({
    devices: devices || order.map((id) => ({ device_id: id, label: id, status: 'online' })),
    deviceSearch: '',
    deviceSort: 'label-asc',
    deviceStatusFilter: 'any',
    selectedIds: new Set(selected),
    selectedConnectionIds: new Set(['connection-1']),
    deviceSelectionAnchorId: null,
    activeGroup: null,
    groups: {},
    targetTab: 'devices',
    powerConfirm: { checked: true },
    deviceTaskState: {},
    render() {},
    refreshDeviceSelection() {},
    renderActions() {},
    addLog() {},
    getDeviceId(d) { return d.device_id; },
    isOnline(d) { return d.status === 'online'; },
  });
  // Keep this accessor live when DEVICE_LIST replaces the module's device array.
  context.deviceById = (id) => context.devices.find((d) => d.device_id === id);
  context.knownIds = () => new Set(context.devices.map((d) => d.device_id));
  vm.runInContext(listHelpers, context);
  for (const name of ['toggleDevice', 'toggleSelectAll', 'clearSelection', 'selectGroupAsTarget',
    'syncActiveGroupSelection', 'switchTargetTab']) {
    vm.runInContext(functionSource(name), context);
  }
  return context;
}

function selected(context) { return [...context.selectedIds].sort(); }

test('forward and reverse ranges include both endpoints and preserve outside selections', () => {
  const forward = makeHarness({ order: ['a', 'b', 'c', 'd', 'e'], selected: ['e'] });
  forward.toggleDevice('b', false);
  forward.toggleDevice('d', true);
  assert.deepEqual(selected(forward), ['b', 'c', 'd', 'e']);
  assert.equal(forward.deviceSelectionAnchorId, 'd');

  const reverse = makeHarness({ order: ['a', 'b', 'c', 'd', 'e'], selected: ['a'] });
  reverse.toggleDevice('d', false);
  reverse.toggleDevice('b', true);
  assert.deepEqual(selected(reverse), ['a', 'b', 'c', 'd']);
});

test('repeated Shift advances the anchor and clicked next state selects or clears a range', () => {
  const context = makeHarness({ order: ['a', 'b', 'c', 'd', 'e'] });
  context.toggleDevice('b', false);
  context.toggleDevice('d', true);
  context.toggleDevice('c', true); // c was selected, so clear c..d; b remains selected.
  assert.deepEqual(selected(context), ['b']);
  assert.equal(context.deviceSelectionAnchorId, 'c');
  context.toggleDevice('e', true); // e was unselected, so select c..e from the new anchor.
  assert.deepEqual(selected(context), ['b', 'c', 'd', 'e']);
  assert.equal(context.deviceSelectionAnchorId, 'e');
});

test('real filters and sorting define range order; kept selected rows participate, hidden rows do not', () => {
  const context = makeHarness({ devices: [
    { device_id: 'id-1', label: 'Room 1', status: 'online' },
    { device_id: 'id-2', label: 'Room 2', status: 'offline' },
    { device_id: 'id-10', label: 'Room 10', status: 'online' },
    { device_id: 'other', label: 'Other', status: 'online' },
  ], selected: ['id-2'] });

  context.deviceSearch = 'room';
  context.deviceSort = 'label-desc';
  context.deviceStatusFilter = 'online';
  assert.deepEqual(context.visibleDevices().map((d) => d.device_id), ['id-10', 'id-2', 'id-1']);
  context.toggleDevice('id-10', false);
  context.toggleDevice('id-1', true);
  assert.deepEqual(selected(context), ['id-1', 'id-10', 'id-2']);
  assert.equal(context.selectedIds.has('other'), false, 'nonmatching unselected device stays unselected');

  // A refreshed label changes actual sort order; the latest clicked anchor ID is looked up in it.
  context.devices.find((d) => d.device_id === 'id-1').label = 'Room 20';
  context.deviceSort = 'label-asc';
  assert.deepEqual(context.visibleDevices().map((d) => d.device_id), ['id-2', 'id-10', 'id-1']);
  context.toggleDevice('id-2', true); // selected -> deselect along the current order to id-1
  assert.deepEqual(selected(context), []);
});

test('hidden anchor falls back to a single toggle and stale clicked IDs are ignored', () => {
  const context = makeHarness({ order: ['a', 'b', 'c'] });
  context.toggleDevice('a', false);
  context.toggleDevice('a', false); // Leave a as the anchor while deselected.
  context.deviceSearch = 'b';
  context.toggleDevice('b', true);
  assert.deepEqual(selected(context), ['b']);
  assert.equal(context.deviceSelectionAnchorId, 'b');
  context.toggleDevice('missing', true);
  assert.deepEqual(selected(context), ['b']);
  assert.equal(context.deviceSelectionAnchorId, 'b');
});

test('DEVICE_LIST refresh keeps an existing anchor ID and prunes one whose device disappeared', () => {
  const block = source.match(/case 'DEVICE_LIST': \{([\s\S]*?)\n          \}\n          case 'PROVISIONAL_CONNECTION_LIST'/);
  assert.ok(block, 'actual DEVICE_LIST handler exists');
  const body = block[1].replace(/\n\s*break;\s*$/, '');
  const context = makeHarness({ order: ['a', 'b'] });
  context.deviceSelectionAnchorId = 'b';
  context.devices = [{ device_id: 'a', label: 'A', status: 'online' }, { device_id: 'b', label: 'B', status: 'online' }];
  context.render = () => {};
  vm.runInContext('function applyDeviceList(msg) {' + body + '\n}', context);
  context.applyDeviceList({ devices: [
    { device_id: 'b', label: 'B renamed', status: 'online' },
    { device_id: 'a', label: 'A', status: 'online' },
  ] });
  assert.equal(context.deviceSelectionAnchorId, 'b');
  context.applyDeviceList({ devices: [{ device_id: 'a', label: 'A', status: 'online' }] });
  assert.equal(context.deviceSelectionAnchorId, null);
});

test('select-all, clear, group selection/sync, and tab transitions reset the anchor', () => {
  const context = makeHarness({ order: ['a', 'b'] });
  context.deviceSelectionAnchorId = 'a';
  context.toggleSelectAll();
  assert.equal(context.deviceSelectionAnchorId, null);
  assert.deepEqual(selected(context), ['a', 'b']);
  context.deviceSelectionAnchorId = 'b';
  context.toggleSelectAll();
  assert.deepEqual(selected(context), []);

  context.deviceSelectionAnchorId = 'a';
  context.clearSelection();
  assert.equal(context.deviceSelectionAnchorId, null);
  assert.equal(context.powerConfirm.checked, false);

  context.devices = [{ device_id: 'a' }, { device_id: 'b' }];
  context.groups = { 'Room A': ['a', 'b', 'missing'] };
  context.deviceSelectionAnchorId = 'b';
  context.selectGroupAsTarget('Room A');
  assert.equal(context.deviceSelectionAnchorId, null);
  assert.deepEqual(selected(context), ['a', 'b']);
  context.groups['Room A'] = ['b'];
  context.syncActiveGroupSelection();
  assert.equal(context.deviceSelectionAnchorId, null);
  assert.deepEqual(selected(context), ['b']);

  context.deviceSelectionAnchorId = 'b';
  context.switchTargetTab('groups');
  assert.equal(context.deviceSelectionAnchorId, null);
  context.deviceSelectionAnchorId = 'a';
  context.switchTargetTab('attention');
  assert.equal(context.deviceSelectionAnchorId, null);
  assert.deepEqual(selected(context), []);
});

test('actual delegated handlers toggle once and prevent selection only for Shift device checkboxes', () => {
  const handlers = new Map();
  let toggleCount = 0;
  let lastShift = null;
  const context = vm.createContext({
    targetsBody: { addEventListener(type, listener) { handlers.set(type, listener); } },
    toggleDevice(id, shift) { toggleCount += 1; lastShift = shift; },
    renderTargets() {},
    expandedGroups: new Set(),
    render() {},
  });
  const click = source.match(/targetsBody\.addEventListener\('click', function \(e\) \{[\s\S]*?\n      \}\);/);
  const down = source.match(/targetsBody\.addEventListener\('mousedown', function \(e\) \{[\s\S]*?\n      \}\);/);
  assert.ok(click && down);
  vm.runInContext(click[0] + down[0], context);
  function event(act, shiftKey = false) {
    let prevented = 0;
    const target = { dataset: { act, id: 'device-a' } };
    return {
      target: { closest(selector) { return selector === '[data-act]' || selector === '[data-act="toggleDevice"]' && act === 'toggleDevice' ? target : null; } },
      shiftKey,
      preventDefault() { prevented += 1; },
      stopPropagation() {},
      get prevented() { return prevented; },
    };
  }
  const clickEvent = event('toggleDevice', true);
  handlers.get('click')(clickEvent);
  assert.equal(toggleCount, 1);
  assert.equal(lastShift, true);
  assert.equal(clickEvent.prevented, 0);

  const shiftBox = event('toggleDevice', true);
  handlers.get('mousedown')(shiftBox);
  assert.equal(shiftBox.prevented, 1);
  const plainBox = event('toggleDevice', false);
  handlers.get('mousedown')(plainBox);
  assert.equal(plainBox.prevented, 0);
  const shiftLabel = event('editLabel', true);
  handlers.get('mousedown')(shiftLabel);
  assert.equal(shiftLabel.prevented, 0);
});

test('ordinary selection at 300 devices does not sort or rebuild the list and refreshes actions', () => {
  const context = makeHarness({ order: Array.from({ length: 300 }, (_, i) => 'device-' + i) });
  let controls = 0, actions = 0;
  context.visibleDevices = () => { throw new Error('ordinary selection must not sort'); };
  context.render = () => { throw new Error('selection must not rebuild the list'); };
  context.refreshDeviceSelection = () => { controls++; };
  context.renderActions = () => { actions++; };
  context.activeGroup = 'Old group';
  context.toggleDevice('device-250', false);
  assert.deepEqual(selected(context), ['device-250']);
  assert.equal(context.activeGroup, null);
  assert.equal(context.deviceSelectionAnchorId, 'device-250');
  context.toggleDevice('device-250', false);
  assert.deepEqual(selected(context), []);
  assert.equal(controls, 2);
  assert.equal(actions, 2);
});

test('in-place checkbox refresh preserves rows, removes deselected kept rows and updates the header', () => {
  const header = {};
  const rowsContainer = {};
  function checkbox(id, kept = false) {
    const classes = new Set(kept ? ['kept'] : []);
    const row = { removed: false, classList: { contains: (name) => classes.has(name) }, remove() { this.removed = true; } };
    return { dataset: { id }, textContent: '', row,
      closest: () => row,
      classList: { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); }, contains: (name) => classes.has(name) } };
  }
  const boxes = [checkbox('a'), checkbox('b'), checkbox('kept', true)];
  const context = vm.createContext({ selectedIds: new Set(['a']), targetsBody: {
    querySelectorAll: () => boxes.filter((box) => !box.row.removed),
    querySelector: (selector) => selector === '#devRows' ? rowsContainer : header,
  } });
  vm.runInContext(functionSource('refreshDeviceSelection'), context);
  context.refreshDeviceSelection();
  assert.equal(boxes[0].textContent, '✓');
  assert.equal(boxes[0].classList.contains('on'), true);
  assert.equal(boxes[1].classList.contains('on'), false);
  assert.equal(boxes[2].row.removed, true);
  assert.equal(header.className, 'selbox mixed');
  assert.equal(header.textContent, '–');
  assert.equal(rowsContainer.innerHTML, undefined, 'matching rows are preserved');
  context.selectedIds.add('b');
  context.refreshDeviceSelection();
  assert.equal(header.className, 'selbox on');
  context.selectedIds.clear();
  context.refreshDeviceSelection();
  assert.equal(header.className, 'selbox ');
  assert.equal(header.textContent, '');
  boxes[0].row.removed = boxes[1].row.removed = true;
  context.refreshDeviceSelection();
  assert.match(rowsContainer.innerHTML, /No matching devices/);
});
