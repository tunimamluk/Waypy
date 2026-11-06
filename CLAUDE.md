# Waypy

Client-side Fabric waypoint mod for Minecraft 26.2 (Java 25). See `docs/DESIGN.md` for the spec.

## Ideas file

`IDEAS.md` at the repo root is the user's backlog of feature ideas. When the user asks to add an idea, append it there (a numbered entry with a short title and their description) and commit it. Ideas are not built until the user asks for one; when one is built, mark it **(done)** in `IDEAS.md` and move the details into `docs/DESIGN.md`.

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

## Visual check (headless screenshots)

- `scripts/devtest-screenshots.sh [--fresh]` runs the client under Xvfb with software GL, plays the scripted scenario in `src/devtest` (dev-only mod `waypy-devtest`, never in the release jar) and writes PNGs to `run-devtest/screenshots/`. About 1 minute per run.
- The scenario lives in `src/devtest/java/dev/waypy/devtest/Scenario.java`; options preset in `src/devtest/options.txt`.
