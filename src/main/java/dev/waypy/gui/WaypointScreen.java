package dev.waypy.gui;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.waypy.waypoint.Waypoint;

/** Minimal placeholder for the Waypy menu; to be replaced by the full editor. */
public class WaypointScreen extends Screen {
	private final @Nullable Waypoint initialSelection;

	public WaypointScreen(@Nullable Waypoint initialSelection) {
		super(Component.translatable("waypy.screen.title"));
		this.initialSelection = initialSelection;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(width / 2 - 100, height - 28, 200, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
	}
}
