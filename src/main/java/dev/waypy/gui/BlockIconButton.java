package dev.waypy.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * A vanilla button showing the logo block's item icon followed by its name; it opens the {@link BlockPickerScreen}.
 * Shared by the Waypy menu and the preset form.
 */
final class BlockIconButton extends Button {
	private final ItemStack stack;

	BlockIconButton(int width, Identifier block, OnPress onPress) {
		this(width, WaypointScreen.stackFor(block), onPress);
	}

	private BlockIconButton(int width, ItemStack stack, OnPress onPress) {
		super(0, 0, width, DEFAULT_HEIGHT, stack.getHoverName(), onPress, DEFAULT_NARRATION);
		this.stack = stack;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		extractDefaultSprite(graphics);
		graphics.fakeItem(stack, getX() + 3, getY() + (getHeight() - 16) / 2);
		graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE)
				.acceptScrollingWithDefaultCenter(getMessage(), getX() + 22, getRight() - 4, getY(), getBottom());
	}
}
