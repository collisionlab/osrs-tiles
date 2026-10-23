package shortestpath.pathfinder.astar;

import mapstyle.routing.RoutingLayers;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import shortestpath.WorldPointUtil;
import shortestpath.pathfinder.*;
import shortestpath.transport.*;

public class RoutingLayersTest {
    static int t(int x,int y) { return WorldPointUtil.packWorldPoint(x,y,0); }
    /** Explicit synthetic edges only; all partition and reachability work runs in production. */
    static class World extends CollisionMap {
        final Set<Integer> tiles=new HashSet<>(); final Map<Integer,List<Integer>> edges=new HashMap<>();
        World(int[]... pairs) { super(null); for(int[] pair:pairs) { tiles.add(pair[0]); tiles.add(pair[1]); edges.computeIfAbsent(pair[0],k->new ArrayList<>()).add(pair[1]); edges.computeIfAbsent(pair[1],k->new ArrayList<>()).add(pair[0]); } }
        public SplitFlagMap.RegionExtent regionExtent() { return new SplitFlagMap.RegionExtent(0,0,1,1); }
        public byte getRegionPlaneCounts(int index) { return 1; }
        public boolean isBlocked(int x,int y,int p) { return !tiles.contains(WorldPointUtil.packWorldPoint(x,y,p)); }
        public int[] ordinaryWalkingNeighbors(int tile) { return edges.getOrDefault(tile,List.of()).stream().mapToInt(i->i).toArray(); }
        public byte ordinaryWalkingMask(int tile) {
            int mask=0; int[] dx={0,1,1,1,0,-1,-1,-1},dy={1,1,0,-1,-1,-1,0,1};
            for(int i=0;i<8;i++) if(edges.getOrDefault(tile,List.of()).contains(t((tile&32767)+dx[i],((tile>>>15)&32767)+dy[i]))) mask|=1<<i;
            return (byte)mask;
        }
    }
    static int[] edge(int a,int b) { return new int[]{a,b}; }
    @Test public void exportsProductionPartitionAndReachabilityAcrossSquareAndDiagonalCuts() throws Exception {
        int a=t(62,10),b=t(63,10),c=t(64,10),d=t(65,11),u=t(100,100),v=t(101,100);
        World world=new World(edge(a,b),edge(b,c),edge(c,d),edge(u,v));
        var walking=RoutingStaticBuilder.walkingGraph(world,Map.of(),a);
        var built=RoutingStaticBuilder.build(world,Map.of(),Set.of(),new int[]{b,c,c,d},a);
        var exported=RoutingLayers.export(built.routingStatic,walking);
        assertEquals(3,exported.components.size()); assertEquals(2,exported.crossings.size()); assertEquals(2,exported.unreachableCount);
        for(int i=0;i<built.routingStatic.searchTileCount();i++) {
            int tile=built.routingStatic.searchTile(i),local=((tile>>>15)&63)*64+(tile&63);
            assertEquals(built.routingStatic.routingComponent(i),exported.square(tile).owners[local]);
        }
        var plain=RoutingLayers.export(RoutingStaticBuilder.build(world,Map.of(),Set.of(),new int[0],a).routingStatic,walking);
        assertEquals(exported.unreachableCount,plain.unreachableCount);
        // No square seam on the uncut component; the diagonal crossing retains its original endpoints.
        int flag=plain.square(b).boundaries[10*64+63]&255;
        assertEquals(0,(flag>>>2)&3);
        assertArrayEquals(new int[]{65,11,0},exported.crossings.get(1).to);
        assertEquals(-1,exported.square(u).owners[100%64*64+100%64]);
        assertEquals(2,exported.square(u).status[100%64*64+100%64]);
    }
    @Test public void staleDuplicateAndNonSeparatingCutsUseProductionDiagnostics() throws Exception {
        int a=t(10,10),b=t(11,10),c=t(11,11);
        World world=new World(edge(a,b),edge(b,c),edge(c,a));
        var built=RoutingStaticBuilder.build(world,Map.of(),Set.of(),new int[]{a,b,b,a,a,t(12,10)},a);
        assertEquals(1,built.diagnostics.duplicateCutCount); assertEquals(1,built.diagnostics.invalidCutCount);
        assertEquals(1,built.diagnostics.nonSeparatingCutCount); assertEquals(0,built.routingStatic.crossingCount());
    }
    @Test public void transportReachesDisconnectedLandWithoutAccountChecks() throws Exception {
        int a=t(10,10),b=t(11,10),u=t(40,40),v=t(41,40);
        World world=new World(edge(a,b),edge(u,v));
        Transport transport=new Transport.TransportBuilder().origin(a).destination(u).type(TransportType.TRANSPORT).duration(1).build();
        Map<Integer,Set<Transport>> transports=Map.of(a,Set.of(transport));
        var graph=RoutingStaticBuilder.walkingGraph(world,transports,a);
        var built=RoutingStaticBuilder.build(world,transports,Set.of(),new int[0],a);
        assertEquals(0,RoutingLayers.export(built.routingStatic,graph).unreachableCount); assertEquals(4,built.routingStatic.searchTileCount());
    }
    @Test public void rejectsCollisionFingerprintMismatch() {
        RoutingCuts cuts=new RoutingCuts(123,new RoutingCuts.Parameters(0,0,0,0,0,""),new int[0]);
        assertThrows(IllegalStateException.class,()->RoutingLayers.verifyFingerprint(cuts,new byte[]{1,2}));
    }
    @Test public void rejectsCollisionReleaseWithDifferentUnderlyingContent() throws Exception {
        java.nio.file.Path root=java.nio.file.Files.createTempDirectory("routing-collision-test");
        try {
            java.nio.file.Files.writeString(root.resolve("manifest.json"),"{\"format\":\"shortest-path-flags-v1\"}");
            java.nio.file.Files.writeString(root.resolve("index.json"),"{}");
            java.util.SortedMap<String,byte[]> planes=new java.util.TreeMap<>(); planes.put("8/1/1.bin",new byte[2048]);
            assertThrows(java.io.IOException.class,()->RoutingLayers.verifyCollision(root,planes));
        } finally {
            java.nio.file.Files.deleteIfExists(root.resolve("manifest.json")); java.nio.file.Files.deleteIfExists(root.resolve("index.json")); java.nio.file.Files.delete(root);
        }
    }
}
