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
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

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
import dev.waypy.waypoint.WaypointGroup;
import dev.waypy.waypoint.WaypointManager;

/**
 * Draws every visible waypoint as a "coloured pin" (design variant D) on a 2D HUD overlay: the anchor point above
 * the waypoint is projected to the screen with the same projection * bobbing * view-rotation matrix the level is
 * rendered with, and the pin is drawn so the tip of its stepped pointer sits exactly on that point. Being HUD, it is
 * always on top of the world (visible through blocks) and never clipped by the far plane.
 *
 * <p>Pin geometry, in GUI pixels at 100 % relative to the anchor (tip) at (0, 0): a 20x20 badge at x -10..10,
 * y -23..-3, filled with the waypoint colour, with a 1 px ring in the shade colour and its four corner pixels cut
 * away; the 16x16 item icon at (-8, -21) (logo NONE: the name's initial, centred, or a 2x2 dot for an empty name);
 * below it a stepped pointer in the shade colour, rows 6, 4 and 2 px wide, the last ending at y 0. The name tag
 * (a 40 % dark plate, 1 px padding left/right/top, white text) sits 2 px above the badge.
 *
 * <p>Size: the pin is scaled by {@link #distanceScale} times the "Icon size" setting, through the GUI pose matrix,
 * continuously (no quantisation, so pins shrink smoothly). The tag is never drawn below {@link #MIN_LABEL_SCALE}, so
 * it stays legible. Only the anchor is snapped to a framebuffer pixel; all offsets from it are whole unscaled
 * pixels, so the pin is pixel-crisp at 100 %.
 *
 * <p>Pins always draw. A tag is drawn only for waypoints with {@code showText} on; the others take no part in
 * tag decisions. Tags ("Labels: Always") are decluttered with the scaled rectangles: a tag that would overlap
 * another tag, or the pin of a nearer waypoint, is hidden if its waypoint is the farther one. The tag of the pin
 * nearest the crosshair (within {@link #FOCUS_RADIUS} GUI px of its badge centre) always shows and is drawn on top;
 * with "Labels: Looking At" it is the only tag. Tags shown or hidden by these rules fade in and out over
 * {@link #LABEL_FADE_MILLIS} instead of popping.
 *
 * <p>Groups ({@link GroupCombiner}): a combined group draws one pin of its own (group colour and logo, tag
 * {@code Name (n) (124m)}) above its members, and its members' pins are not drawn. Crossing the threshold crossfades
 * over {@link #COMBINE_FADE_MILLIS}: member pins fade out while the group pin fades in (and the reverse). Pins and tags
 * fade by alpha; the item icon (which can't be drawn translucent) switches at the half-way point, and the fading pin
 * also shrinks slightly ({@link #FADE_MIN_SCALE}) so the switch reads as part of the transition.
 */
final class WaypointHud implements HudElement {
	/** Pin tip height above the top face of a BLOCK_HIGHLIGHT waypoint, in blocks. */
	static final double HIGHLIGHT_LOGO_HEIGHT = 1.5;
	/** Pin tip height above the top face of a BEACON waypoint, in blocks. */
	static final double BEACON_LOGO_HEIGHT = 3.0;

	/** Badge size (square), in unscaled GUI pixels. */
	static final int BADGE_SIZE = 20;
	/** Height of the stepped pointer (rows 6, 4, 2 px wide) under the badge. */
	static final int POINTER_HEIGHT = 3;
	/** Badge top relative to the tip. */
	private static final int BADGE_TOP = -(BADGE_SIZE + POINTER_HEIGHT);
	private static final int BADGE_LEFT = -BADGE_SIZE / 2;
	/** Gap between the tag plate and the badge. */
	private static final int TAG_GAP = 2;
	/** Shade (ring and pointer) = colour times this, as in the design. */
	private static final float SHADE_FACTOR = 0.55f;
	/** Dark initial on a light badge = colour times this. */
	private static final float DARK_TEXT_FACTOR = 0.3f;
	/** Badges with a relative luminance above this get dark text instead of white text with a shadow. */
	static final double LIGHT_LUMINANCE = 0.42;
	/** Badges with a relative luminance below this get a lighter (not darker) shade, so the ring stays visible. */
	static final double VERY_DARK_LUMINANCE = 0.03;
	/** Vanilla's drop shadow colour for white text (white times 0.25). */
	private static final int TEXT_SHADOW = 0xFF3F3F3F;

