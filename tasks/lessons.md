# Lessons

- Honor the latest delivery instruction: PR 108 must be self-reviewed and tested on hardware before a local commit. Do not push, even though an earlier request authorized it.

- Scope device behavior to PICO 4 Ultra Enterprise. Remove unused peer-protocol compatibility when the supported server cannot exercise it, but preserve stored execution evidence and avoid duplicate validators for shared invariants.
- Treat connectivity refresh as a read: it must not create a new submission intent or rotate idempotency keys.
- Before removing a compatibility path resolver, trace durable recovery and cleanup callers as well as new command admission. Test historical active recovery using the production path construction.
