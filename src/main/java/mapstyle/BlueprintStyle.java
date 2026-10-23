package mapstyle;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import net.runelite.cache.EntityOpsDefinition;
import net.runelite.cache.definitions.AreaDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.region.Location;

/**
 * An architectural drawing of the map, for investigating dungeons, caves and
 * multi-floor buildings under diagnostic overlays.
 *
 * <p>Every tile is one flat colour from a small set of classes, chosen per
 * tile from RuneLite's colour and the tile's own cache data, so a corridor,
 * a room or a shoreline keeps the exact shape (overlay shapes and rotations
 * included) the cache gives it. There is no relief, texture or per-tile
 * variation. Walls are pale lines, door-like walls a warm accent, and map
 * icons and decorative scenery are left out: viewers draw their own markers.
 *
 * <p>Three kinds of dark stay apart: an invisible overlay (the cache's own
 * "nothing drawn here", what fills the space between caves) is {@link #VOID};
 * terrain the cache renders near black is {@link #DARK}; a tile with no
 * terrain at all, or a map square the cache lacks, stays the renderer's black.
 * None of them says whether a tile is walkable: that is the collision map's
 * business, and the style never reads it.
 */
final class BlueprintStyle implements MapStyle
{
	/** Invisible overlays: the cache's explicit void. */
	static final int VOID = 0x121C21;
	/** Terrain the cache renders near black: pits, chasms, unlit floors. */
	static final int DARK = 0x283337;
	static final int GRASS = 0x526B69;
	static final int DIRT = 0x6B6455;
	static final int SAND = 0x8C8670;
	static final int SNOW = 0x9AA6A6;
	/** Grey ground: rock, cave floors, the Wilderness. */
	static final int ROCK = 0x5A646B;
	/** Grey overlays outside buildings: paved roads, flagged corridors. */
	static final int PAVED = 0x9A9C8B;
	/** Brown or light overlays outside buildings: trails, dirt paths. */
	static final int TRAIL = 0x878271;
	static final int STONE_FLOOR = 0x73858B;
	static final int WOOD_FLOOR = 0x817A6A;
	/** Coloured floors under a roof: carpets, tiles, rugs. */
	static final int OTHER_FLOOR = 0x777684;
	static final int WATER = 0x344D62;
	static final int LAVA = 0xAD7350;
	/** Terrain none of the classes describe; never passed off as one of them. */
	static final int UNCLASSIFIED = 0x666B6D;
	static final int WALL = 0xD7E0DA;
	static final int DOOR = 0xDBB77A;

	/** Brightness below which drawn terrain is {@link #DARK}. */
	private static final float DARK_BRIGHTNESS = 0.11f;

	/**
	 * First words of right-click actions that move the player through or
	 * between places. A map-scene object with one of them (a ladder, a
	 * crevice, a rockslide, a bridge, a portal) is part of the map's
	 * structure; one without (a tree, a mining rock, a fountain, an unnamed
	 * decoration) is scenery.
	 */
	static final List<String> PASSAGE_ACTIONS = List.of(
		"climb", "enter", "exit", "leave", "squeeze", "crawl", "cross", "jump", "pass", "go",
		"walk", "swing", "balance", "board", "open", "travel", "teleport", "descend", "ascend",
		"step", "vault", "slide", "hop", "use", "dive", "swim", "push", "navigate");

	@Override
	public int underlay(int rgb, UnderlayDefinition underlay, int x, int y, int z, Terrain terrain)
	{
		float[] hsb = hsb(rgb);
		if (hsb[2] < DARK_BRIGHTNESS)
		{
			return DARK;
		}
		if (terrain.roofed(x, y, z))
		{
			return floor(hsb);
		}
		return ground(hsb, terrain.sandy(x, y, z));
	}

	@Override
	public int overlay(int rgb, OverlayDefinition overlay, int x, int y, int z, Terrain terrain)
	{
		if (rgb == 0)
		{
			return VOID;
		}
		float[] hsb = hsb(rgb);
		if (hsb[2] < DARK_BRIGHTNESS)
		{
			return DARK;
		}
		if (isWater(overlay, rgb, hsb))
		{
			return WATER;
		}
		if (TacticalStyle.isLava(rgb))
		{
			return LAVA;
		}
		if (terrain.roofed(x, y, z))
		{
			return floor(hsb);
		}
		return surface(hsb);
	}

