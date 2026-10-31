# Documentation

Two audiences, two entry points:

| You are | Read |
|---|---|
| Using the mod (install, commands, performance, build) | [`../README.md`](../README.md) |
| Changing the code | [`dev/README.md`](dev/README.md) — workflow + pre-submit self-check |
| An agent working in this repository | [`../AGENTS.md`](../AGENTS.md) + [`.agents/skills/native-threading-architecture/`](../.agents/skills/native-threading-architecture/) |

## Development documents

| File | Contents |
|---|---|
| [`dev/README.md`](dev/README.md) | Workflow: build, test, CI, commits, adding a module; self-check entry points |
| [`dev/architecture-discipline.md`](dev/architecture-discipline.md) | Hard rules (M/C/T/R/V/F/D/B) and the pre-submit checklist |
| [`dev/code-organization.md`](dev/code-organization.md) | File/module/thread-safety layout and the new-feature landing checklist |
| [`dev/explosion-pipeline.md`](dev/explosion-pipeline.md) | How the current explosion pipeline is built and why |
| [`../benchmarks/README.md`](../benchmarks/README.md) | Measurement kits: TNT, pathfinding, section-copy, AABB scan |
| [`../spikes/aabb-broadphase-zig/README.md`](../spikes/aabb-broadphase-zig/README.md) | Standalone native broad-phase measurement spike |

Module-local agent notes: [`../core/AGENTS.md`](../core/AGENTS.md),
[`../explosion/AGENTS.md`](../explosion/AGENTS.md).

## Archive

`docs/archive/` is local-only (gitignored) and is **not** part of the published
documentation. It holds the lessons that shaped the rules — incidents, measured
rejections, superseded designs and investigation notes — so the public docs can
state only the workflow and the self-check rules:

- `archive/lessons.md` — what went wrong, what was falsified, what it changed
- `archive/explosion-parallelization-v0.md` — the original design write-up (superseded)
- `archive/threading-feasibility.md`, `archive/research/` — investigation notes and their verification rounds

On a fresh clone the archive is absent; the rules and checklists are written to
stand on their own.
