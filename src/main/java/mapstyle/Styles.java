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
		public int underlay(int rgb, UnderlayDefinition underlay, int x, int y, int z, int height)
		{
			return mute(rgb, 0.35f, 0.62f, 0.33f);
		}

		@Override
		public int overlay(int rgb, OverlayDefinition overlay, int x, int y, int z, int height)
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
	});

	final boolean forked;
	final MapStyle style;

	Styles(boolean forked, MapStyle style)
	{
		this.forked = forked;
		this.style = style;
	}

	String id()
	{
		return name().toLowerCase();
	}

	static Styles parse(String name)
	{
		try
		{
			return valueOf(name.trim().toUpperCase());
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

	/** Scale saturation, then lift brightness into [floor, floor + range]. */
	static int mute(int rgb, float saturation, float floor, float range)
	{
		float[] hsb = Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
		return Color.HSBtoRGB(hsb[0], hsb[1] * saturation, floor + hsb[2] * range) & 0xFFFFFF;
	}
}
