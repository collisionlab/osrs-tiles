package mapstyle.routing;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.pathfinder.astar.RoutingCuts;
import shortestpath.pathfinder.astar.RoutingStatic;
import shortestpath.pathfinder.astar.RoutingStaticBuilder;
import shortestpath.transport.ResourceLoader;
import shortestpath.transport.TransportType;

/**
 * Offline projection of the pinned production routing graph into per-square files (see
 * docs/routing-layers.md). Every partition, crossing and reachability fact comes from the plugin's own
 * builder; nothing here builds a graph.
 *
 * <pre>
 * RoutingLayers COLLISION_RELEASE_DIR TILE_VERSION OUTPUT_ROOT
 * </pre>
 */
public final class RoutingLayers
{
	static final Gson JSON = new Gson();
	static final int ABSENT = -1;

	/** Tile classifications in a square file. */
	static final byte NON_WALKABLE = 0;
	static final byte REACHABLE = 1;
	static final byte UNREACHABLE = 2;
	/** Walkable in collision but part of an open expanse: not counted or drawn as unreachable terrain. */
	static final byte EXPANSE = 3;

	/**
	 * Natural walking components this large, when no transport reaches them, are open expanses rather
	 * than places to investigate. The collision map leaves sea tiles open (ships sail over them), so
	 * each sea is one huge walkable component: on cache 2746 the five surface seas are 249,286 to
	 * 2,458,003 tiles, while the largest enclosed unreachable area is 35,933.
	 */
	static final int EXPANSE_TILES = 100_000;

	private static final int[] DX = {0, 1, 0, -1};
	private static final int[] DY = {1, 0, -1, 0};

	private RoutingLayers()
	{
	}