	/** Default tag background opacity, as in the design (vanilla nametags use 0.25). */
	private static final float LABEL_BACKGROUND_OPACITY = 0.4f;
	/** Points closer than this (view-space w) to the camera plane are treated as behind the camera. */
	private static final float MIN_CLIP_W = 0.05f;
	/** The pin nearest the screen centre within this many GUI pixels always shows its tag. */
	private static final float FOCUS_RADIUS = 40.0f;
	/** Extra spacing kept between tag rectangles, in GUI pixels. */
	private static final int LABEL_MARGIN = 1;

	/** Perspective reference: pins at or nearer than this many blocks are drawn at full size; at twice it, half. */
	static final double REF_DISTANCE = 16.0;
	/** Fixed floor of the distance scale, so far pins (16 px icon) stay recognisable and never vanish. */
	static final float MIN_SCALE = 0.35f;
	/** Tags never draw smaller than this (effective scale), however small the pin gets. */
	static final float MIN_LABEL_SCALE = 0.5f;
	/** Time a tag takes to fade fully in or out, in milliseconds. */
	static final float LABEL_FADE_MILLIS = 175.0f;
	/** Time a group crossfade (members to group pin or back) takes, in milliseconds. */
	static final float COMBINE_FADE_MILLIS = 200.0f;
	/** Scale of a pin at the very start/end of a crossfade (it grows to full size as it fades in). */
	private static final float FADE_MIN_SCALE = 0.8f;
	/** Longest frame time a fade advances by (so a hitch doesn't skip a fade). */
	private static final float MAX_FRAME_MILLIS = 100.0f;

	/** Tag opacity (0..1] per waypoint or group id, for markers on screen in the last frame. */
	private final Map<UUID, Float> labelAlpha = new HashMap<>();
	/** Per group id: how far it is toward combined (0 = members shown, 1 = group pin shown). */
	private final Map<UUID, Float> combineAlpha = new HashMap<>();
	private long lastFrameNanos;

	private final Map<Identifier, ItemStack> logoStacks = new HashMap<>();
	private final Matrix4f viewProjection = new Matrix4f();
	private final Vector4f clip = new Vector4f();

	/**
	 * What a pin shows: a waypoint, or a combined group ({@code memberCount > 0}, shown in its tag). {@code id} keys the
	 * tag fade state (waypoint and group ids are distinct UUIDs).
	 */
	record PinSource(UUID id, String name, int color, LogoType logoType, Identifier logoBlock, boolean showText, int memberCount) {
		static PinSource of(Waypoint w) {
			return new PinSource(w.id, w.name, w.color, w.logoType, w.logoBlock, w.showText, 0);
		}

		static PinSource of(WaypointGroup g, int visibleMembers) {
			return new PinSource(g.id, g.name, g.color, g.logoType, g.logoBlock, g.showText, visibleMembers);
		}
	}

	/**
	 * A projected pin. {@code x}/{@code y} is the pin tip (the anchor, snapped to a framebuffer pixel);
	 * {@code logoScale} and {@code labelScale} are the (continuous) pose scales of the pin and of the tag; {@code alpha}
	 * is the pin's opacity (1 except during a group crossfade).
	 */
	private record Marker(PinSource source, float x, float y, double distance, float logoScale, float labelScale, float alpha) {
		/** Y of the badge centre, used for the crosshair focus. */
		float badgeCentreY() {
			return y + (BADGE_TOP + BADGE_SIZE / 2.0f) * logoScale;
		}
	}

