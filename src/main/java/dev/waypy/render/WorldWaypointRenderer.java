package dev.waypy.render;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointManager;
import dev.waypy.waypoint.WaypointStyle;

/**
 * Submits the in-world geometry of waypoints (beacon beams and block highlights) from
 * Fabric's {@code LevelRenderEvents.COLLECT_SUBMITS}, i.e. at the end of
 * {@code LevelRenderer#submitFeatures}, alongside block entities and particles.
 *
 * <p>The pose stack handed to us is empty (identity); like vanilla's block entity submission,
 * all positions are made camera-relative by subtracting {@code cameraRenderState.pos}.
 */
final class WorldWaypointRenderer {
	/** Same factor vanilla uses to widen far-away beams ({@code BeaconRenderer.BEAM_SCALE_THRESHOLD}). */
	private static final float BEAM_SCALE_DISTANCE = 96.0f;
	/** Highlight box grows by this much on every side, to avoid z-fighting with the block faces. */
	static final double HIGHLIGHT_INFLATE = 0.002;
	/** Outline width relative to vanilla's block-selection outline width. */
	static final float OUTLINE_WIDTH_SCALE = 1.5f;
	/** Lines whose end is closer than this to the camera plane are clipped (as vanilla gizmos do). */
	private static final float NEAR_CLIP = -0.05f;
	/** Far beams are clamped so their farthest point stays within this fraction of the camera's far plane. */
	private static final double FAR_PLANE_MARGIN = 0.9;

	private WorldWaypointRenderer() {
	}

	static void collectSubmits(LevelRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) return;
		List<Waypoint> waypoints = WaypointManager.get().visibleIn(level.dimension());
		if (waypoints.isEmpty()) return;

		CameraRenderState camera = context.levelState().cameraRenderState;
		if (!camera.initialized) return;
		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();
		double maxDistance = minecraft.options.getEffectiveRenderDistance() * 16.0;
		float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float animationTime = Math.floorMod(level.getGameTime(), 40L) + partialTick;
		boolean scoping = minecraft.player != null && minecraft.player.isScoping();

