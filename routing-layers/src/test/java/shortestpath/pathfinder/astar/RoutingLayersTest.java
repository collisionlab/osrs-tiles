package shortestpath.pathfinder.astar;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import mapstyle.routing.BlockedTiles;
import mapstyle.routing.ComponentTiles;
import mapstyle.routing.RoutingLayers;
import org.junit.Test;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.CollisionMap;
import shortestpath.pathfinder.SplitFlagMap;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportType;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class RoutingLayersTest
{
	private static int t(int x, int y)
	{
		return WorldPointUtil.packWorldPoint(x, y, 0);
	}

	private static int local(int tile)
	{
		return ((tile >>> 15) & 63) * 64 + (tile & 63);
	}

	private static int[] edge(int a, int b)
	{
		return new int[]{a, b};
	}

	/** Only the given walking edges; every partition and reachability decision is production's. */
	private static class World extends CollisionMap
	{
		private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
		private static final int[] DY = {1, 1, 0, -1, -1, -1, 0, 1};
		final Set<Integer> tiles = new HashSet<>();
		final Map<Integer, List<Integer>> edges = new HashMap<>();

		World(int[]... pairs)
		{
			super(null);
			for (int[] pair : pairs)
			{
				tiles.add(pair[0]);
				tiles.add(pair[1]);
				edges.computeIfAbsent(pair[0], k -> new ArrayList<>()).add(pair[1]);
				edges.computeIfAbsent(pair[1], k -> new ArrayList<>()).add(pair[0]);
			}
		}

		@Override
		public SplitFlagMap.RegionExtent regionExtent()
		{
			return new SplitFlagMap.RegionExtent(0, 0, 1, 1);
		}

		@Override
		public byte getRegionPlaneCounts(int index)
		{
			return 1;
		}

		@Override
		public boolean isBlocked(int x, int y, int p)
		{
			return !tiles.contains(WorldPointUtil.packWorldPoint(x, y, p));
		}

		@Override
		public int[] ordinaryWalkingNeighbors(int tile)
		{
			return edges.getOrDefault(tile, List.of()).stream().mapToInt(i -> i).toArray();
		}

		@Override
		public byte ordinaryWalkingMask(int tile)
		{
			int mask = 0;
			List<Integer> next = edges.getOrDefault(tile, List.of());
			for (int i = 0; i < 8; i++)
			{
				if (next.contains(t((tile & 32767) + DX[i], ((tile >>> 15) & 32767) + DY[i])))
				{
					mask |= 1 << i;
				}
			}
			return (byte) mask;
		}
	}

	@Test
	public void exportsProductionPartitionAndReachabilityAcrossSquareAndDiagonalCuts() throws Exception
	{
		int a = t(62, 10), b = t(63, 10), c = t(64, 10), d = t(65, 11), u = t(100, 100), v = t(101, 100);
		World world = new World(edge(a, b), edge(b, c), edge(c, d), edge(u, v));
		var walking = RoutingStaticBuilder.walkingGraph(world, Map.of(), a);
		var built = RoutingStaticBuilder.build(world, Map.of(), Set.of(), new int[]{b, c, c, d}, a);
		var exported = RoutingLayers.export(built.routingStatic, walking);
		assertEquals(3, exported.components.size());
		assertEquals(2, exported.crossings.size());
		assertEquals(2, exported.unreachableCount);
		for (int i = 0; i < built.routingStatic.searchTileCount(); i++)
		{
			int tile = built.routingStatic.searchTile(i);
			assertEquals(built.routingStatic.routingComponent(i), exported.square(tile).owners[local(tile)]);
		}
		var plain = RoutingLayers.export(RoutingStaticBuilder.build(world, Map.of(), Set.of(), new int[0], a).routingStatic, walking);
		assertEquals(exported.unreachableCount, plain.unreachableCount);
		// No square seam on the uncut component; the diagonal crossing keeps its own endpoints.
		int flags = plain.square(b).boundaries[local(b)] & 255;
		assertEquals(0, (flags >>> 2) & 3);
		assertArrayEquals(new int[]{65, 11, 0}, exported.crossings.get(1).to);
		assertEquals(-1, exported.square(u).owners[local(u)]);
		assertEquals(2, exported.square(u).status[local(u)]);
	}

	@Test
	public void drawsComponentsAsTilesInTheirOwnColoursNeighboursApart() throws Exception
	{
		// Two components either side of a cut, in map square (50, 50): tile (35, 31) at zoom 8.
		int a = t(3200, 3200), b = t(3201, 3200), c = t(3202, 3200), d = t(3203, 3200);
		World world = new World(edge(a, b), edge(b, c), edge(c, d))
		{
			@Override
			public SplitFlagMap.RegionExtent regionExtent()
			{
				return new SplitFlagMap.RegionExtent(50, 50, 51, 51);
			}
		};
		var walking = RoutingStaticBuilder.walkingGraph(world, Map.of(), a);
		var built = RoutingStaticBuilder.build(world, Map.of(), Set.of(), new int[]{b, c}, a);
		var exported = RoutingLayers.export(built.routingStatic, walking);
		assertEquals(2, exported.components.size());
		int left = exported.square(a).owners[local(a)], right = exported.square(c).owners[local(c)];
		int leftColour = exported.components.get(left).colour, rightColour = exported.components.get(right).colour;
		assertNotEquals(leftColour, rightColour);

		var tiles = ComponentTiles.tiles(exported);
		BufferedImage native8 = ImageIO.read(new ByteArrayInputStream(tiles.get("components/8/35/31.png")));
		// 4 px a game tile, rows from the north: (3200, 3200) is the bottom-left 4 x 4 pixels.
		assertEquals(rgb(leftColour), native8.getRGB(1, 254));
		assertEquals(rgb(leftColour), native8.getRGB(5, 254));
		assertEquals(rgb(rightColour), native8.getRGB(9, 254));
		assertEquals(0, native8.getRGB(1, 250) >>> 24);
		// Every zoom down to 3, and nothing where no component is.
		assertTrue(tiles.containsKey("components/3/1/0.png"));
		assertEquals(6, tiles.size());
	}

	@Test
	public void drawsBlockedGroundAsTilesAtEveryZoom() throws Exception
	{
		// A line of four walkable tiles in map square (50, 50); the rest of the square is blocked.
		int a = t(3200, 3200), b = t(3201, 3200), c = t(3202, 3200), d = t(3203, 3200);
		World world = new World(edge(a, b), edge(b, c), edge(c, d))
		{
			@Override
			public SplitFlagMap.RegionExtent regionExtent()
			{
				return new SplitFlagMap.RegionExtent(50, 50, 51, 51);
			}
		};
		var walking = RoutingStaticBuilder.walkingGraph(world, Map.of(), a);
		var built = RoutingStaticBuilder.build(world, Map.of(), Set.of(), new int[0], a);
		var exported = RoutingLayers.export(built.routingStatic, walking);
		var tiles = BlockedTiles.tiles(exported);
		BufferedImage native8 = ImageIO.read(new ByteArrayInputStream(tiles.get("blocked/8/35/31.png")));
		// (3200, 3200) is walkable, so clear; the tile north of it is blocked, fully red.
		assertEquals(0, native8.getRGB(1, 254) >>> 24);
		assertEquals(0, native8.getRGB(13, 254) >>> 24);
		assertEquals(BlockedTiles.ALPHA[BlockedTiles.ALPHA.length - 1] << 24 | BlockedTiles.RGB, native8.getRGB(1, 250));
		// Zoomed out the square is nearly all blocked: the darkest step.
		BufferedImage zoom3 = ImageIO.read(new ByteArrayInputStream(tiles.get("blocked/3/1/0.png")));
		// 8 game tiles a pixel: square (50, 50) is pixels 24-31 across, 0-7 down.
		assertEquals(BlockedTiles.ALPHA[BlockedTiles.ALPHA.length - 1], zoom3.getRGB(25, 6) >>> 24);
		assertEquals(0, zoom3.getRGB(0, 255) >>> 24);
		assertEquals(6, tiles.size());
	}

	private static int rgb(int colour)
	{
		return 0xFF000000 | Integer.parseInt(ComponentTiles.PALETTE.get(colour), 16);
	}

	@Test
	public void largeUnreachableComponentsAreExpansesNotCounted() throws Exception
	{
		// A reachable pair and an unreachable strip of five tiles, then an expanse threshold of five.
		int a = t(10, 10), b = t(11, 10);
		int[][] strip = new int[4][];
		for (int i = 0; i < 4; i++)
		{
			strip[i] = edge(t(40 + i, 40), t(41 + i, 40));
		}
		World world = new World(edge(a, b), strip[0], strip[1], strip[2], strip[3]);
		var walking = RoutingStaticBuilder.walkingGraph(world, Map.of(), a);
		var built = RoutingStaticBuilder.build(world, Map.of(), Set.of(), new int[0], a);

		var counted = RoutingLayers.export(built.routingStatic, walking, 6);
		assertEquals(5, counted.unreachableCount);
		assertEquals(0, counted.expanseCount);

		var expanse = RoutingLayers.export(built.routingStatic, walking, 5);
		assertEquals(0, expanse.unreachableCount);
		assertEquals(5, expanse.expanseCount);
		assertEquals(3, expanse.square(t(42, 40)).status[local(t(42, 40))]);
		assertEquals(5, expanse.square(t(42, 40)).naturalSizes[local(t(42, 40))]);
		assertEquals(1, expanse.square(a).status[local(a)]);
		var listed = expanse.expanses.values().iterator().next();
		assertEquals(5, listed.tiles);
		assertArrayEquals(new int[]{40, 40, 45, 41}, listed.bounds);
	}

	@Test
	public void staleDuplicateAndNonSeparatingCutsUseProductionDiagnostics() throws Exception
	{
		int a = t(10, 10), b = t(11, 10), c = t(11, 11);
		World world = new World(edge(a, b), edge(b, c), edge(c, a));
		var built = RoutingStaticBuilder.build(world, Map.of(), Set.of(), new int[]{a, b, b, a, a, t(12, 10)}, a);
		assertEquals(1, built.diagnostics.duplicateCutCount);
		assertEquals(1, built.diagnostics.invalidCutCount);
		assertEquals(1, built.diagnostics.nonSeparatingCutCount);
		assertEquals(0, built.routingStatic.crossingCount());
	}

	@Test
	public void transportReachesDisconnectedLandWithoutAccountChecks() throws Exception
	{
		int a = t(10, 10), b = t(11, 10), u = t(40, 40), v = t(41, 40);
		World world = new World(edge(a, b), edge(u, v));
		Transport transport = new Transport.TransportBuilder().origin(a).destination(u).type(TransportType.TRANSPORT).duration(1).build();
		Map<Integer, Set<Transport>> transports = Map.of(a, Set.of(transport));
		var graph = RoutingStaticBuilder.walkingGraph(world, transports, a);
		var built = RoutingStaticBuilder.build(world, transports, Set.of(), new int[0], a);
		assertEquals(0, RoutingLayers.export(built.routingStatic, graph).unreachableCount);
		assertEquals(4, built.routingStatic.searchTileCount());
	}

	@Test
	public void rejectsCollisionFingerprintMismatch()
	{
		RoutingCuts cuts = new RoutingCuts(123, new RoutingCuts.Parameters(0, 0, 0, 0, 0, ""), new int[0]);
		assertThrows(IllegalStateException.class, () -> RoutingLayers.verifyFingerprint(cuts, new byte[]{1, 2}));
	}

	@Test
	public void rejectsCollisionReleaseWithDifferentUnderlyingContent() throws Exception
	{
		Path root = Files.createTempDirectory("routing-collision-test");
		try
		{
			Files.writeString(root.resolve("manifest.json"), "{\"format\":\"shortest-path-flags-v1\"}");
			Files.writeString(root.resolve("index.json"), "{}");
			SortedMap<String, byte[]> planes = new TreeMap<>();
			planes.put("8/1/1.bin", new byte[2048]);
			assertThrows(IOException.class, () -> RoutingLayers.verifyCollision(root, planes));
		}
		finally
		{
			Files.deleteIfExists(root.resolve("manifest.json"));
			Files.deleteIfExists(root.resolve("index.json"));
			Files.delete(root);
		}
	}
}
