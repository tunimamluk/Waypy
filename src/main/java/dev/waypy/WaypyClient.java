package dev.waypy;

import com.mojang.blaze3d.platform.InputConstants;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import dev.waypy.config.WaypyConfig;
import dev.waypy.gui.WaypointScreen;
import dev.waypy.render.WaypointRenderer;
import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointManager;

public final class WaypyClient implements ClientModInitializer {
	public static final String MOD_ID = "waypy";
	public static final double MAX_RAYCAST_DISTANCE = 512.0;

	/** Shown in Controls as {@code key.category.waypy.waypy}. */
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, MOD_ID));

	public static KeyMapping createKey;
	public static KeyMapping menuKey;
	/** Hide/Show All Waypoints (default Y): toggles {@link WaypyConfig#hideAll}. */
	public static KeyMapping toggleAllKey;
	/**
	 * Held with {@link #createKey} at the press: use the looked-at block instead of the block stood on. Default Left
	 * Shift. Vanilla keeps every mapping of a key in {@code KeyMapping.MAP} and updates them all, so sharing the key
	 * with Sneak works: both report {@code isDown()}.
	 */
	public static KeyMapping lookModifierKey;
	/** N key behaviour: create, hold to grow a clump, press on a waypoint to delete it. */
	public static final CreateKeyHandler CREATE_KEY_HANDLER = new CreateKeyHandler();

	@Override
	public void onInitializeClient() {
		createKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.waypy.create", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY));
		menuKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.waypy.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY));
		lookModifierKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.waypy.look_modifier", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_SHIFT, CATEGORY));
		toggleAllKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.waypy.toggle_all", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, CATEGORY));

		WaypyConfig.load();

		WaypointManager manager = WaypointManager.get();
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> manager.onJoin(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> manager.onDisconnect());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> manager.onDisconnect());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			manager.tick();
			CREATE_KEY_HANDLER.tick(client);
			while (menuKey.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) {
					client.gui.setScreen(new WaypointScreen(manager.selected().orElse(null)));
				}
			}
			while (toggleAllKey.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) toggleHideAll(client);
			}
		});

		WaypointRenderer.init();
	}

	/**
	 * The Hide/Show All Waypoints key: flips {@link WaypyConfig#hideAll}, saves the config at once (so it survives a
	 * restart) and says so in the action bar, naming the key.
	 *
	 * @return the new value of {@code hideAll}
	 */
	public static boolean toggleHideAll(Minecraft client) {
		WaypyConfig config = WaypyConfig.get();
		config.hideAll = !config.hideAll;
		WaypyConfig.save();
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.translatable(
					config.hideAll ? "waypy.message.all_hidden" : "waypy.message.all_shown",
					toggleAllKey.getTranslatedKeyMessage()));
		}
		return config.hideAll;
	}

	/**
	 * Creates a waypoint at the block the player stands on ({@code lookedAt == false}) or at the block the
	 * camera looks at and selects it. Used by the menu's "+ New here" button.
	 *
	 * @return the new waypoint, or null if there is no player/level or no block was found
	 */
	public static @Nullable Waypoint createWaypoint(Minecraft client, boolean lookedAt) {
		if (client.player == null || client.level == null) return null;
		BlockPos pos = lookedAt ? findLookedAtBlock(client, true) : findBlockBelow(client, true);
		if (pos == null) return null;
		return createWaypointAt(client, pos);
	}

	/**
	 * Creates a waypoint on {@code pos} with the default settings (logo = the block's item) and selects it, silently.
	 * Shared by the N key and the menu's "+ New here".
	 */
	public static @Nullable Waypoint createWaypointAt(Minecraft client, BlockPos pos) {
		if (client.player == null || client.level == null) return null;
		Item item = client.level.getBlockState(pos).getBlock().asItem();
		Identifier logo = item == Items.AIR ? WaypointManager.DEFAULT_LOGO : BuiltInRegistries.ITEM.getKey(item);

		WaypointManager manager = WaypointManager.get();
		Waypoint waypoint = manager.create(pos, client.level.dimension(), logo);
		manager.select(waypoint.id);
		return waypoint;
	}

	/**
	 * Block the player stands on, or the first non-air block straight below (down to min build height).
	 *
	 * @param report show {@code No block below you} when there is none
	 */
	public static @Nullable BlockPos findBlockBelow(Minecraft client, boolean report) {
		BlockPos.MutableBlockPos cursor = client.player.getOnPos().mutable();
		int minY = client.level.getMinY();
		while (cursor.getY() >= minY) {
			if (!client.level.getBlockState(cursor).isAir()) return cursor.immutable();
			cursor.move(Direction.DOWN);
		}
		if (report) client.player.sendOverlayMessage(Component.translatable("waypy.message.no_block_below"));
		return null;
	}

	/**
	 * Raycast from the camera up to {@link #MAX_RAYCAST_DISTANCE} blocks, ignoring fluids.
	 *
	 * @param report show {@code No block in sight} on a miss
	 */
	public static @Nullable BlockPos findLookedAtBlock(Minecraft client, boolean report) {
		Entity camera = client.getCameraEntity() != null ? client.getCameraEntity() : client.player;
		HitResult hit = camera.pick(MAX_RAYCAST_DISTANCE, 1.0f, false);
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
			if (report) client.player.sendOverlayMessage(Component.translatable("waypy.message.no_block"));
			return null;
		}
		return blockHit.getBlockPos();
	}
}
