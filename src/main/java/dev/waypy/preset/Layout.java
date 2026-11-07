package dev.waypy.preset;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;

import dev.waypy.waypoint.LogoType;
import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointGroup;
import dev.waypy.waypoint.WaypointManager;
import dev.waypy.waypoint.WaypointStyle;

/**
 * A <b>layout preset</b>: a saved copy of a group's arrangement (the Layouts tab of the H popup). Each member waypoint
 * is stored as its display settings plus its blocks as offsets from an <b>anchor</b> block (the block the player stood
 * on when the layout was created from the menu); the group's own settings are stored too. Layouts are global (all
 * worlds, {@link LayoutManager}). A layout's number is its position in {@link LayoutManager#all()} plus one.
 *
 * <p>{@link #applyAt} stamps the layout onto the world so that the anchor lands on a chosen block, without rotation.
 */
public final class Layout {
	public static final int MAX_NAME_LENGTH = Waypoint.MAX_NAME_LENGTH;

	/** The layout's own name (defaults to the group name), max {@value #MAX_NAME_LENGTH} chars. */
	public String name;
	/** The settings of the group the layout makes. */
	public final GroupSettings group;
	/** The member waypoints, in the group's order (never empty in a stored layout). */
	public final List<Member> members;

	public Layout(String name, GroupSettings group, List<Member> members) {
		setName(name);
		this.group = Objects.requireNonNull(group);
		this.members = List.copyOf(members);
	}

	/** Sets the name, trimmed to {@value #MAX_NAME_LENGTH} chars ({@code null} becomes empty). */
	public void setName(String name) {
		this.name = clamp(name, MAX_NAME_LENGTH);
	}

	static String clamp(@Nullable String s, int max) {
		if (s == null) return "";
		return s.length() > max ? s.substring(0, max) : s;
	}

	/** The group settings a layout stores: what {@link WaypointGroup} shows. */
	public static final class GroupSettings {
		public String name;
		public int color;
		public LogoType logoType = LogoType.BLOCK;
		public Identifier logoBlock = WaypointManager.DEFAULT_LOGO;
		public boolean showText = false;
		public boolean visible = true;

		public GroupSettings(String name, int color) {
			this.name = clamp(name, WaypointGroup.MAX_NAME_LENGTH);
			this.color = color & 0xFFFFFF;
		}

		static GroupSettings of(WaypointGroup g) {
			GroupSettings s = new GroupSettings(g.name, g.color);
			s.logoType = g.logoType;
			s.logoBlock = g.logoBlock;
			s.showText = g.showText;
			s.visible = g.visible;
			return s;
		}

		void applyTo(WaypointGroup g) {
			g.setName(name);
			g.color = color & 0xFFFFFF;
			g.logoType = logoType;
			g.logoBlock = logoBlock;
			g.showText = showText;
			g.visible = visible;
		}

		@Override
		public String toString() {
			return "group \"" + name + "\" #" + String.format("%06X", color) + " " + logoType + " " + logoBlock + " text=" + showText
					+ " visible=" + visible;
		}
	}

	/** One member waypoint: its settings and its blocks as offsets from the layout's anchor, in the waypoint's order. */
	public static final class Member {
		public String name;
		public int color;
		public WaypointStyle style = WaypointStyle.BLOCK_HIGHLIGHT;
		public LogoType logoType = LogoType.BLOCK;
		public Identifier logoBlock = WaypointManager.DEFAULT_LOGO;
		public float highlightOpacity = 0.4f;
		public boolean throughBlocks = false;
		public boolean showText = false;
		public boolean visible = true;
		/** Offsets (dx, dy, dz) from the anchor; 1..{@link Waypoint#MAX_BLOCKS}, unique, the waypoint's first block first. */
		public final List<BlockPos> offsets;

		public Member(String name, int color, List<BlockPos> offsets) {
			this.name = clamp(name, Waypoint.MAX_NAME_LENGTH);
			this.color = color & 0xFFFFFF;
			List<BlockPos> unique = new ArrayList<>();
			for (BlockPos p : offsets) {
				if (unique.size() >= Waypoint.MAX_BLOCKS) break;
				BlockPos immutable = p.immutable();
				if (!unique.contains(immutable)) unique.add(immutable);
			}
			this.offsets = List.copyOf(unique);
		}

		static Member of(Waypoint w, BlockPos anchor) {
			List<BlockPos> offsets = new ArrayList<>(w.size());
			for (BlockPos b : w.blocks()) offsets.add(b.subtract(anchor));
			Member m = new Member(w.name, w.color, offsets);
			m.style = w.style;
			m.logoType = w.logoType;
			m.logoBlock = w.logoBlock;
			m.highlightOpacity = w.highlightOpacity;
			m.throughBlocks = w.throughBlocks;
			m.showText = w.showText;
			m.visible = w.visible;
			return m;
		}

		/** Copies every setting but the name onto {@code w}; a clump is never a beacon. */
		void applySettingsTo(Waypoint w) {
			w.color = color & 0xFFFFFF;
			w.style = w.isClump() && style == WaypointStyle.BEACON ? WaypointStyle.BLOCK_HIGHLIGHT : style;
			w.logoType = logoType;
			w.logoBlock = logoBlock;
			w.highlightOpacity = Math.clamp(highlightOpacity, 0.0f, 1.0f);
			w.throughBlocks = throughBlocks;
			w.showText = showText;
			w.visible = visible;
		}

		/** True if one of the blocks is the anchor (offset 0, 0, 0). */
		public boolean containsAnchor() {
			return offsets.contains(BlockPos.ZERO);
		}

