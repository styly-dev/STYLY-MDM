## Workflow Orchestration

### 1. Plan Node Default
- Enter plan mode for ANY non-trivial task (3+ steps or architectural decisions)
- If something goes sideways, STOP and re-plan immediately - don't keep pushing
- Use plan mode for verification steps, not just building
- Write detailed specs upfront to reduce ambiguity

### 2. Subagent Strategy
- Use subagents liberally to keep main context window clean
- Offload research, exploration, and parallel analysis to subagents
- For complex problems, throw more compute at it via subagents
- One tack per subagent for focused execution

### 3. Self-Improvement Loop
- After ANY correction from the user: update `tasks/lessons.md` with the pattern
- Write rules for yourself that prevent the same mistake
- Ruthlessly iterate on these lessons until mistake rate drops
- Review lessons at session start for relevant project

### 4. Verification Before Done
- Never mark a task complete without proving it works
- Diff behavior between main and your changes when relevant
- Ask yourself: "Would a staff engineer approve this?"
- Run tests, check logs, demonstrate correctness

### 5. Demand Elegance (Balanced)
- For non-trivial changes: pause and ask "is there a more elegant way?"
- If a fix feels hacky: "Knowing everything I know now, implement the elegant solution"
- Skip this for simple, obvious fixes - don't over-engineer
- Challenge your own work before presenting it

### 6. Autonomous Bug Fixing
- When given a bug report: just fix it. Don't ask for hand-holding
- Point at logs, errors, failing tests - then resolve them
- Zero context switching required from the user
- Go fix failing CI tests without being told how

## Task Management

1. **Plan First**: Write plan to `tasks/todo.md` with checkable items
2. **Verify Plan**: Check in before starting implementation
3. **Track Progress**: Mark items complete as you go
4. **Explain Changes**: High-level summary at each step
5. **Document Results**: Add review section to `tasks/todo.md`
6. **Capture Lessons**: Update `tasks/lessons.md` after corrections

## Core Principles

- **Simplicity First**: Make every change as simple as possible. Impact minimal code.
- **No Laziness**: Find root causes. No temporary fixes. Senior developer standards.
- **Minimal Impact**: Changes should only touch what's necessary. Avoid introducing bugs.
- **English Only**: All code comments, log messages, and user-facing strings must be written in English.

## External Documentation (Context7)

When implementing, modifying, reviewing, or debugging anything related to PICO
enterprise devices — device management, enterprise deployment, the PICO
Enterprise SDK (TobService), silent app install/uninstall, device owner
settings, permissions, or Android manifest settings for PICO devices — use
Context7 before writing code.

Primary reference:

- **PICO Business documentation**
- Context7 URL: https://context7.com/websites/business_picoxr_us_doc
- Preferred Context7 library ID: `/websites/business_picoxr_us_doc`

Query the exact library ID `/websites/business_picoxr_us_doc` directly instead
of doing a generic library search.

Do not rely only on memory for PICO Enterprise SDK APIs, permissions, Android
manifest settings, enterprise deployment behavior, device management behavior,
or version-specific behavior. Check the current Context7 documentation first.

Claude Code picks up the Context7 MCP server automatically from the
project-scoped `.mcp.json`. For Codex, register it once with
`codex mcp add context7 --url https://mcp.context7.com/mcp` (Codex does not
read a project-local `.codex/config.toml`).
