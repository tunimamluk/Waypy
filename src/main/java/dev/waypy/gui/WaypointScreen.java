package dev.waypy.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.waypy.WaypyClient;
import dev.waypy.config.WaypyConfig;
import dev.waypy.waypoint.LogoType;
import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointGroup;
import dev.waypy.waypoint.WaypointManager;
import dev.waypy.waypoint.WaypointStyle;

/**
 * The Waypy menu: a list on the left (groups with their members indented under them, then the ungrouped waypoints;
 * "+ New here" / "Delete" below it) and an editor on the right. Built only from vanilla widgets and sprites. Changes
 * are applied live to the {@link Waypoint} or {@link WaypointGroup} and the manager is marked dirty; everything is
 * saved when the screen is removed.
 *
 * <p>Selection, as in a file browser: a plain click selects one row (a waypoint, or a group: the group editor);
 * Ctrl+click toggles a waypoint row and Shift+click selects the range of waypoint rows from the last plain or Ctrl
 * click. With 2+ waypoints selected the editor offers "Make group".
 */
public class WaypointScreen extends Screen {
	private static final Component NEW_HERE = Component.translatable("waypy.gui.new_here");
	private static final Component DELETE = Component.translatable("waypy.gui.delete");
	private static final Component DELETE_GROUP_TOOLTIP = Component.translatable("waypy.gui.delete.group_tooltip");
	private static final Component DELETE_MULTI_TOOLTIP = Component.translatable("waypy.gui.delete.multi_tooltip");
	private static final Component UNDO = Component.translatable("waypy.gui.undo");
	private static final Component NAME_LABEL = Component.translatable("waypy.gui.name");
	private static final Component POSITION_LABEL = Component.translatable("waypy.gui.position");
	private static final Component POSITION_CLUMP_LABEL = Component.translatable("waypy.gui.position.clump");
	private static final Component POSITION_INVALID = Component.translatable("waypy.gui.position.invalid");
	private static final Component COLOR_LABEL = Component.translatable("waypy.gui.color");
	private static final Component HEX_LABEL = Component.translatable("waypy.gui.color.hex");
	private static final Component STYLE_LABEL = Component.translatable("waypy.gui.style");
	private static final Component STYLE_CLUMP_TOOLTIP = Component.translatable("waypy.gui.style.clump_tooltip");
	private static final Component LOGO_LABEL = Component.translatable("waypy.gui.logo");
	private static final Component LOGO_BLOCK_TOOLTIP = Component.translatable("waypy.gui.logo_block.tooltip");
	private static final Component OPACITY_LABEL = Component.translatable("waypy.gui.opacity");
	private static final Component THROUGH_BLOCKS_LABEL = Component.translatable("waypy.gui.through_blocks");
	private static final Component VISIBLE_LABEL = Component.translatable("waypy.gui.visible");
	private static final Component VISIBLE_GROUP_TOOLTIP = Component.translatable("waypy.gui.visible.group_tooltip");
	private static final Component SHOW_TEXT_LABEL = Component.translatable("waypy.gui.show_text");
	private static final Component SHOW_TEXT_TOOLTIP = Component.translatable("waypy.gui.show_text.tooltip");
	private static final Component SHOW_TEXT_GROUP_TOOLTIP = Component.translatable("waypy.gui.show_text.group_tooltip");
	private static final Component SETTINGS = Component.translatable("waypy.gui.settings");
	private static final Component MAKE_GROUP = Component.translatable("waypy.gui.make_group");
	private static final Component MAKE_GROUP_TOOLTIP = Component.translatable("waypy.gui.make_group.tooltip");
	private static final Component MAKE_GROUP_MIXED = Component.translatable("waypy.gui.make_group.mixed");
	private static final Component SELECTED_HINT = Component.translatable("waypy.gui.selected_hint");
	private static final Component UNGROUP = Component.translatable("waypy.gui.ungroup");
	private static final Component UNGROUP_TOOLTIP = Component.translatable("waypy.gui.ungroup.tooltip");
	private static final Component REMOVE_FROM_GROUP = Component.translatable("waypy.gui.remove_from_group");
	private static final Component REMOVE_FROM_GROUP_TOOLTIP = Component.translatable("waypy.gui.remove_from_group.tooltip");

	/** Height of one list row: 2px padding + two text lines / a 16px swatch + 2px padding. */
	private static final int ENTRY_HEIGHT = 24;
	/** Indent of a group's member rows under the group row. */
	private static final int MEMBER_INDENT = 10;
	/** Outline of the rows of a multi-selection other than the primary (which gets vanilla's selection outline). */
	private static final int MULTI_OUTLINE = 0xFFA0A0A0;
	/** Space under the list for the "+ New here" / "Delete" row: 5 + 20 + 3. */
	private static final int LIST_BUTTON_ROW = 28;
	/** Space between the "all waypoints are hidden" notice and the list under it. */
	private static final int NOTICE_GAP = 3;
	private static final int SIDE_MARGIN = 10;
	private static final int COLUMN_GAP = 10;
	/** Width {@link ScrollableLayout} adds to the right of the editor for its scrollbar (4 spacing + 6 bar). */
	private static final int EDITOR_SCROLL_RESERVE = 10;
	private static final int PREFERRED_LIST_WIDTH = 200;
	private static final int PREFERRED_EDITOR_WIDTH = 310;
	private static final int SWATCH_SIZE = 11;
	private static final int SWATCH_GAP = 2;
	private static final int SWATCH_COLUMNS = 8;
	private static final int SWATCH_GRID_WIDTH = SWATCH_COLUMNS * SWATCH_SIZE + (SWATCH_COLUMNS - 1) * SWATCH_GAP;
	private static final int INVALID_TEXT_COLOR = 0xFFFF5555;
	private static final int SETTINGS_MIN_WIDTH = 70;
	private static final int SETTINGS_MAX_WIDTH = 90;
	private static final int GRAY = 0xFF808080;
	private static final int LIGHT_GRAY = 0xFFA0A0A0;
	/** A coordinate: an optional '-' and up to 8 digits. */
	private static final Pattern COORDINATE = Pattern.compile("-?\\d{1,8}");
	/** Narrowest coordinate box that still goes on one row with the other two; narrower editors stack them. */
	private static final int MIN_INLINE_COORD_WIDTH = 40;
	private static final int COORD_LABEL_GAP = 4;
	private static final int COORD_GROUP_GAP = 8;

	private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private final WaypointManager manager = WaypointManager.get();

	// Selection. Exactly one mode at a time: a group (selectedGroup), a multi-selection (multi has 2+ waypoints;
	// selected is the primary, the row clicked last), a single waypoint (selected), or nothing.
	private @Nullable Waypoint selected;
	private @Nullable WaypointGroup selectedGroup;
	private final List<Waypoint> multi = new ArrayList<>();
	/** Where a Shift+click range starts: the waypoint of the last plain or Ctrl click. */
	private @Nullable Waypoint rangeAnchor;

	// geometry, computed in init()
	private int listX;
	private int listWidth;
	private int editorX;
	private int editorWidth;
	private boolean twoColumns;

