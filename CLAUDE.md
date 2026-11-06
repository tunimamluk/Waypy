# Waypy

Client-side Fabric waypoint mod for Minecraft 26.2 (Java 25). See `docs/DESIGN.md` for the spec.

## Working model: architect + subagents

The main Claude session acts as **architect and orchestrator**: it plans, breaks work down, writes briefs, reviews results for consistency, and handles git. All real work (coding or design) is delegated to subagents.

### Subagent model selection

| Task type | Model | Effort |
|---|---|---|
| Default (most coding/design work) | Opus 5.5 | medium |
| Complex (architecture, tricky systems, cross-cutting changes) | Opus 5.5 | high |
| Small, well-defined (single-file fixes, boilerplate, tests, docs) | Sonnet 5.5 | high |

## Build

- JDK 25 is required (`/usr/lib/jvm/java-25-openjdk-amd64`).
- Build: `./gradlew build`. The jar lands in `build/libs/`.
- Minecraft 26.x is unobfuscated, so code uses Mojang names directly. Check APIs against the actual game and Fabric API sources (`./gradlew genSources`), not against memory of older versions.