	public static void main(String[] args) throws Exception
	{
		if (args.length != 3)
		{
			throw new IllegalArgumentException("usage: RoutingLayers COLLISION_RELEASE_DIR TILE_VERSION OUTPUT_ROOT");
		}
		Path release = Path.of(args[0]);
		String tileVersion = args[1];
		byte[] collisionBytes = resource("/collision-map.zip");
		byte[] cutBytes = resource(RoutingCuts.RESOURCE);
		RoutingCuts cuts = RoutingCuts.read(cutBytes);
		verifyFingerprint(cuts, collisionBytes);
		SortedMap<String, byte[]> collisionPlanes = collisionPlanes(collisionBytes);
		verifyCollision(release, collisionPlanes);
		JsonObject collisionManifest = JSON.fromJson(Files.readString(release.resolve("manifest.json")), JsonObject.class);
		String releaseVersion = collisionManifest.get("cacheId").getAsString() + "-"
			+ collisionManifest.get("rendererCommit").getAsString().substring(0, 12);
		if (!tileVersion.equals(releaseVersion))
		{
			throw new IOException("tile version " + tileVersion + " differs from the collision release's " + releaseVersion);
		}

		CollisionMap collision = new CollisionMap(SplitFlagMap.fromResources());
		var transports = ResourceLoader.transports();
		var banks = ResourceLoader.destinations().get("bank");
		var built = RoutingStaticBuilder.build(collision, transports, banks == null ? Set.of() : banks, cuts.pairs());
		var walking = RoutingStaticBuilder.walkingGraph(collision, transports);
		Export export = export(built.routingStatic, walking, EXPANSE_TILES);
		// Collision-bearing planes with no walkable tile still get a file, so missing data stays distinct.
		for (String name : collisionPlanes.keySet())
		{
			String[] parts = name.split("/");
			int plane = parts[0].startsWith("p") ? Integer.parseInt(parts[0].substring(1)) : 0;
			int squareX = Integer.parseInt(parts[parts.length - 2]) + 15;
			int squareY = Integer.parseInt(parts[parts.length - 1].replace(".bin", "")) + 19;
			export.square(pack(squareX * 64, squareY * 64, plane));
		}

		JsonObject manifest = new JsonObject();
		manifest.addProperty("schemaVersion", 3);
		manifest.add("plugin", JSON.fromJson(new String(resource("/plugin.json"), StandardCharsets.UTF_8), JsonObject.class));
		manifest.addProperty("tileVersion", tileVersion);
		manifest.addProperty("collisionSha256", sha(collisionBytes));
		manifest.addProperty("collisionContentSha256", contentHash(collisionPlanes));
		manifest.addProperty("cutCollisionFingerprint", Long.toUnsignedString(cuts.collisionFingerprint(), 16));
		manifest.addProperty("separatorSha256", sha(cutBytes));
		// Every packaged transport resource affects how production reads the map, seasonal ones included.
		SortedMap<String, byte[]> resources = new TreeMap<>();
		for (TransportType type : TransportType.values())
		{
			if (type.hasResourcePath())
			{
				resources.put(type.getResourcePath(), resource(type.getResourcePath()));
			}
		}
		manifest.addProperty("transportSha256", contentHash(resources));
		manifest.addProperty("bankSha256", sha(resource("/destinations/game_features/bank.tsv")));
		manifest.addProperty("componentCount", export.components.size());
		manifest.addProperty("effectiveCrossingCount", built.routingStatic.crossingCount());
		manifest.addProperty("searchTileCount", built.routingStatic.searchTileCount());
		manifest.addProperty("structurallyUnreachableTileCount", export.unreachableCount);
		JsonObject expanses = new JsonObject();
		expanses.addProperty("minimumTiles", EXPANSE_TILES);
		expanses.addProperty("tileCount", export.expanseCount);
		expanses.add("components", JSON.toJsonTree(export.expanses.values()));
		manifest.add("expanses", expanses);
		JsonObject diagnostics = new JsonObject();
		diagnostics.addProperty("raw", built.diagnostics.inputCutCount);
		diagnostics.addProperty("duplicate", built.diagnostics.duplicateCutCount);
		diagnostics.addProperty("invalid", built.diagnostics.invalidCutCount);
		diagnostics.addProperty("nonSeparating", built.diagnostics.nonSeparatingCutCount);
		manifest.add("cuts", diagnostics);
		// The colours components.json's `colour` indexes, as components/ draws them.
		manifest.add("componentPalette", JSON.toJsonTree(ComponentTiles.PALETTE));
		SortedMap<String, byte[]> files = export.files();
		JsonObject checksums = new JsonObject();
		for (var entry : files.entrySet())
		{
			checksums.addProperty(entry.getKey(), sha(entry.getValue()));
		}
		manifest.add("files", checksums);
		String version = sha(JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8)).substring(0, 24);
		manifest.addProperty("version", version);

