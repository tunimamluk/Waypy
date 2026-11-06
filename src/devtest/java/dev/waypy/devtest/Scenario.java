package dev.waypy.devtest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import dev.waypy.CreateKeyHandler;
import dev.waypy.WaypyClient;
import dev.waypy.config.LabelMode;
import dev.waypy.config.WaypyConfig;
import dev.waypy.gui.WaypointScreen;
import dev.waypy.gui.WaypySettingsScreen;
import dev.waypy.waypoint.LogoType;
import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointGroup;
import dev.waypy.waypoint.WaypointManager;
import dev.waypy.waypoint.WaypointStyle;

/**
 * The scripted screenshot scenario, driven one step at a time from the client tick.
 *
 * <p>Each step waits for an optional readiness condition (with its own timeout), runs its action, then waits
 * {@code settleTicks} before the next step. Screenshots are written to {@code <runDir>/screenshots/<name>.png}.
 *
 * <p>Settings: the scenario sets {@code config/waypy.json} explicitly (defaults, then per-shot variations, then
 * the defaults again at the end), so earlier runs or manual edits never change the screenshots.
 *
 * <p>World layout (superflat, grass surface at y=-61, player feet at y=-60, camera facing south / +Z, so +X is
 * screen-left and -X is screen-right):
 * Waypoints are created with the defaults (Block Selection, depth-tested, text off) and then configured; the ones
 * marked "text" have {@code showText} on so the label rules and label smoothness stay covered.
 * <ul>
 *   <li>"Diamond Beacon": BEACON, BLOCK logo, red, text, on a diamond block on top of a hill, ~37 blocks away on the right.</li>
 *   <li>"Lapis Beacon": BEACON, BLOCK logo (lapis), light blue, text, on a lapis block ~64 blocks away on the left.</li>
 *   <li>"Hidden Cache": BLOCK_HIGHLIGHT, throughBlocks=true, lime, text, behind a stone-brick wall ~24 blocks ahead.</li>
 *   <li>"Gold Block": BLOCK_HIGHLIGHT, throughBlocks=false, logo NONE (pin shows the initial "G"), yellow (light colour: dark initial), text, on a gold block ~13 blocks ahead on the left.</li>
 *   <li>"Far Beacon": BEACON, BLOCK logo, purple, text OFF (logo only), ~200 blocks ahead, far outside the render
 *       distance (its chunk is not loaded), to check that far beams are drawn without fog.</li>
 * </ul>
 * Clumps, behind the spawn (north, -Z), built through the N key's code path ({@link CreateKeyHandler}) and seen from
 * a stone pillar at (-1, -1) facing north (+X is screen-right):
 * <ul>
 *   <li>"Floor Patch": BLOCK_HIGHLIGHT, depth-tested, a flat 4×3 patch of smooth stone set into the ground.</li>
 *   <li>"Stair Stack": oak-plank stairs (heights 1/2/3, two deep) with a two-block L foot; set to BEACON right after
 *       the press, so growing to two blocks must force it back to BLOCK_HIGHLIGHT (a clump can't be a beacon).</li>
 * </ul>
 * "Walk Test" (east of the spawn, text on) is used alone for the walking-away (shrink smoothness) screenshots.
 * Group "Mine Area" (east of the spawn, out of the main view), made with {@link WaypointManager#createGroup} (the menu's
 * "Make group"): "Mine Entrance" (iron block), "Ore Vein" (a 3-block coal-ore clump) and "Mine Storage" (a barrel,
 * BEACON), all with text on. Seen near (members) and from beyond its 15-block combine distance (one group pin).
 */
final class Scenario {
	private static final int SURFACE_Y = -61; // top grass block of the default superflat
	private static final int FEET_Y = SURFACE_Y + 1;
	private static final int STEP_TIMEOUT_TICKS = 20 * 60;
	private static final int MAX_CHUNK_WAIT_TICKS = 20 * 45;

	private record Step(String name, BooleanSupplier ready, Runnable action, int settleTicks) {
	}

	private final Minecraft mc;
	private final List<Step> steps = new ArrayList<>();
	private int index;
	private int waitTicks;
	private boolean actionDone;
	private int settleLeft;
	private volatile int pendingScreenshots;
	private final List<String> screenshotFailures = new ArrayList<>();

	private Waypoint beaconBlock;
	private Waypoint beaconLapis;
	private Waypoint highlightThrough;
	private Waypoint highlightSolid;
	private Waypoint farBeacon;
	private Waypoint floorPatch;
	private Waypoint stairStack;
	private Waypoint walkTest;
	private Waypoint mineEntrance;
	private Waypoint oreVein;
	private Waypoint mineStorage;
	private WaypointGroup mineArea;
	/** A waypoint in the Nether, to check that a group can't span dimensions. */
	private Waypoint netherProbe;
	/** The waypoint deleted for the real-time undo expiry check, and when. */
	private Waypoint expiryProbe;
	private List<BlockPos> expiryProbeBlocks;
	private long expiryProbeDeletedAt;

	Scenario(Minecraft mc) {
		this.mc = mc;
		build();
		DevtestClient.log("scenario has " + steps.size() + " steps");
	}

	// ---------------------------------------------------------------- script

	private void build() {
		step("settle after join", () -> true, () -> {
			// Lets the failure path (non-zero exit) be checked: WAYPY_DEVTEST_FAIL=1 scripts/devtest-screenshots.sh
			if (System.getenv("WAYPY_DEVTEST_FAIL") != null) throw new IllegalStateException("forced failure (WAYPY_DEVTEST_FAIL)");
		}, 40);
		// Start from default settings whatever config/waypy.json holds (and save them: the file never drifts).
		step("default settings", () -> true, () -> setConfig(c -> {
		}), 1);
		step("world setup commands", () -> true, this::setupWorld, 40);
		step("position player", () -> true, () -> {
			// yaw 0 = facing south (+Z); slight downward pitch so the ground and the beams are both in frame.
			command("tp @a 0.5 " + FEET_Y + " 0.5 0 8");
		}, 40);
		step("create waypoints", () -> mc.player != null && mc.level != null, this::createWaypoints, 20);
		step("create clumps (N key path)", () -> true, this::createClumps, 5);
		step("N hold rules (skip, cap, limit, cancel)", () -> true, this::checkKeyRules, 5);
		step("new waypoint defaults", () -> true, this::checkDefaults, 2);
		step("undo via N, forced expiry", () -> true, this::checkUndo, 2);
		step("N extend, erase strokes, tap (no block undo)", () -> true, this::checkExtendOrRemove, 2);
		step("groups: create, dimensions, dissolve, undo", () -> true, this::checkGroups, 2);
		step("create group Mine Area", () -> true, this::createMineArea, 2);
		step("start real-time undo expiry probe", () -> true, this::startExpiryProbe, 2);
		step("wait for chunks", chunksReady(), () -> {
		}, 60);
		screenshot("01_world");

		step("position player (high)", () -> true, () -> command("tp @a -18.5 " + (FEET_Y + 12) + " -10.5 -25 16"), 40);
		step("wait for chunks (high)", chunksReady(), () -> {
		}, 60);
		screenshot("02_world_high");
		step("back to ground position", () -> true, () -> command("tp @a 0.5 " + FEET_Y + " 0.5 0 8"), 20);

		// Settings variations, from the ground position. Each one starts from the defaults.
		configShot("03_world_no_shrink", c -> c.shrinkWithDistance = false);
		// 128 blocks: the ~200-block "Far Beacon" loses its beam but keeps its logo and label.
		configShot("04_world_render_distance_128", c -> c.waypointRenderDistance = 128);
		configShot("05_world_labels_looking_at", c -> c.labels = LabelMode.LOOKING_AT);
		configShot("06_world_big_icons_no_distance", c -> {
			c.iconSize = 150;
			c.showDistance = false;
		});
		step("restore default settings", () -> true, () -> setConfig(c -> {
		}), 5);

		// Clumps, seen from the pillar north of the spawn.
		step("position player (clumps)", this::noPendingScreenshots, () -> command("tp @a -0.5 " + (FEET_Y + 3) + " -0.5 180 35"), 40);
		step("wait for chunks (clumps)", chunksReady(), () -> {
		}, 40);
		screenshot("07_clumps");
		step("clump highlights through blocks", this::noPendingScreenshots, () -> {
			stairStack.throughBlocks = true;
			stairStack.highlightOpacity = 0.3f;
			floorPatch.throughBlocks = true;
		}, 5);
		screenshot("08_clumps_highlight");
		step("clump highlights depth-tested", this::noPendingScreenshots, () -> {
			stairStack.throughBlocks = false;
			floorPatch.throughBlocks = false;
		}, 5);
		screenshot("09_clumps_highlight_depth");
		step("back to ground position (after clumps)", () -> true, () -> command("tp @a 0.5 " + FEET_Y + " 0.5 0 8"), 20);

		walkingAwayShots();
		groupWorldShots();

		menuShot("10_menu_beacon_block", () -> beaconBlock);
		menuShot("11_menu_beacon_lapis", () -> beaconLapis);
		menuShot("12_menu_highlight_block", () -> highlightThrough);
		menuShot("13_menu_highlight_no_logo", () -> highlightSolid);
		menuShot("14_menu_no_selection", () -> null);
		menuShot("15_menu_clump", () -> floorPatch);
		menuMoveAndUndo();
		menuGroups();

		step("open BlockPickerScreen", () -> true, this::openBlockPicker, 30);
		step("screenshot block picker", this::noPendingScreenshots, () -> {
			if (mc.gui.screen() != null && mc.gui.screen().getClass().getSimpleName().equals("BlockPickerScreen")) {
				takeScreenshot("20_block_picker");
			} else {
				DevtestClient.log("BlockPickerScreen not open; no block picker screenshot");
			}
		}, 5);
		step("close screens", () -> true, () -> mc.gui.setScreen(null), 10);

		step("open WaypySettingsScreen", this::noPendingScreenshots, () -> mc.gui.setScreen(new WaypySettingsScreen(new WaypointScreen(beaconBlock))), 30);
		screenshot("30_settings");
		step("settings: shrink off", this::noPendingScreenshots, () -> {
			setConfig(c -> c.shrinkWithDistance = false);
			mc.gui.setScreen(new WaypySettingsScreen(new WaypointScreen(beaconBlock)));
		}, 30);
		screenshot("31_settings_shrink_off");
		step("close settings", this::noPendingScreenshots, () -> mc.gui.setScreen(null), 10);
		step("open KeyBindsScreen", this::noPendingScreenshots, this::openKeyBinds, 30);
		screenshot("32_key_binds");
		step("close key binds", this::noPendingScreenshots, () -> mc.gui.setScreen(null), 10);
		step("config: an old minIconSize field is ignored", () -> true, Scenario::checkOldConfigField, 2);
		step("restore default settings (end)", () -> true, () -> setConfig(c -> {
		}), 5);

		step("real-time undo expiry", this::expiryProbeDue, this::checkExpiryProbe, 2);
		step("load migrations", this::noPendingScreenshots, this::checkLoadMigrations, 2);
		step("finish", this::noPendingScreenshots, () -> {
			WaypointManager.get().save();
			if (!screenshotFailures.isEmpty()) {
				DevtestClient.fail("screenshot(s) failed: " + screenshotFailures, null);
			} else {
				DevtestClient.succeed(mc);
			}
		}, 0);
	}

