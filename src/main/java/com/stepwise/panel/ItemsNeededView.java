package com.stepwise.panel;

import com.stepwise.snapshot.ItemNeededStatus;
import com.stepwise.snapshot.ItemNeededStatus.Ownership;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.Border;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The active entry's items-needed list, drawn as an inventory-style sprite grid coloured
 * by whether the player is carrying each item, has it banked, is missing it, or the plugin
 * cannot yet say. {@link EntryBlock}'s Items chip opens and shuts it.
 *
 * <p>Lays its {@link ItemSquare} cells over an {@link InventoryBackdrop}, four wide like the
 * game's inventory. {@link Ownership#UNKNOWN} covers a bank never opened, or cleared since.
 */
class ItemsNeededView extends JPanel
{
	/** The game inventory's width. */
	private static final int COLUMNS = 4;
	private static final int SQUARE = ItemSquare.SIZE;
	private static final int GAP = ItemSquare.GAP;
	/** Muted, for the line naming items the grid cannot draw. */
	private static final Color COLOR_UNRESOLVED = new Color(130, 130, 130);

	// Shared instances: recolor() runs every tick, and setBorder repaints whenever the
	// instance differs, even if it draws identically. These borders hold no per-component state.
	private static final Border BORDER_CARRIED =
		BorderFactory.createLineBorder(ColorScheme.PROGRESS_COMPLETE_COLOR);
	private static final Border BORDER_BANKED =
		BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE);
	private static final Border BORDER_NONE = BorderFactory.createEmptyBorder(1, 1, 1, 1);

	/** Holds the grid and the unresolved line, shown while the view is open. */
	private final JPanel content = PanelSections.column();
	private final InventoryBackdrop grid;
	/** Rows that name no single item, comma-joined. Wraps, so it is never recreated. */
	private final JTextArea unresolved;
	private final RebuildGuard guard = new RebuildGuard();

	/** The squares on screen, in list order, so a border-only change recolours in place. */
	private List<ItemSquare> squares = Collections.emptyList();

	/** Listed items in the grid and the unresolved line combined. */
	private int shownCount;

	ItemsNeededView()
	{
		PanelSections.column(this);

		grid = new InventoryBackdrop(null);
		grid.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 0));
		grid.setAlignmentX(LEFT_ALIGNMENT);

		unresolved = StepCardPanel.textArea("", COLOR_UNRESOLVED, FontManager.getRunescapeSmallFont());
		unresolved.setVisible(false);

		content.add(grid);
		content.add(unresolved);
		content.setVisible(false);

		add(content);
		setVisible(false);
	}

	/**
	 * Redraws the grid. Called every tick, because ownership can flip mid-entry. The
	 * {@link RebuildGuard} rebuilds on a new entry, a changed row (text, item or quantity) or a
	 * MISSING↔held flip; other changes recolour in place.
	 *
	 * @param entryId the active entry id, so a new entry rebuilds even with the same rows
	 */
	void rebuild(String entryId, List<ItemNeededStatus> items, IconProvider icons)
	{
		List<ItemNeededStatus> kept = displayable(items);
		if (kept.isEmpty())
		{
			setVisible(false);
			squares = Collections.emptyList();
			guard.reset();
			return;
		}
		// Anti-flicker: every mutation below is gated on an actual change, and the layout
		// pass runs only if one happened. Re-setting identical text on the wrapping
		// unresolved line would re-measure it and make the height oscillate.
		boolean changed = false;
		if (!isVisible())
		{
			setVisible(true);
			changed = true;
		}
		grid.setIcons(icons);

		List<ItemNeededStatus> resolved = new ArrayList<>(kept.size());
		List<String> unresolvedLines = new ArrayList<>();
		for (ItemNeededStatus item : kept)
		{
			if (item.getItemId() > 0)
			{
				resolved.add(item);
			}
			else
			{
				unresolvedLines.add(item.getText());
			}
		}

		shownCount = kept.size();

		if (guard.shouldRebuild(signature(entryId, kept)))
		{
			rebuildGrid(resolved, icons);
			changed = true;
		}
		else
		{
			recolor(resolved);
		}

		boolean gridVisible = !resolved.isEmpty();
		if (grid.isVisible() != gridVisible)
		{
			grid.setVisible(gridVisible);
			changed = true;
		}
		String line = String.join(", ", unresolvedLines);
		if (!line.equals(unresolved.getText()))
		{
			unresolved.setText(line);
			changed = true;
		}
		boolean lineVisible = !unresolvedLines.isEmpty();
		if (unresolved.isVisible() != lineVisible)
		{
			unresolved.setVisible(lineVisible);
			changed = true;
		}

		if (changed)
		{
			revalidate();
			repaint();
		}
	}

	/** The rows with text to show. */
	private static List<ItemNeededStatus> displayable(List<ItemNeededStatus> items)
	{
		if (items == null || items.isEmpty())
		{
			return Collections.emptyList();
		}
		List<ItemNeededStatus> kept = new ArrayList<>(items.size());
		for (ItemNeededStatus item : items)
		{
			if (item != null && item.getText() != null)
			{
				kept.add(item);
			}
		}
		return kept;
	}

	/** Structural signature: the entry, plus each row's identity and whether it is held.
	 *  Full ownership is excluded because a BANKED↔CARRIED flip changes only the border,
	 *  which {@link #recolor} handles. */
	private static List<String> signature(String entryId, List<ItemNeededStatus> items)
	{
		List<String> sig = new ArrayList<>(items.size() + 1);
		sig.add(entryId);
		for (ItemNeededStatus item : items)
		{
			boolean held = item.getOwnership() != Ownership.MISSING;
			sig.add(item.getText() + "|" + item.getItemId() + "|" + item.getQuantity() + "|" + held);
		}
		return sig;
	}

	/** Full rebuild of the grid. */
	private void rebuildGrid(List<ItemNeededStatus> resolved, IconProvider icons)
	{
		grid.removeAll();
		int n = resolved.size();
		if (n == 0)
		{
			squares = Collections.emptyList();
			return;
		}

		int rows = (n + COLUMNS - 1) / COLUMNS;
		grid.setLayout(new GridLayout(rows, COLUMNS, GAP, GAP));
		// Capped, or GridLayout stretches each square to a quarter of the sidebar.
		Dimension size = new Dimension(
			COLUMNS * SQUARE + (COLUMNS - 1) * GAP + 4,
			rows * SQUARE + (rows - 1) * GAP + 6);
		grid.setMaximumSize(size);
		grid.setPreferredSize(size);

		List<ItemSquare> built = new ArrayList<>(n);
		for (ItemNeededStatus item : resolved)
		{
			ItemSquare cell = square(item, icons);
			grid.addSlot(cell);
			built.add(cell);
		}
		// Trailing cells are blank, not empty slots, which would read as "room for more".
		for (int i = n; i < rows * COLUMNS; i++)
		{
			grid.add(gap());
		}
		squares = built;
	}

	/** A blank, sprite-sized cell for the grid's trailing slots. */
	private static JPanel gap()
	{
		JPanel cell = new JPanel();
		cell.setOpaque(false);
		cell.setPreferredSize(new Dimension(SQUARE, SQUARE));
		return cell;
	}

	private void recolor(List<ItemNeededStatus> resolved)
	{
		int n = Math.min(resolved.size(), squares.size());
		for (int i = 0; i < n; i++)
		{
			applyBorder(squares.get(i), resolved.get(i).getOwnership());
		}
	}

	private static ItemSquare square(ItemNeededStatus item, IconProvider icons)
	{
		boolean held = item.getOwnership() != Ownership.MISSING;
		ImageIcon sprite = icons != null && item.getItemId() > 0
			? icons.getInventoryIcon(item.getItemId(), Math.max(1, item.getQuantity()), held)
			: null;
		// A stack sprite already draws its count. Only an exact quantity of one gets a badge;
		// an absent quantity gets none.
		ItemSquare cell = new ItemSquare(sprite, item.getQuantity() == 1 ? "1" : null);
		cell.setPreferredSize(new Dimension(SQUARE, SQUARE));
		cell.setMinimumSize(new Dimension(SQUARE, SQUARE));
		cell.setToolTipText(item.getText());
		applyBorder(cell, item.getOwnership());
		return cell;
	}

	/** Green when carried, orange when banked, no border otherwise. MISSING gets no warning
	 *  because a correctly-run entry consumes items as it goes. */
	private static void applyBorder(ItemSquare cell, Ownership ownership)
	{
		switch (ownership)
		{
			case CARRIED:
				cell.setBorder(BORDER_CARRIED);
				break;
			case BANKED:
				cell.setBorder(BORDER_BANKED);
				break;
			case MISSING:
			case UNKNOWN:
			default:
				cell.setBorder(BORDER_NONE);
				break;
		}
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	// --- Opened by the entry block's Items chip ---

	/** Opens or shuts the view. Shut, it takes no height. Only a change relays out. */
	void setOpen(boolean open)
	{
		if (content.isVisible() != open)
		{
			content.setVisible(open);
			revalidate();
		}
	}

	/** Whether there is an items-needed list to show. */
	boolean hasItems()
	{
		return isVisible();
	}

	/** Listed items in the grid and the unresolved line combined. */
	int itemCount()
	{
		return shownCount;
	}
}
