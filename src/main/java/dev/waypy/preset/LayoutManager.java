package dev.waypy.preset;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import dev.waypy.waypoint.LogoType;
import dev.waypy.waypoint.WaypointManager;
import dev.waypy.waypoint.WaypointStyle;

/**
 * The layout presets: one ordered list for all worlds, stored as JSON in {@code <gameDir>/config/waypy-layouts.json}
 * (block offsets kept on one line each, like the waypoint files). Numbered like presets: position plus one, so deleting
 * one renumbers the rest and a rename keeps the number.
 *
 * <p>Loaded once at client init ({@link #load()}) and saved at once after every change. A missing file means no
 * layouts. A corrupt file (unparseable, or {@code layouts} not an array) is logged, moved aside to
 * {@code waypy-layouts.json.corrupt-<millis>} and the list starts empty. Entries that aren't objects or have no
 * member with a valid block are skipped; invalid fields get the new-waypoint defaults.
 */
public final class LayoutManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("waypy");
	private static final int FORMAT_VERSION = 1;
	private static final LayoutManager INSTANCE = new LayoutManager();

	private final List<Layout> layouts = new ArrayList<>();

	private LayoutManager() {
	}

	public static LayoutManager get() {
		return INSTANCE;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("waypy-layouts.json");
	}

	// ---------------------------------------------------------------- access

	/** The layouts in number order (an immutable snapshot; layout {@code n} is at index {@code n - 1}). */
	public synchronized List<Layout> all() {
		return List.copyOf(layouts);
	}

	public synchronized int size() {
		return layouts.size();
	}

	/** The layout with number {@code number} (1-based), or null. */
	public synchronized @Nullable Layout byNumber(int number) {
		return number >= 1 && number <= layouts.size() ? layouts.get(number - 1) : null;
	}

	/** The number of {@code layout} (by identity), or -1 if it is not stored. */
	public synchronized int numberOf(Layout layout) {
		for (int i = 0; i < layouts.size(); i++) {
			if (layouts.get(i) == layout) return i + 1;
		}
		return -1;
	}

	// ---------------------------------------------------------------- changes (each saves at once)

	/** Appends {@code layout} with the next number and saves. @return its number */
	public synchronized int add(Layout layout) {
		layouts.add(layout);
		save();
		return layouts.size();
	}

	/** Renames layout {@code number} (it keeps its number) and saves. False if there is no such layout. */
	public synchronized boolean rename(int number, String name) {
		Layout layout = byNumber(number);
		if (layout == null) return false;
		layout.setName(name);
		save();
		return true;
	}

	/** Deletes layout {@code number}; the later ones move up a number. Saves. */
	public synchronized boolean remove(int number) {
		if (number < 1 || number > layouts.size()) return false;
		layouts.remove(number - 1);
		save();
		return true;
	}

	// ---------------------------------------------------------------- persistence

	/** Loads {@link #path()}, replacing the list: empty if missing; empty (and the file moved aside) if corrupt. */
	public synchronized void load() {
		layouts.clear();
		Path file = path();
		if (!Files.isRegularFile(file)) return;
		JsonObject root;
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement el = JsonParser.parseReader(in);
			if (el == null || !el.isJsonObject()) throw new IllegalStateException("root is not a JSON object");
			root = el.getAsJsonObject();
			if (root.has("layouts") && !root.get("layouts").isJsonArray()) throw new IllegalStateException("layouts is not an array");
		} catch (Exception e) {
			LOGGER.error("[Waypy] Layout file {} is unreadable or corrupt; starting with no layouts", file, e);
			JsonFiles.backupCorrupt(file);
			return;
		}
		if (!root.has("layouts")) return;
		for (JsonElement el : root.getAsJsonArray("layouts")) {
			try {
				Layout layout = el.isJsonObject() ? fromJson(el.getAsJsonObject()) : null;
				if (layout != null) {
					layouts.add(layout);
				} else {
					LOGGER.warn("[Waypy] Skipping invalid layout {} in {}", el, file);
				}
			} catch (RuntimeException e) {
				LOGGER.warn("[Waypy] Skipping invalid layout {} in {}", el, file, e);
			}
		}
	}

	/** Writes the list to {@link #path()} (atomically where possible). */
	public synchronized void save() {
		Path file = path();
		JsonObject root = new JsonObject();
		root.addProperty("version", FORMAT_VERSION);
		JsonArray arr = new JsonArray();
		for (Layout l : layouts) arr.add(toJson(l));
		root.add("layouts", arr);
		try {
			JsonFiles.writeAtomically(file, WaypointManager.toCompactJson(root) + "\n");
		} catch (IOException | RuntimeException e) {
			LOGGER.error("[Waypy] Failed to save layouts to {}", file, e);
		}
	}

	private static JsonObject toJson(Layout l) {
		JsonObject o = new JsonObject();
		o.addProperty("name", l.name);
		JsonObject g = new JsonObject();
		g.addProperty("name", l.group.name);
		g.addProperty("color", JsonFiles.colorString(l.group.color));
		g.addProperty("logoType", l.group.logoType.name());
		g.addProperty("logoBlock", l.group.logoBlock.toString());
		g.addProperty("showText", l.group.showText);
		g.addProperty("visible", l.group.visible);
		o.add("group", g);
		JsonArray members = new JsonArray();
		for (Layout.Member m : l.members) {
			JsonObject mo = new JsonObject();
			mo.addProperty("name", m.name);
			mo.addProperty("color", JsonFiles.colorString(m.color));
			mo.addProperty("style", m.style.name());
			mo.addProperty("logoType", m.logoType.name());
			mo.addProperty("logoBlock", m.logoBlock.toString());
			mo.addProperty("highlightOpacity", m.highlightOpacity);
			mo.addProperty("throughBlocks", m.throughBlocks);
			mo.addProperty("showText", m.showText);
			mo.addProperty("visible", m.visible);
			JsonArray blocks = new JsonArray();
			for (BlockPos p : m.offsets) {
				JsonArray xyz = new JsonArray();
				xyz.add(p.getX());
				xyz.add(p.getY());
				xyz.add(p.getZ());
				blocks.add(xyz);
			}
			mo.add("blocks", blocks);
			members.add(mo);
		}
		o.add("members", members);
		return o;
	}

	/** A layout from the file, or null if it has no member with a valid block. Invalid fields get the defaults. */
	private static @Nullable Layout fromJson(JsonObject o) {
		JsonObject g = o.has("group") && o.get("group").isJsonObject() ? o.getAsJsonObject("group") : new JsonObject();
		Layout.GroupSettings group = new Layout.GroupSettings(JsonFiles.parseString(g, "name", "Group"), JsonFiles.parseColor(g, "color"));
		group.logoType = JsonFiles.parseEnum(LogoType.class, g, "logoType", LogoType.BLOCK);
		group.logoBlock = JsonFiles.parseIdentifier(g, "logoBlock", WaypointManager.DEFAULT_LOGO);
		group.showText = JsonFiles.parseBoolean(g, "showText", false);
		group.visible = JsonFiles.parseBoolean(g, "visible", true);

		List<Layout.Member> members = new ArrayList<>();
		if (o.has("members") && o.get("members").isJsonArray()) {
			for (JsonElement el : o.getAsJsonArray("members")) {
				if (!el.isJsonObject()) continue;
				Layout.Member m = memberFromJson(el.getAsJsonObject());
				if (m != null) members.add(m);
			}
		}
		if (members.isEmpty()) return null;
		return new Layout(JsonFiles.parseString(o, "name", group.name), group, members);
	}

	private static Layout.@Nullable Member memberFromJson(JsonObject o) {
		List<BlockPos> offsets = new ArrayList<>();
		if (o.has("blocks") && o.get("blocks").isJsonArray()) {
			for (JsonElement b : o.getAsJsonArray("blocks")) {
				try {
					JsonArray xyz = b.getAsJsonArray();
					if (xyz.size() != 3) continue;
					offsets.add(new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()));
				} catch (RuntimeException e) {
					// not an [x, y, z] array of numbers: skip it
				}
			}
		}
		if (offsets.isEmpty()) return null;
		Layout.Member m = new Layout.Member(JsonFiles.parseString(o, "name", "Waypoint"), JsonFiles.parseColor(o, "color"), offsets);
		m.style = JsonFiles.parseEnum(WaypointStyle.class, o, "style", WaypointStyle.BLOCK_HIGHLIGHT);
		if (m.offsets.size() > 1) m.style = WaypointStyle.BLOCK_HIGHLIGHT; // a clump is never a beacon
		m.logoType = JsonFiles.parseEnum(LogoType.class, o, "logoType", LogoType.BLOCK);
		m.logoBlock = JsonFiles.parseIdentifier(o, "logoBlock", WaypointManager.DEFAULT_LOGO);
		m.highlightOpacity = JsonFiles.parseOpacity(o, "highlightOpacity", 0.4f);
		m.throughBlocks = JsonFiles.parseBoolean(o, "throughBlocks", false);
		m.showText = JsonFiles.parseBoolean(o, "showText", false);
		m.visible = JsonFiles.parseBoolean(o, "visible", true);
		return m;
	}
}
