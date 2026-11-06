# Waypy — design spec

Client-side Fabric waypoint mod for Minecraft **26.2** (Java 25, Mojang names — 26.x is unobfuscated).

- Mod id: `waypy` · Base package: `dev.waypy` · Language: Java
- Entrypoint: `dev.waypy.WaypyClient` (`client` entrypoint only; `environment: "client"`)

## User-facing behaviour

All three keys are vanilla `KeyMapping`s in the "Waypy" category, so they are listed and rebindable in Options → Controls → Key Binds:

| Key mapping | Name in Key Binds | Default |
|---|---|---|
| `key.waypy.create` | Create/Delete Waypoint | `N` |
| `key.waypy.look_modifier` | Look-at Modifier | `Left Shift` |
| `key.waypy.menu` | Open Waypoint Menu | `M` |

| Key | Action |
|---|---|
| `N` | The **target** is the block the player is **standing on** (block below the feet; if that is air, the first non-air block straight down; if none, `No block below you`). What a press does depends on the target, decided at the press: **Block of a waypoint W** (any block of any waypoint in the current dimension, single or clump): nothing changes yet; the press starts an **extend-or-remove** hold on W (see `N` held / released). **Free block with a pending undo** (a block of a recently deleted waypoint, or a block recently removed from a waypoint): undo it (see **Undo**); the hold then extends that waypoint. **Other free block:** create a new waypoint there and make it the **selected** waypoint, silently (no message); the hold then extends it. |
| `N` held | **Extend:** every client tick while the key stays down, the target block is found again (same mode as the press) and added to the hold's waypoint (the one created, restored or pressed on) if it is in no waypoint (blocks of this and other waypoints are skipped silently). A waypoint that grows to 2+ blocks is forced to `BLOCK_HIGHLIGHT`. At 256 blocks: `Clump limit reached (256 blocks)` once, nothing more is added. A press that found no block adds nothing until the key is released and pressed again. |
| `N` released | Silent, except after a press on an existing waypoint W that added no block (a **tap**; a hold that ran into the 256 limit is not a tap): if W has more than one block, only the pressed block is removed from W (`Block removed from "<name>" (<n> blocks left) – press N to undo`; `1 block left` for one); if it was W's only block, the whole waypoint is deleted (`Waypoint "<name>" removed – press N to undo`). Both are undoable for 15 s. A hold that added blocks was an **extend** and keeps everything, the start block included, silently. A press that restored something never counts as a tap, so its release removes nothing. |
| Look-at Modifier + `N` | Same as `N`, but the block comes from a raycast from the camera (max 512 blocks, ignores fluids). On a miss, show `No block in sight`. Held, it paints (extends) by sweeping the crosshair over blocks. |
| `M` | Open the Waypy menu with the **selected** waypoint pre-selected. |

The look-at modifier defaults to Left Shift, the same key as Sneak. That works because vanilla 26.2 keeps every mapping of a key (`KeyMapping.MAP` is `Key → List<KeyMapping>`) and sets all of them down/up, so both report `isDown()`.

**Removing one block.** A tap on a block of a clump removes only that block (`WaypointManager.removeBlock`): the index is updated, the waypoint's `version()` is bumped (the highlight mesh rebuilds), and if it was the anchor the next block becomes the anchor. The logo and every other field stay unchanged. Disconnected parts are fine (the waypoint is never split). The waypoint is a clump only while it has 2+ blocks: down to one block it keeps its current style but may use any style again (the menu's Style lock follows the block count); growing back to two forces `BLOCK_HIGHLIGHT`.

