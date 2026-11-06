package dev.waypy.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import dev.waypy.config.LabelMode;
import dev.waypy.config.WaypyConfig;
import dev.waypy.waypoint.LogoType;
import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointManager;

/**
 * Draws the logo + "name (distance)" label of every visible waypoint as a 2D HUD overlay:
 * the anchor point above the waypoint is projected to the screen with the same
 * projection * bobbing * view-rotation matrix the level is rendered with, then the logo and
 * label are drawn there. Being HUD, it is always on top of the
 * world (visible through blocks) and never clipped by the far plane.
 *
 * <p>Size: each marker (logo and label) is scaled by {@link #distanceScale} times the "Icon size" setting, through
 * the GUI pose matrix, continuously (no quantisation, so markers shrink smoothly). The label is never drawn below
 * {@link #MIN_LABEL_SCALE}, so it stays legible. Only the marker's anchor is snapped to a framebuffer pixel.
 *
 * <p>Logos always draw. A label is drawn only for waypoints with {@code showText} on; the others take no part in
 * label decisions. Labels ("Labels: Always") are decluttered with the scaled rectangles: a label that
 * would overlap another label, or the logo of a nearer waypoint, is hidden if its waypoint is the farther one.
 * The label of the marker nearest the crosshair (within {@link #FOCUS_RADIUS} GUI px) always shows and is drawn
 * on top; with "Labels: Looking At" it is the only label. Labels shown or hidden by these rules fade in and out
 * over {@link #LABEL_FADE_MILLIS} instead of popping.
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
	/** Time a label takes to fade fully in or out, in milliseconds. */
	static final float LABEL_FADE_MILLIS = 175.0f;
	/** Longest frame time a fade advances by (so a hitch doesn't skip a fade). */
	private static final float MAX_FRAME_MILLIS = 100.0f;

	/** Label opacity (0..1] per waypoint id, for markers on screen in the last frame. */
	private final Map<UUID, Float> labelAlpha = new HashMap<>();
	private long lastFrameNanos;

	private final Map<Identifier, ItemStack> logoStacks = new HashMap<>();
	private final Matrix4f viewProjection = new Matrix4f();
	private final Vector4f clip = new Vector4f();

	/**
	 * A projected waypoint. {@code x}/{@code y} is the logo centre (snapped to a framebuffer pixel);
	 * {@code logoScale} and {@code labelScale} are the (continuous) pose scales of the logo and of the label.
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
		long now = Util.getNanos();
		float frameMillis = lastFrameNanos == 0L ? 0.0f : Math.clamp((now - lastFrameNanos) / 1.0e6f, 0.0f, MAX_FRAME_MILLIS);
		lastFrameNanos = now;

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) {
			labelAlpha.clear();
			return;
		}
		GameRenderState renderState = minecraft.gameRenderer.gameRenderState();
		CameraRenderState camera = renderState.levelRenderState.cameraRenderState;
		if (!camera.initialized) return;

		List<Waypoint> waypoints = WaypointManager.get().visibleIn(minecraft.level.dimension());
		if (waypoints.isEmpty()) {
			labelAlpha.clear();
			return;
		}

		WaypyConfig config = WaypyConfig.get();
		float iconSize = config.iconSize / 100.0f;
		float minSize = config.minIconSize / 100.0f;

		computeViewProjection(renderState, camera);
		int guiWidth = graphics.guiWidth();
		int guiHeight = graphics.guiHeight();
		int guiScale = Math.max(1, renderState.windowRenderState.guiScale);
		Vec3 cam = camera.pos;

		List<Marker> markers = new ArrayList<>(waypoints.size());
		for (Waypoint waypoint : waypoints) {
			// Above the clump: x/z of the centroid, y over the top face of its highest block.
			Vec3 centre = waypoint.centre();
			double height = waypoint.isBeacon() ? BEACON_LOGO_HEIGHT : HIGHLIGHT_LOGO_HEIGHT;
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
			// Only the marker's anchor is snapped to a framebuffer pixel; logo and label are laid out from it with
			// unsnapped (continuous) offsets, so they never jitter against each other while the scale changes.
			float x = snap((ndcX * 0.5f + 0.5f) * guiWidth, guiScale);
			float y = snap((0.5f - ndcY * 0.5f) * guiHeight, guiScale);
			double distance = centre.distanceTo(cam);
			// Continuous scales (no quantisation), so markers shrink smoothly while walking away.
			float scale = distanceScale(distance, config.shrinkWithDistance, minSize) * iconSize;
			markers.add(new Marker(waypoint, x, y, distance, scale, Math.max(MIN_LABEL_SCALE, scale)));
		}
		if (markers.isEmpty()) {
			labelAlpha.clear();
			return;
		}

		Font font = minecraft.font;
		int labelBackground = minecraft.options.getBackgroundColor(LABEL_BACKGROUND_OPACITY);
		LabelMode labelMode = config.labels;

		// The text marker nearest the crosshair (if close enough) always shows its label, on top of everything.
		// Waypoints with their text off take no part in any label decision.
		float centerX = guiWidth / 2.0f;
		float centerY = guiHeight / 2.0f;
		Marker focused = null;
		float focusedDistSq = FOCUS_RADIUS * FOCUS_RADIUS;
		for (Marker marker : markers) {
			if (!marker.waypoint().showText) continue;
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
		if (labelMode != LabelMode.OFF && focused != null) {
			shown.put(focused, label(font, focused, config.showDistance));
		}
		if (labelMode == LabelMode.ALWAYS) {
			// Obstacles: accepted labels, and the logos of nearer markers (which paint over farther labels).
			List<Rect> obstacles = new ArrayList<>();
			for (Label label : shown.values()) obstacles.add(label.rect());
			for (Marker marker : markers) {
				if (marker != focused && marker.waypoint().showText) {
					Label label = label(font, marker, config.showDistance);
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

		// Fades: each label's opacity moves toward 1 (shown) or 0 (hidden) by frame time, so labels never pop.
		// State is kept only for markers on screen this frame (deleted or hidden waypoints drop out at once).
		float step = frameMillis / LABEL_FADE_MILLIS;
		Map<Marker, Float> alphas = new IdentityHashMap<>();
		Set<UUID> onScreen = new HashSet<>();
		for (Marker marker : markers) {
			UUID id = marker.waypoint().id;
			onScreen.add(id);
			float previous = labelAlpha.getOrDefault(id, 0.0f);
			float target = shown.containsKey(marker) ? 1.0f : 0.0f;
			float alpha = target > previous ? Math.min(target, previous + step) : Math.max(target, previous - step);
			if (alpha <= 0.0f) {
				labelAlpha.remove(id);
				continue;
			}
			labelAlpha.put(id, alpha);
			alphas.put(marker, alpha);
		}
		labelAlpha.keySet().retainAll(onScreen);

		// Paint far to near, so nearer logos (and labels) draw on top; the focused label goes last.
		for (int i = markers.size() - 1; i >= 0; i--) {
			Marker marker = markers.get(i);
			drawLogo(graphics, marker);
			Float alpha = alphas.get(marker);
			if (alpha != null && marker != focused) {
				Label label = shown.get(marker);
				// A label fading out is no longer placed, but still drawn where it would be.
				if (label == null) label = label(font, marker, config.showDistance);
				drawLabel(graphics, font, label, labelBackground, alpha);
			}
		}
		if (focused != null && alphas.containsKey(focused)) {
			Label label = shown.get(focused);
			if (label == null) label = label(font, focused, config.showDistance);
			drawLabel(graphics, font, label, labelBackground, alphas.get(focused));
		}
	}

	/** Rounds a GUI coordinate to the nearest framebuffer pixel. */
	private static float snap(float gui, int guiScale) {
		return Math.round(gui * guiScale) / (float) guiScale;
	}

	/**
	 * A name/distance label: its text origin ({@code x}, {@code y}), pose scale, unscaled text width and
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

	private static Label label(Font font, Marker marker, boolean showDistance) {
		Waypoint waypoint = marker.waypoint();
		Component text = showDistance
				? Component.translatable("waypy.render.label", waypoint.name, String.valueOf((int) Math.floor(marker.distance())))
				: Component.literal(waypoint.name);
		int width = font.width(text);
		float s = marker.labelScale();
		float x = marker.x() - width * s / 2.0f;
		// Below the (scaled) logo, then the gap and the 1 px of background above the glyphs' top padding.
		float y = marker.y() + (LOGO_HALF_HEIGHT + LABEL_GAP) * marker.logoScale() + s;
		// Background rectangle as drawn by drawLabel, plus a margin so labels never touch.
		return new Label(text, x, y, s, width, new Rect(
				x - 2 * s - LABEL_MARGIN, y - 2 * s - LABEL_MARGIN,
				x + (width + 1) * s + LABEL_MARGIN, y + font.lineHeight * s + LABEL_MARGIN));
	}

	private void drawLogo(GuiGraphicsExtractor graphics, Marker marker) {
		Waypoint waypoint = marker.waypoint();
		if (waypoint.logoType != LogoType.BLOCK) return; // NONE: no logo, the label keeps its place
		float s = marker.logoScale();
		graphics.pose().pushMatrix();
		// Like vanilla's scaled GUI items (e.g. recipe toasts): translate to the icon's top-left, then scale the pose.
		graphics.pose().translate(marker.x() - LOGO_HALF_HEIGHT * s, marker.y() - LOGO_HALF_HEIGHT * s);
		graphics.pose().scale(s, s);
		graphics.item(logoStack(waypoint.logoBlock), 0, 0);
		graphics.pose().popMatrix();
	}

	/** Draws a label at {@code alpha} opacity (0..1, from its fade). */
	private static void drawLabel(GuiGraphicsExtractor graphics, Font font, Label label, int labelBackground, float alpha) {
		int textColor = ARGB.color(Math.round(alpha * 255.0f), 0xFFFFFF);
		if (ARGB.alpha(textColor) == 0) return;
		graphics.pose().pushMatrix();
		graphics.pose().translate(label.x(), label.y());
		graphics.pose().scale(label.scale(), label.scale());
		int background = ARGB.multiplyAlpha(labelBackground, alpha);
		if (ARGB.alpha(background) != 0) {
			graphics.fill(-2, -2, label.width() + 1, font.lineHeight, background);
		}
		graphics.text(font, label.text(), 0, 0, textColor, false);
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
