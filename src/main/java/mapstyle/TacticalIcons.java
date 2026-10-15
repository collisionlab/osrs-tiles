package mapstyle;

import java.awt.image.BufferedImage;
import java.util.Map;
import net.runelite.cache.definitions.AreaDefinition;

/**
 * Pixel badges replacing RuneLite's map icons in the tactical style. Only
 * icons that matter for route planning survive: places the planner routes
 * to (bank, altar, anvil) in cyan, and ways through the world (transport,
 * shortcuts, docks, dungeon entrances, map links) in orange. Every other
 * icon is hidden.
 *
 * <p>Icons are keyed by their sprite id, shared by every area of that kind.
 * The ids were identified against the OSRS Wiki's world-map key and the
 * icons' positions in the cache.
 */
final class TacticalIcons
{
	// Web planner tokens (web/src/styles.css).
	private static final int INK = 0x141914;
	private static final int TEXT = 0xE0E5CD;
	private static final int CYAN = 0x77E2D4;
	private static final int ORANGE = 0xFFAE47;

	private static final int SIZE = 11; // 7x7 glyph + 1px ink + 1px border

	private enum Badge
	{
		BANK(CYAN, TEXT,
			"...#...",
			".#####.",
			"#..#...",
			".#####.",
			"...#..#",
			".#####.",
			"...#..."),
		ALTAR(CYAN, TEXT,
			"...#...",
			"...#...",
			"#######",
			"...#...",
			"...#...",
			"...#...",
			"..###.."),
		ANVIL(CYAN, TEXT,
			".......",
			"#######",
			".#####.",
			"...#...",
			"..###..",
			".#####.",
			"......."),
		TRANSPORT(ORANGE, ORANGE,
			"...#...",
			"...##..",
			"######.",
			"#######",
			"######.",
			"...##..",
			"...#..."),
		SHORTCUT(ORANGE, ORANGE,
			"#..#...",
			".#..#..",
			"..#..#.",
			"...#..#",
			"..#..#.",
			".#..#..",
			"#..#..."),
		DOCK(ORANGE, ORANGE,
			"...#...",
			"..###..",
			"...#...",
			"...#...",
			"#..#..#",
			".#.#.#.",
			"..###.."),
		LADDER(ORANGE, ORANGE,
			"#.....#",
			"#######",
			"#.....#",
			"#######",
			"#.....#",
			"#######",
			"#.....#");

		final int border;
		final int ink;
		final String[] glyph;

		Badge(int border, int ink, String... glyph)
		{
			this.border = border;
			this.ink = ink;
			this.glyph = glyph;
		}
	}

	private static final Map<Integer, Badge> BY_SPRITE = Map.of(
		1453, Badge.BANK,
		1467, Badge.ALTAR,
		1458, Badge.ANVIL,
		1504, Badge.TRANSPORT,
		1518, Badge.SHORTCUT,
		7285, Badge.DOCK,
		1679, Badge.LADDER, // dungeon
		1534, Badge.LADDER  // dungeon map link
	);

	private TacticalIcons()
	{
	}

	/** Draws the badge for {@code area} centred on (cx, cy), or nothing if it is not kept. */
	static void draw(BufferedImage image, AreaDefinition area, int cx, int cy)
	{
		Badge badge = BY_SPRITE.get(area.getSpriteId());
		if (badge == null)
		{
			return;
		}
		int x0 = cx - SIZE / 2;
		int y0 = cy - SIZE / 2;
		for (int y = 0; y < SIZE; y++)
		{
			for (int x = 0; x < SIZE; x++)
			{
				boolean edge = x == 0 || y == 0 || x == SIZE - 1 || y == SIZE - 1;
				int gx = x - 2;
				int gy = y - 2;
				boolean lit = gx >= 0 && gy >= 0 && gx < 7 && gy < 7 && badge.glyph[gy].charAt(gx) == '#';
				set(image, x0 + x, y0 + y, edge ? badge.border : lit ? badge.ink : INK);
			}
		}
	}

	private static void set(BufferedImage image, int x, int y, int rgb)
	{
		if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight())
		{
			image.setRGB(x, y, 0xFF000000 | rgb);
		}
	}
}