	/** Badge fill, shade (ring and pointer) and text (initial) colours for a waypoint colour, all opaque. */
	record PinColors(int fill, int shade, int text, boolean textShadow) {
		static PinColors of(int rgb) {
			int fill = ARGB.opaque(rgb);
			double luminance = luminance(fill);
			int shade = luminance < VERY_DARK_LUMINANCE
					? ARGB.srgbLerp(0.35f, fill, 0xFFFFFFFF)
					: scale(fill, SHADE_FACTOR);
			boolean light = luminance > LIGHT_LUMINANCE;
			int text = light ? scale(fill, DARK_TEXT_FACTOR) : 0xFFFFFFFF;
			return new PinColors(fill, ARGB.opaque(shade), ARGB.opaque(text), !light);
		}

		/** The same colours at {@code alpha} opacity (0..1), for a pin fading during a group crossfade. */
		PinColors withAlpha(float alpha) {
			if (alpha >= 1.0f) return this;
			return new PinColors(ARGB.multiplyAlpha(fill, alpha), ARGB.multiplyAlpha(shade, alpha),
					ARGB.multiplyAlpha(text, alpha), textShadow);
		}

		/** Each channel times {@code f}, rounded (as the design's shade()). */
		private static int scale(int rgb, float f) {
			return ARGB.color(255, Math.round(ARGB.red(rgb) * f), Math.round(ARGB.green(rgb) * f), Math.round(ARGB.blue(rgb) * f));
		}

		/** WCAG relative luminance (0 black .. 1 white) of an sRGB colour. */
		static double luminance(int rgb) {
			return 0.2126 * ARGB.srgbToLinearChannel(ARGB.red(rgb))
					+ 0.7152 * ARGB.srgbToLinearChannel(ARGB.green(rgb))
					+ 0.0722 * ARGB.srgbToLinearChannel(ARGB.blue(rgb));
		}
	}

	/** Height of a waypoint's pin anchor: over the top face of its highest block, higher for a beacon. */
	static double anchorY(Waypoint waypoint) {
		return waypoint.topY() + 1.0 + (waypoint.isBeacon() ? BEACON_LOGO_HEIGHT : HIGHLIGHT_LOGO_HEIGHT);
	}

	/**
	 * Marker size factor (before the global icon size), true perspective: {@code REF_DISTANCE / distance}, clamped to
	 * [{@link #MIN_SCALE}, 1] (1 up to 16 blocks, 0.5 at 32, 0.25 would be 64 but the floor holds it at 0.35 from
	 * about 46 blocks). Always 1 when shrinking is off.
	 */
	static float distanceScale(double distance, boolean shrink) {
		if (!shrink || distance <= REF_DISTANCE) return 1.0f;
		return (float) Math.max(MIN_SCALE, REF_DISTANCE / distance);
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
			combineAlpha.clear();
			return;
		}

		WaypyConfig config = WaypyConfig.get();
		float iconSize = config.iconSize / 100.0f;

		computeViewProjection(renderState, camera);
		int guiWidth = graphics.guiWidth();
		int guiHeight = graphics.guiHeight();
		int guiScale = Math.max(1, renderState.windowRenderState.guiScale);
		Vec3 cam = camera.pos;

		// Groups: each one's crossfade moves toward combined (1) or separate (0) by frame time; a group seen for the
		// first time starts at its target (no fade on joining a world or opening the menu).
		List<GroupCombiner.View> views = GroupCombiner.compute(minecraft.level.dimension(), cam);
		float combineStep = frameMillis / COMBINE_FADE_MILLIS;
		Map<Waypoint, Float> memberAlpha = new IdentityHashMap<>();
		Map<GroupCombiner.View, Float> groupAlpha = new IdentityHashMap<>();
		Set<UUID> groupIds = new HashSet<>();
		for (GroupCombiner.View view : views) {
			UUID id = view.group().id;
			groupIds.add(id);
			float target = view.combined() ? 1.0f : 0.0f;
			Float previous = combineAlpha.get(id);
			float alpha = previous == null ? target
					: target > previous ? Math.min(target, previous + combineStep) : Math.max(target, previous - combineStep);
			combineAlpha.put(id, alpha);
			if (alpha > 0.0f) groupAlpha.put(view, alpha);
			for (Waypoint member : view.members()) memberAlpha.put(member, 1.0f - alpha);
		}
		combineAlpha.keySet().retainAll(groupIds);

