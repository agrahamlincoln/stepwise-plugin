package com.stepwise.panel;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import net.runelite.api.SpriteID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves icon type + key pairs from CompletionCriterion into ImageIcons
 * for display in the side panel. Caches resolved icons to avoid repeated lookups.
 */
public class IconProvider
{
	private static final Logger log = LoggerFactory.getLogger(IconProvider.class);
	/** Size for icons on upcoming steps. */
	static final int SMALL_ICON_SIZE = 24;
	static final int TIMER_ICON_SIZE = 24;

	private static final int COINS_ITEM_ID = 995;
	private static final int COMBAT_ACHIEVEMENTS_ITEM_ID = 25956;

	private final SkillIconManager skillIconManager;
	private final ItemManager itemManager;
	private final SpriteManager spriteManager;

	private final Map<String, ImageIcon> cache = new ConcurrentHashMap<>();
	/**
	 * Interface sprites as decoded, keyed by the criterion icon type that shows them. They need
	 * the client thread, so {@link #loadSprites} fetches them and the panel reads them on the EDT.
	 */
	private final Map<String, BufferedImage> sprites = new ConcurrentHashMap<>();

	private volatile Color inventoryPanelColour;
	private volatile boolean inventoryPanelColourAsked;

	/** Callback to repaint the panel when an async item sprite finishes loading. */
	private volatile Runnable onImageLoaded;

	public IconProvider(SkillIconManager skillIconManager, ItemManager itemManager,
		SpriteManager spriteManager)
	{
		this.skillIconManager = skillIconManager;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
	}

	public void setOnImageLoaded(Runnable callback)
	{
		this.onImageLoaded = callback;
	}

	/**
	 * An item's sprite, fetched from the item manager. Every item lookup goes through here
	 * and {@link #spriteAsync}.
	 */
	protected AsyncBufferedImage itemImage(int itemId, int quantity, boolean stackable)
	{
		return itemManager.getImage(itemId, quantity, stackable);
	}

	/**
	 * A game interface sprite, delivered to the callback once the client has decoded it.
	 * Never delivered when there is no client to decode it, which every caller already
	 * treats as "not loaded yet".
	 */
	protected void spriteAsync(int spriteId, int file,
		Consumer<BufferedImage> callback)
	{
		if (spriteManager != null)
		{
			spriteManager.getSpriteAsync(spriteId, file, callback);
		}
	}

	/**
	 * Pre-fetches game sprites. Call from the client thread. The panel reads the results
	 * on the EDT.
	 */
	@SuppressWarnings("deprecation") // SpriteID — gameval equivalent uses unreadable Jagex names
	public void loadSprites()
	{
		loadSprite("quest", SpriteID.TAB_QUESTS);
		loadSprite("diary", SpriteID.TAB_QUESTS_GREEN_ACHIEVEMENT_DIARIES);
		loadSprite("skills", SpriteID.TAB_STATS);
		loadSprite("hasItem", SpriteID.TAB_INVENTORY);
		loadSprite("location", SpriteID.MINIMAP_ORB_WORLD_MAP_PLANET);
	}

	private void loadSprite(String iconType, int spriteId)
	{
		if (!sprites.containsKey(iconType))
		{
			spriteAsync(spriteId, 0, img ->
			{
				if (img != null)
				{
					sprites.put(iconType, img);
				}
			});
		}
	}

	/** Runs the repaint hook, if one is set. EDT only. */
	private void repaint()
	{
		Runnable repaint = onImageLoaded;
		if (repaint != null)
		{
			repaint.run();
		}
	}

	/**
	 * The colour of the game's inventory panel, or null until the sprite has loaded.
	 *
	 * <p>Null is the normal first answer. The caller falls back to the flat container
	 * colour, and the repaint hook brings the real colour in once it lands.
	 *
	 * <p>Call <b>from the EDT</b> only. The asked flag is a plain check-then-set, so a
	 * caller on another thread could trigger a second fetch. On a {@code SpriteManager}
	 * cache hit the callback runs inline, so the averaging can run on the EDT inside a paint.
	 */
	public Color getInventoryPanelColour()
	{
		if (inventoryPanelColour != null)
		{
			return inventoryPanelColour;
		}
		if (!inventoryPanelColourAsked)
		{
			inventoryPanelColourAsked = true;
			spriteAsync(net.runelite.api.gameval.SpriteID.SIDE_BACKGROUND, 0, img ->
			{
				if (img == null)
				{
					return;
				}
				Color average = averageOfMiddle(img);
				if (average == null)
				{
					log.debug("Inventory panel sprite had no opaque middle to average");
					return;
				}
				SwingUtilities.invokeLater(() ->
				{
					inventoryPanelColour = average;
					repaint();
				});
			});
		}
		return null;
	}

