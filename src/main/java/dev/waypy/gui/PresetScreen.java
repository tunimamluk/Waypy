package dev.waypy.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

import dev.waypy.WaypyClient;
import dev.waypy.config.PopupTab;
import dev.waypy.preset.Layout;
import dev.waypy.preset.LayoutManager;
import dev.waypy.preset.Preset;
import dev.waypy.preset.PresetManager;
import dev.waypy.waypoint.LogoType;
import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointManager;
import dev.waypy.waypoint.WaypointStyle;

/**
 * The Presets popup (default key H; the unbound Open Layouts key opens it on the Layouts tab), styled like vanilla's
 * Game Menu ({@code PauseScreen}): no panel, a centred column of 204-wide vanilla buttons over the blurred, dimmed
 * world.
 *
 * <p>Two tabs, <b>Presets</b> and <b>Layouts</b> (two tab buttons at the top of the column; Ctrl+Tab switches), share
 * the list view: the same search box, number keys and right-click, over display presets ({@link Preset}) or layout
 * presets ({@link Layout}). Switching tabs clears the search. In the Layouts tab "+" is inactive (layouts are created
 * from the menu's group context menu) and right-click opens a small rename form (Name, Save, Delete, Cancel).
 *
 * <p><b>List</b>: the tab buttons with a small "+" button at the column's right edge, a grey
 * {@code Applies to: <waypoint>} line, a search box and one button per entry ({@code 1  Home}, with the logo icon on
 * the left and a colour swatch on the right), in a scrollable column. The target waypoint, and the exact block that
 * found it (where a layout's anchor goes), are fixed when the popup opens ({@link WaypyClient#openPresets}); the popup
 * never opens without them.
 * <ul>
 *   <li>Click an entry, or press its number key (1–9) while the search box is not focused: apply it and close
 *       (silently, no message). A preset is applied to the target ({@link Preset#applyTo}); a layout is stamped with
 *       its anchor on the target block ({@link Layout#applyAt}).</li>
 *   <li>Search: unfocused when the popup opens. Typing a letter (any printable character but a digit) focuses it and
 *       types the character. Filters by name, case-insensitive; the buttons keep their real numbers. Enter in the box
 *       applies the first match. Esc in the box clears it (and unfocuses it) if it has text, else closes.</li>
 *   <li>Right-click an entry: edit it in the inline form (with Delete).</li>
 *   <li>"+" (Presets tab): the inline form for a new preset, copied from the target.</li>
 * </ul>
 *
 * <p><b>Preset form</b> (the same screen, switched over): Name, colour (swatches and hex), Style, Logo (Block / None and
 * the block button, which opens {@link BlockPickerScreen} and comes back here with the edits kept), Opacity and Render
 * through blocks (Block Selection only), Text; then Save and Cancel (and Delete when editing). The form edits a copy
 * ({@link #draft()}); Save stores it ({@link PresetManager#add} or {@link PresetManager#replace}) and returns to the
 * list, Cancel (or Esc) just returns. <b>Layout form</b>: Name, then Save, Delete and Cancel.
 */
public class PresetScreen extends Screen {
	/** Which view the popup shows. */
	public enum Mode {
		LIST,
		/** The preset form (new or edit). */
		FORM,
		/** The layout rename form. */
		LAYOUT_FORM
	}

	private static final Component TITLE = Component.translatable("waypy.presets.title");
	private static final Component SEARCH = Component.translatable("waypy.presets.search");
	private static final Component EMPTY = Component.translatable("waypy.presets.empty");
	private static final Component NO_MATCH = Component.translatable("waypy.presets.no_match");
	private static final Component LAYOUTS_SEARCH = Component.translatable("waypy.layouts.search");
	private static final Component LAYOUTS_NO_MATCH = Component.translatable("waypy.layouts.no_match");
	private static final Component FORM_NEW = Component.translatable("waypy.presets.form.new");
	private static final Component SAVE = Component.translatable("waypy.presets.save");
	private static final Component DELETE = Component.translatable("waypy.gui.delete");
	private static final Component DELETE_TOOLTIP = Component.translatable("waypy.presets.delete.tooltip");
	private static final Component LAYOUT_DELETE_TOOLTIP = Component.translatable("waypy.layouts.delete.tooltip");
	private static final Component NAME_LABEL = Component.translatable("waypy.gui.name");
	private static final Component COLOR_LABEL = Component.translatable("waypy.gui.color");
	private static final Component STYLE_LABEL = Component.translatable("waypy.gui.style");
	private static final Component LOGO_LABEL = Component.translatable("waypy.gui.logo");
	private static final Component LOGO_BLOCK_TOOLTIP = Component.translatable("waypy.gui.logo_block.tooltip");
	private static final Component THROUGH_BLOCKS_LABEL = Component.translatable("waypy.gui.through_blocks");
	private static final Component SHOW_TEXT_LABEL = Component.translatable("waypy.gui.show_text");
	private static final Component SHOW_TEXT_TOOLTIP = Component.translatable("waypy.gui.show_text.tooltip");
	/** Vanilla's hovered button sprite: the selected tab keeps it, with an underline under its label. */
	private static final Identifier TAB_SELECTED_SPRITE = Identifier.withDefaultNamespace("widget/button_highlighted");