	private void setupWorld() {
		for (String cmd : List.of(
				"gamerule send_command_feedback false",
				"gamerule log_admin_commands false",
				"gamerule advance_time false",
				"gamerule advance_weather false",
				"gamerule spawn_mobs false",
				"gamerule spawn_monsters false",
				"gamerule spawn_patrols false",
				"gamerule spawn_phantoms false",
				"gamerule spawn_wandering_traders false",
				"difficulty peaceful",
				"time set 6000",
				"weather clear",
				"gamemode creative @a",
				"kill @e[type=!player]",
				// Clear the area (in case of earlier runs) and build the props.
				// (split: a single fill may modify at most 32768 blocks)
				"fill -30 " + FEET_Y + " -15 30 " + (FEET_Y + 12) + " 20 air",
				"fill -30 " + FEET_Y + " 21 30 " + (FEET_Y + 12) + " 55 air",
				"fill -30 " + FEET_Y + " 56 30 " + (FEET_Y + 12) + " 70 air",
				// Wall that hides the through-blocks highlight.
				"fill -3 " + FEET_Y + " 20 3 " + (FEET_Y + 4) + " 20 stone_bricks",
				// Small hill on the right (-X).
				"fill -26 " + FEET_Y + " 26 -16 " + (FEET_Y + 2) + " 34 grass_block",
				"fill -24 " + (FEET_Y + 3) + " 28 -18 " + (FEET_Y + 4) + " 32 grass_block",
				// Pedestals.
				"setblock -21 " + (FEET_Y + 5) + " 30 diamond_block",
				"setblock 6 " + FEET_Y + " 12 gold_block",
				"setblock 16 " + FEET_Y + " 62 lapis_block",
				// Clump props behind the spawn: a smooth-stone floor patch, oak-plank stairs with an L foot,
				// and a stone pillar to view them from.
				"fill -6 " + SURFACE_Y + " -9 -3 " + SURFACE_Y + " -7 smooth_stone",
				"fill 2 " + FEET_Y + " -9 2 " + FEET_Y + " -6 oak_planks",
				"fill 3 " + FEET_Y + " -9 3 " + (FEET_Y + 1) + " -8 oak_planks",
				"fill 4 " + FEET_Y + " -9 4 " + (FEET_Y + 2) + " -8 oak_planks",
				"fill -1 " + FEET_Y + " -1 -1 " + (FEET_Y + 2) + " -1 stone",
				// Group "Mine Area" props, east of the spawn: an iron block, a coal-ore vein and a barrel.
				"fill 36 " + FEET_Y + " 10 50 " + (FEET_Y + 8) + " 30 air",
				"setblock 40 " + FEET_Y + " 20 iron_block",
				"fill 44 " + FEET_Y + " 22 46 " + FEET_Y + " 22 coal_ore",
				"setblock 42 " + FEET_Y + " 25 barrel")) {
			command(cmd);
		}
	}

	private void createWaypoints() {
		WaypointManager wm = WaypointManager.get();
		for (Waypoint w : wm.all()) wm.remove(w.id);
		wm.expireDeletions(true); // otherwise N on an old waypoint's block would restore it (undo)
		var dim = mc.level.dimension();

		beaconBlock = wm.create(new BlockPos(-21, FEET_Y + 5, 30), dim, Identifier.withDefaultNamespace("diamond_block"));
		beaconBlock.setName("Diamond Beacon");
		beaconBlock.color = 0xE53935;
		beaconBlock.style = WaypointStyle.BEACON;
		beaconBlock.logoType = LogoType.BLOCK;
		beaconBlock.showText = true;

		beaconLapis = wm.create(new BlockPos(16, FEET_Y, 62), dim, Identifier.withDefaultNamespace("lapis_block"));
		beaconLapis.setName("Lapis Beacon");
		beaconLapis.color = 0x3AB3DA;
		beaconLapis.style = WaypointStyle.BEACON;
		beaconLapis.logoType = LogoType.BLOCK;
		beaconLapis.showText = true;

		highlightThrough = wm.create(new BlockPos(0, SURFACE_Y, 24), dim, Identifier.withDefaultNamespace("chest"));
		highlightThrough.setName("Hidden Cache");
		highlightThrough.color = 0x80C71F;
		highlightThrough.style = WaypointStyle.BLOCK_HIGHLIGHT;
		highlightThrough.logoType = LogoType.BLOCK;
		highlightThrough.throughBlocks = true;
		highlightThrough.highlightOpacity = 0.4f;
		highlightThrough.showText = true;

		highlightSolid = wm.create(new BlockPos(6, FEET_Y, 12), dim, Identifier.withDefaultNamespace("gold_block"));
		highlightSolid.setName("Gold Block");
		highlightSolid.color = 0xFED83D; // yellow: a light badge, so the dark initial is covered
		highlightSolid.style = WaypointStyle.BLOCK_HIGHLIGHT;
		highlightSolid.logoType = LogoType.NONE;
		highlightSolid.throughBlocks = false;
		highlightSolid.highlightOpacity = 0.6f;
		highlightSolid.showText = true;

		farBeacon = wm.create(new BlockPos(10, FEET_Y, 200), dim, Identifier.withDefaultNamespace("amethyst_block"));
		farBeacon.setName("Far Beacon");
		farBeacon.color = 0x8932B8;
		farBeacon.style = WaypointStyle.BEACON;
		farBeacon.logoType = LogoType.BLOCK;
		farBeacon.showText = false; // logo only

		walkTest = wm.create(new BlockPos(60, SURFACE_Y, 0), dim, Identifier.withDefaultNamespace("emerald_block"));
		walkTest.setName("Walk Test");
		walkTest.color = 0x5E7C16;
		walkTest.showText = true;
		walkTest.visible = false; // only shown for the walking-away screenshots

		wm.select(beaconBlock.id);
		wm.markDirty();
		DevtestClient.log("created " + wm.all().size() + " waypoints in " + dim.identifier());
	}

	/**
	 * Builds the two clumps through the N key's handler: {@code press} on the first block (creates the waypoint),
	 * {@code extend} once per further block (as each held tick does), then {@code release}.
	 */
	private void createClumps() {
		CreateKeyHandler keys = WaypyClient.CREATE_KEY_HANDLER;
		List<BlockPos> floor = new ArrayList<>();
		for (int z = -9; z <= -7; z++) {
			for (int x = -6; x <= -3; x++) floor.add(new BlockPos(x, SURFACE_Y, z));
		}
		floorPatch = holdOver(keys, floor, false);
		floorPatch.setName("Floor Patch");
		floorPatch.color = 0x169C9C; // cyan
		floorPatch.throughBlocks = false;
		floorPatch.highlightOpacity = 0.35f;

		List<BlockPos> stairs = new ArrayList<>();
		// L foot first (toward the viewer), then the steps, bottom to top.
		stairs.add(new BlockPos(2, FEET_Y, -6));
		stairs.add(new BlockPos(2, FEET_Y, -7));
		for (int z = -8; z >= -9; z--) {
			for (int x = 2; x <= 4; x++) {
				for (int y = FEET_Y; y <= FEET_Y + (x - 2); y++) stairs.add(new BlockPos(x, y, z));
			}
		}
		stairStack = holdOver(keys, stairs, true);
		stairStack.setName("Stair Stack");
		stairStack.color = 0xF38BAA; // pink
		check(stairStack.style == WaypointStyle.BLOCK_HIGHLIGHT && !stairStack.isBeacon(),
				"Stair Stack (BEACON at the press) was not forced to BLOCK_HIGHLIGHT when it grew: " + stairStack.style);

		WaypointManager.get().select(beaconBlock.id);
		WaypointManager.get().markDirty();
		check(floorPatch.size() == 12, "Floor Patch has " + floorPatch.size() + " blocks, expected 12");
		check(stairStack.size() == 14, "Stair Stack has " + stairStack.size() + " blocks, expected 14");
		check(floorPatch.logoBlock.toString().equals("minecraft:smooth_stone"), "Floor Patch logo is " + floorPatch.logoBlock);
		DevtestClient.log("clumps: Floor Patch " + floorPatch.size() + " blocks (centre " + floorPatch.centre() + "), Stair Stack "
				+ stairStack.size() + " blocks (top y " + stairStack.topY() + ", style " + stairStack.style + " forced from BEACON)");
	}

	/**
	 * Simulates pressing N on the first block and holding it while the target moves over the others.
	 *
	 * @param beaconAtPress set the new waypoint to BEACON right after the press (growing must force it back)
	 */
	private Waypoint holdOver(CreateKeyHandler keys, List<BlockPos> targets, boolean beaconAtPress) {
		Waypoint w = keys.press(mc, targets.getFirst(), false);
		check(w != null, "press on a free block " + targets.getFirst() + " did not create a waypoint");
		if (beaconAtPress) w.style = WaypointStyle.BEACON;
		for (BlockPos pos : targets.subList(1, targets.size())) keys.extend(mc, pos);
		keys.release(mc);
		return w;
	}

