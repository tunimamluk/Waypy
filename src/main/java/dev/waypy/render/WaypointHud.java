package dev.waypy.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import dev.waypy.waypoint.LogoType;
import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointManager;
import dev.waypy.waypoint.WaypointStyle;

/**
 * Draws the logo + "name (distance)" label of every visible waypoint as a 2D HUD overlay:
 * the anchor point above the waypoint is projected to the screen with the same
 * projection * bobbing * view-rotation matrix the level is rendered with, then the logo and
 * label are drawn there at a constant on-screen size. Being HUD, it is always on top of the
 * world (visible through blocks) and never clipped by the far plane.
 */
final class WaypointHud implements HudElement {
	/** Logo anchor height above the top face of a BLOCK_HIGHLIGHT waypoint, in blocks. */
	static final double HIGHLIGHT_LOGO_HEIGHT = 1.5;
	/** Logo anchor height above the top face of a BEACON waypoint, in blocks. */
	static final double BEACON_LOGO_HEIGHT = 3.0;
	/** Half the logo height in GUI pixels (item icons are 16x16; the text badge is sized to match). */
	private static final int LOGO_HALF_HEIGHT = 8;
	/** Gap between logo and label, in GUI pixels. */
	private static final int LABEL_GAP = 3;
	/** Text-logo badge padding (horizontal) in GUI pixels. */
	private static final int BADGE_PAD_X = 4;
	/** Text-logo badge background: dark, semi-transparent. */
	private static final int BADGE_BACKGROUND = 0xA0000000;
	/** Used instead for very dark logo colors so the text stays readable. */
	private static final int BADGE_BACKGROUND_LIGHT = 0xB0E0E0E0;
	/** Default label background opacity (vanilla nametags use 0.25; slightly darker reads better on the HUD). */
	private static final float LABEL_BACKGROUND_OPACITY = 0.4f;
	/** Points closer than this (view-space w) to the camera plane are treated as behind the camera. */
	private static final float MIN_CLIP_W = 0.05f;

	private final Map<Identifier, ItemStack> logoStacks = new HashMap<>();
	private final Matrix4f viewProjection = new Matrix4f();
	private final Vector4f clip = new Vector4f();

	private record Marker(Waypoint waypoint, float x, float y, double distance) {
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

		computeViewProjection(renderState, camera);
		int guiWidth = graphics.guiWidth();
		int guiHeight = graphics.guiHeight();
		int guiScale = Math.max(1, renderState.windowRenderState.guiScale);
		Vec3 cam = camera.pos;

		List<Marker> markers = new ArrayList<>(waypoints.size());
		for (Waypoint waypoint : waypoints) {
			BlockPos pos = waypoint.pos;
			double height = waypoint.style == WaypointStyle.BEACON ? BEACON_LOGO_HEIGHT : HIGHLIGHT_LOGO_HEIGHT;
			double ax = pos.getX() + 0.5 - cam.x;
			double ay = pos.getY() + 1.0 + height - cam.y;
			double az = pos.getZ() + 0.5 - cam.z;
			clip.set((float) ax, (float) ay, (float) az, 1.0f);
			viewProjection.transform(clip);
			if (clip.w < MIN_CLIP_W) continue; // behind the camera
			float ndcX = clip.x / clip.w;
			float ndcY = clip.y / clip.w;
			// Generous bounds so a marker partly on screen is still drawn.
			if (ndcX < -1.5f || ndcX > 1.5f || ndcY < -1.5f || ndcY > 1.5f) continue;
			// Snap to whole framebuffer pixels so text and icons stay crisp.
			float x = Math.round((ndcX * 0.5f + 0.5f) * guiWidth * guiScale) / (float) guiScale;
			float y = Math.round((0.5f - ndcY * 0.5f) * guiHeight * guiScale) / (float) guiScale;
			double distance = Vec3.atCenterOf(pos).distanceTo(cam);
			markers.add(new Marker(waypoint, x, y, distance));
		}
		if (markers.isEmpty()) return;

		// Far first, so nearer markers draw on top.
		markers.sort(Comparator.comparingDouble(Marker::distance).reversed());
		Font font = minecraft.font;
		int labelBackground = minecraft.options.getBackgroundColor(LABEL_BACKGROUND_OPACITY);
		for (Marker marker : markers) {
			drawMarker(graphics, font, marker, labelBackground);
		}
	}

	private void drawMarker(GuiGraphicsExtractor graphics, Font font, Marker marker, int labelBackground) {
		Waypoint waypoint = marker.waypoint();
		graphics.pose().pushMatrix();
		graphics.pose().translate(marker.x(), marker.y());

		if (waypoint.logoType == LogoType.TEXT) {
			String text = logoText(waypoint);
			int width = font.width(text);
			int left = -width / 2;
			int textTop = -4;
			int textColor = ARGB.opaque(waypoint.color);
			int background = isVeryDark(waypoint.color) ? BADGE_BACKGROUND_LIGHT : BADGE_BACKGROUND;
			// Badge is exactly 16 px high to match an item icon; width follows the text.
			graphics.fill(left - BADGE_PAD_X, -LOGO_HALF_HEIGHT, left + width + BADGE_PAD_X - 1, LOGO_HALF_HEIGHT, background);
			graphics.text(font, text, left, textTop, textColor, false);
		} else {
			graphics.item(logoStack(waypoint.logoBlock), -8, -LOGO_HALF_HEIGHT);
		}

		Component label = Component.translatable("waypy.render.label", waypoint.name, String.valueOf((int) Math.floor(marker.distance())));
		int labelWidth = font.width(label);
		int labelLeft = -labelWidth / 2;
		int labelTop = LOGO_HALF_HEIGHT + LABEL_GAP + 1;
		if (ARGB.alpha(labelBackground) != 0) {
			graphics.fill(labelLeft - 2, labelTop - 2, labelLeft + labelWidth + 1, labelTop + font.lineHeight, labelBackground);
		}
		graphics.text(font, label, labelLeft, labelTop, 0xFFFFFFFF, false);

		graphics.pose().popMatrix();
	}

	private static String logoText(Waypoint waypoint) {
		String text = waypoint.logoText == null ? "" : waypoint.logoText.strip();
		if (!text.isEmpty()) return text;
		// Empty text logo: fall back to the first letter of the name rather than drawing an empty badge.
		String name = waypoint.name == null ? "" : waypoint.name.strip();
		return name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
	}

	private static boolean isVeryDark(int rgb) {
		float luma = 0.299f * ARGB.red(rgb) + 0.587f * ARGB.green(rgb) + 0.114f * ARGB.blue(rgb);
		return luma < 48f;
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
