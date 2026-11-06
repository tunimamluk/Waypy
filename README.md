# Waypy

Waypy is a client-side waypoint mod for Fabric on Minecraft 26.2. Mark places in your world and see them through beacon beams or block highlights with a floating logo, name and distance.

## Requirements

- Minecraft 26.2
- [Fabric Loader](https://fabricmc.net/use/) 0.20.1 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)

## Install

Put the jar in your `.minecraft/mods` folder.

- A prebuilt jar is committed at [`dist/waypy-0.3.0.jar`](dist/waypy-0.3.0.jar).
- CI also builds the jar on every push. Open the Actions tab, pick a run and download the `waypy-jar` artifact.

## Controls

| Key | Action |
|---|---|
| `N` | Create a waypoint on the block you stand on. On a block that already belongs to a waypoint, delete that waypoint instead |
| `N` again within 15 s | Undo a delete: press `N` on any block of the waypoint you just deleted to bring it back exactly as it was |
| Hold `N` and walk | Grow a clump: every block you walk over joins the waypoint (up to 256 blocks; blocks of other waypoints are skipped) |
| `Left Shift` + `N` | Create (or delete, or undo) a waypoint on the block you look at, up to 512 blocks away. Hold it and sweep the crosshair to paint a clump |
| `M` | Open the Waypy menu |

A clump is one waypoint made of several blocks. It is always drawn as Block Selection (one merged shape), with the logo floating above its centre. Pressing `N` on any of its blocks deletes the whole clump.

Creating a waypoint is silent. Deleting one shows `Waypoint "<name>" removed – press N to undo` for 15 seconds. The waypoint can't come back if another waypoint has taken one of its blocks in the meantime, and leaving the world makes deletions final.

All three keys can be rebound under Options → Controls → Key Binds → Waypy: "Create/Delete Waypoint" (`N`), "Look-at Modifier" (`Left Shift`; sharing it with Sneak works) and "Open Waypoint Menu" (`M`).

## Menu

The menu lists the waypoints of the current world on the left and edits the selected one on the right. Each waypoint has:

- a name and a color (16 dye swatches or a hex `#RRGGBB` value)
- its position: X, Y and Z boxes under the name. Typing moves the waypoint (a whole clump moves with its first block). Invalid numbers, or a move onto another waypoint's blocks, show red and aren't applied. Y stays within the build height
- a style: **Block Selection** (a tinted box with an outline; the default) or **Beacon** (a beam up to build height; single blocks only, so the button is greyed out for clumps)
- a logo shown above the waypoint: a **Block** icon you pick from a list (by default the block the waypoint is on), or **None**
- a **Text** toggle for the name and distance under the logo (off for new waypoints)
- for Block Selection, an opacity slider and a "Render through blocks" option (off by default)
- a Visible toggle

**Delete** removes the selected waypoint right away; an **Undo** button appears next to it for 15 seconds.

Clumps show their block count in the list and in the editor.

Changes apply live and are saved when you close the menu.

## Settings

Open them with the **Settings...** button at the top right of the menu. They apply to all worlds.

| Setting | Default | What it does |
|---|---|---|
| Waypoint Render Distance | 512 blocks | Beacon beams and block highlights farther away are not drawn; the logo and label stay. 16–4096 blocks or Unlimited. |
| Icon Size | 100% | Size of logos and labels, 50–200%. |
| Shrink with Distance | ON | Logos and labels shrink smoothly from full size at 16 blocks to the minimum size at 256 blocks. Labels never get smaller than 50%. |
| Minimum Size | 50% | Size of far logos when shrinking, 25–100%. |
| Labels | Always | Always, Looking At (only the waypoint nearest the crosshair) or Off. Applies to waypoints whose Text is on. Labels fade in and out. |
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