**Undo.** A deleted waypoint (by an N tap on a single-block waypoint or the menu's Delete) leaves the world, the list and the block index at once, and so does a block removed from a waypoint by an N tap. `WaypointManager` keeps both in ONE small "recently undoable" stack of `UndoEntry`s (newest first, at most 16): `DeletedWaypoint` (the waypoint itself, its list position and whether it was selected) and `RemovedBlock` (waypoint id, name, dimension, block pos and its index in the block list). Each stays undoable for **15 seconds of real time** (`UNDO_WINDOW_MILLIS`, `Util.getMillis()`). Within that time:
- `N` (either mode) on a free block restores the newest entry for that block that can be applied:
  - a deleted waypoint that had this block → restored unchanged (same id, name, color, style, logo, every block and field, back at its old list position, and selected again if it was selected): `Waypoint "<name>" restored`. Only if none of its blocks has since been claimed by another waypoint.
  - this removed block → put back into its waypoint at its old index (so a removed anchor is the anchor again): `Block restored to "<name>"`. Only if that waypoint still exists in this dimension, is not full and the block is still free; a waypoint back at 2+ blocks is forced to `BLOCK_HIGHLIGHT`.
  - Otherwise the press is handled normally (e.g. creates a new waypoint).
- The hold of a restoring press continues as an extend on the restored waypoint; its release never removes anything.
- The menu shows an `Undo` button next to `Delete` for the most recent entry of either kind (see Menu).
Re-adding a removed block by extending drops its pending removal; moving a waypoint (menu X/Y/Z) drops its pending block removals (their positions no longer fit). Expired entries are dropped (checked every tick). On disconnect and world change every pending entry becomes permanent. Pending entries are never saved (the file is written without them).

N key state machine (`dev.waypy.CreateKeyHandler`, run at the end of every client tick):
- States (`CreateKeyHandler.Hold`): `IDLE`; and while holding `NONE` (no block), `CREATED` (new waypoint), `RESTORED` (an undo applied) or `EXTEND_OR_REMOVE` (pressed on waypoint W; remembers the start block and how many blocks the hold added).
- Clicks (`consumeClick`) are always drained. A click while in game (player and level present, no screen open) is a **press**: the mode (Look-at Modifier `isDown()` = looked-at block, else block stood on) is fixed at the press for the whole hold, and the state is chosen from the target as above.
- While holding, each tick: if a screen is open, the player or level is gone, or the window lost focus → **cancel** (the hold ends and nothing happens, so an interrupted tap removes nothing). Else if the key is down (`KeyMapping.isDown()`) → extend the hold's waypoint with the current target (missing targets are silent, no "No block" messages). Else → **release**: only `EXTEND_OR_REMOVE` with 0 added blocks (and no limit hit) acts, as the tap described above.
- Clicks during a hold (OS key repeat) are ignored, also on the tick the hold ends. A tap is decided only by whether the hold added a block, never by clicks, so key repeat can't turn a tap into an extend. A tap shorter than a tick is a press followed by an immediate release.
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

public final class WaypointGroup {
    final UUID id;
    String name;                 // max 32 chars; new groups: "Group <n>" (next free number)
    final ResourceKey<Level> dimension; // every member's dimension
    int color;                   // 0xRRGGBB; new groups: the first member's colour
    LogoType logoType;           // BLOCK | NONE (NONE: the pin shows the name's initial); new groups: the first member's
    Identifier logoBlock;        //   logo type and block
    boolean showText;            // the group pin's tag; false for new groups
    int combineDistance;         // blocks, 5..200; new groups: the "Group Combine Distance" setting
    private List<UUID> members;  // ordered member waypoint ids; members() is a read-only view
}
```

**Groups.** A group is 2+ waypoints of one dimension shown as ONE pin from far away (see Rendering). A waypoint is in at most one group. The manager keeps the invariants: every member is a managed waypoint of the group's dimension, and a group that would drop below 2 members (`WaypointGroup.MIN_MEMBERS`) is **dissolved** at once (its remaining waypoint simply becomes ungrouped). Members change only through the manager:
- Making a group of waypoints that are already in groups takes them out of their old groups first (dissolving those that drop below 2).
- Deleting a member (an `N` tap or the menu's Delete) removes it from its group. The `DeletedWaypoint` undo entry remembers the group id and the member's index; undoing the deletion puts it back at that index **if that group still exists** (else it comes back ungrouped). Moving a member (X/Y/Z) keeps it in its group.

A waypoint with more than one block is a **clump** (max `Waypoint.MAX_BLOCKS` = 256). **A clump is never a beacon:** `WaypointManager.addBlock` switches a `BEACON` waypoint to `BLOCK_HIGHLIGHT` the moment it grows to two blocks, loading migrates any multi-block `BEACON` to `BLOCK_HIGHLIGHT`, and the menu's Style button is inactive for clumps. `isBeacon()` (= `style == BEACON && !isClump()`) is what the renderers check. A clump that loses blocks down to one keeps its style. Block helpers: `anchor()` (the first block), `size()`, `isClump()`, `contains(BlockPos)` (hash set), `centre()` (Vec3 centroid of the block centres), `topY()` (highest block), `bounds()` (AABB of the blocks). The derived values are cached per `version()`, a counter bumped on every block change. Blocks are added only through `WaypointManager.addBlock` (or an undo), removed only through `WaypointManager.removeBlock` and moved only through `WaypointManager.move`.

`WaypointManager` (singleton, `WaypointManager.get()`):
- `List<Waypoint> all()`, `List<Waypoint> inDimension(ResourceKey<Level>)`
- `Waypoint create(BlockPos, ResourceKey<Level>, Identifier logoBlock)`
- `AddBlockResult addBlock(Waypoint, BlockPos)`: `ADDED`, `ALREADY_IN_WAYPOINT`, `IN_OTHER_WAYPOINT`, `LIMIT_REACHED` or `UNKNOWN_WAYPOINT`
- `@Nullable Waypoint waypointAt(BlockPos, ResourceKey<Level>)`: O(1) lookup through a per-dimension `Map<BlockPos, Waypoint>` index, kept in sync on create, remove and restore, move (the dimension's index is rebuilt), load, `addBlock` and `removeBlock`. If two waypoints share a block (possible in old files or via "+ New here"), the index points to the earlier one.
- `MoveResult move(Waypoint, BlockPos newAnchor)`: translates every block by the delta from the anchor to `newAnchor` (a clump keeps its shape). `OVERLAP` (with the blocking waypoint, nothing changes) if a moved block would land on a block of another waypoint in the same dimension; else `MOVED` (index rebuilt, `version()` bumped so the cached highlight mesh rebuilds, dirty) or `UNCHANGED`.
- `RemoveBlockResult removeBlock(Waypoint, BlockPos)`: `REMOVED` (undoable for 15 s), `NOT_IN_WAYPOINT`, `LAST_BLOCK` (a waypoint is never empty; remove the waypoint instead) or `UNKNOWN_WAYPOINT`.
- `void remove(UUID)` (restorable for 15 s); undo: `lastUndo()` (newest `UndoEntry` of either kind), `lastDeleted()` (newest `DeletedWaypoint`), `canUndo(UndoEntry)`, `undo(UndoEntry)` (the menu), `restore(UUID)` (a deleted waypoint by id), `undoAt(BlockPos, dimension)` (the N key's undo; returns the entry and the waypoint), `remainingUndoMillis(UndoEntry)`, `expireDeletions(boolean all)`; `void markDirty()` / `save()`
- `Optional<Waypoint> selected()`, `void select(UUID)`
- Groups: `List<WaypointGroup> groups()`, `groupsIn(dimension)`, `groupById(UUID)`, `@Nullable WaypointGroup groupOf(UUID waypointId)` (O(1), a member index rebuilt on every membership change), `List<Waypoint> members(WaypointGroup)` (in order); `GroupCheck canGroup(List<Waypoint>)` (`OK`, `TOO_FEW` (fewer than 2 distinct managed waypoints) or `MIXED_DIMENSIONS`); `@Nullable WaypointGroup createGroup(List<Waypoint>)` (the menu's "Make group"; members in the given order); `boolean removeFromGroup(Waypoint)`; `void ungroup(WaypointGroup)` (members stay).
- Loads on world join, saves on change and on disconnect.

Storage: `<gameDir>/waypy/<worldKey>.json` (Gson tree, pretty-printed with arrays of primitives kept on one line). Each waypoint stores its blocks as `"blocks": [[x, y, z], ...]` and also the anchor as `x`/`y`/`z` (so older Waypy versions still load it as a single block). Migration: an entry without a valid `blocks` array loads `x`/`y`/`z` as a 1-block waypoint; invalid `blocks` entries are skipped, duplicates dropped and at most 256 kept. A stored `logoType` of `TEXT` (the removed text logo) or any unknown value loads as `BLOCK` with the stored `logoBlock`; an old `logoText` field is ignored. A multi-block `BEACON` loads as `BLOCK_HIGHLIGHT` (and the file is rewritten). `showText` is stored per waypoint; an entry without it (written before the field existed) loads as `true`, so existing waypoints keep their labels. `worldKey` = sanitized singleplayer save folder name prefixed `sp_`, or server address prefixed `mp_`. Waypoints are per world, filtered by dimension at render time.

Groups are saved in the same file as a top-level `"groups"` array (after `"waypoints"`), each `{id, name, dimension, color ("#RRGGBB"), logoType, logoBlock, showText, combineDistance, members: ["<waypoint uuid>", ...]}`. A file without `groups` (older versions) loads with no groups. On load, a member that is not a loaded waypoint of the group's dimension, or that is already in an earlier group, is dropped; a group left with fewer than 2 members is dropped (and the file rewritten); `combineDistance` is snapped to a slider step (missing: 15); an unknown `logoType` loads as `BLOCK`.

## Rendering (`dev.waypy.render`)

Only waypoints with `visible == true` in the player's current dimension.

- **BEACON** (single-block waypoints only; a clump is always a highlight): vanilla-looking beacon beam (vanilla beam texture, tinted with `color`) from the top of the block up to the build height. Vanilla depth behaviour, so terrain hides it the way it hides a real beam.
  - Not limited by the game's render distance and independent of chunk loading (only by the Waypoint render distance setting, below). Beams past the start of the render-distance fog use a fog-free copy of the vanilla beam pipeline (`waypy:core/beacon_beam_no_fog`); beams that would reach past the camera's far plane are scaled uniformly about the camera so they stay on the same view rays.
- **BLOCK_HIGHLIGHT**: the blocks as ONE merged shape: a tinted fill at `highlightOpacity`, plus a solid outline in `color`. If `throughBlocks`, both draw without depth testing (visible through terrain). Built by `render.ClumpMesh` and cached per waypoint until its `version()` changes (never per frame); culled with the shape's AABB.
  - Fill: only faces not shared with another block of the waypoint.
  - Outline: for each exposed face (direction `d`) of block `b` and each of its four sides (in-plane direction `s`), the edge is drawn unless the face continues flat onto the neighbour, i.e. unless `b+s` is in the waypoint and `b+s+d` is not. So: convex edges (`b+s` and `b+s+d` both free) and creases (`b+s+d` in the waypoint: the face runs into a wall, e.g. a stair step) are drawn, edges between coplanar exposed faces are not. Each edge is emitted once.
  - Everything is pushed 0.002 outward against z-fighting (faces along their normal, edges along the sum of their two face normals); a single block gives exactly the inflated box.
- **Groups** (`render.GroupCombiner`, shared by the world renderer and the HUD so both agree each frame): for each group in the current dimension, only its `visible` members count; with fewer than 2 visible it is never combined and its members render normally.
  - `groupCentre` = the centroid of the visible members' `centre()`s. The pin anchor is at x/z of `groupCentre` and y = the highest member anchor (each member's own height rule above: `topY() + 1` plus 1.5, or 3 for a beacon). The distance (state, tag, pin scale) is the 3D distance from the camera to `groupCentre`.
  - **Combined** when that distance is beyond the group's `combineDistance`, with **hysteresis** of 0.5 block either side (1 block in total): a separate group combines when the distance exceeds `combineDistance + 0.5` and splits again when it drops below `combineDistance - 0.5`, so standing at the boundary doesn't flicker.
  - Combined: the HUD draws ONE pin for the group (same variant-D geometry and rules: group colour, group logo or its initial for `NONE`, perspective scale, overlap and Labels rules like any marker). Its tag is `Name (n) (124m)` (`n` = visible members; `Name (n)` with Distance in Label off), shown following the group's `showText` and the Labels setting. The members' own pins are not drawn, and their beams and highlights are hidden.
  - Not combined: the members render exactly as normal waypoints and the group draws nothing.
  - **Transition**: the HUD crossfades over 200 ms (`COMBINE_FADE_MILLIS`): the member pins fade out while the group pin fades in, and the reverse. Each group keeps a combine alpha (0 = members, 1 = group pin) advanced by the real frame time; member pins draw at `1 - alpha`, the group pin at `alpha`, and their tags fade with them. Pin fills, ring, pointer, initial and tag fade by alpha; the item icon can't be drawn translucent in the GUI, so it is shown only while its pin is at least half opaque (the icons swap at the midpoint), and a fading pin also scales from 80 % to full size so the swap reads as part of the transition. A group seen for the first time (world join, a new group) starts at its target, without a fade. **Beams and highlights switch instantly** at the threshold (with the same hysteresis); fading world geometry was not worth the cost.
  - The waypoint render-distance setting does not apply to the group pin (pins always show), as for waypoint pins.
- **Waypoint render distance**: beams and highlights are not drawn when the distance from the camera (3D) to the box spanned by the waypoint's block centres is greater than the setting, for both styles and including the far-beam path; the pin and its tag remain. For a single block this is the distance to its centre; for a clump it is the distance to (at most) its nearest block, so a long clump next to the player is not cut off because its centre is far away, at O(1) per waypoint. Highlights are additionally limited to the game's render distance (same distance).
- **Marker: coloured pin** (both styles; design variant "D. Coloured pin"): a HUD marker projected from an anchor point above the waypoint: x/z of the centroid, y of the top face of the highest block (`topY() + 1`) plus 1.5 blocks for highlight, 3 for beacon. The pin is drawn so the **tip of its pointer sits exactly on the projected anchor**. It is always visible through blocks. The distance in the tag is to the centroid. Drawn with GUI fills plus the item icon and font (no texture).
  - **Geometry** (GUI px at 100 %, relative to the tip at (0, 0), y down; the scale applies about the tip):
    - Badge: 20×20 at x −10..10, y −23..−3. Fill = waypoint colour. A 1 px ring (outermost row/column) in the shade colour. The four corner pixels are left empty (the design's 1 px clipped corners).
    - Content: `BLOCK` logo → the 16×16 item icon of `logoBlock` at (−8, −21), leaving 1 px of colour between ring and icon. `NONE` → the name's first character, upper-cased, in the vanilla font, centred: x = −width/2 (the font width includes the 1 px spacing column the shadow uses), y = −17 (7 glyph rows + 1 shadow row centred in the 20 px badge). An empty name shows a 2×2 dot at x −1..1, y −14..−12 (with a 1 px shadow when the text is white).
    - Pointer: three rows in the shade colour directly under the badge, 6, 4 and 2 px wide, centred: x −3..3 at y −3, −2..2 at y −2, −1..1 at y −1. The last row ends at the tip.
    - Name tag: 2 px above the badge (gap scales with the pin). A plate of the vanilla text background colour at 40 % (`Options#getBackgroundColor(0.4)`), 1 px padding left, right and top, none below: it spans text x −1..width and y −1..8 (9 px tall), with white text without shadow. It is centred on the tip with a whole-pixel offset (`floor((width + 1) / 2)`). Text: `name (distance m)`, or `name` with "Distance in Label" off.
    - Whole pin: 20×23 px; with the tag 34 px tall. All offsets are whole unscaled pixels, so the pin is pixel-crisp at 100 %.
  - **Colours and contrast** (relative luminance L per WCAG, from the opaque waypoint colour):
    - Shade (ring and pointer) = colour × 0.55 per channel (rounded), as in the design; for very dark colours (L < 0.03, e.g. black dye) it is the colour lerped 35 % toward white instead, so the ring stays visible.
    - Initial/dot: L ≤ 0.42 → white with the vanilla drop shadow (#3F3F3F), as in the design; L > 0.42 (lime, yellow, white, ...) → the colour × 0.3, no shadow, so light badges stay readable.
    - The tag is neutral (dark plate, white text) for every colour.
  - Pins always draw (a `NONE` waypoint with text off still shows its initial pin). The tag is drawn only if the waypoint's `showText` is on (default off) **and** the Labels setting allows it.
  - **Size**: `scale = distanceScale(d) × iconSize`, true perspective: `distanceScale = clamp(16 / d, 0.35, 1)`, so 1.0 up to 16 blocks, 0.5 at 32, and a fixed floor of 0.35 (`MIN_SCALE`, from about 46 blocks) so far pins stay recognisable and never vanish; it is 1.0 when shrinking is off. There is no user setting for the floor. Scaling goes through the GUI pose matrix (as vanilla scales GUI items). Pin and tag scale **continuously** (no quantisation); the tag scale is `max(0.5, scale)` and its position follows the pin scale. Only the anchor (the projected point) is snapped to a framebuffer pixel; pin and tag are laid out from it with unsnapped scaled offsets, so they never jitter against each other while the scale changes. (At fractional scales the nearest-filtered font and icon texels map to 1 or 2 framebuffer pixels; that is the cost of continuous scaling.)
  - **Labels** (setting): Always / Looking At / Off. Waypoints with text off take no part in any tag decision. Always: tags never overlap — using the scaled rectangles (tag plate plus 1 px margin; the pin's badge-and-pointer box x −10..10, y −23..0), a tag that would overlap an already placed tag or the pin of a nearer waypoint is hidden (the farther waypoint loses). In every mode but Off, the tag of the text pin whose badge centre is nearest the crosshair (within 40 GUI px) is always shown, on top; with Looking At it is the only tag.
  - **Draw order**: far to near (pin, then its tag), so nearer pins paint over farther ones; the focused tag last.
  - **Fades**: a tag shown or hidden by these rules (overlap, Looking At, the Labels setting, the text toggle) fades in or out over 175 ms instead of popping. The HUD keeps an opacity per waypoint id, advanced by the real frame time (at most 100 ms per frame) and applied to the text and its plate; a tag fading out is drawn where it would be. Pins do not fade, except in a group crossfade (see Groups). State is kept only for markers on screen this frame, so deleted, hidden or off-screen waypoints leave none.

## Menu (`dev.waypy.gui`) — vanilla styled

Use only vanilla widgets and textures: `Button`, `EditBox`, `AbstractSliderButton`, `CycleButton`, `Checkbox`, vanilla list widgets, the menu background/blur and the standard header/footer separators. No custom textures.

`WaypointScreen` (title "Waypy"):
- **Left**: a scrollable list of the current world: first each **group row** (a stacked two-square swatch in the group colour, the name and `3 waypoints · 48m`, the distance to the members' centroid) with its members listed directly under it, indented 10 px; then the ungrouped waypoints. A waypoint row has a color swatch, the name and the distance to the centroid; clumps add the block count, e.g. `24m · 12 blocks`.
  - **Selection** (as in a file browser): a plain click (or keyboard navigation) selects one row and clears any multi-selection; a group row selects the group (group editor). `Ctrl`+click (also `Cmd`) toggles a waypoint row in the multi-selection (starting from the current single selection; the last selected row can't be toggled off); `Shift`+click selects the range of waypoint rows (in list order, members included) from the last plain or Ctrl-clicked waypoint. The primary row (the edited waypoint or group, the last one clicked) has vanilla's selection outline; the other rows of a multi-selection get the same outline in light grey. Indented rows have their outline indented too.
  - Buttons below it: `+ New here` (waypoint at the player's feet) and `Delete`. Delete works on a single selected waypoint only: it is inactive (with a tooltip) while a group row or a multi-selection is selected (a group is dissolved with Ungroup in the group editor). Delete removes the selected waypoint at once (no confirmation dialog) and selects its neighbour in the list; while a deletion or a block removal can be undone (15 s), an `Undo` button appears next to Delete (the row becomes three equal buttons). Undo applies the most recent undoable action of either kind (a deleted waypoint, or a block removed by an N tap) and selects that waypoint; it is inactive (tooltip says why) if it can't be applied now (the blocks belong to another waypoint, or the block's waypoint is gone or full).
- **Right**: an editor for the selection (two columns at ≥ 300 px, else one column; it scrolls). With **2+ waypoints selected** it shows `N waypoints selected`, a grey hint (`Ctrl+click toggles a waypoint, Shift+click selects a range`) and a **Make group** button (`WaypointManager.createGroup` with the selected waypoints in list order; waypoints already in a group move out of it). If they span dimensions the button is inactive with the tooltip `All waypoints of a group must be in the same dimension`. The new group is selected (group editor).
- **Group editor** (a group row selected): `Group of n waypoints` (the member count), Name `EditBox`, Color (the same 16 swatches and hex box), then `Logo` (Block / None) and `Text` (on/off) side by side, the block button (opens `BlockPickerScreen`) under Logo, then a **Combine distance** slider (`Combine distance: 15 blocks`; 5–200 blocks, 1-block steps up to 50, then 5-block steps; `WaypyConfig.COMBINE_DISTANCE_STEPS`) and an **Ungroup** button (dissolves the group, the members stay as normal waypoints and the first of them is selected). One column when narrow.
- **Waypoint editor** (a single waypoint selected):
  - Name `EditBox`.
  - Position: three small `EditBox`es labelled X, Y, Z on one row (each on its own row when the editor is too narrow for 40 px boxes) with the anchor's coordinates (the first block of a clump; the label says so). Typing a whole number (optional leading `-`) moves the waypoint through `WaypointManager.move` as you type, like the hex box: a clump is translated as a whole. Invalid input turns the text red (tooltip "Enter a whole number") and isn't applied; a move onto a block of another waypoint in the same dimension is refused, red, with a tooltip naming that waypoint. Y is clamped so every block stays within the current level's build height (the box then shows the clamped value and a tooltip).
  - Color: a row of 16 dye-color swatches and a hex `EditBox` (`#RRGGBB`).
  - Style `CycleButton`: Beacon / Block Selection. Inactive for clumps (2+ blocks; it follows the current block count), tooltip "Clumps always use Block Selection".
  - Logo `CycleButton`: Block / None.
    - Block → a button showing the current block icon, which opens `BlockPickerScreen` (under Logo, in the right column). Hidden for None.
  - "Visible" and "Text" (on/off: the name/distance label) toggles side by side.
  - If Block Selection is the style: an Opacity slider (0–100%) and a "Render through blocks" `Checkbox`.
  - For a group member: a grey `Group: <name>` line with a **Remove from group** button beside it (in the right column; under it when narrow). Removing the second-to-last member dissolves the group.
  - For clumps, a grey info line `Clump: <n> blocks` at the end (no per-block editing).
- Header: a `Settings...` button (70–90 px wide, 20 px high) level with the title, right-aligned to the editor column's right edge; it moves right / narrows so it never overlaps the title on small screens.
- Footer: `Done` (200 px).
- Changes apply live and save when the screen closes.

`BlockPickerScreen`: a vanilla-styled search `EditBox` above a scrollable grid of item icons for all `BuiltInRegistries.BLOCK` entries that have an item. Clicking one sets the logo and returns.

## Settings (`dev.waypy.config`, `gui.WaypySettingsScreen`)

Global client settings in `<gameDir>/config/waypy.json` (`WaypyConfig`, Gson). Loaded at client init; saved when the settings screen closes. A missing file means defaults; an unreadable or corrupt file logs a warning and the defaults are used. Values are clamped/snapped on load. Unknown fields (e.g. the removed `minIconSize`) are ignored.

| Setting | JSON field | Default | Range |
|---|---|---|---|
| Waypoint Render Distance | `waypointRenderDistance` | 512 | 16–4096 blocks or Unlimited (`-1`). Slider steps: 16 up to 256, 32 up to 512, 64 up to 1024, 128 up to 2048, 256 up to 4096; the top position is Unlimited. |
| Icon Size | `iconSize` | 100 | 50–200 %, 5 % steps |
| Shrink with Distance | `shrinkWithDistance` | true | on/off (perspective, fixed 35 % floor; see Marker) |
| Labels | `labels` | `ALWAYS` | `ALWAYS` / `LOOKING_AT` / `OFF` |
| Distance in Label | `showDistance` | true | on/off |
| Group Combine Distance | `groupCombineDistance` | 15 | 5–200 blocks; 1-block steps up to 50, then 5-block steps. Only the default for NEW groups (each group has its own combine distance, set in the menu). |

`WaypySettingsScreen` extends vanilla `OptionsSubScreen` (title header "Waypy Settings", `OptionsList`, Done footer) and uses vanilla `OptionInstance`s: `SliderableEnum` for the render distance, `IntRange.xmap` sliders for the percentages, `createBoolean` and `Enum` cycle buttons for the rest. Rows: render distance (310 px), then two 150 px columns: Icon Size | Shrink with Distance, Labels | Distance in Label, then Group Combine Distance (310 px, a `SliderableEnum` over `COMBINE_DISTANCE_STEPS`). It is opened from the menu's `Settings...` button. No Mod Menu integration (no 26.2 Mod Menu build could be verified).

All user-visible strings go in `assets/waypy/lang/en_us.json` under the `waypy.` / `key.waypy.` namespaces (settings under `waypy.settings.*`).