		@Override
		public String toString() {
			return "member \"" + name + "\" #" + String.format("%06X", color) + " " + style + " " + logoType + " " + logoBlock
					+ " opacity=" + highlightOpacity + " through=" + throughBlocks + " text=" + showText + " visible=" + visible
					+ " offsets=" + offsets.stream().map(p -> "[" + p.getX() + "," + p.getY() + "," + p.getZ() + "]").toList();
		}
	}

	// ---------------------------------------------------------------- creating

	/**
	 * A layout of {@code group}: every member of {@code members} (the group's waypoints, in order) with its blocks as
	 * offsets from {@code anchor}, and the group's settings. Named after the group.
	 */
	public static Layout capture(WaypointGroup group, List<Waypoint> members, BlockPos anchor) {
		List<Member> out = new ArrayList<>(members.size());
		for (Waypoint w : members) out.add(Member.of(w, anchor));
		return new Layout(group.name, GroupSettings.of(group), out);
	}

	/** The member whose blocks include the anchor (offset 0, 0, 0), or null; it merges into the target on apply. */
	public @Nullable Member anchorMember() {
		for (Member m : members) {
			if (m.containsAnchor()) return m;
		}
		return null;
	}

	/** Blocks over all members. */
	public int blockCount() {
		int n = 0;
		for (Member m : members) n += m.offsets.size();
		return n;
	}

	// ---------------------------------------------------------------- applying

	/**
	 * What {@link #applyAt} did.
	 *
	 * @param created        the new waypoints, in layout order
	 * @param mergedBlocks   blocks of the anchor member added to the target
	 * @param skippedBlocks  translated blocks left out: on another waypoint, outside the build height, or over the
	 *                       256-block cap
	 * @param skippedMembers members not created because none of their blocks could be placed
	 * @param group          the new group, or null if fewer than 2 waypoints resulted
	 */
	public record ApplyResult(Waypoint target, List<Waypoint> created, int mergedBlocks, int skippedBlocks, int skippedMembers,
			@Nullable WaypointGroup group) {
	}

	/**
	 * Stamps the layout so that its anchor lands on {@code at} (every offset translated by {@code at}; no rotation), in
	 * {@code target}'s dimension, and makes {@code target} part of it:
	 * <ul>
	 *   <li>The <b>anchor member</b> (the one whose blocks include offset 0, 0, 0) merges into {@code target}: the
	 *       target gets its blocks (through {@link WaypointManager#addBlock}) and every setting but the name. Without an
	 *       anchor member the target is left as it is.</li>
	 *   <li>Every other member becomes a new waypoint with its settings and name, on its translated blocks.</li>
	 *   <li>A translated block that already belongs to a waypoint (the target's own blocks included, for the other
	 *       members) or is outside {@code level}'s build height is skipped; a member left with no block is not
	 *       created. Clumps are capped at 256 blocks and use Block Selection.</li>
	 *   <li>If at least 2 waypoints result, the target and the new waypoints (in layout order, the target at the
	 *       anchor member's place, else first) become a new group with the layout's group settings
	 *       ({@link WaypointManager#createGroup}, which takes the target out of its old group).</li>
	 * </ul>
	 * Silent; marks the manager dirty.
	 *
	 * @param level the level for the build-height check, or null for none
	 * @return what was done, or null if the target is not a managed waypoint
	 */
	public @Nullable ApplyResult applyAt(WaypointManager wm, Waypoint target, BlockPos at, @Nullable LevelHeightAccessor level) {
		if (wm.byId(target.id).orElse(null) != target) return null;
		ResourceKey<Level> dimension = target.dimension;
		Member anchorMember = anchorMember();
		int merged = 0;
		int skipped = 0;
		int skippedMembers = 0;

		if (anchorMember != null) {
			for (BlockPos offset : anchorMember.offsets) {
				BlockPos pos = at.offset(offset);
				Waypoint owner = wm.waypointAt(pos, dimension);
				if (owner == target) continue; // B itself, or another block the target already has
				if (owner != null || (level != null && level.isOutsideBuildHeight(pos))) {
					skipped++;
					continue;
				}
				if (wm.addBlock(target, pos) == WaypointManager.AddBlockResult.ADDED) {
					merged++;
				} else {
					skipped++;
				}
			}
			anchorMember.applySettingsTo(target);
		}

		List<Waypoint> created = new ArrayList<>();
		List<Waypoint> groupMembers = new ArrayList<>();
		if (anchorMember == null) groupMembers.add(target);
		for (Member m : members) {
			if (m == anchorMember) {
				groupMembers.add(target);
				continue;
			}
			List<BlockPos> free = new ArrayList<>();
			for (BlockPos offset : m.offsets) {
				BlockPos pos = at.offset(offset);
				if (wm.waypointAt(pos, dimension) != null || (level != null && level.isOutsideBuildHeight(pos))) {
					skipped++;
				} else {
					free.add(pos);
				}
			}
			if (free.isEmpty()) {
				skippedMembers++;
				continue;
			}
			Waypoint w = wm.create(free.getFirst(), dimension, m.logoBlock);
			w.setName(m.name);
			for (int i = 1; i < free.size(); i++) {
				if (wm.addBlock(w, free.get(i)) != WaypointManager.AddBlockResult.ADDED) skipped++;
			}
			m.applySettingsTo(w);
			created.add(w);
			groupMembers.add(w);
		}

		WaypointGroup group = null;
		if (groupMembers.size() >= WaypointGroup.MIN_MEMBERS) {
			group = wm.createGroup(groupMembers);
			if (group != null) this.group.applyTo(group);
		}
		wm.markDirty();
		return new ApplyResult(target, List.copyOf(created), merged, skipped, skippedMembers, group);
	}

	@Override
	public String toString() {
		return "Layout[\"" + name + "\" " + group + " " + members + "]";
	}
}
