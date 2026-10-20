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
  let renderCalls = 0;
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
    render() { renderCalls += 1; },
    addLog() {},
    knownIds() { return new Set(devicesForHarness.map((d) => d.device_id)); },
    getDeviceId(d) { return d.device_id; },
    isOnline(d) { return d.status === 'online'; },
  });
  const devicesForHarness = context.devices;
  // Keep this accessor live when DEVICE_LIST replaces the module's device array.
  Object.defineProperty(context, 'knownIds', { value: () => new Set(context.devices.map((d) => d.device_id)) });
  Object.defineProperty(context, 'renderCalls', { get: () => renderCalls });
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
  assert.equal(forward.deviceSelectionAnchorId, 'b');

  const reverse = makeHarness({ order: ['a', 'b', 'c', 'd', 'e'], selected: ['a'] });
  reverse.toggleDevice('d', false);
  reverse.toggleDevice('b', true);
  assert.deepEqual(selected(reverse), ['a', 'b', 'c', 'd']);
});

test('repeated Shift uses the original anchor and clicked next state selects or clears a range', () => {
  const context = makeHarness({ order: ['a', 'b', 'c', 'd', 'e'] });
  context.toggleDevice('b', false);
  context.toggleDevice('d', true);
  context.toggleDevice('c', true); // c was selected, so clear b..c; d remains selected.
  assert.deepEqual(selected(context), ['d']);
  context.toggleDevice('d', true); // d was selected, so clear b..d.
  assert.deepEqual(selected(context), []);
  assert.equal(context.deviceSelectionAnchorId, 'b');
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

  // A refreshed label changes actual sort order; the stable anchor ID is looked up in it.
  context.devices.find((d) => d.device_id === 'id-1').label = 'Room 20';
  context.deviceSort = 'label-asc';
  assert.deepEqual(context.visibleDevices().map((d) => d.device_id), ['id-2', 'id-10', 'id-1']);
  context.toggleDevice('id-2', true); // selected -> deselect along the current order to id-10
  assert.deepEqual(selected(context), ['id-1']);
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
  context.deviceSelectionAnchorId = 'a';
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
  assert.equal(event('toggleDevice', true).prevented, 0);

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
