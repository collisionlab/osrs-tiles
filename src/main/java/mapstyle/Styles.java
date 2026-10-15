package mapstyle;

import java.awt.Color;
import java.util.Arrays;
import java.util.stream.Collectors;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.region.Location;

/**
 * Named styles selectable from the command line. {@code forked} picks the
 * renderer: false runs RuneLite's own MapImageDumper (only data-level
 * levers, i.e. {@link MapStyle#keep}, apply), true runs
 * {@link StyledMapImageDumper} with every hook live.
 */
enum Styles
{
	/** Unforked net.runelite.cache.MapImageDumper — the reference image. */
	RUNELITE(false, MapStyle.RUNELITE),
	/** The fork with default hooks; must be pixel-identical to RUNELITE. */
	STANDARD(true, MapStyle.RUNELITE),
	/** Unforked renderer with tree/rock/plant map-scene sprites removed. */
	DECLUTTER(false, new MapStyle()
	{
		@Override
		public boolean keep(Location location, ObjectDefinition object)
		{
			return !isScenery(location, object);
		}
	}),
	/** Fork with a desaturated paper palette, flat water and dark walls. */
	MUTED(true, new MapStyle()
	{
		@Override
		public int underlay(int rgb, UnderlayDefinition underlay, int x, int y, int z, Terrain terrain)
		{
			return mute(rgb, 0.35f, 0.62f, 0.33f);
		}

		@Override
		public int overlay(int rgb, OverlayDefinition overlay, int x, int y, int z, Terrain terrain)
		{
			if (rgb == 0)
			{
				return 0;
			}
			if (isWater(rgb))
			{
				return 0x9DBBD3;
			}
			return mute(rgb, 0.25f, 0.55f, 0.30f);
		}

		@Override
		public int wall(int rgb, ObjectDefinition object)
		{
			return object.getWallOrDoor() != 0 ? 0x8A3B2E : 0x4A4642;
		}
	}),

	/** RuneLite's colours, hillshaded from the tile heights (light from the north-west). */
	RELIEF(true, new MapStyle()
	{
		@Override
		public int underlay(int rgb, UnderlayDefinition underlay, int x, int y, int z, Terrain terrain)
		{
			return shade(rgb, hillshade(terrain, x, y, z));
		}

		@Override
		public int overlay(int rgb, OverlayDefinition overlay, int x, int y, int z, Terrain terrain)
		{
			return rgb == 0 ? 0 : shade(rgb, hillshade(terrain, x, y, z));
		}
	}),
	/** Flat tile-crisp terrain classes and terraces in the web planner's olive palette. */
	TACTICAL(true, new TacticalStyle(TacticalStyle.OLIVE)),
	/** Tactical in the planner's dark theme. */
	TACTICAL_DUSK(true, new TacticalStyle(TacticalStyle.DUSK)),
	/** Tactical as a printed field map with contour lines. */
	TACTICAL_FIELD(true, new TacticalStyle(TacticalStyle.FIELD)),
	/** Tactical in saturated Advance Wars colours. */
	TACTICAL_CLASSIC(true, new TacticalStyle(TacticalStyle.CLASSIC));

	// Hillshade parameters: sun azimuth/altitude and vertical exaggeration.
	private static final double SUN_AZIMUTH = Math.toRadians(315);
	private static final double SUN_ALTITUDE = Math.toRadians(45);
	private static final double EXAGGERATION = 1.5;

	final boolean forked;
	final MapStyle style;

	Styles(boolean forked, MapStyle style)
	{
		this.forked = forked;
		this.style = style;
	}

	String id()
	{
		return name().toLowerCase().replace('_', '-');
	}

	static Styles parse(String name)
	{
		try
		{
			return valueOf(name.trim().toUpperCase().replace('-', '_'));
		}
		catch (IllegalArgumentException e)
		{
			throw new IllegalArgumentException("unknown style '" + name + "', expected one of "
				+ Arrays.stream(values()).map(Styles::id).collect(Collectors.joining(", ")));
		}
	}

	/** Free-standing (10/11) and ground-decoration (22) objects that carry a map-scene sprite. */
	static boolean isScenery(Location location, ObjectDefinition object)
	{
		int type = location.getType();
		return object != null && object.getMapSceneID() != -1
			&& (type == 10 || type == 11 || type == 22);
	}

	static boolean isWater(int rgb)
	{
		float[] hsb = Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
		return hsb[0] > 0.50f && hsb[0] < 0.70f && hsb[1] > 0.25f;
	}

	/**
	 * Lambertian hillshade relative to flat ground: 1.0 on the flat, above 1
	 * on slopes facing the sun, below 1 on slopes facing away. Slope is
	 * Horn's 3x3 gradient over the corner heights, which smooths the
	 * per-tile noise in procedurally generated ground.
	 */
	static double hillshade(Terrain terrain, int x, int y, int z)
	{
		int here = terrain.height(x, y, z);
		double[][] e = new double[3][3];
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				// RuneLite heights grow more negative uphill; negate to get elevation.
				e[dx + 1][dy + 1] = -terrain.height(x + dx, y + dy, z, here);
			}
		}
		double east = (e[2][0] + 2 * e[2][1] + e[2][2]) - (e[0][0] + 2 * e[0][1] + e[0][2]);
		double north = (e[0][2] + 2 * e[1][2] + e[2][2]) - (e[0][0] + 2 * e[1][0] + e[2][0]);
		double gx = EXAGGERATION * east / (8.0 * Terrain.TILE);
		double gy = EXAGGERATION * north / (8.0 * Terrain.TILE);

		// Unit vector towards the sun (x east, y north, z up); azimuth clockwise from north.
		double lx = Math.cos(SUN_ALTITUDE) * Math.sin(SUN_AZIMUTH);
		double ly = Math.cos(SUN_ALTITUDE) * Math.cos(SUN_AZIMUTH);
		double lz = Math.sin(SUN_ALTITUDE);
		double lit = (-gx * lx - gy * ly + lz) / Math.sqrt(gx * gx + gy * gy + 1);
		return Math.max(0, lit) / lz;
	}

	/** Multiply brightness by {@code factor}, clamped per channel. */
	static int shade(int rgb, double factor)
	{
		int r = (int) Math.min(255, Math.round((rgb >> 16 & 0xFF) * factor));
		int g = (int) Math.min(255, Math.round((rgb >> 8 & 0xFF) * factor));
		int b = (int) Math.min(255, Math.round((rgb & 0xFF) * factor));
		return Math.max(1, r << 16 | g << 8 | b);
	}

	/** Scale saturation, then lift brightness into [floor, floor + range]. */
	static int mute(int rgb, float saturation, float floor, float range)
	{
		float[] hsb = Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
		return Color.HSBtoRGB(hsb[0], hsb[1] * saturation, floor + hsb[2] * range) & 0xFFFFFF;
	}
}
