package dev.waypy;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointManager;

/**
 * The "Create Waypoint" key (N) as a small state machine, run once per client tick by {@link #tick(Minecraft)}.
 *
 * <ul>
 *   <li><b>Idle → press.</b> A click ({@code consumeClick}) while in game starts a hold. The mode is fixed for the
 *       whole hold: the Look-at Modifier ({@code key.waypy.look_modifier}, default Left Shift) held at the press = the
 *       looked-at block, else the block stood on. {@link #press} picks the hold's {@link Hold kind} from the target:
 *       <ul>
 *         <li>block of a waypoint W in this dimension → {@link Hold#EXTEND_OR_REMOVE} on W (nothing changes yet);</li>
 *         <li>free block of a recently deleted waypoint (pending undo) → restored at once
 *             ({@code Waypoint "<name>" restored}), {@link Hold#RESTORED} on that waypoint;</li>
 *         <li>other free block → a new waypoint there, silently, {@link Hold#CREATED};</li>
 *         <li>no block → {@link Hold#NONE}.</li>
 *       </ul></li>
 *   <li><b>Holding.</b> Each tick while the key {@code isDown()}, no screen is open and the window has focus, the target
 *       block is found again in the press mode and passed to {@link #extend}:
 *       <ul>
 *         <li>CREATED, RESTORED and {@link Hold#EXTEND}: a free block is added to the hold's waypoint, blocks of this
 *             or other waypoints are skipped silently, and at {@link Waypoint#MAX_BLOCKS} the limit message shows
 *             once.</li>
 *         <li>EXTEND_OR_REMOVE (undecided): the FIRST target different from the start block decides the rest of the
 *             hold. A free block → {@link Hold#EXTEND} (and it is added). A block of W itself → {@link Hold#ERASE}:
 *             the start block and that block are removed. A block of another waypoint is ignored and the hold stays
 *             undecided.</li>
 *         <li>{@link Hold#ERASE}: every block of W the target moves onto is removed (final, no undo), with a live
 *             count ({@code Erasing "<name>": <n> blocks removed}); free blocks and other waypoints are ignored. If
 *             the stroke reaches W's last block, W is deleted through {@link WaypointManager#remove(java.util.UUID, List)}
 *             with its blocks from before the stroke, so the undo brings back the whole original waypoint
 *             ({@code Waypoint "<name>" removed – press N to undo}). The hold then ends and the rest of that key-down
 *             does nothing (presses are ignored until the key is released).</li>
 *         <li>NONE: nothing.</li>
 *       </ul></li>
 *   <li><b>Release</b> ({@link #release}, key up while in game with focus): an EXTEND_OR_REMOVE hold that is still
 *       undecided is a <b>tap</b>: the start block is removed from W, final
 *       ({@code Block removed from "<name>" (<n> blocks left)}), or, if it was W's only block, W is deleted
 *       ({@code Waypoint "<name>" removed – press N to undo}, undoable for 15 s). An ERASE hold shows
 *       {@code Removed <n> blocks from "<name>" (<m> left)}. Every other release is silent and changes nothing, so a
 *       press that restored something never removes anything.</li>
 *   <li><b>Cancel</b> ({@link #cancel}): when a screen is open, the player or level is gone, or the window lost focus,
 *       the hold just ends; an interrupted tap removes nothing (blocks an erase stroke already removed stay removed).</li>
 * </ul>
 *
 * Clicks are always drained, so presses never queue up. Clicks arriving during a hold (OS key repeat) are ignored,
 * including on the tick the hold ends, so holding N never presses a second time. A tap is decided only by whether the
 * hold left the undecided state, never by clicks, so key repeat can't turn a tap into a stroke (or the other way round).
 */
public final class CreateKeyHandler {
	/** What the current hold does. */
	public enum Hold {
		/** Not holding. */
		IDLE,
		/** The press found no block: the hold adds nothing. */
		NONE,
		/** The press created a waypoint: held ticks grow it into a clump. */
		CREATED,
		/** The press undid a deletion: held ticks extend that waypoint; release removes nothing. */
		RESTORED,
		/**
		 * The press was on a block of an existing waypoint W and no target has decided the mode yet: the first free
		 * block makes it {@link #EXTEND}, the first other block of W makes it {@link #ERASE}. A release in this state is
		 * a tap that removes the start block (or the whole waypoint if that was its only block).
		 */
		EXTEND_OR_REMOVE,
		/** Pressed on W, then the target moved onto a free block: held ticks add free blocks to W; release removes nothing. */
		EXTEND,
		/** Pressed on W, then the target moved onto another block of W: held ticks remove W's blocks (final). */
		ERASE
	}

