package dev.waypy.devtest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import dev.waypy.CreateKeyHandler;
import dev.waypy.WaypyClient;
import dev.waypy.config.LabelMode;
import dev.waypy.config.WaypyConfig;
import dev.waypy.preset.Preset;
import dev.waypy.preset.PresetManager;
import dev.waypy.gui.PresetScreen;
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
 * BEACON), all with text on. Seen near (members) and from beyond its 15-block combine distance (one group pin), then
 * hidden as a whole (and shown again) far and near; near, beside the temporary group "Camp" ("Camp Fire", "Camp Bed"),
 * which is also used for the hide-all (Y key) checks and removed afterwards.
 * Presets (west of the spawn): "Base Camp" (looked at), "Doorstep" (stood on) and the 2-block clump "Wheat Field",
 * used by {@link #presetChecks()} for the Presets popup (H) and removed afterwards.
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
	/** A temporary second group next to Mine Area, for the hidden-group shot. */
	private Waypoint campFire;
	private Waypoint campBed;
	private WaypointGroup camp;
	/** A waypoint in the Nether, to check that a group can't span dimensions. */
	private Waypoint netherProbe;
	/** The waypoint deleted for the real-time undo expiry check, and when. */
	private Waypoint expiryProbe;
	private List<BlockPos> expiryProbeBlocks;
	/** A hidden 3-block clump that lost one block at the start of the expiry probe (its removal session expires). */
	private Waypoint sessionProbe;
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
		step("clump restore: the whole clump comes back", () -> true, this::checkClumpRestore, 2);
		step("look straight up (no block in sight)", () -> true, () -> command("tp @a 0.5 " + FEET_Y + " 0.5 0 -90"), 20);
		step("no block in sight: silent", () -> true, this::checkNoBlockSilent, 2);
		step("position player (after the sky check)", () -> true, () -> command("tp @a 0.5 " + FEET_Y + " 0.5 0 8"), 20);
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
		presetChecks();

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

		clearOverlay();
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
		checkNoOverlay("clump limit, block tap");
		DevtestClient.log("ASSERT ok: limit-hit hold and cancelled tap remove nothing; tap removed block " + target + " from \""
				+ temp.name + "\" (" + temp.size() + " left); other-waypoint block skipped; cap " + Waypoint.MAX_BLOCKS
				+ " enforced; no action-bar message");
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
		checkNoOverlay("delete + N restore");
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
		clearOverlay();
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
		checkNoOverlay("extend, erase strokes, taps, erase delete, restores");
		DevtestClient.log("ASSERT ok: tap on single-block waypoint \"" + single.name + "\" deleted it; N undo then extend in the same press -> "
				+ single.blocks() + "; no action-bar message from any N action");

		for (Waypoint x : List.of(w, pair, single, other, four, whole)) wm.remove(x.id);
		wm.expireDeletions(true);
		check(wm.groups().isEmpty(), "groups left after the erase checks: " + wm.groups());
		wm.select(beaconBlock.id);
	}

	/** A 5-block clump (in a 3-member group, selected) for {@link #checkClumpRestore}, and its state before any change. */
	private record RestoreFixture(Waypoint w, Waypoint p1, Waypoint p2, WaypointGroup g, List<BlockPos> blocks, String before) {
	}

	/** Builds the clump of scenario {@code i} through the N key (press + hold), with two group partners. */
	private RestoreFixture restoreFixture(int i, List<Waypoint> created) {
		WaypointManager wm = WaypointManager.get();
		var dim = mc.level.dimension();
		int x = 60 + 6 * i, z = -60;
		List<BlockPos> blocks = List.of(new BlockPos(x, SURFACE_Y, z), new BlockPos(x + 1, SURFACE_Y, z), new BlockPos(x + 2, SURFACE_Y, z),
				new BlockPos(x + 2, SURFACE_Y, z - 1), new BlockPos(x + 2, SURFACE_Y, z - 2));
		Waypoint w = holdOver(WaypyClient.CREATE_KEY_HANDLER, blocks, false);
		created.add(w);
		check(w.blocks().equals(blocks), "restore fixture " + i + " gave " + w.blocks());
		w.setName("Restore " + i);
		w.color = 0x100000 * (i + 1) + 0x2468;
		w.showText = true;
		w.highlightOpacity = 0.6f;
		Waypoint p1 = wm.create(new BlockPos(x, SURFACE_Y, z - 4), dim, null);
		Waypoint p2 = wm.create(new BlockPos(x + 1, SURFACE_Y, z - 4), dim, null);
		created.add(p1);
		created.add(p2);
		WaypointGroup g = wm.createGroup(List.of(p1, w, p2));
		check(g != null, "restore fixture " + i + ": group not made");
		wm.select(w.id);
		return new RestoreFixture(w, p1, p2, g, blocks, describe(w));
	}

	/** The fixture's clump is back exactly as before: blocks (order, anchor), fields, index, mesh, group place, selection. */
	private void checkRestored(RestoreFixture f, String label) {
		WaypointManager wm = WaypointManager.get();
		var dim = mc.level.dimension();
		Waypoint w = f.w();
		check(wm.byId(w.id).orElse(null) == w, label + ": not restored");
		check(w.blocks().equals(f.blocks()), label + ": restored blocks " + w.blocks() + ", expected the full clump " + f.blocks());
		check(describe(w).equals(f.before()), label + ": restored waypoint differs:\n  " + describe(w) + "\n  " + f.before());
		for (BlockPos pos : f.blocks()) check(wm.waypointAt(pos, dim) == w, label + ": index lacks " + pos);
		checkMesh(w);
		check(wm.members(f.g()).equals(List.of(f.p1(), w, f.p2())) && wm.groupOf(w.id) == f.g(),
				label + ": group membership not restored: " + names(wm.members(f.g())));
		check(wm.selected().orElse(null) == w, label + ": selection is " + wm.selected().map(x -> x.name).orElse("none"));
		check(wm.lastDeleted().map(d -> d.waypoint() != w).orElse(true), label + ": deletion still pending after the restore");
	}

	/** Deletion of the fixture's clump is pending and the clump left the world (index, list, group). */
	private void checkDeleted(RestoreFixture f, String label) {
		WaypointManager wm = WaypointManager.get();
		var dim = mc.level.dimension();
		check(wm.byId(f.w().id).isEmpty(), label + ": the clump was not deleted");
		for (BlockPos pos : f.blocks()) check(wm.waypointAt(pos, dim) == null, label + ": index still has " + pos);
		check(wm.members(f.g()).equals(List.of(f.p1(), f.p2())), label + ": deleted clump still in its group");
		check(wm.lastDeleted().map(d -> d.waypoint() == f.w()).orElse(false), label + ": deletion not pending");
	}

	/**
	 * Restoring a deleted clump brings back the WHOLE clump, as it was before the deletion and the block removals that
	 * led to it, through the real N handler (press / extend / release with explicit targets, both modes) and the menu.
	 * Each scenario runs on its own clump; failures are collected so one run shows every broken path.
	 */
	private void checkClumpRestore() {
		WaypointManager wm = WaypointManager.get();
		CreateKeyHandler keys = WaypyClient.CREATE_KEY_HANDLER;
		var dim = mc.level.dimension();
		wm.expireDeletions(true);
		List<Waypoint> created = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		clearOverlay();
		List<Runnable> scenarios = List.of(
				// 1a. Menu Delete (manager path), then N underfoot on a non-anchor block; the hold runs over its own blocks.
				() -> {
					RestoreFixture f = restoreFixture(0, created);
					wm.remove(f.w().id);
					checkDeleted(f, "1a");
					check(keys.press(mc, f.blocks().get(3), false) == f.w() && keys.hold() == CreateKeyHandler.Hold.RESTORED, "1a: no RESTORED hold");
					checkRestored(f, "1a after the press");
					for (BlockPos pos : f.blocks()) keys.extend(mc, pos); // own blocks while held: nothing trimmed
					keys.release(mc);
					checkRestored(f, "1a after the release");
					DevtestClient.log("ASSERT ok: 1a menu-deleted clump, N underfoot on block " + f.blocks().get(3) + " restored all "
							+ f.w().size() + " blocks " + f.w().blocks() + " (index, mesh, group, selection)");
				},
				// 1b. Menu Delete, then Shift+N (look-at) on the anchor, tapped (released at once).
				() -> {
					RestoreFixture f = restoreFixture(1, created);
					wm.remove(f.w().id);
					checkDeleted(f, "1b");
					check(keys.press(mc, f.blocks().getFirst(), true) == f.w() && keys.hold() == CreateKeyHandler.Hold.RESTORED, "1b: no RESTORED hold");
					keys.release(mc);
					checkRestored(f, "1b");
					DevtestClient.log("ASSERT ok: 1b menu-deleted clump, Shift+N on its anchor restored all " + f.w().size() + " blocks");
				},
				// 2a. An erase stroke (underfoot) over every block deletes the clump; Shift+N on a block restores the pre-stroke clump.
				() -> {
					RestoreFixture f = restoreFixture(2, created);
					List<BlockPos> b = f.blocks();
					keys.press(mc, b.get(1), false);
					for (BlockPos pos : List.of(b.get(2), b.get(3), b.get(4), b.get(0))) keys.extend(mc, pos);
					check(keys.isWaitingForKeyUp(), "2a: the stroke did not delete the clump");
					keys.release(mc);
					checkDeleted(f, "2a");
					check(keys.press(mc, b.get(4), true) == f.w(), "2a: Shift+N did not restore");
					keys.release(mc);
					checkRestored(f, "2a");
					DevtestClient.log("ASSERT ok: 2a erase stroke over all 5 blocks, Shift+N restored the full clump " + f.w().blocks());
				},
				// 2b. A tap removes one block, then a separate erase stroke (look-at) removes the rest: the undo restores
				// the clump as it was before the tap (one removal session).
				() -> {
					RestoreFixture f = restoreFixture(3, created);
					List<BlockPos> b = f.blocks();
					keys.press(mc, b.get(4), false);
					keys.release(mc);
					check(f.w().size() == 4 && wm.lastDeleted().isEmpty(), "2b: tap did not remove one block (final)");
					keys.press(mc, b.get(0), true);
					for (BlockPos pos : List.of(b.get(1), b.get(2), b.get(3))) keys.extend(mc, pos);
					keys.release(mc);
					checkDeleted(f, "2b");
					check(keys.press(mc, b.get(2), false) == f.w(), "2b: N did not restore");
					keys.release(mc);
					checkRestored(f, "2b");
					DevtestClient.log("ASSERT ok: 2b tap + erase stroke down to zero, N restored the clump from before the tap " + f.w().blocks());
				},
				// 3a. Taps one by one down to the last block, then a tap on the last one; N underfoot on the last block.
				() -> {
					RestoreFixture f = restoreFixture(4, created);
					List<BlockPos> b = f.blocks();
					for (BlockPos pos : List.of(b.get(0), b.get(3), b.get(1), b.get(4))) {
						keys.press(mc, pos, false);
						keys.release(mc);
					}
					check(f.w().blocks().equals(List.of(b.get(2))) && wm.lastDeleted().isEmpty(), "3a: taps gave " + f.w().blocks());
					int versionBefore = f.w().version();
					keys.press(mc, b.get(2), false); // the last block: deletes the clump
					keys.release(mc);
					checkDeleted(f, "3a");
					check(keys.press(mc, b.get(2), false) == f.w() && keys.hold() == CreateKeyHandler.Hold.RESTORED, "3a: N did not restore");
					keys.release(mc);
					checkRestored(f, "3a");
					check(f.w().version() != versionBefore, "3a: version not bumped by the restore (cached mesh would be stale)");
					DevtestClient.log("ASSERT ok: 3a taps down to zero, N underfoot on the last block restored all 5 blocks " + f.w().blocks());
				},
				// 3b. Taps (look-at) down to zero; Shift+N on a block that an EARLIER tap removed restores the whole clump.
				() -> {
					RestoreFixture f = restoreFixture(5, created);
					List<BlockPos> b = f.blocks();
					for (BlockPos pos : List.of(b.get(4), b.get(3), b.get(2), b.get(1), b.get(0))) {
						keys.press(mc, pos, true);
						keys.release(mc);
					}
					checkDeleted(f, "3b");
					check(keys.press(mc, b.get(3), true) == f.w() && keys.hold() == CreateKeyHandler.Hold.RESTORED,
							"3b: Shift+N on an earlier-tapped block did not restore (hold " + keys.hold() + ")");
					keys.release(mc);
					checkRestored(f, "3b");
					DevtestClient.log("ASSERT ok: 3b Shift+N taps down to zero, Shift+N on an earlier-tapped block restored all 5 blocks");
				},
				// 4. Menu Undo (the real screen) after taps down to zero, and after a menu Delete of the clump.
				() -> {
					RestoreFixture f = restoreFixture(6, created);
					List<BlockPos> b = f.blocks();
					for (BlockPos pos : b) {
						keys.press(mc, pos, false);
						keys.release(mc);
					}
					checkDeleted(f, "4 (taps)");
					WaypointScreen screen = new WaypointScreen(f.p1());
					mc.gui.setScreen(screen);
					Button undo = screen.undoButton();
					check(undo != null && undo.visible && undo.active, "4: Undo not offered after the taps deleted the clump");
					screen.undoDelete();
					check(screen.selectedWaypoint() == f.w(), "4: menu Undo did not select the clump");
					checkRestored(f, "4 menu Undo after taps");
					screen.deleteSelected();
					checkDeleted(f, "4 (menu Delete)");
					screen.undoDelete();
					checkRestored(f, "4 menu Delete + Undo");
					mc.gui.setScreen(null);
					DevtestClient.log("ASSERT ok: 4 menu Undo restored all 5 blocks after taps down to zero and after a menu Delete");
				},
				// 5. Adding a block ends the removal session: the undo restores the clump as it was after the add.
				() -> {
					RestoreFixture f = restoreFixture(7, created);
					List<BlockPos> b = f.blocks();
					keys.press(mc, b.get(4), false); // tap: removal session starts with all 5 blocks
					keys.release(mc);
					BlockPos extra = b.getFirst().offset(0, 0, 1);
					keys.press(mc, b.get(0), false); // extend stroke: adds a block, ends the session
					keys.extend(mc, extra);
					keys.release(mc);
					List<BlockPos> afterAdd = List.copyOf(f.w().blocks());
					check(afterAdd.equals(List.of(b.get(0), b.get(1), b.get(2), b.get(3), extra)), "5: extend gave " + afterAdd);
					for (BlockPos pos : afterAdd) {
						keys.press(mc, pos, false);
						keys.release(mc);
					}
					check(wm.byId(f.w().id).isEmpty(), "5: taps did not delete the clump");
					check(keys.press(mc, extra, false) == f.w(), "5: N did not restore");
					keys.release(mc);
					check(f.w().blocks().equals(afterAdd) && !f.w().contains(b.get(4)) && wm.waypointAt(b.get(4), dim) == null,
							"5: restored " + f.w().blocks() + ", expected the clump after the add " + afterAdd);
					checkMesh(f.w());
					DevtestClient.log("ASSERT ok: 5 an add ends the removal session: undo restored " + afterAdd + " (block removed before the add stays removed)");
				});
		for (int i = 0; i < scenarios.size(); i++) {
			try {
				scenarios.get(i).run();
				checkNoOverlay("clump restore scenario " + i);
			} catch (RuntimeException e) {
				failures.add(e.getMessage());
				DevtestClient.log("ASSERT FAIL: clump restore scenario " + i + ": " + e.getMessage());
			} finally {
				keys.cancel();
				if (mc.gui.screen() != null) mc.gui.setScreen(null);
			}
		}
		for (Waypoint x : created) wm.remove(x.id);
		wm.expireDeletions(true);
		check(wm.groups().isEmpty(), "groups left after the clump restore checks: " + wm.groups());
		wm.select(beaconBlock.id);
		check(failures.isEmpty(), failures.size() + " clump restore scenario(s) failed: " + failures);
	}

	/** Looking at the sky: Shift+N and "+ New here" (look-at) find no block, change nothing and say nothing. */
	private void checkNoBlockSilent() {
		WaypointManager wm = WaypointManager.get();
		CreateKeyHandler keys = WaypyClient.CREATE_KEY_HANDLER;
		clearOverlay();
		int count = wm.all().size();
		BlockPos target = WaypyClient.findLookedAtBlock(mc);
		check(target == null, "a block in sight while looking straight up: " + target);
		check(keys.press(mc, target, true) == null && keys.hold() == CreateKeyHandler.Hold.NONE, "Shift+N on nothing: hold " + keys.hold());
		keys.release(mc);
		check(WaypyClient.createWaypoint(mc, true) == null, "a waypoint was created with no block in sight");
		check(wm.all().size() == count, "the waypoint count changed");
		checkNoOverlay("no block in sight");
		DevtestClient.log("ASSERT ok: no block in sight: Shift+N and createWaypoint do nothing, no action-bar message");
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

		// Create: name, first member's colour/logo, text off, member order.
		WaypointGroup g = wm.createGroup(List.of(a, b, c));
		check(g != null && wm.groups().contains(g), "createGroup returned no group");
		check(g.name.startsWith("Group ") && g.color == 0xABCDEF && g.logoType == LogoType.NONE && g.logoBlock.equals(a.logoBlock)
				&& !g.showText && g.dimension.equals(dim),
				"new group defaults wrong: " + g.name + " " + Integer.toHexString(g.color) + " " + g.logoType);
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
		check(WaypyConfig.get().groupCombineDistance == WaypyConfig.DEFAULT_COMBINE_DISTANCE,
				"default combine distance " + WaypyConfig.get().groupCombineDistance);
		wm.select(beaconBlock.id);
		wm.markDirty();
		DevtestClient.log("group Mine Area: " + names(wm.members(mineArea)) + ", combine distance " + WaypyConfig.get().groupCombineDistance + " (global)");
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
	 * beam) and far (~35 blocks, beyond the 15-block global combine distance: one group pin, no member highlights or beam). Then the
	 * global setting is changed at that same camera position: the group must split (50) and combine again (default).
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
			WaypyConfig.get().groupCombineDistance = 50; // same camera position, ~35 blocks away: now inside the distance
		}, 20);
		step("check global setting 50 splits the group", this::noPendingScreenshots, () -> {
			check(!combined(mineArea), "Mine Area still combined at ~35 blocks with the global combine distance 50");
			WaypyConfig.get().groupCombineDistance = WaypyConfig.DEFAULT_COMBINE_DISTANCE;
		}, 20);
		step("check default combine distance restored", this::noPendingScreenshots, () -> {
			check(combined(mineArea), "Mine Area not combined again at ~35 blocks after restoring the default combine distance");
			DevtestClient.log("ASSERT ok: global combine distance 50 -> Mine Area separate, default 15 -> combined, same camera position");
			check(hudPins() == 1 && worldSubmits() == 0, "combined Mine Area alone: " + hudPins() + " pins, " + worldSubmits() + " world");
			mineArea.visible = false; // hide the whole group (far: it was combined)
		}, 10);
		step("check hidden group (far)", this::noPendingScreenshots, () -> {
			WaypointManager wm = WaypointManager.get();
			for (Waypoint w : wm.members(mineArea)) {
				check(w.visible && !wm.isEffectivelyVisible(w) && wm.isHiddenByGroup(w), "member " + w.name + " not hidden by its group");
			}
			check(!inCombinerViews(mineArea), "a hidden group is still in the combiner's views");
			check(hudPins() == 0 && worldSubmits() == 0, "hidden Mine Area still drawn: " + hudPins() + " pins, " + worldSubmits() + " world");
			DevtestClient.log("ASSERT ok: Mine Area hidden (far): members' own visible kept, isEffectivelyVisible false, no group pin "
					+ "(combiner dropped it), HUD pins 0, world submits 0");
			mineArea.visible = true;
		}, 10);
		step("check shown group (far), go near", this::noPendingScreenshots, () -> {
			WaypointManager wm = WaypointManager.get();
			for (Waypoint w : wm.members(mineArea)) check(wm.isEffectivelyVisible(w), "member " + w.name + " not visible again");
			check(inCombinerViews(mineArea) && combined(mineArea) && hudPins() == 1 && worldSubmits() == 0,
					"Mine Area shown again (far): combined=" + combined(mineArea) + " " + hudPins() + " pins, " + worldSubmits() + " world");
			DevtestClient.log("ASSERT ok: Mine Area shown again (far): combined group pin back (HUD pins 1)");
			// A second group, "Camp", in view of the near position, to show beside the hidden Mine Area.
			var dim = mc.level.dimension();
			campFire = wm.create(new BlockPos(38, SURFACE_Y, 18), dim, Identifier.withDefaultNamespace("campfire"));
			campFire.setName("Camp Fire");
			campFire.color = 0xF9801D;
			campFire.showText = true;
			campBed = wm.create(new BlockPos(36, SURFACE_Y, 19), dim, Identifier.withDefaultNamespace("red_bed"));
			campBed.setName("Camp Bed");
			campBed.color = 0xB02E26;
			campBed.showText = true;
			camp = wm.createGroup(List.of(campFire, campBed));
			check(camp != null, "Camp not created");
			camp.setName("Camp");
			command("tp @a 42.8 " + (FEET_Y + 2) + " 10.5 0 5");
		}, 40);
		step("wait for chunks (group near again)", chunksReady(), () -> {
		}, 10);
		step("near: hide Mine Area", this::noPendingScreenshots, () -> {
			check(!combined(mineArea) && hudPins() == 5 && worldSubmits() == 5,
					"near, both groups shown: " + hudPins() + " pins, " + worldSubmits() + " world");
			mineArea.visible = false;
		}, 10);
		step("near: check hidden Mine Area", this::noPendingScreenshots, () -> {
			check(hudPins() == 2 && worldSubmits() == 2, "near, Mine Area hidden: " + hudPins() + " pins, " + worldSubmits() + " world");
			DevtestClient.log("ASSERT ok: near, Mine Area hidden: only Camp's 2 pins and 2 highlights remain (49_group_hidden)");
		}, 2);
		screenshot("49_group_hidden");
		step("near: show Mine Area, hide all (Y key)", this::noPendingScreenshots, () -> {
			mineArea.visible = true;
			check(!WaypyConfig.get().hideAll, "hideAll on before the toggle");
			clearOverlay();
			check(WaypyClient.toggleHideAll(), "toggle did not turn hideAll on");
			checkNoOverlay("hide all");
		}, 10);
		step("check hide all", this::noPendingScreenshots, () -> {
			check(hudPins() == 0 && worldSubmits() == 0, "hide all: still " + hudPins() + " pins, " + worldSubmits() + " world");
			WaypyConfig.load(); // persisted: the saved file has it
			check(WaypyConfig.get().hideAll, "hideAll not saved to config/waypy.json");
			DevtestClient.log("ASSERT ok: hide all on: render entry points draw nothing (HUD pins 0, world submits 0), "
					+ "saved to the config file, no action-bar message (49b_world_hide_all shows no text)");
		}, 2);
		screenshot("49b_world_hide_all");
		step("show all again (Y key)", this::noPendingScreenshots, () -> {
			check(!WaypyClient.toggleHideAll(), "toggle did not turn hideAll off");
			checkNoOverlay("show all");
			WaypyConfig.load();
			check(!WaypyConfig.get().hideAll, "hideAll off not saved");
		}, 10);
		step("check shown again, clean up", this::noPendingScreenshots, () -> {
			check(hudPins() == 5 && worldSubmits() == 5, "after show all: " + hudPins() + " pins, " + worldSubmits() + " world");
			DevtestClient.log("ASSERT ok: hide all off: everything back (HUD pins 5, world submits 5), saved off");
			WaypointManager wm = WaypointManager.get();
			wm.remove(campFire.id);
			wm.remove(campBed.id);
			wm.expireDeletions(true);
			check(wm.groupById(camp.id).isEmpty(), "Camp left behind");
			for (Waypoint w : hidden) w.visible = true;
			command("tp @a 0.5 " + FEET_Y + " 0.5 0 8");
		}, 20);
	}

	/** Whether {@code GroupCombiner.compute} (what both renderers draw from) lists the group now (by reflection). */
	private boolean inCombinerViews(WaypointGroup group) {
		try {
			Class<?> cls = Class.forName("dev.waypy.render.GroupCombiner");
			java.lang.reflect.Method compute = cls.getDeclaredMethod("compute", net.minecraft.resources.ResourceKey.class,
					net.minecraft.world.phys.Vec3.class);
			compute.setAccessible(true);
			List<?> views = (List<?>) compute.invoke(null, mc.level.dimension(), mc.gameRenderer.mainCamera().position());
			for (Object view : views) {
				java.lang.reflect.Method g = view.getClass().getDeclaredMethod("group");
				g.setAccessible(true);
				if (g.invoke(view) == group) return true;
			}
			return false;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not run the group combiner", e);
		}
	}

	/** Pins the HUD drew last frame (render package, by reflection). */
	private static int hudPins() {
		return renderCounter("dev.waypy.render.WaypointHud", "lastPinCount");
	}

	/** Waypoints the world renderer submitted a beam or highlight for last frame (render package, by reflection). */
	private static int worldSubmits() {
		return renderCounter("dev.waypy.render.WorldWaypointRenderer", "lastSubmitCount");
	}

	private static int renderCounter(String className, String field) {
		try {
			java.lang.reflect.Field f = Class.forName(className).getDeclaredField(field);
			f.setAccessible(true);
			return f.getInt(null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not read " + className + "." + field, e);
		}
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
		step("menu: hidden group, hide all notice", this::noPendingScreenshots, () -> {
			WaypointManager wm = WaypointManager.get();
			mineArea.visible = false;
			mineStorage.visible = false; // hidden itself too: greyed without "group hidden"
			WaypyConfig.get().hideAll = true; // not saved: just for the notice
			screen[0] = new WaypointScreen(null);
			mc.gui.setScreen(screen[0]);
			screen[0].clickGroup(mineArea);
			check(screen[0].showsHiddenNotice(), "no 'All waypoints are hidden' notice");
			check(screen[0].visibleButton() != null && !screen[0].visibleButton().getValue(), "group editor Visible not OFF");
			check(wm.isHiddenByGroup(oreVein) && !wm.isHiddenByGroup(mineStorage) && !wm.isEffectivelyVisible(mineStorage),
					"group-hidden state of the members wrong");
			DevtestClient.log("ASSERT ok: menu: hide-all notice shown, group editor Visible OFF, Ore Vein 'group hidden', Mine Storage hidden itself");
		}, 10);
		screenshot("54_menu_group_hidden");
		step("menu: restore group and hide all, close", this::noPendingScreenshots, () -> {
			mineArea.visible = true;
			mineStorage.visible = true;
			WaypyConfig.get().hideAll = false;
			mc.gui.setScreen(new WaypointScreen(null));
			check(mc.gui.screen() instanceof WaypointScreen sc && !sc.showsHiddenNotice(), "notice shown with hideAll off");
			mc.gui.setScreen(null);
		}, 5);
	}

	// ---------------------------------------------------------------- presets

	/** Presets area, west of the spawn (grass surface): the look-at target, the block stood on, a 2-block clump. */
	private static final BlockPos PRESET_LOOK = new BlockPos(-12, SURFACE_Y, 6);
	private static final BlockPos PRESET_UNDER = new BlockPos(-12, SURFACE_Y, 2);
	private static final BlockPos PRESET_CLUMP = new BlockPos(-16, SURFACE_Y, 4);
	private Waypoint presetLook;
	private Waypoint presetUnder;
	private Waypoint presetClump;
	private PresetScreen presetScreen;
	/** The presets (as text) before the JSON checks, and the good file content. */
	private List<String> presetSnapshot;

	/** The canvas presets: name, colour, style, logo block, opacity, through blocks, text. */
	private static final Object[][] CANVAS_PRESETS = {
			{"Home", 0x3C44AA, WaypointStyle.BEACON, "red_bed", 0.4f, false, true},
			{"Mine", 0xF9801D, WaypointStyle.BLOCK_HIGHLIGHT, "iron_ore", 0.55f, true, false},
			{"Farm", 0x80C71F, WaypointStyle.BLOCK_HIGHLIGHT, "hay_block", 0.3f, false, true},
			{"Danger", 0xB02E26, WaypointStyle.BEACON, "tnt", 0.4f, false, true},
			{"Portal", 0x8932B8, WaypointStyle.BLOCK_HIGHLIGHT, "obsidian", 0.65f, true, false},
			{"Loot", 0xFED83D, WaypointStyle.BLOCK_HIGHLIGHT, "chest", 0.4f, false, true}};

	/** The fields a preset copies, as they are on {@code w} (name, blocks and visibility are compared separately). */
	private static String copied(Waypoint w) {
		return "color=" + Integer.toHexString(w.color) + " " + w.style + " " + w.logoType + " " + w.logoBlock + " opacity="
				+ w.highlightOpacity + " through=" + w.throughBlocks + " text=" + w.showText;
	}

	/** {@link #copied(Waypoint)} of a waypoint that has {@code p} applied (style as given). */
	private static String copied(Preset p, WaypointStyle style) {
		return "color=" + Integer.toHexString(p.color) + " " + style + " " + p.logoType + " " + p.logoBlock + " opacity="
				+ p.highlightOpacity + " through=" + p.throughBlocks + " text=" + p.showText;
	}

	private static List<String> presetTexts() {
		return PresetManager.get().all().stream().map(Preset::toString).toList();
	}

	private static KeyEvent key(int glfwKey) {
		return new KeyEvent(glfwKey, 0, 0);
	}

	/**
	 * A key press as GLFW delivers it: the key event (press, release), then its character if it has one ({@code '\0'}
	 * for Esc, Enter, Backspace) and the screen is still open.
	 */
	private void press(int glfwKey, char c) {
		Screen before = mc.gui.screen();
		if (before == null) return;
		before.keyPressed(key(glfwKey));
		before.keyReleased(key(glfwKey));
		if (c != '\0' && mc.gui.screen() == before) before.charTyped(new CharacterEvent(c));
	}

	/** Types {@code text} as key + character events (letters and digits only). */
	private void type(String text) {
		for (char c : text.toCharArray()) {
			int k = Character.isDigit(c) ? GLFW.GLFW_KEY_0 + (c - '0') : GLFW.GLFW_KEY_A + (Character.toUpperCase(c) - 'A');
			press(k, c);
		}
	}

	private void clickCentre(Screen screen, net.minecraft.client.gui.components.AbstractWidget button, int mouseButton) {
		double x = button.getX() + button.getWidth() / 2.0;
		double y = button.getY() + button.getHeight() / 2.0;
		MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(mouseButton, 0));
		if (screen.mouseClicked(event, false)) screen.mouseReleased(event);
	}

	/** Moves the (headless) mouse to GUI position ({@code x}, {@code y}), e.g. off the buttons for a clean screenshot. */
	private void parkMouse(double x, double y) {
		try {
			double scale = mc.getWindow().getGuiScale();
			java.lang.reflect.Field fx = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
			java.lang.reflect.Field fy = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
			fx.setAccessible(true);
			fy.setAccessible(true);
			fx.setDouble(mc.mouseHandler, x * scale);
			fy.setDouble(mc.mouseHandler, y * scale);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not move the mouse", e);
		}
	}

	/** The mouse in the bottom-left corner, away from every widget. */
	private void parkMouse() {
		parkMouse(2, mc.getWindow().getGuiScaledHeight() - 2);
	}

	/** The target of the open Presets popup, for failure messages. */
	private String presetTarget() {
		return mc.gui.screen() instanceof PresetScreen p ? p.target().name : "no popup";
	}

	private PresetScreen openPresets(Waypoint target) {
		presetScreen = new PresetScreen(target, false);
		mc.gui.setScreen(presetScreen);
		return presetScreen;
	}

	/**
	 * The Presets popup (H): creates the six canvas presets through the "+" form, checks the real H handling
	 * ({@link WaypyClient#openPresets}: plain = the waypoint stood on, look mode = the one looked at, no fallback; no
	 * target = nothing happens, no message), applying (number key, click, search + Enter; a BEACON preset on a
	 * clump), the search keys, "+" prefill, right-click edit and Delete (renumbering), the block picker round trip,
	 * the JSON round trip and the corrupt-file fallback; with screenshots of each state.
	 */
	private void presetChecks() {
		step("presets: targets, start empty", this::noPendingScreenshots, () -> {
			WaypointManager wm = WaypointManager.get();
			var dim = mc.level.dimension();
			for (BlockPos pos : List.of(PRESET_LOOK, PRESET_UNDER, PRESET_CLUMP, PRESET_CLUMP.east())) {
				check(wm.waypointAt(pos, dim) == null, "preset test block " + pos + " already has a waypoint");
			}
			presetLook = wm.create(PRESET_LOOK, dim, Identifier.withDefaultNamespace("grass_block"));
			presetLook.setName("Base Camp");
			presetUnder = wm.create(PRESET_UNDER, dim, Identifier.withDefaultNamespace("grass_block"));
			presetUnder.setName("Doorstep");
			presetClump = wm.create(PRESET_CLUMP, dim, Identifier.withDefaultNamespace("grass_block"));
			presetClump.setName("Wheat Field");
			check(wm.addBlock(presetClump, PRESET_CLUMP.east()) == WaypointManager.AddBlockResult.ADDED && presetClump.isClump(),
					"preset clump not made");
			wm.markDirty();
			PresetManager pm = PresetManager.get();
			while (pm.size() > 0) pm.remove(pm.size());
			check(pm.all().isEmpty(), "presets not cleared");
			// Stand on Doorstep, looking at Base Camp's top face 4 blocks ahead (eye 1.62 above the face: atan(1.62/4) = 22°).
			command("tp @a -11.5 " + FEET_Y + " 2.5 0 22");
		}, 30);
		step("presets: H plain targets the waypoint stood on, look mode the one looked at", this::noPendingScreenshots, () -> {
			// Standing on Doorstep, looking at Base Camp.
			check(WaypyClient.openPresets(mc, false) && mc.gui.screen() instanceof PresetScreen p && p.target() == presetUnder,
					"plain H target is not Doorstep (stood on): " + presetTarget());
			mc.gui.setScreen(null);
			check(WaypyClient.openPresets(mc, true) && mc.gui.screen() instanceof PresetScreen p && p.target() == presetLook,
					"look-mode H target is not Base Camp (looked at): " + presetTarget());
			mc.gui.setScreen(null);
			DevtestClient.log("ASSERT ok: standing on Doorstep, looking at Base Camp: plain H targets Doorstep, look-modifier H targets Base Camp");
			command("tp @a -11.5 " + FEET_Y + " 2.5 0 -60"); // look up: only the block stood on
		}, 20);
		step("presets: look mode at the sky does not open", this::noPendingScreenshots, () -> {
			clearOverlay();
			check(!WaypyClient.openPresets(mc, true) && mc.gui.screen() == null, "look-mode H opened looking at the sky: " + presetTarget());
			checkNoOverlay("look-mode H with no target");
			check(WaypyClient.openPresets(mc, false) && mc.gui.screen() instanceof PresetScreen p && p.target() == presetUnder,
					"plain H target is not Doorstep: " + presetTarget());
			mc.gui.setScreen(null);
			DevtestClient.log("ASSERT ok: looking at the sky: look-modifier H does not open (no fallback to the waypoint stood on), no message; plain H still targets Doorstep");
			command("tp @a -11.5 " + FEET_Y + " 9.5 180 28"); // free grass, looking north at Base Camp (z 6, 3 blocks away)
		}, 20);
		step("presets: plain H on free ground does not open, shows the message", this::noPendingScreenshots, () -> {
			check(WaypyClient.openPresets(mc, true) && mc.gui.screen() instanceof PresetScreen p && p.target() == presetLook,
					"look-mode H on free ground is not Base Camp: " + presetTarget());
			mc.gui.setScreen(null);
			clearOverlay();
			check(!WaypyClient.openPresets(mc, false) && mc.gui.screen() == null,
					"plain H opened on free ground (no fallback to the waypoint looked at): " + presetTarget());
			checkNoOverlay("plain H with no target");
			DevtestClient.log("ASSERT ok: free ground, looking at Base Camp: look-modifier H targets Base Camp, plain H does not open (no fallback to the waypoint looked at), "
					+ "and sends no action-bar message");
		}, 10);
		step("presets: empty state", this::noPendingScreenshots, () -> {
			command("tp @a -11.5 " + FEET_Y + " 2.5 0 22");
			PresetScreen sc = openPresets(presetLook);
			check(sc.presetButtons().isEmpty() && sc.mode() == PresetScreen.Mode.LIST, "presets shown with none stored");
			check(sc.searchBox() != null && !sc.searchBox().isFocused(), "search box focused on open");
			parkMouse();
		}, 10);
		screenshot("66_presets_empty");
		step("presets: create the six canvas presets via + and Save", this::noPendingScreenshots, () -> {
			PresetManager pm = PresetManager.get();
			for (int i = 0; i < CANVAS_PRESETS.length; i++) {
				Object[] c = CANVAS_PRESETS[i];
				PresetScreen sc = openPresets(presetLook);
				check(sc.plusButton() != null && sc.plusButton().active, "no + button");
				clickCentre(sc, sc.plusButton(), 0);
				Preset draft = sc.draft();
				check(sc.mode() == PresetScreen.Mode.FORM && draft != null && sc.editingNumber() == 0, "+ did not open the new form");
				if (i == 0) {
					check(draft.name.equals("Base Camp") && draft.sameAs(Preset.from(presetLook, "Base Camp")),
							"+ prefill " + draft + " differs from Base Camp " + Preset.from(presetLook, "Base Camp"));
					check(sc.nameBox() != null && sc.nameBox().getValue().equals("Base Camp") && sc.nameBox().isFocused(),
							"name box not prefilled/focused");
					check(sc.deleteButton() == null, "Delete shown in the new form");
				}
				sc.nameBox().setValue((String) c[0]);
				draft.color = (int) c[1];
				draft.style = (WaypointStyle) c[2];
				draft.logoType = LogoType.BLOCK;
				draft.logoBlock = Identifier.withDefaultNamespace((String) c[3]);
				draft.highlightOpacity = (float) c[4];
				draft.throughBlocks = (boolean) c[5];
				draft.showText = (boolean) c[6];
				clickCentre(sc, sc.saveButton(), 0);
				check(sc.mode() == PresetScreen.Mode.LIST && pm.size() == i + 1 && pm.byNumber(i + 1).name.equals(c[0]),
						"Save did not add " + c[0] + " as number " + (i + 1) + ": " + presetTexts());
			}
			check(presetScreen.visibleNumbers().equals(List.of(1, 2, 3, 4, 5, 6)), "list shows " + presetScreen.visibleNumbers());
			check(presetScreen.presetButtons().stream().allMatch(b -> b.active), "preset buttons inactive with a target");
			check(presetScreen.presetButtons().get(0).getMessage().getString().equals("1  Home"), "button text is "
					+ presetScreen.presetButtons().get(0).getMessage().getString());
			DevtestClient.log("ASSERT ok: + prefilled from Base Camp (name \"Base Camp\", same settings); Save added Home..Loot as 1..6");
			// The screenshot target: the popup as H opens it, target found by the real lookup.
			check(WaypyClient.openPresets(mc, true) && mc.gui.screen() instanceof PresetScreen, "look-mode H did not open on Base Camp");
			presetScreen = (PresetScreen) mc.gui.screen();
			check(presetScreen.target() == presetLook, "not looking at Base Camp");
			parkMouse();
		}, 10);
		screenshot("60_presets_list");
		step("presets: number key applies to a single waypoint", this::noPendingScreenshots, () -> {
			Preset mine = PresetManager.get().byNumber(2);
			String name = presetLook.name;
			List<BlockPos> blocks = List.copyOf(presetLook.blocks());
			boolean visible = presetLook.visible;
			check(!presetScreen.searchBox().isFocused(), "search focused before the number key");
			clearOverlay();
			press(GLFW.GLFW_KEY_2, '2');
			check(mc.gui.screen() == null, "popup still open after the number key");
			checkNoOverlay("applying a preset");
			check(copied(presetLook).equals(copied(mine, mine.style)), "key 2 applied " + copied(presetLook) + ", expected " + copied(mine, mine.style));
			check(presetLook.name.equals(name) && presetLook.blocks().equals(blocks) && presetLook.visible == visible
					&& WaypointManager.get().groupOf(presetLook.id) == null, "applying changed name/blocks/visible/group");
			DevtestClient.log("ASSERT ok: key 2 (search unfocused) applied Mine to \"" + name + "\": " + copied(presetLook) + "; name, blocks, visible kept");
		}, 5);
		step("presets: BEACON preset on a clump", this::noPendingScreenshots, () -> {
			PresetScreen sc = openPresets(presetClump);
			Preset home = PresetManager.get().byNumber(1);
			check(home.style == WaypointStyle.BEACON, "Home is not a beacon preset");
			clickCentre(sc, sc.presetButtons().get(0), 0);
			check(mc.gui.screen() == null, "popup still open after the click");
			check(presetClump.style == WaypointStyle.BLOCK_HIGHLIGHT && !presetClump.isBeacon(), "a clump became a beacon");
			check(copied(presetClump).equals(copied(home, WaypointStyle.BLOCK_HIGHLIGHT)), "click applied " + copied(presetClump));
			check(presetClump.size() == 2 && presetClump.name.equals("Wheat Field"), "clump changed");
			DevtestClient.log("ASSERT ok: clicking Home (BEACON) on the 2-block clump kept BLOCK_HIGHLIGHT, applied the rest: " + copied(presetClump));
		}, 5);
		step("presets: search filter", this::noPendingScreenshots, () -> {
			command("tp @a -11.5 " + FEET_Y + " 2.5 0 22");
			mc.gui.setScreen(null);
		}, 20);
		step("presets: type to search", this::noPendingScreenshots, () -> {
			check(WaypyClient.openPresets(mc, true), "look-mode H did not open on Base Camp");
			PresetScreen sc = presetScreen = (PresetScreen) mc.gui.screen();
			check(sc.target() == presetLook, "target is not Base Camp");
			check(!sc.searchBox().isFocused(), "search focused on open");
			type("o");
			check(sc.searchBox().isFocused() && sc.searchBox().getValue().equals("o"), "a letter did not focus the search and type: \""
					+ sc.searchBox().getValue() + "\"");
			check(sc.visibleNumbers().equals(List.of(1, 5, 6)), "\"o\" shows " + sc.visibleNumbers() + ", expected [1, 5, 6] (Home, Portal, Loot)");
			parkMouse();
			DevtestClient.log("ASSERT ok: letter focused the search; \"o\" filters to presets 1, 5, 6 (real numbers kept)");
		}, 10);
		screenshot("61_presets_search");
		step("presets: search keys", this::noPendingScreenshots, () -> {
			PresetScreen sc = presetScreen;
			// Case-insensitive; Enter applies the first match.
			sc.searchBox().setValue("");
			type("LoOt");
			check(sc.visibleNumbers().equals(List.of(6)), "\"LoOt\" shows " + sc.visibleNumbers());
			press(GLFW.GLFW_KEY_ENTER, '\0');
			Preset loot = PresetManager.get().byNumber(6);
			check(mc.gui.screen() == null && copied(presetLook).equals(copied(loot, loot.style)), "Enter did not apply Loot: " + copied(presetLook));
			// Digits type into the focused box; Esc clears it (unfocused, still open); then a digit applies.
			sc = openPresets(presetLook);
			type("m");
			check(sc.visibleNumbers().equals(List.of(1, 2, 3)), "\"m\" shows " + sc.visibleNumbers()); // Home, Mine, Farm
			String beforeDigit = copied(presetLook);
			type("3");
			check(sc.searchBox().getValue().equals("m3") && sc.presetButtons().isEmpty() && copied(presetLook).equals(beforeDigit)
					&& mc.gui.screen() == sc, "a digit in the focused search applied a preset");
			press(GLFW.GLFW_KEY_ESCAPE, '\0');
			check(mc.gui.screen() == sc && sc.searchBox().getValue().isEmpty() && !sc.searchBox().isFocused()
					&& sc.visibleNumbers().size() == 6, "Esc with text did not clear and unfocus the search");
			press(GLFW.GLFW_KEY_4, '4');
			Preset danger = PresetManager.get().byNumber(4);
			check(mc.gui.screen() == null && copied(presetLook).equals(copied(danger, danger.style)), "key 4 after Esc did not apply Danger");
			// Esc in the focused, empty box closes.
			sc = openPresets(presetLook);
			type("x");
			press(GLFW.GLFW_KEY_BACKSPACE, '\0');
			check(sc.searchBox().isFocused() && sc.searchBox().getValue().isEmpty(), "backspace did not empty the box");
			press(GLFW.GLFW_KEY_ESCAPE, '\0');
			check(mc.gui.screen() == null, "Esc in the empty search box did not close");
			DevtestClient.log("ASSERT ok: search case-insensitive (\"LoOt\" -> 6), Enter applied Loot; digit typed into the focused box;"
					+ " Esc cleared+unfocused, then key 4 applied Danger; Esc in the empty box closed");
		}, 5);
		step("presets: + prefill form", this::noPendingScreenshots, () -> {
			// Base Camp as the canvas's "Hidden Cache": lime, Block Selection 40 %, through blocks, chest.
			presetLook.color = 0x80C71F;
			presetLook.style = WaypointStyle.BLOCK_HIGHLIGHT;
			presetLook.logoType = LogoType.BLOCK;
			presetLook.logoBlock = Identifier.withDefaultNamespace("chest");
			presetLook.highlightOpacity = 0.4f;
			presetLook.throughBlocks = true;
			presetLook.showText = false;
			PresetScreen sc = openPresets(presetLook);
			clickCentre(sc, sc.plusButton(), 0);
			check(sc.draft() != null && sc.draft().sameAs(Preset.from(presetLook, "Base Camp")), "+ prefill " + sc.draft());
			parkMouse();
		}, 10);
		screenshot("62_presets_new_form");
		step("presets: + Save -> number 7", this::noPendingScreenshots, () -> {
			PresetScreen sc = presetScreen;
			sc.nameBox().setValue("Cache");
			press(GLFW.GLFW_KEY_ENTER, '\0'); // Enter in the name box saves
			Preset seven = PresetManager.get().byNumber(7);
			check(sc.mode() == PresetScreen.Mode.LIST && seven != null && seven.name.equals("Cache")
					&& seven.sameAs(Preset.from(presetLook, "Cache")), "Save gave " + presetTexts());
			check(sc.visibleNumbers().equals(List.of(1, 2, 3, 4, 5, 6, 7)), "list after Save: " + sc.visibleNumbers());
			DevtestClient.log("ASSERT ok: + copied Base Camp's settings, Enter saved it as preset 7 \"Cache\"");
			// Right-click Farm (3): the edit form with Delete.
			clickCentre(sc, sc.presetButtons().get(2), 1);
			check(sc.mode() == PresetScreen.Mode.FORM && sc.editingNumber() == 3 && sc.deleteButton() != null
					&& sc.draft().sameAs(PresetManager.get().byNumber(3)) && sc.draft() != PresetManager.get().byNumber(3),
					"right-click did not open the edit form for Farm");
			parkMouse();
		}, 10);
		screenshot("63_presets_edit_form");
		step("presets: edit form, narrow (GUI scale 4)", this::noPendingScreenshots, () -> {
			mc.options.guiScale().set(4);
			mc.resizeGui();
			check(presetScreen.mode() == PresetScreen.Mode.FORM && presetScreen.editingNumber() == 3, "resize lost the edit form");
			parkMouse();
		}, 10);
		screenshot("64_presets_edit_form_narrow");
		step("presets: list, narrow (GUI scale 4)", this::noPendingScreenshots, () -> {
			presetScreen.cancelForm();
			check(presetScreen.mode() == PresetScreen.Mode.LIST && presetScreen.visibleNumbers().size() == 7, "Cancel did not return to the list");
			parkMouse();
		}, 10);
		screenshot("67_presets_list_narrow");
		step("presets: GUI scale back; edit, block picker, Delete", this::noPendingScreenshots, () -> {
			mc.options.guiScale().set(2);
			mc.resizeGui();
			PresetScreen sc = presetScreen;
			PresetManager pm = PresetManager.get();
			// Edit Farm: rename, then pick a block in the picker: the form comes back with the edits.
			clickCentre(sc, sc.presetButtons().get(2), 1);
			sc.nameBox().setValue("Farms");
			check(sc.styleButton() != null && sc.styleButton().getValue() == WaypointStyle.BLOCK_HIGHLIGHT, "Farm's style button");
			clickCentre(sc, sc.styleButton(), 0); // Block Selection -> Beacon (the opacity row goes away)
			Button logo = sc.logoBlockButton();
			check(logo != null, "no logo block button");
			clickCentre(sc, logo, 0);
			Screen picker = mc.gui.screen();
			check(picker != null && picker.getClass().getSimpleName().equals("BlockPickerScreen"), "block button did not open the picker");
			type("sponge");
			press(GLFW.GLFW_KEY_ENTER, '\0');
			check(mc.gui.screen() == sc && sc.mode() == PresetScreen.Mode.FORM && sc.editingNumber() == 3, "picker did not return to the form");
			check(sc.draft().name.equals("Farms") && sc.draft().style == WaypointStyle.BEACON
					&& sc.draft().logoBlock.toString().equals("minecraft:sponge") && sc.nameBox().getValue().equals("Farms"),
					"edits lost over the picker: " + sc.draft());
			clickCentre(sc, sc.saveButton(), 0);
			check(pm.size() == 7 && pm.byNumber(3).name.equals("Farms") && pm.byNumber(3).logoBlock.toString().equals("minecraft:sponge"),
					"edit Save gave " + presetTexts());
			// Delete Mine (2): the rest move up, 1..6.
			clickCentre(sc, sc.presetButtons().get(1), 1);
			check(sc.editingNumber() == 2 && sc.deleteButton() != null, "edit form for Mine not open");
			clickCentre(sc, sc.deleteButton(), 0);
			List<String> names = pm.all().stream().map(p -> p.name).toList();
			check(names.equals(List.of("Home", "Farms", "Danger", "Portal", "Loot", "Cache")), "after Delete: " + names);
			check(sc.mode() == PresetScreen.Mode.LIST && sc.visibleNumbers().equals(List.of(1, 2, 3, 4, 5, 6)), "not renumbered: " + sc.visibleNumbers());
			check(sc.presetButtons().get(1).getMessage().getString().equals("2  Farms"), "button 2 reads " + sc.presetButtons().get(1).getMessage().getString());
			DevtestClient.log("ASSERT ok: right-click edit kept number 3 (\"Farms\", sponge via the block picker, edits kept); "
					+ "Delete of 2 renumbered to " + names);
			mc.gui.setScreen(null);
		}, 5);
		step("presets: JSON round trip, corrupt file", this::noPendingScreenshots, this::checkPresetFile, 5);
		step("presets: clean up", this::noPendingScreenshots, () -> {
			WaypointManager wm = WaypointManager.get();
			for (Waypoint w : List.of(presetLook, presetUnder, presetClump)) wm.remove(w.id);
			wm.expireDeletions(true);
			command("tp @a 0.5 " + FEET_Y + " 0.5 0 8");
		}, 20);
	}

	private void checkPresetFile() {
		PresetManager pm = PresetManager.get();
		java.nio.file.Path file = PresetManager.path();
		try {
			presetSnapshot = presetTexts();
			pm.save();
			String json = java.nio.file.Files.readString(file);
			com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
			com.google.gson.JsonArray arr = root.getAsJsonArray("presets");
			check(root.get("version").getAsInt() == 1 && arr.size() == 6, "file has " + arr.size() + " presets");
			com.google.gson.JsonObject home = arr.get(0).getAsJsonObject();
			check(home.get("name").getAsString().equals("Home") && home.get("color").getAsString().equals("#3C44AA")
					&& home.get("style").getAsString().equals("BEACON") && home.get("logoBlock").getAsString().equals("minecraft:red_bed")
					&& home.get("showText").getAsBoolean() && !home.has("number"), "Home written as " + home);
			pm.load();
			check(presetTexts().equals(presetSnapshot), "reload differs:\n" + presetTexts() + "\n" + presetSnapshot);
			// Corrupt file: no presets, the file moved aside (a later save can't destroy it).
			java.nio.file.Files.writeString(file, "{ \"presets\": [ {\"name\": \"Broken\", ");
			pm.load();
			List<java.nio.file.Path> backups;
			try (var files = java.nio.file.Files.list(file.getParent())) {
				backups = files.filter(f -> f.getFileName().toString().startsWith("waypy-presets.json.corrupt-")).toList();
			}
			check(pm.size() == 0 && !java.nio.file.Files.exists(file) && backups.size() == 1, "corrupt file: " + pm.size()
					+ " presets, backups " + backups);
			for (java.nio.file.Path b : backups) java.nio.file.Files.delete(b);
			// Bad entries: skipped (not an object) or loaded with defaults for bad fields.
			java.nio.file.Files.writeString(file, "{\"presets\": [42, {\"name\": \"Odd\", \"color\": \"zz\", \"style\": \"LASER\","
					+ " \"logoType\": 7, \"highlightOpacity\": 3.5}]}");
			pm.load();
			Preset odd = pm.byNumber(1);
			check(pm.size() == 1 && odd.name.equals("Odd") && odd.style == WaypointStyle.BLOCK_HIGHLIGHT && odd.logoType == LogoType.BLOCK
					&& odd.highlightOpacity == 1.0f && odd.logoBlock.equals(WaypointManager.DEFAULT_LOGO), "lenient load gave " + presetTexts());
			// Missing file: no presets.
			java.nio.file.Files.delete(file);
			pm.load();
			check(pm.size() == 0, "missing file loaded " + pm.size() + " presets");
			// Restore the good file.
			java.nio.file.Files.writeString(file, json);
			pm.load();
			check(presetTexts().equals(presetSnapshot), "restore differs");
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
		DevtestClient.log("ASSERT ok: presets JSON round trip (6 presets, " + file.getFileName() + "), corrupt file -> none + backup,"
				+ " bad entries skipped/defaulted, missing file -> none");
	}

	/** Every group (name, fields, members) of the world, for exact before/after comparisons. */
	private static String describeGroups() {
		WaypointManager wm = WaypointManager.get();
		StringBuilder out = new StringBuilder();
		for (WaypointGroup g : wm.groups()) {
			out.append(g.id).append(" \"").append(g.name).append("\" ").append(g.dimension.identifier()).append(" color=")
					.append(Integer.toHexString(g.color)).append(' ').append(g.logoType).append(' ').append(g.logoBlock)
					.append(" text=").append(g.showText).append(" visible=").append(g.visible).append(" members=")
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
		// A clump that loses one block now: its removal session must be over by the time the probe has expired.
		sessionProbe = holdOver(keys, List.of(new BlockPos(30, SURFACE_Y, -27), new BlockPos(31, SURFACE_Y, -27),
				new BlockPos(32, SURFACE_Y, -27)), false);
		sessionProbe.setName("Session Probe");
		sessionProbe.visible = false; // out of the screenshots
		keys.press(mc, new BlockPos(32, SURFACE_Y, -27), false);
		keys.release(mc);
		check(sessionProbe.size() == 2, "session probe tap gave " + sessionProbe.blocks());
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

		// The session probe's removal session (one tap 15+ s ago) has expired: tapping it down to zero now starts a new
		// session, so the undo brings back the 2 blocks it had, not the 3 from before the old tap.
		List<BlockPos> two = List.copyOf(sessionProbe.blocks());
		check(two.size() == 2, "session probe changed: " + two);
		for (BlockPos p : List.of(two.get(1), two.get(0))) {
			keys.press(mc, p, false);
			keys.release(mc);
		}
		check(wm.byId(sessionProbe.id).isEmpty(), "taps did not delete the session probe");
		check(keys.press(mc, two.get(1), true) == sessionProbe, "Shift+N did not restore the session probe");
		keys.release(mc);
		check(sessionProbe.blocks().equals(two) && wm.waypointAt(new BlockPos(32, SURFACE_Y, -27), mc.level.dimension()) == null,
				"session probe restored " + sessionProbe.blocks() + ", expected " + two + " (the old session had expired)");
		for (BlockPos p : two) check(wm.waypointAt(p, mc.level.dimension()) == sessionProbe, "index lacks " + p);
		wm.remove(sessionProbe.id);
		wm.expireDeletions(true);
		DevtestClient.log("ASSERT ok: a removal session ends 15 s after its last removal: taps down to zero restored " + two
				+ " (not the block tapped 15+ s earlier)");
	}

	/**
	 * Disconnect/rejoin: a pending deletion becomes permanent (not saved, not restorable); on load a multi-block BEACON
	 * becomes BLOCK_HIGHLIGHT and a waypoint without {@code showText} keeps its label. Waypoint objects are new afterwards.
	 */
	private void checkLoadMigrations() {
		WaypointManager wm = WaypointManager.get();
		var dim = mc.level.dimension();
		// Groups round-trip: save, reload, the same groups (ids, fields, member order).
		wm.groups().getFirst().visible = false; // a hidden group must round-trip hidden
		wm.markDirty();
		String groupsBefore = describeGroups();
		check(!groupsBefore.isEmpty() && groupsBefore.contains("visible=false"), "no hidden group before the reload");
		wm.onDisconnect();
		wm.onJoin(mc);
		check(describeGroups().equals(groupsBefore), "groups changed by save/reload:\n" + describeGroups() + "vs\n" + groupsBefore);
		DevtestClient.log("ASSERT ok: groups round-trip through the file (incl. visible=false):\n" + groupsBefore.strip());

		// Old file: a group still carrying the removed per-group combineDistance loads fine, the field is ignored and not rewritten.
		java.nio.file.Path groupFile = mc.gameDirectory.toPath().resolve("waypy").resolve(wm.worldKey() + ".json");
		wm.onDisconnect();
		try {
			var root = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(groupFile)).getAsJsonObject();
			check(!root.toString().contains("combineDistance"), "combineDistance was written to the file");
			var group = root.getAsJsonArray("groups").get(0).getAsJsonObject();
			check(group.has("visible") && !group.get("visible").getAsBoolean(), "group visible=false not written: " + group);
			group.addProperty("combineDistance", 37);
			group.remove("visible"); // a file from before the group visible field: loads as shown
			java.nio.file.Files.writeString(groupFile, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root));
		} catch (java.io.IOException e) {
			throw new IllegalStateException("could not edit the waypoint file", e);
		}
		wm.onJoin(mc);
		String groupsOld = groupsBefore.replace("visible=false", "visible=true");
		check(describeGroups().equals(groupsOld), "a group with an old combineDistance field / no visible field loaded differently:\n" + describeGroups());
		DevtestClient.log("ASSERT ok: old file with a per-group combineDistance still loads (field ignored); no group visible field -> visible=true");

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
		for (KeyMapping k : List.of(WaypyClient.createKey, WaypyClient.menuKey, WaypyClient.lookModifierKey, WaypyClient.toggleAllKey,
				WaypyClient.presetsKey)) {
			check(java.util.Arrays.asList(mc.options.keyMappings).contains(k), k.getName() + " not in options.keyMappings");
		}
		var y = com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(org.lwjgl.glfw.GLFW.GLFW_KEY_Y);
		check(WaypyClient.toggleAllKey.getDefaultKey().equals(y), "toggle_all default is not Y: " + WaypyClient.toggleAllKey.getDefaultKey());
		List<String> sharing = new ArrayList<>();
		for (KeyMapping k : mc.options.keyMappings) {
			if (k != WaypyClient.toggleAllKey && k.getDefaultKey().equals(y)) sharing.add(k.getName());
		}
		check(sharing.isEmpty(), "other key mappings default to Y: " + sharing);
		DevtestClient.log("ASSERT ok: key.waypy.toggle_all defaults to Y; no other mapping (vanilla or mod) defaults to Y");
		var h = com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_H);
		check(WaypyClient.presetsKey.getDefaultKey().equals(h) && WaypyClient.presetsKey.getName().equals("key.waypy.presets"),
				"presets key default is " + WaypyClient.presetsKey.getDefaultKey());
		List<String> sharingH = new ArrayList<>();
		for (KeyMapping k : mc.options.keyMappings) {
			if (k != WaypyClient.presetsKey && k.getDefaultKey().equals(h)) sharingH.add(k.getName());
		}
		// The only one is the F3+H debug combo (advanced tooltips), which only acts while F3 is held; vanilla then
		// suppresses the plain key, and the Key Binds screen flags no conflict between two default bindings.
		check(sharingH.equals(List.of("key.debug.showAdvancedTooltips"))
				&& mc.options.keyDebugShowAdvancedTooltips.getCategory() == KeyMapping.Category.DEBUG,
				"mappings sharing H: " + sharingH);
		DevtestClient.log("ASSERT ok: key.waypy.presets (\"Open Presets\") defaults to H; the only other H default is the F3+H debug combo "
				+ sharingH);
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

	/** The HUD's action-bar (overlay) message, or null if none is set ({@code Hud.overlayMessageString}, by reflection). */
	private Component overlayMessage() {
		try {
			java.lang.reflect.Field field = mc.gui.hud.getClass().getDeclaredField("overlayMessageString");
			field.setAccessible(true);
			return (Component) field.get(mc.gui.hud);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not read the overlay message", e);
		}
	}

	private void clearOverlay() {
		try {
			java.lang.reflect.Field field = mc.gui.hud.getClass().getDeclaredField("overlayMessageString");
			field.setAccessible(true);
			field.set(mc.gui.hud, null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not clear the overlay message", e);
		}
	}

	/** Waypy's actions are silent: no action-bar message was set since the last {@link #clearOverlay}. */
	private void checkNoOverlay(String what) {
		Component message = overlayMessage();
		check(message == null, what + " showed an action-bar message: \"" + (message == null ? "" : message.getString()) + "\"");
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
