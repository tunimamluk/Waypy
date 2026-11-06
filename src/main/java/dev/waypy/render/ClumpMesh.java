package dev.waypy.render;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import dev.waypy.waypoint.Waypoint;

/**
 * The merged highlight geometry of a waypoint's blocks (one block or a clump), built once per
 * {@link Waypoint#version()} and reused every frame.
 *
 * <p><b>Fill</b>: only faces not shared with another block of the waypoint. <b>Outline</b>: silhouette and crease
 * edges only. For each exposed face (direction {@code d}) of block {@code b}, and each of its four sides
 * (in-plane direction {@code s}), the edge between them is drawn unless the face continues flat onto the neighbour:
 * <ul>
 *   <li>{@code b+s} not in the waypoint, {@code b+s+d} not in it → convex edge: drawn;</li>
 *   <li>{@code b+s} in it, {@code b+s+d} not in it → the neighbour's face {@code d} is exposed too and continues
 *       this face in its plane: not drawn;</li>
 *   <li>{@code b+s+d} in it → the face runs into a wall rising from the neighbour (a concave crease, e.g. the inner
 *       corner of a stair step): drawn.</li>
 * </ul>
 * Each edge is emitted once (both faces of an edge find it). Coordinates are relative to {@link #origin} and pushed
 * {@code inflate} outward (faces along their normal, edges along the sum of the two face normals, face sides grow at
 * convex edges and shrink at creases), so a single block gives exactly the old inflated box.
 */
final class ClumpMesh {
	/** Unit steps of the six directions; 0/1 and 2/3 and 4/5 are opposite pairs on the X, Y, Z axes. */
	private static final int[][] DIRS = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

	final int version;
	/** All coordinates are relative to this block's minimum corner. */
	final BlockPos origin;
	/** Fill quads: 12 floats (4 vertices × xyz) per exposed face. */
	final float[] quads;
	/** Outline segments: 6 floats (2 vertices × xyz) per edge. */
	final float[] lines;
	/** World-space bounds of the geometry, for frustum culling. */
	final AABB bounds;

	private ClumpMesh(int version, BlockPos origin, float[] quads, float[] lines, AABB bounds) {
		this.version = version;
		this.origin = origin;
		this.quads = quads;
		this.lines = lines;
		this.bounds = bounds;
	}

	static ClumpMesh build(Waypoint waypoint, double inflate) {
		List<BlockPos> blocks = waypoint.blocks();
		BlockPos origin = waypoint.anchor();
		float e = (float) inflate;
		FloatList quads = new FloatList(blocks.size() * 6 * 12);
		FloatList lines = new FloatList(blocks.size() * 12 * 6);
		Set<Long> seenEdges = new HashSet<>();
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

		for (BlockPos b : blocks) {
			int bx = b.getX() - origin.getX();
			int by = b.getY() - origin.getY();
			int bz = b.getZ() - origin.getZ();
			for (int di = 0; di < 6; di++) {
				int[] d = DIRS[di];
				if (waypoint.contains(probe.setWithOffset(b, d[0], d[1], d[2]))) continue; // internal face
				int dAxis = di / 2;
				// Per face side: how far the face extends past the block edge (+e convex, 0 flat, -e crease).
				float[] grow = new float[6];
				for (int si = 0; si < 6; si++) {
					if (si / 2 == dAxis) continue;
					int[] s = DIRS[si];
					boolean sideIn = waypoint.contains(probe.setWithOffset(b, s[0], s[1], s[2]));
					boolean diagIn = waypoint.contains(probe.setWithOffset(b, s[0] + d[0], s[1] + d[1], s[2] + d[2]));
					if (sideIn && !diagIn) continue; // the face continues flat onto the neighbour: no edge
					grow[si] = diagIn ? -e : e;
					addEdge(lines, seenEdges, bx, by, bz, d, s, diagIn, e);
				}
				addFace(quads, bx, by, bz, di, grow, e);
			}
		}

		AABB bounds = waypoint.bounds().inflate(inflate);
		return new ClumpMesh(waypoint.version(), origin, quads.toArray(), lines.toArray(), bounds);
	}

	/** The face {@code di} of the block at (bx, by, bz), pushed {@code e} along its normal, sides grown by {@code grow}. */
	private static void addFace(FloatList out, int bx, int by, int bz, int di, float[] grow, float e) {
		int axis = di / 2;
		boolean positive = (di & 1) == 1;
		float[] lo = {bx - grow[0], by - grow[2], bz - grow[4]};
		float[] hi = {bx + 1 + grow[1], by + 1 + grow[3], bz + 1 + grow[5]};
		float plane = positive ? (axisCoord(axis, bx, by, bz) + 1 + e) : (axisCoord(axis, bx, by, bz) - e);
		int u = (axis + 1) % 3;
		int v = (axis + 2) % 3;
		float[][] corners = {{lo[u], lo[v]}, {hi[u], lo[v]}, {hi[u], hi[v]}, {lo[u], hi[v]}};
		for (float[] c : corners) {
			float[] p = new float[3];
			p[axis] = plane;
			p[u] = c[0];
			p[v] = c[1];
			out.add(p[0], p[1], p[2]);
		}
	}

	/**
	 * The unit edge of the block at (bx, by, bz) between its face {@code d} and its side {@code s}, offset
	 * {@code e} along {@code d + s} (convex) or {@code d - s} (crease, into the open corner). Skipped if already added.
	 */
	private static void addEdge(FloatList out, Set<Long> seen, int bx, int by, int bz, int[] d, int[] s, boolean crease, float e) {
		// The edge runs along the axis that is neither d's nor s's; its other two coordinates are fixed by d and s.
		int[] start = {bx, by, bz};
		int along = 0;
		for (int a = 0; a < 3; a++) {
			if (d[a] > 0 || s[a] > 0) start[a] += 1;
			if (d[a] == 0 && s[a] == 0) along = a;
		}
		long key = ((long) (start[0] & 0xFFFFF) << 42) | ((long) (start[1] & 0xFFFFF) << 22) | ((long) (start[2] & 0xFFFFF) << 2) | along;
		if (!seen.add(key)) return;
		float[] off = new float[3];
		for (int a = 0; a < 3; a++) off[a] = (d[a] + (crease ? -s[a] : s[a])) * e;
		float x0 = start[0] + off[0], y0 = start[1] + off[1], z0 = start[2] + off[2];
		out.add(x0, y0, z0);
		out.add(x0 + (along == 0 ? 1 : 0), y0 + (along == 1 ? 1 : 0), z0 + (along == 2 ? 1 : 0));
	}

	private static int axisCoord(int axis, int x, int y, int z) {
		return axis == 0 ? x : axis == 1 ? y : z;
	}

	/** Minimal growable float array. */
	private static final class FloatList {
		private float[] data;
		private int size;

		FloatList(int capacity) {
			data = new float[Math.max(16, capacity)];
		}

		void add(float x, float y, float z) {
			if (size + 3 > data.length) data = Arrays.copyOf(data, data.length * 2);
			data[size++] = x;
			data[size++] = y;
			data[size++] = z;
		}

		float[] toArray() {
			return Arrays.copyOf(data, size);
		}
	}
}
