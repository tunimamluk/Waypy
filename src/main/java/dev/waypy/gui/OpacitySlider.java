package dev.waypy.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Opacity 0–100% for the Block Selection style ("Opacity: 40%"). Shared by the Waypy menu and the preset form. */
final class OpacitySlider extends AbstractSliderButton {
	private static final Component OPACITY_LABEL = Component.translatable("waypy.gui.opacity");

	/** Receives each new value (0..1). */
	interface Listener {
		void changed(float opacity);
	}

	private final Listener listener;

	OpacitySlider(int width, float opacity, Listener listener) {
		super(0, 0, width, Button.DEFAULT_HEIGHT, CommonComponents.EMPTY, Math.clamp(opacity, 0.0F, 1.0F));
		this.listener = listener;
		updateMessage();
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.translatable("options.percent_value", OPACITY_LABEL, (int) Math.round(value * 100.0)));
	}

	@Override
	protected void applyValue() {
		listener.changed((float) value);
	}
}
