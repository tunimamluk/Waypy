package dev.waypy.gui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Lets the player pick the logo block: a search box above a scrollable grid of the item icons of every block
 * that has an item, drawn on vanilla slot sprites like the creative inventory. Clicking an icon reports its
 * item id to {@code onPick} and returns to the parent screen.
 */
public class BlockPickerScreen extends Screen {
	private static final Component TITLE = Component.translatable("waypy.gui.block_picker.title");
	private static final Component SEARCH = Component.translatable("waypy.gui.block_picker.search");
	private static final Component NO_RESULTS = Component.translatable("waypy.gui.block_picker.empty");
	private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");
	private static final Identifier SLOT_HIGHLIGHT_BACK_SPRITE = Identifier.withDefaultNamespace("container/slot_highlight_back");
	private static final Identifier SLOT_HIGHLIGHT_FRONT_SPRITE = Identifier.withDefaultNamespace("container/slot_highlight_front");
	private static final Identifier MENU_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/menu_list_background.png");
	private static final Identifier INWORLD_MENU_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");
	private static final int SLOT = 18;
	private static final int GRID_PADDING = 4;
	private static final int MAX_COLUMNS = 18;
	/** Space right of the slots: 4px gap + 6px scrollbar. */
	private static final int SCROLLBAR_SPACE = 10;

	private final Screen parent;
	private final @Nullable Identifier current;
	private final Consumer<Identifier> onPick;
	private final List<Choice> choices;
	private HeaderAndFooterLayout layout = newLayout();
	private @Nullable EditBox searchBox;
	private @Nullable BlockGrid grid;

	public BlockPickerScreen(Screen parent, Consumer<Identifier> onPick) {
		this(parent, null, onPick);
	}

	/** @param current the currently used block/item id, outlined in the grid and scrolled into view */
	public BlockPickerScreen(Screen parent, @Nullable Identifier current, Consumer<Identifier> onPick) {
		super(TITLE);
		this.parent = parent;
		this.current = current;
		this.onPick = onPick;
		this.choices = collectChoices();
	}

