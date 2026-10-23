package mapstyle;

import com.google.gson.GsonBuilder;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.imageio.ImageIO;
import net.runelite.cache.MapImageDumper;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.OverlayManager;
import net.runelite.cache.UnderlayManager;
import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.XteaKeyManager;

/**
 * Writes map tiles in the layout of Explv's osrs_map_tiles, the layer the
 * route planner already uses, so a style is a drop-in URL swap, for every
 * plane. See docs/tiles.md.
 *
 * <p>The layout (Leaflet, EPSG:3857, TMS rows counted from the south,
 * 256px tiles):
 * <ul>
 * <li>zoom 8 is native: 4px per game tile, so tile (c, r) is exactly map
 * square (15 + c, 19 + r);</li>
 * <li>zooms 7..3 average 2x2 blocks of the zoom above;</li>
 * <li>zooms 9..11 are not written: viewers upscale zoom 8
 * ({@code maxNativeZoom 8});</li>
 * <li>plane 0 is at the style's root, as in Explv's set; planes 1..3 are
 * under {@code p1/}, {@code p2/} and {@code p3/}.</li>
 * </ul>
 * A square gets a tile on a plane when it has visible terrain there (the
 * rule inset regions are cut by, see {@link InsetTopology}), even if that
 * renders black, so every inset area has its tiles. Plane 0 also gets a tile
 * for any other map square the cache has unless it renders all black.
 * Viewers show a missing tile as black.
 *
 * <pre>
 * Tiles --cache DIR --xteas FILE --out DIR [--styles standard,tactical]
 *       [--no-icons tactical,blueprint] [--planes 0,1,2,3] [--block 16] [--commit SHA] [--cache-id N]
 * </pre>
 *
 * Writes {@code <out>/<style>/[p<plane>/]<z>/<x>/<y>.png}, plus per style
 * {@code manifest.json} (inputs, extent, per-plane counts, missing keys) and
 * {@code index.json} (sha256 per tile path).
 */
public final class Tiles
{
	static final int TILE = 256;
	static final int NATIVE_ZOOM = 8;
	static final int MIN_ZOOM = 3;
	/** Map square at zoom-8 tile (0, 0). */
	static final int ORIGIN_RX = 15;
	static final int ORIGIN_RY = 19;
	/** Exclusive upper bound of the map archive ids RegionLoader scans. */
	static final int MAP_IDS = 25287;

	private Tiles()
	{
	}

	public static void main(String[] argv) throws Exception
	{
		Args args;
		try
		{
			args = Args.parse(argv);
		}
		catch (IllegalArgumentException e)
		{
			System.err.println("error: " + e.getMessage());
			System.err.println(Args.USAGE);
			System.exit(2);
			return;
		}

		XteaKeyManager keys = new XteaKeyManager();
		try (InputStream in = new FileInputStream(args.xteas))
		{
			keys.loadKeys(in);
		}

		try (Store store = new Store(args.cache))
		{
			store.load();
			ObjectManager objects = new ObjectManager(store);
			objects.load();
			AreaRenderer renderer = new AreaRenderer(store, objects, keys);

			Map<Integer, Integer> terrain = terrainPlanes(store);
			int[] extent = args.extent != null ? args.extent : extent(terrain.keySet());
			System.out.printf("%d map squares; extent %d,%d..%d,%d%n", terrain.size(), extent[0], extent[1], extent[2], extent[3]);

			for (Styles style : args.styles)
			{
				MapStyle hooks = args.noIcons.contains(style) ? style.style.withoutIcons() : style.style;
				boolean icons = hooks.drawsIcons();
				Path dir = args.out.toPath().resolve(style.id());
				long start = System.nanoTime();

				TreeSet<Integer> missingKeys = new TreeSet<>();
				List<Map<String, Object>> planes = new ArrayList<>();
				int total = 0;
				for (int plane = 0; plane < 4; plane++)
				{
					Map<String, Object> summary = new LinkedHashMap<>();
					summary.put("path", planePath(plane));
					planes.add(summary);
					if (!args.planes.contains(plane))
					{
						summary.put("tiles", 0);
						summary.put("mapSquares", 0);
						continue;
					}
					Set<Integer> squares = new TreeSet<>();
					for (Map.Entry<Integer, Integer> e : terrain.entrySet())
					{
						int rx = e.getKey() >> 8;
						int ry = e.getKey() & 0xFF;
						if (rx >= extent[0] && rx <= extent[2] && ry >= extent[1] && ry <= extent[3]
							&& (plane == 0 || (e.getValue() >> plane & 1) != 0))
						{
							squares.add(e.getKey());
						}
					}
					Path planeDir = dir.resolve(planePath(plane));
					Set<Long> written = new HashSet<>();
					for (int[] b : blocks(squares, args.block))
					{
						int rx0 = b[0];
						int ry0 = b[1];
						int rx1 = rx0 + args.block - 1;
						int ry1 = ry0 + args.block - 1;
						AreaRenderer.Result result = renderer.render(style, hooks, false,
							rx0 << 6, ry0 << 6, (rx1 << 6) + 63, (ry1 << 6) + 63, plane);
						if (result == null)
						{
							continue;
						}
						for (int id : result.present)
						{
							if (!squares.contains(id))
							{
								continue;
							}
							if (result.missingKeys.contains(id))
							{
								missingKeys.add(id);
							}
							int rx = id >> 8;
							int ry = id & 0xFF;
							BufferedImage tile = result.image.getSubimage((rx - rx0) * TILE, (ry1 - ry) * TILE, TILE, TILE);
							boolean hasTerrain = (terrain.get(id) >> plane & 1) != 0;
							if (!hasTerrain && black(tile))
							{
								continue;
							}
							write(planeDir, NATIVE_ZOOM, rx - ORIGIN_RX, ry - ORIGIN_RY, tile);
							written.add(key(rx - ORIGIN_RX, ry - ORIGIN_RY));
						}
					}
					summary.put("mapSquares", written.size());
					int tiles = written.size();
					for (int z = NATIVE_ZOOM - 1; z >= MIN_ZOOM; z--)
					{
						written = downsample(planeDir, z, written);
						tiles += written.size();
					}
					summary.put("tiles", tiles);
					total += tiles;
					System.out.printf("%-16s plane %d: %d tiles (%d s)%n", style.id(), plane, tiles,
						(System.nanoTime() - start) / 1_000_000_000);
				}

				Map<String, String> index = index(dir);
				writeJson(dir.resolve("index.json"), index);
				writeJson(dir.resolve("manifest.json"), manifest(args, style, icons, extent, planes, missingKeys, total));
				System.out.printf("%-16s %d tiles %s, %d missing keys (%d s)%n", style.id(), total, planes,
					missingKeys.size(), (System.nanoTime() - start) / 1_000_000_000);
			}
		}
	}

