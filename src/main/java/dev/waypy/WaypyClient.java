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

	@Override
	public void onInitializeClient() {
		createKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.waypy.create", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY));
		menuKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.waypy.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY));

		WaypyConfig.load();

		WaypointManager manager = WaypointManager.get();
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> manager.onJoin(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> manager.onDisconnect());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> manager.onDisconnect());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			manager.tick();
			while (createKey.consumeClick()) {
				createWaypoint(client);
			}
			while (menuKey.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) {
					client.gui.setScreen(new WaypointScreen(manager.selected().orElse(null)));
				}
			}
		});

		WaypointRenderer.init();
	}

	/** N: waypoint at the block under the player; Shift+N: at the block the camera looks at. */
	private static void createWaypoint(Minecraft client) {
		createWaypoint(client, client.hasShiftDown());
	}

	/**
	 * Creates a waypoint at the block the player stands on ({@code lookedAt == false}) or at the block the
	 * camera looks at, selects it and shows the action-bar message. Shared by the N key and the menu's
	 * "+ New here" button.
	 *
	 * @return the new waypoint, or null if there is no player/level or no block was found
	 */
	public static @Nullable Waypoint createWaypoint(Minecraft client, boolean lookedAt) {
		if (client.player == null || client.level == null) return null;
		BlockPos pos = lookedAt ? findLookedAtBlock(client) : findBlockBelow(client);
		if (pos == null) return null;

		Item item = client.level.getBlockState(pos).getBlock().asItem();
		Identifier logo = item == Items.AIR ? WaypointManager.DEFAULT_LOGO : BuiltInRegistries.ITEM.getKey(item);

		WaypointManager manager = WaypointManager.get();
		Waypoint waypoint = manager.create(pos, client.level.dimension(), logo);
		manager.select(waypoint.id);
		client.player.sendOverlayMessage(Component.translatable("waypy.message.created", waypoint.name));
		return waypoint;
	}

	/** Block the player stands on, or the first non-air block straight below (down to min build height). */
	public static @Nullable BlockPos findBlockBelow(Minecraft client) {
		BlockPos.MutableBlockPos cursor = client.player.getOnPos().mutable();
		int minY = client.level.getMinY();
		while (cursor.getY() >= minY) {
			if (!client.level.getBlockState(cursor).isAir()) return cursor.immutable();
			cursor.move(Direction.DOWN);
		}
		client.player.sendOverlayMessage(Component.translatable("waypy.message.no_block_below"));
		return null;
	}

	/** Raycast from the camera up to {@link #MAX_RAYCAST_DISTANCE} blocks, ignoring fluids. */
	public static @Nullable BlockPos findLookedAtBlock(Minecraft client) {
		Entity camera = client.getCameraEntity() != null ? client.getCameraEntity() : client.player;
		HitResult hit = camera.pick(MAX_RAYCAST_DISTANCE, 1.0f, false);
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
			client.player.sendOverlayMessage(Component.translatable("waypy.message.no_block"));
			return null;
		}
		return blockHit.getBlockPos();
	}
}
