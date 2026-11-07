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
 * Blocks are added to a waypoint only through {@link #addBlock}, removed only through {@link #removeBlock} and moved
 * only through {@link #move}, which keep the per-dimension block index ({@link #waypointAt}) in sync.
 *
 * <p><b>Undo:</b> {@link #remove} does not discard a waypoint at once: it leaves the world, the list and the index
 * immediately, but stays restorable for {@link #UNDO_WINDOW_MILLIS} (real time) in one small "recently deleted" stack
 * of {@link DeletedWaypoint}s, newest first ({@link #undoAt}, {@link #undo}, {@link #restore}). Pending deletions are
 * never saved (the file is written without them) and become permanent when they expire, on disconnect and on world
 * change. Removing single blocks from a clump ({@link #removeBlock}) is final and has no undo.
 *
 * <p><b>Groups:</b> {@link WaypointGroup}s of 2+ waypoints of one dimension ({@link #createGroup},
 * {@link #removeFromGroup}, {@link #ungroup}). A waypoint is in at most one group. Deleting a member removes it from
 * its group (a group left with fewer than {@link WaypointGroup#MIN_MEMBERS} is dissolved); undoing the deletion puts
 * it back at its old place in the group if that group still exists, else it comes back ungrouped. Groups are saved in
 * the same file (a top-level {@code groups} array).
 */
public final class WaypointManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("waypy");
	private static final WaypointManager INSTANCE = new WaypointManager();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int FORMAT_VERSION = 1;
	/** Ticks between a {@link #markDirty()} and the automatic save. */
	private static final int SAVE_DELAY_TICKS = 20;
	public static final Identifier DEFAULT_LOGO = Identifier.withDefaultNamespace("compass");
	/** How long a deleted waypoint can be restored, in milliseconds of real time. */
	public static final long UNDO_WINDOW_MILLIS = 15_000L;
	/** Most pending deletions kept at once (older ones become permanent early). */
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
	private final List<WaypointGroup> groups = new ArrayList<>();
	/** Waypoint id -> its group; rebuilt by {@link #reindexGroups()} after every membership change. */
	private final Map<UUID, WaypointGroup> groupByMember = new HashMap<>();
	/** Per dimension: block -> the (first) waypoint containing it. Kept in sync on add, remove, load, block add/remove, move and undo. */
	private final Map<ResourceKey<Level>, Map<BlockPos, Waypoint>> blockIndex = new HashMap<>();
	/** Recently deleted waypoints, newest first; each restorable until {@link #UNDO_WINDOW_MILLIS} after the deletion. */
	private final Deque<DeletedWaypoint> recentlyDeleted = new ArrayDeque<>();
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

	// ---------------------------------------------------------------- groups: queries

	/** Every group of the world, in creation order. */
	public synchronized List<WaypointGroup> groups() {
		return List.copyOf(groups);
	}

	/** The groups of {@code dimension}; a fresh list the caller may keep. Used by rendering. */
	public synchronized List<WaypointGroup> groupsIn(ResourceKey<Level> dimension) {
		List<WaypointGroup> out = new ArrayList<>();
		for (WaypointGroup g : groups) {
			if (g.dimension.equals(dimension)) out.add(g);
		}
		return out;
	}

	public synchronized Optional<WaypointGroup> groupById(UUID id) {
		for (WaypointGroup g : groups) {
			if (g.id.equals(id)) return Optional.of(g);
		}
		return Optional.empty();
	}

	/** The group the waypoint with this id belongs to, or null. */
	public synchronized @Nullable WaypointGroup groupOf(UUID waypointId) {
		return groupByMember.get(waypointId);
	}

	/** The group's member waypoints, in group order. */
	public synchronized List<Waypoint> members(WaypointGroup group) {
		List<Waypoint> out = new ArrayList<>(group.size());
		for (UUID id : group.members()) byId(id).ifPresent(out::add);
		return out;
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
		remove(id, null);
	}

	/**
	 * Like {@link #remove(UUID)}, but the undo brings the waypoint back with {@code undoBlocks} (in that order) instead
	 * of its current blocks. The N key's erase stroke uses it when it erases a waypoint's last block, so the undo
	 * restores the waypoint as it was before the stroke began. Null (or empty) keeps the current blocks.
	 */
	public synchronized void remove(UUID id, @Nullable List<BlockPos> undoBlocks) {
		Optional<Waypoint> removed = byId(id);
		if (removed.isEmpty()) return;
		Waypoint w = removed.get();
		int listIndex = waypoints.indexOf(w);
		waypoints.remove(listIndex);
		boolean wasSelected = id.equals(selectedId);
		if (wasSelected) selectedId = null;
		reindex(w.dimension);
		// Leaves its group (which dissolves below 2 members); the undo puts it back if the group still exists.
		WaypointGroup group = groupByMember.get(id);
		int groupIndex = group != null ? leaveGroup(group, id) : -1;
		// Out of the list and the index now, so its blocks can be reset without touching either.
		if (undoBlocks != null && !undoBlocks.isEmpty()) w.setBlocksInternal(undoBlocks);
		pushUndo(new DeletedWaypoint(w, listIndex, wasSelected, group != null ? group.id : null, groupIndex, clock.getAsLong()));
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
	 * Removes one block from a waypoint with 2+ blocks (the N key's tap or erase stroke on a clump). The other blocks
	 * keep their order; removing the anchor makes the next block the anchor. Every other field (logo, style, group
	 * membership, ...) is unchanged, and disconnected parts stay one waypoint. Keeps the index in sync, bumps the
	 * version (the highlight mesh rebuilds) and marks the manager dirty. <b>Final:</b> a block removal has no undo.
	 */
	public synchronized RemoveBlockResult removeBlock(Waypoint waypoint, BlockPos pos) {
		if (!waypoints.contains(waypoint)) return RemoveBlockResult.UNKNOWN_WAYPOINT;
		if (!waypoint.contains(pos)) return RemoveBlockResult.NOT_IN_WAYPOINT;
		if (!waypoint.isClump()) return RemoveBlockResult.LAST_BLOCK;
		if (waypoint.removeBlockInternal(pos) < 0) return RemoveBlockResult.NOT_IN_WAYPOINT;
		reindex(waypoint.dimension); // another waypoint may share the block (old files, "+ New here")
		markDirty();
		return RemoveBlockResult.REMOVED;
	}

	// ---------------------------------------------------------------- undo

	/**
	 * A deleted waypoint that can still be restored (kept in the "recently deleted" stack, newest first, for
	 * {@link #UNDO_WINDOW_MILLIS}): the waypoint itself (id, fields and blocks unchanged), and the group it was in
	 * ({@code groupId}, null if none) with its index there. {@code atMillis} is when it was deleted
	 * ({@code Util.getMillis()}).
	 */
	public record DeletedWaypoint(Waypoint waypoint, int listIndex, boolean wasSelected, @Nullable UUID groupId, int groupIndex,
			long atMillis) {
		public ResourceKey<Level> dimension() {
			return waypoint.dimension;
		}

		/** The waypoint's name (for messages and tooltips). */
		public String name() {
			return waypoint.name;
		}
	}

	private void pushUndo(DeletedWaypoint entry) {
		expireDeletions(false);
		recentlyDeleted.addFirst(entry);
		while (recentlyDeleted.size() > MAX_PENDING_UNDO) recentlyDeleted.removeLast();
	}

	/** The most recent deletion that has not expired yet (the menu's Undo offers it). */
	public synchronized Optional<DeletedWaypoint> lastDeleted() {
		expireDeletions(false);
		return Optional.ofNullable(recentlyDeleted.peekFirst());
	}

	/** Milliseconds left to undo {@code entry} (0 when expired). */
	public synchronized long remainingUndoMillis(DeletedWaypoint entry) {
		return Math.max(0L, entry.atMillis() + UNDO_WINDOW_MILLIS - clock.getAsLong());
	}

	/** True if the deletion is pending and can be undone now: none of its blocks has since been claimed by another waypoint. */
	public synchronized boolean canUndo(DeletedWaypoint entry) {
		expireDeletions(false);
		if (!recentlyDeleted.contains(entry)) return false;
		Map<BlockPos, Waypoint> index = blockIndex.get(entry.dimension());
		if (index != null) {
			for (BlockPos pos : entry.waypoint().blocks()) {
				if (index.containsKey(pos)) return false;
			}
		}
		return true;
	}

	/** Applies a pending deletion's undo if {@link #canUndo} allows it (the menu's Undo). The restored waypoint, or null. */
	public synchronized @Nullable Waypoint undo(DeletedWaypoint entry) {
		return canUndo(entry) ? apply(entry) : null;
	}

	/**
	 * Restores the pending deletion of the waypoint with this id, with the same id, fields and blocks, at its old place
	 * in the list (and selected again if it was selected). Null if it is not pending (expired, never deleted) or one of
	 * its blocks now belongs to another waypoint.
	 */
	public synchronized @Nullable Waypoint restore(UUID id) {
		expireDeletions(false);
		for (DeletedWaypoint entry : recentlyDeleted) {
			if (entry.waypoint().id.equals(id)) return undo(entry);
		}
		return null;
	}

	/**
	 * The N key's undo: restores the newest pending deletion in {@code dimension} of a waypoint that had the block at
	 * {@code pos}, if {@link #canUndo} allows it. Null if there is none (then the press is handled normally).
	 */
	public synchronized @Nullable Waypoint undoAt(BlockPos pos, ResourceKey<Level> dimension) {
		expireDeletions(false);
		for (DeletedWaypoint entry : List.copyOf(recentlyDeleted)) {
			if (entry.dimension().equals(dimension) && entry.waypoint().contains(pos) && canUndo(entry)) return apply(entry);
		}
		return null;
	}

	/** Applies a deletion {@link #canUndo} allowed and drops it from the stack. */
	private Waypoint apply(DeletedWaypoint deleted) {
		recentlyDeleted.remove(deleted);
		Waypoint w = deleted.waypoint();
		waypoints.add(Math.min(deleted.listIndex(), waypoints.size()), w);
		index(w);
		// Back into its group, at its old place, if that group still exists (else it stays ungrouped).
		WaypointGroup group = deleted.groupId() == null ? null : groupById(deleted.groupId()).orElse(null);
		if (group != null && group.dimension.equals(w.dimension) && !groupByMember.containsKey(w.id)) {
			group.addMemberInternal(deleted.groupIndex(), w.id);
			reindexGroups();
		}
		if (deleted.wasSelected() && selectedId == null) selectedId = w.id;
		markDirty();
		return w;
	}

	/**
	 * Makes deletions permanent: the expired ones, or with {@code all} every pending one (world change, disconnect;
	 * the devtest uses it to force expiry).
	 */
	public synchronized void expireDeletions(boolean all) {
		if (all) {
			recentlyDeleted.clear();
			return;
		}
		long now = clock.getAsLong();
		recentlyDeleted.removeIf(e -> now - e.atMillis() >= UNDO_WINDOW_MILLIS);
	}

	// ---------------------------------------------------------------- groups: mutations

	/** Whether a list of waypoints can be made into a group ({@link #canGroup}). */
	public enum GroupCheck {
		OK,
		/** Fewer than {@link WaypointGroup#MIN_MEMBERS} distinct managed waypoints. */
		TOO_FEW,
		/** The waypoints are not all in the same dimension. */
		MIXED_DIMENSIONS
	}

	/** Checks {@code waypoints} for {@link #createGroup}: 2+ distinct managed waypoints, all in one dimension. */
	public synchronized GroupCheck canGroup(List<Waypoint> waypoints) {
		List<Waypoint> distinct = managedDistinct(waypoints);
		if (distinct.size() < WaypointGroup.MIN_MEMBERS) return GroupCheck.TOO_FEW;
		ResourceKey<Level> dimension = distinct.getFirst().dimension;
		for (Waypoint w : distinct) {
			if (!w.dimension.equals(dimension)) return GroupCheck.MIXED_DIMENSIONS;
		}
		return GroupCheck.OK;
	}

	/** The managed waypoints of {@code list}, without duplicates, in order. */
	private List<Waypoint> managedDistinct(List<Waypoint> list) {
		List<Waypoint> out = new ArrayList<>();
		for (Waypoint w : list) {
			if (w != null && waypoints.contains(w) && !out.contains(w)) out.add(w);
		}
		return out;
	}

	/**
	 * Makes a new group of {@code waypoints} (the menu's "Make group"), in that order, if {@link #canGroup} allows it.
	 * Waypoints already in a group leave it first (an old group left with fewer than 2 members is dissolved). The new
	 * group is named {@code Group <n>} (next free number), takes the first member's colour and logo, and has its text off.
	 *
	 * @return the new group, or null if the waypoints can't be grouped
	 */
	public synchronized @Nullable WaypointGroup createGroup(List<Waypoint> waypoints) {
		if (canGroup(waypoints) != GroupCheck.OK) return null;
		List<Waypoint> members = managedDistinct(waypoints);
		for (Waypoint w : members) {
			WaypointGroup old = groupByMember.get(w.id);
			if (old != null) leaveGroup(old, w.id);
		}
		Waypoint first = members.getFirst();
		WaypointGroup group = new WaypointGroup(UUID.randomUUID(), nextFreeGroupName(), first.dimension, first.color,
				first.logoBlock);
		group.logoType = first.logoType;
		for (Waypoint w : members) group.addMemberInternal(group.size(), w.id);
		groups.add(group);
		reindexGroups();
		markDirty();
		return group;
	}

	/** Takes the waypoint out of its group (a group left with fewer than 2 members is dissolved). False if it had none. */
	public synchronized boolean removeFromGroup(Waypoint waypoint) {
		WaypointGroup group = groupByMember.get(waypoint.id);
		if (group == null) return false;
		leaveGroup(group, waypoint.id);
		markDirty();
		return true;
	}

	/** Dissolves the group; its members stay as normal (ungrouped) waypoints. */
	public synchronized void ungroup(WaypointGroup group) {
		if (groups.remove(group)) {
			reindexGroups();
			markDirty();
		}
	}

	/** Removes a member from its group, dissolving the group below {@link WaypointGroup#MIN_MEMBERS}. Its former index. */
	private int leaveGroup(WaypointGroup group, UUID waypointId) {
		int index = group.removeMemberInternal(waypointId);
		if (group.size() < WaypointGroup.MIN_MEMBERS) groups.remove(group);
		reindexGroups();
		return index;
	}

	private void reindexGroups() {
		groupByMember.clear();
		for (WaypointGroup g : groups) {
			for (UUID id : g.members()) groupByMember.putIfAbsent(id, g);
		}
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
		recentlyDeleted.clear(); // pending deletions become permanent
		waypoints.clear();
		groups.clear();
		groupByMember.clear();
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
		JsonArray groupArr = new JsonArray();
		for (WaypointGroup g : groups) groupArr.add(toJson(g));
		root.add("groups", groupArr);

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
		if (root.has("groups") && root.get("groups").isJsonArray()) {
			for (JsonElement el : root.getAsJsonArray("groups")) {
				try {
					WaypointGroup g = groupFromJson(el.getAsJsonObject());
					if (g != null) {
						groups.add(g);
						reindexGroups();
					} else {
						migrated = true; // dropped (too few valid members): rewrite the file
					}
				} catch (Exception e) {
					LOGGER.warn("[Waypy] Skipping invalid group entry in {}: {}", file, el, e);
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

	private static JsonObject toJson(WaypointGroup g) {
		JsonObject o = new JsonObject();
		o.addProperty("id", g.id.toString());
		o.addProperty("name", g.name);
		o.addProperty("dimension", g.dimension.identifier().toString());
		o.addProperty("color", String.format(Locale.ROOT, "#%06X", g.color & 0xFFFFFF));
		o.addProperty("logoType", g.logoType.name());
		o.addProperty("logoBlock", g.logoBlock.toString());
		o.addProperty("showText", g.showText);
		JsonArray members = new JsonArray();
		for (UUID id : g.members()) members.add(id.toString());
		o.add("members", members);
		return o;
	}

	/**
	 * A group from the file, keeping only members that are loaded waypoints of its dimension and not already in an
	 * earlier group (duplicates dropped). Null if fewer than {@link WaypointGroup#MIN_MEMBERS} are left or the id is
	 * already used.
	 */
	private @Nullable WaypointGroup groupFromJson(JsonObject o) {
		UUID id = UUID.fromString(o.get("id").getAsString());
		if (groupById(id).isPresent()) return null;
		ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dimension").getAsString()));
		Identifier logo = DEFAULT_LOGO;
		if (o.has("logoBlock")) {
			Identifier parsed = Identifier.tryParse(o.get("logoBlock").getAsString());
			if (parsed != null) logo = parsed;
		}
		WaypointGroup g = new WaypointGroup(id, o.has("name") ? o.get("name").getAsString() : "Group", dim,
				parseColor(o.get("color")), logo);
		g.logoType = parseEnum(LogoType.class, o, "logoType", LogoType.BLOCK);
		if (o.has("showText")) g.showText = o.get("showText").getAsBoolean();
		JsonElement members = o.get("members");
		if (members != null && members.isJsonArray()) {
			for (JsonElement m : members.getAsJsonArray()) {
				try {
					UUID wid = UUID.fromString(m.getAsString());
					Waypoint w = byId(wid).orElse(null);
					if (w != null && w.dimension.equals(dim) && !groupByMember.containsKey(wid)) g.addMemberInternal(g.size(), wid);
				} catch (RuntimeException e) {
					// not a UUID string: skip it
				}
			}
		}
		if (g.size() < WaypointGroup.MIN_MEMBERS) {
			LOGGER.warn("[Waypy] Dropping group {} with fewer than {} valid members", g.name, WaypointGroup.MIN_MEMBERS);
			return null;
		}
		return g;
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

	private String nextFreeGroupName() {
		Set<String> names = new HashSet<>();
		for (WaypointGroup g : groups) names.add(g.name);
		int n = 1;
		while (names.contains("Group " + n)) n++;
		return "Group " + n;
	}

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
