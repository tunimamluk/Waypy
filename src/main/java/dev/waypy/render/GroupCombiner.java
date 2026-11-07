package dev.waypy.render;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import dev.waypy.config.WaypyConfig;
import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointGroup;
import dev.waypy.waypoint.WaypointManager;

/**
 * Decides, per frame, which groups are <b>combined</b> (drawn as one pin, members hidden), shared by the world
 * renderer and the HUD so both agree.
 *
 * <p>A group counts only its members that are {@linkplain WaypointManager#isEffectivelyVisible effectively visible}
 * (so a hidden group has none and draws nothing at all); with fewer than {@link WaypointGroup#MIN_MEMBERS} visible it is never
 * combined. Its centre is the centroid of those members' {@link Waypoint#centre()}s, and its distance the 3D distance
 * from the camera to that centre. The state switches with {@link #HYSTERESIS} blocks either side of the global
 * "Group Combine Distance" setting, which applies to every group (combined beyond {@code distance + 0.5}, separate
 * again below {@code distance - 0.5}; read each call, so a settings change takes effect at once), so standing at the boundary never flickers. {@link #compute} is idempotent for an unchanged camera position, so
 * calling it from both renderers in one frame is safe. Render thread only.
 */
final class GroupCombiner {
	/** Half-width of the hysteresis band around the combine distance, in blocks (1 block in total). */
	static final double HYSTERESIS = 0.5;

	/** Groups that were combined last time they were computed (absent = not combined). */
	private static final Set<UUID> COMBINED = new HashSet<>();

	private GroupCombiner() {
	}

	/**
	 * A group as the renderers see it this frame.
	 *
	 * @param members its visible members (2+), in group order
	 * @param centre the centroid of the members' centres
	 * @param anchorY the pin anchor height: the highest member anchor (each member's own pin height rule)
	 * @param distance camera to {@code centre}, 3D
	 */
	record View(WaypointGroup group, List<Waypoint> members, Vec3 centre, double anchorY, double distance, boolean combined) {
	}

	/** The groups of {@code dimension} with 2+ visible members, with their combined state updated for {@code camera}. */
	static List<View> compute(ResourceKey<Level> dimension, Vec3 camera) {
		WaypointManager manager = WaypointManager.get();
		int combineDistance = WaypyConfig.get().groupCombineDistance;
		List<View> views = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		for (WaypointGroup group : manager.groupsIn(dimension)) {
			List<Waypoint> members = new ArrayList<>();
			for (Waypoint w : manager.members(group)) {
				// The shared visibility rule: a hidden group has no visible members, so it is skipped entirely.
				if (manager.isEffectivelyVisible(w)) members.add(w);
			}
			if (members.size() < WaypointGroup.MIN_MEMBERS) continue;
			double sx = 0, sy = 0, sz = 0;
			double anchorY = Double.NEGATIVE_INFINITY;
			for (Waypoint w : members) {
				Vec3 c = w.centre();
				sx += c.x;
				sy += c.y;
				sz += c.z;
				anchorY = Math.max(anchorY, WaypointHud.anchorY(w));
			}
			int n = members.size();
			Vec3 centre = new Vec3(sx / n, sy / n, sz / n);
			double distance = centre.distanceTo(camera);
			boolean was = COMBINED.contains(group.id);
			boolean combined = was
					? distance > combineDistance - HYSTERESIS
					: distance > combineDistance + HYSTERESIS;
			if (combined) COMBINED.add(group.id);
			else COMBINED.remove(group.id);
			seen.add(group.id);
			views.add(new View(group, List.copyOf(members), centre, anchorY, distance, combined));
		}
		COMBINED.retainAll(seen);
		return views;
	}

	/** The members of every combined group in {@code views} (their own beams, highlights and pins are hidden). */
	static Set<Waypoint> combinedMembers(List<View> views) {
		Set<Waypoint> out = new HashSet<>();
		for (View view : views) {
			if (view.combined()) out.addAll(view.members());
		}
		return out;
	}

	/** Whether the group was combined when last computed. For the devtest (by reflection). */
	static boolean isCombined(UUID groupId) {
		return COMBINED.contains(groupId);
	}
}
