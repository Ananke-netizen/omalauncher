## What and why

## How it was tested

- [ ] `scripts/dev.sh test`
- [ ] on the emulator (`scripts/dev.sh run`): screenshots below for UI changes
- [ ] on an arm64 phone: needed for anything under proot (Linux, terminals, agents, windows)

## Existing installs

Tick what applies; see AGENTS.md › Invariants.

- [ ] changes a rootfs step: it has a new id
- [ ] changes a saved format: `Versioned` bumped, with a migration
- [ ] adds to the widget language: `Compiler.LEVEL` bumped, README documented
- [ ] changes launcher-provided files: Seeded files a user edited stay theirs
