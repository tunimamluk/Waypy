# Waypy — design spec

Client-side Fabric waypoint mod for Minecraft **26.2** (Java 25, Mojang names — 26.x is unobfuscated).

- Mod id: `waypy` · Base package: `dev.waypy` · Language: Java
- Entrypoint: `dev.waypy.WaypyClient` (`client` entrypoint only; `environment: "client"`)

## User-facing behaviour

| Key (rebindable, category "Waypy") | Action |
|---|---|
| `N` — `key.waypy.create` | Create a new waypoint at the block the player is **standing on** (block below the feet; if that is air, the first non-air block straight down; if none, `No block below you`). Make it the **selected** waypoint and show an action-bar message `Waypoint "<name>" created`. |
| `Shift` + `N` | Same as `N`, but the block comes from a raycast from the camera (max 512 blocks, ignores fluids). On a miss, show `No block in sight`. |
| `M` — `key.waypy.menu` | Open the Waypy menu with the **selected** waypoint pre-selected. |

New waypoint defaults: name `Waypoint <n>` (n = next free number), color cycles through a palette of vanilla dye colors, style `BEACON`, logo `BLOCK` using the item of the block the waypoint is on (`minecraft:compass` only for blocks without an item, e.g. fire), opacity 0.4, throughBlocks true, visible true. The N key and the menu's "+ New here" share this code (`WaypyClient.createWaypoint`).

## Data model (`dev.waypy.waypoint`)

```java
public final class Waypoint {
    UUID id;
    String name;                 // max 32 chars
    BlockPos pos;
    ResourceKey<Level> dimension; // serialized as identifier string, e.g. "minecraft:overworld"
    int color;                   // 0xRRGGBB
    WaypointStyle style;         // BEACON | BLOCK_HIGHLIGHT
    LogoType logoType;           // BLOCK | NONE
    Identifier logoBlock;        // item/block id used when logoType == BLOCK (kept while NONE)
    float highlightOpacity;      // 0..1, BLOCK_HIGHLIGHT only
    boolean throughBlocks;       // BLOCK_HIGHLIGHT only: render without depth test
    boolean visible;
}
enum WaypointStyle { BEACON, BLOCK_HIGHLIGHT }
enum LogoType { BLOCK, NONE }
```

`WaypointManager` (singleton, `WaypointManager.get()`):
- `List<Waypoint> all()`, `List<Waypoint> inDimension(ResourceKey<Level>)`
- `Waypoint create(BlockPos, ResourceKey<Level>, Identifier logoBlock)`
- `void remove(UUID)`, `void markDirty()` / `save()`
- `Optional<Waypoint> selected()`, `void select(UUID)`
- Loads on world join, saves on change and on disconnect.

Storage: `<gameDir>/waypy/<worldKey>.json` (Gson). Migration: a stored `logoType` of `TEXT` (the removed text logo) or any unknown value loads as `BLOCK` with the stored `logoBlock`; an old `logoText` field is ignored. `worldKey` = sanitized singleplayer save folder name prefixed `sp_`, or server address prefixed `mp_`. Waypoints are per world, filtered by dimension at render time.

## Rendering (`dev.waypy.render`)

Only waypoints with `visible == true` in the player's current dimension.

- **BEACON**: vanilla-looking beacon beam (vanilla beam texture, tinted with `color`) from the waypoint block up to the build height. Vanilla depth behaviour, so terrain hides it the way it hides a real beam.
  - Not limited by the game's render distance and independent of chunk loading (only by the Waypoint render distance setting, below). Beams past the start of the render-distance fog use a fog-free copy of the vanilla beam pipeline (`waypy:core/beacon_beam_no_fog`); beams that would reach past the camera's far plane are scaled uniformly about the camera so they stay on the same view rays.
- **BLOCK_HIGHLIGHT**: a tinted filled box over the block at `highlightOpacity`, plus a solid outline in `color`. If `throughBlocks`, both draw without depth testing (visible through terrain).
- **Waypoint render distance**: beams and highlights whose block centre is farther from the camera (3D) than the setting are not drawn, for both styles and including the far-beam path; the logo and label remain. Highlights are additionally limited to the game's render distance.
- **Logo** (both styles): a HUD marker projected from a point above the waypoint (1.5 blocks above the block for highlight, 3 for beacon). It is always visible through blocks. Below it is a small label: `name` and distance in metres (the distance can be turned off).
  - `BLOCK` logo → the item icon of `logoBlock`. `NONE` → no logo; the label stays where it would be with a logo.
  - **Size**: `scale = distanceScale(d) × iconSize`. `distanceScale` is 1.0 at d ≤ 16 blocks and `minIconSize` (default 0.5) at d ≥ 256, linear in ln(d) in between: `t = (ln d − ln 16) / (ln 256 − ln 16)`, `distanceScale = lerp(t, 1, min)`; it is 1.0 when shrinking is off. Scaling goes through the GUI pose matrix (as vanilla scales GUI items). The logo scale is rounded to 1/16 framebuffer pixel per icon (the icon covers whole pixels); the label scale is rounded to whole framebuffer pixels per font texel (crisp text) and never goes below 0.5 (the next whole-pixel step ≥ 0.5 at the current GUI scale). Positions are snapped to framebuffer pixels.
  - **Labels** (setting): Always / Looking At / Off. Always: labels never overlap — using the scaled rectangles, a label that would overlap an already placed label or the logo of a nearer waypoint is hidden (the farther waypoint loses). In every mode but Off, the label of the marker nearest the crosshair (within 40 GUI px) is always shown, on top; with Looking At it is the only label.

## Menu (`dev.waypy.gui`) — vanilla styled

Use only vanilla widgets and textures: `Button`, `EditBox`, `AbstractSliderButton`, `CycleButton`, `Checkbox`, vanilla list widgets, the menu background/blur and the standard header/footer separators. No custom textures.

`WaypointScreen` (title "Waypy"):
- **Left**: a scrollable list of waypoints in the current world (color swatch, name, distance). The selected waypoint is highlighted. Buttons below it: `+ New here` (waypoint at the player's feet) and `Delete`.
- **Right**: an editor for the selected waypoint.
  - Name `EditBox`.
  - Color: a row of 16 dye-color swatches and a hex `EditBox` (`#RRGGBB`).
  - Style `CycleButton`: Beacon / Block Selection.
  - Logo `CycleButton`: Block / None.
    - Block → a button showing the current block icon, which opens `BlockPickerScreen`. Hidden for None.
  - If Block Selection is the style: an Opacity slider (0–100%) and a "Render through blocks" `Checkbox`.
  - A "Visible" toggle.
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
