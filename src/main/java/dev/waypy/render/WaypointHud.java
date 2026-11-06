package dev.waypy.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import dev.waypy.config.LabelMode;
import dev.waypy.config.WaypyConfig;
import dev.waypy.waypoint.LogoType;
import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointManager;
import dev.waypy.waypoint.WaypointStyle;

/**
 * Draws the logo + "name (distance)" label of every visible waypoint as a 2D HUD overlay:
 * the anchor point above the waypoint is projected to the screen with the same
 * projection * bobbing * view-rotation matrix the level is rendered with, then the logo and
 * label are drawn there. Being HUD, it is always on top of the
 * world (visible through blocks) and never clipped by the far plane.
 *
 * <p>Size: each marker (logo and label) is scaled by {@link #distanceScale} times the "Icon size" setting, through
 * the GUI pose matrix. The label is never drawn below {@link #MIN_LABEL_SCALE}, so it stays legible.
 *
 * <p>Logos always draw. Labels ("Labels: Always") are decluttered with the scaled rectangles: a label that
 * would overlap another label, or the logo of a nearer waypoint, is hidden if its waypoint is the farther one.
 * The label of the marker nearest the crosshair (within {@link #FOCUS_RADIUS} GUI px) always shows and is drawn
 * on top; with "Labels: Looking At" it is the only label.
 */
final class WaypointHud implements HudElement {
	/** Logo anchor height above the top face of a BLOCK_HIGHLIGHT waypoint, in blocks. */
	static final double HIGHLIGHT_LOGO_HEIGHT = 1.5;
	/** Logo anchor height above the top face of a BEACON waypoint, in blocks. */
	static final double BEACON_LOGO_HEIGHT = 3.0;
	/** Half the logo height in GUI pixels (item icons are 16x16). A NONE logo keeps this space so labels don't move. */
	private static final int LOGO_HALF_HEIGHT = 8;
	/** Gap between logo and label, in GUI pixels. */
	private static final int LABEL_GAP = 3;
	/** Default label background opacity (vanilla nametags use 0.25; slightly darker reads better on the HUD). */
	private static final float LABEL_BACKGROUND_OPACITY = 0.4f;
	/** Points closer than this (view-space w) to the camera plane are treated as behind the camera. */
	private static final float MIN_CLIP_W = 0.05f;
	/** The marker nearest the screen centre within this many GUI pixels always shows its label. */
	private static final float FOCUS_RADIUS = 40.0f;
	/** Extra spacing kept between label rectangles, in GUI pixels. */
	private static final int LABEL_MARGIN = 1;

	/** Markers at or nearer than this many blocks are drawn at full size. */
	static final double SHRINK_NEAR = 16.0;
	/** Markers at or beyond this many blocks are drawn at the minimum size. */
	static final double SHRINK_FAR = 256.0;
	private static final double LOG_SHRINK_NEAR = Math.log(SHRINK_NEAR);
	private static final double LOG_SHRINK_FAR = Math.log(SHRINK_FAR);
	/** Labels never draw smaller than this (effective scale), however small the logo gets. */
	static final float MIN_LABEL_SCALE = 0.5f;

	private final Map<Identifier, ItemStack> logoStacks = new HashMap<>();
	private final Matrix4f viewProjection = new Matrix4f();
	private final Vector4f clip = new Vector4f();

	/**
	 * A projected waypoint. {@code x}/{@code y} is the logo centre (snapped to framebuffer pixels);
	 * {@code logoScale} and {@code labelScale} are the pose scales of the logo and of the label.
	 */
	private record Marker(Waypoint waypoint, float x, float y, double distance, float logoScale, float labelScale) {
	}