	/** The hold rules: skip other waypoints, cap, a limit-hit hold or a cancelled hold is no tap. */
	private void checkKeyRules() {
		CreateKeyHandler keys = WaypyClient.CREATE_KEY_HANDLER;
		WaypointManager wm = WaypointManager.get();
		var dim = mc.level.dimension();

		// A clump held over blocks of other waypoints skips them silently.
		Waypoint temp = keys.press(mc, new BlockPos(-10, SURFACE_Y, -12), false);
		check(temp != null, "press did not create the temporary clump");
		keys.extend(mc, new BlockPos(-9, SURFACE_Y, -12));
		keys.extend(mc, new BlockPos(-6, SURFACE_Y, -9)); // Floor Patch
		keys.extend(mc, new BlockPos(-8, SURFACE_Y, -12));
		check(temp.size() == 3, "temp clump has " + temp.size() + " blocks, expected 3 (Floor Patch block skipped)");
		check(wm.waypointAt(new BlockPos(-6, SURFACE_Y, -9), dim) == floorPatch, "Floor Patch block was taken over");
		// Cap at 256 blocks.
		for (int i = 0; i < 300; i++) keys.extend(mc, new BlockPos(-14 + i % 10, SURFACE_Y - 1 - i / 10, -14));
		check(temp.size() == Waypoint.MAX_BLOCKS, "temp clump has " + temp.size() + " blocks, expected the cap " + Waypoint.MAX_BLOCKS);
		keys.release(mc);

		// A hold on a full clump that only hits the limit is an extend attempt, not a tap: nothing is removed.
		BlockPos target = temp.blocks().get(2);
		check(keys.press(mc, target, false) == temp && keys.hold() == CreateKeyHandler.Hold.EXTEND_OR_REMOVE,
				"N on a clump block did not start an extend-or-remove hold");
		keys.extend(mc, new BlockPos(-10, SURFACE_Y, -13)); // free, but the clump is full
		keys.release(mc);
		check(temp.size() == Waypoint.MAX_BLOCKS && temp.contains(target) && wm.waypointAt(target, dim) == temp,
				"release after hitting the limit removed a block");
		check(wm.waypointAt(new BlockPos(-10, SURFACE_Y, -13), dim) == null, "a full clump grew");
		check(!keys.isHolding(), "hold not ended by release");
		// A screen opening (or focus loss) cancels the hold: an interrupted tap removes nothing.
		keys.press(mc, target, false);
		keys.cancel();
		check(temp.size() == Waypoint.MAX_BLOCKS && temp.contains(target) && !keys.isHolding(), "a cancelled tap removed a block");
		// A tap removes only the pressed block.
		keys.press(mc, target, false);
		keys.release(mc);
		check(temp.size() == Waypoint.MAX_BLOCKS - 1 && !temp.contains(target) && wm.waypointAt(target, dim) == null
				&& wm.byId(temp.id).isPresent(), "tap on a full clump's block did not remove just that block");
		DevtestClient.log("ASSERT ok: limit-hit hold and cancelled tap remove nothing; tap removed block " + target + " from \""
				+ temp.name + "\" (" + temp.size() + " left); other-waypoint block skipped; cap " + Waypoint.MAX_BLOCKS + " enforced");
		wm.remove(temp.id);
		wm.expireDeletions(true);

		// Persistence: the clump blocks are written as [x, y, z] arrays.
		wm.save();
		try {
			java.nio.file.Path file = mc.gameDirectory.toPath().resolve("waypy").resolve(wm.worldKey() + ".json");
			String json = java.nio.file.Files.readString(file);
			var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
			int found = -1;
			for (var el : root.getAsJsonArray("waypoints")) {
				var o = el.getAsJsonObject();
				if (o.get("id").getAsString().equals(stairStack.id.toString())) found = o.getAsJsonArray("blocks").size();
			}
			check(found == stairStack.size(), "saved Stair Stack has " + found + " blocks");
			check(json.contains("\"blocks\": [\n"), "blocks array not in the expected compact format");
			DevtestClient.log("saved " + file.getFileName() + ": Stair Stack blocks=" + found);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("could not read the waypoint file", e);
		}
	}

	/** "+ New here" / N defaults: Block Selection, depth-tested, opacity 0.4, text off, visible, logo = the block. */
	private void checkDefaults() {
		WaypointManager wm = WaypointManager.get();
		CreateKeyHandler keys = WaypyClient.CREATE_KEY_HANDLER;
		Waypoint menu = WaypyClient.createWaypointAt(mc, new BlockPos(20, SURFACE_Y, -20)); // the "+ New here" path
		Waypoint key = keys.press(mc, new BlockPos(21, SURFACE_Y, -20), false);
		keys.release(mc);
		for (Waypoint w : new Waypoint[] {menu, key}) {
			check(w != null, "default waypoint not created");
			check(w.style == WaypointStyle.BLOCK_HIGHLIGHT, "default style is " + w.style);
			check(!w.throughBlocks, "default throughBlocks is true");
			check(w.highlightOpacity == 0.4f, "default opacity is " + w.highlightOpacity);
			check(!w.showText, "default showText is true");
			check(w.visible && w.logoType == LogoType.BLOCK && w.logoBlock.toString().equals("minecraft:grass_block"),
					"default visible/logo wrong: " + w.visible + " " + w.logoType + " " + w.logoBlock);
			wm.remove(w.id);
		}
		wm.expireDeletions(true);
		wm.select(beaconBlock.id);
		DevtestClient.log("defaults OK: BLOCK_HIGHLIGHT, throughBlocks=false, opacity=0.4, showText=false (N and + New here)");
	}

	/**
	 * Whole-waypoint undo through the real N handler: restore (same id, fields, blocks) and extend in the same press,
	 * a restore-press tap removes nothing, blocked restore, forced expiry.
	 */
	private void checkUndo() {
		WaypointManager wm = WaypointManager.get();
		CreateKeyHandler keys = WaypyClient.CREATE_KEY_HANDLER;
		var dim = mc.level.dimension();
		List<BlockPos> blocks = List.of(new BlockPos(22, SURFACE_Y, -22), new BlockPos(23, SURFACE_Y, -22), new BlockPos(23, SURFACE_Y, -23));
		Waypoint w = holdOver(keys, blocks, false);
		w.setName("Undo Me");
		w.color = 0x123456;
		w.logoType = LogoType.NONE;
		w.highlightOpacity = 0.7f;
		w.throughBlocks = true;
		w.showText = true;
		String before = describe(w);
		var id = w.id;

		// Delete the whole clump (as the menu's Delete does; N on a clump block only removes that block).
		wm.remove(id);
		check(wm.byId(id).isEmpty() && !wm.visibleIn(dim).contains(w), "remove did not remove the waypoint");
		for (BlockPos pos : blocks) check(wm.waypointAt(pos, dim) == null, "index still has " + pos + " after the delete");
		check(wm.lastDeleted().map(d -> d.waypoint().id.equals(id)).orElse(false), "deletion is not pending");

		// N (look-at mode this time) on another of its blocks restores it unchanged; the hold then extends it.
		check(keys.press(mc, blocks.get(2), true) == w && keys.hold() == CreateKeyHandler.Hold.RESTORED,
				"the undo press did not restore the waypoint into a RESTORED hold");
		Waypoint restored = wm.byId(id).orElse(null);
		check(restored == w, "N within 15 s did not restore the waypoint");
		check(describe(restored).equals(before), "restored waypoint differs:\n  " + describe(restored) + "\n  " + before);
		for (BlockPos pos : blocks) check(wm.waypointAt(pos, dim) == restored, "index lacks " + pos + " after the restore");
		check(wm.lastDeleted().isEmpty(), "restored deletion still pending");
		BlockPos extra = new BlockPos(24, SURFACE_Y, -22);
		keys.extend(mc, blocks.get(2)); // its own block: nothing
		keys.extend(mc, extra);
		keys.release(mc);
		check(w.size() == 4 && w.contains(extra) && wm.waypointAt(extra, dim) == w && w.contains(blocks.get(2)),
				"undo + hold did not extend the restored waypoint (or the release removed a block): " + w.blocks());
		check(wm.lastDeleted().isEmpty(), "the restore-press release left an undo entry");
		DevtestClient.log("ASSERT ok: whole-waypoint undo via N restored " + before + ", then the same hold extended it to "
				+ w.size() + " blocks; its release removed nothing");

		// Restore-press released at once (a tap on a restorable block) removes nothing either.
		wm.remove(id);
		check(keys.press(mc, blocks.get(1), false) == w, "second undo press did not restore");
		keys.release(mc);
		check(wm.byId(id).isPresent() && w.size() == 4 && w.contains(blocks.get(1)), "a restore-press tap removed something");
		DevtestClient.log("ASSERT ok: restore-press tap released at once removed nothing (" + w.size() + " blocks)");

		// A block claimed meanwhile blocks the restore: the press is handled normally (creates a waypoint).
		wm.remove(id);
		Waypoint claimer = wm.create(blocks.get(0), dim, null); // like "+ New here"
		Waypoint fresh = keys.press(mc, blocks.get(1), false);
		keys.release(mc);
		check(fresh != null && !fresh.id.equals(id) && keys.hold() == CreateKeyHandler.Hold.IDLE && wm.byId(id).isEmpty(),
				"restored although a block was claimed");
		wm.remove(claimer.id);
		wm.remove(fresh.id);

		// Forced expiry: gone for good, and N on its block creates a new waypoint.
		wm.expireDeletions(true);
		check(wm.lastDeleted().isEmpty() && wm.restore(id) == null, "expired deletion still restorable");
		Waypoint again = keys.press(mc, blocks.get(2), false);
		keys.release(mc);
		check(again != null && !again.id.equals(id) && again.size() == 1, "N after expiry did not create a new waypoint");
		wm.remove(again.id);
		wm.expireDeletions(true);
		wm.select(beaconBlock.id);
		DevtestClient.log("undo blocked by a claimed block; forced expiry permanent");
	}

