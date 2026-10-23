package mapstyle;

import java.awt.image.BufferedImage;
import net.runelite.cache.definitions.AreaDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.region.Location;

/**
 * Cartographic decisions the renderer defers to. Every hook receives the
 * colour RuneLite would have drawn plus the decoded cache data behind it, so
 * a style can restyle by terrain type, elevation or object rather than by
 * pixel. The defaults reproduce RuneLite's MapImageDumper exactly.
 *
 * <p>{@link Terrain} answers heights for any loaded tile, so hooks can
 * work from slope and relief, not just the tile being drawn.
 */
public interface MapStyle
{
	MapStyle RUNELITE = new MapStyle()
	{
	};

	/** Blended ground colour of a tile with an underlay. */
	default int underlay(int rgb, UnderlayDefinition underlay, int x, int y, int z, Terrain terrain)
	{
		return rgb;
	}

	/**
	 * Overlay colour (paths, floors, water, …) of a shaped or full overlay
	 * tile. 0 means "not drawn" (RuneLite's colour key for invisible
	 * overlays).
	 */
	default int overlay(int rgb, OverlayDefinition overlay, int x, int y, int z, Terrain terrain)
	{
		return rgb;
	}

	/** Wall/door line colour; RuneLite uses near-white walls and red doors. */
	default int wall(int rgb, ObjectDefinition object)
	{
		return rgb;
	}

	/**
	 * Map icon (bank, transport, shop, …) centred on pixel (cx, cy). Return
	 * true when the style has handled it, by drawing its own icon or by
	 * hiding it; false draws RuneLite's sprite.
	 */
	default boolean icon(BufferedImage image, AreaDefinition area, int cx, int cy)
	{
		return false;
	}

	/** Whether {@link #icon} ever lets a map icon through; false for a style that hides them all. */
	default boolean drawsIcons()
	{
		return true;
	}

	/**
	 * Whether a placed object is drawn at all. Applied to the decoded
	 * region data before rendering, so it works with the unforked
	 * RuneLite renderer too.
	 */
	default boolean keep(Location location, ObjectDefinition object)
	{
		return true;
	}

	/**
	 * This style with every map icon hidden, for tiles shown under a viewer
	 * that draws its own markers.
	 */
	default MapStyle withoutIcons()
	{
		MapStyle base = this;
		return new MapStyle()
		{
			@Override
			public int underlay(int rgb, UnderlayDefinition underlay, int x, int y, int z, Terrain terrain)
			{
				return base.underlay(rgb, underlay, x, y, z, terrain);
			}

			@Override
			public int overlay(int rgb, OverlayDefinition overlay, int x, int y, int z, Terrain terrain)
			{
				return base.overlay(rgb, overlay, x, y, z, terrain);
			}

			@Override
			public int wall(int rgb, ObjectDefinition object)
			{
				return base.wall(rgb, object);
			}

			@Override
			public boolean icon(BufferedImage image, AreaDefinition area, int cx, int cy)
			{
				return true;
			}

			@Override
			public boolean drawsIcons()
			{
				return false;
			}

			@Override
			public boolean keep(Location location, ObjectDefinition object)
			{
				return base.keep(location, object);
			}
		};
	}
}
