package dev.waypy.preset;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

import dev.waypy.waypoint.LogoType;
import dev.waypy.waypoint.WaypointManager;
import dev.waypy.waypoint.WaypointStyle;

/**
 * The presets: one ordered list for all worlds, stored as Gson JSON in {@code <gameDir>/config/waypy-presets.json}.
 * A preset's number is its position plus one, so deleting one renumbers the rest 1..n in order, and replacing one
 * (an edit) keeps its number.
 *
 * <p>Loaded once at client init ({@link #load()}) and saved at once after every change. A missing file means no
 * presets (none ship by default). A corrupt file is logged, moved aside to {@code waypy-presets.json.corrupt-<millis>}
 * (so the next save can't destroy it) and the list starts empty. Invalid entries are skipped and invalid fields fall
 * back to the defaults of a new waypoint.
 */
public final class PresetManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("waypy");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int FORMAT_VERSION = 1;
	private static final PresetManager INSTANCE = new PresetManager();

	private final List<Preset> presets = new ArrayList<>();

	private PresetManager() {
	}

	public static PresetManager get() {
		return INSTANCE;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("waypy-presets.json");
	}

	// ---------------------------------------------------------------- access

	/** The presets in number order (an immutable snapshot; preset {@code n} is at index {@code n - 1}). */
	public synchronized List<Preset> all() {
		return List.copyOf(presets);
	}

	public synchronized int size() {
		return presets.size();
	}

	/** The preset with number {@code number} (1-based), or null. */
	public synchronized @Nullable Preset byNumber(int number) {
		return number >= 1 && number <= presets.size() ? presets.get(number - 1) : null;
	}

	/** The number of {@code preset} (by identity), or -1 if it is not stored. */
	public synchronized int numberOf(Preset preset) {
		for (int i = 0; i < presets.size(); i++) {
			if (presets.get(i) == preset) return i + 1;
		}
		return -1;
	}

	/** The number the next {@link #add} gets. */
	public synchronized int nextNumber() {
		return presets.size() + 1;
	}

	// ---------------------------------------------------------------- changes (each saves at once)

	/** Appends {@code preset} with the next number and saves. @return its number */
	public synchronized int add(Preset preset) {
		presets.add(preset);
		save();
		return presets.size();
	}

	/** Replaces preset {@code number} (an edit keeps the number) and saves. False if there is no such preset. */
	public synchronized boolean replace(int number, Preset preset) {
		if (number < 1 || number > presets.size()) return false;
		presets.set(number - 1, preset);
		save();
		return true;
	}

	/** Deletes preset {@code number}; the later ones move up a number (1..n stays gapless). Saves. */
	public synchronized boolean remove(int number) {
		if (number < 1 || number > presets.size()) return false;
		presets.remove(number - 1);
		save();
		return true;
	}

	// ---------------------------------------------------------------- persistence

	/** Loads {@link #path()}, replacing the list: empty if missing; empty (and the file moved aside) if corrupt. */
	public synchronized void load() {
		presets.clear();
		Path file = path();
		if (!Files.isRegularFile(file)) return;
		JsonObject root;
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement el = JsonParser.parseReader(in);
			if (el == null || !el.isJsonObject()) throw new IllegalStateException("root is not a JSON object");
			root = el.getAsJsonObject();
			if (root.has("presets") && !root.get("presets").isJsonArray()) throw new IllegalStateException("presets is not an array");
		} catch (Exception e) {
			LOGGER.error("[Waypy] Preset file {} is unreadable or corrupt; starting with no presets", file, e);
			JsonFiles.backupCorrupt(file);
			return;
		}
		if (!root.has("presets")) return;
		for (JsonElement el : root.getAsJsonArray("presets")) {
			try {
				if (el.isJsonObject()) presets.add(fromJson(el.getAsJsonObject()));
			} catch (RuntimeException e) {
				LOGGER.warn("[Waypy] Skipping invalid preset {} in {}", el, file, e);
			}
		}
	}

	/** Writes the list to {@link #path()} (atomically where possible). */
	public synchronized void save() {
		Path file = path();
		JsonObject root = new JsonObject();
		root.addProperty("version", FORMAT_VERSION);
		JsonArray arr = new JsonArray();
		for (Preset p : presets) arr.add(toJson(p));
		root.add("presets", arr);
		try {
			JsonFiles.writeAtomically(file, GSON.toJson(root) + "\n");
		} catch (IOException | RuntimeException e) {
			LOGGER.error("[Waypy] Failed to save presets to {}", file, e);
		}
	}

	private static JsonObject toJson(Preset p) {
		JsonObject o = new JsonObject();
		o.addProperty("name", p.name);
		o.addProperty("color", JsonFiles.colorString(p.color));
		o.addProperty("style", p.style.name());
		o.addProperty("logoType", p.logoType.name());
		o.addProperty("logoBlock", p.logoBlock.toString());
		o.addProperty("highlightOpacity", p.highlightOpacity);
		o.addProperty("throughBlocks", p.throughBlocks);
		o.addProperty("showText", p.showText);
		return o;
	}

	/** A preset from the file; missing or invalid fields get the defaults of a new waypoint. */
	private static Preset fromJson(JsonObject o) {
		Preset p = new Preset(o.has("name") ? o.get("name").getAsString() : "Preset", JsonFiles.parseColor(o, "color"));
		p.style = JsonFiles.parseEnum(WaypointStyle.class, o, "style", WaypointStyle.BLOCK_HIGHLIGHT);
		p.logoType = JsonFiles.parseEnum(LogoType.class, o, "logoType", LogoType.BLOCK);
		p.logoBlock = JsonFiles.parseIdentifier(o, "logoBlock", WaypointManager.DEFAULT_LOGO);
		p.highlightOpacity = JsonFiles.parseOpacity(o, "highlightOpacity", p.highlightOpacity);
		p.throughBlocks = JsonFiles.parseBoolean(o, "throughBlocks", false);
		p.showText = JsonFiles.parseBoolean(o, "showText", false);
		return p;
	}
}
