(function (root) {
  'use strict';

  function membershipGroups(deviceId, groups) {
    return Object.keys(groups || {}).filter(function (name) {
      return (groups[name] || []).includes(deviceId);
    }).sort();
  }

  function selectedMembers(currentMembers, edits) {
    const selected = new Set(currentMembers);
    for (const [deviceId, edit] of edits) {
      if (edit.kind === 'add') selected.add(deviceId);
      else if (edit.kind === 'remove') selected.delete(deviceId);
    }
    return selected;
  }

  function toggleMember(name, groups, edits, deviceId) {
    const committed = (groups[name] || []).includes(deviceId);
    const edit = edits.get(deviceId);
    const currentMember = edit ? edit.kind === 'add' : committed;
    const nowMember = !currentMember;

    if (nowMember === committed) {
      edits.delete(deviceId);
    } else if (nowMember) {
      const owners = membershipGroups(deviceId, groups);
      if (owners.length > 1) {
        throw new Error('Resolve this device’s conflicting group memberships before editing it.');
      }
      edits.set(deviceId, { kind: 'add', expectedOwner: owners[0] ?? null });
    } else {
      edits.set(deviceId, { kind: 'remove' });
    }
    return nowMember;
  }

  function reconcileMembers(currentMembers, edits) {
    if (!edits.size) return;
    const current = new Set(currentMembers);
    for (const [deviceId, edit] of edits) {
      if ((edit.kind === 'add' && current.has(deviceId)) ||
          (edit.kind === 'remove' && !current.has(deviceId))) {
        edits.delete(deviceId);
      }
    }
  }

  function buildMembershipEdit(name, groups, edits) {
    const currentMembers = groups[name] || [];
    const current = new Set(currentMembers);
    if (current.size !== currentMembers.length) {
      throw new Error('Resolve duplicate device IDs in this group before editing it.');
    }

    const add = new Map();
    const remove = [];

    for (const [deviceId, edit] of edits) {
      if (edit.kind === 'add') {
        if (current.has(deviceId)) continue;

        const owners = membershipGroups(deviceId, groups);
        if (owners.length > 1 || (owners[0] ?? null) !== edit.expectedOwner) {
          throw new Error('Group membership changed. Remove and add the device again after reviewing its current group.');
        }
        add.set(deviceId, edit.expectedOwner);
      } else if (edit.kind === 'remove') {
        if (!current.has(deviceId)) continue;

        const owners = membershipGroups(deviceId, groups);
        if (owners.length !== 1 || owners[0] !== name) {
          throw new Error('Group membership changed. Review the current members before saving again.');
        }
        remove.push(deviceId);
      } else {
        throw new Error('Review the current group members before saving again.');
      }
    }

    return { type: 'UPDATE_GROUP_MEMBERS', name, add: Object.fromEntries(add), remove };
  }

  const api = {
    membershipGroups,
    selectedMembers,
    toggleMember,
    reconcileMembers,
    buildMembershipEdit,
  };
  root.MdmGroupMembership = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