	/**
	 * The N key on an existing waypoint, through the real handler (press / extend / release with explicit targets):
	 * the first target off the start block decides the hold. A free block = extend; a block of the waypoint itself =
	 * erase stroke (final block removals, a stroke over the last block deletes the waypoint, undoable as the original);
	 * a block of another waypoint is ignored. A tap removes the start block (final), or deletes a single-block
	 * waypoint (undoable); N on a removed block then creates a new waypoint.
	 */
	private void checkExtendOrRemove() {
		WaypointManager wm = WaypointManager.get();
		CreateKeyHandler keys = WaypyClient.CREATE_KEY_HANDLER;
		var dim = mc.level.dimension();
		wm.expireDeletions(true);
		BlockPos b32 = new BlockPos(32, SURFACE_Y, -32), b33 = new BlockPos(33, SURFACE_Y, -32);
		BlockPos b34 = new BlockPos(34, SURFACE_Y, -32), b35 = new BlockPos(35, SURFACE_Y, -32);

		// 1. Extend stroke: a single waypoint grows into a clump, starting the hold on its block.
		Waypoint w = keys.press(mc, b32, false);
		keys.release(mc);
		check(w != null && w.size() == 1 && keys.hold() == CreateKeyHandler.Hold.IDLE, "single waypoint not created");
		w.setName("Extend Me");
		w.style = WaypointStyle.BEACON; // a single block may be a beacon; growing must force Block Selection
		w.logoType = LogoType.NONE;
		check(keys.press(mc, b32, false) == w && keys.hold() == CreateKeyHandler.Hold.EXTEND_OR_REMOVE,
				"N on a waypoint block did not start an extend-or-remove hold");
		check(wm.byId(w.id).isPresent() && w.size() == 1, "the press on a waypoint changed it");
		keys.extend(mc, b32); // still on the start block: nothing, still undecided
		check(keys.addedBlocks() == 0 && keys.hold() == CreateKeyHandler.Hold.EXTEND_OR_REMOVE, "the start block decided the mode");
		keys.extend(mc, b33);
		check(keys.hold() == CreateKeyHandler.Hold.EXTEND, "a free block did not decide EXTEND: " + keys.hold());
		keys.extend(mc, b34);
		keys.extend(mc, b32); // back on its own block while extending: skipped, nothing removed
		keys.extend(mc, b35);
		keys.release(mc);
		check(w.blocks().equals(List.of(b32, b33, b34, b35)), "extend from the waypoint's block gave " + w.blocks());
		for (BlockPos pos : w.blocks()) check(wm.waypointAt(pos, dim) == w, "index lacks " + pos + " after the extend");
		check(w.style == WaypointStyle.BLOCK_HIGHLIGHT && !w.isBeacon(), "extended beacon not forced to Block Selection");
		check(wm.lastDeleted().isEmpty(), "an extend left an undo entry");
		DevtestClient.log("ASSERT ok: extend stroke from a waypoint's block (free block first -> EXTEND) -> " + w.size()
				+ "-block clump " + w.blocks() + ", own blocks skipped, BEACON forced to " + w.style);

		// 2. Erase stroke over 3 blocks, starting on the anchor: the anchor moves, index and mesh follow, fields stay.
		BlockPos b36 = new BlockPos(36, SURFACE_Y, -32), b37 = new BlockPos(37, SURFACE_Y, -32);
		keys.press(mc, b35, false);
		keys.extend(mc, b36);
		keys.extend(mc, b37);
		keys.release(mc);
		check(w.blocks().equals(List.of(b32, b33, b34, b35, b36, b37)), "setup extend gave " + w.blocks());
		String fields = fields(w);
		int version = w.version();
		check(keys.press(mc, b32, false) == w && keys.hold() == CreateKeyHandler.Hold.EXTEND_OR_REMOVE, "erase press not on W");
		keys.extend(mc, b33); // a block of W first: ERASE, removes the start block (anchor) and b33
		check(keys.hold() == CreateKeyHandler.Hold.ERASE && keys.erasedBlocks() == 2, "W's block did not decide ERASE: "
				+ keys.hold() + " erased " + keys.erasedBlocks());
		BlockPos free = new BlockPos(33, SURFACE_Y, -31);
		keys.extend(mc, free); // a free block in ERASE mode: ignored, not added
		keys.extend(mc, b32); // the erased start block, now free: ignored
		keys.extend(mc, b34);
		keys.release(mc);
		check(w.blocks().equals(List.of(b35, b36, b37)) && w.anchor().equals(b35), "erase stroke gave " + w.blocks());
		check(wm.waypointAt(free, dim) == null && wm.waypointAt(b32, dim) == null && wm.waypointAt(b33, dim) == null
				&& wm.waypointAt(b34, dim) == null, "index still has erased (or a free) block");
		for (BlockPos pos : w.blocks()) check(wm.waypointAt(pos, dim) == w, "index lacks " + pos + " after the erase");
		check(w.version() != version, "version not bumped by the erase");
		check(fields(w).equals(fields), "fields changed by the erase:\n  " + fields(w) + "\n  " + fields);
		checkMesh(w);
		check(wm.lastDeleted().isEmpty() && keys.hold() == CreateKeyHandler.Hold.IDLE, "the erase stroke left an undo entry");
		DevtestClient.log("ASSERT ok: erase stroke over 3 blocks " + List.of(b32, b33, b34) + " -> " + w.blocks() + ", anchor moved to "
				+ w.anchor() + ", free blocks ignored, index and mesh (version " + w.version() + ") in sync, no undo entry");

		// 3. A tap on a clump block removes only it, final: N on that block then creates a NEW waypoint (no undo).
		keys.press(mc, b36, false);
		keys.release(mc);
		check(w.blocks().equals(List.of(b35, b37)) && wm.waypointAt(b36, dim) == null, "tap on b36 gave " + w.blocks());
		check(wm.lastDeleted().isEmpty(), "a block tap left an undo entry");
		checkMesh(w);
		Waypoint fresh = keys.press(mc, b36, false);
		check(fresh != null && fresh != w && keys.hold() == CreateKeyHandler.Hold.CREATED && !w.contains(b36)
				&& wm.waypointAt(b36, dim) == fresh, "N on a removed block did not create a new waypoint");
		keys.release(mc);
		BlockPos erasedBefore = b33; // erased by the stroke above: also just free
		Waypoint fresh2 = keys.press(mc, erasedBefore, false);
		keys.release(mc);
		check(fresh2 != null && fresh2 != w && fresh2.size() == 1 && !w.contains(erasedBefore), "N on an erased block restored it");
		DevtestClient.log("ASSERT ok: tap on clump block " + b36 + " removed it for good (no undo); N on it and on erased block "
				+ erasedBefore + " created new waypoints \"" + fresh.name + "\", \"" + fresh2.name + "\"");
		wm.remove(fresh.id);
		wm.remove(fresh2.id);
		wm.expireDeletions(true);

		// 4. An erase stroke that hits another waypoint's block first: ignored (undecided), then W's block -> ERASE.
		BlockPos c0 = new BlockPos(32, SURFACE_Y, -38), c1 = new BlockPos(33, SURFACE_Y, -38), c2 = new BlockPos(34, SURFACE_Y, -38);
		BlockPos c3 = new BlockPos(35, SURFACE_Y, -38), o0 = new BlockPos(33, SURFACE_Y, -39);
		Waypoint other = keys.press(mc, o0, false);
		keys.extend(mc, new BlockPos(34, SURFACE_Y, -39));
		keys.release(mc);
		Waypoint four = holdOver(keys, List.of(c0, c1, c2, c3), false);
		List<BlockPos> otherBefore = List.copyOf(other.blocks());
		String otherFields = fields(other);
		keys.press(mc, c1, false);
		keys.extend(mc, o0); // another waypoint's block first: ignored
		check(keys.hold() == CreateKeyHandler.Hold.EXTEND_OR_REMOVE, "another waypoint's block decided the mode: " + keys.hold());
		keys.extend(mc, c2); // W's block: ERASE
		check(keys.hold() == CreateKeyHandler.Hold.ERASE, "W's block after another waypoint did not decide ERASE: " + keys.hold());
		keys.extend(mc, new BlockPos(34, SURFACE_Y, -39)); // the other waypoint again: ignored in ERASE
		keys.release(mc);
		check(four.blocks().equals(List.of(c0, c3)), "erase after another waypoint's block gave " + four.blocks());
		check(other.blocks().equals(otherBefore) && fields(other).equals(otherFields) && wm.byId(other.id).isPresent()
				&& wm.waypointAt(o0, dim) == other, "the other waypoint was touched: " + other.blocks());
		DevtestClient.log("ASSERT ok: stroke over another waypoint's block first stayed undecided, then W's block -> ERASE; "
				+ four.name + " -> " + four.blocks() + ", " + other.name + " untouched " + other.blocks());

		// 5. An erase stroke over every block deletes W; N undo restores the ORIGINAL full waypoint (incl. its group).
		BlockPos e0 = new BlockPos(40, SURFACE_Y, -38), e1 = new BlockPos(41, SURFACE_Y, -38), e2 = new BlockPos(42, SURFACE_Y, -38);
		BlockPos e3 = new BlockPos(42, SURFACE_Y, -37);
		Waypoint whole = holdOver(keys, List.of(e0, e1, e2, e3), false);
		whole.setName("Erase All");
		whole.color = 0x654321;
		WaypointGroup g = wm.createGroup(List.of(other, whole, four));
		check(g != null && wm.members(g).equals(List.of(other, whole, four)), "group for the erase test not made");
		String original = describe(whole);
		List<BlockPos> originalBlocks = List.copyOf(whole.blocks());
		keys.press(mc, e1, false);
		keys.extend(mc, e2); // ERASE: e1, e2
		keys.extend(mc, e3);
		check(wm.byId(whole.id).isPresent() && whole.blocks().equals(List.of(e0)) && keys.erasedBlocks() == 3,
				"erase stroke before the last block gave " + whole.blocks());
		check(wm.members(g).contains(whole), "a member losing blocks left its group");
		keys.extend(mc, e0); // the last block: the waypoint is deleted, the hold ends
		check(wm.byId(whole.id).isEmpty() && keys.hold() == CreateKeyHandler.Hold.IDLE && keys.isWaitingForKeyUp(),
				"erasing the last block did not delete the waypoint and end the hold: " + keys.hold());
		for (BlockPos pos : originalBlocks) check(wm.waypointAt(pos, dim) == null, "index still has " + pos + " after the erase delete");
		check(wm.members(g).equals(List.of(other, four)), "deleted member still in its group: " + names(wm.members(g)));
		check(wm.lastDeleted().map(d -> d.waypoint() == whole && d.waypoint().blocks().equals(originalBlocks)).orElse(false),
				"the deletion's undo entry does not hold the original blocks");
		keys.extend(mc, e1); // key still down: nothing
		keys.release(mc);
		check(!keys.isWaitingForKeyUp() && wm.byId(whole.id).isEmpty(), "release after the erase delete changed something");
		check(keys.press(mc, e2, false) == whole && keys.hold() == CreateKeyHandler.Hold.RESTORED, "N on an erased block did not undo");
		keys.release(mc);
		check(describe(whole).equals(original) && whole.blocks().equals(originalBlocks) && whole.anchor().equals(e0),
				"undo did not restore the original waypoint:\n  " + describe(whole) + "\n  " + original);
		for (BlockPos pos : originalBlocks) check(wm.waypointAt(pos, dim) == whole, "index lacks " + pos + " after the undo");
		check(wm.members(g).equals(List.of(other, whole, four)), "undo did not put it back in its group: " + names(wm.members(g)));
		checkMesh(whole);
		DevtestClient.log("ASSERT ok: erase stroke over all " + originalBlocks.size() + " blocks deleted \"" + whole.name
				+ "\" (left its group, hold ended until key-up); N undo restored the original " + whole.blocks() + " into its group");

		// 6. Down to one block a clump keeps its style and may become a beacon; growing again forces Block Selection.
		Waypoint pair = keys.press(mc, new BlockPos(32, SURFACE_Y, -35), false);
		keys.extend(mc, new BlockPos(33, SURFACE_Y, -35));
		keys.release(mc);
		keys.press(mc, new BlockPos(33, SURFACE_Y, -35), false);
		keys.release(mc);
		check(pair.size() == 1 && !pair.isClump() && pair.style == WaypointStyle.BLOCK_HIGHLIGHT, "pair not down to one block");
		pair.style = WaypointStyle.BEACON;
		check(pair.isBeacon(), "a single block left of a clump can't be a beacon");
		keys.press(mc, new BlockPos(32, SURFACE_Y, -35), false);
		keys.extend(mc, new BlockPos(32, SURFACE_Y, -36));
		keys.release(mc);
		check(pair.size() == 2 && pair.style == WaypointStyle.BLOCK_HIGHLIGHT, "growing back to 2 did not force Block Selection");

		// 7. Tap a single-block waypoint: the whole waypoint goes (undoable); N on it again restores it and the hold extends.
		BlockPos b38 = new BlockPos(38, SURFACE_Y, -32), b39 = new BlockPos(39, SURFACE_Y, -32), b40 = new BlockPos(40, SURFACE_Y, -32);
		Waypoint single = keys.press(mc, b38, false);
		keys.release(mc);
		keys.press(mc, b38, false);
		keys.release(mc);
		check(wm.byId(single.id).isEmpty() && wm.waypointAt(b38, dim) == null, "tap on a single-block waypoint did not delete it");
		check(wm.lastDeleted().map(d -> d.waypoint() == single).orElse(false), "deletion not pending");
		check(keys.press(mc, b38, false) == single && keys.hold() == CreateKeyHandler.Hold.RESTORED, "N did not restore the deleted waypoint");
		keys.extend(mc, b39);
		keys.extend(mc, b40);
		keys.release(mc);
		check(wm.byId(single.id).isPresent() && single.blocks().equals(List.of(b38, b39, b40)), "restore + extend gave " + single.blocks());
		for (BlockPos pos : single.blocks()) check(wm.waypointAt(pos, dim) == single, "index lacks " + pos);
		DevtestClient.log("ASSERT ok: tap on single-block waypoint \"" + single.name + "\" deleted it; N undo then extend in the same press -> "
				+ single.blocks());

		for (Waypoint x : List.of(w, pair, single, other, four, whole)) wm.remove(x.id);
		wm.expireDeletions(true);
		check(wm.groups().isEmpty(), "groups left after the erase checks: " + wm.groups());
		wm.select(beaconBlock.id);
	}