	private @Nullable WaypointList list;
	/** "All waypoints are hidden (press Y)" above the list while hide-all is on (empty otherwise); set in init(). */
	private List<FormattedCharSequence> hiddenNotice = List.of();
	private @Nullable Button newHereButton;
	private @Nullable Button deleteButton;
	private @Nullable Button undoButton;
	/** The pending deletion the Undo button currently offers (null: the button is hidden). */
	private WaypointManager.@Nullable DeletedWaypoint undoOffer;
	private boolean undoRestorable;
	private @Nullable ScrollableLayout editorScroll;
	private LinearLayout editorContent = LinearLayout.vertical();

	// editor widgets (rebuilt on selection change and on init); which are set depends on the mode
	private @Nullable EditBox nameBox;
	private final List<EditBox> coordBoxes = new ArrayList<>();
	/** Set while a coordinate box's text is rewritten by code (Y clamping), so its responder ignores it. */
	private boolean updatingCoordinate;
	private final List<ColorSwatch> swatches = new ArrayList<>();
	private @Nullable EditBox hexBox;
	private @Nullable CycleButton<WaypointStyle> styleButton;
	private @Nullable CycleButton<LogoType> logoTypeButton;
	private @Nullable BlockIconButton logoBlockButton;
	private @Nullable OpacitySlider opacitySlider;
	private @Nullable Checkbox throughBlocksBox;
	private @Nullable CycleButton<Boolean> visibleButton;
	private @Nullable CycleButton<Boolean> showTextButton;
	private @Nullable Button removeFromGroupButton;
	private @Nullable Button ungroupButton;
	private @Nullable Button makeGroupButton;

	public WaypointScreen(@Nullable Waypoint selected) {
		super(Component.translatable("waypy.screen.title"));
		this.selected = selected;
	}

	// ------------------------------------------------------------------ setup

	@Override
	protected void init() {
		layout = new HeaderAndFooterLayout(this);
		layout.addTitleHeader(title, font);
		layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(Button.BIG_WIDTH).build());
		layout.arrangeElements();
		computeGeometry();

		int top = layout.getHeaderHeight();
		// While "hide all" is on, a grey notice line sits at the top of the list column and the list starts under it.
		// (Hide-all only changes from outside this screen: the key in game, or the settings screen, which re-inits this.)
		hiddenNotice = WaypyConfig.get().hideAll
				? font.split(Component.translatable("waypy.gui.all_hidden", WaypyClient.toggleAllKey.getTranslatedKeyMessage()), listWidth - 8)
				: List.of();
		int noticeHeight = hiddenNotice.isEmpty() ? 0 : NOTICE_GAP + hiddenNotice.size() * font.lineHeight + NOTICE_GAP;
		int listHeight = Math.max(ENTRY_HEIGHT, layout.getContentHeight() - LIST_BUTTON_ROW - noticeHeight);
		list = addRenderableWidget(new WaypointList(minecraft, listWidth, listHeight, top + noticeHeight));
		list.setX(listX);

		newHereButton = addRenderableWidget(Button.builder(NEW_HERE, b -> createHere()).size(0, Button.DEFAULT_HEIGHT).build());
		deleteButton = addRenderableWidget(Button.builder(DELETE, b -> deleteSelected()).size(0, Button.DEFAULT_HEIGHT).build());
		undoButton = addRenderableWidget(Button.builder(UNDO, b -> undoDelete()).size(0, Button.DEFAULT_HEIGHT).build());
		undoOffer = null;
		updateUndoButton(true);

		editorContent = LinearLayout.vertical().spacing(4);
		editorScroll = new ScrollableLayout(minecraft, editorContent, Math.max(20, layout.getContentHeight() - 8),
				ScrollableLayout.ReserveStrategy.RIGHT);
		editorScroll.visitWidgets(this::addRenderableWidget);

		resolveSelection();
		list.populate();

		buildEditor();

