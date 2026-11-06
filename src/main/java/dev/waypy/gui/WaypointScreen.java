package dev.waypy.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
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

import dev.waypy.WaypyClient;
import dev.waypy.waypoint.LogoType;
import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointManager;
import dev.waypy.waypoint.WaypointStyle;

/**
 * The Waypy menu: a waypoint list on the left (with "+ New here" / "Delete" below it) and an editor for the
 * selected waypoint on the right. Built only from vanilla widgets and sprites. Changes are applied live to the
 * {@link Waypoint} and the manager is marked dirty; everything is saved when the screen is removed.
 */
public class WaypointScreen extends Screen {
	private static final Component NEW_HERE = Component.translatable("waypy.gui.new_here");
	private static final Component DELETE = Component.translatable("waypy.gui.delete");
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
	private static final Component SHOW_TEXT_LABEL = Component.translatable("waypy.gui.show_text");
	private static final Component SHOW_TEXT_TOOLTIP = Component.translatable("waypy.gui.show_text.tooltip");
	private static final Component SETTINGS = Component.translatable("waypy.gui.settings");

	/** Height of one list row: 2px padding + two text lines / a 16px swatch + 2px padding. */
	private static final int ENTRY_HEIGHT = 24;
	/** Space under the list for the "+ New here" / "Delete" row: 5 + 20 + 3. */
	private static final int LIST_BUTTON_ROW = 28;
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
	private @Nullable Waypoint selected;

	// geometry, computed in init()
	private int listX;
	private int listWidth;
	private int editorX;
	private int editorWidth;
	private boolean twoColumns;

	private @Nullable WaypointList list;
	private @Nullable Button newHereButton;
	private @Nullable Button deleteButton;
	private @Nullable Button undoButton;
	/** The undoable action (deletion or block removal) the Undo button currently offers (null: the button is hidden). */
	private WaypointManager.@Nullable UndoEntry undoOffer;
	private boolean undoRestorable;
	private @Nullable ScrollableLayout editorScroll;
	private LinearLayout editorContent = LinearLayout.vertical();

	// editor widgets of the selected waypoint (rebuilt on selection change and on init)
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
		int listHeight = Math.max(ENTRY_HEIGHT, layout.getContentHeight() - LIST_BUTTON_ROW);
		list = addRenderableWidget(new WaypointList(minecraft, listWidth, listHeight, top));
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

		// Resolve the selection: the given/remembered waypoint if it still exists, else the manager's, else the first.
		List<Waypoint> all = manager.all();
		Waypoint initial = selected != null && all.contains(selected) ? selected : manager.selected().orElse(null);
		if (initial == null && !all.isEmpty()) initial = all.getFirst();
		selected = initial;
		if (initial != null) manager.select(initial.id);
		list.populate(all, initial);

		buildEditor();