	/** The highlight mesh rebuilt from the waypoint's current blocks has exactly their exposed faces. */
	private static void checkMesh(Waypoint w) {
		int faces = 0;
		for (BlockPos b : w.blocks()) {
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
				if (!w.contains(b.relative(d))) faces++;
			}
		}
		try {
			Class<?> cls = Class.forName("dev.waypy.render.ClumpMesh");
			java.lang.reflect.Method build = cls.getDeclaredMethod("build", Waypoint.class, double.class);
			build.setAccessible(true);
			Object mesh = build.invoke(null, w, 0.002);
			java.lang.reflect.Field quads = cls.getDeclaredField("quads");
			java.lang.reflect.Field version = cls.getDeclaredField("version");
			quads.setAccessible(true);
			version.setAccessible(true);
			int quadFaces = ((float[]) quads.get(mesh)).length / 12;
			check(quadFaces == faces, "mesh has " + quadFaces + " faces, expected " + faces + " for " + w.blocks());
			check((int) version.get(mesh) == w.version(), "mesh version " + version.get(mesh) + " != waypoint version " + w.version());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not build the clump mesh", e);
		}
	}

	/** Every field but the blocks, for "unchanged" comparisons. */
	private static String fields(Waypoint w) {
		return w.id + " \"" + w.name + "\" color=" + Integer.toHexString(w.color) + " " + w.style + " " + w.logoType + " "
				+ w.logoBlock + " opacity=" + w.highlightOpacity + " through=" + w.throughBlocks + " visible=" + w.visible
				+ " text=" + w.showText;
	}

	/** Member names, in order (for messages). */
	private static List<String> names(List<Waypoint> waypoints) {
		return waypoints.stream().map(w -> w.name).toList();
	}

	/**
	 * Group rules through the manager (the menu's methods) and the N key: defaults, one group per waypoint, no mixed
	 * dimensions, auto-dissolve below 2 members, membership restored by undo (or not, once the group is gone).
	 */
	private void checkGroups() {
		WaypointManager wm = WaypointManager.get();
		CreateKeyHandler keys = WaypyClient.CREATE_KEY_HANDLER;
		var dim = mc.level.dimension();
		wm.expireDeletions(true);
		BlockPos pa = new BlockPos(50, SURFACE_Y, -40), pb = new BlockPos(52, SURFACE_Y, -40);
		BlockPos pc = new BlockPos(54, SURFACE_Y, -40), pd = new BlockPos(56, SURFACE_Y, -40);
		Waypoint a = wm.create(pa, dim, null), b = wm.create(pb, dim, null), c = wm.create(pc, dim, null), d = wm.create(pd, dim, null);
		a.color = 0xABCDEF;
		a.logoType = LogoType.NONE;

		// Create: name, first member's colour/logo, text off, the setting's combine distance, member order.
		WaypointGroup g = wm.createGroup(List.of(a, b, c));
		check(g != null && wm.groups().contains(g), "createGroup returned no group");
		check(g.name.startsWith("Group ") && g.color == 0xABCDEF && g.logoType == LogoType.NONE && g.logoBlock.equals(a.logoBlock)
				&& !g.showText && g.combineDistance == WaypyConfig.get().groupCombineDistance && g.dimension.equals(dim),
				"new group defaults wrong: " + g.name + " " + Integer.toHexString(g.color) + " " + g.logoType + " " + g.combineDistance);
		check(wm.members(g).equals(List.of(a, b, c)) && wm.groupOf(b.id) == g && wm.groupOf(d.id) == null, "members wrong: " + names(wm.members(g)));

		// Mixed dimensions: refused, nothing changes. Fewer than 2: refused.
		Waypoint nether = wm.create(new BlockPos(0, 64, 0), net.minecraft.world.level.Level.NETHER, null);
		check(wm.canGroup(List.of(d, nether)) == WaypointManager.GroupCheck.MIXED_DIMENSIONS && wm.createGroup(List.of(d, nether)) == null,
				"a group across dimensions was allowed");
		check(wm.createGroup(List.of(d)) == null && wm.createGroup(List.of(d, d)) == null, "a group of one was allowed");
		check(wm.groupOf(d.id) == null && wm.groupOf(nether.id) == null, "a refused group changed membership");
		wm.remove(nether.id);

		// Deleting a member with an N tap: it leaves the group; undo with N puts it back at its old place.
		keys.press(mc, pb, false);
		keys.release(mc);
		check(wm.byId(b.id).isEmpty() && wm.members(g).equals(List.of(a, c)) && wm.groupById(g.id).isPresent(),
				"N tap on a member: group is " + names(wm.members(g)));
		check(keys.press(mc, pb, false) == b && keys.hold() == CreateKeyHandler.Hold.RESTORED, "N did not restore the member");
		keys.release(mc);
		check(wm.members(g).equals(List.of(a, b, c)) && wm.groupOf(b.id) == g, "undo did not restore the membership: " + names(wm.members(g)));
		DevtestClient.log("ASSERT ok: group \"" + g.name + "\" " + names(wm.members(g)) + "; N tap on a member removed it from the group, "
				+ "N undo put it back at index 1");

		// Down to one member: the group dissolves; undo brings the waypoints back ungrouped.
		wm.remove(a.id); // the menu's Delete path
		check(wm.members(g).equals(List.of(b, c)), "group after deleting a: " + names(wm.members(g)));
		wm.remove(c.id);
		check(wm.groupById(g.id).isEmpty() && !wm.groups().contains(g) && wm.groupOf(b.id) == null, "group with 1 member not dissolved");
		check(wm.undo(wm.lastDeleted().orElseThrow()) == c && wm.undo(wm.lastDeleted().orElseThrow()) == a, "undo of the deletions failed");
		check(wm.groupOf(a.id) == null && wm.groupOf(c.id) == null && wm.groupById(g.id).isEmpty(), "undo after dissolve restored a group");
		DevtestClient.log("ASSERT ok: deleting down to 1 member dissolved the group; undo brought the waypoints back ungrouped");

		// Remove from group: below 2 it dissolves. Regrouping moves waypoints out of their old group.
		WaypointGroup two = wm.createGroup(List.of(a, b));
		check(wm.removeFromGroup(a) && wm.groupById(two.id).isEmpty() && wm.groupOf(b.id) == null, "removeFromGroup did not dissolve a 2-group");
		WaypointGroup g1 = wm.createGroup(List.of(a, b, c));
		WaypointGroup g2 = wm.createGroup(List.of(c, d));
		check(wm.members(g1).equals(List.of(a, b)) && wm.members(g2).equals(List.of(c, d)), "regroup did not move c");
		WaypointGroup g3 = wm.createGroup(List.of(b, d));
		check(wm.groupById(g1.id).isEmpty() && wm.groupById(g2.id).isEmpty() && wm.members(g3).equals(List.of(b, d))
				&& wm.groupOf(a.id) == null && wm.groupOf(c.id) == null, "regroup did not dissolve the old groups");
		wm.ungroup(g3);
		check(wm.groupById(g3.id).isEmpty() && wm.byId(b.id).isPresent() && wm.byId(d.id).isPresent(), "ungroup removed waypoints");
		DevtestClient.log("ASSERT ok: removeFromGroup/regroup dissolve groups below 2; ungroup keeps the waypoints");

		for (Waypoint w : List.of(a, b, c, d)) wm.remove(w.id);
		wm.expireDeletions(true);
		check(wm.groups().isEmpty(), "groups left after cleanup: " + wm.groups());
		wm.select(beaconBlock.id);
	}

	/** "Mine Area": three waypoints made into a group with the manager method the menu's "Make group" uses. */
	private void createMineArea() {
		WaypointManager wm = WaypointManager.get();
		var dim = mc.level.dimension();
		mineEntrance = wm.create(new BlockPos(40, FEET_Y, 20), dim, Identifier.withDefaultNamespace("iron_block"));
		mineEntrance.setName("Mine Entrance");
		mineEntrance.color = 0xF9801D; // orange
		mineEntrance.showText = true;
		oreVein = wm.create(new BlockPos(44, FEET_Y, 22), dim, Identifier.withDefaultNamespace("coal_ore"));
		check(wm.addBlock(oreVein, new BlockPos(45, FEET_Y, 22)) == WaypointManager.AddBlockResult.ADDED
				&& wm.addBlock(oreVein, new BlockPos(46, FEET_Y, 22)) == WaypointManager.AddBlockResult.ADDED, "Ore Vein clump");
		oreVein.setName("Ore Vein");
		oreVein.color = 0x474F52; // gray
		oreVein.showText = true;
		mineStorage = wm.create(new BlockPos(42, FEET_Y, 25), dim, Identifier.withDefaultNamespace("barrel"));
		mineStorage.setName("Mine Storage");
		mineStorage.color = 0xB02E26; // red
		mineStorage.style = WaypointStyle.BEACON;
		mineStorage.showText = true;

		mineArea = wm.createGroup(List.of(mineEntrance, oreVein, mineStorage));
		check(mineArea != null, "Mine Area not created");
		check(mineArea.color == mineEntrance.color && mineArea.logoBlock.equals(mineEntrance.logoBlock),
				"group did not take the first member's colour/logo");
		mineArea.setName("Mine Area");
		mineArea.color = 0xFED83D; // yellow
		mineArea.logoBlock = Identifier.withDefaultNamespace("iron_pickaxe");
		mineArea.showText = true;
		check(mineArea.combineDistance == 15, "default combine distance " + mineArea.combineDistance);
		wm.select(beaconBlock.id);
		wm.markDirty();
		DevtestClient.log("group Mine Area: " + names(wm.members(mineArea)) + ", combine distance " + mineArea.combineDistance);
	}

	/** The group combiner's state for a group (render package, by reflection). */
	private static boolean combined(WaypointGroup group) {
		try {
			Class<?> cls = Class.forName("dev.waypy.render.GroupCombiner");
			java.lang.reflect.Method m = cls.getDeclaredMethod("isCombined", java.util.UUID.class);
			m.setAccessible(true);
			return (boolean) m.invoke(null, group.id);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not read the group combiner", e);
		}
	}

	/**
	 * Mine Area alone on screen: near (camera ~13 blocks from the group centre: the members' pins, highlights and
	 * beam) and far (~35 blocks, beyond the 15-block combine distance: one group pin, no member highlights or beam).
	 */
	private void groupWorldShots() {
		List<Waypoint> hidden = new ArrayList<>();
		step("groups: show only Mine Area", this::noPendingScreenshots, () -> {
			for (Waypoint w : WaypointManager.get().all()) {
				if (w.visible && WaypointManager.get().groupOf(w.id) != mineArea) {
					w.visible = false;
					hidden.add(w);
				}
			}
			command("tp @a 42.8 " + (FEET_Y + 2) + " 10.5 0 5");
		}, 40);
		step("wait for chunks (group near)", chunksReady(), () -> {
		}, 20);
		screenshot("47_group_near");
		step("check near state", this::noPendingScreenshots, () -> {
			check(!combined(mineArea), "Mine Area combined at ~13 blocks");
			command("tp @a 42.8 " + (FEET_Y + 5) + " -11.5 0 12");
		}, 40);
		step("wait for chunks (group far)", chunksReady(), () -> {
		}, 20);
		screenshot("48_group_far");
		step("check far state, restore", this::noPendingScreenshots, () -> {
			check(combined(mineArea), "Mine Area not combined at ~35 blocks");
			DevtestClient.log("ASSERT ok: Mine Area separate near (47_group_near), combined far (48_group_far)");
			for (Waypoint w : hidden) w.visible = true;
			command("tp @a 0.5 " + FEET_Y + " 0.5 0 8");
		}, 20);
	}

	/**
	 * The menu with groups: Shift range, a cross-dimension selection (Make group inactive), a Ctrl multi-selection
	 * (screenshot), Make group, Remove from group and Ungroup through the screen, the group editor and a member's
	 * editor (screenshots), Delete inactive for a group, and the group editor in a narrow window.
	 */
	private void menuGroups() {
		WaypointScreen[] screen = new WaypointScreen[1];
		step("menu: shift range, cross-dimension selection", this::noPendingScreenshots, () -> {
			WaypointManager wm = WaypointManager.get();
			netherProbe = wm.create(new BlockPos(5, 70, 5), net.minecraft.world.level.Level.NETHER, null);
			netherProbe.setName("Nether Probe");
			screen[0] = new WaypointScreen(beaconBlock);
			mc.gui.setScreen(screen[0]);
			WaypointScreen sc = screen[0];
			sc.clickWaypoint(beaconBlock, false, false);
			sc.clickWaypoint(highlightSolid, true, false); // range Diamond Beacon .. Gold Block (list order)
			check(sc.multiSelection().equals(List.of(beaconBlock, beaconLapis, highlightThrough, highlightSolid)),
					"shift range gave " + names(sc.multiSelection()));
			check(sc.makeGroupButton() != null && sc.makeGroupButton().active, "Make group not offered for a range");
			check(sc.deleteButton() != null && !sc.deleteButton().active, "Delete active for a multi-selection");
			sc.clickWaypoint(netherProbe, false, true);
			check(sc.multiSelection().size() == 5 && sc.makeGroupButton() != null && !sc.makeGroupButton().active,
					"Make group active across dimensions");
			sc.clickWaypoint(netherProbe, false, true); // Ctrl again: toggled off
			check(sc.multiSelection().size() == 4 && sc.makeGroupButton().active, "Ctrl toggle off failed");
			sc.clickWaypoint(beaconLapis, false, false); // plain click: single selection again
			check(sc.multiSelection().isEmpty() && sc.selectedWaypoint() == beaconLapis, "plain click did not clear the multi-selection");
			wm.remove(netherProbe.id);
			wm.expireDeletions(true);
			// The screenshot selection: Ctrl+click three waypoints.
			mc.gui.setScreen(screen[0] = new WaypointScreen(beaconBlock));
			screen[0].clickWaypoint(beaconBlock, false, false);
			screen[0].clickWaypoint(highlightThrough, false, true);
			screen[0].clickWaypoint(highlightSolid, false, true);
			check(screen[0].multiSelection().size() == 3 && screen[0].selectedWaypoint() == highlightSolid, "ctrl selection wrong");
			DevtestClient.log("ASSERT ok: menu Shift range, Ctrl toggle, cross-dimension Make group inactive, plain click clears");
		}, 10);
		screenshot("50_menu_multi_select");
		step("menu: Make group, Remove from group, Ungroup", this::noPendingScreenshots, () -> {
			WaypointManager wm = WaypointManager.get();
			WaypointScreen sc = screen[0];
			WaypointGroup made = sc.makeGroup();
			check(made != null && sc.selectedGroup() == made && made.name.equals("Group 1")
					&& wm.members(made).equals(List.of(beaconBlock, highlightThrough, highlightSolid)),
					"Make group gave " + made + (made != null ? " " + names(wm.members(made)) : ""));
			check(sc.deleteButton() != null && !sc.deleteButton().active, "Delete active with a group selected");
			sc.clickWaypoint(highlightThrough, false, false);
			check(sc.removeFromGroupButton() != null, "no Remove from group for a member");
			sc.removeSelectedFromGroup();
			check(wm.groupOf(highlightThrough.id) == null && wm.members(made).equals(List.of(beaconBlock, highlightSolid))
					&& sc.selectedWaypoint() == highlightThrough, "Remove from group failed");
			sc.clickGroup(made);
			sc.ungroupSelected();
			check(wm.groupById(made.id).isEmpty() && wm.byId(beaconBlock.id).isPresent() && wm.groupOf(highlightSolid.id) == null
					&& sc.selectedWaypoint() == beaconBlock, "Ungroup failed");
			sc.clickGroup(mineArea);
			check(sc.selectedGroup() == mineArea, "group row not selected");
			DevtestClient.log("ASSERT ok: menu Make group -> \"" + made.name + "\" selected; Remove from group; Ungroup kept the waypoints");
		}, 10);
		screenshot("51_menu_group_editor");
		step("menu: member editor", this::noPendingScreenshots, () -> {
			screen[0].clickWaypoint(oreVein, false, false);
			check(screen[0].removeFromGroupButton() != null && screen[0].deleteButton().active, "member editor wrong");
		}, 10);
		screenshot("52_menu_group_member");
		step("menu: narrow window (GUI scale 4)", this::noPendingScreenshots, () -> {
			mc.options.guiScale().set(4);
			mc.resizeGui();
			screen[0].clickGroup(mineArea);
		}, 10);
		screenshot("53_menu_group_editor_narrow");
		step("menu: GUI scale back, close", this::noPendingScreenshots, () -> {
			mc.options.guiScale().set(2);
			mc.resizeGui();
			mc.gui.setScreen(null);
		}, 5);
	}

	/** Every group (name, fields, members) of the world, for exact before/after comparisons. */
	private static String describeGroups() {
		WaypointManager wm = WaypointManager.get();
		StringBuilder out = new StringBuilder();
		for (WaypointGroup g : wm.groups()) {
			out.append(g.id).append(" \"").append(g.name).append("\" ").append(g.dimension.identifier()).append(" color=")
					.append(Integer.toHexString(g.color)).append(' ').append(g.logoType).append(' ').append(g.logoBlock)
					.append(" text=").append(g.showText).append(" combine=").append(g.combineDistance).append(" members=")
					.append(g.members()).append('\n');
		}
		return out.toString();
	}

	/**
	 * Deletes a waypoint with an N tap now; {@link #checkExpiryProbe} checks it can't be undone once 15 s have passed.
	 */
	private void startExpiryProbe() {
		WaypointManager wm = WaypointManager.get();
		CreateKeyHandler keys = WaypyClient.CREATE_KEY_HANDLER;
		BlockPos pos = new BlockPos(26, SURFACE_Y, -26);
		expiryProbe = keys.press(mc, pos, false);
		keys.release(mc);
		check(expiryProbe != null, "expiry probe not created");
		expiryProbe.setName("Expiry Probe");
		expiryProbeBlocks = List.copyOf(expiryProbe.blocks());
		keys.press(mc, pos, false); // tap on a single-block waypoint: deletes it
		keys.release(mc);
		expiryProbeDeletedAt = Util.getMillis();
		var pending = wm.lastDeleted().orElse(null);
		check(pending != null && pending.waypoint() == expiryProbe && wm.canUndo(pending), "expiry probe deletion not pending");
		check(wm.remainingUndoMillis(pending) > 14_000, "undo window shorter than expected: " + wm.remainingUndoMillis(pending));
		wm.select(beaconBlock.id);
	}

	private boolean expiryProbeDue() {
		return Util.getMillis() - expiryProbeDeletedAt > WaypointManager.UNDO_WINDOW_MILLIS + 500;
	}

	private void checkExpiryProbe() {
		WaypointManager wm = WaypointManager.get();
		CreateKeyHandler keys = WaypyClient.CREATE_KEY_HANDLER;
		long age = Util.getMillis() - expiryProbeDeletedAt;
		check(wm.lastDeleted().map(d -> d.waypoint() != expiryProbe).orElse(true), "probe still pending after " + age + " ms");
		check(wm.restore(expiryProbe.id) == null, "probe restorable after " + age + " ms");
		Waypoint created = keys.press(mc, expiryProbeBlocks.getFirst(), false);
		keys.release(mc);
		check(created != null && !created.id.equals(expiryProbe.id), "N on the expired probe's block did not create a new waypoint");
		wm.remove(created.id);
		wm.expireDeletions(true);
		DevtestClient.log("ASSERT ok: real-time expiry: deleted probe gone for good after " + age + " ms");
	}

	/**
	 * Disconnect/rejoin: a pending deletion becomes permanent (not saved, not restorable); on load a multi-block BEACON
	 * becomes BLOCK_HIGHLIGHT and a waypoint without {@code showText} keeps its label. Waypoint objects are new afterwards.
	 */
	private void checkLoadMigrations() {
		WaypointManager wm = WaypointManager.get();
		var dim = mc.level.dimension();
		// Groups round-trip: save, reload, the same groups (ids, fields, member order).
		mineArea.combineDistance = 37;
		String groupsBefore = describeGroups();
		check(!groupsBefore.isEmpty(), "no groups before the reload");
		wm.onDisconnect();
		wm.onJoin(mc);
		check(describeGroups().equals(groupsBefore), "groups changed by save/reload:\n" + describeGroups() + "vs\n" + groupsBefore);
		DevtestClient.log("ASSERT ok: groups round-trip through the file:\n" + groupsBefore.strip());

		Waypoint doomed = wm.create(new BlockPos(28, SURFACE_Y, -28), dim, null);
		var doomedId = doomed.id;
		wm.remove(doomedId);
		String key = wm.worldKey();
		wm.onDisconnect();
		check(wm.all().isEmpty() && wm.worldKey() == null, "disconnect did not unload");
		java.nio.file.Path file = mc.gameDirectory.toPath().resolve("waypy").resolve(key + ".json");
		try {
			String json = java.nio.file.Files.readString(file);
			check(!json.contains(doomedId.toString()), "pending deletion was saved");
			var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
			for (var el : root.getAsJsonArray("waypoints")) {
				var o = el.getAsJsonObject();
				String id = o.get("id").getAsString();
				if (id.equals(stairStack.id.toString())) o.addProperty("style", "BEACON"); // an old clump beacon
				if (id.equals(highlightThrough.id.toString())) o.remove("showText"); // written before showText existed
			}
			check(root.has("groups") && root.getAsJsonArray("groups").size() == 1, "groups not saved: " + root.get("groups"));
			root.remove("groups"); // a file from before groups existed
			java.nio.file.Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root));
		} catch (java.io.IOException e) {
			throw new IllegalStateException("could not edit the waypoint file", e);
		}
		wm.onJoin(mc);
		check(wm.byId(doomedId).isEmpty() && wm.restore(doomedId) == null, "deletion pending at disconnect came back");
		Waypoint stairs = wm.byId(stairStack.id).orElseThrow();
		check(stairs.style == WaypointStyle.BLOCK_HIGHLIGHT && stairs.size() == 14, "clump BEACON not migrated: " + stairs.style);
		check(wm.byId(highlightThrough.id).orElseThrow().showText, "missing showText did not load as true");
		check(!wm.byId(farBeacon.id).orElseThrow().showText && wm.byId(beaconBlock.id).orElseThrow().showText, "showText not round-tripped");
		check(wm.groups().isEmpty() && wm.groupOf(mineEntrance.id) == null && wm.byId(oreVein.id).isPresent(),
				"a file without groups did not load ungrouped");
		DevtestClient.log("load: clump BEACON -> BLOCK_HIGHLIGHT, missing showText -> true, pending deletion dropped at disconnect, "
				+ "no groups array -> no groups");
	}

	/** Name, fields and blocks of a waypoint, for exact comparisons. */
	private static String describe(Waypoint w) {
		return w.id + " \"" + w.name + "\" " + w.dimension.identifier() + " color=" + Integer.toHexString(w.color) + " " + w.style
				+ " " + w.logoType + " " + w.logoBlock + " opacity=" + w.highlightOpacity + " through=" + w.throughBlocks
				+ " visible=" + w.visible + " text=" + w.showText + " blocks=" + w.blocks();
	}

	/**
	 * Walking away from "Walk Test" (alone on screen, text on): two shots three blocks apart, then a sequence at
	 * doubling distances (16, 32, 64, 128, 256): the pin shrinks with perspective (half at 32) down to its fixed
	 * floor; compare the crops for jumps or jitter.
	 */
	private void walkingAwayShots() {
		List<Waypoint> hidden = new ArrayList<>();
		step("walk test: show only Walk Test", this::noPendingScreenshots, () -> {
			for (Waypoint w : WaypointManager.get().all()) {
				if (w.visible && w != walkTest) {
					w.visible = false;
					hidden.add(w);
				}
			}
			walkTest.visible = true;
		}, 1);
		String[][] shots = {{"40_walk_d40", "40"}, {"41_walk_d43", "43"}, {"42_walk_seq_d16", "16"}, {"43_walk_seq_d32", "32"},
				{"44_walk_seq_d64", "64"}, {"45_walk_seq_d128", "128"}, {"46_walk_seq_d256", "256"}};
		for (String[] shot : shots) {
			double d = Double.parseDouble(shot[1]);
			// Facing +X (yaw -90), looking 10 degrees down so the pin (just above eye level) sits clear of the
			// crosshair; Walk Test's block centre is at x 60.5, z 0.5.
			step("walk back to " + shot[1] + " blocks", this::noPendingScreenshots,
					() -> command("tp @a " + (60.5 - d) + " " + FEET_Y + " 0.5 -90 10"), 30);
			screenshot(shot[0]);
		}
		step("walk test: restore visibility", this::noPendingScreenshots, () -> {
			for (Waypoint w : hidden) w.visible = true;
			walkTest.visible = false;
			command("tp @a 0.5 " + FEET_Y + " 0.5 0 8");
		}, 20);
	}

	/** The menu: move a clump through the X/Y/Z boxes (index checks, overlap, clamp), then Delete (no confirm) and Undo. */
	private void menuMoveAndUndo() {
		WaypointScreen[] screen = new WaypointScreen[1];
		step("menu: move Floor Patch via coordinates", this::noPendingScreenshots, () -> {
			WaypointManager.get().select(floorPatch.id);
			screen[0] = new WaypointScreen(floorPatch);
			mc.gui.setScreen(screen[0]);
			checkCoordinateMove(screen[0]);
		}, 10);
		screenshot("16_menu_coords_overlap");
		step("menu: coordinates back", this::noPendingScreenshots, () -> {
			List<EditBox> boxes = screen[0].coordinateBoxes();
			boxes.get(1).setValue(String.valueOf(SURFACE_Y));
			boxes.get(0).setValue("-6");
			check(floorPatch.anchor().equals(new BlockPos(-6, SURFACE_Y, -9)), "Floor Patch not moved back: " + floorPatch.anchor());
			check(WaypointManager.get().waypointAt(new BlockPos(-3, SURFACE_Y, -7), mc.level.dimension()) == floorPatch,
					"index not restored after moving back");
		}, 5);
		step("menu: Delete Gold Block", this::noPendingScreenshots, () -> {
			WaypointManager.get().select(highlightSolid.id);
			screen[0] = new WaypointScreen(highlightSolid);
			mc.gui.setScreen(screen[0]);
			String before = describe(highlightSolid);
			screen[0].deleteSelected();
			check(mc.gui.screen() == screen[0], "Delete opened another screen (confirm dialog?)");
			check(WaypointManager.get().byId(highlightSolid.id).isEmpty(), "menu Delete did not remove the waypoint");
			Button undo = screen[0].undoButton();
			check(undo != null && undo.visible && undo.active, "Undo button not shown after Delete");
			undoSnapshot = before;
		}, 10);
		screenshot("17_menu_undo_offered");
		step("menu: Undo", this::noPendingScreenshots, () -> {
			screen[0].undoDelete();
			Waypoint restored = WaypointManager.get().byId(highlightSolid.id).orElse(null);
			check(restored == highlightSolid && describe(restored).equals(undoSnapshot), "menu Undo did not restore Gold Block unchanged");
			check(screen[0].selectedWaypoint() == highlightSolid, "menu Undo did not re-select the waypoint");
			Button undo = screen[0].undoButton();
			check(undo != null && !undo.visible, "Undo button still shown after the undo");
			DevtestClient.log("menu Delete (no confirm) + Undo OK: " + undoSnapshot);
		}, 10);
		screenshot("18_menu_undone");
		step("menu: no Undo for a block removal", this::noPendingScreenshots, () -> {
			WaypointManager wm = WaypointManager.get();
			CreateKeyHandler keys = WaypyClient.CREATE_KEY_HANDLER;
			mc.gui.setScreen(null);
			List<BlockPos> before = List.copyOf(stairStack.blocks());
			BlockPos last = before.getLast();
			var pendingBefore = wm.lastDeleted().orElse(null); // the expiry probe's deletion may still be pending
			keys.press(mc, last, false); // tap on a Stair Stack block: final
			keys.release(mc);
			check(stairStack.size() == before.size() - 1 && !stairStack.contains(last), "tap did not remove the Stair Stack block");
			check(wm.lastDeleted().orElse(null) == pendingBefore, "a block removal changed the pending deletions");
			screen[0] = new WaypointScreen(floorPatch);
			mc.gui.setScreen(screen[0]);
			Button undo = screen[0].undoButton();
			check(undo != null && undo.visible == (pendingBefore != null), "Undo offered for a block removal (visible=" + undo.visible + ")");
			if (pendingBefore == null) {
				screen[0].undoDelete(); // nothing to undo: a no-op
				check(!stairStack.contains(last), "menu Undo restored a removed block");
			}
			// Put the block back (by extending) so the later load checks see the full Stair Stack.
			check(wm.addBlock(stairStack, last) == WaypointManager.AddBlockResult.ADDED && stairStack.blocks().equals(before),
					"Stair Stack not re-extended: " + stairStack.blocks());
			DevtestClient.log("ASSERT ok: menu Undo is only offered for whole-waypoint deletions: after the N tap removed " + last
					+ " from Stair Stack the button " + (pendingBefore == null ? "stayed hidden" : "still offered only \"" + pendingBefore.name() + "\""));
		}, 10);
		step("menu: close", this::noPendingScreenshots, () -> mc.gui.setScreen(null), 5);
	}

	private String undoSnapshot;

	private void checkCoordinateMove(WaypointScreen screen) {
		WaypointManager wm = WaypointManager.get();
		var dim = mc.level.dimension();
		List<EditBox> boxes = screen.coordinateBoxes();
		check(boxes.size() == 3, "expected 3 coordinate boxes, got " + boxes.size());
		check(boxes.get(0).getValue().equals("-6") && boxes.get(1).getValue().equals(String.valueOf(SURFACE_Y))
				&& boxes.get(2).getValue().equals("-9"), "coordinate boxes don't show the anchor");
		List<BlockPos> before = List.copyOf(floorPatch.blocks());
		int version = floorPatch.version();

		// X: -6 -> -26 translates all 12 blocks.
		boxes.get(0).setValue("-26");
		check(floorPatch.version() != version, "version not bumped by the move");
		for (int i = 0; i < before.size(); i++) {
			BlockPos expected = before.get(i).offset(-20, 0, 0);
			check(floorPatch.blocks().get(i).equals(expected), "block " + i + " is " + floorPatch.blocks().get(i) + ", expected " + expected);
			check(wm.waypointAt(expected, dim) == floorPatch, "index lacks moved block " + expected);
			check(wm.waypointAt(before.get(i), dim) == null, "index still has old block " + before.get(i));
		}
		// Invalid input is not applied.
		boxes.get(2).setValue("-");
		boxes.get(2).setValue("1x");
		check(floorPatch.anchor().getZ() == -9, "invalid Z applied");
		// Y is clamped to the build height.
		boxes.get(1).setValue("99999");
		check(floorPatch.anchor().getY() == mc.level.getMaxY(), "Y not clamped: " + floorPatch.anchor());
		check(boxes.get(1).getValue().equals(String.valueOf(mc.level.getMaxY())), "clamped Y not shown: " + boxes.get(1).getValue());
		boxes.get(1).setValue(String.valueOf(SURFACE_Y));
		// Move under the stairs (free), then up into them: refused, nothing moves.
		boxes.get(0).setValue("2");
		check(floorPatch.anchor().equals(new BlockPos(2, SURFACE_Y, -9)), "move to x=2 failed: " + floorPatch.anchor());
		boxes.get(1).setValue(String.valueOf(FEET_Y));
		check(floorPatch.anchor().equals(new BlockPos(2, SURFACE_Y, -9)), "move onto Stair Stack was applied");
		check(wm.waypointAt(new BlockPos(2, FEET_Y, -9), dim) == stairStack, "Stair Stack block taken over by the refused move");
		DevtestClient.log("coordinate move OK: clump translated with index in sync, invalid input ignored, Y clamped to "
				+ mc.level.getMaxY() + ", overlap with Stair Stack refused (Y box red in 16_menu_coords_overlap)");
	}

	/** Vanilla Key Binds screen, scrolled to the Waypy category. */
	private void openKeyBinds() {
		for (KeyMapping k : List.of(WaypyClient.createKey, WaypyClient.menuKey, WaypyClient.lookModifierKey)) {
			check(java.util.Arrays.asList(mc.options.keyMappings).contains(k), k.getName() + " not in options.keyMappings");
		}
		KeyBindsScreen screen = new KeyBindsScreen(new WaypointScreen(null), mc.options);
		mc.gui.setScreen(screen);
		try {
			java.lang.reflect.Field listField = KeyBindsScreen.class.getDeclaredField("keyBindsList");
			listField.setAccessible(true);
			KeyBindsList list = (KeyBindsList) listField.get(screen);
			java.lang.reflect.Field keyField = KeyBindsList.KeyEntry.class.getDeclaredField("key");
			keyField.setAccessible(true);
			int row = -1;
			for (int i = 0; i < list.children().size(); i++) {
				if (list.children().get(i) instanceof KeyBindsList.KeyEntry e && keyField.get(e) == WaypyClient.createKey) row = i;
			}
			check(row > 0, "Waypy keys not in the Key Binds list");
			// Category header (row - 1) at the top; the list clamps the amount at its end.
			list.setScrollAmount((row - 1) * 20.0);
			DevtestClient.log("Key Binds: Waypy keys at rows " + (row - 1) + ".." + (row + 2));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not scroll the Key Binds list", e);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("check failed: " + message);
	}

	/** A config file from an older version (with the removed "Minimum Size" field) still loads; the field is ignored. */
	private static void checkOldConfigField() {
		try {
			java.nio.file.Files.writeString(WaypyConfig.path(),
					"{\n  \"iconSize\": 150,\n  \"shrinkWithDistance\": false,\n  \"minIconSize\": 25\n}\n");
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
		WaypyConfig.load();
		check(WaypyConfig.get().iconSize == 150 && !WaypyConfig.get().shrinkWithDistance,
				"config with an old minIconSize field did not load: iconSize=" + WaypyConfig.get().iconSize);
		DevtestClient.log("config: old minIconSize field ignored, other fields loaded");
	}

	/** Resets the live config to the defaults, applies {@code change} and saves it to config/waypy.json. */
	private static void setConfig(Consumer<WaypyConfig> change) {
		WaypyConfig wanted = new WaypyConfig();
		change.accept(wanted);
		WaypyConfig.get().copyFrom(wanted);
		WaypyConfig.save();
		DevtestClient.log("settings: renderDistance=" + WaypyConfig.get().waypointRenderDistance + " iconSize=" + WaypyConfig.get().iconSize
				+ " shrink=" + WaypyConfig.get().shrinkWithDistance
				+ " labels=" + WaypyConfig.get().labels + " showDistance=" + WaypyConfig.get().showDistance);
	}

	/** A world screenshot with the given settings (on top of the defaults), then back to the defaults. */
	private void configShot(String name, Consumer<WaypyConfig> change) {
		step("settings for " + name, this::noPendingScreenshots, () -> setConfig(change), 10);
		screenshot(name);
		step("default settings after " + name, this::noPendingScreenshots, () -> setConfig(c -> {
		}), 1);
	}

	private void menuShot(String name, Supplier<Waypoint> selection) {
		step("open WaypointScreen for " + name, this::noPendingScreenshots, () -> {
			Waypoint w = selection.get();
			WaypointManager.get().select(w != null ? w.id : null);
			mc.gui.setScreen(new WaypointScreen(w));
		}, 30);
		screenshot(name);
		step("close WaypointScreen " + name, this::noPendingScreenshots, () -> mc.gui.setScreen(null), 5);
	}

	/** Opens {@code dev.waypy.gui.BlockPickerScreen} by reflection, so this compiles before that class exists. */
	private void openBlockPicker() {
		Class<?> cls;
		try {
			cls = Class.forName("dev.waypy.gui.BlockPickerScreen");
		} catch (ClassNotFoundException e) {
			DevtestClient.log("dev.waypy.gui.BlockPickerScreen does not exist yet; skipping");
			return;
		}
		Waypoint w = beaconBlock;
		Screen parent = new WaypointScreen(w);
		List<String> errors = new ArrayList<>();
		for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
			Object[] args = new Object[ctor.getParameterCount()];
			boolean ok = true;
			Parameter[] params = ctor.getParameters();
			for (int i = 0; i < params.length; i++) {
				Object arg = argFor(params[i].getType(), parent, w);
				if (arg == UNSUPPORTED) {
					ok = false;
					break;
				}
				args[i] = arg;
			}
			if (!ok) {
				errors.add(ctor + ": unsupported parameter types");
				continue;
			}
			try {
				ctor.setAccessible(true);
				Screen screen = (Screen) ctor.newInstance(args);
				mc.gui.setScreen(screen);
				DevtestClient.log("opened BlockPickerScreen via " + ctor);
				return;
			} catch (ReflectiveOperationException | RuntimeException e) {
				errors.add(ctor + ": " + e);
			}
		}
		DevtestClient.log("could not construct BlockPickerScreen: " + errors);
	}

	private static final Object UNSUPPORTED = new Object();

	private Object argFor(Class<?> type, Screen parent, Waypoint w) {
		if (type == Waypoint.class) return w;
		if (Screen.class.isAssignableFrom(type) && type.isInstance(parent)) return parent;
		if (type == Identifier.class) return w.logoBlock;
		if (type == Consumer.class) return (Consumer<Object>) o -> DevtestClient.log("block picker callback: " + o);
		if (type == Runnable.class) return (Runnable) () -> {
		};
		if (type == Function.class) return Function.identity();
		if (type == boolean.class) return false;
		if (type == int.class) return 0;
		if (type == String.class) return "";
		if (type == Minecraft.class) return mc;
		return UNSUPPORTED;
	}

	// ---------------------------------------------------------------- step helpers

	private void step(String name, BooleanSupplier ready, Runnable action, int settleTicks) {
		steps.add(new Step(name, ready, action, settleTicks));
	}

	private void screenshot(String name) {
		step("screenshot " + name, this::noPendingScreenshots, () -> takeScreenshot(name), 5);
	}

	private BooleanSupplier chunksReady() {
		int[] waited = {0};
		return () -> {
			waited[0]++;
			if (mc.levelRenderer.hasRenderedAllSections() && waited[0] > 20) return true;
			if (waited[0] >= MAX_CHUNK_WAIT_TICKS) {
				DevtestClient.log("chunk compile queue not empty after " + waited[0] + " ticks; continuing anyway");
				return true;
			}
			return false;
		};
	}

	private boolean noPendingScreenshots() {
		return pendingScreenshots == 0;
	}

	private void takeScreenshot(String name) {
		String file = name + ".png";
		pendingScreenshots++;
		Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1, message -> mc.execute(() -> {
			pendingScreenshots--;
			String text = message.getString();
			if (text.contains(file) && !text.toLowerCase().contains("couldn't") && !text.toLowerCase().contains("fail")) {
				DevtestClient.log("SCREENSHOT " + mc.gameDirectory.toPath().resolve("screenshots").resolve(file).toAbsolutePath().normalize());
			} else {
				screenshotFailures.add(name + ": " + text);
				DevtestClient.log("screenshot " + name + " failed: " + text);
			}
		}));
	}

	/** Runs a command as the integrated server (op level, output suppressed). */
	private void command(String cmd) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) throw new IllegalStateException("no integrated server");
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd));
	}

	// ---------------------------------------------------------------- runner

	void tick() {
		if (index >= steps.size()) return;
		Step step = steps.get(index);
		if (!actionDone) {
			if (!step.ready().getAsBoolean()) {
				if (++waitTicks > STEP_TIMEOUT_TICKS) {
					DevtestClient.fail("step '" + step.name() + "' not ready after " + waitTicks + " ticks", null);
				}
				return;
			}
			DevtestClient.log("step " + (index + 1) + "/" + steps.size() + ": " + step.name());
			step.action().run();
			actionDone = true;
			settleLeft = step.settleTicks();
			return;
		}
		if (settleLeft-- > 0) return;
		index++;
		waitTicks = 0;
		actionDone = false;
	}
}