		// Footer last so it comes last in tab order.
		layout.visitWidgets(this::addRenderableWidget);
		addSettingsButton();
	}

	/**
	 * Drops selected things that no longer exist and settles on one mode: the group if it still exists, else a
	 * multi-selection of 2+, else a single waypoint (the given/remembered one if it still exists, else the manager's,
	 * else the first).
	 */
	private void resolveSelection() {
		List<Waypoint> all = manager.all();
		if (selectedGroup != null && manager.groupById(selectedGroup.id).orElse(null) != selectedGroup) selectedGroup = null;
		multi.removeIf(w -> !all.contains(w));
		if (rangeAnchor != null && !all.contains(rangeAnchor)) rangeAnchor = null;
		if (selectedGroup != null) {
			selected = null;
			multi.clear();
			return;
		}
		if (multi.size() >= 2) {
			if (selected == null || !multi.contains(selected)) selected = multi.getLast();
			manager.select(selected.id);
			return;
		}
		if (multi.size() == 1) selected = multi.getFirst();
		multi.clear();
		Waypoint initial = selected != null && all.contains(selected) ? selected : manager.selected().orElse(null);
		if (initial == null && !all.isEmpty()) initial = all.getFirst();
		selected = initial;
		if (initial != null) manager.select(initial.id);
	}

	/**
	 * Lays out the row under the list: {@code + New here | Delete}, or {@code + New here | Delete | Undo} while a
	 * deletion can be undone.
	 */
	private void layoutListButtons() {
		if (list == null || newHereButton == null || deleteButton == null || undoButton == null) return;
		int y = list.getBottom() + 5;
		boolean undo = undoOffer != null;
		int count = undo ? 3 : 2;
		int each = (listWidth - 4 * (count - 1)) / count;
		int first = listWidth - 4 * (count - 1) - each * (count - 1); // "+ New here" takes the rounding remainder
		newHereButton.setRectangle(first, Button.DEFAULT_HEIGHT, listX, y);
		deleteButton.setRectangle(each, Button.DEFAULT_HEIGHT, listX + first + 4, y);
		undoButton.setRectangle(each, Button.DEFAULT_HEIGHT, listX + first + 4 + each + 4, y);
		undoButton.visible = undo;
	}

	/**
	 * Shows the Undo button while the manager has a pending deletion (15 s): the most recent whole-waypoint deletion,
	 * active only if it can be applied now. Block removals from a clump are final and never offered. Called on init
	 * and every tick.
	 */
	private void updateUndoButton(boolean force) {
		if (undoButton == null) return;
		WaypointManager.DeletedWaypoint offer = manager.lastDeleted().orElse(null);
		boolean restorable = offer != null && manager.canUndo(offer);
		if (!force && offer == undoOffer && restorable == undoRestorable) return;
		undoOffer = offer;
		undoRestorable = restorable;
		undoButton.active = restorable;
		String key = restorable ? "waypy.gui.undo.tooltip" : "waypy.gui.undo.blocked";
		undoButton.setTooltip(offer == null ? null : Tooltip.create(Component.translatable(key, offer.name())));
		layoutListButtons();
	}

	/** Delete works on a single waypoint only: inactive (with the reason) for a group or a multi-selection. */
	private void updateDeleteButton() {
		if (deleteButton == null) return;
		boolean single = selectedGroup == null && multi.size() < 2 && selected != null;
		deleteButton.active = single;
		deleteButton.setTooltip(selectedGroup != null ? Tooltip.create(DELETE_GROUP_TOOLTIP)
				: multi.size() >= 2 ? Tooltip.create(DELETE_MULTI_TOOLTIP) : null);
	}

	@Override
	public void tick() {
		super.tick();
		updateUndoButton(false);
	}

	/**
	 * "Settings..." in the header row, level with the title and right-aligned to the editor column's right edge.
	 * Narrowed (or moved right, up to the screen edge) so it never overlaps the centred title.
	 */
	private void addSettingsButton() {
		int headerHeight = layout.getHeaderHeight();
		int buttonWidth = Math.clamp(font.width(SETTINGS) + 16, SETTINGS_MIN_WIDTH, SETTINGS_MAX_WIDTH);
		int right = Math.min(editorX + editorWidth, width - 4);
		int minLeft = (width + font.width(title)) / 2 + 8; // keep clear of the title
		if (right - buttonWidth < minLeft) {
			right = width - 4;
			buttonWidth = Math.min(buttonWidth, right - minLeft);
		}
		if (buttonWidth < 20) return; // absurdly narrow window: no room
		int y = Math.max(0, (headerHeight - Button.DEFAULT_HEIGHT) / 2);
		addRenderableWidget(Button.builder(SETTINGS, b -> minecraft.gui.setScreen(new WaypySettingsScreen(this)))
				.bounds(right - buttonWidth, y, buttonWidth, Button.DEFAULT_HEIGHT).build());
	}

	/** Splits the width between list and editor, shrinking the list first and the editor second. */
	private void computeGeometry() {
		int available = width - 2 * SIDE_MARGIN - COLUMN_GAP - EDITOR_SCROLL_RESERVE;
		int lw = PREFERRED_LIST_WIDTH;
		int ew = PREFERRED_EDITOR_WIDTH;
		int over = lw + ew - available;
		if (over > 0) { int d = Math.min(over, lw - 140); lw -= d; over -= d; }
		if (over > 0) { int d = Math.min(over, ew - 200); ew -= d; over -= d; }
		if (over > 0) { int d = Math.min(over, lw - 100); lw -= d; over -= d; }
		if (over > 0) { ew = Math.max(100, ew - over); }
		listWidth = lw;
		editorWidth = ew;
		twoColumns = ew >= 300;
		int total = lw + COLUMN_GAP + ew + EDITOR_SCROLL_RESERVE;
		listX = Math.max(0, (width - total) / 2);
		editorX = listX + lw + COLUMN_GAP;
	}

	@Override
	protected void setInitialFocus() {
		if (list != null) setInitialFocus(list);
	}

	// ------------------------------------------------------------------ selection

	/**
	 * A click on a waypoint row (public for the devtest, which clicks rows this way): plain = select just it;
	 * {@code ctrl} = toggle it in the multi-selection (the last selected row can't be toggled off); {@code shift} =
	 * select the range of waypoint rows from the last plain/Ctrl-clicked one to it.
	 */
	public void clickWaypoint(Waypoint waypoint, boolean shift, boolean ctrl) {
		if (ctrl) {
			if (selectedGroup != null) {
				selectedGroup = null;
				multi.clear();
			}
			if (multi.isEmpty() && selected != null) multi.add(selected);
			if (multi.contains(waypoint)) {
				if (multi.size() > 1) multi.remove(waypoint);
				if (selected == waypoint) selected = multi.getLast();
			} else {
				multi.add(waypoint);
				selected = waypoint;
			}
			rangeAnchor = waypoint;
		} else if (shift && (rangeAnchor != null || selected != null)) {
			List<Waypoint> order = list != null ? list.waypointOrder() : manager.all();
			Waypoint anchor = rangeAnchor != null && order.contains(rangeAnchor) ? rangeAnchor : selected;
			int from = order.indexOf(anchor);
			int to = order.indexOf(waypoint);
			if (from < 0 || to < 0) {
				selectOnly(waypoint);
			} else {
				selectedGroup = null;
				multi.clear();
				multi.addAll(order.subList(Math.min(from, to), Math.max(from, to) + 1));
				selected = waypoint;
			}
		} else {
			selectOnly(waypoint);
		}
		if (multi.size() < 2) {
			if (multi.size() == 1) selected = multi.getFirst();
			multi.clear();
		}
		selectionChanged();
	}

	/** A click on a group row: selects the group (the group editor). Public for the devtest. */
	public void clickGroup(WaypointGroup group) {
		selectedGroup = group;
		selected = null;
		multi.clear();
		rangeAnchor = null;
		selectionChanged();
	}

	private void selectOnly(Waypoint waypoint) {
		selectedGroup = null;
		multi.clear();
		selected = waypoint;
		rangeAnchor = waypoint;
	}

	/** After a selection change: remember it in the manager, highlight the rows and rebuild the editor. */
	private void selectionChanged() {
		if (selected != null) manager.select(selected.id);
		if (list != null) list.highlightPrimary();
		buildEditor();
	}

	private boolean isMultiSelected(Waypoint waypoint) {
		return multi.size() >= 2 && multi.contains(waypoint);
	}

	// ------------------------------------------------------------------ editor

	/** (Re)creates the editor widgets for the current selection mode and lays them out. */
	private void buildEditor() {
		if (editorScroll != null) {
			editorScroll.visitChildren(e -> {
				if (e instanceof ContainerEventHandler container) container.setFocused(null);
			});
		}
		swatches.clear();
		coordBoxes.clear();
		nameBox = hexBox = null;
		styleButton = null;
		logoTypeButton = null;
		logoBlockButton = null;
		opacitySlider = null;
		throughBlocksBox = null;
		visibleButton = null;
		showTextButton = null;
		removeFromGroupButton = null;
		ungroupButton = null;
		makeGroupButton = null;
		updateDeleteButton();

		int half = twoColumns ? (editorWidth - COLUMN_GAP) / 2 : editorWidth;
		if (selectedGroup != null) {
			buildGroupWidgets(selectedGroup, half);
		} else if (multi.size() >= 2) {
			buildMultiWidgets();
		} else if (selected != null) {
			buildWaypointWidgets(selected, half);
		}
		layoutEditor();
	}

	private void buildWaypointWidgets(Waypoint w, int half) {
		int full = editorWidth;
		nameBox = nameBox(w.name, w::setName);

		int coordWidth = coordinatesInline() ? inlineCoordinateWidth() : full - font.width("X") - COORD_LABEL_GAP;
		for (Direction.Axis axis : Direction.Axis.values()) {
			coordBoxes.add(coordinateBox(w, axis, coordWidth));
		}

		colorWidgets(() -> w.color, color -> w.color = color);

		styleButton = CycleButton.<WaypointStyle>builder(WaypointScreen::styleName, w.style)
				.withValues(WaypointStyle.values())
				.create(0, 0, half, Button.DEFAULT_HEIGHT, STYLE_LABEL, (button, value) -> {
					w.style = value;
					manager.markDirty();
					layoutEditor();
				});
		if (w.isClump()) {
			// A clump is always Block Selection (the manager enforces it); the style can't be changed.
			styleButton.active = false;
			styleButton.setTooltip(Tooltip.create(STYLE_CLUMP_TOOLTIP));
		}

		logoWidgets(half, () -> w.logoType, type -> w.logoType = type, () -> w.logoBlock, id -> w.logoBlock = id);

		opacitySlider = new OpacitySlider(half, w);

		throughBlocksBox = Checkbox.builder(THROUGH_BLOCKS_LABEL, font)
				.maxWidth(half)
				.selected(w.throughBlocks)
				.onValueChange((box, value) -> {
					w.throughBlocks = value;
					manager.markDirty();
				})
				.build();

		visibleButton = CycleButton.onOffBuilder(w.visible)
				.create(0, 0, half, Button.DEFAULT_HEIGHT, VISIBLE_LABEL, (button, value) -> {
					w.visible = value;
					manager.markDirty();
				});

		showTextButton = showTextButton(half, w.showText, value -> w.showText = value, SHOW_TEXT_TOOLTIP);

		if (manager.groupOf(w.id) != null) {
			removeFromGroupButton = Button.builder(REMOVE_FROM_GROUP, b -> removeSelectedFromGroup())
					.size(half, Button.DEFAULT_HEIGHT).tooltip(Tooltip.create(REMOVE_FROM_GROUP_TOOLTIP)).build();
		}
	}

	private void buildGroupWidgets(WaypointGroup g, int half) {
		nameBox = nameBox(g.name, g::setName);
		colorWidgets(() -> g.color, color -> g.color = color);
		logoWidgets(half, () -> g.logoType, type -> g.logoType = type, () -> g.logoBlock, id -> g.logoBlock = id);
		visibleButton = CycleButton.onOffBuilder(g.visible)
				.create(0, 0, half, Button.DEFAULT_HEIGHT, VISIBLE_LABEL, (button, value) -> {
					g.visible = value; // the members' own Visible flags are left alone
					manager.markDirty();
				});
		visibleButton.setTooltip(Tooltip.create(VISIBLE_GROUP_TOOLTIP));
		showTextButton = showTextButton(half, g.showText, value -> g.showText = value, SHOW_TEXT_GROUP_TOOLTIP);
		ungroupButton = Button.builder(UNGROUP, b -> ungroupSelected())
				.size(half, Button.DEFAULT_HEIGHT).tooltip(Tooltip.create(UNGROUP_TOOLTIP)).build();
	}

	private void buildMultiWidgets() {
		int width = twoColumns ? (editorWidth - COLUMN_GAP) / 2 : editorWidth;
		makeGroupButton = Button.builder(MAKE_GROUP, b -> makeGroup()).size(width, Button.DEFAULT_HEIGHT).build();
		boolean ok = manager.canGroup(List.copyOf(multi)) == WaypointManager.GroupCheck.OK;
		makeGroupButton.active = ok;
		makeGroupButton.setTooltip(Tooltip.create(ok ? MAKE_GROUP_TOOLTIP : MAKE_GROUP_MIXED));
	}

	private EditBox nameBox(String value, Consumer<String> setter) {
		EditBox box = new EditBox(font, editorWidth, Button.DEFAULT_HEIGHT, NAME_LABEL);
		box.setMaxLength(Waypoint.MAX_NAME_LENGTH);
		box.setValue(value);
		box.setResponder(text -> {
			setter.accept(text);
			manager.markDirty();
		});
		return box;
	}

	/** The 16 dye swatches and the hex box, editing a colour through {@code get}/{@code set}. */
	private void colorWidgets(IntSupplier get, IntConsumer set) {
		for (DyeColor dye : DyeColor.values()) {
			swatches.add(new ColorSwatch(dye, get, set));
		}
		EditBox hex = new EditBox(font, hexInline() ? editorWidth - SWATCH_GRID_WIDTH - 8 : editorWidth, Button.DEFAULT_HEIGHT, HEX_LABEL);
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
			if (get.getAsInt() != color) {
				set.accept(color);
				manager.markDirty();
			}
		});
		hexBox = hex;
	}

	/** The Logo (Block / None) cycle button and the block button that opens the picker. */
	private void logoWidgets(int half, Supplier<LogoType> type, Consumer<LogoType> setType, Supplier<Identifier> block,
			Consumer<Identifier> setBlock) {
		logoTypeButton = CycleButton.<LogoType>builder(WaypointScreen::logoTypeName, type.get())
				.withValues(LogoType.values())
				.create(0, 0, half, Button.DEFAULT_HEIGHT, LOGO_LABEL, (button, value) -> {
					setType.accept(value);
					manager.markDirty();
					layoutEditor();
				});
		logoBlockButton = new BlockIconButton(half, block.get(), b -> minecraft.gui.setScreen(
				new BlockPickerScreen(this, block.get(), id -> {
					setBlock.accept(id);
					manager.markDirty();
				})));
		logoBlockButton.setTooltip(Tooltip.create(LOGO_BLOCK_TOOLTIP));
	}

	private CycleButton<Boolean> showTextButton(int half, boolean value, Consumer<Boolean> setter, Component tooltip) {
		CycleButton<Boolean> button = CycleButton.onOffBuilder(value)
				.create(0, 0, half, Button.DEFAULT_HEIGHT, SHOW_TEXT_LABEL, (b, v) -> {
					setter.accept(v);
					manager.markDirty();
				});
		button.setTooltip(Tooltip.create(tooltip));
		return button;
	}

	/** Width of each coordinate box when X, Y and Z share one row. */
	private int inlineCoordinateWidth() {
		int labels = font.width("X") + font.width("Y") + font.width("Z") + 3 * COORD_LABEL_GAP;
		return (editorWidth - labels - 2 * COORD_GROUP_GAP) / 3;
	}

	private boolean coordinatesInline() {
		return inlineCoordinateWidth() >= MIN_INLINE_COORD_WIDTH;
	}

	/**
	 * One coordinate of the waypoint's anchor (first block). Typing a whole number moves the waypoint (a clump is
	 * translated as a whole) through {@link WaypointManager#move}, as the hex box applies colors: invalid input or a
	 * move onto another waypoint's blocks turns the text red, gives the reason as a tooltip and isn't applied. Y is
	 * clamped so every block stays within the current level's build height.
	 */
	private EditBox coordinateBox(Waypoint w, Direction.Axis axis, int boxWidth) {
		Component name = Component.translatable("waypy.gui.position." + axis.getName());
		EditBox box = new EditBox(font, boxWidth, Button.DEFAULT_HEIGHT, name);
		box.setMaxLength(9);
		box.setValue(String.valueOf(w.anchor().get(axis)));
		box.setResponder(value -> {
			if (updatingCoordinate) return;
			applyCoordinate(box, w, axis, value);
		});
		return box;
	}

	/** The menu's move path, public for the devtest: applies {@code value} typed into the {@code axis} box. */
	public void applyCoordinate(EditBox box, Waypoint w, Direction.Axis axis, String value) {
		String text = value.trim();
		if (!COORDINATE.matcher(text).matches()) {
			markCoordinate(box, POSITION_INVALID);
			return;
		}
		int target = Integer.parseInt(text);
		BlockPos anchor = w.anchor();
		Component note = null;
		if (axis == Direction.Axis.Y && minecraft.level != null) {
			// Keep the whole clump inside the build height: the anchor's range shrinks by the blocks below/above it.
			AABB bounds = w.bounds();
			int below = anchor.getY() - (int) bounds.minY;
			int above = (int) bounds.maxY - 1 - anchor.getY();
			int min = minecraft.level.getMinY() + below;
			int max = minecraft.level.getMaxY() - above;
			if (target < min || target > max) {
				target = Math.clamp(target, min, Math.max(min, max));
				note = Component.translatable("waypy.gui.position.clamped", minecraft.level.getMinY(), minecraft.level.getMaxY());
			}
		}
		BlockPos moved = switch (axis) {
			case X -> new BlockPos(target, anchor.getY(), anchor.getZ());
			case Y -> new BlockPos(anchor.getX(), target, anchor.getZ());
			case Z -> new BlockPos(anchor.getX(), anchor.getY(), target);
		};
		WaypointManager.MoveResult result = manager.move(w, moved);
		switch (result.status()) {
			case OVERLAP -> {
				Waypoint blocker = result.blocker();
				markCoordinate(box, Component.translatable("waypy.gui.position.overlap", blocker != null ? blocker.name : "?"));
				return;
			}
			case UNKNOWN_WAYPOINT -> {
				markCoordinate(box, POSITION_INVALID);
				return;
			}
			default -> {
			}
		}
		box.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
		box.setTooltip(note == null ? null : Tooltip.create(note));
		if (note != null) {
			updatingCoordinate = true;
			box.setValue(String.valueOf(target));
			updatingCoordinate = false;
		}
	}

	/** Shows a coordinate box as invalid (red text) with the reason as its tooltip. */
	private static void markCoordinate(EditBox box, Component reason) {
		box.setTextColor(INVALID_TEXT_COLOR);
		box.setTooltip(Tooltip.create(reason));
	}

	private boolean hexInline() {
		return editorWidth - SWATCH_GRID_WIDTH - 8 >= 60;
	}

	/** Puts the existing editor widgets into the scrollable layout according to the mode and current style/logo type. */
	private void layoutEditor() {
		ScrollableLayout scroll = editorScroll;
		if (scroll == null) return;
		scroll.removeChildren();

		if (selectedGroup != null) {
			layoutGroupEditor(selectedGroup);
		} else if (multi.size() >= 2) {
			layoutMultiEditor();
		} else if (selected != null) {
			layoutWaypointEditor(selected);
		}

		int top = layout.getHeaderHeight() + 4;
		int height = Math.max(20, layout.getContentHeight() - 8);
		scroll.setMinHeight(height);
		scroll.setMaxHeight(height);
		scroll.arrangeElements();
		scroll.setX(editorX);
		scroll.setY(top);
	}

	private void layoutWaypointEditor(Waypoint w) {
		if (nameBox == null || hexBox == null || styleButton == null || logoTypeButton == null || logoBlockButton == null
				|| opacitySlider == null || throughBlocksBox == null || visibleButton == null || showTextButton == null
				|| coordBoxes.size() != 3) {
			return;
		}
		editorContent.addChild(label(NAME_LABEL));
		editorContent.addChild(nameBox);

		// Position (the anchor) under the name: "X [ ] Y [ ] Z [ ]" on one row, or one row each when narrow.
		editorContent.addChild(label(w.isClump() ? POSITION_CLUMP_LABEL : POSITION_LABEL), s -> s.paddingTop(2));
		boolean inline = coordinatesInline();
		LinearLayout coordRow = LinearLayout.horizontal().spacing(COORD_GROUP_GAP);
		Direction.Axis[] axes = Direction.Axis.values();
		for (int i = 0; i < axes.length; i++) {
			LinearLayout group = LinearLayout.horizontal().spacing(COORD_LABEL_GAP);
			group.addChild(new StringWidget(Component.literal(axes[i].getName().toUpperCase(Locale.ROOT)), font),
					s -> s.alignVerticallyMiddle());
			group.addChild(coordBoxes.get(i));
			if (inline) coordRow.addChild(group);
			else editorContent.addChild(group);
		}
		if (inline) editorContent.addChild(coordRow);

		addColorRows();

		boolean highlight = w.style == WaypointStyle.BLOCK_HIGHLIGHT;
		Button logoDetail = w.logoType == LogoType.BLOCK ? logoBlockButton : null;
		if (twoColumns) {
			LinearLayout row1 = LinearLayout.horizontal().spacing(COLUMN_GAP);
			row1.addChild(styleButton);
			row1.addChild(logoTypeButton);
			editorContent.addChild(row1, s -> s.paddingTop(4));

			if (logoDetail != null) {
				// The block button sits under "Logo", in the right column.
				LinearLayout logoRow = LinearLayout.horizontal().spacing(COLUMN_GAP);
				logoRow.addChild(SpacerElement.width(styleButton.getWidth()));
				logoRow.addChild(logoDetail);
				editorContent.addChild(logoRow);
			}

			LinearLayout row2 = LinearLayout.horizontal().spacing(COLUMN_GAP);
			row2.addChild(visibleButton);
			row2.addChild(showTextButton);
			editorContent.addChild(row2);

			if (highlight) {
				LinearLayout row3 = LinearLayout.horizontal().spacing(COLUMN_GAP);
				row3.addChild(opacitySlider);
				row3.addChild(throughBlocksBox, s -> s.alignVerticallyMiddle());
				editorContent.addChild(row3);
			}
		} else {
			editorContent.addChild(styleButton, s -> s.paddingTop(4));
			editorContent.addChild(logoTypeButton);
			if (logoDetail != null) editorContent.addChild(logoDetail);
			if (highlight) {
				editorContent.addChild(opacitySlider);
				editorContent.addChild(throughBlocksBox);
			}
			editorContent.addChild(visibleButton);
			editorContent.addChild(showTextButton);
		}

		// A group member: "Group: <name>" and "Remove from group", side by side when there is room.
		WaypointGroup group = manager.groupOf(w.id);
		if (group != null && removeFromGroupButton != null) {
			Component groupText = Component.translatable("waypy.gui.member_of", group.name).withStyle(ChatFormatting.GRAY);
			if (twoColumns) {
				// The line fills the left column (clipped if too long, centred on the button's height).
				LinearLayout row = LinearLayout.horizontal().spacing(COLUMN_GAP);
				row.addChild(new StringWidget(removeFromGroupButton.getWidth(), Button.DEFAULT_HEIGHT, groupText, font));
				row.addChild(removeFromGroupButton);
				editorContent.addChild(row, s -> s.paddingTop(2));
			} else {
				editorContent.addChild(new StringWidget(groupText, font).setMaxWidth(editorWidth), s -> s.paddingTop(2));
				editorContent.addChild(removeFromGroupButton);
			}
		}
		if (w.isClump()) {
			editorContent.addChild(label(Component.translatable("waypy.gui.clump_info", w.size())), s -> s.paddingTop(2));
		}
	}

	private void layoutGroupEditor(WaypointGroup g) {
		if (nameBox == null || hexBox == null || logoTypeButton == null || logoBlockButton == null || visibleButton == null
				|| showTextButton == null || ungroupButton == null) {
			return;
		}
		editorContent.addChild(new StringWidget(Component.translatable("waypy.gui.group_members", g.size()), font));
		editorContent.addChild(label(NAME_LABEL), s -> s.paddingTop(2));
		editorContent.addChild(nameBox);
		addColorRows();

		Button logoDetail = g.logoType == LogoType.BLOCK ? logoBlockButton : null;
		if (twoColumns) {
			// Logo | block button, then Visible | Text (as in the waypoint editor), then Ungroup in the left column.
			LinearLayout row1 = LinearLayout.horizontal().spacing(COLUMN_GAP);
			row1.addChild(logoTypeButton);
			if (logoDetail != null) row1.addChild(logoDetail);
			editorContent.addChild(row1, s -> s.paddingTop(4));
			LinearLayout row2 = LinearLayout.horizontal().spacing(COLUMN_GAP);
			row2.addChild(visibleButton);
			row2.addChild(showTextButton);
			editorContent.addChild(row2);
			editorContent.addChild(ungroupButton); // alone on its row, in the left column
		} else {
			editorContent.addChild(logoTypeButton, s -> s.paddingTop(4));
			if (logoDetail != null) editorContent.addChild(logoDetail);
			editorContent.addChild(visibleButton);
			editorContent.addChild(showTextButton);
			editorContent.addChild(ungroupButton);
		}
	}

	private void layoutMultiEditor() {
		if (makeGroupButton == null) return;
		editorContent.addChild(new StringWidget(Component.translatable("waypy.gui.selected_count", multi.size()), font));
		editorContent.addChild(new MultiLineTextWidget(SELECTED_HINT.copy().withStyle(ChatFormatting.GRAY), font)
				.setMaxWidth(editorWidth));
		editorContent.addChild(makeGroupButton, s -> s.paddingTop(4));
	}

	/** "Color" label, then the swatch grid with the hex box beside it (or under it when narrow). */
	private void addColorRows() {
		if (hexBox == null) return;
		editorContent.addChild(label(COLOR_LABEL), s -> s.paddingTop(2));
		GridLayout swatchGrid = new GridLayout().spacing(SWATCH_GAP);
		for (int i = 0; i < swatches.size(); i++) {
			swatchGrid.addChild(swatches.get(i), i / SWATCH_COLUMNS, i % SWATCH_COLUMNS);
		}
		if (hexInline()) {
			LinearLayout colorRow = LinearLayout.horizontal().spacing(8);
			colorRow.addChild(swatchGrid, s -> s.alignVerticallyMiddle());
			colorRow.addChild(hexBox, s -> s.alignVerticallyMiddle());
			editorContent.addChild(colorRow);
		} else {
			editorContent.addChild(swatchGrid);
			editorContent.addChild(hexBox);
		}
	}

	private StringWidget label(Component text) {
		return new StringWidget(text.copy().withStyle(ChatFormatting.GRAY), font);
	}

	// ------------------------------------------------------------------ actions

	private void createHere() {
		Waypoint created = WaypyClient.createWaypoint(minecraft, false);
		if (created != null) {
			selectOnly(created);
			rebuildWidgets();
		}
	}

	/**
	 * Deletes the selected waypoint at once (no confirmation: Undo restores it for 15 s) and selects its neighbour in
	 * the list. Only for a single selected waypoint (Delete is inactive for a group or a multi-selection).
	 */
	public void deleteSelected() {
		Waypoint w = selected;
		if (w == null || selectedGroup != null || multi.size() >= 2) return;
		List<Waypoint> order = list != null ? list.waypointOrder() : manager.all();
		int index = order.indexOf(w);
		manager.remove(w.id);
		List<Waypoint> rest = new ArrayList<>(order);
		rest.remove(w);
		rest.removeIf(x -> manager.byId(x.id).isEmpty());
		selected = rest.isEmpty() ? null : rest.get(Math.min(Math.max(index, 0), rest.size() - 1));
		rangeAnchor = selected;
		manager.select(selected != null ? selected.id : null);
		rebuildWidgets();
	}

	/**
	 * Undoes the most recent deletion, if it can be applied, and selects the restored waypoint. The editor is rebuilt,
	 * so the Style lock follows its block count.
	 */
	public void undoDelete() {
		Optional<WaypointManager.DeletedWaypoint> offer = manager.lastDeleted();
		if (offer.isEmpty()) return;
		Waypoint restored = manager.undo(offer.get());
		if (restored == null) {
			updateUndoButton(true);
			return;
		}
		selectOnly(restored);
		manager.select(restored.id);
		rebuildWidgets();
	}

	/**
	 * "Make group" for the multi-selection (in list order, so the topmost waypoint is the first member, whose colour
	 * and logo the group takes). The new group is selected. Public for the devtest.
	 *
	 * @return the new group, or null if the selection can't be grouped
	 */
	public @Nullable WaypointGroup makeGroup() {
		if (multi.size() < 2) return null;
		List<Waypoint> members = new ArrayList<>();
		for (Waypoint w : list != null ? list.waypointOrder() : manager.all()) {
			if (multi.contains(w)) members.add(w);
		}
		WaypointGroup group = manager.createGroup(members);
		if (group == null) return null;
		clickGroupAndRebuild(group);
		return group;
	}

	/** "Ungroup": dissolves the selected group; its first member is selected. Public for the devtest. */
	public void ungroupSelected() {
		WaypointGroup g = selectedGroup;
		if (g == null) return;
		List<Waypoint> members = manager.members(g);
		manager.ungroup(g);
		selectedGroup = null;
		if (!members.isEmpty()) selectOnly(members.getFirst());
		rebuildWidgets();
	}

	/** "Remove from group" for the selected waypoint (it stays selected). Public for the devtest. */
	public void removeSelectedFromGroup() {
		if (selected == null) return;
		manager.removeFromGroup(selected);
		rebuildWidgets();
	}

	private void clickGroupAndRebuild(WaypointGroup group) {
		selectedGroup = group;
		selected = null;
		multi.clear();
		rangeAnchor = null;
		rebuildWidgets();
	}

	/** The waypoint being edited (or the primary of a multi-selection), if any. */
	public @Nullable Waypoint selectedWaypoint() {
		return selected;
	}

	/** The group being edited, if any. For the devtest. */
	public @Nullable WaypointGroup selectedGroup() {
		return selectedGroup;
	}

	/** The multi-selection (empty unless 2+ waypoints are selected). For the devtest. */
	public List<Waypoint> multiSelection() {
		return List.copyOf(multi);
	}

	/** The Undo button (null before init). For the devtest. */
	public @Nullable Button undoButton() {
		return undoButton;
	}

	/** The Delete button (null before init). For the devtest. */
	public @Nullable Button deleteButton() {
		return deleteButton;
	}

	/** The "Make group" button (null unless 2+ waypoints are selected). For the devtest. */
	public @Nullable Button makeGroupButton() {
		return makeGroupButton;
	}

	/** The "Remove from group" button (null unless a group member is edited). For the devtest. */
	public @Nullable Button removeFromGroupButton() {
		return removeFromGroupButton;
	}

	/** The "Visible" cycle button of the waypoint or group editor (null otherwise). For the devtest. */
	public @Nullable CycleButton<Boolean> visibleButton() {
		return visibleButton;
	}

	/** Whether the "All waypoints are hidden" notice is shown above the list. For the devtest. */
	public boolean showsHiddenNotice() {
		return !hiddenNotice.isEmpty();
	}

	/** The X, Y and Z boxes of the editor (empty without a selection). For the devtest. */
	public List<EditBox> coordinateBoxes() {
		return List.copyOf(coordBoxes);
	}

	@Override
	public void removed() {
		manager.save();
		super.removed();
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		boolean inWorld = minecraft.level != null;
		Identifier header = inWorld ? INWORLD_HEADER_SEPARATOR : HEADER_SEPARATOR;
		Identifier footer = inWorld ? INWORLD_FOOTER_SEPARATOR : FOOTER_SEPARATOR;
		graphics.blit(RenderPipelines.GUI_TEXTURED, header, 0, layout.getHeaderHeight() - 2, 0.0F, 0.0F, width, 2, 32, 2);
		graphics.blit(RenderPipelines.GUI_TEXTURED, footer, 0, height - layout.getFooterHeight(), 0.0F, 0.0F, width, 2, 32, 2);

		int noticeY = layout.getHeaderHeight() + NOTICE_GAP;
		for (FormattedCharSequence line : hiddenNotice) {
			graphics.text(font, line, listX + 4, noticeY, LIGHT_GRAY);
			noticeY += font.lineHeight;
		}

		if (selected == null && selectedGroup == null) {
			Component hint = Component.translatable("waypy.gui.no_waypoints", WaypyClient.createKey.getTranslatedKeyMessage());
			List<FormattedCharSequence> lines = font.split(hint, editorWidth);
			int centerX = editorX + editorWidth / 2;
			int y = layout.getHeaderHeight() + (layout.getContentHeight() - lines.size() * 9) / 2;
			for (FormattedCharSequence line : lines) {
				graphics.centeredText(font, line, centerX, y, LIGHT_GRAY);
				y += 9;
			}
		}
	}

	// ------------------------------------------------------------------ helpers

	private static Component styleName(WaypointStyle style) {
		return switch (style) {
			case BEACON -> Component.translatable("waypy.gui.style.beacon");
			case BLOCK_HIGHLIGHT -> Component.translatable("waypy.gui.style.block_highlight");
		};
	}

	private static Component logoTypeName(LogoType type) {
		return switch (type) {
			case BLOCK -> Component.translatable("waypy.gui.logo.block");
			case NONE -> Component.translatable("waypy.gui.logo.none");
		};
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

	static ItemStack stackFor(Identifier id) {
		Item item = BuiltInRegistries.ITEM.getValue(id);
		if (item == Items.AIR) item = BuiltInRegistries.ITEM.getValue(WaypointManager.DEFAULT_LOGO);
		return new ItemStack(item);
	}

	private static String ellipsize(net.minecraft.client.gui.Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		String dots = "...";
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(dots))) + dots;
	}

	/** Distance from the player to {@code point} in {@code dimension} (e.g. {@code 24m}), or the dimension's id elsewhere. */
	private Component distanceText(Vec3 point, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
		LocalPlayer player = minecraft.player;
		if (player == null || !player.level().dimension().equals(dimension)) {
			return Component.literal(dimension.identifier().toString());
		}
		return Component.translatable("waypy.gui.distance", Math.round(point.distanceTo(player.position())));
	}

	// ------------------------------------------------------------------ widgets

	/**
	 * The list: each group's row followed by its members (indented), then the ungrouped waypoints. The vanilla
	 * selection is the primary row (the edited waypoint or group); the other rows of a multi-selection get a lighter
	 * outline of the same shape.
	 */
	private final class WaypointList extends ObjectSelectionList<WaypointList.Entry> {
		private boolean populating;
		/** Set by a row's mouse click, which applies the selection itself; the focus change that follows only highlights. */
		private boolean clickHandled;

		WaypointList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, ENTRY_HEIGHT);
		}

		void populate() {
			populating = true;
			clearEntries();
			for (WaypointGroup group : manager.groups()) {
				addEntry(new GroupEntry(group));
				for (Waypoint member : manager.members(group)) addEntry(new WaypointEntry(member, true));
			}
			for (Waypoint w : manager.all()) {
				if (manager.groupOf(w.id) == null) addEntry(new WaypointEntry(w, false));
			}
			Entry primary = primaryEntry();
			super.setSelected(primary);
			if (primary != null) centerScrollOn(primary);
			populating = false;
		}

		/** The waypoints in row order (members under their group first, then the ungrouped ones). */
		List<Waypoint> waypointOrder() {
			List<Waypoint> out = new ArrayList<>();
			for (Entry e : children()) {
				if (e instanceof WaypointEntry we) out.add(we.waypoint);
			}
			return out;
		}

		/** The row of the edited waypoint or group, if any. */
		@Nullable Entry primaryEntry() {
			for (Entry e : children()) {
				if (selectedGroup != null ? e instanceof GroupEntry g && g.group == selectedGroup
						: e instanceof WaypointEntry w && w.waypoint == selected) {
					return e;
				}
			}
			return null;
		}

		/** Moves the vanilla selection to the primary row without selecting anything. */
		void highlightPrimary() {
			super.setSelected(primaryEntry());
		}

		@Override
		public void setSelected(@Nullable Entry entry) {
			if (clickHandled) {
				// The click (which may have been a Ctrl/Shift click) already changed the selection.
				clickHandled = false;
				super.setSelected(primaryEntry());
				return;
			}
			if (entry == null && !populating) {
				// Keep the edited row highlighted when keyboard navigation leaves the list.
				Entry primary = primaryEntry();
				if (primary != null) {
					super.setSelected(primary);
					return;
				}
			}
			super.setSelected(entry);
			if (!populating && entry != null) entry.select(false, false); // keyboard navigation: a plain select
		}

		@Override
		protected void extractItem(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, Entry entry) {
			if (entry != getSelected() && entry instanceof WaypointEntry w && isMultiSelected(w.waypoint)) {
				extractSelection(graphics, entry, MULTI_OUTLINE);
			}
			super.extractItem(graphics, mouseX, mouseY, a, entry);
		}

		@Override
		protected void extractSelection(GuiGraphicsExtractor graphics, Entry entry, int outlineColor) {
			int x0 = entry.getX() + entry.indent();
			int y0 = entry.getY();
			int x1 = entry.getX() + entry.getWidth();
			int y1 = y0 + entry.getHeight();
			graphics.fill(x0, y0, x1, y1, outlineColor);
			graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xFF000000);
		}

		@Override
		public int getRowLeft() {
			return getX() + 4;
		}

		@Override
		public int getRowWidth() {
			// Leave room for the scrollbar (scrollBarX = rowRight + 8, 6 wide) only when it is shown.
			return scrollable() ? getWidth() - 20 : getWidth() - 8;
		}

		abstract class Entry extends ObjectSelectionList.Entry<Entry> {
			/** Applies a click (or keyboard selection) on this row. */
			abstract void select(boolean shift, boolean ctrl);

			/** Left indent of the row's content and selection outline. */
			int indent() {
				return 0;
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				boolean ctrl = event.hasControlDown() || (event.modifiers() & GLFW.GLFW_MOD_SUPER) != 0;
				select(event.hasShiftDown(), ctrl);
				clickHandled = true;
				return true;
			}

			/** A 16x16 colour swatch with a black border at ({@code x}, {@code y}). */
			void swatch(GuiGraphicsExtractor graphics, int x, int y, int color) {
				graphics.fill(x, y, x + 16, y + 16, 0xFF000000);
				graphics.fill(x + 1, y + 1, x + 15, y + 15, 0xFF000000 | color);
			}
		}

		/** A waypoint row: swatch, name and distance (plus the block count for clumps); indented under its group. */
		final class WaypointEntry extends Entry {
			final Waypoint waypoint;
			private final boolean member;

			WaypointEntry(Waypoint waypoint, boolean member) {
				this.waypoint = waypoint;
				this.member = member;
			}

			@Override
			void select(boolean shift, boolean ctrl) {
				clickWaypoint(waypoint, shift, ctrl);
			}

			@Override
			int indent() {
				return member ? MEMBER_INDENT : 0;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				int x = getContentX() + indent();
				int y = getContentY();
				swatch(graphics, x, getContentYMiddle() - 8, waypoint.color);
				int textX = x + 16 + 4;
				int maxWidth = getContentRight() - textX;
				// Greyed when not shown: hidden itself, or (own Visible on) hidden by its group, which the detail line says.
				boolean shown = manager.isEffectivelyVisible(waypoint);
				graphics.text(font, ellipsize(font, waypoint.name, maxWidth), textX, y + 1, shown ? 0xFFFFFFFF : GRAY);
				graphics.text(font, ellipsize(font, detailText().getString(), maxWidth), textX, y + 11, GRAY);
			}

			/**
			 * Distance (or dimension), plus the block count for clumps: {@code 24m · 12 blocks}; then
			 * {@code · group hidden} when only its group hides it.
			 */
			private Component detailText() {
				Component distance = distanceText(waypoint.centre(), waypoint.dimension);
				Component detail = waypoint.isClump() ? Component.translatable("waypy.gui.block_count", distance, waypoint.size()) : distance;
				return manager.isHiddenByGroup(waypoint) ? Component.translatable("waypy.gui.group_hidden_detail", detail) : detail;
			}

			@Override
			public Component getNarration() {
				return Component.translatable("narrator.select", waypoint.name);
			}
		}

		/** A group row: a stacked swatch in the group colour, the name and {@code 3 waypoints · 24m}. */
		final class GroupEntry extends Entry {
			final WaypointGroup group;

			GroupEntry(WaypointGroup group) {
				this.group = group;
			}

			@Override
			void select(boolean shift, boolean ctrl) {
				clickGroup(group);
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				int x = getContentX();
				int y = getContentY();
				int swatchY = getContentYMiddle() - 8;
				// Two offset swatches ("a stack") tell a group row from a waypoint row.
				graphics.fill(x + 3, swatchY - 1, x + 17, swatchY + 13, 0xFF000000);
				graphics.fill(x + 4, swatchY, x + 16, swatchY + 12, 0xFF000000 | darker(group.color));
				graphics.fill(x - 1, swatchY + 3, x + 13, swatchY + 17, 0xFF000000);
				graphics.fill(x, swatchY + 4, x + 12, swatchY + 16, 0xFF000000 | group.color);
				int textX = x + 16 + 4;
				int maxWidth = getContentRight() - textX;
				graphics.text(font, ellipsize(font, group.name, maxWidth), textX, y + 1, group.visible ? 0xFFFFFFFF : GRAY);
				List<Waypoint> members = manager.members(group);
				Component detail = Component.translatable("waypy.gui.group_detail", members.size(),
						distanceText(centre(members), group.dimension));
				graphics.text(font, ellipsize(font, detail.getString(), maxWidth), textX, y + 11, GRAY);
			}

			private static int darker(int rgb) {
				return ((rgb >> 1) & 0x7F7F7F);
			}

			private static Vec3 centre(List<Waypoint> members) {
				double sx = 0, sy = 0, sz = 0;
				for (Waypoint w : members) {
					Vec3 c = w.centre();
					sx += c.x;
					sy += c.y;
					sz += c.z;
				}
				int n = Math.max(1, members.size());
				return new Vec3(sx / n, sy / n, sz / n);
			}

			@Override
			public Component getNarration() {
				return Component.translatable("narrator.select", group.name);
			}
		}
	}

	/** One of the 16 dye-color squares, editing a colour through {@code get}/{@code set}. The current color gets a white border. */
	private final class ColorSwatch extends AbstractButton {
		private final int color;
		private final IntSupplier get;
		private final IntConsumer set;

		ColorSwatch(DyeColor dye, IntSupplier get, IntConsumer set) {
			super(0, 0, SWATCH_SIZE, SWATCH_SIZE, Component.translatable("color.minecraft." + dye.getName()));
			this.color = dye.getTextureDiffuseColor() & 0xFFFFFF;
			this.get = get;
			this.set = set;
			setTooltip(Tooltip.create(getMessage()));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			set.accept(color);
			manager.markDirty();
			if (hexBox != null) hexBox.setValue(formatHex(color));
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

	/** A vanilla button showing the logo block's item icon followed by its name. */
	private static final class BlockIconButton extends Button {
		private final ItemStack stack;

		BlockIconButton(int width, Identifier block, OnPress onPress) {
			this(width, stackFor(block), onPress);
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

	/** Opacity 0–100% for the block-highlight style. */
	private final class OpacitySlider extends AbstractSliderButton {
		private final Waypoint waypoint;

		OpacitySlider(int width, Waypoint waypoint) {
			super(0, 0, width, DEFAULT_HEIGHT, CommonComponents.EMPTY, Math.clamp(waypoint.highlightOpacity, 0.0F, 1.0F));
			this.waypoint = waypoint;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("options.percent_value", OPACITY_LABEL, (int) Math.round(value * 100.0)));
		}

		@Override
		protected void applyValue() {
			waypoint.highlightOpacity = (float) value;
			manager.markDirty();
		}
	}
}