		List<Marker> markers = new ArrayList<>(waypoints.size() + groupAlpha.size());
		for (Waypoint waypoint : waypoints) {
			float alpha = memberAlpha.getOrDefault(waypoint, 1.0f);
			if (alpha <= 0.0f) continue; // a member of a fully combined group
			// Above the clump: x/z of the centroid, y over the top face of its highest block.
			Vec3 centre = waypoint.centre();
			Marker marker = project(PinSource.of(waypoint), centre.x, anchorY(waypoint), centre.z, centre.distanceTo(cam),
					alpha, cam, config, iconSize, guiWidth, guiHeight, guiScale);
			if (marker != null) markers.add(marker);
		}
		for (Map.Entry<GroupCombiner.View, Float> entry : groupAlpha.entrySet()) {
			GroupCombiner.View view = entry.getKey();
			// Above the group: x/z of its centre, y over its highest member (each member's own height rule).
			Marker marker = project(PinSource.of(view.group(), view.members().size()), view.centre().x, view.anchorY(),
					view.centre().z, view.distance(), entry.getValue(), cam, config, iconSize, guiWidth, guiHeight, guiScale);
			if (marker != null) markers.add(marker);
		}
		if (markers.isEmpty()) {
			labelAlpha.clear();
			return;
		}

		Font font = minecraft.font;
		int labelBackground = minecraft.options.getBackgroundColor(LABEL_BACKGROUND_OPACITY);
		LabelMode labelMode = config.labels;

		// The text pin whose badge is nearest the crosshair (if close enough) always shows its tag, on top of
		// everything. Waypoints with their text off take no part in any tag decision.
		float centerX = guiWidth / 2.0f;
		float centerY = guiHeight / 2.0f;
		Marker focused = null;
		float focusedDistSq = FOCUS_RADIUS * FOCUS_RADIUS;
		for (Marker marker : markers) {
			if (!marker.source().showText()) continue;
			float ddx = marker.x() - centerX;
			float ddy = marker.badgeCentreY() - centerY;
			float distSq = ddx * ddx + ddy * ddy;
			if (distSq <= focusedDistSq) {
				focused = marker;
				focusedDistSq = distSq;
			}
		}

