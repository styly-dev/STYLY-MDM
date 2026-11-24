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

  function deploymentRevision(deployments, name) {
    return name !== null && Object.hasOwn(deployments, name) ? deployments[name].revision : null;
  }

  function toggleMember(name, groups, edits, deviceId, deployments) {
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
    const pending = edits.get(deviceId);
    if (pending && deployments) {
      pending.targetRevision = deploymentRevision(deployments, name);
      if (pending.kind === 'add') pending.sourceRevision = deploymentRevision(deployments, pending.expectedOwner);
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

  function buildMembershipEdit(name, groups, edits, deployments) {
    const currentMembers = groups[name] || [];
    const current = new Set(currentMembers);
    if (current.size !== currentMembers.length) {
      throw new Error('Resolve duplicate device IDs in this group before editing it.');
    }

    const add = new Map();
    const remove = [];

    for (const [deviceId, edit] of edits) {
      if (deployments && Object.hasOwn(edit, 'targetRevision') &&
          (edit.targetRevision !== deploymentRevision(deployments, name) ||
           (edit.kind === 'add' && edit.sourceRevision !== deploymentRevision(deployments, edit.expectedOwner)))) {
        throw new Error('A package changed while you were editing members. Cancel the edits and review the current package.');
      }
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

  function reviewMembershipDeployment(edit, snapshot) {
    const groups = new Set([edit.name, ...Object.values(edit.add).filter(name => name !== null)]);
    const deployments = snapshot.deployments || {};
    const expected = Object.fromEntries([...groups].map(name => [
      name, deploymentRevision(deployments, name),
    ]));
    const target = expected[edit.name];
    const changed = Object.values(edit.add).filter(owner => (owner === null ? null : expected[owner]) !== target).length;
    const details = [];
    if (changed && target) {
      const id = deployments[edit.name].package_id;
      const pkg = snapshot.packages?.find(item => item.package_id === id);
      details.push('Apply "' + (pkg?.name || id) + '" (' + id + ') to ' + changed + ' device(s).');
    }
    const released = (target ? edit.remove.length : changed);
    if (released) details.push('Release managed settings on ' + released + ' device(s).');
    return { expected, message: details.join('\n') };
  }

  const api = {
    membershipGroups,
    selectedMembers,
    toggleMember,
    reconcileMembers,
    buildMembershipEdit,
    reviewMembershipDeployment,
  };
  root.MdmGroupMembership = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