	/** Width of the button column, as in the Game Menu. */
	public static final int COLUMN_WIDTH = 204;
	private static final int ROW = Button.DEFAULT_HEIGHT;
	private static final int GAP = 4;
	/** List mode, from the top of the column: tab row (20), "Applies to" line, search box, then the entries. */
	private static final int APPLIES_Y = 25;
	private static final int SEARCH_Y = 38;
	private static final int LIST_Y = SEARCH_Y + ROW + 8;
	/** Space kept free below the column. */
	private static final int BOTTOM_MARGIN = 8;
	/** Highest top of the column (the Game Menu's title sits at 40). */
	private static final int MAX_TOP = 40;
	/** Width the scroll area reserves on each side of its content (4 spacing + 6 bar). */
	private static final int SCROLL_RESERVE = 10;
	/** Form mode: widest form, and the narrowest that still uses two columns. */
	private static final int FORM_MAX_WIDTH = 310;
	private static final int FORM_TWO_COLUMN_MIN = 250;
	private static final int FORM_MARGIN = 6;
	private static final int FORM_COLUMN_GAP = 10;
	/** Form mode, from the top: title row (20), the grey sub-line, then the fields. */
	private static final int FORM_FIELDS_Y = 36;
	/** Layout form, from the top: the "Name" label, the box, the buttons. */
	private static final int LAYOUT_NAME_Y = FORM_FIELDS_Y + 12;
	private static final int LAYOUT_BUTTONS_Y = LAYOUT_NAME_Y + ROW + 8;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int WHITE = 0xFFFFFFFF;
	/** A character typed right after a consumed key press (number keys, the held open key) belongs to it. */
	private static final long CHAR_AFTER_KEY_MILLIS = 100L;

	private final WaypointManager waypoints = WaypointManager.get();
	private final PresetManager presets = PresetManager.get();
	private final LayoutManager layouts = LayoutManager.get();
	/** The waypoint presets apply to and layouts join, found when the popup opened. */
	private final Waypoint target;
	/** The block that targeting found (stood on, or looked at): a layout's anchor lands here. */
	private final BlockPos targetBlock;
	/** The key that opened the popup (null if opened otherwise): while held, its key repeats are ignored. */
	private final @Nullable KeyMapping openKey;
	private boolean ignoreOpenKey;
	private long swallowCharAt = -1;
	private PopupTab tab;
	private Mode mode = Mode.LIST;
	/** Counts {@link #init()} calls, so a click that switched the view can drop the focus on the removed widget. */
	private int builds;
	/** What the last applied layout did (for the devtest), or null. */
	private Layout.@Nullable ApplyResult lastLayoutResult;

	// list state (kept across rebuilds; the search is cleared when the tab changes)
	private String query = "";
	private boolean searchFocused;
	private int top;
	private int columnX;
	private @Nullable EditBox searchBox;
	private @Nullable Button plusButton;
	private @Nullable TabButton presetsTabButton;
	private @Nullable TabButton layoutsTabButton;
	private @Nullable ScrollableLayout listScroll;
	private LinearLayout listContent = LinearLayout.vertical();
	private final List<EntryButton> entryButtons = new ArrayList<>();

	// form state: the edited copy and its number (0 = a new preset), kept while the block picker is open
	private @Nullable Preset draft;
	private int editingNumber;
	/** Layout form: the name being edited (applied on Save). */
	private String layoutDraftName = "";
	private Component formSubtitle = CommonComponents.EMPTY;
	private int formWidth;
	private @Nullable EditBox nameBox;
	private @Nullable ColorPicker colorPicker;
	private @Nullable CycleButton<WaypointStyle> styleButton;
	private @Nullable CycleButton<LogoType> logoTypeButton;
	private @Nullable BlockIconButton logoBlockButton;
	private @Nullable OpacitySlider opacitySlider;
	private @Nullable Checkbox throughBlocksBox;
	private @Nullable CycleButton<Boolean> showTextButton;
	private @Nullable ScrollableLayout formScroll;
	private LinearLayout formContent = LinearLayout.vertical();
	private @Nullable Button saveButton;
	private @Nullable Button cancelButton;
	private @Nullable Button deleteButton;

	/**
	 * The popup on the Presets tab, with the target's anchor as the target block.
	 *
	 * @param target   the waypoint presets apply to
	 * @param fromKey  opened by the presets key: while that key stays held, its key repeats are ignored
	 */
	public PresetScreen(Waypoint target, boolean fromKey) {
		this(target, target.anchor(), PopupTab.PRESETS, fromKey ? WaypyClient.presetsKey : null);
	}

	/**
	 * @param target      the waypoint presets apply to (and layouts join)
	 * @param targetBlock the block targeting found: a layout's anchor lands on it
	 * @param tab         the tab to open on
	 * @param openKey     the key that opened the popup, or null: while it stays held, its key repeats are ignored
	 */
	public PresetScreen(Waypoint target, BlockPos targetBlock, PopupTab tab, @Nullable KeyMapping openKey) {
		super(TITLE);
		this.target = target;
		this.targetBlock = targetBlock.immutable();
		this.tab = tab;
		this.openKey = openKey;
		this.ignoreOpenKey = openKey != null && openKey.isDown();
	}

