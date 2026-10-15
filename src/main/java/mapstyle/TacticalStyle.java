package mapstyle;

import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.cache.definitions.AreaDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;

/**
 * A flat, tile-crisp "tactics game" map in the web planner's olive palette.
 *
 * <p>Ground is RuneLite's blended colour snapped to a few flat terrain
 * classes, so colour changes happen at tile edges instead of gradients.
 * Elevation is drawn as terraces: every {@link #TERRACE} height units is
 * one step, and tiles at the foot of a step (the step is to their
 * north or west) get a dark cliff line. A three-level hillshade adds the
 * slope within a terrace. Water is banded by distance to land, with a foam
 * line along every shore.
 */
final class TacticalStyle implements MapStyle
{
	// Web planner tokens (web/src/styles.css): --ink and --orange.
	private static final int INK = 0x141914;
	private static final int ORANGE = 0xFFAE47;

	private static final int GRASS_BRIGHT = 0x9DB04F;
	private static final int GRASS = 0x7F9440;
	private static final int GRASS_DARK = 0x5F7232;
	private static final int SCRUB = 0x9A9443;
	private static final int SCRUB_DARK = 0x7A7636;
	private static final int SAND = 0xD3C58A;
	private static final int DIRT = 0x8A7044;
	private static final int DIRT_DARK = 0x5E4B2E;
	private static final int ROCK = 0x7D7B68;
	// Water by distance to land: foam line, two shallow bands, open water.
	private static final int FOAM = 0x86C4BB;
	private static final int SHALLOW = 0x5D9EA0;
	private static final int MID = 0x4B8D93;
	private static final int DEEP = 0x3D7A85;
	private static final int WAVE = 0x5A97A0;
	/** How far (tiles) to look for land; beyond it a tile is open water. */
	private static final int SHORE_REACH = 4;
	private static final int ROAD = 0xB5B29A;
	private static final int PATH = 0xB89A62;

	/** Height units per terrace step (128 = one tile). */
	private static final int TERRACE = 192;
	private static final double CLIFF_SHADOW = 0.70;
	private static final double LIT = 1.12;
	private static final double SHADED = 0.86;

	@Override
	public int underlay(int rgb, UnderlayDefinition underlay, int x, int y, int z, Terrain terrain)
	{
		return relief(ground(rgb), x, y, z, terrain);
	}

	@Override
	public int overlay(int rgb, OverlayDefinition overlay, int x, int y, int z, Terrain terrain)
	{
		if (rgb == 0)
		{
			return 0;
		}
		if (Styles.isWater(rgb))
		{
			return water(x, y, z, terrain);
		}
		return relief(surface(rgb), x, y, z, terrain);
	}

	@Override
	public int wall(int rgb, ObjectDefinition object)
	{
		return object.getWallOrDoor() != 0 ? ORANGE : INK;
	}

	@Override
	public boolean icon(BufferedImage image, AreaDefinition area, int cx, int cy)
	{
		TacticalIcons.draw(image, area, cx, cy);
		return true;
	}

	/**
	 * Water banded by squared distance to the nearest land tile: a foam
	 * line on the first ring, shallows, then open water with sparse
	 * two-tile wave dashes.
	 */
	private static int water(int x, int y, int z, Terrain terrain)
	{
		int nearest = Integer.MAX_VALUE;
		for (int dx = -SHORE_REACH; dx <= SHORE_REACH; dx++)
		{
			for (int dy = -SHORE_REACH; dy <= SHORE_REACH; dy++)
			{
				int d2 = dx * dx + dy * dy;
				if (d2 < nearest && isLand(terrain.overlay(x + dx, y + dy, z)))
				{
					nearest = d2;
				}
			}
		}
		if (nearest <= 2)
		{
			return FOAM;
		}
		if (nearest <= 5)
		{
			return SHALLOW;
		}
		if (nearest <= 10)
		{
			return MID;
		}
		// Dashes two tiles long; the hash keeps them sparse and stable.
		int h = (x >> 1) * 73856093 ^ y * 19349663;
		return Math.floorMod(h, 29) == 0 ? WAVE : DEEP;
	}

	/** Land is any loaded tile without a water overlay; unloaded tiles count as sea. */
	private static boolean isLand(int overlayRgb)
	{
		return overlayRgb >= 0 && (overlayRgb == 0 || !Styles.isWater(overlayRgb));
	}

	/**
	 * Terrain class of RuneLite's blended ground colour. Classifying the
	 * blend rather than the tile's own definition turns per-tile noise in the
	 * map data into smooth class regions with crisp tile-aligned edges.
	 * Thresholds come from a histogram of the Lumbridge and Varlamore renders.
	 */
	static int ground(int rgb)
	{
		float[] hsb = hsb(rgb);
		float hue = hsb[0];
		float sat = hsb[1];
		float bri = hsb[2];
		if (bri >= 0.7f && sat < 0.7f)
		{
			return SAND;
		}
		if (sat < 0.3f)
		{
			return ROCK;
		}
		if (hue < 0.115f)
		{
			return bri > 0.45f ? DIRT : DIRT_DARK;
		}
		if (hue < 0.165f)
		{
			return bri > 0.5f ? SCRUB : SCRUB_DARK;
		}
		return bri > 0.55f ? GRASS_BRIGHT : bri > 0.35f ? GRASS : GRASS_DARK;
	}

	/** Class of a non-water overlay: paved/stone, light path or dark ground. */
	static int surface(int rgb)
	{
		float[] hsb = hsb(rgb);
		if (hsb[1] < 0.25f)
		{
			return ROAD;
		}
		if (hsb[2] > 0.5f)
		{
			return PATH;
		}
		return hsb[2] > 0.3f ? DIRT : DIRT_DARK;
	}

	private static int relief(int rgb, int x, int y, int z, Terrain terrain)
	{
		int here = terrain.height(x, y, z);
		int step = terrace(here);
		if (terrace(terrain.height(x, y + 1, z, here)) > step || terrace(terrain.height(x - 1, y, z, here)) > step)
		{
			return Styles.shade(rgb, CLIFF_SHADOW);
		}
		double lit = Styles.hillshade(terrain, x, y, z);
		if (lit > 1.15)
		{
			return Styles.shade(rgb, LIT);
		}
		if (lit < 0.85)
		{
			return Styles.shade(rgb, SHADED);
		}
		return rgb;
	}

	/** Terrace index; RuneLite heights are negative uphill. */
	private static int terrace(int height)
	{
		return Math.floorDiv(-height, TERRACE);
	}

	private static float[] hsb(int rgb)
	{
		return Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
	}
}
