package mapstyle;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import net.runelite.cache.definitions.AreaDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.region.Location;

/**
 * A flat, tile-crisp "tactics game" map, coloured by a {@link Palette}.
 *
 * <p>Ground is RuneLite's blended colour snapped to a few flat terrain
 * classes, so colour changes happen at tile edges instead of gradients.
 * Elevation is drawn as terraces of {@link #TERRACE} height units: either
 * as a cliff shadow on tiles at the foot of a step (the step is to their
 * north or west), or as contour lines on every terrace edge. A three-level
 * hillshade adds the slope within a terrace. Water is banded by distance to
 * land, with a foam line along every shore and a darker rim on the land
 * side.
 */
final class TacticalStyle implements MapStyle
{
	/** Every colour and relief setting of one variant. */
	static final class Palette
	{
		int grassBright;
		int grass;
		int grassDark;
		int scrub;
		int scrubDark;
		int sand;
		int dirt;
		int dirtDark;
		int rock;
		/** Dark, nearly colourless ground: the Wilderness. */
		int ash;
		/** Very bright, nearly colourless ground. */
		int snow;
		/** Teal-green ground: Morytania's swamps. */
		int swamp;
		int swampDark;
		/** Bright orange-yellow overlays. */
		int lava;
		/** Roofed floors: stone (and anything grey) and wood (brown floors). */
		int buildingStone;
		int buildingWood;
		/** Brightness of ground in a building's shadow (south and east of it). */
		double buildingShadow = 0.74;
		int road;
		int path;
		int foam;
		int shallow;
		int mid;
		int deep;
		/** Wave-dash colour in open water; equal to {@link #deep} for none. */
		int wave;
		int wall;
		int door;
		/** Contour-line colour on every terrace edge, or -1 for a cliff shadow instead. */
		int contour = -1;
		double cliffShadow = 0.70;
		/** Brightness of land tiles touching water: the land side of the coastline. */
		double coastShade = 0.72;
		double lit = 1.12;
		double shaded = 0.86;
	}

	// Web planner tokens (web/src/styles.css).
	private static final int INK = 0x141914;
	private static final int MUTED = 0xA4AF95;
	private static final int CYAN = 0x77E2D4;
	private static final int ORANGE = 0xFFAE47;

	/** The planner's olive daylight look. */
	static final Palette OLIVE = new Palette();
	/** The planner's dark theme: low-light olives, an ink sea with a cyan shoreline. */
	static final Palette DUSK = new Palette();
	/** A printed field map: khaki paper, brown contours, pale water, no waves. */
	static final Palette FIELD = new Palette();
	/** Saturated Advance Wars colours: bright plains, blue sea, white surf. */
	static final Palette CLASSIC = new Palette();

