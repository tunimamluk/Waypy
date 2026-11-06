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
 *       looked-at block, else the block stood on. {@link #press}:
 *       <ul>
 *         <li>target block in a waypoint of this dimension → that whole waypoint (clump) is removed, restorable for
 *             {@link WaypointManager#UNDO_WINDOW_MILLIS} ({@code Waypoint "<name>" removed – press N to undo});</li>
 *         <li>free block that belonged to a waypoint deleted within the undo window, whose blocks are all still free →
 *             that waypoint is restored unchanged ({@code Waypoint "<name>" restored}); no clump is started;</li>
 *         <li>other free block → a new waypoint is created there (the clump being built), silently;</li>
 *         <li>no block → nothing.</li>
 *       </ul></li>
 *   <li><b>Holding.</b> Each tick while the key {@code isDown()} and no screen is open, the target block is found
 *       again in the press mode and {@link #extend}ed into the clump: new free blocks are added, blocks of other
 *       waypoints are skipped silently, and at {@link Waypoint#MAX_BLOCKS} the limit message shows once. A hold that
 *       began with a delete, a restore (or with no block) has no clump and adds nothing.</li>
 *   <li><b>Release.</b> When the key is up, or a screen is open, the hold ends ({@link #release}), silently.</li>
 * </ul>
 *
 * Clicks are always drained, so presses never queue up. Clicks arriving during a hold (OS key repeat) are ignored,
 * including on the tick the hold ends, so holding N never creates or deletes a second time.
 */
public final class CreateKeyHandler {
	private boolean holding;
	/** Mode of the current hold: true = looked-at block (modifier + N), false = block stood on. */
	private boolean lookedAt;
	/** The clump being built by the current hold; null if the press deleted a waypoint or found no block. */
	private @Nullable Waypoint clump;
	private boolean limitReported;

	/** Called at the end of every client tick. */
	public void tick(Minecraft client) {
		boolean clicked = false;
		while (WaypyClient.createKey.consumeClick()) clicked = true;
		boolean inGame = client.player != null && client.level != null && client.gui.screen() == null;
		boolean down = WaypyClient.createKey.isDown();

		if (holding) {
			if (down && inGame) {
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

	/** True between a press and its release. */
	public boolean isHolding() {
		return holding;
	}

	/** The clump the current hold is building, if any. */
	public @Nullable Waypoint activeClump() {
		return clump;
	}

	/**
	 * Key-down on {@code target}: removes the waypoint containing it, restores a recently deleted waypoint that had
	 * this block, or creates a new waypoint there that the hold then extends. Starts a hold (ending any previous one
	 * first).
	 *
	 * @param lookedAt the hold's mode, used by {@link #tick} to find the target on later ticks
	 * @return the created waypoint, or null if a waypoint was removed or restored, or there is no target
	 */
	public @Nullable Waypoint press(Minecraft client, @Nullable BlockPos target, boolean lookedAt) {
		release(client);
		holding = true;
		this.lookedAt = lookedAt;
		clump = null;
		limitReported = false;
		if (target == null || client.player == null || client.level == null) return null;

		WaypointManager manager = WaypointManager.get();
		Waypoint existing = manager.waypointAt(target, client.level.dimension());
		if (existing != null) {
			manager.remove(existing.id);
			client.player.sendOverlayMessage(Component.translatable("waypy.message.removed", existing.name,
					WaypyClient.createKey.getTranslatedKeyMessage()));
			return null;
		}
		Waypoint restored = manager.restoreDeletedAt(target, client.level.dimension());
		if (restored != null) {
			client.player.sendOverlayMessage(Component.translatable("waypy.message.restored", restored.name));
			return null;
		}
		clump = WaypyClient.createWaypointAt(client, target);
		return clump;
	}

	/** One held tick on {@code target}: adds it to the clump if it is free. No-op without a clump or target. */
	public void extend(Minecraft client, @Nullable BlockPos target) {
		Waypoint w = clump;
		if (!holding || w == null || target == null || client.level == null) return;
		if (!w.dimension.equals(client.level.dimension())) return;
		switch (WaypointManager.get().addBlock(w, target)) {
			case LIMIT_REACHED -> {
				if (!limitReported && client.player != null) {
					limitReported = true;
					client.player.sendOverlayMessage(Component.translatable("waypy.message.clump_limit", Waypoint.MAX_BLOCKS));
				}
			}
			case UNKNOWN_WAYPOINT -> clump = null; // removed meanwhile
			default -> {
			}
		}
	}

	/** Ends the hold. No-op when not holding. */
	public void release(Minecraft client) {
		holding = false;
		clump = null;
	}

	private static @Nullable BlockPos findTarget(Minecraft client, boolean lookedAt, boolean report) {
		if (client.player == null || client.level == null) return null;
		return lookedAt ? WaypyClient.findLookedAtBlock(client, report) : WaypyClient.findBlockBelow(client, report);
	}
}