	/**
	 * Water as the tactical style finds it ({@link TacticalStyle#isWater}),
	 * except a plain violet-blue overlay: one colour, no texture, hue from
	 * {@link #VIOLET}. That is stone, not water: the Catacombs of Kourend's
	 * floors (#423f73) are the main use. Darkness is tested first, since a
	 * blue-black void (the Chasm of Fire draws #000001) has a water hue.
	 */
	static boolean isWater(OverlayDefinition overlay, int rgb, float[] hsb)
	{
		if (!TacticalStyle.isWater(overlay, rgb))
		{
			return false;
		}
		boolean plain = overlay == null || overlay.getTexture() < 0 && overlay.getSecondaryRgbColor() == -1;
		return !plain || hsb[0] < VIOLET;
	}

	/** Hue from which a plain overlay is violet rather than water blue. */
	private static final float VIOLET = 0.665f;

	/** A floor under a roof: grey is stone, brown is wood, anything else another surface. */
	static int floor(float[] hsb)
	{
		if (hsb[1] < 0.25f)
		{
			return STONE_FLOOR;
		}
		if (hsb[0] < 0.14f)
		{
			return WOOD_FLOOR;
		}
		return OTHER_FLOOR;
	}

	/**
	 * Class of RuneLite's blended ground colour outside buildings. Blending
	 * smooths the per-tile alternation of ground definitions, so the classes
	 * come out as regions with tile-stepped edges. {@code sandy} is
	 * {@link Terrain#sandy}: the desert and Varlamore's grass blend alike.
	 */
	static int ground(float[] hsb, boolean sandy)
	{
		float hue = hsb[0];
		float sat = hsb[1];
		float bri = hsb[2];
		if (bri > 0.75f && sat < 0.12f)
		{
			return SNOW;
		}
		// Dim, weakly coloured ground is rock too: cave floors blend to dark
		// browns that would otherwise flicker between rock and dirt.
		if (sat < 0.25f || bri < 0.25f && sat < 0.45f)
		{
			return ROCK;
		}
		if (hue < 0.165f)
		{
			return sandy || bri >= 0.7f && sat < 0.7f ? SAND : DIRT;
		}
		if (hue < 0.55f)
		{
			return GRASS;
		}
		return UNCLASSIFIED;
	}

	/** Class of a drawn overlay outside buildings that is neither water nor lava. */
	static int surface(float[] hsb)
	{
		if (hsb[1] < 0.25f)
		{
			return PAVED;
		}
		if (hsb[0] < 0.165f)
		{
			return hsb[2] > 0.45f ? TRAIL : DIRT;
		}
		if (hsb[0] < 0.45f)
		{
			return GRASS;
		}
		return UNCLASSIFIED;
	}

	/**
	 * Door-like walls in {@link #DOOR}. RuneLite's red doors are walls whose
	 * {@code wallOrDoor} is set, but that flag is the definition's
	 * "interactive" bit (opcode 19, else true whenever the object has any
	 * action), and some walls set it with no action at all. Here a wall is
	 * door-like when it also has a right-click action: a door, a gate, a
	 * loose railing, a crumbling wall. That says the wall is something to use,
	 * not that it opens for any given player.
	 */
	@Override
	public int wall(int rgb, ObjectDefinition object)
	{
		return object.getWallOrDoor() != 0 && hasAction(object) ? DOOR : WALL;
	}

	/** Every map icon is hidden: viewers draw their own, richer markers. */
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

	/**
	 * Drops map-scene scenery ({@link Styles#isScenery}: trees, plants, rocks,
	 * statues, Sailing's sea decorations) unless it is a passage, i.e. has an
	 * action from {@link #PASSAGE_ACTIONS}. Walls (types 0-3 and 9),
	 * including those drawn with a map-scene sprite, are never dropped.
	 */
	@Override
	public boolean keep(Location location, ObjectDefinition object)
	{
		return !Styles.isScenery(location, object) || passage(object);
	}

	static boolean passage(ObjectDefinition object)
	{
		for (String action : actions(object))
		{
			String verb = action.toLowerCase(Locale.ROOT).split("[- ]", 2)[0];
			if (PASSAGE_ACTIONS.contains(verb))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean hasAction(ObjectDefinition object)
	{
		return !actions(object).isEmpty();
	}

	/** The object's right-click actions, without blanks or "Examine"-like nulls. */
	static List<String> actions(ObjectDefinition object)
	{
		EntityOpsDefinition ops = object.getOps();
		if (ops == null || ops.ops == null)
		{
			return List.of();
		}
		return ops.ops.stream()
			.filter(op -> op != null && op.text != null && !op.text.isBlank() && !"null".equals(op.text))
			.map(op -> op.text)
			.collect(java.util.stream.Collectors.toList());
	}

	private static float[] hsb(int rgb)
	{
		return Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
	}
}