	/**
	 * The mean colour of a sprite's middle, ignoring the outer quarter on every side.
	 *
	 * <p>The edges hold the panel's bevel decoration, which would skew the average.
	 * Mostly-transparent pixels are skipped so cut-away areas do not darken the result.
	 * Returns null when nothing in the middle is opaque.
	 */
	static Color averageOfMiddle(BufferedImage sprite)
	{
		int insetX = sprite.getWidth() / 4;
		int insetY = sprite.getHeight() / 4;
		long red = 0;
		long green = 0;
		long blue = 0;
		long counted = 0;
		for (int y = insetY; y < sprite.getHeight() - insetY; y++)
		{
			for (int x = insetX; x < sprite.getWidth() - insetX; x++)
			{
				int argb = sprite.getRGB(x, y);
				if ((argb >>> 24) < 128)
				{
					continue;
				}
				red += (argb >> 16) & 0xFF;
				green += (argb >> 8) & 0xFF;
				blue += argb & 0xFF;
				counted++;
			}
		}
		if (counted == 0)
		{
			return null;
		}
		return new Color((int) (red / counted), (int) (green / counted),
			(int) (blue / counted));
	}

	/**
	 * A skill's tab icon at a caller-chosen size.
	 *
	 * @param skillName the guide's spelling of the skill, matched case-insensitively
	 * @param size      width and height in pixels
	 * @return the icon, or null when the name is not one of the game's skills
	 */
	public ImageIcon getSkillIcon(String skillName, int size)
	{
		if (skillName == null)
		{
			return null;
		}

		String cacheKey = "skill:" + skillName.toLowerCase(java.util.Locale.ROOT) + ":" + size;
		ImageIcon cached = cache.get(cacheKey);
		if (cached != null)
		{
			return cached;
		}

		for (Skill skill : Skill.values())
		{
			if (!skill.getName().equalsIgnoreCase(skillName))
			{
				continue;
			}
			BufferedImage img = skillIconManager.getSkillImage(skill, true);
			if (img == null)
			{
				return null;
			}
			ImageIcon icon = new ImageIcon(ImageUtil.resizeImage(img, size, size));
			cache.put(cacheKey, icon);
			return icon;
		}
		return null;
	}

	/**
	 * The quest tab's icon at a caller-chosen size, or null until {@link #loadSprites} has
	 * fetched it.
	 *
	 * @param size width and height in pixels
	 */
	public ImageIcon getQuestIcon(int size)
	{
		return resizedTab("quest", size);
	}

	/**
	 * The stats tab's icon at a caller-chosen size, or null until {@link #loadSprites} has
	 * fetched it.
	 *
	 * @param size width and height in pixels
	 */
	public ImageIcon getSkillsIcon(int size)
	{
		return resizedTab("skills", size);
	}

	private ImageIcon resizedTab(String iconType, int size)
	{
		BufferedImage sprite = sprites.get(iconType);
		if (sprite == null)
		{
			return null;
		}
		return cache.computeIfAbsent("tab:" + iconType + ":" + size,
			k -> new ImageIcon(ImageUtil.resizeImage(sprite, size, size)));
	}

	/**
	 * A coin stack at a caller-chosen size, drawn as the large pile. Handed out blank at full
	 * size and filled in place on load: the panel rebuilds a row only when its content
	 * changes, so it never asks again for an icon that was missing at rebuild time.
	 *
	 * @param size width and height in pixels
	 */
	public ImageIcon getCoinsIcon(int size)
	{
		// Quantity 10000 selects the large stack sprite.
		return sizedItemIcon("coins:" + size, size, () -> itemImage(COINS_ITEM_ID, 10000, false));
	}

	/**
	 * An item's sprite at a caller-chosen size, handed out blank at that size and filled in
	 * place on load, for the same reason as {@link #getCoinsIcon(int)}.
	 *
	 * @param size width and height in pixels
	 */
	public ImageIcon getItemIcon(int itemId, int size)
	{
		return sizedItemIcon("item:" + itemId + ":" + size, size, () -> itemImage(itemId, 1, false));
	}

	private ImageIcon sizedItemIcon(String cacheKey, int size, Supplier<AsyncBufferedImage> sprite)
	{
		ImageIcon cached = cache.get(cacheKey);
		if (cached != null)
		{
			return cached;
		}
		try
		{
			ImageIcon icon = resizedOnLoad(sprite.get(), size);
			cache.put(cacheKey, icon);
			return icon;
		}
		catch (Exception e)
		{
			log.debug("Failed to load icon {}", cacheKey, e);
			return null;
		}
	}

	/**
	 * Resolves an icon from type + key, or null for an unknown type.
	 *
	 * @param iconType "skill", "item", "gp", "quest", "diary", "combat_achievement",
	 *                 "location", "hasItem" or "skills"
	 * @param iconKey  skill name for skills, item ID or coin quantity string, null otherwise
	 * @param active   true for the current step, false for upcoming. Item and coin icons keep
	 *                 their natural size on the current step and are capped at 24px on
	 *                 upcoming ones; skill and sprite icons are 24px either way.
	 */
	public ImageIcon getIcon(String iconType, String iconKey, boolean active)
	{
		if (iconType == null)
		{
			return null;
		}

		String cacheKey = iconType + ":" + iconKey + ":" + (active ? "lg" : "sm");
		ImageIcon cached = cache.get(cacheKey);
		if (cached != null)
		{
			return cached;
		}

		ImageIcon icon = resolve(iconType, iconKey, active);
		if (icon == null)
		{
			return null;
		}
		// An active item icon wraps the AsyncBufferedImage directly, so its pixels fill in
		// place and only need a repaint. A shrunk inactive icon repaints itself via
		// resizedOnLoad.
		cache.put(cacheKey, icon);
		registerAsyncRepaint(icon);
		return icon;
	}