	/** Every distinct non-air item of a block in {@link BuiltInRegistries#BLOCK}, in registry order. */
	private static List<Choice> collectChoices() {
		List<Choice> out = new ArrayList<>();
		Set<Item> seen = new HashSet<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			Item item = block.asItem();
			if (item == Items.AIR || !seen.add(item)) continue;
			ItemStack stack = new ItemStack(item);
			Identifier id = BuiltInRegistries.ITEM.getKey(item);
			out.add(new Choice(id, stack, stack.getHoverName().getString().toLowerCase(Locale.ROOT), id.toString()));
		}
		return List.copyOf(out);
	}

	/** Header fits the title and the search box (8 + 9 + 4 + 20 + 8, like the world selection screen). */
	private HeaderAndFooterLayout newLayout() {
		return new HeaderAndFooterLayout(this, 8 + 9 + 4 + 20 + 8, HeaderAndFooterLayout.DEFAULT_HEADER_AND_FOOTER_HEIGHT);
	}

	@Override
	protected void init() {
		layout = newLayout();
		LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new StringWidget(title, font));
		searchBox = header.addChild(new EditBox(font, 0, 0, Button.BIG_WIDTH, Button.DEFAULT_HEIGHT, searchBox, SEARCH));
		searchBox.setHint(SEARCH.copy().setStyle(EditBox.SEARCH_HINT_STYLE));
		layout.addToFooter(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).width(Button.BIG_WIDTH).build());
		layout.arrangeElements();

		int columns = Math.clamp((width - 2 * 10 - SCROLLBAR_SPACE) / SLOT, 1, MAX_COLUMNS);
		int slotsX = (width - columns * SLOT) / 2;
		grid = new BlockGrid(slotsX, layout.getHeaderHeight(), columns * SLOT + SCROLLBAR_SPACE, layout.getContentHeight(), columns);
		grid.setFilter(searchBox.getValue());
		grid.scrollToCurrent();
		BlockGrid g = grid;
		searchBox.setResponder(g::setFilter);

		addRenderableWidget(searchBox);
		addRenderableWidget(grid);
		layout.visitWidgets(widget -> {
			if (widget != searchBox) addRenderableWidget(widget);
		});
	}

	@Override
	protected void setInitialFocus() {
		if (searchBox != null) setInitialFocus(searchBox);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (searchBox != null && searchBox.isFocused() && event.isConfirmation() && grid != null && !grid.filtered.isEmpty()) {
			pick(grid.filtered.getFirst());
			return true;
		}
		return super.keyPressed(event);
	}

	private void pick(Choice choice) {
		onPick.accept(choice.id());
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		boolean inWorld = minecraft.level != null;
		int top = layout.getHeaderHeight();
		int contentHeight = layout.getContentHeight();
		graphics.blit(RenderPipelines.GUI_TEXTURED, inWorld ? INWORLD_MENU_LIST_BACKGROUND : MENU_LIST_BACKGROUND,
				0, top, 0.0F, 0.0F, width, contentHeight, 32, 32);
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.blit(RenderPipelines.GUI_TEXTURED, inWorld ? INWORLD_HEADER_SEPARATOR : HEADER_SEPARATOR,
				0, top - 2, 0.0F, 0.0F, width, 2, 32, 2);
		graphics.blit(RenderPipelines.GUI_TEXTURED, inWorld ? INWORLD_FOOTER_SEPARATOR : FOOTER_SEPARATOR,
				0, height - layout.getFooterHeight(), 0.0F, 0.0F, width, 2, 32, 2);
		if (grid != null && grid.filtered.isEmpty()) {
			graphics.centeredText(font, NO_RESULTS, width / 2, top + contentHeight / 2 - 4, 0xFFA0A0A0);
		}
	}

	private record Choice(Identifier id, ItemStack stack, String name, String idString) {
	}

	/** Scrollable grid of slots; uses the vanilla scrollbar of {@link AbstractScrollArea}. */
	private final class BlockGrid extends AbstractScrollArea {
		private final int columns;
		private List<Choice> filtered = List.of();

		BlockGrid(int x, int y, int width, int height, int columns) {
			super(x, y, width, height, CommonComponents.EMPTY, AbstractScrollArea.defaultSettings(SLOT));
			this.columns = columns;
		}

		void setFilter(String query) {
			String q = query.trim().toLowerCase(Locale.ROOT);
			if (q.isEmpty()) {
				filtered = choices;
			} else {
				List<Choice> out = new ArrayList<>();
				for (Choice c : choices) {
					if (c.name().contains(q) || c.idString().contains(q)) out.add(c);
				}
				filtered = out;
			}
			setScrollAmount(0.0);
		}

		void scrollToCurrent() {
			if (current == null) return;
			for (int i = 0; i < filtered.size(); i++) {
				if (filtered.get(i).id().equals(current)) {
					int rowY = (i / columns) * SLOT;
					setScrollAmount(rowY - (height - SLOT) / 2.0);
					return;
				}
			}
		}

		private int rows() {
			return (filtered.size() + columns - 1) / columns;
		}

		@Override
		protected int contentHeight() {
			return rows() * SLOT + 2 * GRID_PADDING;
		}

		private int slotsTop() {
			return getY() + GRID_PADDING - (int) scrollAmount();
		}

		private int indexAt(double mouseX, double mouseY) {
			if (mouseX < getX() || mouseX >= getX() + columns * SLOT || mouseY < getY() || mouseY >= getBottom()) return -1;
			int dy = (int) Math.floor(mouseY) - slotsTop();
			if (dy < 0) return -1;
			int index = (dy / SLOT) * columns + ((int) Math.floor(mouseX) - getX()) / SLOT;
			return index < filtered.size() ? index : -1;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			int hovered = isHovered() ? indexAt(mouseX, mouseY) : -1;
			graphics.enableScissor(getX(), getY(), getRight(), getBottom());
			int top = slotsTop();
			int firstRow = Math.max(0, (getY() - top) / SLOT);
			int lastRow = Math.min(rows() - 1, (getBottom() - top) / SLOT);
			for (int row = firstRow; row <= lastRow; row++) {
				for (int col = 0; col < columns; col++) {
					int index = row * columns + col;
					if (index >= filtered.size()) break;
					Choice choice = filtered.get(index);
					int sx = getX() + col * SLOT;
					int sy = top + row * SLOT;
					int ix = sx + 1;
					int iy = sy + 1;
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, sx, sy, SLOT, SLOT);
					if (index == hovered) {
						graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_BACK_SPRITE, ix - 4, iy - 4, 24, 24);
					}
					graphics.fakeItem(choice.stack(), ix, iy);
					if (index == hovered) {
						graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_FRONT_SPRITE, ix - 4, iy - 4, 24, 24);
					} else if (choice.id().equals(current)) {
						graphics.outline(sx, sy, SLOT, SLOT, 0xFFFFFFFF);
					}
				}
			}
			graphics.disableScissor();
			extractScrollbar(graphics, mouseX, mouseY);
			if (hovered >= 0) {
				graphics.requestCursor(CursorTypes.POINTING_HAND);
				graphics.setTooltipForNextFrame(font, filtered.get(hovered).stack(), mouseX, mouseY);
			}
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			if (!visible || !isMouseOver(event.x(), event.y())) return false;
			if (updateScrolling(event)) return true;
			if (event.button() == 0) {
				int index = indexAt(event.x(), event.y());
				if (index >= 0) {
					playDownSound(minecraft.getSoundManager());
					pick(filtered.get(index));
				}
			}
			return true;
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
		}
	}
}