	private Hold hold = Hold.IDLE;
	/** Mode of the current hold: true = looked-at block (modifier + N), false = block stood on. */
	private boolean lookedAt;
	/** The waypoint the current hold extends or erases; null for NONE (or if it was removed meanwhile). */
	private @Nullable Waypoint waypoint;
	/** EXTEND_OR_REMOVE / EXTEND / ERASE: the pressed block (removed by a tap or when the erase mode starts). */
	private @Nullable BlockPos startBlock;
	/** ERASE: the waypoint's blocks from before the stroke (the undo of a deletion by the stroke restores them). */
	private @Nullable List<BlockPos> strokeBlocks;
	/** Blocks the current hold added. */
	private int added;
	/** Blocks the current ERASE hold removed. */
	private int erased;
	/** The hold ran into the block limit (shown once). */
	private boolean limitReached;
	/** An erase stroke deleted its waypoint: the hold is over, but the key is still down; ignore it until released. */
	private boolean awaitKeyUp;

	/** Called at the end of every client tick. */
	public void tick(Minecraft client) {
		boolean clicked = false;
		while (WaypyClient.createKey.consumeClick()) clicked = true;
		boolean inGame = client.player != null && client.level != null && client.gui.screen() == null;
		boolean down = WaypyClient.createKey.isDown();

		if (awaitKeyUp) {
			if (!down || !inGame || !client.isWindowActive()) awaitKeyUp = false;
			return; // clicks while the key is still down are key repeats of the finished stroke
		}
		if (hold != Hold.IDLE) {
			if (!inGame || !client.isWindowActive()) {
				cancel(); // a screen opened, the world went away or the window lost focus: not a deliberate tap
			} else if (down) {
				extend(client, findTarget(client, lookedAt, false));
			} else {
				release(client);
			}
			return; // clicks during a hold are key repeats
		}
		if (clicked && inGame) {
			boolean look = WaypyClient.lookModifierKey.isDown();
			press(client, findTarget(client, look, true), look);
			if (!down) release(client); // tapped within one tick
		}
	}

	/** True between a press and its release (or cancel). */
	public boolean isHolding() {
		return hold != Hold.IDLE;
	}

	/** The kind of the current hold ({@link Hold#IDLE} when not holding). */
	public Hold hold() {
		return hold;
	}

	/** True after an erase stroke deleted its waypoint, until the key is released (that key-down does nothing more). */
	public boolean isWaitingForKeyUp() {
		return awaitKeyUp;
	}

	/** The waypoint the current hold extends or erases (created, restored or pressed on), if any. */
	public @Nullable Waypoint activeWaypoint() {
		return waypoint;
	}

	/** Blocks the current hold has added so far. */
	public int addedBlocks() {
		return added;
	}

	/** Blocks the current ERASE hold has removed so far. */
	public int erasedBlocks() {
		return erased;
	}

	/**
	 * Key-down on {@code target}. Ends any previous hold first (as a {@link #cancel}), then starts a hold whose kind
	 * depends on the target (see the class comment). Only an undo or a create changes anything at the press; a press on
	 * an existing waypoint waits for the next targets (extend or erase) or the release (tap).
	 *
	 * @param lookedAt the hold's mode, used by {@link #tick} to find the target on later ticks
	 * @return the waypoint the hold works on (pressed on, restored or created), or null if there is no target
	 */
	public @Nullable Waypoint press(Minecraft client, @Nullable BlockPos target, boolean lookedAt) {
		cancel();
		hold = Hold.NONE;
		this.lookedAt = lookedAt;
		if (target == null || client.player == null || client.level == null) return null;

		WaypointManager manager = WaypointManager.get();
		Waypoint existing = manager.waypointAt(target, client.level.dimension());
		if (existing != null) {
			start(Hold.EXTEND_OR_REMOVE, existing);
			startBlock = target.immutable();
			return existing;
		}
		Waypoint restored = manager.undoAt(target, client.level.dimension());
		if (restored != null) {
			client.player.sendOverlayMessage(Component.translatable("waypy.message.restored", restored.name));
			start(Hold.RESTORED, restored);
			return restored;
		}
		Waypoint created = WaypyClient.createWaypointAt(client, target);
		if (created != null) start(Hold.CREATED, created);
		return created;
	}

	private void start(Hold kind, Waypoint w) {
		hold = kind;
		waypoint = w;
	}

	/**
	 * One held tick on {@code target}: extends or erases the hold's waypoint, or decides which of the two an
	 * EXTEND_OR_REMOVE hold does (see the class comment). No-op without a waypoint or a target.
	 */
	public void extend(Minecraft client, @Nullable BlockPos target) {
		Waypoint w = waypoint;
		if (w == null || target == null || client.level == null) return;
		if (!w.dimension.equals(client.level.dimension())) return;
		switch (hold) {
			case EXTEND_OR_REMOVE -> decide(client, w, target);
			case ERASE -> {
				if (w.contains(target)) erase(client, w, target);
			}
			case CREATED, RESTORED, EXTEND -> add(client, w, target);
			default -> {
			}
		}
	}

