package dev.waypy.gui;

import java.util.List;

import com.mojang.serialization.Codec;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import dev.waypy.config.LabelMode;
import dev.waypy.config.WaypyConfig;

/**
 * Waypy settings, laid out exactly like vanilla's option screens: an {@link OptionsSubScreen} (title header,
 * {@code OptionsList} with 310-px rows of one big or two 150-px widgets, Done footer) filled with vanilla
 * {@link OptionInstance}s that write straight into {@link WaypyConfig}. The config is saved when the screen closes.
 */
public class WaypySettingsScreen extends OptionsSubScreen {
	private static final Component TITLE = Component.translatable("waypy.settings.title");

	private final WaypyConfig config = WaypyConfig.get();
	private final OptionInstance<Integer> renderDistance;
	private final OptionInstance<Integer> iconSize;
	private final OptionInstance<Boolean> shrink;
	private final OptionInstance<LabelMode> labels;
	private final OptionInstance<Boolean> showDistance;
	private final OptionInstance<Integer> groupCombineDistance;

	public WaypySettingsScreen(@Nullable Screen lastScreen) {
		this(lastScreen, Minecraft.getInstance().options);
	}

	@SuppressWarnings("DataFlowIssue") // OptionsSubScreen#onClose handles a null last screen (closes to the game)
	public WaypySettingsScreen(@Nullable Screen lastScreen, Options options) {
		super(lastScreen, options, TITLE);

		renderDistance = new OptionInstance<>("waypy.settings.render_distance",
				OptionInstance.cachedConstantTooltip(Component.translatable("waypy.settings.render_distance.tooltip")),
				(caption, value) -> Options.genericValueLabel(caption, value == WaypyConfig.UNLIMITED
						? Component.translatable("waypy.settings.render_distance.unlimited")
						: Component.translatable("waypy.settings.render_distance.blocks", value)),
				new OptionInstance.SliderableEnum<>(WaypyConfig.RENDER_DISTANCE_STEPS, Codec.INT),
				config.waypointRenderDistance,
				value -> config.waypointRenderDistance = value);

		iconSize = percentOption("waypy.settings.icon_size", WaypyConfig.MIN_ICON_SIZE, WaypyConfig.MAX_ICON_SIZE,
				config.iconSize, value -> config.iconSize = value);

		shrink = OptionInstance.createBoolean("waypy.settings.shrink",
				OptionInstance.cachedConstantTooltip(Component.translatable("waypy.settings.shrink.tooltip")),
				config.shrinkWithDistance,
				value -> config.shrinkWithDistance = value);

		LabelMode[] modes = LabelMode.values();
		labels = new OptionInstance<>("waypy.settings.labels",
				mode -> Tooltip.create(Component.translatable(mode.translationKey() + ".tooltip")),
				// A cycle button prefixes the caption itself ("Labels: Always").
				(caption, mode) -> Component.translatable(mode.translationKey()),
				new OptionInstance.Enum<>(List.of(modes), Codec.INT.xmap(i -> modes[i], LabelMode::ordinal)),
				config.labels,
				value -> config.labels = value);

		showDistance = OptionInstance.createBoolean("waypy.settings.show_distance",
				OptionInstance.cachedConstantTooltip(Component.translatable("waypy.settings.show_distance.tooltip")),
				config.showDistance,
				value -> config.showDistance = value);

		groupCombineDistance = new OptionInstance<>("waypy.settings.group_combine_distance",
				OptionInstance.cachedConstantTooltip(Component.translatable("waypy.settings.group_combine_distance.tooltip")),
				(caption, value) -> Options.genericValueLabel(caption, Component.translatable("waypy.settings.render_distance.blocks", value)),
				new OptionInstance.SliderableEnum<>(WaypyConfig.COMBINE_DISTANCE_STEPS, Codec.INT),
				config.groupCombineDistance,
				value -> config.groupCombineDistance = value);
	}

	/** A 5%-step percentage slider over {@code [min, max]}. */
	private static OptionInstance<Integer> percentOption(String key, int min, int max, int initial,
			OptionInstance.ValueUpdateListener<Integer> onChange) {
		int step = WaypyConfig.PERCENT_STEP;
		return new OptionInstance<>(key,
				OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
				(caption, value) -> Component.translatable("options.percent_value", caption, value),
				new OptionInstance.IntRange(min / step, max / step).xmap(i -> i * step, value -> value / step, true),
				Codec.INT,
				initial,
				onChange);
	}

	@Override
	protected void addOptions() {
		if (list == null) return;
		list.addBig(renderDistance);
		list.addSmall(iconSize, shrink);
		list.addSmall(labels, showDistance);
		list.addBig(groupCombineDistance);
	}

	@Override
	public void removed() {
		// Not super: that saves vanilla's options.txt, which this screen doesn't change.
		WaypyConfig.save();
	}
}
