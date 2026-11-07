package dev.waypy.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
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
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

import dev.waypy.WaypyClient;
import dev.waypy.preset.Preset;
import dev.waypy.preset.PresetManager;
import dev.waypy.waypoint.LogoType;
import dev.waypy.waypoint.Waypoint;
import dev.waypy.waypoint.WaypointManager;
import dev.waypy.waypoint.WaypointStyle;

/**
 * The Presets popup (default key H), styled like vanilla's Game Menu ({@code PauseScreen}): no panel, a centred
 * column of 204-wide vanilla buttons over the blurred, dimmed world.
 *
 * <p><b>List</b> (the popup as it opens): the title with a small "+" button at the column's right edge, a grey
 * {@code Applies to: <waypoint>} line (or {@code Look at or stand on a waypoint}), a search box and one button per
 * preset ({@code 1  Home}, with the logo icon on the left and a colour swatch on the right), in a scrollable column.
 * The target waypoint is fixed when the popup opens ({@link WaypyClient#findTargetWaypoint}).
 * <ul>
 *   <li>Click a preset, or press its number key (1–9) while the search box is not focused: apply it to the target
 *       ({@link Preset#applyTo}), show {@code Applied "<preset>" to "<waypoint>"} and close. Without a target the
 *       preset buttons are inactive and number keys do nothing.</li>
 *   <li>Search: unfocused when the popup opens. Typing a letter (any printable character but a digit) focuses it and
 *       types the character. Filters by name, case-insensitive; the buttons keep their real numbers. Enter in the box
 *       applies the first match. Esc in the box clears it (and unfocuses it) if it has text, else closes.</li>
 *   <li>Right-click a preset: edit it in the inline form (with Delete).</li>
 *   <li>"+": the inline form for a new preset, copied from the target (or the new-waypoint defaults).</li>
 * </ul>
 *
 * <p><b>Form</b> (the same screen, switched over): Name, colour (swatches and hex), Style, Logo (Block / None and the
 * block button, which opens {@link BlockPickerScreen} and comes back here with the edits kept), Opacity and Render
 * through blocks (Block Selection only), Text; then Save and Cancel (and Delete when editing). The form edits a copy
 * ({@link #draft()}); Save stores it ({@link PresetManager#add} or {@link PresetManager#replace}) and returns to the
 * list, Cancel (or Esc) just returns.
 */
public class PresetScreen extends Screen {
	/** Which view the popup shows. */
	public enum Mode {
		LIST,
		FORM
	}

	private static final Component TITLE = Component.translatable("waypy.presets.title");
	private static final Component SEARCH = Component.translatable("waypy.presets.search");
	private static final Component NO_TARGET = Component.translatable("waypy.presets.no_target");
	private static final Component NO_TARGET_TOOLTIP = Component.translatable("waypy.presets.no_target.tooltip");
	private static final Component EMPTY = Component.translatable("waypy.presets.empty");
	private static final Component NO_MATCH = Component.translatable("waypy.presets.no_match");
	private static final Component NEW_DEFAULTS_TOOLTIP = Component.translatable("waypy.presets.new.tooltip.default");
	private static final Component FORM_NEW = Component.translatable("waypy.presets.form.new");
	private static final Component FORM_DEFAULTS = Component.translatable("waypy.presets.form.defaults");
	private static final Component SAVE = Component.translatable("waypy.presets.save");
	private static final Component DELETE = Component.translatable("waypy.gui.delete");
	private static final Component DELETE_TOOLTIP = Component.translatable("waypy.presets.delete.tooltip");
	private static final Component NAME_LABEL = Component.translatable("waypy.gui.name");
	private static final Component COLOR_LABEL = Component.translatable("waypy.gui.color");
	private static final Component STYLE_LABEL = Component.translatable("waypy.gui.style");
	private static final Component LOGO_LABEL = Component.translatable("waypy.gui.logo");
	private static final Component LOGO_BLOCK_TOOLTIP = Component.translatable("waypy.gui.logo_block.tooltip");
	private static final Component THROUGH_BLOCKS_LABEL = Component.translatable("waypy.gui.through_blocks");
	private static final Component SHOW_TEXT_LABEL = Component.translatable("waypy.gui.show_text");
	private static final Component SHOW_TEXT_TOOLTIP = Component.translatable("waypy.gui.show_text.tooltip");