		// Footer last so it comes last in tab order.
		layout.visitWidgets(this::addRenderableWidget);
		addSettingsButton();
	}

	/**
	 * Lays out the row under the list: {@code + New here | Delete}, or {@code + New here | Delete | Undo} while a
	 * deletion or block removal can be undone.
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
	 * Shows the Undo button while the manager has a pending undoable action (15 s): the most recent whole deletion or
	 * block removal, active only if it can be applied now. Called on init and every tick.
	 */
	private void updateUndoButton(boolean force) {
		if (undoButton == null) return;
		WaypointManager.UndoEntry offer = manager.lastUndo().orElse(null);
		boolean restorable = offer != null && manager.canUndo(offer);
		if (!force && offer == undoOffer && restorable == undoRestorable) return;
		undoOffer = offer;
		undoRestorable = restorable;
		undoButton.active = restorable;
		String key = offer instanceof WaypointManager.RemovedBlock
				? (restorable ? "waypy.gui.undo.block_tooltip" : "waypy.gui.undo.block_blocked")
				: (restorable ? "waypy.gui.undo.tooltip" : "waypy.gui.undo.blocked");
		undoButton.setTooltip(offer == null ? null : Tooltip.create(Component.translatable(key, offer.name())));
		layoutListButtons();
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

	// ------------------------------------------------------------------ editor

	/** (Re)creates the editor widgets for {@link #selected} and lays them out. */
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
		if (deleteButton != null) deleteButton.active = selected != null;

		Waypoint w = selected;
		if (w != null) {
			int full = editorWidth;
			int half = twoColumns ? (editorWidth - COLUMN_GAP) / 2 : editorWidth;

			nameBox = new EditBox(font, full, Button.DEFAULT_HEIGHT, NAME_LABEL);
			nameBox.setMaxLength(Waypoint.MAX_NAME_LENGTH);
			nameBox.setValue(w.name);
			nameBox.setResponder(value -> {
				w.setName(value);
				manager.markDirty();
			});

			int coordWidth = coordinatesInline() ? inlineCoordinateWidth() : full - font.width("X") - COORD_LABEL_GAP;
			for (Direction.Axis axis : Direction.Axis.values()) {
				coordBoxes.add(coordinateBox(w, axis, coordWidth));
			}

			for (DyeColor dye : DyeColor.values()) {
				swatches.add(new ColorSwatch(dye, w));
			}

			hexBox = new EditBox(font, hexInline() ? full - SWATCH_GRID_WIDTH - 8 : full, Button.DEFAULT_HEIGHT, HEX_LABEL);
			hexBox.setMaxLength(7);
			hexBox.setHint(Component.literal("#RRGGBB").withStyle(ChatFormatting.DARK_GRAY));
			hexBox.setValue(formatHex(w.color));
			EditBox hex = hexBox;
			hexBox.setResponder(value -> {
				Integer color = parseHex(value);
				if (color == null) {
					hex.setTextColor(INVALID_TEXT_COLOR);
					return;
				}
				hex.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
				if (w.color != color) {
					w.color = color;
					manager.markDirty();
				}
			});

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

			logoTypeButton = CycleButton.<LogoType>builder(WaypointScreen::logoTypeName, w.logoType)
					.withValues(LogoType.values())
					.create(0, 0, half, Button.DEFAULT_HEIGHT, LOGO_LABEL, (button, value) -> {
						w.logoType = value;
						manager.markDirty();
						layoutEditor();
					});

			logoBlockButton = new BlockIconButton(half, w.logoBlock, b -> minecraft.gui.setScreen(
					new BlockPickerScreen(this, w.logoBlock, id -> {
						w.logoBlock = id;
						manager.markDirty();
					})));
			logoBlockButton.setTooltip(Tooltip.create(LOGO_BLOCK_TOOLTIP));

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

			showTextButton = CycleButton.onOffBuilder(w.showText)
					.create(0, 0, half, Button.DEFAULT_HEIGHT, SHOW_TEXT_LABEL, (button, value) -> {
						w.showText = value;
						manager.markDirty();
					});
			showTextButton.setTooltip(Tooltip.create(SHOW_TEXT_TOOLTIP));
		}
		layoutEditor();
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

	/** Puts the existing editor widgets into the scrollable layout according to the current style/logo type. */
	private void layoutEditor() {
		ScrollableLayout scroll = editorScroll;
		if (scroll == null) return;
		scroll.removeChildren();

		Waypoint w = selected;
		if (w != null && nameBox != null && hexBox != null && styleButton != null && logoTypeButton != null
				&& logoBlockButton != null && opacitySlider != null && throughBlocksBox != null
				&& visibleButton != null && showTextButton != null && coordBoxes.size() == 3) {
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
			if (w.isClump()) {
				editorContent.addChild(label(Component.translatable("waypy.gui.clump_info", w.size())), s -> s.paddingTop(2));
			}
		}

		int top = layout.getHeaderHeight() + 4;
		int height = Math.max(20, layout.getContentHeight() - 8);
		scroll.setMinHeight(height);
		scroll.setMaxHeight(height);
		scroll.arrangeElements();
		scroll.setX(editorX);
		scroll.setY(top);
	}

	private StringWidget label(Component text) {
		return new StringWidget(text.copy().withStyle(ChatFormatting.GRAY), font);
	}

	// ------------------------------------------------------------------ actions

	private void onListSelect(Waypoint waypoint) {
		if (waypoint == selected) return;
		selected = waypoint;
		manager.select(waypoint.id);
		buildEditor();
	}

	private void createHere() {
		Waypoint created = WaypyClient.createWaypoint(minecraft, false);
		if (created != null) {
			selected = created;
			rebuildWidgets();
		}
	}

	/** Deletes the selected waypoint at once (no confirmation: Undo restores it for 15 s) and selects a neighbour. */
	public void deleteSelected() {
		Waypoint w = selected;
		if (w == null) return;
		List<Waypoint> all = manager.all();
		int index = all.indexOf(w);
		manager.remove(w.id);
		List<Waypoint> rest = manager.all();
		selected = rest.isEmpty() ? null : rest.get(Math.min(Math.max(index, 0), rest.size() - 1));
		manager.select(selected != null ? selected.id : null);
		rebuildWidgets();
	}

	/**
	 * Undoes the most recent undoable action (a deleted waypoint or a block removed from one), if it can be applied,
	 * and selects the restored waypoint. The editor is rebuilt, so the Style lock follows the new block count.
	 */
	public void undoDelete() {
		Optional<WaypointManager.UndoEntry> offer = manager.lastUndo();
		if (offer.isEmpty()) return;
		Waypoint restored = manager.undo(offer.get());
		if (restored == null) {
			updateUndoButton(true);
			return;
		}
		selected = restored;
		manager.select(restored.id);
		rebuildWidgets();
	}

	/** The waypoint being edited, if any. */
	public @Nullable Waypoint selectedWaypoint() {
		return selected;
	}

	/** The Undo button (null before init). For the devtest. */
	public @Nullable Button undoButton() {
		return undoButton;
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

		if (selected == null) {
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

	// ------------------------------------------------------------------ widgets

	/** The waypoint list: rows with a color swatch, the name and the distance to the player. */
	private final class WaypointList extends ObjectSelectionList<WaypointList.Entry> {
		private boolean populating;

		WaypointList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, ENTRY_HEIGHT);
		}

		void populate(List<Waypoint> waypoints, @Nullable Waypoint initial) {
			populating = true;
			clearEntries();
			Entry initialEntry = null;
			for (Waypoint w : waypoints) {
				Entry entry = new Entry(w);
				addEntry(entry);
				if (w == initial) initialEntry = entry;
			}
			setSelected(initialEntry);
			if (initialEntry != null) centerScrollOn(initialEntry);
			populating = false;
		}

		@Override
		public void setSelected(@Nullable Entry entry) {
			if (entry == null && !populating && selected != null) {
				// Keep the edited waypoint highlighted when keyboard navigation leaves the list.
				for (Entry e : children()) {
					if (e.waypoint == selected) {
						super.setSelected(e);
						return;
					}
				}
			}
			super.setSelected(entry);
			if (!populating && entry != null) onListSelect(entry.waypoint);
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

		final class Entry extends ObjectSelectionList.Entry<Entry> {
			final Waypoint waypoint;

			Entry(Waypoint waypoint) {
				this.waypoint = waypoint;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				int x = getContentX();
				int y = getContentY();
				int swatchY = getContentYMiddle() - 8;
				graphics.fill(x, swatchY, x + 16, swatchY + 16, 0xFF000000);
				graphics.fill(x + 1, swatchY + 1, x + 15, swatchY + 15, 0xFF000000 | waypoint.color);

				int textX = x + 16 + 4;
				int maxWidth = getContentRight() - textX;
				graphics.text(font, ellipsize(font, waypoint.name, maxWidth), textX, y + 1, waypoint.visible ? 0xFFFFFFFF : GRAY);
				graphics.text(font, ellipsize(font, detailText().getString(), maxWidth), textX, y + 11, GRAY);
			}

			/** Distance (or dimension), plus the block count for clumps: {@code 24m · 12 blocks}. */
			private Component detailText() {
				Component distance = distanceText();
				return waypoint.isClump() ? Component.translatable("waypy.gui.block_count", distance, waypoint.size()) : distance;
			}

			private Component distanceText() {
				LocalPlayer player = minecraft.player;
				if (player == null || !player.level().dimension().equals(waypoint.dimension)) {
					return Component.literal(waypoint.dimension.identifier().toString());
				}
				long metres = Math.round(waypoint.centre().distanceTo(player.position()));
				return Component.translatable("waypy.gui.distance", metres);
			}

			@Override
			public Component getNarration() {
				return Component.translatable("narrator.select", waypoint.name);
			}
		}
	}

	/** One of the 16 dye-color squares. Selected (current color) gets a white border. */
	private final class ColorSwatch extends AbstractButton {
		private final int color;
		private final Waypoint waypoint;

		ColorSwatch(DyeColor dye, Waypoint waypoint) {
			super(0, 0, SWATCH_SIZE, SWATCH_SIZE, Component.translatable("color.minecraft." + dye.getName()));
			this.color = dye.getTextureDiffuseColor() & 0xFFFFFF;
			this.waypoint = waypoint;
			setTooltip(Tooltip.create(getMessage()));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			waypoint.color = color;
			manager.markDirty();
			if (hexBox != null) hexBox.setValue(formatHex(color));
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			boolean current = (waypoint.color & 0xFFFFFF) == color;
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
