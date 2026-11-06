# Waypy

Waypy is a client-side waypoint mod for Fabric on Minecraft 26.2. Mark places in your world and see them through beacon beams or block highlights with a floating logo, name and distance.

## Requirements

- Minecraft 26.2
- [Fabric Loader](https://fabricmc.net/use/) 0.20.1 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)

## Install

Put the jar in your `.minecraft/mods` folder.

- A prebuilt jar is committed at [`dist/waypy-0.5.0.jar`](dist/waypy-0.5.0.jar).
- CI also builds the jar on every push. Open the Actions tab, pick a run and download the `waypy-jar` artifact.

## Controls

| Key | Action |
|---|---|
| `N` on empty ground | Make a new waypoint on the block you stand on. Hold `N` and walk to make a clump: every block you walk over joins it (up to 256 blocks; blocks of other waypoints are skipped) |
| `N` on a waypoint | Tap to remove that one block from it (or the whole waypoint if it is a single block). Hold `N` and walk to extend it instead: the blocks you walk over join it and nothing is removed |
| `N` again within 15 s | Undo: press `N` on the removed block (or on any block of the removed waypoint) to bring it back exactly as it was. Keep holding and walk to extend it in the same press |
| `Left Shift` + `N` | The same on the block you look at, up to 512 blocks away. Hold it and sweep the crosshair to paint blocks |
| `M` | Open the Waypy menu |

A clump is one waypoint made of several blocks. It is always drawn as Block Selection (one merged shape), with the logo floating above its centre. Tapping `N` on one of its blocks removes just that block; if it was the first block, the next one takes over the position. Parts that end up apart stay one waypoint. A clump that is down to one block can be a Beacon again.

Creating and extending are silent. Removing shows `Block removed from "<name>" (<n> blocks left) – press N to undo` or `Waypoint "<name>" removed – press N to undo`, and you have 15 seconds to undo. Nothing comes back if another waypoint has taken the block in the meantime, and leaving the world makes removals final.

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

**Delete** removes the selected waypoint right away; an **Undo** button appears next to it for 15 seconds. Undo brings back the last thing you removed, a whole waypoint or a single block taken off with `N`.

Clumps show their block count in the list and in the editor.

Changes apply live and are saved when you close the menu.

## Settings

Open them with the **Settings...** button at the top right of the menu. They apply to all worlds.

| Setting | Default | What it does |
|---|---|---|
| Waypoint Render Distance | 512 blocks | Beacon beams and block highlights farther away are not drawn; the logo and label stay. 16–4096 blocks or Unlimited. |
| Icon Size | 100% | Size of the waypoint pins and their name tags, 50–200%. |
| Shrink with Distance | ON | Pins shrink with distance like real objects (perspective): full size up to 16 blocks, half size at 32, quarter at 64, never smaller than a fixed 35% so far waypoints stay visible. Name tags never get smaller than 50%. |
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
