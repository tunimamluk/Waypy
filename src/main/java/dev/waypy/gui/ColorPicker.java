package dev.waypy.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;

/**
 * The colour editor shared by the Waypy menu (waypoints and groups) and the preset form: the 16 dye swatches (a
 * grid of 8 × 2) and a hex {@code EditBox} ({@code #RRGGBB}). The current colour's swatch has a white border.
 * {@code set} is called with every new colour: a swatch click, or a valid hex value that differs from the current
 * one; invalid hex input turns the box's text red and isn't applied.
 */
final class ColorPicker {
	static final int SWATCH_SIZE = 11;
	static final int SWATCH_GAP = 2;
	static final int SWATCH_COLUMNS = 8;
	static final int GRID_WIDTH = SWATCH_COLUMNS * SWATCH_SIZE + (SWATCH_COLUMNS - 1) * SWATCH_GAP;
	/** Gap between the swatch grid and the hex box beside it. */
	static final int HEX_GAP = 8;
	/** Narrowest hex box that still goes beside the grid; a narrower one goes under it. */
	static final int MIN_INLINE_HEX_WIDTH = 60;
	private static final Component HEX_LABEL = Component.translatable("waypy.gui.color.hex");
	private static final int INVALID_TEXT_COLOR = 0xFFFF5555;
	private static final int LIGHT_GRAY = 0xFFA0A0A0;

	private final List<ColorSwatch> swatches = new ArrayList<>();
	private final EditBox hexBox;

	/** @param hexWidth width of the hex box (see {@link #hexWidth}) */
	ColorPicker(Font font, int hexWidth, IntSupplier get, IntConsumer set) {
		EditBox hex = new EditBox(font, hexWidth, Button.DEFAULT_HEIGHT, HEX_LABEL);
		hexBox = hex;
		for (DyeColor dye : DyeColor.values()) {
			swatches.add(new ColorSwatch(dye, get, color -> {
				set.accept(color);
				hex.setValue(formatHex(color));
			}));
		}
		hex.setMaxLength(7);
		hex.setHint(Component.literal("#RRGGBB").withStyle(ChatFormatting.DARK_GRAY));
		hex.setValue(formatHex(get.getAsInt()));
		hex.setResponder(value -> {
			Integer color = parseHex(value);
			if (color == null) {
				hex.setTextColor(INVALID_TEXT_COLOR);
				return;
			}
			hex.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
			if (get.getAsInt() != color) set.accept(color);
		});
	}

	/** True if a hex box beside the grid would be at least {@link #MIN_INLINE_HEX_WIDTH} wide in {@code width}. */
	static boolean hexInline(int width) {
		return width - GRID_WIDTH - HEX_GAP >= MIN_INLINE_HEX_WIDTH;
	}

	/** Width of the hex box for a picker {@code width} wide: beside the grid if it fits, else full width under it. */
	static int hexWidth(int width) {
		return hexInline(width) ? width - GRID_WIDTH - HEX_GAP : width;
	}

	EditBox hexBox() {
		return hexBox;
	}

	List<ColorSwatch> swatches() {
		return List.copyOf(swatches);
	}

	/** A new grid layout holding the swatches (8 per row). */
	GridLayout grid() {
		GridLayout grid = new GridLayout().spacing(SWATCH_GAP);
		for (int i = 0; i < swatches.size(); i++) {
			grid.addChild(swatches.get(i), i / SWATCH_COLUMNS, i % SWATCH_COLUMNS);
		}
		return grid;
	}

	static String formatHex(int color) {
		return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
	}

	/** Parses {@code #RRGGBB} or {@code RRGGBB}; null if invalid. */
	static @Nullable Integer parseHex(String text) {
		String s = text.trim();
		if (s.startsWith("#")) s = s.substring(1);
		if (s.length() != 6) return null;
		for (int i = 0; i < 6; i++) {
			if (Character.digit(s.charAt(i), 16) < 0) return null;
		}
		return Integer.parseInt(s, 16);
	}

	/** One of the 16 dye-colour squares. The current colour gets a white border, a hovered or focused one light grey. */
	static final class ColorSwatch extends AbstractButton {
		private final int color;
		private final IntSupplier get;
		private final IntConsumer onPick;

		ColorSwatch(DyeColor dye, IntSupplier get, IntConsumer onPick) {
			super(0, 0, SWATCH_SIZE, SWATCH_SIZE, Component.translatable("color.minecraft." + dye.getName()));
			this.color = dye.getTextureDiffuseColor() & 0xFFFFFF;
			this.get = get;
			this.onPick = onPick;
			setTooltip(Tooltip.create(getMessage()));
		}

		/** The swatch's colour (0xRRGGBB). */
		int color() {
			return color;
		}

		@Override
		public void onPress(InputWithModifiers input) {
			onPick.accept(color);
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			boolean current = (get.getAsInt() & 0xFFFFFF) == color;
			int border = current ? 0xFFFFFFFF : isHoveredOrFocused() ? LIGHT_GRAY : 0xFF000000;
			int x = getX();
			int y = getY();
			graphics.fill(x, y, x + width, y + height, border);
			graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF000000 | color);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}
