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
 * so the change is persisted. Use {@link #setName(String)} / {@link #setLogoText(String)}
 * (or the {@code MAX_*} constants) to respect the length limits.
 */
public final class Waypoint {
	public static final int MAX_NAME_LENGTH = 32;
	public static final int MAX_LOGO_TEXT_LENGTH = 3;

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
	/** Item/block id used when {@link #logoType} is {@link LogoType#BLOCK}. */
	public Identifier logoBlock;
	/** Max {@value #MAX_LOGO_TEXT_LENGTH} chars, used when {@link #logoType} is {@link LogoType#TEXT}. */
	public String logoText = "";
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

	/** Sets the logo text, trimmed to {@value #MAX_LOGO_TEXT_LENGTH} chars. */
	public void setLogoText(String text) {
		this.logoText = clamp(text, MAX_LOGO_TEXT_LENGTH);
	}

	static String clamp(String s, int max) {
		if (s == null) return "";
		return s.length() > max ? s.substring(0, max) : s;
	}
}
