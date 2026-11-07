# Waypy

Waypy is a client-side waypoint mod for Fabric on Minecraft 26.2. Mark places in your world and see them through beacon beams or block highlights with a floating logo, name and distance.

## Requirements

- Minecraft 26.2
- [Fabric Loader](https://fabricmc.net/use/) 0.20.1 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)

## Install

Put the jar in your `.minecraft/mods` folder.

- A prebuilt jar is committed at [`dist/waypy-0.8.1.jar`](dist/waypy-0.8.1.jar).
- CI also builds the jar on every push. Open the Actions tab, pick a run and download the `waypy-jar` artifact.

## Controls

| Key | Action |
|---|---|
| `N` on empty ground | Make a new waypoint on the block you stand on. Hold `N` and walk to make a clump: every block you walk over joins it (up to 256 blocks; blocks of other waypoints are skipped) |
| `N` on a waypoint | Tap to remove that one block from it (or the whole waypoint if it is a single block). Hold `N` on a waypoint and walk onto new ground to extend it, or walk over its own blocks to erase them. The first block you step onto decides which (stepping over another waypoint does not count) |
| `N` again within 15 s | Undo a deleted waypoint: press `N` on any of its blocks to bring it back exactly as it was. Keep holding and walk to extend it in the same press |
| `Left Shift` + `N` | The same on the block you look at, up to 512 blocks away. Hold it and sweep the crosshair to paint or erase blocks |
| `M` | Open the Waypy menu |
| `H` | Open the Presets popup for the waypoint you stand on: press a number key to apply a preset to it (see [Presets](#presets)) |
| `Left Shift` + `H` | The same for the waypoint you look at. With no waypoint there, nothing happens |
| `Y` | Hide or show all waypoints at once (beams, highlights and pins, groups included). The setting is remembered after a restart. `N` and the menu still work while everything is hidden |

A clump is one waypoint made of several blocks. It is always drawn as Block Selection (one merged shape), with the logo floating above its centre. Tapping `N` on one of its blocks removes just that block; if it was the first block, the next one takes over the position. Parts that end up apart stay one waypoint. A clump that is down to one block can be a Beacon again.

Everything the keys do is silent: Waypy shows no messages on screen. Removing a block from a clump that still has other blocks is final on its own; the freed block is plain ground again. Deleting a whole waypoint (a tap on a single block, tapping or erasing a clump's last block, or the menu's Delete) can be undone for 15 seconds, and a clump comes back whole: with every block it had before you started removing blocks from it (removals more than 15 seconds apart, or with an extend or a move in between, count as a fresh start). Press `N` on any of its old blocks, or use the menu's Undo. Nothing comes back if another waypoint has taken one of its blocks in the meantime, and leaving the world makes deletions final.

All five keys can be rebound under Options → Controls → Key Binds → Waypy: "Create/Delete Waypoint" (`N`), "Look-at Modifier" (`Left Shift`; sharing it with Sneak works), "Open Waypoint Menu" (`M`), "Open Presets" (`H`) and "Hide/Show All Waypoints" (`Y`). Vanilla only uses `H` together with `F3` (F3+H, advanced tooltips), so the two don't clash.

## Menu

The menu lists the waypoints of the current world on the left and edits the selected one on the right. Each waypoint has:

- a name and a color (16 dye swatches or a hex `#RRGGBB` value)
- its position: X, Y and Z boxes under the name. Typing moves the waypoint (a whole clump moves with its first block). Invalid numbers, or a move onto another waypoint's blocks, show red and aren't applied. Y stays within the build height
- a style: **Block Selection** (a tinted box with an outline; the default) or **Beacon** (a beam up to build height; single blocks only, so the button is greyed out for clumps)
- a logo shown above the waypoint: a **Block** icon you pick from a list (by default the block the waypoint is on), or **None**
- a **Text** toggle for the name and distance under the logo (off for new waypoints)
- for Block Selection, an opacity slider and a "Render through blocks" option (off by default)
- a Visible toggle (hidden waypoints are greyed in the list)

**Delete** removes the selected waypoint right away (it is greyed out while a group or several waypoints are selected); an **Undo** button appears next to it for 15 seconds. Undo brings back the last deleted waypoint (deleted here or with `N`). Blocks removed from a clump that still exists can't be undone; a deleted clump comes back with the blocks it had before you started removing them.

Clumps show their block count in the list and in the editor.

### Groups

A group bundles nearby waypoints (2 or more, in the same dimension) so that from far away they show as one pin instead of a crowd of pins.

- **Select several waypoints** in the list: `Ctrl`+click adds or removes one, `Shift`+click selects a range, a plain click goes back to one. The editor then shows "N waypoints selected" and a **Make group** button (greyed out if the waypoints are in different dimensions).
- The list shows each group as a row ("Mine Area · 3 waypoints") with its waypoints indented under it. Click the group row to edit the group: name, color, logo (a block, or None for the name's initial), **Visible**, Text and **Ungroup** (the waypoints stay, just ungrouped).
- **Visible: OFF** on a group hides the whole group: its waypoints and its group pin. The waypoints keep their own Visible setting, so turning the group back on shows exactly what was shown before. In the list a hidden group and its waypoints are greyed, and a waypoint hidden only by its group says "group hidden".
- A group member's editor shows "Group: <name>" and a **Remove from group** button. A waypoint is in at most one group; making a new group with it moves it out of its old one.
- A group needs at least 2 waypoints: when removing or deleting leaves only one, the group goes away by itself. A member that only loses blocks stays in its group. Undoing the deletion of a member puts it back into its group if the group still exists.
- **In the world:** while you are farther than the combine distance (15 blocks by default) from the middle of the group, its waypoints are replaced by one pin in the group's color, with the label "Name (3) (124m)" when its Text is on; their highlights and beams are hidden too. Come closer and the waypoints show again. The pins crossfade, and there is a 1-block margin so standing right at the edge doesn't flicker. Hidden waypoints don't count; a group with fewer than 2 visible waypoints always shows its waypoints.

While all waypoints are hidden with `Y`, the menu shows "All waypoints are hidden (press Y)" above the list.

Changes apply live and are saved when you close the menu.

## Presets

A preset is a saved look for a waypoint: its color, style, logo, opacity, "Render through blocks" and Text setting. Presets are shared by all your worlds and numbered 1, 2, 3, ... Waypy comes with none; make your own.

Stand on a waypoint and press `H`, or hold `Left Shift` and look at one while pressing `H`. With no waypoint there, nothing happens. The popup, styled like the game menu, shows "Applies to: <waypoint>" and your presets as buttons ("1  Home", with the logo and a color square).

- **Apply**: click a preset, or press its number key (1–9). The waypoint takes the preset's settings but keeps its own name, position, blocks, Visible setting and group. A clump stays Block Selection even with a Beacon preset. The popup closes.
- **Search**: just start typing a name. Letters go into the search box; the buttons keep their numbers. `Enter` applies the first match. `Esc` clears the search, and closes the popup when the search is empty. While the search box has the cursor, digits type into it instead of applying.
- **New preset**: the **+** button at the top right copies the waypoint's current settings into a small form: name, color, style, logo, opacity, through blocks, text. **Save** adds it with the next number.
- **Edit or delete**: right-click a preset. **Save** keeps its number; **Delete** removes it and the presets after it move up a number.
- `Esc` closes the popup (in the form, it goes back to the list).

Presets are saved in `<.minecraft>/config/waypy-presets.json` right after every change.

## Settings

Open them with the **Settings...** button at the top right of the menu. They apply to all worlds.

| Setting | Default | What it does |
|---|---|---|
| Waypoint Render Distance | 512 blocks | Beacon beams and block highlights farther away are not drawn; the logo and label stay. 16–4096 blocks or Unlimited. |
| Icon Size | 100% | Size of the waypoint pins and their name tags, 50–200%. |
| Shrink with Distance | ON | Pins shrink with distance like real objects (perspective): full size up to 16 blocks, half size at 32, quarter at 64, never smaller than a fixed 35% so far waypoints stay visible. Name tags never get smaller than 50%. |
| Labels | Always | Always, Looking At (only the waypoint nearest the crosshair) or Off. Applies to waypoints whose Text is on. Labels fade in and out. |
| Distance in Label | ON | Show the distance after the name. |
| Group Combine Distance | 15 blocks | The distance at which groups combine into one pin, the same for all groups (5–200 blocks). |
| Show Waypoints | ON | OFF hides everything Waypy draws, the same as pressing `Y`. |

Settings are saved in `<.minecraft>/config/waypy.json`.

## Where waypoints are saved

Waypoints and groups are stored per world in `<.minecraft>/waypy/<world>.json`.

## Building from source

You need JDK 25.

```
./gradlew build
```

The jar is written to `build/libs/`.