	// ------------------------------------------------------------------ setup

	@Override
	protected void init() {
		builds++;
		entryButtons.clear();
		searchBox = null;
		plusButton = null;
		presetsTabButton = layoutsTabButton = null;
		listScroll = null;
		nameBox = null;
		formScroll = null;
		saveButton = cancelButton = deleteButton = null;
		if (mode == Mode.FORM && draft != null) {
			initForm(draft);
		} else if (mode == Mode.LAYOUT_FORM && layouts.byNumber(editingNumber) != null) {
			initLayoutForm();
		} else {
			mode = Mode.LIST;
			initList();
		}
	}

	@Override
	protected void rebuildWidgets() {
		// Remember whether the list's search box had the focus (a resize, or a trip to the form and back keeps it).
		if (searchBox != null) searchFocused = searchBox.isFocused();
		super.rebuildWidgets();
	}

	@Override
	protected void setInitialFocus() {
		// The search box starts unfocused (letters focus it, digits apply entries); it keeps its focus across rebuilds.
		if (mode == Mode.LIST && searchFocused && searchBox != null) setFocused(searchBox);
		// The forms start in the name box (Enter there saves).
		if (mode != Mode.LIST && nameBox != null) setFocused(nameBox);
	}

	private void initList() {
		// Measured with the longer of the two lists, so neither filtering nor switching tabs moves the column.
		int count = Math.max(presets.size(), layouts.size());
		int listHeight = count == 0 ? ROW : count * (ROW + GAP) - GAP;
		top = Math.clamp((height - (LIST_Y + listHeight)) / 4, BOTTOM_MARGIN, MAX_TOP);
		columnX = (width - COLUMN_WIDTH) / 2;

		// Tabs: two equal buttons left of the "+" (where the Game Menu's title would be).
		int tabsWidth = COLUMN_WIDTH - ROW - GAP;
		int tabWidth = (tabsWidth - GAP) / 2;
		presetsTabButton = addRenderableWidget(new TabButton(PopupTab.PRESETS, columnX, top, tabWidth));
		layoutsTabButton = addRenderableWidget(new TabButton(PopupTab.LAYOUTS, columnX + tabWidth + GAP, top,
				tabsWidth - tabWidth - GAP));

		Button plus = Button.builder(Component.literal("+"), b -> openNewForm())
				.bounds(columnX + COLUMN_WIDTH - ROW, top, ROW, ROW)
				.build();
		if (tab == PopupTab.PRESETS) {
			plus.setTooltip(Tooltip.create(Component.translatable("waypy.presets.new.tooltip", target.name)));
		} else {
			plus.active = false;
			plus.setTooltip(Tooltip.create(Component.translatable("waypy.layouts.new.tooltip", WaypyClient.menuKey.getTranslatedKeyMessage())));
		}
		plusButton = addRenderableWidget(plus);

		Component hint = tab == PopupTab.PRESETS ? SEARCH : LAYOUTS_SEARCH;
		EditBox search = new EditBox(font, columnX, top + SEARCH_Y, COLUMN_WIDTH, ROW, hint);
		search.setHint(hint.copy().setStyle(EditBox.SEARCH_HINT_STYLE));
		search.setMaxLength(Preset.MAX_NAME_LENGTH);
		search.setValue(query);
		search.setResponder(value -> {
			query = value;
			refreshList();
		});
		searchBox = addRenderableWidget(search);

		listContent = LinearLayout.vertical().spacing(GAP);
		int maxHeight = Math.max(ROW, height - (top + LIST_Y) - BOTTOM_MARGIN);
		listScroll = new ScrollableLayout(minecraft, listContent, maxHeight, ScrollableLayout.ReserveStrategy.BOTH);
		listScroll.visitWidgets(this::addRenderableWidget);
		refreshList();
	}

	/** Rebuilds the entry buttons for the tab and the search (case-insensitive, by name); numbers stay the real ones. */
	private void refreshList() {
		ScrollableLayout scroll = listScroll;
		if (scroll == null) return;
		scroll.removeChildren();
		entryButtons.clear();
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (tab == PopupTab.PRESETS) {
			List<Preset> all = presets.all();
			for (int i = 0; i < all.size(); i++) {
				Preset preset = all.get(i);
				if (!q.isEmpty() && !preset.name.toLowerCase(Locale.ROOT).contains(q)) continue;
				entryButtons.add(new PresetButton(preset, i + 1));
			}
		} else {
			List<Layout> all = layouts.all();
			for (int i = 0; i < all.size(); i++) {
				Layout layout = all.get(i);
				if (!q.isEmpty() && !layout.name.toLowerCase(Locale.ROOT).contains(q)) continue;
				entryButtons.add(new LayoutButton(layout, i + 1));
			}
		}
		for (EntryButton button : entryButtons) listContent.addChild(button);
		listContent.arrangeElements();
		scroll.setMaxHeight(Math.max(ROW, height - (top + LIST_Y) - BOTTOM_MARGIN));
		scroll.arrangeElements();
		scroll.setX(columnX - SCROLL_RESERVE);
		scroll.setY(top + LIST_Y);
	}

	// ------------------------------------------------------------------ tabs