		// Tag decluttering, nearest first: a tag whose (scaled) rectangle overlaps one already accepted is
		// hidden, so on a collision the waypoint farther from the camera loses. The focused tag is placed first.
		markers.sort(Comparator.comparingDouble(Marker::distance));
		Map<Marker, Label> shown = new IdentityHashMap<>();
		if (labelMode != LabelMode.OFF && focused != null) {
			shown.put(focused, label(font, focused, config.showDistance));
		}
		if (labelMode == LabelMode.ALWAYS) {
			// Obstacles: accepted tags, and the pins of nearer markers (which paint over farther tags).
			List<Rect> obstacles = new ArrayList<>();
			for (Label label : shown.values()) obstacles.add(label.rect());
			for (Marker marker : markers) {
				if (marker != focused && marker.source().showText()) {
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
				obstacles.add(pinRect(marker));
			}
		}

		// Fades: each tag's opacity moves toward 1 (shown) or 0 (hidden) by frame time, so tags never pop.
		// State is kept only for markers on screen this frame (deleted or hidden waypoints drop out at once).
		float step = frameMillis / LABEL_FADE_MILLIS;
		Map<Marker, Float> alphas = new IdentityHashMap<>();
		Set<UUID> onScreen = new HashSet<>();
		for (Marker marker : markers) {
			UUID id = marker.source().id();
			onScreen.add(id);
			float previous = labelAlpha.getOrDefault(id, 0.0f);
			float target = shown.containsKey(marker) ? 1.0f : 0.0f;
			float alpha = target > previous ? Math.min(target, previous + step) : Math.max(target, previous - step);
			if (alpha <= 0.0f) {
				labelAlpha.remove(id);
				continue;
			}
			labelAlpha.put(id, alpha);
			alphas.put(marker, alpha * marker.alpha()); // a tag fades with its pin during a group crossfade
		}
		labelAlpha.keySet().retainAll(onScreen);

		// Paint far to near, so nearer pins (and tags) draw on top; the focused tag goes last.
		for (int i = markers.size() - 1; i >= 0; i--) {
			Marker marker = markers.get(i);
			drawPin(graphics, font, marker);
			Float alpha = alphas.get(marker);
			if (alpha != null && marker != focused) {
				Label label = shown.get(marker);
				// A tag fading out is no longer placed, but still drawn where it would be.
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

	/**
	 * Projects a pin anchored at world ({@code x}, {@code y}, {@code z}); null if it is behind the camera or far off
	 * screen. During a crossfade ({@code alpha < 1}) the pin is also scaled down slightly toward
	 * {@link #FADE_MIN_SCALE}.
	 */
	private @Nullable Marker project(PinSource source, double x, double y, double z, double distance,
			float alpha, Vec3 cam, WaypyConfig config, float iconSize, int guiWidth, int guiHeight, int guiScale) {
		clip.set((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z), 1.0f);
		viewProjection.transform(clip);
		if (clip.w < MIN_CLIP_W) return null; // behind the camera
		float ndcX = clip.x / clip.w;
		float ndcY = clip.y / clip.w;
		// Generous bounds so a pin partly on screen is still drawn.
		if (ndcX < -1.5f || ndcX > 1.5f || ndcY < -1.5f || ndcY > 1.5f) return null;
		// Only the anchor (the pin tip) is snapped to a framebuffer pixel; pin and tag are laid out from it with
		// whole unscaled offsets, so they never jitter against each other while the scale changes.
		float sx = snap((ndcX * 0.5f + 0.5f) * guiWidth, guiScale);
		float sy = snap((0.5f - ndcY * 0.5f) * guiHeight, guiScale);
		// Continuous scales (no quantisation), so pins shrink smoothly while walking away.
		float scale = distanceScale(distance, config.shrinkWithDistance) * iconSize;
		float pinScale = alpha >= 1.0f ? scale : scale * Mth.lerp(alpha, FADE_MIN_SCALE, 1.0f);
		return new Marker(source, sx, sy, distance, pinScale, Math.max(MIN_LABEL_SCALE, scale), alpha);
	}

	/** Rounds a GUI coordinate to the nearest framebuffer pixel. */
	private static float snap(float gui, int guiScale) {
		return Math.round(gui * guiScale) / (float) guiScale;
	}

	/**
	 * A name tag: its text origin ({@code x}, {@code y}), pose scale, unscaled text width and its on-screen
	 * rectangle (scaled plate plus margin), in GUI coordinates.
	 */
	private record Label(Component text, float x, float y, float scale, int width, Rect rect) {
	}

	/** An axis-aligned rectangle in GUI coordinates. */
	private record Rect(float left, float top, float right, float bottom) {
		boolean overlaps(Rect o) {
			return left < o.right && o.left < right && top < o.bottom && o.top < bottom;
		}
	}

	/** The on-screen rectangle of a marker's pin (badge and pointer), as drawn by {@link #drawPin}. */
	private static Rect pinRect(Marker marker) {
		float s = marker.logoScale();
		return new Rect(marker.x() + BADGE_LEFT * s, marker.y() + BADGE_TOP * s,
				marker.x() - BADGE_LEFT * s, marker.y());
	}

	private static Label label(Font font, Marker marker, boolean showDistance) {
		PinSource source = marker.source();
		String distance = String.valueOf((int) Math.floor(marker.distance()));
		Component text;
		if (source.memberCount() > 0) { // a group: "Name (3) (124m)"
			text = showDistance
					? Component.translatable("waypy.render.group_label", source.name(), source.memberCount(), distance)
					: Component.translatable("waypy.render.group_label.no_distance", source.name(), source.memberCount());
		} else {
			text = showDistance
					? Component.translatable("waypy.render.label", source.name(), distance)
					: Component.literal(source.name());
		}
		int width = font.width(text);
		float s = marker.labelScale();
		// The plate spans x-1 .. x+width (the font's trailing 1 px spacing is the right padding) and y-1 .. y+8;
		// it is centred on the tip with a whole-pixel offset and ends TAG_GAP above the (pin-scaled) badge.
		float plateBottom = marker.y() + (BADGE_TOP - TAG_GAP) * marker.logoScale();
		float x = marker.x() + (1 - (width + 1) / 2) * s;
		float y = plateBottom - (font.lineHeight - 1) * s;
		return new Label(text, x, y, s, width, new Rect(
				x - s - LABEL_MARGIN, y - s - LABEL_MARGIN,
				x + width * s + LABEL_MARGIN, plateBottom + LABEL_MARGIN));
	}

	/**
	 * Draws the pin (badge, icon or initial, pointer), scaled about its tip. Opaque except during a group crossfade;
	 * the item icon can't be drawn translucent, so it is shown only while the pin is at least half opaque.
	 */
	private void drawPin(GuiGraphicsExtractor graphics, Font font, Marker marker) {
		PinSource source = marker.source();
		PinColors colors = PinColors.of(source.color()).withAlpha(marker.alpha());
		float s = marker.logoScale();
		graphics.pose().pushMatrix();
		graphics.pose().translate(marker.x(), marker.y());
		graphics.pose().scale(s, s);

		int l = BADGE_LEFT, t = BADGE_TOP, r = -BADGE_LEFT, b = BADGE_TOP + BADGE_SIZE;
		// Badge: fill, then a 1 px shade ring; the four corner pixels stay empty.
		graphics.fill(l + 1, t + 1, r - 1, b - 1, colors.fill());
		graphics.fill(l + 1, t, r - 1, t + 1, colors.shade());
		graphics.fill(l + 1, b - 1, r - 1, b, colors.shade());
		graphics.fill(l, t + 1, l + 1, b - 1, colors.shade());
		graphics.fill(r - 1, t + 1, r, b - 1, colors.shade());
		// Stepped pointer: rows 6, 4 and 2 px wide; the last row ends at the tip (y 0).
		for (int row = 0; row < POINTER_HEIGHT; row++) {
			int half = POINTER_HEIGHT - row;
			graphics.fill(-half, b + row, half, b + row + 1, colors.shade());
		}

		if (source.logoType() == LogoType.BLOCK) {
			if (marker.alpha() >= 0.5f) graphics.item(logoStack(source.logoBlock()), -8, t + 2);
		} else {
			drawInitial(graphics, font, source.name(), colors, t);
		}
		graphics.pose().popMatrix();
	}

	/** The name's initial (upper case), centred in the badge whose top is {@code top}; a 2x2 dot for no name. */
	private static void drawInitial(GuiGraphicsExtractor graphics, Font font, String name, PinColors colors, int top) {
		String trimmed = name.strip();
		int centreY = top + BADGE_SIZE / 2;
		if (trimmed.isEmpty()) {
			if (colors.textShadow()) graphics.fill(0, centreY, 2, centreY + 2, ARGB.color(ARGB.alpha(colors.fill()), TEXT_SHADOW));
			graphics.fill(-1, centreY - 1, 1, centreY + 1, colors.text());
			return;
		}
		String initial = new String(Character.toChars(Character.toUpperCase(trimmed.codePointAt(0))));
		// font.width includes the 1 px spacing column the drop shadow uses, so glyph + shadow is centred;
		// the 7 px glyph plus its 1 px shadow row (8 rows) are centred vertically.
		int x = -font.width(initial) / 2;
		graphics.text(font, initial, x, centreY - 4, colors.text(), colors.textShadow());
	}

	/** Draws a tag at {@code alpha} opacity (0..1, from its fade). */
	private static void drawLabel(GuiGraphicsExtractor graphics, Font font, Label label, int labelBackground, float alpha) {
		int textColor = ARGB.color(Math.round(alpha * 255.0f), 0xFFFFFF);
		if (ARGB.alpha(textColor) == 0) return;
		graphics.pose().pushMatrix();
		graphics.pose().translate(label.x(), label.y());
		graphics.pose().scale(label.scale(), label.scale());
		int background = ARGB.multiplyAlpha(labelBackground, alpha);
		if (ARGB.alpha(background) != 0) {
			graphics.fill(-1, -1, label.width(), font.lineHeight - 1, background);
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