	/** EXTEND_OR_REMOVE: the first target that is neither the start block nor another waypoint's block picks the mode. */
	private void decide(Minecraft client, Waypoint w, BlockPos target) {
		if (target.equals(startBlock)) return;
		if (w.contains(target)) {
			hold = Hold.ERASE;
			strokeBlocks = List.copyOf(w.blocks());
			BlockPos start = startBlock;
			if (start != null && w.contains(start)) erase(client, w, start);
			if (hold == Hold.ERASE && waypoint == w && w.contains(target)) erase(client, w, target);
		} else if (WaypointManager.get().waypointAt(target, w.dimension) == null) {
			hold = Hold.EXTEND;
			add(client, w, target);
		}
		// Else a block of another waypoint: ignored, the hold stays undecided.
	}

	private void add(Minecraft client, Waypoint w, BlockPos target) {
		switch (WaypointManager.get().addBlock(w, target)) {
			case ADDED -> added++;
			case LIMIT_REACHED -> {
				if (!limitReached && client.player != null) {
					client.player.sendOverlayMessage(Component.translatable("waypy.message.clump_limit", Waypoint.MAX_BLOCKS));
				}
				limitReached = true;
			}
			case UNKNOWN_WAYPOINT -> waypoint = null; // removed meanwhile
			default -> {
			}
		}
	}

	/**
	 * ERASE: removes {@code pos} from W (final). W's last block deletes W instead (undoable, with the blocks from
	 * before the stroke) and ends the hold; the key is then ignored until it is released.
	 */
	private void erase(Minecraft client, Waypoint w, BlockPos pos) {
		WaypointManager manager = WaypointManager.get();
		switch (manager.removeBlock(w, pos)) {
			case REMOVED -> {
				erased++;
				if (client.player != null) {
					client.player.sendOverlayMessage(erased == 1
							? Component.translatable("waypy.message.erasing.one", w.name)
							: Component.translatable("waypy.message.erasing", w.name, erased));
				}
			}
			case LAST_BLOCK -> {
				manager.remove(w.id, strokeBlocks);
				if (client.player != null) {
					client.player.sendOverlayMessage(Component.translatable("waypy.message.removed", w.name,
							WaypyClient.createKey.getTranslatedKeyMessage()));
				}
				reset();
				awaitKeyUp = true;
			}
			case UNKNOWN_WAYPOINT -> waypoint = null; // removed meanwhile
			default -> {
			}
		}
	}

	/**
	 * Key-up: ends the hold. An EXTEND_OR_REMOVE hold that is still undecided (a tap) removes its start block from the
	 * waypoint (final), or deletes the waypoint if that was its only block (undoable). An ERASE hold reports how many
	 * blocks it removed. Also ends the wait after an erase stroke deleted its waypoint. No-op when not holding.
	 */
	public void release(Minecraft client) {
		Hold kind = hold;
		Waypoint w = waypoint;
		BlockPos start = startBlock;
		int removed = erased;
		cancel();
		if (w == null) return;
		if (kind == Hold.EXTEND_OR_REMOVE && start != null) {
			tap(client, w, start);
		} else if (kind == Hold.ERASE && removed > 0 && client.player != null && WaypointManager.get().byId(w.id).isPresent()) {
			client.player.sendOverlayMessage(removed == 1
					? Component.translatable("waypy.message.erased.one", w.name, w.size())
					: Component.translatable("waypy.message.erased", removed, w.name, w.size()));
		}
	}

	/** Ends the hold without acting on it (screen opened, focus lost, world gone). No-op when not holding. */
	public void cancel() {
		reset();
		awaitKeyUp = false;
	}

	private void reset() {
		hold = Hold.IDLE;
		waypoint = null;
		startBlock = null;
		strokeBlocks = null;
		added = 0;
		erased = 0;
		limitReached = false;
	}

	private static void tap(Minecraft client, Waypoint w, BlockPos start) {
		WaypointManager manager = WaypointManager.get();
		if (manager.byId(w.id).isEmpty() || !w.contains(start)) return; // changed meanwhile
		Component message;
		if (w.isClump()) {
			if (manager.removeBlock(w, start) != WaypointManager.RemoveBlockResult.REMOVED) return;
			message = w.size() == 1
					? Component.translatable("waypy.message.block_removed.one", w.name)
					: Component.translatable("waypy.message.block_removed", w.name, w.size());
		} else {
			manager.remove(w.id);
			message = Component.translatable("waypy.message.removed", w.name, WaypyClient.createKey.getTranslatedKeyMessage());
		}
		if (client.player != null) client.player.sendOverlayMessage(message);
	}

	private static @Nullable BlockPos findTarget(Minecraft client, boolean lookedAt, boolean report) {
		if (client.player == null || client.level == null) return null;
		return lookedAt ? WaypyClient.findLookedAtBlock(client, report) : WaypyClient.findBlockBelow(client, report);
	}
}