	static
	{
		Palette p = OLIVE;
		p.grassBright = 0x9DB04F;
		p.grass = 0x7F9440;
		p.grassDark = 0x5F7232;
		p.scrub = 0x9A9443;
		p.scrubDark = 0x7A7636;
		p.sand = 0xD3C58A;
		p.dirt = 0x8A7044;
		p.dirtDark = 0x5E4B2E;
		p.rock = 0x7D7B68;
		p.ash = 0x4A4743;
		p.snow = 0xE6E8DC;
		p.swamp = 0x5E7A5A;
		p.swampDark = 0x4A6249;
		p.lava = 0xE8642C;
		p.buildingStone = 0xC9C2A8;
		p.buildingWood = 0xA88A5E;
		p.road = 0xB5B29A;
		p.path = 0xB89A62;
		p.foam = 0x86C4BB;
		p.shallow = 0x5D9EA0;
		p.mid = 0x4B8D93;
		p.deep = 0x3D7A85;
		p.wave = 0x5A97A0;
		p.wall = INK;
		p.door = ORANGE;

		p = DUSK;
		p.grassBright = 0x55642F;
		p.grass = 0x485628;
		p.grassDark = 0x3A4622;
		p.scrub = 0x59562B;
		p.scrubDark = 0x4A4825;
		p.sand = 0x7F7754;
		p.dirt = 0x564630;
		p.dirtDark = 0x3E3324;
		p.rock = 0x4E4D43;
		p.ash = 0x262624;
		p.snow = 0x9AA095;
		p.swamp = 0x34483A;
		p.swampDark = 0x2A3A2F;
		p.lava = 0xFF7A2F;
		p.buildingStone = 0x6F6B5C;
		p.buildingWood = 0x5E4C35;
		p.road = 0x75735F;
		p.path = 0x6E5E3E;
		p.foam = CYAN;
		p.shallow = 0x2D5B57;
		p.mid = 0x224643;
		p.deep = 0x1A3533;
		p.wave = 0x2B4F4B;
		p.wall = MUTED;
		p.door = ORANGE;
		p.cliffShadow = 0.62;
		p.lit = 1.18;
		p.shaded = 0.82;

		p = FIELD;
		p.grassBright = 0xD4D7A8;
		p.grass = 0xC5CB98;
		p.grassDark = 0xAFB985;
		p.scrub = 0xD8D2A2;
		p.scrubDark = 0xC7C190;
		p.sand = 0xEDE4C2;
		p.dirt = 0xCDB98E;
		p.dirtDark = 0xB49F78;
		p.rock = 0xBDB8A5;
		p.ash = 0xA8A496;
		p.snow = 0xFAFAF2;
		p.swamp = 0xB5C4A8;
		p.swampDark = 0xA5B598;
		p.lava = 0xD9663A;
		p.buildingStone = 0xF2EBD6;
		p.buildingWood = 0xDCC49A;
		p.road = 0xF4F0E0;
		p.path = 0xE2CFA0;
		p.foam = 0x5A86A6;
		p.shallow = 0xB8D2DC;
		p.mid = 0xB8D2DC;
		p.deep = 0xB8D2DC;
		p.wave = 0xB8D2DC;
		p.wall = 0x4A3B2C;
		p.door = 0xB4442E;
		p.contour = 0x9A7A55;
		p.coastShade = 0.85;
		p.lit = 1.0;
		p.shaded = 1.0;

		p = CLASSIC;
		p.grassBright = 0x98D858;
		p.grass = 0x78C048;
		p.grassDark = 0x3F8F3A;
		p.scrub = 0xC8C050;
		p.scrubDark = 0xA8A040;
		p.sand = 0xF0D888;
		p.dirt = 0xB08850;
		p.dirtDark = 0x806038;
		p.rock = 0xA0A0A0;
		p.ash = 0x585058;
		p.snow = 0xF8F8F8;
		p.swamp = 0x4F9A70;
		p.swampDark = 0x3B7A58;
		p.lava = 0xF05020;
		p.buildingStone = 0xE8E0C8;
		p.buildingWood = 0xC89858;
		p.road = 0xD0D0C8;
		p.path = 0xD8B878;
		p.foam = 0xE8F8FF;
		p.shallow = 0x58A0F0;
		p.mid = 0x3878E0;
		p.deep = 0x2860D0;
		p.wave = 0x78B8F8;
		p.wall = 0x202020;
		p.door = 0xE04030;
		p.cliffShadow = 0.6;
		p.lit = 1.1;
		p.shaded = 0.85;
	}

	/** How far (tiles) to look for land; beyond it a tile is open water. */
	private static final int SHORE_REACH = 4;
	/** Lowest map-scene sprite of Sailing's sea decorations. */
	private static final int SEA_DECOR_SCENE = 201;
	/** Height units per terrace step (128 = one tile). */
	private static final int TERRACE = 192;

	private final Palette p;

	TacticalStyle(Palette palette)
	{
		this.p = palette;
	}

	@Override
	public int underlay(int rgb, UnderlayDefinition underlay, int x, int y, int z, Terrain terrain)
	{
		if (terrain.roofed(x, y, z))
		{
			return building(rgb, x, y, z, terrain);
		}
		return shadow(coast(relief(ground(rgb), x, y, z, terrain), x, y, z, terrain), x, y, z, terrain);
	}

	@Override
	public int overlay(int rgb, OverlayDefinition overlay, int x, int y, int z, Terrain terrain)
	{
		if (rgb == 0)
		{
			return 0;
		}
		if (isWater(overlay, rgb))
		{
			return water(x, y, z, terrain);
		}
		if (terrain.roofed(x, y, z))
		{
			return building(rgb, x, y, z, terrain);
		}
		return shadow(coast(relief(surface(rgb), x, y, z, terrain), x, y, z, terrain), x, y, z, terrain);
	}

