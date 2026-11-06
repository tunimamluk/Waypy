package dev.waypy;

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
 *         <li>free block with a pending undo (a deleted waypoint that had it, or this block removed from a waypoint)
 *             → undone at once ({@code Waypoint "<name>" restored} / {@code Block restored to "<name>"}),
 *             {@link Hold#RESTORED} on that waypoint;</li>
 *         <li>other free block → a new waypoint there, silently, {@link Hold#CREATED};</li>
 *         <li>no block → {@link Hold#NONE}.</li>
 *       </ul></li>
 *   <li><b>Holding.</b> Each tick while the key {@code isDown()}, no screen is open and the window has focus, the target block is found
 *       again in the press mode and {@link #extend}ed into the hold's waypoint (CREATED, RESTORED and
 *       EXTEND_OR_REMOVE alike): a free block is added, blocks of this or other waypoints are skipped silently, and at
 *       {@link Waypoint#MAX_BLOCKS} the limit message shows once. A NONE hold adds nothing.</li>
 *   <li><b>Release</b> ({@link #release}, key up while in game with focus): only an EXTEND_OR_REMOVE hold that added no block
 *       (and never hit the block limit) acts, as a <b>tap</b>: the start block is removed from W
 *       ({@code Block removed from "<name>" (<n> blocks left) – press N to undo}), or, if it was W's only block, W is
 *       deleted ({@code Waypoint "<name>" removed – press N to undo}). Both are undoable for 15 s. Every other release
 *       is silent and changes nothing, so a press that restored something never removes anything.</li>
 *   <li><b>Cancel</b> ({@link #cancel}): when a screen is open, the player or level is gone, or the window lost focus,
 *       the hold just ends; an interrupted tap removes nothing.</li>
 * </ul>
 *
 * Clicks are always drained, so presses never queue up. Clicks arriving during a hold (OS key repeat) are ignored,
 * including on the tick the hold ends, so holding N never presses a second time. A tap is decided only by whether the
 * hold added a block, never by clicks, so key repeat can't turn a tap into an extend (or the other way round).
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
		/** The press undid a deletion or a block removal: held ticks extend that waypoint; release removes nothing. */
		RESTORED,
		/**
		 * The press was on a block of an existing waypoint: held ticks extend it; a release without any added block is
		 * a tap that removes the start block (or the whole waypoint if that was its only block).
		 */
		EXTEND_OR_REMOVE
	}

	private Hold hold = Hold.IDLE;
	/** Mode of the current hold: true = looked-at block (modifier + N), false = block stood on. */
	private boolean lookedAt;
	/** The waypoint the current hold extends; null for NONE (or if it was removed meanwhile). */
	private @Nullable Waypoint waypoint;
	/** EXTEND_OR_REMOVE: the pressed block, removed by a tap. */
	private @Nullable BlockPos startBlock;
	/** Blocks the current hold added. */
	private int added;
	/** The hold ran into the block limit (shown once; for EXTEND_OR_REMOVE it counts as an extend, not a tap). */
	private boolean limitReached;

	/** Called at the end of every client tick. */
	public void tick(Minecraft client) {
		boolean clicked = false;
		while (WaypyClient.createKey.consumeClick()) clicked = true;
		boolean inGame = client.player != null && client.level != null && client.gui.screen() == null;
		boolean down = WaypyClient.createKey.isDown();

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

	/** The waypoint the current hold extends (created, restored or pressed on), if any. */
	public @Nullable Waypoint activeWaypoint() {
		return waypoint;
	}

	/** Blocks the current hold has added so far. */
	public int addedBlocks() {
		return added;
	}

	/**
	 * Key-down on {@code target}. Ends any previous hold first (as a {@link #cancel}), then starts a hold whose kind
	 * depends on the target (see the class comment). Only an undo or a create changes anything at the press; a press on
	 * an existing waypoint waits for the release to know if it is a tap.
	 *
	 * @param lookedAt the hold's mode, used by {@link #tick} to find the target on later ticks
	 * @return the waypoint the hold extends (pressed on, restored or created), or null if there is no target
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
		WaypointManager.Undone undone = manager.undoAt(target, client.level.dimension());
		if (undone != null) {
			Waypoint w = undone.waypoint();
			String key = undone.entry() instanceof WaypointManager.RemovedBlock ? "waypy.message.block_restored" : "waypy.message.restored";
			client.player.sendOverlayMessage(Component.translatable(key, w.name));
			start(Hold.RESTORED, w);
			return w;
		}
		Waypoint created = WaypyClient.createWaypointAt(client, target);
		if (created != null) start(Hold.CREATED, created);
		return created;
	}

	private void start(Hold kind, Waypoint w) {
		hold = kind;
		waypoint = w;
	}

	/** One held tick on {@code target}: adds it to the hold's waypoint if it is free. No-op without one or a target. */
	public void extend(Minecraft client, @Nullable BlockPos target) {
		Waypoint w = waypoint;
		if (w == null || target == null || client.level == null) return;
		if (!w.dimension.equals(client.level.dimension())) return;
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
	 * Key-up: ends the hold. An EXTEND_OR_REMOVE hold that added nothing (a tap) removes its start block from the
	 * waypoint, or deletes the waypoint if that was its only block; both undoable. No-op when not holding.
	 */
	public void release(Minecraft client) {
		boolean tap = hold == Hold.EXTEND_OR_REMOVE && added == 0 && !limitReached;
		Waypoint w = waypoint;
		BlockPos start = startBlock;
		cancel();
		if (tap && w != null && start != null) tap(client, w, start);
	}

	/** Ends the hold without acting on it (screen opened, focus lost, world gone). No-op when not holding. */
	public void cancel() {
		hold = Hold.IDLE;
		waypoint = null;
		startBlock = null;
		added = 0;
		limitReached = false;
	}

	private static void tap(Minecraft client, Waypoint w, BlockPos start) {
		WaypointManager manager = WaypointManager.get();
		if (manager.byId(w.id).isEmpty() || !w.contains(start)) return; // changed meanwhile
		Component key = WaypyClient.createKey.getTranslatedKeyMessage();
		Component message;
		if (w.isClump()) {
			if (manager.removeBlock(w, start) != WaypointManager.RemoveBlockResult.REMOVED) return;
			message = w.size() == 1
					? Component.translatable("waypy.message.block_removed.one", w.name, key)
					: Component.translatable("waypy.message.block_removed", w.name, w.size(), key);
		} else {
			manager.remove(w.id);
			message = Component.translatable("waypy.message.removed", w.name, key);
		}
		if (client.player != null) client.player.sendOverlayMessage(message);
	}

	private static @Nullable BlockPos findTarget(Minecraft client, boolean lookedAt, boolean report) {
		if (client.player == null || client.level == null) return null;
		return lookedAt ? WaypyClient.findLookedAtBlock(client, report) : WaypyClient.findBlockBelow(client, report);
	}
}