	/** Where a plane's tiles are, relative to the style directory. */
	static String planePath(int plane)
	{
		return plane == 0 ? "" : "p" + plane + "/";
	}

	/**
	 * Every map square in the cache, with a bit per plane that has visible
	 * terrain ({@link InsetTopology#visibleTerrain}).
	 */
	private static Map<Integer, Integer> terrainPlanes(Store store) throws IOException
	{
		UnderlayManager underlays = new UnderlayManager(store);
		underlays.load();
		OverlayManager overlays = new OverlayManager(store);
		overlays.load();
		RegionLoader loader = new RegionLoader(store, id -> null);
		Map<Integer, Integer> result = new TreeMap<>();
		for (int id = 0; id < MAP_IDS; id++)
		{
			MapDefinition map = loader.loadMapDef(id);
			if (map == null)
			{
				continue;
			}
			int planes = 0;
			for (int plane = 0; plane < 4; plane++)
			{
				search:
				for (int x = 0; x < 64; x++)
				{
					for (int y = 0; y < 64; y++)
					{
						MapDefinition.Tile tile = map.getTiles()[plane][x][y];
						OverlayDefinition overlay = tile.overlayId == 0 ? null
							: overlays.provide(Short.toUnsignedInt(tile.overlayId) - 1);
						UnderlayDefinition underlay = tile.underlayId == 0 ? null
							: underlays.provide(Short.toUnsignedInt(tile.underlayId) - 1);
						if (InsetTopology.visibleTerrain(tile, underlay, overlay))
						{
							planes |= 1 << plane;
							break search;
						}
					}
				}
			}
			result.put(id, planes);
		}
		return result;
	}

	/** The smallest extent holding every map square; one west or south of the layout's origin fails. */
	static int[] extent(Set<Integer> squares)
	{
		int[] e = {255, 255, 0, 0};
		for (int id : squares)
		{
			int rx = id >> 8;
			int ry = id & 0xFF;
			if (rx < ORIGIN_RX || ry < ORIGIN_RY)
			{
				throw new IllegalStateException("Map square " + rx + "," + ry + " lies outside the tile layout");
			}
			e[0] = Math.min(e[0], rx);
			e[1] = Math.min(e[1], ry);
			e[2] = Math.max(e[2], rx);
			e[3] = Math.max(e[3], ry);
		}
		return e;
	}

	/** The block-aligned blocks (lowest map square of each) that hold any of the squares. */
	static List<int[]> blocks(Set<Integer> squares, int block)
	{
		TreeSet<Long> keys = new TreeSet<>();
		for (int id : squares)
		{
			int rx = id >> 8;
			int ry = id & 0xFF;
			keys.add(key(rx - Math.floorMod(rx - ORIGIN_RX, block), ry - Math.floorMod(ry - ORIGIN_RY, block)));
		}
		List<int[]> blocks = new ArrayList<>();
		for (long k : keys)
		{
			blocks.add(new int[]{col(k), row(k)});
		}
		return blocks;
	}