	/**
	 * A roofed floor tile. Grey floors become flagstones and brown floors
	 * planks, each textured per tile in three close tones; coloured floors
	 * (carpets) keep a muted version of their hue. Tiles along a building's
	 * north and west walls are lit, so with {@link #shadow} on the other
	 * side buildings read as raised blocks. No terrain relief applies indoors.
	 */
	private int building(int rgb, int x, int y, int z, Terrain terrain)
	{
		float[] hsb = hsb(rgb);
		int floor;
		int tone;
		if (hsb[1] < 0.25f)
		{
			floor = p.buildingStone;
			// Flagstones two tiles wide, staggered every row.
			tone = hash((x + (y & 1)) >> 1, y);
		}
		else if (hsb[0] < 0.14f)
		{
			floor = p.buildingWood;
			// Planks running east-west, four tiles long, staggered by row.
			tone = hash((x + 2 * (y % 3)) >> 2, y);
		}
		else
		{
			float[] stone = hsb(p.buildingStone);
			floor = Color.HSBtoRGB(hsb[0], Math.min(hsb[1], 0.4f) * 0.7f, stone[2] * 0.85f) & 0xFFFFFF;
			tone = hash(x, y);
		}
		double shade = TEXTURE[Math.floorMod(tone, TEXTURE.length)];
		if (!terrain.roofed(x, y + 1, z) || !terrain.roofed(x - 1, y, z))
		{
			shade *= BUILDING_LIT_EDGE;
		}
		return Styles.shade(floor, shade);
	}

	/** Ground just south or east of a building lies in its shadow. */
	private int shadow(int rgb, int x, int y, int z, Terrain terrain)
	{
		if (terrain.roofed(x, y + 1, z) || terrain.roofed(x - 1, y, z))
		{
			return Styles.shade(rgb, p.buildingShadow);
		}
		return rgb;
	}

	/** Per-tile floor tones; mostly the base colour, with a lighter and a darker variant. */
	private static final double[] TEXTURE = {1.0, 1.0, 0.94, 1.05, 1.0, 0.97};
	private static final double BUILDING_LIT_EDGE = 1.08;

	private static int hash(int a, int b)
	{
		int h = a * 73856093 ^ b * 19349663;
		return h ^ h >>> 13;
	}

	/**
	 * Darkens land on the shoreline: a tile that is itself partly water (a
	 * shaped overlay) or has water on any side. Gives every coast and river
	 * bank a land-side edge to match the foam line on the water side.
	 */
	private int coast(int rgb, int x, int y, int z, Terrain terrain)
	{
		if (isWaterAt(terrain, x, y, z)
			|| isWaterAt(terrain, x + 1, y, z) || isWaterAt(terrain, x - 1, y, z)
			|| isWaterAt(terrain, x, y + 1, z) || isWaterAt(terrain, x, y - 1, z))
		{
			return Styles.shade(rgb, p.coastShade);
		}
		return rgb;
	}

	private static boolean isWaterAt(Terrain terrain, int x, int y, int z)
	{
		int rgb = terrain.overlay(x, y, z);
		return rgb > 0 && isWater(terrain.overlayDefinition(x, y, z), rgb);
	}

	@Override
	public int wall(int rgb, ObjectDefinition object)
	{
		return object.getWallOrDoor() != 0 ? p.door : p.wall;
	}

