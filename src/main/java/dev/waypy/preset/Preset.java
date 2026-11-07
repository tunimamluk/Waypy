package dev.waypy.preset;

import java.util.Objects;

import net.minecraft.resources.Identifier;

import dev.waypy.waypoint.LogoType;
import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointManager;
import dev.waypy.waypoint.WaypointStyle;

/**
 * A named set of waypoint display settings that can be applied to any waypoint (the Presets popup, default key H).
 * Holds exactly the fields a preset copies: colour, style, logo type and block, opacity, through-blocks and text. A
 * preset's number is its position in {@link PresetManager#all()} plus one; it is not stored in the preset.
 *
 * <p>Mutable on purpose, like {@link Waypoint}: the inline form edits a {@link #copy()} and the manager stores the
 * edited copy on Save.
 */
public final class Preset {
	public static final int MAX_NAME_LENGTH = Waypoint.MAX_NAME_LENGTH;

	/** Display name, max {@value #MAX_NAME_LENGTH} chars. */
	public String name;
	/** 0xRRGGBB. */
	public int color;
	public WaypointStyle style = WaypointStyle.BLOCK_HIGHLIGHT;
	public LogoType logoType = LogoType.BLOCK;
	public Identifier logoBlock = WaypointManager.DEFAULT_LOGO;
	/** 0..1, Block Selection only. */
	public float highlightOpacity = 0.4f;
	/** Block Selection only: render without depth test. */
	public boolean throughBlocks = false;
	public boolean showText = false;

	public Preset(String name, int color) {
		setName(name);
		this.color = color & 0xFFFFFF;
	}

	/** Sets the name, trimmed to {@value #MAX_NAME_LENGTH} chars ({@code null} becomes empty). */
	public void setName(String name) {
		this.name = name == null ? "" : name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
	}

	/** A preset with {@code waypoint}'s display settings (what {@link #applyTo} copies) under {@code name}. */
	public static Preset from(Waypoint waypoint, String name) {
		Preset p = new Preset(name, waypoint.color);
		p.style = waypoint.style;
		p.logoType = waypoint.logoType;
		p.logoBlock = waypoint.logoBlock;
		p.highlightOpacity = waypoint.highlightOpacity;
		p.throughBlocks = waypoint.throughBlocks;
		p.showText = waypoint.showText;
		return p;
	}

	/**
	 * Copies the display settings onto {@code waypoint}: colour, style, logo type and block, opacity, through-blocks
	 * and text. Never the name, position, blocks, visibility or group. A clump (2+ blocks) can't be a beacon, so a
	 * {@link WaypointStyle#BEACON} preset leaves it at {@link WaypointStyle#BLOCK_HIGHLIGHT}. The caller marks the
	 * manager dirty.
	 */
	public void applyTo(Waypoint waypoint) {
		waypoint.color = color & 0xFFFFFF;
		waypoint.style = waypoint.isClump() && style == WaypointStyle.BEACON ? WaypointStyle.BLOCK_HIGHLIGHT : style;
		waypoint.logoType = logoType;
		waypoint.logoBlock = logoBlock;
		waypoint.highlightOpacity = Math.clamp(highlightOpacity, 0.0f, 1.0f);
		waypoint.throughBlocks = throughBlocks;
		waypoint.showText = showText;
	}

	/** An independent copy (the inline form edits one; Cancel just drops it). */
	public Preset copy() {
		Preset p = new Preset(name, color);
		p.style = style;
		p.logoType = logoType;
		p.logoBlock = logoBlock;
		p.highlightOpacity = highlightOpacity;
		p.throughBlocks = throughBlocks;
		p.showText = showText;
		return p;
	}

	/** True if every field (name included) is equal. */
	public boolean sameAs(Preset other) {
		return name.equals(other.name) && color == other.color && style == other.style && logoType == other.logoType
				&& Objects.equals(logoBlock, other.logoBlock) && Float.compare(highlightOpacity, other.highlightOpacity) == 0
				&& throughBlocks == other.throughBlocks && showText == other.showText;
	}

	@Override
	public String toString() {
		return "Preset[\"" + name + "\" #" + String.format("%06X", color) + " " + style + " " + logoType + " " + logoBlock
				+ " opacity=" + highlightOpacity + " through=" + throughBlocks + " text=" + showText + "]";
	}
}
