package dev.waypy.waypoint;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Holds the waypoints of the current world and persists them as JSON at
 * {@code <gameDir>/waypy/<worldKey>.json}.
 *
 * <p>Lifecycle (wired in {@code WaypyClient}): {@link #onJoin(Minecraft)} loads on world join,
 * {@link #onDisconnect()} saves and clears on disconnect, {@link #tick()} flushes pending
 * {@link #markDirty()} changes after a short debounce. {@link #save()} writes immediately.
 *
 * <p>All public methods are synchronized; list getters return immutable snapshots, but the
 * {@link Waypoint} objects themselves are live and may be mutated (then call {@link #markDirty()}).
 */
public final class WaypointManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("waypy");
	private static final WaypointManager INSTANCE = new WaypointManager();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int FORMAT_VERSION = 1;
	/** Ticks between a {@link #markDirty()} and the automatic save. */
	private static final int SAVE_DELAY_TICKS = 20;
	public static final Identifier DEFAULT_LOGO = Identifier.withDefaultNamespace("compass");

	/** Palette new waypoints cycle through: the 16 vanilla dye colors. */
	private static final int[] PALETTE;

	static {
		DyeColor[] dyes = DyeColor.values();
		PALETTE = new int[dyes.length];
		for (int i = 0; i < dyes.length; i++) {
			PALETTE[i] = dyes[i].getTextureDiffuseColor() & 0xFFFFFF;
		}
	}

	private final List<Waypoint> waypoints = new ArrayList<>();
	private @Nullable UUID selectedId;
	private @Nullable String worldKey;
	private int colorCursor;
	private boolean dirty;
	private int dirtyTicks;

	private WaypointManager() {
	}

	public static WaypointManager get() {
		return INSTANCE;
	}

	// ---------------------------------------------------------------- queries

	public synchronized List<Waypoint> all() {
		return List.copyOf(waypoints);
	}

	public synchronized List<Waypoint> inDimension(ResourceKey<Level> dimension) {
		List<Waypoint> out = new ArrayList<>();
		for (Waypoint w : waypoints) {
			if (w.dimension.equals(dimension)) out.add(w);
		}
		return List.copyOf(out);
	}

	public synchronized Optional<Waypoint> byId(UUID id) {
		for (Waypoint w : waypoints) {
			if (w.id.equals(id)) return Optional.of(w);
		}
		return Optional.empty();
	}

	public synchronized Optional<Waypoint> selected() {
		return selectedId == null ? Optional.empty() : byId(selectedId);
	}

	/** The key of the loaded world (e.g. {@code sp_New_World}), or null when not in a world. */
	public synchronized @Nullable String worldKey() {
		return worldKey;
	}

	// ---------------------------------------------------------------- mutations

	/** Creates a waypoint with the DESIGN.md defaults, adds it and marks the manager dirty. Does not select it. */
	public synchronized Waypoint create(BlockPos pos, ResourceKey<Level> dimension, @Nullable Identifier logoBlock) {
		Waypoint w = new Waypoint(UUID.randomUUID(), nextFreeName(), pos.immutable(), dimension,
				PALETTE[Math.floorMod(colorCursor++, PALETTE.length)], logoBlock != null ? logoBlock : DEFAULT_LOGO);
		waypoints.add(w);
		markDirty();
		return w;
	}

	public synchronized void remove(UUID id) {
		if (waypoints.removeIf(w -> w.id.equals(id))) {
			if (id.equals(selectedId)) selectedId = null;
			markDirty();
		}
	}

	/** Selects the waypoint with this id; pass {@code null} to clear the selection. */
	public synchronized void select(@Nullable UUID id) {
		if (id != null && byId(id).isEmpty()) return;
		if (!java.util.Objects.equals(selectedId, id)) {
			selectedId = id;
			markDirty();
		}
	}

	/** Schedules a save shortly (debounced). */
	public synchronized void markDirty() {
		dirty = true;
		dirtyTicks = 0;
	}

	/** Called every client tick; flushes debounced changes. */
	public synchronized void tick() {
		if (dirty && ++dirtyTicks >= SAVE_DELAY_TICKS) {
			save();
		}
	}

	// ---------------------------------------------------------------- lifecycle

	/** Loads the waypoints of the world the client just joined. */
	public synchronized void onJoin(Minecraft minecraft) {
		if (worldKey != null) save();
		clear();
		worldKey = computeWorldKey(minecraft);
		load();
	}

	/** Saves and unloads the current world's waypoints. */
	public synchronized void onDisconnect() {
		if (worldKey == null) return;
		save();
		clear();
		worldKey = null;
	}

	private void clear() {
		waypoints.clear();
		selectedId = null;
		colorCursor = 0;
		dirty = false;
		dirtyTicks = 0;
	}

	// ---------------------------------------------------------------- persistence

	private static Path directory() {
		return FabricLoader.getInstance().getGameDir().resolve("waypy");
	}

	private @Nullable Path file() {
		return worldKey == null ? null : directory().resolve(worldKey + ".json");
	}

	/** Writes the current world's waypoints to disk immediately (atomically where possible). */
	public synchronized void save() {
		dirty = false;
		dirtyTicks = 0;
		Path file = file();
		if (file == null) return;

		JsonObject root = new JsonObject();
		root.addProperty("version", FORMAT_VERSION);
		if (selectedId != null) root.addProperty("selected", selectedId.toString());
		root.addProperty("colorCursor", colorCursor);
		JsonArray arr = new JsonArray();
		for (Waypoint w : waypoints) arr.add(toJson(w));
		root.add("waypoints", arr);

		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, out);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.error("[Waypy] Failed to save waypoints to {}", file, e);
		}
	}

	private void load() {
		Path file = file();
		if (file == null || !Files.isRegularFile(file)) return;

		JsonObject root;
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement el = JsonParser.parseReader(in);
			if (!el.isJsonObject()) throw new IllegalStateException("root is not a JSON object");
			root = el.getAsJsonObject();
		} catch (Exception e) {
			LOGGER.error("[Waypy] Waypoint file {} is unreadable or corrupt; starting empty", file, e);
			backupCorrupt(file);
			return;
		}

		Set<UUID> seen = new HashSet<>();
		if (root.has("waypoints") && root.get("waypoints").isJsonArray()) {
			for (JsonElement el : root.getAsJsonArray("waypoints")) {
				try {
					Waypoint w = fromJson(el.getAsJsonObject());
					if (seen.add(w.id)) waypoints.add(w);
				} catch (Exception e) {
					LOGGER.warn("[Waypy] Skipping invalid waypoint entry in {}: {}", file, el, e);
				}
			}
		}
		try {
			if (root.has("selected")) {
				UUID sel = UUID.fromString(root.get("selected").getAsString());
				if (seen.contains(sel)) selectedId = sel;
			}
			if (root.has("colorCursor")) colorCursor = root.get("colorCursor").getAsInt();
			else colorCursor = waypoints.size();
		} catch (Exception e) {
			LOGGER.warn("[Waypy] Ignoring invalid metadata in {}", file, e);
		}
	}

	private static void backupCorrupt(Path file) {
		try {
			Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
			Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
			LOGGER.warn("[Waypy] Moved corrupt file to {}", backup);
		} catch (IOException e) {
			LOGGER.error("[Waypy] Could not back up corrupt file {}", file, e);
		}
	}

	private static JsonObject toJson(Waypoint w) {
		JsonObject o = new JsonObject();
		o.addProperty("id", w.id.toString());
		o.addProperty("name", w.name);
		o.addProperty("x", w.pos.getX());
		o.addProperty("y", w.pos.getY());
		o.addProperty("z", w.pos.getZ());
		o.addProperty("dimension", w.dimension.identifier().toString());
		o.addProperty("color", String.format(Locale.ROOT, "#%06X", w.color & 0xFFFFFF));
		o.addProperty("style", w.style.name());
		o.addProperty("logoType", w.logoType.name());
		o.addProperty("logoBlock", w.logoBlock.toString());
		o.addProperty("logoText", w.logoText);
		o.addProperty("highlightOpacity", w.highlightOpacity);
		o.addProperty("throughBlocks", w.throughBlocks);
		o.addProperty("visible", w.visible);
		return o;
	}

	private static Waypoint fromJson(JsonObject o) {
		UUID id = UUID.fromString(o.get("id").getAsString());
		BlockPos pos = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
		ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dimension").getAsString()));
		int color = parseColor(o.get("color"));
		Identifier logo = DEFAULT_LOGO;
		if (o.has("logoBlock")) {
			Identifier parsed = Identifier.tryParse(o.get("logoBlock").getAsString());
			if (parsed != null) logo = parsed;
		}
		String name = o.has("name") ? o.get("name").getAsString() : "Waypoint";

		Waypoint w = new Waypoint(id, name, pos, dim, color, logo);
		w.style = parseEnum(WaypointStyle.class, o, "style", WaypointStyle.BEACON);
		w.logoType = parseEnum(LogoType.class, o, "logoType", LogoType.BLOCK);
		if (o.has("logoText")) w.setLogoText(o.get("logoText").getAsString());
		if (o.has("highlightOpacity")) {
			float f = o.get("highlightOpacity").getAsFloat();
			w.highlightOpacity = Float.isFinite(f) ? Math.clamp(f, 0f, 1f) : 0.4f;
		}
		if (o.has("throughBlocks")) w.throughBlocks = o.get("throughBlocks").getAsBoolean();
		if (o.has("visible")) w.visible = o.get("visible").getAsBoolean();
		return w;
	}

	private static int parseColor(@Nullable JsonElement el) {
		if (el == null || el.isJsonNull()) return PALETTE[0];
		if (el.getAsJsonPrimitive().isNumber()) return el.getAsInt() & 0xFFFFFF;
		String s = el.getAsString().trim();
		if (s.startsWith("#")) s = s.substring(1);
		return Integer.parseUnsignedInt(s, 16) & 0xFFFFFF;
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, JsonObject o, String key, E fallback) {
		if (!o.has(key)) return fallback;
		try {
			return Enum.valueOf(type, o.get(key).getAsString().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	// ---------------------------------------------------------------- helpers

	private String nextFreeName() {
		Set<String> names = new HashSet<>();
		for (Waypoint w : waypoints) names.add(w.name);
		int n = 1;
		while (names.contains("Waypoint " + n)) n++;
		return "Waypoint " + n;
	}

	/** {@code sp_<save folder>} in singleplayer, {@code mp_<server address>} otherwise; sanitized for file names. */
	static String computeWorldKey(Minecraft minecraft) {
		IntegratedServer sp = minecraft.getSingleplayerServer();
		if (sp != null) {
			Path root = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path folder = root.getFileName();
			return "sp_" + sanitize(folder != null ? folder.toString() : "world");
		}
		ServerData server = minecraft.getCurrentServer();
		String address = server != null && server.ip != null && !server.ip.isBlank() ? server.ip : "unknown";
		return "mp_" + sanitize(address.toLowerCase(Locale.ROOT));
	}

	static String sanitize(String raw) {
		String s = raw.replaceAll("[^A-Za-z0-9._-]", "_");
		if (s.isEmpty() || s.chars().allMatch(c -> c == '.')) s = "_" + s;
		return s.length() > 100 ? s.substring(0, 100) : s;
	}
}
