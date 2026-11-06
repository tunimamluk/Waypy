package dev.waypy.devtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Dev-only headless screenshot harness. Loaded only by the {@code runDevtest} Loom run config.
 *
 * <p>Boot: the run config quick-plays into the {@code waypy-devtest} world. If that world does not exist yet,
 * quick play lands on an error screen (or the title screen); the harness then creates a creative, peaceful,
 * cheats-enabled superflat world with a fixed seed. Once in the world it runs {@link Scenario} from the client
 * tick, then stops the game. Failures and timeouts exit the JVM with a non-zero code.
 */
public final class DevtestClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("waypy-devtest");
	public static final String WORLD_ID = "waypy-devtest";
	private static final long SEED = 20260602L;

	private static final AtomicBoolean failed = new AtomicBoolean();
	private static final List<String> resultLines = new ArrayList<>();
	private static volatile boolean finished;

	private boolean worldRequested;
	private int bootTicks;
	private Scenario scenario;

	@Override
	public void onInitializeClient() {
		if (!Boolean.getBoolean("waypy.devtest")) {
			LOG.warn("[devtest] waypy.devtest system property not set; harness disabled");
			return;
		}
		int timeoutSeconds = Integer.getInteger("waypy.devtest.timeout", 900);
		startWatchdog(timeoutSeconds);
		log("harness loaded, global timeout " + timeoutSeconds + "s");
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft mc) {
		if (finished) return;
		try {
			if (scenario != null) {
				scenario.tick();
				return;
			}
			bootTicks++;
			if (mc.level != null && mc.player != null && mc.gui.screen() == null) {
				log("joined world " + WORLD_ID + " after " + bootTicks + " boot ticks");
				scenario = new Scenario(mc);
				return;
			}
			Screen screen = mc.gui.screen();
			if (!worldRequested && mc.level == null && (screen instanceof TitleScreen || screen instanceof DisconnectedScreen)) {
				worldRequested = true;
				if (mc.getLevelSource().levelExists(WORLD_ID)) {
					log("quick play did not open the world; opening existing world " + WORLD_ID);
					mc.createWorldOpenFlows().openWorld(WORLD_ID, () -> fail("could not open world " + WORLD_ID, null));
				} else {
					log("world " + WORLD_ID + " does not exist; creating superflat world (seed " + SEED + ")");
					createWorld(mc);
				}
			}
		} catch (Throwable t) {
			fail("exception in client tick", t);
		}
	}

	private static void createWorld(Minecraft mc) {
		LevelSettings settings = new LevelSettings(WORLD_ID, GameType.CREATIVE,
				new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
		WorldOptions options = new WorldOptions(SEED, false, false);
		mc.createWorldOpenFlows().createFreshLevel(WORLD_ID, settings, options,
				registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
				new TitleScreen());
	}

	// ---------------------------------------------------------------- results / exit

	public static void log(String msg) {
		LOG.info("[devtest] {}", msg);
		synchronized (resultLines) {
			resultLines.add(msg);
		}
	}

	/** Marks the run successful and stops the game normally. */
	public static void succeed(Minecraft mc) {
		if (finished) return;
		finished = true;
		log("RESULT: OK");
		writeResult("OK");
		mc.stop();
	}

	/** Logs the failure, stops the game, and makes the JVM exit with code 1 once the render thread has finished. */
	public static void fail(String why, Throwable t) {
		if (!failed.compareAndSet(false, true)) return;
		finished = true;
		LOG.error("[devtest] FAILED: {}", why, t);
		log("RESULT: FAIL: " + why + (t != null ? " (" + t + ")" : ""));
		writeResult("FAIL");
		Thread renderThread = Thread.currentThread();
		Thread exiter = new Thread(() -> {
			try {
				renderThread.join(120_000);
			} catch (InterruptedException ignored) {
			}
			LOG.error("[devtest] exiting with code 1");
			System.exit(1);
		}, "devtest-fail-exit");
		exiter.setDaemon(false);
		exiter.start();
		Minecraft.getInstance().stop();
	}

	private static void writeResult(String status) {
		Path file = gameDir().resolve("devtest-result.txt");
		try {
			List<String> lines;
			synchronized (resultLines) {
				lines = new ArrayList<>(resultLines);
			}
			lines.addFirst("status=" + status);
			Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException e) {
			LOG.error("[devtest] could not write {}", file, e);
		}
	}

	static Path gameDir() {
		return Minecraft.getInstance().gameDirectory.toPath();
	}

	private static void startWatchdog(int timeoutSeconds) {
		Thread t = new Thread(() -> {
			try {
				Thread.sleep(timeoutSeconds * 1000L);
			} catch (InterruptedException e) {
				return;
			}
			if (finished && !failed.get()) {
				// Finished but still shutting down; give it a little longer, then force.
				try {
					Thread.sleep(60_000);
				} catch (InterruptedException e) {
					return;
				}
				LOG.error("[devtest] shutdown hung; halting");
				Runtime.getRuntime().halt(0);
			}
			LOG.error("[devtest] TIMEOUT after {}s; halting with code 2", timeoutSeconds);
			synchronized (resultLines) {
				resultLines.add("RESULT: TIMEOUT after " + timeoutSeconds + "s");
			}
			writeResult("TIMEOUT");
			Runtime.getRuntime().halt(2);
		}, "devtest-watchdog");
		t.setDaemon(true);
		t.start();
	}
}
