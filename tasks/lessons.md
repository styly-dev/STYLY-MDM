# Lessons

- When the user revises a numeric requirement, treat the latest value as authoritative
  and update the plan, implementation, tests, and documentation from that value before
  editing code.
- After moving large bundle work off the event loop, verify the complete browser-to-device
  flow: upload completion, the browser's first admin WebSocket command, device dispatch,
  and both WebSocket connections remaining open.