	/**
	 * Marker size factor (before the global icon size): 1 at or under {@link #SHRINK_NEAR} blocks, {@code min} at or
	 * beyond {@link #SHRINK_FAR}, linear in log(distance) in between. Always 1 when shrinking is off.
	 */
	static float distanceScale(double distance, boolean shrink, float min) {
		if (!shrink || distance <= SHRINK_NEAR) return 1.0f;
		if (distance >= SHRINK_FAR) return min;
		double t = (Math.log(distance) - LOG_SHRINK_NEAR) / (LOG_SHRINK_FAR - LOG_SHRINK_NEAR);
		return (float) Mth.lerp(t, 1.0, min);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) return;
		GameRenderState renderState = minecraft.gameRenderer.gameRenderState();
		CameraRenderState camera = renderState.levelRenderState.cameraRenderState;
		if (!camera.initialized) return;

		List<Waypoint> waypoints = WaypointManager.get().visibleIn(minecraft.level.dimension());
		if (waypoints.isEmpty()) return;

		WaypyConfig config = WaypyConfig.get();
		float iconSize = config.iconSize / 100.0f;
		float minSize = config.minIconSize / 100.0f;

		computeViewProjection(renderState, camera);
		int guiWidth = graphics.guiWidth();
		int guiHeight = graphics.guiHeight();
		int guiScale = Math.max(1, renderState.windowRenderState.guiScale);
		Vec3 cam = camera.pos;
		// Smallest label scale that maps font texels to whole framebuffer pixels and is >= MIN_LABEL_SCALE.
		float minLabelScale = (float) Math.ceil(MIN_LABEL_SCALE * guiScale - 1.0e-4f) / guiScale;

		List<Marker> markers = new ArrayList<>(waypoints.size());
		for (Waypoint waypoint : waypoints) {
			// Above the clump: x/z of the centroid, y over the top face of its highest block.
			Vec3 centre = waypoint.centre();
			double height = waypoint.style == WaypointStyle.BEACON ? BEACON_LOGO_HEIGHT : HIGHLIGHT_LOGO_HEIGHT;
			double ax = centre.x - cam.x;
			double ay = waypoint.topY() + 1.0 + height - cam.y;
			double az = centre.z - cam.z;
			clip.set((float) ax, (float) ay, (float) az, 1.0f);
			viewProjection.transform(clip);
			if (clip.w < MIN_CLIP_W) continue; // behind the camera
			float ndcX = clip.x / clip.w;
			float ndcY = clip.y / clip.w;
			// Generous bounds so a marker partly on screen is still drawn.
			if (ndcX < -1.5f || ndcX > 1.5f || ndcY < -1.5f || ndcY > 1.5f) continue;
			// Snap to whole framebuffer pixels so text and icons stay crisp.
			float x = snap((ndcX * 0.5f + 0.5f) * guiWidth, guiScale);
			float y = snap((0.5f - ndcY * 0.5f) * guiHeight, guiScale);
			double distance = centre.distanceTo(cam);
			float scale = distanceScale(distance, config.shrinkWithDistance, minSize) * iconSize;
			// The logo scale is a multiple of 1/16 framebuffer pixel per 16-px icon, so the icon covers whole pixels;
			// the label scale maps each font texel to a whole number of framebuffer pixels, so text stays crisp.
			float logoScale = Math.max(1, Math.round(scale * 16 * guiScale)) / (16.0f * guiScale);
			float labelScale = Math.max(minLabelScale, Math.round(scale * guiScale) / (float) guiScale);
			markers.add(new Marker(waypoint, x, y, distance, logoScale, labelScale));
		}
		if (markers.isEmpty()) return;

		Font font = minecraft.font;
		int labelBackground = minecraft.options.getBackgroundColor(LABEL_BACKGROUND_OPACITY);

		// The marker nearest the crosshair (if close enough) always shows its label, on top of everything.
		float centerX = guiWidth / 2.0f;
		float centerY = guiHeight / 2.0f;
		Marker focused = null;
		float focusedDistSq = FOCUS_RADIUS * FOCUS_RADIUS;
		for (Marker marker : markers) {
			float ddx = marker.x() - centerX;
			float ddy = marker.y() - centerY;
			float distSq = ddx * ddx + ddy * ddy;
			if (distSq <= focusedDistSq) {
				focused = marker;
				focusedDistSq = distSq;
			}
		}

