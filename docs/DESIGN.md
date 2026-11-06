# Waypy — design spec

Client-side Fabric waypoint mod for Minecraft **26.2** (Java 25, Mojang names — 26.x is unobfuscated).

- Mod id: `waypy` · Base package: `dev.waypy` · Language: Java
- Entrypoint: `dev.waypy.WaypyClient` (`client` entrypoint only; `environment: "client"`)

## User-facing behaviour

All three keys are vanilla `KeyMapping`s in the "Waypy" category, so they are listed and rebindable in Options → Controls → Key Binds:

| Key mapping | Name in Key Binds | Default |
|---|---|---|
| `key.waypy.create` | Create / Delete Waypoint (hold for clump) | `N` |
| `key.waypy.look_modifier` | Look-at Modifier (hold with Create) | `Left Shift` |
| `key.waypy.menu` | Open Waypoint Menu | `M` |

| Key | Action |
|---|---|
| `N` | The target is the block the player is **standing on** (block below the feet; if that is air, the first non-air block straight down; if none, `No block below you`). **Block of a waypoint** (any block of any waypoint in the current dimension, so also any block of a clump): delete that whole waypoint (restorable for 15 s, see **Undo**) and show `Waypoint "<name>" removed – press N to undo` (the key's current binding). **Free block of a recently deleted waypoint:** restore it (see **Undo**). **Other free block:** create a new waypoint there and make it the **selected** waypoint, silently (no message). |
| `N` held | **Clump:** after a press that created a waypoint, every client tick while the key stays down the target block is found again (same mode as the press) and added to the waypoint if it is in neither this nor any other waypoint (blocks of other waypoints are skipped silently). At 256 blocks: `Clump limit reached (256 blocks)` once, nothing more is added. Release is silent. A press that deleted, restored (or found no block) adds nothing until the key is released and pressed again. |
| Look-at Modifier + `N` | Same as `N`, but the block comes from a raycast from the camera (max 512 blocks, ignores fluids). On a miss, show `No block in sight`. Held, it paints a clump by sweeping the crosshair over blocks. |
| `M` | Open the Waypy menu with the **selected** waypoint pre-selected. |

The look-at modifier defaults to Left Shift, the same key as Sneak. That works because vanilla 26.2 keeps every mapping of a key (`KeyMapping.MAP` is `Key → List<KeyMapping>`) and sets all of them down/up, so both report `isDown()`.

**Undo.** A deleted waypoint (by the N toggle or the menu's Delete) leaves the world, the list and the block index at once, but `WaypointManager` keeps it in a small "recently deleted" stack (newest first, at most 8) for **15 seconds of real time** (`UNDO_WINDOW_MILLIS`, `Util.getMillis()`). Within that time:
- `N` (either mode) on any block that belonged to it restores it — same id, name, color, style, logo, every block and field, back at its old list position (and selected again if it was selected) — and shows `Waypoint "<name>" restored`. That press does not create a waypoint or start a clump.
- Only if none of its blocks has since been claimed by another waypoint; otherwise the press is handled normally (e.g. creates a new waypoint).
- The menu shows an `Undo` button next to `Delete` (see Menu).
Expired deletions are dropped (checked every tick). On disconnect and world change every pending deletion becomes permanent. Pending deletions are never saved (the file is written without them).

N key state machine (`dev.waypy.CreateKeyHandler`, run at the end of every client tick):
- Clicks (`consumeClick`) are always drained. A click while in game (player and level present, no screen open) is a **press**: the mode (Look-at Modifier `isDown()` = looked-at block, else block stood on) is fixed at the press for the whole hold.
- While holding: if the key is down (`KeyMapping.isDown()`) and no screen is open, extend the clump with the current target (missing targets are silent, no "No block" messages); otherwise **release**. Clicks during a hold (OS key repeat) are ignored, also on the tick the hold ends. A tap shorter than a tick is a press followed by an immediate release.
- Opening a screen calls `KeyMapping.releaseAll()`, and GLFW releases keys on focus loss, so a hold can never get stuck.

New waypoint defaults: name `Waypoint <n>` (n = next free number), color cycles through a palette of vanilla dye colors, style `BLOCK_HIGHLIGHT`, logo `BLOCK` using the item of the block the waypoint is on (the first block of a clump; `minecraft:compass` only for blocks without an item, e.g. fire), opacity 0.4, throughBlocks **false**, visible true, showText **false** (logo only). The N key and the menu's "+ New here" share this code (`WaypyClient.createWaypointAt`). "+ New here" always creates (it does not toggle or restore). Creating a waypoint shows no message.

## Data model (`dev.waypy.waypoint`)

```java
public final class Waypoint {
    UUID id;
    String name;                 // max 32 chars
    private List<BlockPos> blocks; // ordered (first added first), unique, 1..256; blocks() is a read-only view
    ResourceKey<Level> dimension; // serialized as identifier string, e.g. "minecraft:overworld"
    int color;                   // 0xRRGGBB
    WaypointStyle style;         // BEACON | BLOCK_HIGHLIGHT (a clump is always BLOCK_HIGHLIGHT)
    LogoType logoType;           // BLOCK | NONE
    Identifier logoBlock;        // item/block id used when logoType == BLOCK (kept while NONE)
    float highlightOpacity;      // 0..1, BLOCK_HIGHLIGHT only
    boolean throughBlocks;       // BLOCK_HIGHLIGHT only: render without depth test
    boolean visible;
    boolean showText;            // draw the name/distance label (if the global Labels setting allows it)
}
enum WaypointStyle { BEACON, BLOCK_HIGHLIGHT }
enum LogoType { BLOCK, NONE }
```

A waypoint with more than one block is a **clump** (max `Waypoint.MAX_BLOCKS` = 256). **A clump is never a beacon:** `WaypointManager.addBlock` switches a `BEACON` waypoint to `BLOCK_HIGHLIGHT` the moment it grows to two blocks, loading migrates any multi-block `BEACON` to `BLOCK_HIGHLIGHT`, and the menu's Style button is inactive for clumps. `isBeacon()` (= `style == BEACON && !isClump()`) is what the renderers check. Block helpers: `anchor()` (the first block), `size()`, `isClump()`, `contains(BlockPos)` (hash set), `centre()` (Vec3 centroid of the block centres), `topY()` (highest block), `bounds()` (AABB of the blocks). The derived values are cached per `version()`, a counter bumped on every block change. Blocks are added only through `WaypointManager.addBlock` and moved only through `WaypointManager.move`.

`WaypointManager` (singleton, `WaypointManager.get()`):
- `List<Waypoint> all()`, `List<Waypoint> inDimension(ResourceKey<Level>)`
- `Waypoint create(BlockPos, ResourceKey<Level>, Identifier logoBlock)`
- `AddBlockResult addBlock(Waypoint, BlockPos)`: `ADDED`, `ALREADY_IN_WAYPOINT`, `IN_OTHER_WAYPOINT`, `LIMIT_REACHED` or `UNKNOWN_WAYPOINT`
- `@Nullable Waypoint waypointAt(BlockPos, ResourceKey<Level>)`: O(1) lookup through a per-dimension `Map<BlockPos, Waypoint>` index, kept in sync on create, remove and restore, move (the dimension's index is rebuilt), load and `addBlock`. If two waypoints share a block (possible in old files or via "+ New here"), the index points to the earlier one.
- `MoveResult move(Waypoint, BlockPos newAnchor)`: translates every block by the delta from the anchor to `newAnchor` (a clump keeps its shape). `OVERLAP` (with the blocking waypoint, nothing changes) if a moved block would land on a block of another waypoint in the same dimension; else `MOVED` (index rebuilt, `version()` bumped so the cached highlight mesh rebuilds, dirty) or `UNCHANGED`.
- `void remove(UUID)` (restorable for 15 s), `lastDeleted()`, `canRestore(DeletedWaypoint)`, `restore(UUID)`, `restoreDeletedAt(BlockPos, dimension)` (the N key's undo), `expireDeletions(boolean all)`; `void markDirty()` / `save()`
- `Optional<Waypoint> selected()`, `void select(UUID)`
- Loads on world join, saves on change and on disconnect.

Storage: `<gameDir>/waypy/<worldKey>.json` (Gson tree, pretty-printed with arrays of primitives kept on one line). Each waypoint stores its blocks as `"blocks": [[x, y, z], ...]` and also the anchor as `x`/`y`/`z` (so older Waypy versions still load it as a single block). Migration: an entry without a valid `blocks` array loads `x`/`y`/`z` as a 1-block waypoint; invalid `blocks` entries are skipped, duplicates dropped and at most 256 kept. A stored `logoType` of `TEXT` (the removed text logo) or any unknown value loads as `BLOCK` with the stored `logoBlock`; an old `logoText` field is ignored. A multi-block `BEACON` loads as `BLOCK_HIGHLIGHT` (and the file is rewritten). `showText` is stored per waypoint; an entry without it (written before the field existed) loads as `true`, so existing waypoints keep their labels. `worldKey` = sanitized singleplayer save folder name prefixed `sp_`, or server address prefixed `mp_`. Waypoints are per world, filtered by dimension at render time.

## Rendering (`dev.waypy.render`)

Only waypoints with `visible == true` in the player's current dimension.

- **BEACON** (single-block waypoints only; a clump is always a highlight): vanilla-looking beacon beam (vanilla beam texture, tinted with `color`) from the top of the block up to the build height. Vanilla depth behaviour, so terrain hides it the way it hides a real beam.
  - Not limited by the game's render distance and independent of chunk loading (only by the Waypoint render distance setting, below). Beams past the start of the render-distance fog use a fog-free copy of the vanilla beam pipeline (`waypy:core/beacon_beam_no_fog`); beams that would reach past the camera's far plane are scaled uniformly about the camera so they stay on the same view rays.
- **BLOCK_HIGHLIGHT**: the blocks as ONE merged shape: a tinted fill at `highlightOpacity`, plus a solid outline in `color`. If `throughBlocks`, both draw without depth testing (visible through terrain). Built by `render.ClumpMesh` and cached per waypoint until its `version()` changes (never per frame); culled with the shape's AABB.
  - Fill: only faces not shared with another block of the waypoint.
  - Outline: for each exposed face (direction `d`) of block `b` and each of its four sides (in-plane direction `s`), the edge is drawn unless the face continues flat onto the neighbour, i.e. unless `b+s` is in the waypoint and `b+s+d` is not. So: convex edges (`b+s` and `b+s+d` both free) and creases (`b+s+d` in the waypoint: the face runs into a wall, e.g. a stair step) are drawn, edges between coplanar exposed faces are not. Each edge is emitted once.
  - Everything is pushed 0.002 outward against z-fighting (faces along their normal, edges along the sum of their two face normals); a single block gives exactly the inflated box.
- **Waypoint render distance**: beams and highlights are not drawn when the distance from the camera (3D) to the box spanned by the waypoint's block centres is greater than the setting, for both styles and including the far-beam path; the logo and label remain. For a single block this is the distance to its centre; for a clump it is the distance to (at most) its nearest block, so a long clump next to the player is not cut off because its centre is far away, at O(1) per waypoint. Highlights are additionally limited to the game's render distance (same distance).
- **Logo** (both styles): a HUD marker projected from a point above the waypoint: x/z of the centroid, y of the top face of the highest block (`topY() + 1`) plus 1.5 blocks for highlight, 3 for beacon. The distance in the label is to the centroid. It is always visible through blocks. Below it is a small label: `name` and distance in metres (the distance can be turned off). The label is drawn only if the waypoint's `showText` is on (default off) **and** the Labels setting allows it.
  - `BLOCK` logo → the item icon of `logoBlock`. `NONE` → no logo; the label stays where it would be with a logo. `NONE` with text off → no HUD marker at all, only the beam or highlight.
  - **Size**: `scale = distanceScale(d) × iconSize`. `distanceScale` is 1.0 at d ≤ 16 blocks and `minIconSize` (default 0.5) at d ≥ 256, linear in ln(d) in between: `t = (ln d − ln 16) / (ln 256 − ln 16)`, `distanceScale = lerp(t, 1, min)`; it is 1.0 when shrinking is off. Scaling goes through the GUI pose matrix (as vanilla scales GUI items). Logo and label scale **continuously** (no quantisation, so markers shrink smoothly while walking away); the label scale is `max(0.5, scale)`. Only the marker's anchor (the projected point) is snapped to a framebuffer pixel; the logo and label are laid out from it with unsnapped offsets, so they never jitter against each other while the scale changes, and at a constant scale their pixel pattern is stable while the marker moves. (At fractional scales the nearest-filtered font texels map to 1 or 2 framebuffer pixels, so glyph strokes vary slightly in width; that is the cost of continuous scaling.)
  - **Labels** (setting): Always / Looking At / Off. Waypoints with text off take no part in any label decision. Always: labels never overlap — using the scaled rectangles, a label that would overlap an already placed label or the logo of a nearer waypoint is hidden (the farther waypoint loses). In every mode but Off, the label of the text marker nearest the crosshair (within 40 GUI px) is always shown, on top; with Looking At it is the only label.
  - **Fades**: a label shown or hidden by these rules (overlap, Looking At, the Labels setting, the text toggle) fades in or out over 175 ms instead of popping. The HUD keeps an opacity per waypoint id, advanced by the real frame time (at most 100 ms per frame) and applied to the text and its background; a label fading out is drawn where it would be. State is kept only for markers on screen this frame, so deleted, hidden or off-screen waypoints leave none.

## Menu (`dev.waypy.gui`) — vanilla styled

Use only vanilla widgets and textures: `Button`, `EditBox`, `AbstractSliderButton`, `CycleButton`, `Checkbox`, vanilla list widgets, the menu background/blur and the standard header/footer separators. No custom textures.

`WaypointScreen` (title "Waypy"):
- **Left**: a scrollable list of waypoints in the current world (color swatch, name, distance to the centroid; clumps add the block count, e.g. `24m · 12 blocks`). The selected waypoint is highlighted. Buttons below it: `+ New here` (waypoint at the player's feet) and `Delete`. Delete removes the selected waypoint at once (no confirmation dialog) and selects its neighbour; while a deletion can be undone (15 s), an `Undo` button appears next to Delete (the row becomes three equal buttons). Undo restores the most recent deletion and selects it; it is inactive (tooltip says why) if the waypoint's blocks now belong to another waypoint.
- **Right**: an editor for the selected waypoint (two columns at ≥ 300 px, else one column; it scrolls).
  - Name `EditBox`.
  - Position: three small `EditBox`es labelled X, Y, Z on one row (each on its own row when the editor is too narrow for 40 px boxes) with the anchor's coordinates (the first block of a clump; the label says so). Typing a whole number (optional leading `-`) moves the waypoint through `WaypointManager.move` as you type, like the hex box: a clump is translated as a whole. Invalid input turns the text red (tooltip "Enter a whole number") and isn't applied; a move onto a block of another waypoint in the same dimension is refused, red, with a tooltip naming that waypoint. Y is clamped so every block stays within the current level's build height (the box then shows the clamped value and a tooltip).
  - Color: a row of 16 dye-color swatches and a hex `EditBox` (`#RRGGBB`).
  - Style `CycleButton`: Beacon / Block Selection. Inactive for clumps, tooltip "Clumps always use Block Selection".
  - Logo `CycleButton`: Block / None.
    - Block → a button showing the current block icon, which opens `BlockPickerScreen` (under Logo, in the right column). Hidden for None.
  - "Visible" and "Text" (on/off: the name/distance label) toggles side by side.
  - If Block Selection is the style: an Opacity slider (0–100%) and a "Render through blocks" `Checkbox`.
  - For clumps, a grey info line `Clump: <n> blocks` at the end (no per-block editing).
- Header: a `Settings...` button (70–90 px wide, 20 px high) level with the title, right-aligned to the editor column's right edge; it moves right / narrows so it never overlaps the title on small screens.
- Footer: `Done` (200 px).
- Changes apply live and save when the screen closes.

`BlockPickerScreen`: a vanilla-styled search `EditBox` above a scrollable grid of item icons for all `BuiltInRegistries.BLOCK` entries that have an item. Clicking one sets the logo and returns.

## Settings (`dev.waypy.config`, `gui.WaypySettingsScreen`)

Global client settings in `<gameDir>/config/waypy.json` (`WaypyConfig`, Gson). Loaded at client init; saved when the settings screen closes. A missing file means defaults; an unreadable or corrupt file logs a warning and the defaults are used. Values are clamped/snapped on load.

| Setting | JSON field | Default | Range |
|---|---|---|---|
| Waypoint Render Distance | `waypointRenderDistance` | 512 | 16–4096 blocks or Unlimited (`-1`). Slider steps: 16 up to 256, 32 up to 512, 64 up to 1024, 128 up to 2048, 256 up to 4096; the top position is Unlimited. |
| Icon Size | `iconSize` | 100 | 50–200 %, 5 % steps |
| Shrink with Distance | `shrinkWithDistance` | true | on/off |
| Minimum Size | `minIconSize` | 50 | 25–100 %, 5 % steps; disabled while shrinking is off |
| Labels | `labels` | `ALWAYS` | `ALWAYS` / `LOOKING_AT` / `OFF` |
| Distance in Label | `showDistance` | true | on/off |

`WaypySettingsScreen` extends vanilla `OptionsSubScreen` (title header "Waypy Settings", `OptionsList`, Done footer) and uses vanilla `OptionInstance`s: `SliderableEnum` for the render distance, `IntRange.xmap` sliders for the percentages, `createBoolean` and `Enum` cycle buttons for the rest. Rows: render distance (310 px), icon size (310 px), then two 150 px columns: Shrink with Distance | Minimum Size, Labels | Distance in Label. It is opened from the menu's `Settings...` button. No Mod Menu integration (no 26.2 Mod Menu build could be verified).

All user-visible strings go in `assets/waypy/lang/en_us.json` under the `waypy.` / `key.waypy.` namespaces (settings under `waypy.settings.*`).