	/** Width of the button column, as in the Game Menu. */
	public static final int COLUMN_WIDTH = 204;
	private static final int ROW = Button.DEFAULT_HEIGHT;
	private static final int GAP = 4;
	/** List mode, from the top of the column: title row (20), "Applies to" line, search box, then the presets. */
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
	private static final int GRAY = 0xFFA0A0A0;
	private static final int WHITE = 0xFFFFFFFF;
	/** A character typed right after a consumed key press (number keys, the held open key) belongs to it. */
	private static final long CHAR_AFTER_KEY_MILLIS = 100L;

	private final WaypointManager waypoints = WaypointManager.get();
	private final PresetManager presets = PresetManager.get();
	/** The waypoint presets apply to, found when the popup opened; null = none (preset buttons inactive). */
	private final @Nullable Waypoint target;
	/** Opened by the presets key, which may still be held: its repeats must not type into the search box. */
	private boolean ignoreOpenKey;
	private long swallowCharAt = -1;
	private Mode mode = Mode.LIST;
	/** Counts {@link #init()} calls, so a click that switched the view can drop the focus on the removed widget. */
	private int builds;

	// list state (kept across rebuilds)
	private String query = "";
	private boolean searchFocused;
	private int top;
	private int columnX;
	private @Nullable EditBox searchBox;
	private @Nullable Button plusButton;
	private @Nullable ScrollableLayout listScroll;
	private LinearLayout listContent = LinearLayout.vertical();
	private final List<PresetButton> presetButtons = new ArrayList<>();

	// form state: the edited copy and its number (0 = a new preset), kept while the block picker is open
	private @Nullable Preset draft;
	private int editingNumber;
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
	 * @param target   the waypoint presets apply to (null: none)
	 * @param fromKey  opened by the presets key: while that key stays held, its key repeats are ignored
	 */
	public PresetScreen(@Nullable Waypoint target, boolean fromKey) {
		super(TITLE);
		this.target = target;
		this.ignoreOpenKey = fromKey && WaypyClient.presetsKey != null && WaypyClient.presetsKey.isDown();
	}

	// ------------------------------------------------------------------ setup