		// Label decluttering, nearest first: a label whose (scaled) rectangle overlaps one already accepted is
		// hidden, so on a collision the waypoint farther from the camera loses. The focused label is placed first.
		markers.sort(Comparator.comparingDouble(Marker::distance));
		Map<Marker, Label> shown = new IdentityHashMap<>();
		LabelMode labelMode = config.labels;
		if (labelMode != LabelMode.OFF && focused != null) {
			shown.put(focused, label(font, focused, config.showDistance, guiScale));
		}
		if (labelMode == LabelMode.ALWAYS) {
			// Obstacles: accepted labels, and the logos of nearer markers (which paint over farther labels).
			List<Rect> obstacles = new ArrayList<>();
			for (Label label : shown.values()) obstacles.add(label.rect());
			for (Marker marker : markers) {
				if (marker != focused) {
					Label label = label(font, marker, config.showDistance, guiScale);
					boolean overlaps = false;
					for (Rect other : obstacles) {
						if (label.rect().overlaps(other)) {
							overlaps = true;
							break;
						}
					}
					if (!overlaps) {
						obstacles.add(label.rect());
						shown.put(marker, label);
					}
				}
				Rect logo = logoRect(marker);
				if (logo != null) obstacles.add(logo);
			}
		}

		// Paint far to near, so nearer logos (and labels) draw on top; the focused label goes last.
		for (int i = markers.size() - 1; i >= 0; i--) {
			Marker marker = markers.get(i);
			drawLogo(graphics, marker, guiScale);
			Label label = shown.get(marker);
			if (label != null && marker != focused) drawLabel(graphics, font, label, labelBackground);
		}
		Label focusedLabel = focused == null ? null : shown.get(focused);
		if (focusedLabel != null) drawLabel(graphics, font, focusedLabel, labelBackground);
	}

	/** Rounds a GUI coordinate to the nearest framebuffer pixel. */
	private static float snap(float gui, int guiScale) {
		return Math.round(gui * guiScale) / (float) guiScale;
	}

	/**
	 * A name/distance label: its text origin ({@code x}, {@code y}, snapped), pose scale, unscaled text width and
	 * its on-screen rectangle (scaled background plus margin), in GUI coordinates.
	 */
	private record Label(Component text, float x, float y, float scale, int width, Rect rect) {
	}

	/** An axis-aligned rectangle in GUI coordinates. */
	private record Rect(float left, float top, float right, float bottom) {
		boolean overlaps(Rect o) {
			return left < o.right && o.left < right && top < o.bottom && o.top < bottom;
		}
	}

	/** The on-screen rectangle of a marker's logo, as drawn by {@link #drawLogo}; null if it has none. */
	private static @Nullable Rect logoRect(Marker marker) {
		if (marker.waypoint().logoType != LogoType.BLOCK) return null;
		float s = marker.logoScale();
		return new Rect(marker.x() - LOGO_HALF_HEIGHT * s, marker.y() - LOGO_HALF_HEIGHT * s,
				marker.x() + LOGO_HALF_HEIGHT * s, marker.y() + LOGO_HALF_HEIGHT * s);
	}

	private static Label label(Font font, Marker marker, boolean showDistance, int guiScale) {
		Waypoint waypoint = marker.waypoint();
		Component text = showDistance
				? Component.translatable("waypy.render.label", waypoint.name, String.valueOf((int) Math.floor(marker.distance())))
				: Component.literal(waypoint.name);
		int width = font.width(text);
		float s = marker.labelScale();
		float x = snap(marker.x() - width * s / 2.0f, guiScale);
		// Below the (scaled) logo, then the gap and the 1 px of background above the glyphs' top padding.
		float y = snap(marker.y() + (LOGO_HALF_HEIGHT + LABEL_GAP) * marker.logoScale() + s, guiScale);
		// Background rectangle as drawn by drawLabel, plus a margin so labels never touch.
		return new Label(text, x, y, s, width, new Rect(
				x - 2 * s - LABEL_MARGIN, y - 2 * s - LABEL_MARGIN,
				x + (width + 1) * s + LABEL_MARGIN, y + font.lineHeight * s + LABEL_MARGIN));
	}

	private void drawLogo(GuiGraphicsExtractor graphics, Marker marker, int guiScale) {
		Waypoint waypoint = marker.waypoint();
		if (waypoint.logoType != LogoType.BLOCK) return; // NONE: no logo, the label keeps its place
		float s = marker.logoScale();
		graphics.pose().pushMatrix();
		// Like vanilla's scaled GUI items (e.g. recipe toasts): translate to the icon's top-left, then scale the pose.
		graphics.pose().translate(snap(marker.x() - LOGO_HALF_HEIGHT * s, guiScale), snap(marker.y() - LOGO_HALF_HEIGHT * s, guiScale));
		graphics.pose().scale(s, s);
		graphics.item(logoStack(waypoint.logoBlock), 0, 0);
		graphics.pose().popMatrix();
	}

	private static void drawLabel(GuiGraphicsExtractor graphics, Font font, Label label, int labelBackground) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(label.x(), label.y());
		graphics.pose().scale(label.scale(), label.scale());
		if (ARGB.alpha(labelBackground) != 0) {
			graphics.fill(-2, -2, label.width() + 1, font.lineHeight, labelBackground);
		}
		graphics.text(font, label.text(), 0, 0, 0xFFFFFFFF, false);
		graphics.pose().popMatrix();
	}

	private ItemStack logoStack(Identifier id) {
		return logoStacks.computeIfAbsent(id, key -> {
			Item item = BuiltInRegistries.ITEM.getOptional(key).filter(i -> i != Items.AIR).orElse(Items.COMPASS);
			return new ItemStack(item);
		});
	}

	/**
	 * projection * hurt/bob transform * view rotation, mirroring {@code GameRenderer#renderLevel}
	 * (the nausea/portal warp is ignored). Positions are camera-relative.
	 */
	private void computeViewProjection(GameRenderState renderState, CameraRenderState camera) {
		PoseStack bob = new PoseStack();
		bobHurt(renderState, camera.entityRenderState, bob);
		if (renderState.optionsRenderState.bobView) {
			bobView(camera.entityRenderState, bob);
		}
		viewProjection.set(camera.projectionMatrix).mul(bob.last().pose()).mul(camera.viewRotationMatrix);
	}

	/** Copy of the private {@code GameRenderer#bobHurt}. */
	private static void bobHurt(GameRenderState renderState, CameraEntityRenderState entity, PoseStack poseStack) {
		if (!entity.isLiving) return;
		float hurt = entity.hurtTime;
		if (entity.isDeadOrDying) {
			float duration = Math.min(entity.deathTime, 20.0F);
			poseStack.mulPose(Axis.ZP.rotationDegrees(40.0F - 8000.0F / (duration + 200.0F)));
		}
		if (hurt < 0.0F || entity.hurtDuration <= 0) return;
		hurt /= entity.hurtDuration;
		hurt = Mth.sin(hurt * hurt * hurt * hurt * (float) Math.PI);
		float dir = entity.hurtDir;
		poseStack.mulPose(Axis.YP.rotationDegrees(-dir));
		float tilt = (float) (-hurt * 14.0 * renderState.optionsRenderState.damageTiltStrength);
		poseStack.mulPose(Axis.ZP.rotationDegrees(tilt));
		poseStack.mulPose(Axis.YP.rotationDegrees(dir));
	}

	/** Copy of the private {@code GameRenderer#bobView}. */
	private static void bobView(CameraEntityRenderState entity, PoseStack poseStack) {
		if (!entity.isPlayer) return;
		float walk = entity.backwardsInterpolatedWalkDistance;
		float bob = entity.bob;
		poseStack.translate(
				Mth.sin(walk * (float) Math.PI) * bob * 0.5F,
				-Math.abs(Mth.cos(walk * (float) Math.PI) * bob),
				0.0F);
		poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.sin(walk * (float) Math.PI) * bob * 3.0F));
		poseStack.mulPose(Axis.XP.rotationDegrees(Math.abs(Mth.cos(walk * (float) Math.PI - 0.2F) * bob) * 5.0F));
	}
}
