package dev.waypy.render;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.resources.Identifier;

import dev.waypy.WaypyClient;
import dev.waypy.config.WaypyConfig;

/**
 * Entry point of waypoint rendering.
 *
 * <ul>
 *   <li>World geometry (beacon beams, block highlights): {@link WorldWaypointRenderer}, hooked into
 *       {@link LevelRenderEvents#COLLECT_SUBMITS}.</li>
 *   <li>Logos and name/distance labels: {@link WaypointHud}, a HUD element drawn right after vanilla's
 *       camera overlays (so below the crosshair, hotbar and chat).</li>
 * </ul>
 */
public final class WaypointRenderer {
	public static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(WaypyClient.MOD_ID, "waypoints");

	private WaypointRenderer() {
	}

	/**
	 * False while "hide all" is on ({@link WaypyConfig#hideAll}): both render entry points
	 * ({@link WorldWaypointRenderer#collectSubmits} and {@link WaypointHud#extractRenderState}) return before drawing
	 * anything, groups included.
	 */
	public static boolean enabled() {
		return !WaypyConfig.get().hideAll;
	}

	public static void init() {
		WaypyRenderTypes.init();
		LevelRenderEvents.COLLECT_SUBMITS.register(WorldWaypointRenderer::collectSubmits);
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, HUD_ID, new WaypointHud());
	}
}