	/**
	 * Shows {@code newTab} (the list view; an open form is dropped). Switching clears and unfocuses the search. A no-op
	 * for the current tab in the list.
	 */
	public void selectTab(PopupTab newTab) {
		if (newTab == tab && mode == Mode.LIST) return;
		tab = newTab;
		query = "";
		searchFocused = false;
		if (searchBox != null) searchBox.setFocused(false);
		searchBox = null; // so rebuildWidgets doesn't remember its focus
		draft = null;
		editingNumber = 0;
		mode = Mode.LIST;
		rebuildWidgets();
	}

	// ------------------------------------------------------------------ preset form

	/** "+" (Presets tab): the inline form for a new preset, copied from the target (named after it). */
	public void openNewForm() {
		if (tab != PopupTab.PRESETS) return;
		draft = Preset.from(target, target.name);
		formSubtitle = Component.translatable("waypy.presets.form.copied", target.name);
		editingNumber = 0;
		mode = Mode.FORM;
		rebuildWidgets();
	}

	/** Right-click on a preset: the inline form for it, with Delete. No-op for an unknown number. */
	public void openEditForm(int number) {
		Preset preset = presets.byNumber(number);
		if (preset == null) return;
		draft = preset.copy();
		editingNumber = number;
		formSubtitle = Component.translatable("waypy.presets.form.shared");
		mode = Mode.FORM;
		rebuildWidgets();
	}

	/**
	 * Save in either form. Preset form: a new preset gets the next number, an edited one keeps its number. Layout form:
	 * renames the layout (a blank name saves as {@code Layout <n>}). Back to the list.
	 */
	public void saveForm() {
		if (mode == Mode.LAYOUT_FORM) {
			String name = layoutDraftName.isBlank()
					? Component.translatable("waypy.layouts.default_name", editingNumber).getString()
					: layoutDraftName;
			layouts.rename(editingNumber, name);
			backToList();
			return;
		}
		Preset p = draft;
		if (mode != Mode.FORM || p == null) return;
		if (p.name.isBlank()) {
			int number = editingNumber > 0 ? editingNumber : presets.nextNumber();
			p.setName(Component.translatable("waypy.presets.default_name", number).getString());
		}
		if (editingNumber > 0) {
			presets.replace(editingNumber, p);
		} else {
			presets.add(p);
		}
		backToList();
	}

	/** Cancel (or Esc in a form): back to the list without saving. */
	public void cancelForm() {
		if (mode == Mode.LIST) return;
		backToList();
	}

	/**
	 * Delete (edit forms only): removes the preset or layout being edited; the later ones move up a number. Back to the
	 * list.
	 */
	public void deleteEdited() {
		if (mode == Mode.LIST || editingNumber <= 0) return;
		if (mode == Mode.LAYOUT_FORM) {
			layouts.remove(editingNumber);
		} else {
			presets.remove(editingNumber);
		}
		backToList();
	}

	private void backToList() {
		draft = null;
		editingNumber = 0;
		mode = Mode.LIST;
		rebuildWidgets();
	}

