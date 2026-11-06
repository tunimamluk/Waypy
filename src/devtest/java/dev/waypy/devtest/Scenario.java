package dev.waypy.devtest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import dev.waypy.config.LabelMode;
import dev.waypy.config.WaypyConfig;
import dev.waypy.gui.WaypointScreen;
import dev.waypy.gui.WaypySettingsScreen;
import dev.waypy.waypoint.LogoType;
import dev.waypy.waypoint.Waypoint;
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
 * <ul>
 *   <li>"Diamond Beacon": BEACON, BLOCK logo, red, on a diamond block on top of a hill, ~37 blocks away on the right.</li>
 *   <li>"Lapis Beacon": BEACON, BLOCK logo (lapis), light blue, on a lapis block ~64 blocks away on the left.</li>
 *   <li>"Hidden Cache": BLOCK_HIGHLIGHT, throughBlocks=true, lime, behind a stone-brick wall ~24 blocks ahead.</li>
 *   <li>"Gold Block": BLOCK_HIGHLIGHT, throughBlocks=false, logo NONE (label only), orange, on a gold block ~13 blocks ahead on the left.</li>
 *   <li>"Far Beacon": BEACON, BLOCK logo, purple, ~200 blocks ahead, far outside the render distance (its chunk is
 *       not loaded), to check that far beams are drawn without fog.</li>
 * </ul>
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

		menuShot("10_menu_beacon_block", () -> beaconBlock);
		menuShot("11_menu_beacon_lapis", () -> beaconLapis);
		menuShot("12_menu_highlight_block", () -> highlightThrough);
		menuShot("13_menu_highlight_no_logo", () -> highlightSolid);
		menuShot("14_menu_no_selection", () -> null);

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
		step("restore default settings (end)", () -> true, () -> setConfig(c -> {
		}), 5);

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
				"setblock 16 " + FEET_Y + " 62 lapis_block")) {
			command(cmd);
		}
	}

	private void createWaypoints() {
		WaypointManager wm = WaypointManager.get();
		for (Waypoint w : wm.all()) wm.remove(w.id);
		var dim = mc.level.dimension();

		beaconBlock = wm.create(new BlockPos(-21, FEET_Y + 5, 30), dim, Identifier.withDefaultNamespace("diamond_block"));
		beaconBlock.setName("Diamond Beacon");
		beaconBlock.color = 0xE53935;
		beaconBlock.style = WaypointStyle.BEACON;
		beaconBlock.logoType = LogoType.BLOCK;

		beaconLapis = wm.create(new BlockPos(16, FEET_Y, 62), dim, Identifier.withDefaultNamespace("lapis_block"));
		beaconLapis.setName("Lapis Beacon");
		beaconLapis.color = 0x3AB3DA;
		beaconLapis.style = WaypointStyle.BEACON;
		beaconLapis.logoType = LogoType.BLOCK;

		highlightThrough = wm.create(new BlockPos(0, SURFACE_Y, 24), dim, Identifier.withDefaultNamespace("chest"));
		highlightThrough.setName("Hidden Cache");
		highlightThrough.color = 0x80C71F;
		highlightThrough.style = WaypointStyle.BLOCK_HIGHLIGHT;
		highlightThrough.logoType = LogoType.BLOCK;
		highlightThrough.throughBlocks = true;
		highlightThrough.highlightOpacity = 0.4f;

		highlightSolid = wm.create(new BlockPos(6, FEET_Y, 12), dim, Identifier.withDefaultNamespace("gold_block"));
		highlightSolid.setName("Gold Block");
		highlightSolid.color = 0xF9801D;
		highlightSolid.style = WaypointStyle.BLOCK_HIGHLIGHT;
		highlightSolid.logoType = LogoType.NONE;
		highlightSolid.throughBlocks = false;
		highlightSolid.highlightOpacity = 0.6f;

		farBeacon = wm.create(new BlockPos(10, FEET_Y, 200), dim, Identifier.withDefaultNamespace("amethyst_block"));
		farBeacon.setName("Far Beacon");
		farBeacon.color = 0x8932B8;
		farBeacon.style = WaypointStyle.BEACON;
		farBeacon.logoType = LogoType.BLOCK;

		wm.select(beaconBlock.id);
		wm.markDirty();
		DevtestClient.log("created " + wm.all().size() + " waypoints in " + dim.identifier());
	}

	/** Resets the live config to the defaults, applies {@code change} and saves it to config/waypy.json. */
	private static void setConfig(Consumer<WaypyConfig> change) {
		WaypyConfig wanted = new WaypyConfig();
		change.accept(wanted);
		WaypyConfig.get().copyFrom(wanted);
		WaypyConfig.save();
		DevtestClient.log("settings: renderDistance=" + WaypyConfig.get().waypointRenderDistance + " iconSize=" + WaypyConfig.get().iconSize
				+ " shrink=" + WaypyConfig.get().shrinkWithDistance + " min=" + WaypyConfig.get().minIconSize
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