		for (Waypoint waypoint : waypoints) {
			if (waypoint.style == WaypointStyle.BEACON) {
				submitBeam(waypoint, level, camera, poseStack, collector, animationTime, scoping);
			} else {
				submitHighlight(minecraft, waypoint, camera, poseStack, collector, maxDistance);
			}
		}
	}

	// ---------------------------------------------------------------- beacon beam

	private static void submitBeam(Waypoint waypoint, ClientLevel level, CameraRenderState camera, PoseStack poseStack,
			SubmitNodeCollector collector, float animationTime, boolean scoping) {
		BlockPos pos = waypoint.pos;
		// The beam starts on top of the waypoint block (so a solid block doesn't hide its base)
		// and ends at the build limit. Only the position is used, so the chunk need not be loaded.
		int beamBottom = pos.getY() + 1;
		int beamTop = level.getMaxY() + 1;
		int height = beamTop - beamBottom;
		if (height <= 0) return;

		Vec3 cam = camera.pos;
		double relX = pos.getX() - cam.x;
		double relY = pos.getY() - cam.y;
		double relZ = pos.getZ() - cam.z;
		double dx = relX + 0.5;
		double dz = relZ + 0.5;
		float horizontalDistance = (float) Math.sqrt(dx * dx + dz * dz);

		// Same widening as BeaconRenderer.extract: far beams get thicker so they stay visible.
		float radiusScale = scoping ? 1.0f : Math.max(1.0f, horizontalDistance / BEAM_SCALE_DISTANCE);
		float solidRadius = BeaconRenderer.SOLID_BEAM_RADIUS * radiusScale;
		float glowRadius = BeaconRenderer.BEAM_GLOW_RADIUS * radiusScale;

		// Near beams (inside the render-distance fog) are vanilla in every respect. Beams past the
		// start of that fog draw without fog, and if any part of them would lie beyond the far plane
		// the whole beam is scaled down uniformly about the camera: every vertex stays on the same
		// view ray, so on screen it looks exactly like the unclamped beam, just nearer in depth.
		boolean far = horizontalDistance > camera.fogData.renderDistanceStart;
		double scale = 1.0;
		if (far) {
			double topDy = beamTop - cam.y;
			double bottomDy = beamBottom - cam.y;
			double maxDy = Math.max(Math.abs(topDy), Math.abs(bottomDy));
			double farthest = Math.sqrt((double) horizontalDistance * horizontalDistance + maxDy * maxDy) + glowRadius * 1.5;
			double limit = camera.depthFar * FAR_PLANE_MARGIN;
			if (farthest > limit) scale = limit / farthest;
		}

		// The beam rotates around the block center, so a square of 2*glowRadius*sqrt(2) bounds it.
		// Culled with the (possibly clamped) camera-relative bounds, offset back to world space.
		double extent = glowRadius * 1.5;
		AABB bounds = new AABB(
				(dx - extent) * scale + cam.x, (beamBottom - cam.y) * scale + cam.y, (dz - extent) * scale + cam.z,
				(dx + extent) * scale + cam.x, (beamTop - cam.y) * scale + cam.y, (dz + extent) * scale + cam.z);
		if (!camera.cullFrustum.isVisible(bounds)) return;

		int color = ARGB.opaque(waypoint.color);
		poseStack.pushPose();
		poseStack.translate(relX * scale, relY * scale, relZ * scale);
		if (scale != 1.0) poseStack.scale((float) scale, (float) scale, (float) scale);
		if (far) {
			submitBeamGeometry(poseStack, collector, WaypyRenderTypes.BEACON_BEAM_NO_FOG_OPAQUE,
					WaypyRenderTypes.BEACON_BEAM_NO_FOG_TRANSLUCENT, animationTime, height, color, solidRadius, glowRadius);
		} else {
			// Exactly what vanilla's beacon block entity submits for its last beam section:
			// opaque scrolling core (BEACON_BEAM_OPAQUE) + translucent glow with alpha 32 (BEACON_BEAM_TRANSLUCENT).
			BeaconRenderer.submitBeaconBeam(poseStack, collector, BeaconRenderer.BEAM_LOCATION, 1.0f, animationTime,
					1, height, color, solidRadius, glowRadius);
		}
		poseStack.popPose();
	}

	/**
	 * Geometry identical to {@code BeaconRenderer.submitBeaconBeam(..., scale 1, beamStart 1, ...)}, but with the
	 * render types supplied (the fog-free ones). Vanilla's {@code renderPart}/{@code renderQuad} are private.
	 */
	private static void submitBeamGeometry(PoseStack poseStack, SubmitNodeCollector collector, RenderType opaque,
			RenderType translucent, float animationTime, int height, int color, float solidRadius, float glowRadius) {
		int beamStart = 1;
		int beamEnd = beamStart + height;
		poseStack.pushPose();
		poseStack.translate(0.5, 0.0, 0.5);
		float scroll = -animationTime;
		float texVOff = Mth.frac(scroll * 0.2F - Mth.floor(scroll * 0.1F));
		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees(animationTime * 2.25F - 45.0F));
		float r = solidRadius;
		float vv2 = -1.0F + texVOff;
		float solidV1 = height * (0.5F / solidRadius) + vv2;
		collector.submitCustomGeometry(poseStack, opaque, (pose, buffer) -> beamPart(pose, buffer, color, beamStart, beamEnd,
				0.0F, r, r, 0.0F, -r, 0.0F, 0.0F, -r, solidV1, vv2));
		poseStack.popPose();
		float g = glowRadius;
		float glowV1 = height + vv2;
		collector.submitCustomGeometry(poseStack, translucent, (pose, buffer) -> beamPart(pose, buffer, ARGB.color(32, color),
				beamStart, beamEnd, -g, -g, g, -g, -g, g, g, g, glowV1, vv2));
		poseStack.popPose();
	}

	private static void beamPart(PoseStack.Pose pose, VertexConsumer buffer, int color, int beamStart, int beamEnd,
			float wnx, float wnz, float enx, float enz, float wsx, float wsz, float esx, float esz, float vv1, float vv2) {
		beamQuad(pose, buffer, color, beamStart, beamEnd, wnx, wnz, enx, enz, vv1, vv2);
		beamQuad(pose, buffer, color, beamStart, beamEnd, esx, esz, wsx, wsz, vv1, vv2);
		beamQuad(pose, buffer, color, beamStart, beamEnd, enx, enz, esx, esz, vv1, vv2);
		beamQuad(pose, buffer, color, beamStart, beamEnd, wsx, wsz, wnx, wnz, vv1, vv2);
	}

	private static void beamQuad(PoseStack.Pose pose, VertexConsumer buffer, int color, int beamStart, int beamEnd,
			float ax, float az, float bx, float bz, float vv1, float vv2) {
		beamVertex(pose, buffer, color, beamEnd, ax, az, 1.0F, vv1);
		beamVertex(pose, buffer, color, beamStart, ax, az, 1.0F, vv2);
		beamVertex(pose, buffer, color, beamStart, bx, bz, 0.0F, vv2);
		beamVertex(pose, buffer, color, beamEnd, bx, bz, 0.0F, vv1);
	}

	private static void beamVertex(PoseStack.Pose pose, VertexConsumer buffer, int color, int y, float x, float z, float u, float v) {
		buffer.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0.0F, 1.0F, 0.0F);
	}

	// ---------------------------------------------------------------- block highlight

	private static void submitHighlight(Minecraft minecraft, Waypoint waypoint, CameraRenderState camera, PoseStack poseStack,
			SubmitNodeCollector collector, double maxDistance) {
		BlockPos pos = waypoint.pos;
		Vec3 cam = camera.pos;
		if (Vec3.atCenterOf(pos).distanceToSqr(cam) > maxDistance * maxDistance) return;
		AABB world = new AABB(pos).inflate(HIGHLIGHT_INFLATE);
		if (!camera.cullFrustum.isVisible(world)) return;

		float[] box = {
				(float) (world.minX - cam.x), (float) (world.minY - cam.y), (float) (world.minZ - cam.z),
				(float) (world.maxX - cam.x), (float) (world.maxY - cam.y), (float) (world.maxZ - cam.z)
		};
		int alpha = Math.round(Math.clamp(waypoint.highlightOpacity, 0f, 1f) * 255f);
		int fillColor = ARGB.color(alpha, waypoint.color);
		int lineColor = ARGB.opaque(waypoint.color);
		float lineWidth = minecraft.gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth * OUTLINE_WIDTH_SCALE;
		Matrix4f viewRotation = new Matrix4f(camera.viewRotationMatrix);

		SubmitNodeCollector.CustomGeometryRenderer fill = (pose, buffer) -> emitFilledBox(pose, buffer, box, fillColor);
		SubmitNodeCollector.CustomGeometryRenderer outline = (pose, buffer) -> emitOutline(pose, buffer, box, lineColor, lineWidth, viewRotation);

		if (waypoint.throughBlocks) {
			// ALWAYS_ON_TOP runs after terrain, translucents, clouds, weather and the Fabulous composite,
			// on a cleared depth buffer; our pipelines additionally ignore depth entirely.
			PoseStack.Pose pose = poseStack.last().copy();
			if (alpha > 0) {
				collector.submitCustom(SubmitRenderPhases.ALWAYS_ON_TOP,
						new CustomFeatureRenderer.Submit(pose, WaypyRenderTypes.FILLED_BOX_NO_DEPTH, fill));
			}
			collector.submitCustom(SubmitRenderPhases.ALWAYS_ON_TOP,
					new CustomFeatureRenderer.Submit(pose, WaypyRenderTypes.LINES_NO_DEPTH, outline));
		} else {
			// Vanilla depth-tested types: debug_filled_box (test, no write) and the block-outline lines.
			if (alpha > 0) {
				collector.submitCustomGeometry(poseStack, RenderTypes.debugFilledBox(), fill);
			}
			collector.submitCustomGeometry(poseStack, RenderTypes.lines(), outline);
		}
	}

	/** Six quads (POSITION_COLOR), both windings visible since the pipeline doesn't cull. */
	private static void emitFilledBox(PoseStack.Pose pose, VertexConsumer buffer, float[] b, int color) {
		float x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3], y1 = b[4], z1 = b[5];
		// -Y
		quad(pose, buffer, color, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
		// +Y
		quad(pose, buffer, color, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
		// -Z
		quad(pose, buffer, color, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
		// +Z
		quad(pose, buffer, color, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
		// -X
		quad(pose, buffer, color, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
		// +X
		quad(pose, buffer, color, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer buffer, int color,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		buffer.addVertex(pose, ax, ay, az).setColor(color);
		buffer.addVertex(pose, bx, by, bz).setColor(color);
		buffer.addVertex(pose, cx, cy, cz).setColor(color);
		buffer.addVertex(pose, dx, dy, dz).setColor(color);
	}

	/** The 12 box edges as LINES, clipped against the camera plane like {@code GizmoFeatureRenderer#buildLines}. */
	private static void emitOutline(PoseStack.Pose pose, VertexConsumer buffer, float[] b, int color, float width, Matrix4fc viewRotation) {
		float x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3], y1 = b[4], z1 = b[5];
		Matrix4f toView = new Matrix4f(viewRotation).mul(pose.pose());
		Vector4f start = new Vector4f();
		Vector4f end = new Vector4f();
		Vector4f startView = new Vector4f();
		Vector4f endView = new Vector4f();
		float[][] edges = {
				{x0, y0, z0, x1, y0, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z0, x1, y1, z0}, {x0, y1, z1, x1, y1, z1},
				{x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
				{x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}
		};
		for (float[] e : edges) {
			start.set(e[0], e[1], e[2], 1.0f);
			end.set(e[3], e[4], e[5], 1.0f);
			start.mul(toView, startView);
			end.mul(toView, endView);
			boolean startBehind = startView.z > NEAR_CLIP;
			boolean endBehind = endView.z > NEAR_CLIP;
			if (startBehind && endBehind) continue;
			if (startBehind || endBehind) {
				float denom = endView.z - startView.z;
				if (Math.abs(denom) < 1.0e-9f) continue;
				float t = Math.clamp((NEAR_CLIP - startView.z) / denom, 0.0f, 1.0f);
				Vector4f cut = new Vector4f(start).lerp(end, t);
				if (startBehind) start.set(cut);
				else end.set(cut);
			}
			float nx = end.x - start.x, ny = end.y - start.y, nz = end.z - start.z;
			buffer.addVertex(pose, start.x, start.y, start.z).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
			buffer.addVertex(pose, end.x, end.y, end.z).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
		}
	}
}