		Path output = Path.of(args[2]).resolve(version);
		if (Files.exists(output))
		{
			throw new IOException("immutable output already exists: " + output);
		}
		for (var entry : files.entrySet())
		{
			Path file = output.resolve(entry.getKey());
			Files.createDirectories(file.getParent());
			Files.write(file, entry.getValue());
		}
		Files.writeString(output.resolve("manifest.json"), JSON.toJson(manifest) + "\n");
		System.out.println(output);
	}

	/** One map square and plane: the RTL1 file. */
	public static final class Square
	{
		public final int[] owners = new int[4096];
		public final int[] naturalSizes = new int[4096];
		public final byte[] status = new byte[4096];
		public final byte[] boundaries = new byte[4096];
		public final List<Integer> crossings = new ArrayList<>();

		Square()
		{
			Arrays.fill(owners, ABSENT);
		}

		public byte[] bytes()
		{
			SortedSet<Integer> palette = new TreeSet<>();
			for (int id : owners)
			{
				if (id >= 0)
				{
					palette.add(id);
				}
			}
			List<Integer> ids = new ArrayList<>(palette);
			Map<Integer, Integer> refs = new HashMap<>();
			for (int i = 0; i < ids.size(); i++)
			{
				refs.put(ids.get(i), i + 1);
			}
			ByteBuffer out = ByteBuffer.allocate(16 + ids.size() * 4 + 4096 * 8 + crossings.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
			out.putInt(0x314c5452).putInt(ids.size()).putInt(crossings.size()).putInt(4096);
			for (int id : ids)
			{
				out.putInt(id);
			}
			for (int i = 0; i < 4096; i++)
			{
				out.putShort((short) (owners[i] < 0 ? 0 : refs.get(owners[i])))
					.put(status[i])
					.put(boundaries[i])
					.putInt(naturalSizes[i]);
			}
			for (int id : crossings)
			{
				out.putInt(id);
			}
			return out.array();
		}
	}

	/** A retained production routing component, as components.json lists it. */
	public static final class Component
	{
		public int id;
		public int plane;
		public int tileCount;
		public int separatorCrossings;
		/** x0, y0, x1, y1, the maxima exclusive. */
		public int[] bounds;
		public int[] representative;
		public final SortedSet<Integer> neighbours = new TreeSet<>();
		/** Its index in the manifest's componentPalette; no neighbour or component it touches shares it. */
		public int colour;

		Component(int id, int tile)
		{
			this.id = id;
			plane = p(tile);
			representative = point(tile);
			bounds = new int[]{x(tile), y(tile), x(tile) + 1, y(tile) + 1};
		}

		void add(int tile)
		{
			tileCount++;
			bounds[0] = Math.min(bounds[0], x(tile));
			bounds[1] = Math.min(bounds[1], y(tile));
			bounds[2] = Math.max(bounds[2], x(tile) + 1);
			bounds[3] = Math.max(bounds[3], y(tile) + 1);
		}
	}

	/** An unreachable natural component left out as an open expanse, for the manifest. */
	public static final class Expanse
	{
		public int plane;
		public int tiles;
		/** x0, y0, x1, y1, the maxima exclusive. */
		public int[] bounds;

		Expanse(int tile, int tiles)
		{
			plane = p(tile);
			this.tiles = tiles;
			bounds = new int[]{x(tile), y(tile), x(tile) + 1, y(tile) + 1};
		}

		void add(int tile)
		{
			bounds[0] = Math.min(bounds[0], x(tile));
			bounds[1] = Math.min(bounds[1], y(tile));
			bounds[2] = Math.max(bounds[2], x(tile) + 1);
			bounds[3] = Math.max(bounds[3], y(tile) + 1);
		}
	}

	/** An effective synthetic walking crossing. */
	public static final class Crossing
	{
		public int id;
		public int cost;
		public Integer fromComponent;
		public Integer toComponent;
		public int[] from;
		public int[] to;
	}

	public static final class Export
	{
		public final SortedMap<String, Square> squares = new TreeMap<>();
		public final SortedMap<Integer, Component> components = new TreeMap<>();
		public final List<Crossing> crossings = new ArrayList<>();
		/** By natural component id. */
		public final SortedMap<Integer, Expanse> expanses = new TreeMap<>();
		public long unreachableCount;
		public long expanseCount;
		/** Components sharing a tile edge, by component; not published, only coloured apart. */
		final Map<Integer, Set<Integer>> touching = new HashMap<>();

		SortedMap<String, byte[]> files()
		{
			SortedMap<String, byte[]> files = new TreeMap<>();
			squares.forEach((name, square) ->
			{
				files.put(name, square.bytes());
				if (!square.crossings.isEmpty())
				{
					files.put(name.replace(".bin", ".crossings.json"), jsonBytes(square.crossings.stream().map(crossings::get).toArray()));
				}
			});
			files.put("components.json", jsonBytes(components.values()));
			files.putAll(ComponentTiles.tiles(this));
			files.put("crossings.json", jsonBytes(crossings));
			return files;
		}

		public Square square(int tile)
		{
			return squares.computeIfAbsent(path(tile), k -> new Square());
		}
	}

	public static Export export(RoutingStatic routing, RoutingStaticBuilder.WalkingGraph walking)
	{
		return export(routing, walking, EXPANSE_TILES);
	}

	/**
	 * Classifies every walkable tile by structural reachability, unreachable natural components of
	 * {@code expanseTiles} or more as open expanses, then copies production ownership, component
	 * boundaries and crossings.
	 */
	public static Export export(RoutingStatic routing, RoutingStaticBuilder.WalkingGraph walking, int expanseTiles)
	{
		Export output = new Export();
		Map<Integer, Integer> sizes = new HashMap<>();
		for (int i = 0; i < walking.tileCount(); i++)
		{
			sizes.merge(walking.naturalComponent(i), 1, Integer::sum);
		}
		for (int i = 0; i < walking.tileCount(); i++)
		{
			int tile = walking.tile(i);
			int natural = walking.naturalComponent(i);
			int size = sizes.get(natural);
			Square square = output.square(tile);
			byte status;
			if (walking.isStructurallyReachable(natural))
			{
				status = REACHABLE;
			}
			else if (size >= expanseTiles)
			{
				status = EXPANSE;
				output.expanseCount++;
				output.expanses.computeIfAbsent(natural, k -> new Expanse(tile, size)).add(tile);
			}
			else
			{
				status = UNREACHABLE;
				output.unreachableCount++;
			}
			square.status[local(tile)] = status;
			square.naturalSizes[local(tile)] = size;
		}
		for (int i = 0; i < routing.searchTileCount(); i++)
		{
			int tile = routing.searchTile(i);
			int id = routing.routingComponent(i);
			Square square = output.square(tile);
			if (square.status[local(tile)] != REACHABLE)
			{
				throw new IllegalStateException("search tile is not structurally reachable");
			}
			square.owners[local(tile)] = id;
			output.components.computeIfAbsent(id, k -> new Component(id, tile)).add(tile);
			// Two bits per side (N, E, S, W): 1 the search graph's exterior, 2 another production
			// component. A global lookup, so square borders make no seams.
			int flags = 0;
			for (int side = 0; side < 4; side++)
			{
				int next = routing.searchIndex(pack(x(tile) + DX[side], y(tile) + DY[side], p(tile)));
				int kind = next < 0 ? 1 : routing.routingComponent(next) != id ? 2 : 0;
				flags |= kind << (side * 2);
				if (kind == 2)
				{
					output.touching.computeIfAbsent(id, k -> new TreeSet<>()).add(routing.routingComponent(next));
				}
			}
			square.boundaries[local(tile)] = (byte) flags;
		}
		for (int i = 0; i < routing.crossingCount(); i++)
		{
			int from = routing.siteTile(routing.crossingFromSite(i));
			int to = routing.siteTile(routing.crossingToSite(i));
			Crossing crossing = new Crossing();
			crossing.id = i;
			crossing.from = point(from);
			crossing.to = point(to);
			crossing.cost = routing.crossingCost(i);
			// A crossing may sit on excluded land: take site membership as production records it
			// rather than inventing search ids.
			int[] a = routing.siteComponents(routing.crossingFromSite(i));
			int[] b = routing.siteComponents(routing.crossingToSite(i));
			if (a.length > 1 || b.length > 1)
			{
				throw new IllegalStateException("separator endpoint has ambiguous component membership");
			}
			crossing.fromComponent = a.length == 0 ? null : a[0];
			crossing.toComponent = b.length == 0 ? null : b[0];
			output.crossings.add(crossing);
			output.square(from).crossings.add(i);
			if (!path(from).equals(path(to)))
			{
				output.square(to).crossings.add(i);
			}
			if (a.length == 1 && b.length == 1)
			{
				link(output.components.get(a[0]), b[0]);
				link(output.components.get(b[0]), a[0]);
			}
		}
		ComponentTiles.colour(output);
		return output;
	}

	private static void link(Component component, int neighbour)
	{
		if (component != null)
		{
			component.separatorCrossings++;
			component.neighbours.add(neighbour);
		}
	}

	public static void verifyFingerprint(RoutingCuts cuts, byte[] collision)
	{
		if (cuts.collisionFingerprint() != RoutingCuts.fingerprint(collision))
		{
			throw new IllegalStateException("routing-cuts collision fingerprint differs from packaged collision-map.zip");
		}
	}

	/** The plugin's collision-map.zip split into the tile release's padded 2048-byte plane files. */
	public static SortedMap<String, byte[]> collisionPlanes(byte[] zip) throws IOException
	{
		SortedMap<String, byte[]> result = new TreeMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip)))
		{
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null)
			{
				if (!entry.getName().matches("[0-9]+_[0-9]+"))
				{
					throw new IOException("unexpected collision entry " + entry.getName());
				}
				String[] xy = entry.getName().split("_");
				int squareX = Integer.parseInt(xy[0]) - 15;
				int squareY = Integer.parseInt(xy[1]) - 19;
				byte[] raw = in.readAllBytes();
				if (raw.length > 8192)
				{
					throw new IOException("oversize collision square " + entry.getName());
				}
				for (int p = 0; p < 4; p++)
				{
					byte[] plane = new byte[2048];
					int start = p * 2048;
					if (start < raw.length)
					{
						System.arraycopy(raw, start, plane, 0, Math.min(2048, raw.length - start));
					}
					boolean nonzero = false;
					for (byte b : plane)
					{
						nonzero |= b != 0;
					}
					String name = (p == 0 ? "" : "p" + p + "/") + "8/" + squareX + "/" + squareY + ".bin";
					if (nonzero && result.put(name, plane) != null)
					{
						throw new IOException("duplicate collision entry " + name);
					}
				}
			}
		}
		return result;
	}

	/** The tile release's collision layer must hold exactly the plugin's planes, byte for byte. */
	public static void verifyCollision(Path release, SortedMap<String, byte[]> planes) throws Exception
	{
		JsonObject manifest = JSON.fromJson(Files.readString(release.resolve("manifest.json")), JsonObject.class);
		if (!"shortest-path-flags-v1".equals(manifest.get("format").getAsString()))
		{
			throw new IOException("unsupported collision format");
		}
		JsonObject index = JSON.fromJson(Files.readString(release.resolve("index.json")), JsonObject.class);
		if (!index.keySet().equals(planes.keySet()))
		{
			throw new IOException("tile release collision square set differs from plugin");
		}
		for (var entry : planes.entrySet())
		{
			byte[] published = Files.readAllBytes(release.resolve(entry.getKey()));
			if (!sha(published).equals(index.get(entry.getKey()).getAsString()) || !Arrays.equals(published, entry.getValue()))
			{
				throw new IOException("tile release collision differs at " + entry.getKey());
			}
		}
	}

	static byte[] resource(String name) throws IOException
	{
		try (InputStream in = RoutingLayers.class.getResourceAsStream(name))
		{
			if (in == null)
			{
				throw new IOException("missing production resource " + name);
			}
			return in.readAllBytes();
		}
	}

	static byte[] jsonBytes(Object value)
	{
		return JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
	}

	/** SHA-256 over sorted entries: UTF-8 path, NUL, then the raw SHA-256 of the entry's bytes. */
	static String contentHash(SortedMap<String, byte[]> files) throws Exception
	{
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		for (var entry : files.entrySet())
		{
			digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			digest.update(rawSha(entry.getValue()));
		}
		return hex(digest.digest());
	}

	static byte[] rawSha(byte[] data) throws Exception
	{
		return MessageDigest.getInstance("SHA-256").digest(data);
	}

	static String hex(byte[] data)
	{
		StringBuilder s = new StringBuilder();
		for (byte b : data)
		{
			s.append(String.format("%02x", b));
		}
		return s.toString();
	}

	static String sha(byte[] data) throws Exception
	{
		return hex(rawSha(data));
	}

	// Packed tiles as the plugin packs them: x and y in 15 bits each, the plane in the top two.
	static int x(int tile)
	{
		return tile & 32767;
	}

	static int y(int tile)
	{
		return (tile >>> 15) & 32767;
	}

	static int p(int tile)
	{
		return tile >>> 30;
	}

	static int pack(int x, int y, int plane)
	{
		return (x & 32767) | ((y & 32767) << 15) | (plane << 30);
	}

	static int[] point(int tile)
	{
		return new int[]{x(tile), y(tile), p(tile)};
	}

	static int local(int tile)
	{
		return (y(tile) & 63) * 64 + (x(tile) & 63);
	}

	static String path(int tile)
	{
		return "p" + p(tile) + "/" + (x(tile) >> 6) + "/" + (y(tile) >> 6) + ".bin";
	}
}