	private void initForm(Preset p) {
		formWidth = Math.min(FORM_MAX_WIDTH, width - 2 * FORM_MARGIN - 2 * SCROLL_RESERVE);
		boolean twoColumns = formWidth >= FORM_TWO_COLUMN_MIN;
		int half = twoColumns ? (formWidth - FORM_COLUMN_GAP) / 2 : formWidth;

		nameBox = new EditBox(font, formWidth, ROW, NAME_LABEL);
		nameBox.setMaxLength(Preset.MAX_NAME_LENGTH);
		nameBox.setValue(p.name);
		nameBox.setResponder(p::setName);

		colorPicker = new ColorPicker(font, ColorPicker.hexWidth(formWidth), () -> p.color, color -> p.color = color);

		styleButton = CycleButton.<WaypointStyle>builder(WaypointScreen::styleName, p.style)
				.withValues(WaypointStyle.values())
				.create(0, 0, half, ROW, STYLE_LABEL, (button, value) -> {
					p.style = value;
					layoutForm();
				});
		logoTypeButton = CycleButton.<LogoType>builder(WaypointScreen::logoTypeName, p.logoType)
				.withValues(LogoType.values())
				.create(0, 0, half, ROW, LOGO_LABEL, (button, value) -> {
					p.logoType = value;
					layoutForm();
				});
		logoBlockButton = new BlockIconButton(half, p.logoBlock,
				b -> minecraft.gui.setScreen(new BlockPickerScreen(this, p.logoBlock, id -> p.logoBlock = id)));
		logoBlockButton.setTooltip(Tooltip.create(LOGO_BLOCK_TOOLTIP));
		opacitySlider = new OpacitySlider(half, p.highlightOpacity, value -> p.highlightOpacity = value);
		throughBlocksBox = Checkbox.builder(THROUGH_BLOCKS_LABEL, font)
				.maxWidth(half)
				.selected(p.throughBlocks)
				.onValueChange((box, value) -> p.throughBlocks = value)
				.build();
		showTextButton = CycleButton.onOffBuilder(p.showText)
				.create(0, 0, half, ROW, SHOW_TEXT_LABEL, (button, value) -> p.showText = value);
		showTextButton.setTooltip(Tooltip.create(SHOW_TEXT_TOOLTIP));

		formContent = LinearLayout.vertical().spacing(GAP);
		formScroll = new ScrollableLayout(minecraft, formContent, 100, ScrollableLayout.ReserveStrategy.BOTH);
		formScroll.visitWidgets(this::addRenderableWidget);

		boolean editing = editingNumber > 0;
		saveButton = addRenderableWidget(Button.builder(SAVE, b -> saveForm()).size(0, ROW).build());
		cancelButton = addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> cancelForm()).size(0, ROW).build());
		if (editing) {
			deleteButton = addRenderableWidget(Button.builder(DELETE, b -> deleteEdited()).size(0, ROW)
					.tooltip(Tooltip.create(DELETE_TOOLTIP)).build());
		}
		layoutForm();
	}

	/** Puts the form widgets into the scroll area (the Block Selection rows only for that style) and places the buttons. */
	private void layoutForm() {
		ScrollableLayout scroll = formScroll;
		Preset p = draft;
		if (scroll == null || p == null || nameBox == null || colorPicker == null || styleButton == null || logoTypeButton == null
				|| logoBlockButton == null || opacitySlider == null || throughBlocksBox == null || showTextButton == null
				|| saveButton == null || cancelButton == null) {
			return;
		}
		boolean twoColumns = formWidth >= FORM_TWO_COLUMN_MIN;
		scroll.removeChildren();
		formContent.addChild(label(NAME_LABEL));
		formContent.addChild(nameBox);
		formContent.addChild(label(COLOR_LABEL), s -> s.paddingTop(2));
		if (ColorPicker.hexInline(formWidth)) {
			LinearLayout colorRow = LinearLayout.horizontal().spacing(ColorPicker.HEX_GAP);
			colorRow.addChild(colorPicker.grid(), s -> s.alignVerticallyMiddle());
			colorRow.addChild(colorPicker.hexBox(), s -> s.alignVerticallyMiddle());
			formContent.addChild(colorRow);
		} else {
			formContent.addChild(colorPicker.grid());
			formContent.addChild(colorPicker.hexBox());
		}
		boolean highlight = p.style == WaypointStyle.BLOCK_HIGHLIGHT;
		boolean blockLogo = p.logoType == LogoType.BLOCK;
		if (twoColumns) {
			formContent.addChild(row(styleButton, logoTypeButton), s -> s.paddingTop(4));
			if (blockLogo) formContent.addChild(row(SpacerElement.width(styleButton.getWidth()), logoBlockButton));
			if (highlight) {
				LinearLayout row = LinearLayout.horizontal().spacing(FORM_COLUMN_GAP);
				row.addChild(opacitySlider);
				row.addChild(throughBlocksBox, s -> s.alignVerticallyMiddle());
				formContent.addChild(row);
			}
			formContent.addChild(showTextButton);
		} else {
			formContent.addChild(styleButton, s -> s.paddingTop(4));
			formContent.addChild(logoTypeButton);
			if (blockLogo) formContent.addChild(logoBlockButton);
			if (highlight) {
				formContent.addChild(opacitySlider);
				formContent.addChild(throughBlocksBox);
			}
			formContent.addChild(showTextButton);
		}
		formContent.arrangeElements();

		// Vertically: the whole form (title, sub-line, fields, buttons) a quarter down the free space, like the list;
		// the fields scroll when the screen is too low.
		int buttonsBlock = 8 + ROW;
		int fieldsMax = Math.max(ROW, height - 2 * BOTTOM_MARGIN - FORM_FIELDS_Y - buttonsBlock);
		int fieldsHeight = Math.min(formContent.getHeight(), fieldsMax);
		top = Math.clamp((height - (FORM_FIELDS_Y + fieldsHeight + buttonsBlock)) / 4, BOTTOM_MARGIN, MAX_TOP);
		scroll.setMinHeight(fieldsHeight);
		scroll.setMaxHeight(fieldsHeight);
		scroll.arrangeElements();
		scroll.setX((width - formWidth) / 2 - SCROLL_RESERVE);
		scroll.setY(top + FORM_FIELDS_Y);

		placeFormButtons(top + FORM_FIELDS_Y + fieldsHeight + 8);
	}

	/** Save | (Delete |) Cancel in one row of {@link #formWidth}, centred, at {@code y}. */
	private void placeFormButtons(int y) {
		if (saveButton == null || cancelButton == null) return;
		Button[] buttons = deleteButton != null
				? new Button[] {saveButton, deleteButton, cancelButton}
				: new Button[] {saveButton, cancelButton};
		int each = (formWidth - GAP * (buttons.length - 1)) / buttons.length;
		int x = (width - formWidth) / 2;
		int last = formWidth - (each + GAP) * (buttons.length - 1); // the last one takes the rounding remainder
		for (int i = 0; i < buttons.length; i++) {
			int w = i == buttons.length - 1 ? last : each;
			buttons[i].setRectangle(w, ROW, x, y);
			x += w + GAP;
		}
	}

	private LinearLayout row(LayoutElement left, LayoutElement right) {
		LinearLayout row = LinearLayout.horizontal().spacing(FORM_COLUMN_GAP);
		row.addChild(left);
		row.addChild(right);
		return row;
	}

	private StringWidget label(Component text) {
		return new StringWidget(text.copy().withStyle(ChatFormatting.GRAY), font);
	}

	// ------------------------------------------------------------------ layout form

	/** Right-click on a layout: the small rename form (Name, Save, Delete, Cancel). No-op for an unknown number. */
	public void openLayoutForm(int number) {
		Layout layout = layouts.byNumber(number);
		if (layout == null) return;
		editingNumber = number;
		layoutDraftName = layout.name;
		draft = null;
		mode = Mode.LAYOUT_FORM;
		rebuildWidgets();
	}

	private void initLayoutForm() {
		formWidth = Math.min(COLUMN_WIDTH, width - 2 * FORM_MARGIN);
		int contentHeight = LAYOUT_BUTTONS_Y + ROW;
		top = Math.clamp((height - contentHeight) / 4, BOTTOM_MARGIN, MAX_TOP);
		int x = (width - formWidth) / 2;
		StringWidget nameLabel = label(NAME_LABEL);
		nameLabel.setPosition(x, top + FORM_FIELDS_Y);
		addRenderableWidget(nameLabel);
		nameBox = new EditBox(font, x, top + LAYOUT_NAME_Y, formWidth, ROW, NAME_LABEL);
		nameBox.setMaxLength(Layout.MAX_NAME_LENGTH);
		nameBox.setValue(layoutDraftName);
		nameBox.setResponder(value -> layoutDraftName = value);
		addRenderableWidget(nameBox);
		saveButton = addRenderableWidget(Button.builder(SAVE, b -> saveForm()).size(0, ROW).build());
		deleteButton = addRenderableWidget(Button.builder(DELETE, b -> deleteEdited()).size(0, ROW)
				.tooltip(Tooltip.create(LAYOUT_DELETE_TOOLTIP)).build());
		cancelButton = addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> cancelForm()).size(0, ROW).build());
		placeFormButtons(top + LAYOUT_BUTTONS_Y);
	}

	// ------------------------------------------------------------------ applying

	/**
	 * Applies {@code preset} to the target (see {@link Preset#applyTo}), marks the waypoints dirty and closes the
	 * popup, without any message. Does nothing if the target was deleted meanwhile.
	 *
	 * @return true if it was applied
	 */
	public boolean applyPreset(Preset preset) {
		if (waypoints.byId(target.id).isEmpty()) return false;
		preset.applyTo(target);
		waypoints.markDirty();
		onClose();
		return true;
	}

	/**
	 * Stamps {@code layout} with its anchor on the target block, the target joining it (see {@link Layout#applyAt}),
	 * and closes the popup, without any message. Does nothing if the target was deleted meanwhile.
	 *
	 * @return true if it was applied
	 */
	public boolean applyLayout(Layout layout) {
		if (waypoints.byId(target.id).isEmpty()) return false;
		boolean sameLevel = minecraft.level != null && minecraft.level.dimension().equals(target.dimension);
		Layout.ApplyResult result = layout.applyAt(waypoints, target, targetBlock, sameLevel ? minecraft.level : null);
		if (result == null) return false;
		lastLayoutResult = result;
		onClose();
		return true;
	}

	/** A number key: applies entry {@code number} of the current tab (if it exists). */
	public boolean applyNumber(int number) {
		if (tab == PopupTab.LAYOUTS) {
			Layout layout = layouts.byNumber(number);
			return layout != null && applyLayout(layout);
		}
		Preset preset = presets.byNumber(number);
		return preset != null && applyPreset(preset);
	}

	/** Enter in the search box: applies the first entry the search shows. */
	public boolean applyFirstMatch() {
		return !entryButtons.isEmpty() && entryButtons.getFirst().apply();
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (ignoreOpenKey && openKey != null && openKey.matches(event)) {
			swallowNextChar(); // a key repeat of the key that opened the popup
			return true;
		}
		if (mode != Mode.LIST) {
			if (event.isEscape()) {
				cancelForm();
				return true;
			}
			if (event.isConfirmation() && nameBox != null && nameBox.isFocused()) {
				saveForm();
				return true;
			}
			return super.keyPressed(event);
		}
		if (event.key() == GLFW.GLFW_KEY_TAB && event.hasControlDown()) {
			selectTab(tab == PopupTab.PRESETS ? PopupTab.LAYOUTS : PopupTab.PRESETS);
			return true;
		}
		EditBox search = searchBox;
		if (search != null && search.isFocused()) {
			if (event.isEscape()) {
				if (search.getValue().isEmpty()) {
					onClose();
				} else {
					search.setValue("");
					setFocused(null);
				}
				return true;
			}
			if (event.isConfirmation()) {
				applyFirstMatch();
				return true;
			}
			return super.keyPressed(event); // the box types digits too
		}
		int digit = digit(event);
		if (digit >= 0 && !event.hasShiftDown() && !event.hasControlDown() && !event.hasAltDown()) {
			swallowNextChar(); // the character of this key (e.g. "&" on AZERTY) must not start a search
			if (digit > 0) applyNumber(digit);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (openKey != null && openKey.matches(event)) ignoreOpenKey = false;
		return super.keyReleased(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (swallowCharAt >= 0) {
			boolean recent = Util.getMillis() - swallowCharAt <= CHAR_AFTER_KEY_MILLIS;
			swallowCharAt = -1;
			if (recent) return true;
		}
		EditBox search = searchBox;
		if (mode == Mode.LIST && search != null && !search.isFocused()) {
			if (Character.isDigit(event.codepoint())) return true; // digits apply entries (keyPressed) while unfocused
			if (event.isAllowedChatCharacter()) {
				setFocused(search);
				return search.charTyped(event);
			}
		}
		return super.charTyped(event);
	}

	private void swallowNextChar() {
		swallowCharAt = Util.getMillis();
	}

	/** 0–9 for the number row and the keypad, else -1. */
	private static int digit(KeyEvent event) {
		int key = event.key();
		if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return key - GLFW.GLFW_KEY_KP_0;
		return event.getDigit();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (mode == Mode.LIST && event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			EntryButton button = entryButtonAt(event.x(), event.y());
			if (button != null) {
				button.playDownSound(minecraft.getSoundManager());
				button.edit();
				return true;
			}
		}
		int before = builds;
		boolean handled = super.mouseClicked(event, doubleClick);
		if (builds != before) {
			// The click switched the view ("+", a tab, Save, Cancel, Delete): the clicked button is gone, so don't keep
			// it focused; focus what the new view starts with instead.
			setDragging(false);
			setFocused(null);
			setInitialFocus();
		}
		return handled;
	}

	/** The entry button under the mouse, inside the visible part of the scrolled list (inactive ones included). */
	private @Nullable EntryButton entryButtonAt(double x, double y) {
		ScrollableLayout scroll = listScroll;
		if (scroll == null) return null;
		ScreenRectangle area = new ScreenRectangle(scroll.getX(), scroll.getY(), scroll.getWidth(), scroll.getHeight());
		if (!area.containsPoint((int) x, (int) y)) return null;
		for (EntryButton b : entryButtons) {
			if (x >= b.getX() && x < b.getRight() && y >= b.getY() && y < b.getBottom()) return b;
		}
		return null;
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int centerX = width / 2;
		if (mode == Mode.FORM) {
			Component heading = editingNumber > 0 ? Component.translatable("waypy.presets.form.edit", editingNumber) : FORM_NEW;
			graphics.centeredText(font, heading, centerX, top + 6, WHITE);
			graphics.centeredText(font, formSubtitle, centerX, top + 22, GRAY);
			return;
		}
		if (mode == Mode.LAYOUT_FORM) {
			graphics.centeredText(font, Component.translatable("waypy.layouts.form.edit", editingNumber), centerX, top + 6, WHITE);
			graphics.centeredText(font, Component.translatable("waypy.layouts.form.shared"), centerX, top + 22, GRAY);
			return;
		}
		Component applies = Component.translatable("waypy.presets.applies_to", Component.literal(target.name).withStyle(ChatFormatting.WHITE));
		graphics.centeredText(font, font.split(applies, width - 8).getFirst(), centerX, top + APPLIES_Y, GRAY);
		if (entryButtons.isEmpty()) {
			Component hint;
			if (tab == PopupTab.PRESETS) {
				hint = presets.size() == 0 ? EMPTY : NO_MATCH;
			} else {
				hint = layouts.size() == 0
						? Component.translatable("waypy.layouts.empty", WaypyClient.menuKey.getTranslatedKeyMessage())
						: LAYOUTS_NO_MATCH;
			}
			List<FormattedCharSequence> lines = font.split(hint, Math.max(COLUMN_WIDTH, width - 16));
			int y = top + LIST_Y + 6;
			for (FormattedCharSequence line : lines) {
				graphics.centeredText(font, line, centerX, y, GRAY);
				y += font.lineHeight + 1;
			}
		}
	}

	// ------------------------------------------------------------------ access for the devtest

	public Mode mode() {
		return mode;
	}

	/** The tab shown. */
	public PopupTab tab() {
		return tab;
	}

	/** The waypoint presets apply to (fixed when the popup opened). */
	public Waypoint target() {
		return target;
	}

	/** The block targeting found (fixed when the popup opened): a layout's anchor lands here. */
	public BlockPos targetBlock() {
		return targetBlock;
	}

	/** What the last applied layout did, or null. */
	public Layout.@Nullable ApplyResult lastLayoutResult() {
		return lastLayoutResult;
	}

	/** The entry buttons the list shows for the current tab and search, in order (empty in a form). */
	public List<Button> presetButtons() {
		return List.copyOf(entryButtons);
	}

	/** The entry number shown on each visible button, in order. */
	public List<Integer> visibleNumbers() {
		List<Integer> out = new ArrayList<>();
		for (EntryButton b : entryButtons) out.add(b.number);
		return out;
	}

	public @Nullable EditBox searchBox() {
		return searchBox;
	}

	public @Nullable Button plusButton() {
		return plusButton;
	}

	/** The tab button for {@code which} (null outside the list). */
	public @Nullable Button tabButton(PopupTab which) {
		return which == PopupTab.PRESETS ? presetsTabButton : layoutsTabButton;
	}

	/** The preset the form edits (a copy until Save), or null in the list. */
	public @Nullable Preset draft() {
		return draft;
	}

	/** The number of the preset or layout the form edits; 0 for a new preset. */
	public int editingNumber() {
		return editingNumber;
	}

	public @Nullable EditBox nameBox() {
		return nameBox;
	}

	public @Nullable Button deleteButton() {
		return deleteButton;
	}

	public @Nullable Button saveButton() {
		return saveButton;
	}

	public @Nullable Button cancelButton() {
		return cancelButton;
	}

	public @Nullable CycleButton<WaypointStyle> styleButton() {
		return styleButton;
	}

	public @Nullable Button logoBlockButton() {
		return logoBlockButton;
	}

	// ------------------------------------------------------------------ widgets

	/**
	 * A tab: a vanilla button; the selected one keeps the hovered (white-framed) sprite and underlines its label, as
	 * vanilla's tab buttons do. Clicking the other one switches.
	 */
	private final class TabButton extends Button {
		private final PopupTab value;

		TabButton(PopupTab value, int x, int y, int width) {
			super(x, y, width, ROW, Component.translatable(value.translationKey()), b -> selectTab(value), DEFAULT_NARRATION);
			this.value = value;
			setTooltip(Tooltip.create(Component.translatable("waypy.popup.tab.tooltip")));
		}

		boolean selected() {
			return tab == value;
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			if (selected()) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, TAB_SELECTED_SPRITE, getX(), getY(), getWidth(), getHeight());
				int textWidth = Math.min(font.width(getMessage()), getWidth() - 8);
				int left = getX() + (getWidth() - textWidth) / 2;
				graphics.fill(left, getBottom() - 5, left + textWidth, getBottom() - 4, WHITE);
			} else {
				extractDefaultSprite(graphics);
			}
			graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE)
					.acceptScrollingWithDefaultCenter(selected() ? getMessage() : getMessage().copy().withStyle(ChatFormatting.GRAY),
							getX() + 2, getRight() - 2, getY() - 1, getBottom() - 1);
		}
	}

	/** One numbered entry of the list: a full-width vanilla button with an icon on the left and a swatch on the right. */
	private abstract class EntryButton extends Button {
		final int number;
		private final @Nullable ItemStack icon;
		private final int color;

		EntryButton(Component message, int number, @Nullable ItemStack icon, int color) {
			super(0, 0, COLUMN_WIDTH, ROW, message, b -> ((EntryButton) b).apply(), DEFAULT_NARRATION);
			this.number = number;
			this.icon = icon;
			this.color = color;
		}

		/** Applies the entry (and closes the popup). */
		abstract boolean apply();

		/** Opens the entry's edit form. */
		abstract void edit();

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			extractDefaultSprite(graphics);
			int x = getX();
			int y = getY();
			if (icon != null) graphics.fakeItem(icon, x + 3, y + (getHeight() - 16) / 2);
			// Swatch: 6x6 of the colour in a 1 px black ring, 5 px from the right edge.
			int sx = getRight() - 13;
			int sy = y + 5;
			graphics.fill(sx, sy, sx + 8, sy + 8, 0xFF000000);
			graphics.fill(sx + 1, sy + 1, sx + 7, sy + 7, 0xFF000000 | color);
			graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE)
					.acceptScrollingWithDefaultCenter(getMessage(), x + 22, getRight() - 22, y, getBottom());
		}
	}

	/** A preset: {@code 1  Home}, the logo's item icon on the left and the preset colour on the right. */
	private final class PresetButton extends EntryButton {
		private final Preset preset;

		PresetButton(Preset preset, int number) {
			super(Component.translatable("waypy.presets.button", number, preset.name), number,
					preset.logoType == LogoType.BLOCK ? WaypointScreen.stackFor(preset.logoBlock) : null, preset.color);
			this.preset = preset;
			setTooltip(Tooltip.create(number <= 9 ? Component.translatable("waypy.presets.button.tooltip", number)
					: Component.translatable("waypy.presets.button.tooltip.no_key")));
		}

		@Override
		boolean apply() {
			return applyPreset(preset);
		}

		@Override
		void edit() {
			openEditForm(number);
		}
	}

	/**
	 * A layout: {@code 1  Base Layout · 3 waypoints} (the count in grey), the group's logo icon on the left and the group
	 * colour on the right.
	 */
	private final class LayoutButton extends EntryButton {
		private final Layout layout;

		LayoutButton(Layout layout, int number) {
			super(Component.translatable("waypy.presets.button", number, layout.name)
							.append(Component.translatable("waypy.layouts.button.count", layout.members.size()).withStyle(ChatFormatting.GRAY)),
					number, layout.group.logoType == LogoType.BLOCK ? WaypointScreen.stackFor(layout.group.logoBlock) : null,
					layout.group.color);
			this.layout = layout;
			int members = layout.members.size();
			setTooltip(Tooltip.create(number <= 9
					? Component.translatable("waypy.layouts.button.tooltip", members, layout.blockCount(), number)
					: Component.translatable("waypy.layouts.button.tooltip.no_key", members, layout.blockCount())));
		}

		@Override
		boolean apply() {
			return applyLayout(layout);
		}

		@Override
		void edit() {
			openLayoutForm(number);
		}
	}
}
