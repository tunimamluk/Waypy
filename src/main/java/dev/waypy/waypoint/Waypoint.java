package dev.waypy.waypoint;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A single waypoint. Plain mutable data holder: all state lives in <b>public fields</b>
 * (no getters/setters). After mutating a waypoint, call {@link WaypointManager#markDirty()}
 * so the change is persisted. Use {@link #setName(String)} (or {@link #MAX_NAME_LENGTH}) to respect the length limit.
 */
public final class Waypoint {
	public static final int MAX_NAME_LENGTH = 32;

	public UUID id;
	/** Display name, max {@value #MAX_NAME_LENGTH} chars. */
	public String name;
	public BlockPos pos;
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

	public Waypoint(UUID id, String name, BlockPos pos, ResourceKey<Level> dimension, int color, Identifier logoBlock) {
		this.id = Objects.requireNonNull(id);
		this.pos = Objects.requireNonNull(pos);
		this.dimension = Objects.requireNonNull(dimension);
		this.color = color & 0xFFFFFF;
		this.logoBlock = Objects.requireNonNull(logoBlock);
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
}
