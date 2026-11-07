# Waypy

**Waypy** is a client-side waypoint mod for Fabric. Mark a block, or a whole area of blocks, and find it again from anywhere: a coloured pin with the block's icon floats above it, and a beacon beam or a tinted block highlight shows exactly where it is.

Waypy runs only on your game. It needs nothing on the server, so it works in singleplayer, on Realms and on any multiplayer server.

---

## Features

- **One key to mark a spot.** Press `N` to put a waypoint on the block you stand on. Hold `Left Shift` and press `N` to mark the block you look at, up to 512 blocks away.
- **Coloured pins.** Every waypoint shows a small pin in its own colour with the icon of a block you pick (by default the block it sits on), optionally with its name and distance. Pins stay on top of the world and shrink with distance like real objects, but never so small that you lose them.
- **Beacon beams or block highlights.** Show a waypoint as a beam up to build height, or as a tinted, outlined box. Highlights can be drawn through walls if you want.
- **Clumps.** Hold `N` and walk (or hold `Left Shift` + `N` and sweep the crosshair) to paint several blocks into one waypoint: a farm, a building outline, a path. A clump is drawn as one merged highlight with a single pin above its centre. Up to 256 blocks per waypoint.
- **Walk to erase.** Hold `N` on a waypoint and walk back over its blocks to erase them again, or tap `N` on one block to remove just that one.
- **Undo.** Deleted a waypoint by mistake? Press `N` on one of its old blocks within 15 seconds (or use the Undo button in the menu) and it comes back exactly as it was, whole clump included.
- **Groups that combine from afar.** Bundle nearby waypoints into a group. From farther away, the group shows as one pin ("Mine Area (3) (124m)") instead of a crowd of pins; walk closer and the waypoints fade back in.
- **Presets.** Save a look (colour, style, icon, opacity, text) once and apply it to any waypoint with `H` and a number key.
- **Layout presets.** Save a whole group's arrangement and stamp it down somewhere else in one go: handy for repeating builds, farm plots or base outlines.
- **Hide everything with one key.** `Y` hides or shows all waypoints at once.
- **Silent.** Waypy never writes messages to chat or the action bar.

## Controls

All keys can be rebound under *Options → Controls → Key Binds → Waypy*.

| Key | What it does |
|---|---|
| `N` | Create a waypoint on the block you stand on. On an existing waypoint: tap to remove that block (or the waypoint). Hold and walk to extend a waypoint into a clump, or walk over its own blocks to erase them. Within 15 s of a deletion, press it on an old block to undo. |
| `Left Shift` + `N` | The same for the block you look at (up to 512 blocks). Hold and sweep the crosshair to paint or erase. |
| `M` | Open the Waypy menu. |
| `H` | Open the Presets popup for the waypoint you stand on. Press a number key to apply a preset. |
| `Left Shift` + `H` | The same for the waypoint you look at. |
| *Open Layouts* (unbound) | Open the same popup on its Layouts tab. Bind it if you use layouts a lot. |
| `Y` | Hide or show all waypoints. |

`Left Shift` is shared with Sneak, and that works fine. `H` only clashes with vanilla's `F3`+`H`, which is a separate combination.

## The menu (`M`)

The menu lists the waypoints of the current world on the left and edits the selected one on the right:

- name and colour (16 dye swatches or any `#RRGGBB` value);
- position (X, Y, Z boxes; typing moves the waypoint);
- style: **Block Selection** (tinted box, adjustable opacity, optional "Render through blocks") or **Beacon** (single blocks only);
- logo: a block icon picked from a searchable list, or none;
- a **Text** toggle for the name and distance under the pin;
- **Visible**, **Delete** and **Undo**.

## Groups

Select several waypoints in the list (`Ctrl`+click or `Shift`+click) and press **Make group**. A group has its own name, colour, logo and Visible switch, and hiding a group hides all of its waypoints at once.

While you are farther away than the *Group Combine Distance* (15 blocks by default) from the middle of a group, its waypoints are replaced by one group pin in the group's colour. Come closer and the individual pins crossfade back in.

## Presets and layouts

Press `H` on a waypoint to open the Presets popup:

- click a preset or press its number (1–9) to apply it; the waypoint keeps its own name, position and blocks;
- just start typing to search, `Enter` applies the first match;
- **+** saves the waypoint's current look as a new preset; right-click a preset to edit or delete it.

The **Layouts** tab works the same way for layout presets. Create one by right-clicking a group in the menu and choosing **Create layout preset**: the block you stand on becomes its anchor. Later, press `H` on a waypoint and pick the layout: the whole group is rebuilt with its anchor on that block, as a new group.

Presets and layouts are shared by all your worlds and servers.

## Settings

Open them from the **Settings...** button in the menu.

| Setting | Default | What it does |
|---|---|---|
| Waypoint Render Distance | 512 blocks | Beams and highlights farther away are not drawn; pins stay. 16–4096 or Unlimited. |
| Icon Size | 100% | Size of pins and name tags, 50–200%. |
| Shrink with Distance | ON | Pins get smaller with distance, down to a fixed minimum. |
| Labels | Always | Always, Looking At (only the pin near your crosshair) or Off. |
| Distance in Label | ON | Show the distance after the name. |
| Group Combine Distance | 15 blocks | From how far away groups combine into one pin (5–200). |
| Show Waypoints | ON | The same as the `Y` key. |
| H Opens | Presets | Which tab `H` opens: Presets or Layouts. |

## Requirements

- **Minecraft 26.2**
- **Fabric Loader 0.20.1** or newer
- **[Fabric API](https://modrinth.com/mod/fabric-api)**
- Java 25 (the version Minecraft 26.2 ships with)

Waypy is **client-side only**. Install it in your own `mods` folder; servers don't need it, and it works on any server.

## FAQ

**Where are my waypoints saved?**
On your computer, per world: `.minecraft/waypy/<world>.json` (singleplayer worlds start with `sp_`, servers with `mp_` and the server address). Presets, layouts and settings are in `.minecraft/config/` (`waypy-presets.json`, `waypy-layouts.json` and `waypy.json`).

**Does it work on servers?**
Yes. Waypy is purely client-side and only draws on your screen, so it works on any server without the server installing anything. Other players don't see your waypoints.

**How do I change the keys?**
*Options → Controls → Key Binds*, then scroll to the **Waypy** category. All six keys are there: Create/Delete Waypoint, Look-at Modifier, Open Waypoint Menu, Open Presets, Open Layouts and Hide/Show All Waypoints.

**I pressed `N` by accident and deleted a waypoint.**
Press `N` on any of its old blocks within 15 seconds, or open the menu and press **Undo**. It comes back exactly as it was.

**Can I share waypoints with friends?**
Not built in. The waypoint files are plain JSON, so you can copy them between installations.

## Source and issues

Waypy is open source under the MIT license. Source code and bug reports: [github.com/tunimamluk/Waypy](https://github.com/tunimamluk/Waypy).