	@Override
	protected void init() {
		builds++;
		presetButtons.clear();
		searchBox = null;
		plusButton = null;
		listScroll = null;
		nameBox = null;
		formScroll = null;
		saveButton = cancelButton = deleteButton = null;
		if (mode == Mode.FORM && draft != null) {
			initForm(draft);
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
		// The search box starts unfocused (letters focus it, digits apply presets); it keeps its focus across rebuilds.
		if (mode == Mode.LIST && searchFocused && searchBox != null) setFocused(searchBox);
		// The form starts in the name box (Enter there saves).
		if (mode == Mode.FORM && nameBox != null) setFocused(nameBox);
	}

	private void initList() {
		int count = presets.size();
		int listHeight = count == 0 ? ROW : count * (ROW + GAP) - GAP;
		top = Math.clamp((height - (LIST_Y + listHeight)) / 4, BOTTOM_MARGIN, MAX_TOP);
		columnX = (width - COLUMN_WIDTH) / 2;

		int titleWidth = font.width(title); // centred, level with the "+" (as the Game Menu's title)
		addRenderableWidget(new StringWidget(width / 2 - titleWidth / 2, top + 6, titleWidth, font.lineHeight, title, font));
		plusButton = addRenderableWidget(Button.builder(Component.literal("+"), b -> openNewForm())
				.bounds(columnX + COLUMN_WIDTH - ROW, top, ROW, ROW)
				.tooltip(Tooltip.create(target != null
						? Component.translatable("waypy.presets.new.tooltip", target.name) : NEW_DEFAULTS_TOOLTIP))
				.build());

		EditBox search = new EditBox(font, columnX, top + SEARCH_Y, COLUMN_WIDTH, ROW, SEARCH);
		search.setHint(SEARCH.copy().setStyle(EditBox.SEARCH_HINT_STYLE));
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

	/** Rebuilds the preset buttons for the current search (case-insensitive, by name); numbers stay the real ones. */
	private void refreshList() {
		ScrollableLayout scroll = listScroll;
		if (scroll == null) return;
		scroll.removeChildren();
		presetButtons.clear();
		String q = query.trim().toLowerCase(Locale.ROOT);
		List<Preset> all = presets.all();
		for (int i = 0; i < all.size(); i++) {
			Preset preset = all.get(i);
			if (!q.isEmpty() && !preset.name.toLowerCase(Locale.ROOT).contains(q)) continue;
			PresetButton button = new PresetButton(preset, i + 1);
			presetButtons.add(button);
			listContent.addChild(button);
		}
		listContent.arrangeElements();
		scroll.setMaxHeight(Math.max(ROW, height - (top + LIST_Y) - BOTTOM_MARGIN));
		scroll.arrangeElements();
		scroll.setX(columnX - SCROLL_RESERVE);
		scroll.setY(top + LIST_Y);
	}

	// ------------------------------------------------------------------ form

	/** "+": the inline form for a new preset, copied from the target (named after it) or from the new-waypoint defaults. */
	public void openNewForm() {
		int number = presets.nextNumber();
		if (target != null) {
			draft = Preset.from(target, target.name);
			formSubtitle = Component.translatable("waypy.presets.form.copied", target.name);
		} else {
			draft = new Preset(Component.translatable("waypy.presets.default_name", number).getString(), waypoints.peekNextColor());
			formSubtitle = FORM_DEFAULTS;
		}
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

	/** Save: a new preset gets the next number, an edited one keeps its number. Back to the list. */
	public void saveForm() {
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

	/** Cancel (or Esc in the form): back to the list without saving. */
	public void cancelForm() {
		if (mode != Mode.FORM) return;
		backToList();
	}

	/** Delete (edit form only): removes the preset; the later ones move up a number. Back to the list. */
	public void deleteEdited() {
		if (mode != Mode.FORM || editingNumber <= 0) return;
		presets.remove(editingNumber);
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

		int buttonsY = top + FORM_FIELDS_Y + fieldsHeight + 8;
		Button[] buttons = deleteButton != null
				? new Button[] {saveButton, deleteButton, cancelButton}
				: new Button[] {saveButton, cancelButton};
		int each = (formWidth - GAP * (buttons.length - 1)) / buttons.length;
		int x = (width - formWidth) / 2;
		int last = formWidth - (each + GAP) * (buttons.length - 1); // the last one takes the rounding remainder
		for (int i = 0; i < buttons.length; i++) {
			int w = i == buttons.length - 1 ? last : each;
			buttons[i].setRectangle(w, ROW, x, buttonsY);
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

	// ------------------------------------------------------------------ applying

	/**
	 * Applies {@code preset} to the target (see {@link Preset#applyTo}), marks the waypoints dirty, says so in the
	 * action bar and closes the popup. Does nothing without a target (or if it was deleted meanwhile).
	 *
	 * @return true if it was applied
	 */
	public boolean applyPreset(Preset preset) {
		Waypoint w = target;
		if (w == null || waypoints.byId(w.id).isEmpty()) return false;
		preset.applyTo(w);
		waypoints.markDirty();
		if (minecraft.player != null) {
			minecraft.player.sendOverlayMessage(Component.translatable("waypy.message.preset_applied", preset.name, w.name));
		}
		onClose();
		return true;
	}

	/** A number key: applies preset {@code number} (if it exists and there is a target). */
	public boolean applyNumber(int number) {
		Preset preset = presets.byNumber(number);
		return preset != null && applyPreset(preset);
	}

	/** Enter in the search box: applies the first preset the search shows. */
	public boolean applyFirstMatch() {
		return !presetButtons.isEmpty() && applyPreset(presetButtons.getFirst().preset);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (ignoreOpenKey && WaypyClient.presetsKey.matches(event)) {
			swallowNextChar(); // a key repeat of the key that opened the popup
			return true;
		}
		if (mode == Mode.FORM) {
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
		if (WaypyClient.presetsKey != null && WaypyClient.presetsKey.matches(event)) ignoreOpenKey = false;
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
			if (Character.isDigit(event.codepoint())) return true; // digits apply presets (keyPressed) while unfocused
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
			PresetButton button = presetButtonAt(event.x(), event.y());
			if (button != null) {
				button.playDownSound(minecraft.getSoundManager());
				openEditForm(button.number);
				return true;
			}
		}
		int before = builds;
		boolean handled = super.mouseClicked(event, doubleClick);
		if (builds != before) {
			// The click switched the view ("+", Save, Cancel, Delete): the clicked button is gone, so don't keep it
			// focused; focus what the new view starts with instead.
			setDragging(false);
			setFocused(null);
			setInitialFocus();
		}
		return handled;
	}

	/** The preset button under the mouse, inside the visible part of the scrolled list (inactive ones included). */
	private @Nullable PresetButton presetButtonAt(double x, double y) {
		ScrollableLayout scroll = listScroll;
		if (scroll == null) return null;
		ScreenRectangle area = new ScreenRectangle(scroll.getX(), scroll.getY(), scroll.getWidth(), scroll.getHeight());
		if (!area.containsPoint((int) x, (int) y)) return null;
		for (PresetButton b : presetButtons) {
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
		Component applies = target != null
				? Component.translatable("waypy.presets.applies_to", Component.literal(target.name).withStyle(ChatFormatting.WHITE))
				: NO_TARGET;
		graphics.centeredText(font, font.split(applies, width - 8).getFirst(), centerX, top + APPLIES_Y, GRAY);
		if (presetButtons.isEmpty()) {
			Component hint = presets.size() == 0 ? EMPTY : NO_MATCH;
			graphics.centeredText(font, hint, centerX, top + LIST_Y + 6, GRAY);
		}
	}

	// ------------------------------------------------------------------ access for the devtest

	public Mode mode() {
		return mode;
	}

	/** The waypoint presets apply to (fixed when the popup opened). */
	public @Nullable Waypoint target() {
		return target;
	}

	/** The preset buttons the search shows, in order (empty in the form). */
	public List<Button> presetButtons() {
		return List.copyOf(presetButtons);
	}

	/** The preset number shown on each visible button, in order. */
	public List<Integer> visibleNumbers() {
		List<Integer> out = new ArrayList<>();
		for (PresetButton b : presetButtons) out.add(b.number);
		return out;
	}

	public @Nullable EditBox searchBox() {
		return searchBox;
	}

	public @Nullable Button plusButton() {
		return plusButton;
	}

	/** The preset the form edits (a copy until Save), or null in the list. */
	public @Nullable Preset draft() {
		return draft;
	}

	/** The number of the preset the form edits; 0 for a new one. */
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

	public @Nullable CycleButton<WaypointStyle> styleButton() {
		return styleButton;
	}

	public @Nullable Button logoBlockButton() {
		return logoBlockButton;
	}

	// ------------------------------------------------------------------ widgets

	/**
	 * One preset: a full-width vanilla button reading {@code 1  Home}, with the logo's item icon on the left and a
	 * small swatch of the preset colour on the right. Inactive (with the reason as tooltip) without a target.
	 */
	private final class PresetButton extends Button {
		private final Preset preset;
		private final int number;
		private final @Nullable ItemStack icon;

		PresetButton(Preset preset, int number) {
			super(0, 0, COLUMN_WIDTH, ROW, Component.translatable("waypy.presets.button", number, preset.name),
					b -> applyPreset(preset), DEFAULT_NARRATION);
			this.preset = preset;
			this.number = number;
			this.icon = preset.logoType == LogoType.BLOCK ? WaypointScreen.stackFor(preset.logoBlock) : null;
			active = target != null;
			setTooltip(Tooltip.create(target == null ? NO_TARGET_TOOLTIP
					: number <= 9 ? Component.translatable("waypy.presets.button.tooltip", number)
					: Component.translatable("waypy.presets.button.tooltip.no_key")));
		}

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
			graphics.fill(sx + 1, sy + 1, sx + 7, sy + 7, 0xFF000000 | preset.color);
			graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE)
					.acceptScrollingWithDefaultCenter(getMessage(), x + 22, getRight() - 22, y, getBottom());
		}
	}
}
