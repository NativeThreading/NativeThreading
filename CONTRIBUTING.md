# Contributing to NativeThreading

## Commit Convention

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

perf(explosion): improve flat ray capture
fix(explosion): vanilla-exact RNG sequence
chore(build): update Gradle
```

**Scopes:** `core`, `explosion`, `build`, `docs`

## Code Style

- Java 25, 4-space indentation.
- **No dependency on NotEnoughPalette** — modules must build standalone.
- Prefer `ParallelWorker.mapEach`/`mapBatched` for parallel dispatch.
- Keep mixin injection points minimal; avoid `@Overwrite`.

## Module Guidelines

- New parallelization → new subproject with `build.included.gradle.kts`.
- Register mixins in a module-level `*.mixins.json`.
- Register fabric entrypoint in `fabric/src/main/resources/fabric.mod.json` under `"provides"`.

## License

By contributing you agree your code is licensed under MIT.
