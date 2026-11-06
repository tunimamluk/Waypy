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

New waypoint defaults: name `Waypoint <n>` (n = next free number), color cycles through a palette of vanilla dye colors, style `BEACON`, logo `BLOCK` using the item of the targeted block (fallback `minecraft:compass`... any non-air item), opacity 0.4, throughBlocks true, visible true.

## Data model (`dev.waypy.waypoint`)

```java
public final class Waypoint {
    UUID id;
    String name;                 // max 32 chars
    BlockPos pos;
    ResourceKey<Level> dimension; // serialized as identifier string, e.g. "minecraft:overworld"
    int color;                   // 0xRRGGBB
    WaypointStyle style;         // BEACON | BLOCK_HIGHLIGHT
    LogoType logoType;           // BLOCK | TEXT
    Identifier logoBlock;        // item/block id used when logoType == BLOCK
    String logoText;             // max 8 chars, used when logoType == TEXT
    float highlightOpacity;      // 0..1, BLOCK_HIGHLIGHT only
    boolean throughBlocks;       // BLOCK_HIGHLIGHT only: render without depth test
    boolean visible;
}
enum WaypointStyle { BEACON, BLOCK_HIGHLIGHT }
enum LogoType { BLOCK, TEXT }
```

`WaypointManager` (singleton, `WaypointManager.get()`):
- `List<Waypoint> all()`, `List<Waypoint> inDimension(ResourceKey<Level>)`
- `Waypoint create(BlockPos, ResourceKey<Level>, Identifier logoBlock)`
- `void remove(UUID)`, `void markDirty()` / `save()`
- `Optional<Waypoint> selected()`, `void select(UUID)`
- Loads on world join, saves on change and on disconnect.

Storage: `<gameDir>/waypy/<worldKey>.json` (Gson). `worldKey` = sanitized singleplayer save folder name prefixed `sp_`, or server address prefixed `mp_`. Waypoints are per world, filtered by dimension at render time.

## Rendering (`dev.waypy.render`)

Only waypoints with `visible == true` in the player's current dimension.

- **BEACON**: vanilla-looking beacon beam (vanilla beam texture, tinted with `color`) from the waypoint block up to the build height. Vanilla depth behaviour, so terrain hides it the way it hides a real beam.
- **BLOCK_HIGHLIGHT**: a tinted filled box over the block at `highlightOpacity`, plus a solid outline in `color`. If `throughBlocks`, both draw without depth testing (visible through terrain).
- **Logo** (both styles): a camera-facing billboard floating above the waypoint (above the block for highlight, a few blocks above it for beacon). It is always visible through blocks and scales with distance so it stays readable. Below it is a small label: `name` and distance in metres.
  - `BLOCK` logo → the item icon of `logoBlock`.
  - `TEXT` logo → `logoText` in the waypoint color with a dark backing plate.

## Menu (`dev.waypy.gui`) — vanilla styled

Use only vanilla widgets and textures: `Button`, `EditBox`, `AbstractSliderButton`, `CycleButton`, `Checkbox`, vanilla list widgets, the menu background/blur and the standard header/footer separators. No custom textures.

`WaypointScreen` (title "Waypy"):
- **Left**: a scrollable list of waypoints in the current world (color swatch, name, distance). The selected waypoint is highlighted. Buttons below it: `+ New here` (waypoint at the player's feet) and `Delete`.
- **Right**: an editor for the selected waypoint.
  - Name `EditBox`.
  - Color: a row of 16 dye-color swatches and a hex `EditBox` (`#RRGGBB`).
  - Style `CycleButton`: Beacon / Block Selection.
  - Logo `CycleButton`: Block / Text.
    - Block → a button showing the current block icon, which opens `BlockPickerScreen`.
    - Text → an 8-char `EditBox`.
  - If Block Selection is the style: an Opacity slider (0–100%) and a "Render through blocks" `Checkbox`.
  - A "Visible" toggle.
- Footer: `Done`.
- Changes apply live and save when the screen closes.

`BlockPickerScreen`: a vanilla-styled search `EditBox` above a scrollable grid of item icons for all `BuiltInRegistries.BLOCK` entries that have an item. Clicking one sets the logo and returns.

All user-visible strings go in `assets/waypy/lang/en_us.json` under the `waypy.` / `key.waypy.` namespaces.