	static boolean black(BufferedImage tile)
	{
		for (int y = 0; y < tile.getHeight(); y++)
		{
			for (int x = 0; x < tile.getWidth(); x++)
			{
				if ((tile.getRGB(x, y) & 0xFFFFFF) != 0)
				{
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Builds zoom z from zoom z + 1: each tile averages the 2x2 tiles under
	 * it. TMS rows grow northwards, so the children's upper row is 2r + 1.
	 * Missing children stay black.
	 */
	private static Set<Long> downsample(Path dir, int z, Set<Long> children) throws IOException
	{
		Set<Long> parents = new HashSet<>();
		for (long k : children)
		{
			parents.add(key(col(k) >> 1, row(k) >> 1));
		}
		for (long k : parents)
		{
			int c = col(k);
			int r = row(k);
			BufferedImage tile = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
			for (int dc = 0; dc < 2; dc++)
			{
				for (int dr = 0; dr < 2; dr++)
				{
					int cc = 2 * c + dc;
					int cr = 2 * r + dr;
					if (!children.contains(key(cc, cr)))
					{
						continue;
					}
					BufferedImage child = ImageIO.read(path(dir, z + 1, cc, cr).toFile());
					int ox = dc * TILE / 2;
					int oy = (1 - dr) * TILE / 2;
					for (int y = 0; y < TILE / 2; y++)
					{
						for (int x = 0; x < TILE / 2; x++)
						{
							tile.setRGB(ox + x, oy + y, average(
								child.getRGB(2 * x, 2 * y), child.getRGB(2 * x + 1, 2 * y),
								child.getRGB(2 * x, 2 * y + 1), child.getRGB(2 * x + 1, 2 * y + 1)));
						}
					}
				}
			}
			write(dir, z, c, r, tile);
		}
		return parents;
	}

	static int average(int a, int b, int c, int d)
	{
		int r = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF) + ((c >> 16) & 0xFF) + ((d >> 16) & 0xFF) + 2) / 4;
		int g = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF) + ((c >> 8) & 0xFF) + ((d >> 8) & 0xFF) + 2) / 4;
		int bl = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF) + 2) / 4;
		return r << 16 | g << 8 | bl;
	}

	private static void write(Path dir, int z, int c, int r, BufferedImage tile) throws IOException
	{
		Path file = path(dir, z, c, r);
		Files.createDirectories(file.getParent());
		ImageIO.write(tile, "png", file.toFile());
	}

	static Path path(Path dir, int z, int c, int r)
	{
		return dir.resolve(z + "/" + c + "/" + r + ".png");
	}

	private static long key(int c, int r)
	{
		return (long) c << 32 | (r & 0xFFFFFFFFL);
	}

	private static int col(long key)
	{
		return (int) (key >> 32);
	}

	private static int row(long key)
	{
		return (int) key;
	}

	/** sha256 of every tile, keyed by its path relative to the style directory. */
	private static Map<String, String> index(Path dir) throws IOException
	{
		Map<String, String> index = new TreeMap<>();
		try (var files = Files.walk(dir))
		{
			for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".png"))::iterator)
			{
				index.put(dir.relativize(p).toString().replace(File.separatorChar, '/'), sha256(p.toFile()));
			}
		}
		return index;
	}

	private static Map<String, Object> manifest(Args args, Styles style, boolean icons, int[] extent,
		List<Map<String, Object>> planes, Set<Integer> missingKeys, int tiles) throws IOException
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("layout", "explv-tms-v1");
		m.put("style", style.id());
		m.put("icons", icons);
		m.put("tileSize", TILE);
		m.put("nativeZoom", NATIVE_ZOOM);
		m.put("minZoom", MIN_ZOOM);
		m.put("origin", new int[]{ORIGIN_RX, ORIGIN_RY});
		m.put("extent", extent);
		m.put("tiles", tiles);
		m.put("planes", planes);
		m.put("missingKeyRegions", new ArrayList<>(missingKeys));
		if (args.cacheId != null)
		{
			m.put("cacheId", args.cacheId);
		}
		m.put("cacheIdx255Sha256", sha256(new File(args.cache, "main_file_cache.idx255")));
		m.put("xteasSha256", sha256(args.xteas));
		m.put("runeliteCache", runeliteCacheVersion());
		if (args.commit != null)
		{
			m.put("rendererCommit", args.commit);
		}
		return m;
	}

	private static void writeJson(Path file, Object value) throws IOException
	{
		Files.write(file, (new GsonBuilder().setPrettyPrinting().create().toJson(value) + "\n")
			.getBytes(StandardCharsets.UTF_8));
	}

	/** The net.runelite:cache version on the classpath, from its jar name (cache-1.13.2.jar). */
	private static String runeliteCacheVersion()
	{
		String jar = new File(MapImageDumper.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getName();
		return jar.matches("cache-.+\\.jar") ? jar.substring("cache-".length(), jar.length() - ".jar".length()) : jar;
	}

	static String sha256(File file) throws IOException
	{
		try
		{
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			try (InputStream in = new FileInputStream(file))
			{
				byte[] buf = new byte[1 << 16];
				for (int n; (n = in.read(buf)) > 0; )
				{
					md.update(buf, 0, n);
				}
			}
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest())
			{
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static final class Args
	{
		static final String USAGE = String.join("\n",
			"usage: Tiles --cache DIR --xteas FILE --out DIR [--styles S[,S...]] [--no-icons S[,S...]]",
			"             [--planes Z[,Z...]] [--block N] [--extent RX0,RY0,RX1,RY1] [--commit SHA] [--cache-id N]",
			"  --planes    planes to write (default 0,1,2,3)",
			"  --styles    styles to write (default standard,tactical)",
			"  --no-icons  styles drawn without map icons (default tactical,blueprint; blueprint never draws them)",
			"  --block     map squares per render block side (default 16)",
			"  --extent    inclusive map-square rectangle to write (default: every map square in the cache)",
			"  --commit    renderer commit recorded in each manifest",
			"  --cache-id  openrs2 cache id recorded in each manifest");

		File cache;
		File xteas;
		File out;
		Set<Integer> planes = new TreeSet<>(List.of(0, 1, 2, 3));
		int block = 16;
		int[] extent;
		String commit;
		Integer cacheId;
		List<Styles> styles = new ArrayList<>(List.of(Styles.STANDARD, Styles.TACTICAL));
		Set<Styles> noIcons = new HashSet<>(Set.of(Styles.TACTICAL, Styles.BLUEPRINT));

		static Args parse(String[] argv)
		{
			Args a = new Args();
			for (int i = 0; i < argv.length; i++)
			{
				String flag = argv[i];
				if (i + 1 >= argv.length)
				{
					throw new IllegalArgumentException(flag + " needs a value");
				}
				String value = argv[++i];
				switch (flag)
				{
					case "--cache":
						a.cache = new File(value);
						break;
					case "--xteas":
						a.xteas = new File(value);
						break;
					case "--out":
						a.out = new File(value);
						break;
					case "--planes":
						a.planes = new TreeSet<>();
						for (String z : value.split(","))
						{
							a.planes.add(Integer.parseInt(z.trim()));
						}
						break;
					case "--block":
						a.block = Integer.parseInt(value);
						break;
					case "--extent":
					{
						String[] v = value.split(",");
						if (v.length != 4)
						{
							throw new IllegalArgumentException("--extent expects RX0,RY0,RX1,RY1");
						}
						a.extent = new int[4];
						for (int k = 0; k < 4; k++)
						{
							a.extent[k] = Integer.parseInt(v[k].trim());
						}
						break;
					}
					case "--commit":
						a.commit = value;
						break;
					case "--cache-id":
						a.cacheId = Integer.parseInt(value);
						break;
					case "--styles":
						a.styles = styles(value);
						break;
					case "--no-icons":
						a.noIcons = new HashSet<>(value.isEmpty() ? List.of() : styles(value));
						break;
					default:
						throw new IllegalArgumentException("unknown flag " + flag);
				}
			}
			if (a.cache == null || a.xteas == null || a.out == null)
			{
				throw new IllegalArgumentException("--cache, --xteas and --out are required");
			}
			if (!new File(a.cache, "main_file_cache.idx255").isFile())
			{
				throw new IllegalArgumentException("not a cache directory: " + a.cache);
			}
			if (!a.xteas.isFile())
			{
				throw new IllegalArgumentException("no such XTEA file: " + a.xteas);
			}
			int[] e = a.extent;
			if (e != null && (e[0] < ORIGIN_RX || e[1] < ORIGIN_RY || e[2] > 255 || e[3] > 255 || e[0] > e[2] || e[1] > e[3]))
			{
				throw new IllegalArgumentException("--extent must lie within " + ORIGIN_RX + "," + ORIGIN_RY + ",255,255");
			}
			if (a.planes.isEmpty() || a.planes.stream().anyMatch(z -> z < 0 || z > 3))
			{
				throw new IllegalArgumentException("--planes must be among 0..3");
			}
			if (a.block < 1)
			{
				throw new IllegalArgumentException("--block must be positive");
			}
			return a;
		}

		private static List<Styles> styles(String value)
		{
			List<Styles> list = new ArrayList<>();
			for (String s : value.split(","))
			{
				list.add(Styles.parse(s));
			}
			return list;
		}
	}
}