	/**
	 * Drops Sailing's sea decorations: nameless scenery with map-scene
	 * sprites from {@link #SEA_DECOR_SCENE} up. Across the whole surface these
	 * sit almost only on water tiles (2 of ~189,000 on bare ground), where they
	 * texture the regional seas and read as noise in flat tactical water.
	 * Nameless land scenery (plants, rocks) uses lower sprites and stays.
	 * RuneLite's default object name is the string "null".
	 */
	@Override
	public boolean keep(Location location, ObjectDefinition object)
	{
		return !Styles.isScenery(location, object)
			|| object.getMapSceneID() < SEA_DECOR_SCENE
			|| !"null".equals(object.getName());
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
	 * two-tile wave dashes. The bands are tinted per sea (see
	 * {@link #seaTint}) from the water definitions in the same window.
	 */
	private int water(int x, int y, int z, Terrain terrain)
	{
		int nearest = Integer.MAX_VALUE;
		float hue = 0;
		float sat = 0;
		float bri = 0;
		int seaTiles = 0;
		int frozenTiles = 0;
		for (int dx = -SHORE_REACH; dx <= SHORE_REACH; dx++)
		{
			for (int dy = -SHORE_REACH; dy <= SHORE_REACH; dy++)
			{
				int d2 = dx * dx + dy * dy;
				if (d2 < nearest && isLand(terrain, x + dx, y + dy, z))
				{
					nearest = d2;
				}
				OverlayDefinition sea = terrain.overlayDefinition(x + dx, y + dy, z);
				float[] tint = sea == null ? null : seaColour(sea);
				if (tint != null)
				{
					if (isFrozen(sea))
					{
						frozenTiles++;
					}
					hue += tint[0];
					sat += tint[1];
					bri += tint[2];
					seaTiles++;
				}
			}
		}
		int band;
		if (nearest <= 2)
		{
			band = p.foam;
		}
		else if (nearest <= 5)
		{
			band = p.shallow;
		}
		else if (nearest <= 10)
		{
			band = p.mid;
		}
		else
		{
			// Dashes two tiles long; the hash keeps them sparse and stable.
			int h = (x >> 1) * 73856093 ^ y * 19349663;
			band = Math.floorMod(h, 29) == 0 ? p.wave : p.deep;
		}
		if (seaTiles == 0)
		{
			return band;
		}
		float frozenShare = (float) frozenTiles / seaTiles;
		float strength = 1 - frozenShare * (1 - FROZEN_TINT_STRENGTH);
		return seaTint(band, hue / seaTiles, sat / seaTiles, bri / seaTiles, strength);
	}

	// Ordinary ocean (texture 133, #4a5d8a): the reference every sea is tinted against.
	private static final float[] OCEAN = hsb(0x4A5D8A);
	private final Map<Integer, float[]> seaColours = new HashMap<>();

	/**
	 * The frozen sea north of the Fremennik Province (textures 160-164,
	 * identified by where they are used). Its own colour is too close to
	 * ordinary ocean to stand out, so it is tinted against this pale ice
	 * colour instead.
	 */
	private static final int FROZEN_FIRST_TEXTURE = 160;
	private static final int FROZEN_LAST_TEXTURE = 164;
	private static final int ICE = 0x9DACCF;
	/** Share of the frozen sea's difference from ordinary ocean that is applied; at full strength it is too white. */
	private static final float FROZEN_TINT_STRENGTH = 0.3f;

	/**
	 * HSB of a water definition's own colour (the secondary colour when it
	 * has one; texture 1 water has a black primary), or null for non-water.
	 */
	private float[] seaColour(OverlayDefinition overlay)
	{
		return seaColours.computeIfAbsent(overlay.getId(), id ->
		{
			int rgb = overlay.getSecondaryRgbColor() != -1 ? overlay.getSecondaryRgbColor() : overlay.getRgbColor();
			if (!isWater(overlay, rgb))
			{
				return null;
			}
			return hsb(isFrozen(overlay) ? ICE : rgb);
		});
	}

	private static boolean isFrozen(OverlayDefinition overlay)
	{
		int texture = overlay.getTexture();
		return texture >= FROZEN_FIRST_TEXTURE && texture <= FROZEN_LAST_TEXTURE;
	}

	/**
	 * Shifts a water band by how far the local sea's colour is from ordinary
	 * ocean: hue by the difference, saturation and brightness by the ratio
	 * (brighter seas also lose saturation, so they read as icy, not vivid).
	 * Small differences are ignored, so ordinary ocean keeps the palette's
	 * exact bands, and the rest is stepped so each sea is a flat region. This
	 * keeps the frozen, fetid, dark and Sailing seas distinct in every
	 * palette without naming them.
	 */
	private static int seaTint(int band, float hue, float sat, float bri, float strength)
	{
		float dh = step(hue - OCEAN[0], 0.035f, 0.02f) * 0.8f;
		float sr = step(sat / OCEAN[1] - 1, 0.25f, 0.1f) + 1;
		float br = step(bri / OCEAN[2] - 1, 0.25f, 0.1f) + 1;
		if (dh == 0 && sr == 1 && br == 1)
		{
			return band;
		}
		dh *= strength;
		sr = 1 + (sr - 1) * strength;
		br = 1 + (br - 1) * strength;
		float[] b = hsb(band);
		float h = b[0] + dh;
		// Brighter seas read as paler (the frozen sea), darker ones as deeper.
		float s = clamp(b[1] * (float) (Math.pow(sr, 0.7) / Math.pow(br, 1.5)), 0, 1);
		float v = clamp(b[2] * (float) Math.pow(br, 0.6), 0.08f, 1);
		return Color.HSBtoRGB(h - (float) Math.floor(h), s, v) & 0xFFFFFF;
	}

	/** 0 inside the dead zone, else rounded to multiples of {@code step}. */
	private static float step(float value, float deadZone, float step)
	{
		return Math.abs(value) < deadZone ? 0 : Math.round(value / step) * step;
	}

	private static float clamp(float v, float lo, float hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}

	/** Land is any loaded tile without a water overlay; unloaded tiles count as sea. */
	private static boolean isLand(Terrain terrain, int x, int y, int z)
	{
		int rgb = terrain.overlay(x, y, z);
		return rgb == 0 || rgb > 0 && !isWater(terrain.overlayDefinition(x, y, z), rgb);
	}

	/**
	 * Water by RuneLite's displayed colour, or by definition: a blue or teal
	 * base colour on a textured or two-colour overlay. The definition test catches
	 * Sailing's regional seas, whose textures average to colours outside the
	 * blue window (teal textures 180-184, purple-blue 145-149), so every sea
	 * gets the same water bands.
	 */
	static boolean isWater(OverlayDefinition overlay, int rgb)
	{
		if (Styles.isWater(rgb))
		{
			return true;
		}
		if (overlay == null || overlay.getTexture() < 0 && overlay.getSecondaryRgbColor() == -1)
		{
			return false;
		}
		float[] hsb = hsb(overlay.getRgbColor());
		// Textured water may be nearly grey: the fetid sea west of the Feldip
		// Hills (textures 175-179) is ~11% saturated.
		float minSaturation = overlay.getTexture() >= 0 ? 0.08f : 0.2f;
		return hsb[0] > 0.45f && hsb[0] < 0.75f && hsb[1] > minSaturation;
	}

	/**
	 * Terrain class of RuneLite's blended ground colour. Classifying the
	 * blend rather than the tile's own definition turns per-tile noise in the
	 * map data into smooth class regions with crisp tile-aligned edges.
	 * Thresholds come from histograms of RuneLite renders of Lumbridge,
	 * Varlamore, the Wilderness, Morytania and Trollheim.
	 */
	int ground(int rgb)
	{
		float[] hsb = hsb(rgb);
		float hue = hsb[0];
		float sat = hsb[1];
		float bri = hsb[2];
		if (bri > 0.75f && sat < 0.12f)
		{
			return p.snow;
		}
		if (bri >= 0.7f && sat < 0.7f)
		{
			return p.sand;
		}
		if (sat < 0.3f)
		{
			return bri < 0.3f ? p.ash : p.rock;
		}
		if (hue >= 0.32f && hue < 0.55f)
		{
			return bri > 0.45f ? p.swamp : p.swampDark;
		}
		if (hue < 0.115f)
		{
			return bri > 0.45f ? p.dirt : p.dirtDark;
		}
		if (hue < 0.165f)
		{
			return bri > 0.5f ? p.scrub : p.scrubDark;
		}
		return bri > 0.55f ? p.grassBright : bri > 0.35f ? p.grass : p.grassDark;
	}

	/** Class of a non-water overlay: lava, paved/stone, light path or dark ground. */
	int surface(int rgb)
	{
		float[] hsb = hsb(rgb);
		if (hsb[0] < 0.17f && hsb[1] > 0.75f && hsb[2] > 0.85f)
		{
			return p.lava;
		}
		if (hsb[1] < 0.25f)
		{
			return p.road;
		}
		if (hsb[2] > 0.5f)
		{
			return p.path;
		}
		return hsb[2] > 0.3f ? p.dirt : p.dirtDark;
	}

	private int relief(int rgb, int x, int y, int z, Terrain terrain)
	{
		int here = terrain.height(x, y, z);
		int step = terrace(here);
		boolean higherNorthOrWest = terrace(terrain.height(x, y + 1, z, here)) > step
			|| terrace(terrain.height(x - 1, y, z, here)) > step;
		if (p.contour >= 0)
		{
			if (higherNorthOrWest
				|| terrace(terrain.height(x, y - 1, z, here)) > step
				|| terrace(terrain.height(x + 1, y, z, here)) > step)
			{
				return p.contour;
			}
		}
		else if (higherNorthOrWest)
		{
			return Styles.shade(rgb, p.cliffShadow);
		}
		double lit = Styles.hillshade(terrain, x, y, z);
		if (lit > 1.15)
		{
			return Styles.shade(rgb, p.lit);
		}
		if (lit < 0.85)
		{
			return Styles.shade(rgb, p.shaded);
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