	/**
	 * The same sprite the inventory draws for a stack of this size, at the size the game draws
	 * it, drained of colour when the player has not got it.
	 *
	 * <p>Unresized on purpose: a grid drawn as an inventory should hold the same 36×32
	 * sprites the inventory does.
	 *
	 * @param quantity how many, which changes the sprite for anything stackable
	 * @param held     false to render in greyscale
	 */
	public ImageIcon getInventoryIcon(int itemId, int quantity, boolean held)
	{
		int stack = Math.max(1, quantity);
		String cacheKey = "inv:" + itemId + ":" + stack + ":" + held;
		ImageIcon cached = cache.get(cacheKey);
		if (cached != null)
		{
			return cached;
		}

		AsyncBufferedImage sprite;
		try
		{
			// A non-stackable item ignores the stackable flag and draws a single sprite.
			sprite = itemImage(itemId, stack, stack > 1);
		}
		catch (Exception e)
		{
			log.debug("Failed to load inventory icon for item {}", itemId, e);
			return null;
		}
		if (sprite == null)
		{
			return null;
		}

		// A held item wraps the async image as-is. Greying waits for the pixels and
		// restyles the same instance, since labels already hold it.
		ImageIcon icon = new ImageIcon(sprite);
		cache.put(cacheKey, icon);
		if (held)
		{
			registerAsyncRepaint(icon);
		}
		else
		{
			sprite.onLoaded(() -> SwingUtilities.invokeLater(() ->
			{
				icon.setImage(ImageUtil.grayscaleImage(sprite));
				repaint();
			}));
		}
		return icon;
	}

	/**
	 * A blank icon at its final size, filled in place once the sprite's pixels load.
	 *
	 * <p>Handing out the full-size sprite meanwhile would lay rows out around a 36x32 icon
	 * and clip fixed-height rows. The load fires only a repaint, never a revalidate, so the
	 * icon's size must not change on arrival. An already-loaded sprite still fills a client
	 * tick later, because {@code AsyncBufferedImage} cannot report whether it has loaded.
	 */
	private ImageIcon resizedOnLoad(AsyncBufferedImage async, int size)
	{
		ImageIcon sized = new ImageIcon(new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB));
		async.onLoaded(() -> SwingUtilities.invokeLater(() ->
		{
			sized.setImage(ImageUtil.resizeImage(async, size, size));
			repaint();
		}));
		return sized;
	}

	private void registerAsyncRepaint(ImageIcon icon)
	{
		if (icon.getImage() instanceof AsyncBufferedImage)
		{
			((AsyncBufferedImage) icon.getImage()).onLoaded(() ->
				SwingUtilities.invokeLater(this::repaint));
		}
	}

	private ImageIcon resolve(String iconType, String iconKey, boolean active)
	{
		try
		{
			ImageIcon icon;
			switch (iconType)
			{
				case "skill":
					return getSkillIcon(iconKey, SMALL_ICON_SIZE);
				case "item":
					icon = new ImageIcon(itemImage(Integer.parseInt(iconKey), 1, false));
					break;
				case "gp":
					// The inactive icon is the large pile. Active, OSRS draws different coin
					// sprites at 1, 2, 3, 4, 5, 25, 100, 250, 1000 and 10000+, and ItemManager
					// picks the one for the quantity.
					icon = new ImageIcon(active
						? itemImage(COINS_ITEM_ID, coinQuantity(iconKey), true)
						: itemImage(COINS_ITEM_ID, 10000, false));
					break;
				case "combat_achievement":
					icon = new ImageIcon(itemImage(COMBAT_ACHIEVEMENTS_ITEM_ID, 1, false));
					break;
				case "quest":
				case "diary":
				case "location":
				case "hasItem":
				case "skills":
					BufferedImage sprite = sprites.get(iconType);
					return sprite != null ? new ImageIcon(
						ImageUtil.resizeImage(sprite, SMALL_ICON_SIZE, SMALL_ICON_SIZE)) : null;
				default:
					return null;
			}
			if (!active && (icon.getIconWidth() > SMALL_ICON_SIZE
				|| icon.getIconHeight() > SMALL_ICON_SIZE))
			{
				icon = resizedOnLoad((AsyncBufferedImage) icon.getImage(), SMALL_ICON_SIZE);
			}
			return icon;
		}
		catch (Exception e)
		{
			log.debug("Failed to resolve icon type={} key={}", iconType, iconKey, e);
			return null;
		}
	}

	/** A coin criterion's quantity, or a single coin when the key is not a number. */
	private static int coinQuantity(String key)
	{
		try
		{
			return key != null ? Integer.parseInt(key) : 1;
		}
		catch (NumberFormatException e)
		{
			return 1;
		}
	}
}
