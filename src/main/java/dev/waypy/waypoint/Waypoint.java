package dev.waypy.waypoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A waypoint: one block or a <b>clump</b> of up to {@link #MAX_BLOCKS} blocks. Display state lives in
 * <b>public fields</b> (no getters/setters). After mutating a waypoint, call {@link WaypointManager#markDirty()}
 * so the change is persisted. Use {@link #setName(String)} (or {@link #MAX_NAME_LENGTH}) to respect the length limit.
 *
 * <p>The blocks are encapsulated: ordered (first added first), unique and never empty. The first block is the
 * {@link #anchor()}. Blocks are added only through {@link WaypointManager#addBlock}, which keeps the manager's
 * block index in sync. Every change bumps {@link #version()}, so renderers can cache geometry built from the blocks.
 */
public final class Waypoint {
	public static final int MAX_NAME_LENGTH = 32;
	/** Most blocks a clump can hold. */
	public static final int MAX_BLOCKS = 256;

	public UUID id;
	/** Display name, max {@value #MAX_NAME_LENGTH} chars. */
	public String name;
	/** Serialized as an identifier string, e.g. {@code minecraft:overworld}. */
	public ResourceKey<Level> dimension;
	/** 0xRRGGBB (no alpha). */
	public int color;
	public WaypointStyle style = WaypointStyle.BEACON;
	public LogoType logoType = LogoType.BLOCK;
	/** Item/block id used when {@link #logoType} is {@link LogoType#BLOCK}; kept while the logo is NONE. */
	public Identifier logoBlock;
	/** 0..1, {@link WaypointStyle#BLOCK_HIGHLIGHT} only. */
	public float highlightOpacity = 0.4f;
	/** {@link WaypointStyle#BLOCK_HIGHLIGHT} only: render without depth test. */
	public boolean throughBlocks = true;
	public boolean visible = true;

	private final List<BlockPos> blocks = new ArrayList<>();
	private final List<BlockPos> blocksView = Collections.unmodifiableList(blocks);
	private final Set<BlockPos> blockSet = new HashSet<>();
	private int version;

	// Derived from the blocks; recomputed lazily when the version changes.
	private int derivedVersion = -1;
	private Vec3 centre = Vec3.ZERO;
	private int topY;
	private BlockPos beamBlock = BlockPos.ZERO;
	private int beamColumnTopY;
	private AABB bounds = new AABB(BlockPos.ZERO);

	public Waypoint(UUID id, String name, BlockPos pos, ResourceKey<Level> dimension, int color, Identifier logoBlock) {
		this(id, name, List.of(pos), dimension, color, logoBlock);
	}

	/** @param blocks at least one block; duplicates are dropped and at most {@link #MAX_BLOCKS} are kept */
	public Waypoint(UUID id, String name, List<BlockPos> blocks, ResourceKey<Level> dimension, int color, Identifier logoBlock) {
		this.id = Objects.requireNonNull(id);
		this.dimension = Objects.requireNonNull(dimension);
		this.color = color & 0xFFFFFF;
		this.logoBlock = Objects.requireNonNull(logoBlock);
		for (BlockPos pos : blocks) {
			addBlockInternal(Objects.requireNonNull(pos));
		}
		if (this.blocks.isEmpty()) throw new IllegalArgumentException("a waypoint needs at least one block");
		setName(name);
	}

	/** Sets the name, trimmed to {@value #MAX_NAME_LENGTH} chars. */
	public void setName(String name) {
		this.name = clamp(name, MAX_NAME_LENGTH);
	}

	static String clamp(String s, int max) {
		if (s == null) return "";
		return s.length() > max ? s.substring(0, max) : s;
	}

	// ---------------------------------------------------------------- blocks

	/** The blocks in the order they were added (read-only live view, never empty). */
	public List<BlockPos> blocks() {
		return blocksView;
	}

	/** The first block. For a single-block waypoint, the block it is on. */
	public BlockPos anchor() {
		return blocks.getFirst();
	}

	public int size() {
		return blocks.size();
	}

	/** True for more than one block. */
	public boolean isClump() {
		return blocks.size() > 1;
	}

	public boolean contains(BlockPos pos) {
		return blockSet.contains(pos);
	}

	/** Bumped on every block change; renderers cache geometry by it. */
	public int version() {
		return version;
	}

	/** Adds a block; false if it is already in this waypoint or the cap is reached. Only the manager calls this. */
	boolean addBlockInternal(BlockPos pos) {
		if (blocks.size() >= MAX_BLOCKS) return false;
		BlockPos immutable = pos.immutable();
		if (!blockSet.add(immutable)) return false;
		blocks.add(immutable);
		version++;
		return true;
	}

	/** Centroid of the block centres. */
	public Vec3 centre() {
		derive();
		return centre;
	}

	/** Y of the highest block. */
	public int topY() {
		derive();
		return topY;
	}

	/** The block whose centre is nearest the centroid in XZ (ties: the first added). The beacon beam rises from its column. */
	public BlockPos beamBlock() {
		derive();
		return beamBlock;
	}

	/** Y of the highest block of this waypoint in the {@link #beamBlock()} column: the beam starts on top of it. */
	public int beamColumnTopY() {
		derive();
		return beamColumnTopY;
	}

	/** Union of the block boxes (not inflated). */
	public AABB bounds() {
		derive();
		return bounds;
	}

	private void derive() {
		if (derivedVersion == version) return;
		double sx = 0, sy = 0, sz = 0;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos p : blocks) {
			sx += p.getX() + 0.5;
			sy += p.getY() + 0.5;
			sz += p.getZ() + 0.5;
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());
		}
		int n = blocks.size();
		centre = new Vec3(sx / n, sy / n, sz / n);
		topY = maxY;
		bounds = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);

		BlockPos best = blocks.getFirst();
		double bestDistSq = Double.MAX_VALUE;
		for (BlockPos p : blocks) {
			double dx = p.getX() + 0.5 - centre.x;
			double dz = p.getZ() + 0.5 - centre.z;
			double d = dx * dx + dz * dz;
			if (d < bestDistSq - 1.0e-9) { // strictly nearer, so ties keep the earlier block
				best = p;
				bestDistSq = d;
			}
		}
		beamBlock = best;
		int columnTop = best.getY();
		for (BlockPos p : blocks) {
			if (p.getX() == best.getX() && p.getZ() == best.getZ()) columnTop = Math.max(columnTop, p.getY());
		}
		beamColumnTopY = columnTop;
		derivedVersion = version;
	}

	@Override
	public String toString() {
		return "Waypoint[" + name + ", " + blocks.size() + " block(s) at " + anchor() + "]";
	}
}
