package dev.waypy.waypoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A named group of 2+ waypoints of one dimension. Far away (beyond {@link #combineDistance}) the renderers draw the
 * group as ONE pin instead of its members (see {@code docs/DESIGN.md}, Groups).
 *
 * <p>Display state lives in <b>public fields</b>, as on {@link Waypoint}; after changing one, call
 * {@link WaypointManager#markDirty()}. The member list is encapsulated: it changes only through
 * {@link WaypointManager} ({@link WaypointManager#createGroup}, {@link WaypointManager#removeFromGroup},
 * {@link WaypointManager#ungroup}, deleting a member and undoing that), which keeps the invariants: every member is a
 * managed waypoint of {@link #dimension}, a waypoint is in at most one group, and a group has at least
 * {@link #MIN_MEMBERS} members (a group that would drop below that is dissolved).
 */
public final class WaypointGroup {
	public static final int MAX_NAME_LENGTH = Waypoint.MAX_NAME_LENGTH;
	/** Fewest members a group can have; a group that loses more is dissolved. */
	public static final int MIN_MEMBERS = 2;

	public final UUID id;
	/** Display name, max {@value #MAX_NAME_LENGTH} chars. */
	public String name;
	/** The dimension of every member. */
	public final ResourceKey<Level> dimension;
	/** 0xRRGGBB (no alpha). */
	public int color;
	/** The group pin's logo: a block icon, or (NONE) the name's initial. Defaults to the first member's logo. */
	public LogoType logoType = LogoType.BLOCK;
	/** Item/block id used when {@link #logoType} is {@link LogoType#BLOCK}; kept while the logo is NONE. */
	public Identifier logoBlock;
	/** Show the group pin's name tag (if the global Labels setting allows it). Off for new groups. */
	public boolean showText = false;
	/** Beyond this distance (blocks, camera to the group's centre) the group is drawn combined, as one pin. */
	public int combineDistance;

	private final List<UUID> members = new ArrayList<>();
	private final List<UUID> membersView = Collections.unmodifiableList(members);

	public WaypointGroup(UUID id, String name, ResourceKey<Level> dimension, int color, Identifier logoBlock, int combineDistance) {
		this.id = Objects.requireNonNull(id);
		this.dimension = Objects.requireNonNull(dimension);
		this.color = color & 0xFFFFFF;
		this.logoBlock = Objects.requireNonNull(logoBlock);
		this.combineDistance = combineDistance;
		setName(name);
	}

	/** Sets the name, trimmed to {@value #MAX_NAME_LENGTH} chars. */
	public void setName(String name) {
		this.name = Waypoint.clamp(name, MAX_NAME_LENGTH);
	}

	/** The member waypoint ids, in order (read-only live view). */
	public List<UUID> members() {
		return membersView;
	}

	public int size() {
		return members.size();
	}

	public boolean contains(UUID waypointId) {
		return members.contains(waypointId);
	}

	// Only the manager changes the members.

	void addMemberInternal(int index, UUID waypointId) {
		if (members.contains(waypointId)) return;
		members.add(Math.clamp(index, 0, members.size()), waypointId);
	}

	/** @return the member's former index, or -1 if it was not a member */
	int removeMemberInternal(UUID waypointId) {
		int index = members.indexOf(waypointId);
		if (index >= 0) members.remove(index);
		return index;
	}

	@Override
	public String toString() {
		return "WaypointGroup[" + name + ", " + members.size() + " members]";
	}
}
