package dev.waypy.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Global (not per-world) client settings, stored as Gson JSON in {@code <gameDir>/config/waypy.json}.
 *
 * <p>Loaded once at client init ({@link #load()}), saved when the settings screen closes ({@link #save()}).
 * A missing file means defaults; an unreadable or corrupt file is logged and replaced by defaults in memory
 * (the file itself is left alone until the next save). Out-of-range values are clamped/snapped on load.
 */
public final class WaypyConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("waypy");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** {@link #waypointRenderDistance} value meaning "no limit". */
	public static final int UNLIMITED = -1;
	public static final int DEFAULT_RENDER_DISTANCE = 512;
	public static final int MIN_RENDER_DISTANCE = 16;
	public static final int MAX_RENDER_DISTANCE = 4096;
	/**
	 * The slider positions of the render distance, in blocks, ending with {@link #UNLIMITED}:
	 * 16-block steps up to 256, then 32 up to 512, 64 up to 1024, 128 up to 2048 and 256 up to 4096.
	 */
	public static final List<Integer> RENDER_DISTANCE_STEPS;

	public static final int DEFAULT_ICON_SIZE = 100;
	public static final int MIN_ICON_SIZE = 50;
	public static final int MAX_ICON_SIZE = 200;
	/** Granularity of the percentage sliders. */
	public static final int PERCENT_STEP = 5;

	public static final int DEFAULT_COMBINE_DISTANCE = 15;
	public static final int MIN_COMBINE_DISTANCE = 5;
	public static final int MAX_COMBINE_DISTANCE = 200;
	/** The slider positions of a group's combine distance, in blocks: 1-block steps up to 50, then 5-block steps up to 200. */
	public static final List<Integer> COMBINE_DISTANCE_STEPS;

	static {
		List<Integer> steps = new ArrayList<>();
		int value = MIN_RENDER_DISTANCE;
		while (value <= MAX_RENDER_DISTANCE) {
			steps.add(value);
			int step = value < 256 ? 16 : value < 512 ? 32 : value < 1024 ? 64 : value < 2048 ? 128 : 256;
			value += step;
		}
		steps.add(UNLIMITED);
		RENDER_DISTANCE_STEPS = List.copyOf(steps);

		List<Integer> combine = new ArrayList<>();
		for (int d = MIN_COMBINE_DISTANCE; d <= MAX_COMBINE_DISTANCE; d += d < 50 ? 1 : 5) combine.add(d);
		COMBINE_DISTANCE_STEPS = List.copyOf(combine);
	}

	private static WaypyConfig instance = new WaypyConfig();

	// ---------------------------------------------------------------- settings (serialized fields)

	/** Beams and block highlights farther than this (blocks, camera to block centre) are not drawn; {@link #UNLIMITED} = no limit. */
	public int waypointRenderDistance = DEFAULT_RENDER_DISTANCE;
	/** Global logo/label size, percent. */
	public int iconSize = DEFAULT_ICON_SIZE;
	/** Shrink markers with distance (perspective: full size up to 16 blocks, half at 32, down to a fixed floor). */
	public boolean shrinkWithDistance = true;
	public LabelMode labels = LabelMode.ALWAYS;
	/** Show the distance after the name in labels. */
	public boolean showDistance = true;
	/** The combine distance (blocks) new groups start with; each group then has its own. */
	public int groupCombineDistance = DEFAULT_COMBINE_DISTANCE;

	// ---------------------------------------------------------------- access

	public static WaypyConfig get() {
		return instance;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("waypy.json");
	}

	/** Copies every setting from {@code other} into the live config (used by the devtest). */
	public void copyFrom(WaypyConfig other) {
		waypointRenderDistance = other.waypointRenderDistance;
		iconSize = other.iconSize;
		shrinkWithDistance = other.shrinkWithDistance;
		labels = other.labels;
		showDistance = other.showDistance;
		groupCombineDistance = other.groupCombineDistance;
		sanitize();
	}

	/** Max distance (blocks) at which beams/highlights are drawn, or {@link Double#POSITIVE_INFINITY}. */
	public double maxWorldRenderDistance() {
		return waypointRenderDistance == UNLIMITED ? Double.POSITIVE_INFINITY : waypointRenderDistance;
	}

	// ---------------------------------------------------------------- persistence

	/** Loads {@link #path()} into the live config; defaults if missing, defaults + warning if corrupt. */
	public static void load() {
		Path file = path();
		WaypyConfig loaded = new WaypyConfig();
		if (Files.isRegularFile(file)) {
			try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonElement root = JsonParser.parseReader(in);
				if (!root.isJsonObject()) throw new IllegalStateException("root is not a JSON object");
				WaypyConfig parsed = GSON.fromJson(root, WaypyConfig.class);
				if (parsed != null) loaded = parsed;
			} catch (Exception e) {
				LOGGER.warn("[Waypy] Config {} is unreadable or corrupt; using defaults", file, e);
				loaded = new WaypyConfig();
			}
		}
		loaded.sanitize();
		instance = loaded;
	}

	/** Writes the live config to {@link #path()} (atomically where possible). */
	public static void save() {
		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, out);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.error("[Waypy] Failed to save config to {}", file, e);
		}
	}

	/** Brings every value into range: render distance snapped to a slider step, percentages to {@link #PERCENT_STEP}. */
	void sanitize() {
		waypointRenderDistance = snapRenderDistance(waypointRenderDistance);
		iconSize = snapPercent(iconSize, MIN_ICON_SIZE, MAX_ICON_SIZE);
		if (labels == null) labels = LabelMode.ALWAYS;
		groupCombineDistance = snapCombineDistance(groupCombineDistance);
	}

	/** The nearest combine-distance slider step (5..200 blocks). */
	public static int snapCombineDistance(int blocks) {
		int best = MIN_COMBINE_DISTANCE;
		for (int step : COMBINE_DISTANCE_STEPS) {
			if (Math.abs(step - blocks) < Math.abs(best - blocks)) best = step;
		}
		return best;
	}

	/** The nearest slider step; values above 4096 (and {@link #UNLIMITED} or any negative value) become Unlimited. */
	public static int snapRenderDistance(int blocks) {
		if (blocks < 0 || blocks > MAX_RENDER_DISTANCE) return UNLIMITED;
		int best = MIN_RENDER_DISTANCE;
		for (int step : RENDER_DISTANCE_STEPS) {
			if (step != UNLIMITED && Math.abs(step - blocks) < Math.abs(best - blocks)) best = step;
		}
		return best;
	}

	private static int snapPercent(int value, int min, int max) {
		int snapped = Math.round(value / (float) PERCENT_STEP) * PERCENT_STEP;
		return Math.clamp(snapped, min, max);
	}
}
