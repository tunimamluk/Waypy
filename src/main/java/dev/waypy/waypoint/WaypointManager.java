package dev.waypy.waypoint;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
import net.minecraft.util.Util;
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
 * Blocks are added to a waypoint only through {@link #addBlock} (or an undo), removed only through {@link #removeBlock}
 * and moved only through {@link #move}, which keep the per-dimension block index ({@link #waypointAt}) in sync.
 *
 * <p><b>Undo:</b> {@link #remove} does not discard a waypoint at once, and {@link #removeBlock} does not forget the
 * block. Either leaves the world, the list and the index immediately, but stays undoable for
 * {@link #UNDO_WINDOW_MILLIS} (real time) in one small "recently undoable" stack of {@link UndoEntry}s, newest first
 * ({@link #undoAt}, {@link #undo}, {@link #restore}). Pending entries are never saved (the file is written without
 * them) and become permanent when they expire, on disconnect and on world change.
 */
public final class WaypointManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("waypy");
	private static final WaypointManager INSTANCE = new WaypointManager();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int FORMAT_VERSION = 1;
	/** Ticks between a {@link #markDirty()} and the automatic save. */
	private static final int SAVE_DELAY_TICKS = 20;
	public static final Identifier DEFAULT_LOGO = Identifier.withDefaultNamespace("compass");
	/** How long a deleted waypoint or a removed block can be restored, in milliseconds of real time. */
	public static final long UNDO_WINDOW_MILLIS = 15_000L;
	/** Most undoable actions (deletions and block removals) kept at once (older ones become permanent early). */
	private static final int MAX_PENDING_UNDO = 16;

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
	/** Per dimension: block -> the (first) waypoint containing it. Kept in sync on add, remove, load, block add/remove and undo. */
	private final Map<ResourceKey<Level>, Map<BlockPos, Waypoint>> blockIndex = new HashMap<>();
	/**
	 * Recently deleted waypoints and removed blocks, newest first; each undoable until {@link #UNDO_WINDOW_MILLIS}
	 * after it happened.
	 */
	private final Deque<UndoEntry> recentlyUndoable = new ArrayDeque<>();
	private final LongSupplier clock = Util::getMillis;
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

	/** Waypoints in {@code dimension} with {@code visible == true}; a fresh list the caller may keep. Used by rendering. */
	public synchronized List<Waypoint> visibleIn(ResourceKey<Level> dimension) {
		List<Waypoint> out = new ArrayList<>();
		for (Waypoint w : waypoints) {
			if (w.visible && w.dimension.equals(dimension)) out.add(w);
		}
		return out;
	}

	public synchronized Optional<Waypoint> byId(UUID id) {
		for (Waypoint w : waypoints) {
			if (w.id.equals(id)) return Optional.of(w);
		}
		return Optional.empty();
	}

	/** The waypoint (single block or clump) containing {@code pos} in {@code dimension}, or null. O(1). */
	public synchronized @Nullable Waypoint waypointAt(BlockPos pos, ResourceKey<Level> dimension) {
		Map<BlockPos, Waypoint> index = blockIndex.get(dimension);
		return index == null ? null : index.get(pos);
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
		index(w);
		markDirty();
		return w;
	}

	/**
	 * Removes the waypoint from the world, the list and the index. It stays restorable ({@link #restore},
	 * {@link #undoAt}, {@link #undo}) for {@link #UNDO_WINDOW_MILLIS}.
	 */
	public synchronized void remove(UUID id) {
		Optional<Waypoint> removed = byId(id);
		if (removed.isEmpty()) return;
		Waypoint w = removed.get();
		int listIndex = waypoints.indexOf(w);
		waypoints.remove(listIndex);
		boolean wasSelected = id.equals(selectedId);
		if (wasSelected) selectedId = null;
		reindex(w.dimension);
		pushUndo(new DeletedWaypoint(w, listIndex, wasSelected, clock.getAsLong()));
		markDirty();
	}

	/** Result of {@link #removeBlock}. */
	public enum RemoveBlockResult {
		REMOVED,
		/** The block is not one of this waypoint's blocks. */
		NOT_IN_WAYPOINT,
		/** It is the waypoint's only block (a waypoint is never empty: {@link #remove} the waypoint instead). */
		LAST_BLOCK,
		/** The waypoint is not managed (e.g. it was removed). */
		UNKNOWN_WAYPOINT
	}

	/**
	 * Removes one block from a waypoint with 2+ blocks (the N key's tap on a clump block). The other blocks keep their
	 * order; removing the anchor makes the next block the anchor. Every other field (logo, style, ...) is unchanged,
	 * and disconnected parts stay one waypoint. Keeps the index in sync, bumps the version (the highlight mesh
	 * rebuilds), marks the manager dirty and makes the removal undoable ({@link RemovedBlock}) for
	 * {@link #UNDO_WINDOW_MILLIS}.
	 */
	public synchronized RemoveBlockResult removeBlock(Waypoint waypoint, BlockPos pos) {
		if (!waypoints.contains(waypoint)) return RemoveBlockResult.UNKNOWN_WAYPOINT;
		if (!waypoint.contains(pos)) return RemoveBlockResult.NOT_IN_WAYPOINT;
		if (!waypoint.isClump()) return RemoveBlockResult.LAST_BLOCK;
		int blockIndex = waypoint.removeBlockInternal(pos);
		if (blockIndex < 0) return RemoveBlockResult.NOT_IN_WAYPOINT;
		reindex(waypoint.dimension); // another waypoint may share the block (old files, "+ New here")
		pushUndo(new RemovedBlock(waypoint.id, waypoint.name, waypoint.dimension, pos.immutable(), blockIndex, clock.getAsLong()));
		markDirty();
		return RemoveBlockResult.REMOVED;
	}

	// ---------------------------------------------------------------- undo

	/**
	 * An undoable action, kept in one "recently undoable" stack (newest first) for {@link #UNDO_WINDOW_MILLIS}: a whole
	 * deleted waypoint or one block removed from a waypoint.
	 */
	public sealed interface UndoEntry permits DeletedWaypoint, RemovedBlock {
		/** When the action happened ({@code Util.getMillis()}). */
		long atMillis();

		ResourceKey<Level> dimension();

		/** The waypoint's name at the time (for messages and tooltips). */
		String name();
	}

	/** A deleted waypoint that can still be restored: the waypoint itself (id, fields and blocks unchanged). */
	public record DeletedWaypoint(Waypoint waypoint, int listIndex, boolean wasSelected, long atMillis) implements UndoEntry {
		@Override
		public ResourceKey<Level> dimension() {
			return waypoint.dimension;
		}

		@Override
		public String name() {
			return waypoint.name;
		}
	}

	/**
	 * A block removed from the waypoint {@code waypointId} (which still exists, with its other blocks), at
	 * {@code blockIndex} in its block list. Undoing it puts the block back at that index, so a removed anchor becomes
	 * the anchor again.
	 */
	public record RemovedBlock(UUID waypointId, String name, ResourceKey<Level> dimension, BlockPos pos, int blockIndex,
			long atMillis) implements UndoEntry {
	}

	/** An undo that was applied: the entry and the waypoint it restored (whole, or with the block back). */
	public record Undone(UndoEntry entry, Waypoint waypoint) {
	}

	private void pushUndo(UndoEntry entry) {
		expireDeletions(false);
		recentlyUndoable.addFirst(entry);
		while (recentlyUndoable.size() > MAX_PENDING_UNDO) recentlyUndoable.removeLast();
	}

	/** The most recent undoable action (whole deletion or block removal) that has not expired yet. */
	public synchronized Optional<UndoEntry> lastUndo() {
		expireDeletions(false);
		return Optional.ofNullable(recentlyUndoable.peekFirst());
	}

	/** The most recent whole-waypoint deletion that has not expired yet. */
	public synchronized Optional<DeletedWaypoint> lastDeleted() {
		expireDeletions(false);
		for (UndoEntry entry : recentlyUndoable) {
			if (entry instanceof DeletedWaypoint deleted) return Optional.of(deleted);
		}
		return Optional.empty();
	}

	/** Milliseconds left to undo {@code entry} (0 when expired). */
	public synchronized long remainingUndoMillis(UndoEntry entry) {
		return Math.max(0L, entry.atMillis() + UNDO_WINDOW_MILLIS - clock.getAsLong());
	}

	/**
	 * True if the entry is pending and can be applied now: for a deleted waypoint, none of its blocks has since been
	 * claimed by another waypoint; for a removed block, its waypoint still exists (in the same dimension), is not full
	 * and the block is still free.
	 */
	public synchronized boolean canUndo(UndoEntry entry) {
		expireDeletions(false);
		if (!recentlyUndoable.contains(entry)) return false;
		Map<BlockPos, Waypoint> index = blockIndex.get(entry.dimension());
		return switch (entry) {
			case DeletedWaypoint deleted -> {
				if (index != null) {
					for (BlockPos pos : deleted.waypoint().blocks()) {
						if (index.containsKey(pos)) yield false;
					}
				}
				yield true;
			}
			case RemovedBlock removed -> {
				Waypoint w = byId(removed.waypointId()).orElse(null);
				yield w != null && w.dimension.equals(removed.dimension()) && w.size() < Waypoint.MAX_BLOCKS
						&& !w.contains(removed.pos()) && (index == null || !index.containsKey(removed.pos()));
			}
		};
	}

	/** Applies a pending undo entry if {@link #canUndo} allows it (the menu's Undo). The restored waypoint, or null. */
	public synchronized @Nullable Waypoint undo(UndoEntry entry) {
		return canUndo(entry) ? apply(entry) : null;
	}

	/**
	 * Restores the pending deletion of the waypoint with this id, with the same id, fields and blocks, at its old place
	 * in the list (and selected again if it was selected). Null if it is not pending (expired, never deleted) or one of
	 * its blocks now belongs to another waypoint.
	 */
	public synchronized @Nullable Waypoint restore(UUID id) {
		expireDeletions(false);
		for (UndoEntry entry : recentlyUndoable) {
			if (entry instanceof DeletedWaypoint deleted && deleted.waypoint().id.equals(id)) return undo(deleted);
		}
		return null;
	}

	/**
	 * The N key's undo: applies the newest pending entry in {@code dimension} for the block at {@code pos} (a deleted
	 * waypoint that had this block, or this removed block) that {@link #canUndo} allows. Null if there is none (then
	 * the press is handled normally).
	 */
	public synchronized @Nullable Undone undoAt(BlockPos pos, ResourceKey<Level> dimension) {
		expireDeletions(false);
		for (UndoEntry entry : List.copyOf(recentlyUndoable)) {
			if (!entry.dimension().equals(dimension)) continue;
			boolean matches = switch (entry) {
				case DeletedWaypoint deleted -> deleted.waypoint().contains(pos);
				case RemovedBlock removed -> removed.pos().equals(pos);
			};
			if (matches && canUndo(entry)) return new Undone(entry, apply(entry));
		}
		return null;
	}

	/** Applies an entry {@link #canUndo} allowed and drops it from the stack. */
	private Waypoint apply(UndoEntry entry) {
		recentlyUndoable.remove(entry);
		switch (entry) {
			case DeletedWaypoint deleted -> {
				Waypoint w = deleted.waypoint();
				waypoints.add(Math.min(deleted.listIndex(), waypoints.size()), w);
				index(w);
				if (deleted.wasSelected() && selectedId == null) selectedId = w.id;
				markDirty();
				return w;
			}
			case RemovedBlock removed -> {
				Waypoint w = byId(removed.waypointId()).orElseThrow();
				w.insertBlockInternal(removed.blockIndex(), removed.pos());
				blockIndex.computeIfAbsent(w.dimension, d -> new HashMap<>()).put(removed.pos(), w);
				// Back to 2+ blocks: a clump is never a beacon.
				if (w.isClump() && w.style == WaypointStyle.BEACON) w.style = WaypointStyle.BLOCK_HIGHLIGHT;
				markDirty();
				return w;
			}
		}
	}

	/** Drops the pending block removals of {@code waypointId} (all of them, or only the one at {@code pos}). */
	private void dropRemovedBlocks(UUID waypointId, @Nullable BlockPos pos) {
		recentlyUndoable.removeIf(e -> e instanceof RemovedBlock r && r.waypointId().equals(waypointId)
				&& (pos == null || r.pos().equals(pos)));
	}

	/**
	 * Makes undoable actions permanent: the expired ones, or with {@code all} every pending one (world change,
	 * disconnect; the devtest uses it to force expiry).
	 */
	public synchronized void expireDeletions(boolean all) {
		if (all) {
			recentlyUndoable.clear();
			return;
		}
		long now = clock.getAsLong();
		recentlyUndoable.removeIf(e -> now - e.atMillis() >= UNDO_WINDOW_MILLIS);
	}

	// ---------------------------------------------------------------- move

	/** Result of {@link #move}: what happened and, for {@link Status#OVERLAP}, the waypoint in the way. */
	public record MoveResult(Status status, @Nullable Waypoint blocker) {
		public enum Status {
			MOVED,
			/** The new anchor is the current one. */
			UNCHANGED,
			/** A block would land on a block of another waypoint in the same dimension; nothing was moved. */
			OVERLAP,
			/** The waypoint is not managed (e.g. it was removed). */
			UNKNOWN_WAYPOINT
		}
	}

	/**
	 * Moves the waypoint so its anchor (first block) is at {@code newAnchor}: every block is translated by the same
	 * delta, so a clump keeps its shape. Refused (nothing changes) if a moved block would overlap a block of another
	 * waypoint in the same dimension. Keeps the index in sync, bumps the waypoint's version and marks the manager dirty.
	 */
	public synchronized MoveResult move(Waypoint waypoint, BlockPos newAnchor) {
		if (!waypoints.contains(waypoint)) return new MoveResult(MoveResult.Status.UNKNOWN_WAYPOINT, null);
		BlockPos anchor = waypoint.anchor();
		int dx = newAnchor.getX() - anchor.getX();
		int dy = newAnchor.getY() - anchor.getY();
		int dz = newAnchor.getZ() - anchor.getZ();
		if (dx == 0 && dy == 0 && dz == 0) return new MoveResult(MoveResult.Status.UNCHANGED, null);
		Waypoint blocker = moveBlocker(waypoint, dx, dy, dz);
		if (blocker != null) return new MoveResult(MoveResult.Status.OVERLAP, blocker);
		waypoint.translateInternal(dx, dy, dz);
		reindex(waypoint.dimension);
		dropRemovedBlocks(waypoint.id, null); // their positions no longer fit the moved waypoint
		markDirty();
		return new MoveResult(MoveResult.Status.MOVED, null);
	}

	/** The first other waypoint of the same dimension that has a block where a moved block would land, or null. */
	private @Nullable Waypoint moveBlocker(Waypoint waypoint, int dx, int dy, int dz) {
		BlockPos.MutableBlockPos moved = new BlockPos.MutableBlockPos();
		for (Waypoint other : waypoints) {
			if (other == waypoint || !other.dimension.equals(waypoint.dimension)) continue;
			for (BlockPos pos : waypoint.blocks()) {
				if (other.contains(moved.setWithOffset(pos, dx, dy, dz))) return other;
			}
		}
		return null;
	}

	/** Result of {@link #addBlock}. */
	public enum AddBlockResult {
		ADDED,
		/** Already one of this waypoint's blocks. */
		ALREADY_IN_WAYPOINT,
		/** Belongs to another waypoint in the same dimension. */
		IN_OTHER_WAYPOINT,
		/** The waypoint already has {@link Waypoint#MAX_BLOCKS} blocks. */
		LIMIT_REACHED,
		/** The waypoint is not managed (e.g. it was removed). */
		UNKNOWN_WAYPOINT
	}

	/**
	 * Adds {@code pos} to the waypoint (making it a clump), unless the block is already in it or in another waypoint
	 * of the same dimension, or the waypoint is full. Marks the manager dirty when the block was added.
	 */
	public synchronized AddBlockResult addBlock(Waypoint waypoint, BlockPos pos) {
		if (!waypoints.contains(waypoint)) return AddBlockResult.UNKNOWN_WAYPOINT;
		if (waypoint.contains(pos)) return AddBlockResult.ALREADY_IN_WAYPOINT;
		Map<BlockPos, Waypoint> index = blockIndex.computeIfAbsent(waypoint.dimension, d -> new HashMap<>());
		if (index.containsKey(pos)) return AddBlockResult.IN_OTHER_WAYPOINT;
		if (waypoint.size() >= Waypoint.MAX_BLOCKS) return AddBlockResult.LIMIT_REACHED;
		if (!waypoint.addBlockInternal(pos)) return AddBlockResult.LIMIT_REACHED;
		index.put(pos.immutable(), waypoint);
		dropRemovedBlocks(waypoint.id, pos); // the block is back: its pending removal is moot
		// A clump is never a beacon: it becomes Block Selection the moment it grows to two blocks.
		if (waypoint.style == WaypointStyle.BEACON) waypoint.style = WaypointStyle.BLOCK_HIGHLIGHT;
		markDirty();
		return AddBlockResult.ADDED;
	}

	private void index(Waypoint w) {
		Map<BlockPos, Waypoint> index = blockIndex.computeIfAbsent(w.dimension, d -> new HashMap<>());
		for (BlockPos pos : w.blocks()) index.putIfAbsent(pos, w);
	}

	/** Rebuilds one dimension's index (after a removal: another waypoint may share a block from an old file). */
	private void reindex(ResourceKey<Level> dimension) {
		blockIndex.remove(dimension);
		for (Waypoint w : waypoints) {
			if (w.dimension.equals(dimension)) index(w);
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

	/** Called every client tick; flushes debounced changes and expires old deletions. */
	public synchronized void tick() {
		expireDeletions(false);
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
		recentlyUndoable.clear(); // pending deletions and block removals become permanent
		waypoints.clear();
		blockIndex.clear();
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
				StringBuilder json = new StringBuilder();
				writeJson(root, json, "");
				out.write(json.append('\n').toString());
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
		boolean migrated = false;
		if (root.has("waypoints") && root.get("waypoints").isJsonArray()) {
			for (JsonElement el : root.getAsJsonArray("waypoints")) {
				try {
					Waypoint w = fromJson(el.getAsJsonObject());
					if (w.isClump() && w.style == WaypointStyle.BEACON) {
						// Migration: a clump can't be a beacon.
						w.style = WaypointStyle.BLOCK_HIGHLIGHT;
						migrated = true;
					}
					if (seen.add(w.id)) {
						waypoints.add(w);
						index(w);
					}
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
		if (migrated) markDirty();
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
		// x/y/z: the anchor (first block), so older Waypy versions still load the waypoint as a single block.
		BlockPos anchor = w.anchor();
		o.addProperty("x", anchor.getX());
		o.addProperty("y", anchor.getY());
		o.addProperty("z", anchor.getZ());
		JsonArray blocks = new JsonArray();
		for (BlockPos pos : w.blocks()) {
			JsonArray xyz = new JsonArray(3);
			xyz.add(pos.getX());
			xyz.add(pos.getY());
			xyz.add(pos.getZ());
			blocks.add(xyz);
		}
		o.add("blocks", blocks);
		o.addProperty("dimension", w.dimension.identifier().toString());
		o.addProperty("color", String.format(Locale.ROOT, "#%06X", w.color & 0xFFFFFF));
		o.addProperty("style", w.style.name());
		o.addProperty("logoType", w.logoType.name());
		o.addProperty("logoBlock", w.logoBlock.toString());
		o.addProperty("highlightOpacity", w.highlightOpacity);
		o.addProperty("throughBlocks", w.throughBlocks);
		o.addProperty("visible", w.visible);
		o.addProperty("showText", w.showText);
		return o;
	}

	private static Waypoint fromJson(JsonObject o) {
		UUID id = UUID.fromString(o.get("id").getAsString());
		List<BlockPos> blocks = parseBlocks(o);
		ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dimension").getAsString()));
		int color = parseColor(o.get("color"));
		Identifier logo = DEFAULT_LOGO;
		if (o.has("logoBlock")) {
			Identifier parsed = Identifier.tryParse(o.get("logoBlock").getAsString());
			if (parsed != null) logo = parsed;
		}
		String name = o.has("name") ? o.get("name").getAsString() : "Waypoint";

		Waypoint w = new Waypoint(id, name, blocks, dim, color, logo);
		w.style = parseEnum(WaypointStyle.class, o, "style", WaypointStyle.BEACON);
		// Migration: the removed TEXT logo (and any unknown value) loads as BLOCK, keeping the stored logoBlock;
		// an old "logoText" field is ignored.
		w.logoType = parseEnum(LogoType.class, o, "logoType", LogoType.BLOCK);
		if (o.has("highlightOpacity")) {
			float f = o.get("highlightOpacity").getAsFloat();
			w.highlightOpacity = Float.isFinite(f) ? Math.clamp(f, 0f, 1f) : 0.4f;
		}
		if (o.has("throughBlocks")) w.throughBlocks = o.get("throughBlocks").getAsBoolean();
		if (o.has("visible")) w.visible = o.get("visible").getAsBoolean();
		// Migration: files written before the per-waypoint text toggle keep their labels.
		w.showText = !o.has("showText") || o.get("showText").getAsBoolean();
		return w;
	}

	/**
	 * The {@code "blocks": [[x,y,z], ...]} list: invalid entries are skipped, duplicates dropped, at most
	 * {@link Waypoint#MAX_BLOCKS} kept. Migration: files without (valid) blocks load the {@code x}/{@code y}/{@code z}
	 * position as a single-block waypoint.
	 */
	private static List<BlockPos> parseBlocks(JsonObject o) {
		Set<BlockPos> blocks = new LinkedHashSet<>();
		JsonElement el = o.get("blocks");
		if (el != null && el.isJsonArray()) {
			for (JsonElement entry : el.getAsJsonArray()) {
				if (blocks.size() >= Waypoint.MAX_BLOCKS) {
					LOGGER.warn("[Waypy] Waypoint {} has more than {} blocks; extra blocks dropped", o.get("id"), Waypoint.MAX_BLOCKS);
					break;
				}
				try {
					JsonArray xyz = entry.getAsJsonArray();
					if (xyz.size() != 3) continue;
					blocks.add(new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()));
				} catch (RuntimeException e) {
					// not an [x,y,z] array of numbers: skip it
				}
			}
		}
		if (blocks.isEmpty()) {
			blocks.add(new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
		}
		return new ArrayList<>(blocks);
	}

	/**
	 * Pretty-prints like Gson's pretty printing (two-space indent), except that arrays holding only primitives
	 * (the {@code [x, y, z]} block positions) stay on one line, so a 256-block clump isn't 1280 lines long.
	 */
	private static void writeJson(JsonElement el, StringBuilder out, String indent) {
		if (el.isJsonObject()) {
			JsonObject obj = el.getAsJsonObject();
			if (obj.isEmpty()) {
				out.append("{}");
				return;
			}
			String inner = indent + "  ";
			out.append("{\n");
			boolean first = true;
			for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
				if (!first) out.append(",\n");
				first = false;
				out.append(inner).append(GSON.toJson(new JsonPrimitive(e.getKey()))).append(": ");
				writeJson(e.getValue(), out, inner);
			}
			out.append('\n').append(indent).append('}');
		} else if (el.isJsonArray()) {
			JsonArray arr = el.getAsJsonArray();
			if (arr.isEmpty()) {
				out.append("[]");
				return;
			}
			boolean flat = true;
			for (JsonElement e : arr) flat &= e.isJsonPrimitive() || e.isJsonNull();
			if (flat) {
				out.append('[');
				for (int i = 0; i < arr.size(); i++) {
					if (i > 0) out.append(", ");
					out.append(GSON.toJson(arr.get(i)));
				}
				out.append(']');
				return;
			}
			String inner = indent + "  ";
			out.append("[\n");
			for (int i = 0; i < arr.size(); i++) {
				if (i > 0) out.append(",\n");
				out.append(inner);
				writeJson(arr.get(i), out, inner);
			}
			out.append('\n').append(indent).append(']');
		} else {
			out.append(GSON.toJson(el));
		}
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
		} catch (RuntimeException e) { // unknown name, or not a string
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
