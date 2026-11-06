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
| `N` | Create a waypoint on the block you stand on. On a block that already belongs to a waypoint, delete that waypoint instead |
| Hold `N` and walk | Grow a clump: every block you walk over joins the waypoint (up to 256 blocks; blocks of other waypoints are skipped) |
| `Shift` + `N` | Create (or delete) a waypoint on the block you look at, up to 512 blocks away. Hold it and sweep the crosshair to paint a clump |
| `M` | Open the Waypy menu |

A clump is one waypoint made of several blocks: Block Selection draws it as one merged shape, Beacon draws one beam from its middle, and the logo floats above its centre. Pressing `N` on any of its blocks deletes the whole clump.

Keys can be rebound under Controls → Waypy.

## Menu

The menu lists the waypoints of the current world on the left and edits the selected one on the right. Each waypoint has:

- a name and a color (16 dye swatches or a hex `#RRGGBB` value)
- a style: **Beacon** (a beam up to build height) or **Block Selection** (a tinted box with an outline)
- a logo shown above the waypoint: a **Block** icon you pick from a list (by default the block the waypoint is on), or **None** for just the label
- for Block Selection, an opacity slider and a "Render through blocks" option
- a Visible toggle

Clumps show their block count in the list and in the editor.

Changes apply live and are saved when you close the menu.

## Settings

Open them with the **Settings...** button at the top right of the menu. They apply to all worlds.

| Setting | Default | What it does |
|---|---|---|
| Waypoint Render Distance | 512 blocks | Beacon beams and block highlights farther away are not drawn; the logo and label stay. 16–4096 blocks or Unlimited. |
| Icon Size | 100% | Size of logos and labels, 50–200%. |
| Shrink with Distance | ON | Logos and labels shrink from full size at 16 blocks to the minimum size at 256 blocks. Labels never get smaller than 50%. |
| Minimum Size | 50% | Size of far logos when shrinking, 25–100%. |
| Labels | Always | Always, Looking At (only the waypoint nearest the crosshair) or Off. |
| Distance in Label | ON | Show the distance after the name. |

Settings are saved in `<.minecraft>/config/waypy.json`.

## Where waypoints are saved

Waypoints are stored per world in `<.minecraft>/waypy/<world>.json`.

## Building from source

You need JDK 25.

```
./gradlew build
```

The jar is written to `build/libs/`.
