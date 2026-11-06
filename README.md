# Waypy

Waypy is a client-side waypoint mod for Fabric on Minecraft 26.2. Mark places in your world and see them through beacon beams or block highlights with a floating logo, name and distance.

## Requirements

- Minecraft 26.2
- [Fabric Loader](https://fabricmc.net/use/) 0.20.1 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)

## Install

Put the jar in your `.minecraft/mods` folder.

- A prebuilt jar is committed at [`dist/waypy-0.1.0.jar`](dist/waypy-0.1.0.jar).
- CI also builds the jar on every push. Open the Actions tab, pick a run and download the `waypy-jar` artifact.

## Controls

| Key | Action |
|---|---|
| `N` | Create a waypoint on the block you stand on |
| `Shift` + `N` | Create a waypoint on the block you look at, up to 512 blocks away |
| `M` | Open the Waypy menu |

Keys can be rebound under Controls → Waypy.

## Menu

The menu lists the waypoints of the current world on the left and edits the selected one on the right. Each waypoint has:

- a name and a color (16 dye swatches or a hex `#RRGGBB` value)
- a style: **Beacon** (a beam up to build height) or **Block Selection** (a tinted box with an outline)
- a logo shown above the waypoint: a **Block** icon you pick from a list, or **Text** of up to 8 characters
- for Block Selection, an opacity slider and a "Render through blocks" option
- a Visible toggle

Changes apply live and are saved when you close the menu.

## Where waypoints are saved

Waypoints are stored per world in `<.minecraft>/waypy/<world>.json`.

## Building from source

You need JDK 25.

```
./gradlew build
```

The jar is written to `build/libs/`.
