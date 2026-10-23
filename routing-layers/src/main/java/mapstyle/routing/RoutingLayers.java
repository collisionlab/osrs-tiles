package mapstyle.routing;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import shortestpath.pathfinder.*;
import shortestpath.pathfinder.astar.*;
import shortestpath.transport.ResourceLoader;

/** Offline projection of the pinned production graph; contains no graph construction algorithms. */
public final class RoutingLayers {
    static final Gson JSON = new Gson();
    static final int ABSENT = -1;
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("COLLISION_RELEASE_DIR TILE_VERSION OUTPUT_ROOT");
        Path release = Path.of(args[0]);
        byte[] collisionBytes = resource("/collision-map.zip"), cutBytes = resource(RoutingCuts.RESOURCE);
        RoutingCuts cuts = RoutingCuts.read(cutBytes);
        verifyFingerprint(cuts, collisionBytes);
        SortedMap<String, byte[]> collisionPlanes = collisionPlanes(collisionBytes);
        verifyCollision(release, collisionPlanes);
        JsonObject collisionManifest = JSON.fromJson(Files.readString(release.resolve("manifest.json")), JsonObject.class);
        String tileVersion = collisionManifest.get("cacheId").getAsString() + "-" + collisionManifest.get("rendererCommit").getAsString().substring(0,12);
        if (!args[1].equals(tileVersion)) throw new IOException("tile version differs from collision release provenance");
        CollisionMap collision = new CollisionMap(SplitFlagMap.fromResources());
        var transports = ResourceLoader.transports();
        var banks = ResourceLoader.destinations().get("bank");
        var result = RoutingStaticBuilder.build(collision, transports, banks == null ? Set.of() : banks, cuts.pairs());
        var walking = RoutingStaticBuilder.walkingGraph(collision, transports);
        var export = export(result.routingStatic, walking);
        // Include collision-bearing but entirely non-walkable planes, so missing data stays distinct.
        for (String name : collisionPlanes.keySet()) {
            String[] parts=name.split("/"); int plane=parts[0].startsWith("p") ? Integer.parseInt(parts[0].substring(1)) : 0;
            int sx=Integer.parseInt(parts[parts.length-2])+15, sy=Integer.parseInt(parts[parts.length-1].replace(".bin",""))+19;
            export.square(pack(sx*64,sy*64,plane));
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schemaVersion", 1);
        manifest.add("plugin", JSON.fromJson(new String(resource("/plugin.json"), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class));
        manifest.addProperty("tileVersion", args[1]);
        manifest.addProperty("collisionSha256", sha(collisionBytes));
        manifest.addProperty("collisionContentSha256", contentHash(collisionPlanes));
        manifest.addProperty("cutCollisionFingerprint", Long.toUnsignedString(cuts.collisionFingerprint(), 16));
        manifest.addProperty("separatorSha256", sha(cutBytes));
        // All packaged resources affect production interpretation, including banks and seasonal transports.
        SortedMap<String, byte[]> resources = new TreeMap<>();
        for (var type : shortestpath.transport.TransportType.values()) if (type.hasResourcePath())
            resources.put(type.getResourcePath(), resource(type.getResourcePath()));
        manifest.addProperty("transportSha256", contentHash(resources));
        manifest.addProperty("bankSha256", sha(resource("/destinations/game_features/bank.tsv")));
        manifest.addProperty("componentCount", export.components.size());
        manifest.addProperty("effectiveCrossingCount", result.routingStatic.crossingCount());
        manifest.addProperty("searchTileCount", result.routingStatic.searchTileCount());
        manifest.addProperty("structurallyUnreachableTileCount", export.unreachableCount);
        JsonObject diagnostics = new JsonObject();
        diagnostics.addProperty("raw", result.diagnostics.inputCutCount);
        diagnostics.addProperty("duplicate", result.diagnostics.duplicateCutCount);
        diagnostics.addProperty("invalid", result.diagnostics.invalidCutCount);
        diagnostics.addProperty("nonSeparating", result.diagnostics.nonSeparatingCutCount);
        manifest.add("cuts", diagnostics);
        SortedMap<String, byte[]> files = export.files();
        JsonObject checksums = new JsonObject();
        for (var entry : files.entrySet()) checksums.addProperty(entry.getKey(), sha(entry.getValue()));
        manifest.add("files", checksums);
        String version = sha(JSON.toJson(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 24);
        manifest.addProperty("version", version);
        Path output = Path.of(args[2]).resolve(version);
        if (Files.exists(output)) throw new IOException("immutable output already exists: " + output);
        for (var entry : files.entrySet()) {
            Path file = output.resolve(entry.getKey()); Files.createDirectories(file.getParent()); Files.write(file, entry.getValue());
        }
        Files.writeString(output.resolve("manifest.json"), JSON.toJson(manifest) + "\n");
        System.out.println(output);
    }

    public static final class Square {
        public final int[] owners = new int[4096], naturalSizes = new int[4096];
        public final byte[] status = new byte[4096], boundaries = new byte[4096];
        public final List<Integer> crossings = new ArrayList<>();
        Square() { Arrays.fill(owners, ABSENT); }
        public byte[] bytes() {
            SortedSet<Integer> palette = new TreeSet<>(); for (int id : owners) if (id >= 0) palette.add(id);
            List<Integer> ids = new ArrayList<>(palette); Map<Integer, Integer> refs = new HashMap<>();
            for (int i=0;i<ids.size();i++) refs.put(ids.get(i), i+1);
            ByteBuffer out = ByteBuffer.allocate(16 + ids.size()*4 + 4096*8 + crossings.size()*4).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(0x314c5452).putInt(ids.size()).putInt(crossings.size()).putInt(4096);
            for (int id : ids) out.putInt(id);
            for (int i=0;i<4096;i++) out.putShort((short)(owners[i]<0 ? 0 : refs.get(owners[i]))).put(status[i]).put(boundaries[i]).putInt(naturalSizes[i]);
            for (int id : crossings) out.putInt(id);
            return out.array();
        }
    }
    public static final class Component {
        public int id, plane, tileCount, separatorCrossings;
        public int[] bounds, representative;
        public final SortedSet<Integer> neighbours = new TreeSet<>();
        Component(int id, int tile) { this.id=id; plane=p(tile); representative=point(tile); bounds=new int[]{x(tile),y(tile),x(tile)+1,y(tile)+1}; }
        void add(int tile) { tileCount++; bounds[0]=Math.min(bounds[0],x(tile)); bounds[1]=Math.min(bounds[1],y(tile)); bounds[2]=Math.max(bounds[2],x(tile)+1); bounds[3]=Math.max(bounds[3],y(tile)+1); }
    }
    public static final class Crossing {
        public int id, cost;
        public Integer fromComponent, toComponent;
        public int[] from, to;
    }
    public static final class Export {
        public final SortedMap<String, Square> squares = new TreeMap<>();
        public final SortedMap<Integer, Component> components = new TreeMap<>();
        public final List<Crossing> crossings = new ArrayList<>();
        public long unreachableCount;
        SortedMap<String, byte[]> files() {
            SortedMap<String, byte[]> files = new TreeMap<>();
            squares.forEach((name,square) -> {
                files.put(name, square.bytes());
                if(!square.crossings.isEmpty()) files.put(name.replace(".bin", ".crossings.json"), jsonBytes(square.crossings.stream().map(crossings::get).toArray()));
            });
            files.put("components.json", jsonBytes(components.values())); files.put("crossings.json", jsonBytes(crossings));
            return files;
        }
        public Square square(int tile) { return squares.computeIfAbsent(path(tile), k -> new Square()); }
    }
    public static Export export(RoutingStatic routing, RoutingStaticBuilder.WalkingGraph walking) {
        Export output = new Export(); Map<Integer,Integer> sizes = new HashMap<>();
        for (int i=0;i<walking.tileCount();i++) sizes.merge(walking.naturalComponent(i),1,Integer::sum);
        for (int i=0;i<walking.tileCount();i++) {
            int tile=walking.tile(i), natural=walking.naturalComponent(i), index=local(tile); Square square=output.square(tile);
            boolean reachable=walking.isStructurallyReachable(natural);
            square.status[index]=(byte)(reachable ? 1 : 2); square.naturalSizes[index]=sizes.get(natural);
            if (!reachable) output.unreachableCount++;
        }
        for (int i=0;i<routing.searchTileCount();i++) {
            int tile=routing.searchTile(i), id=routing.routingComponent(i);
            if (output.square(tile).status[local(tile)] != 1) throw new IllegalStateException("search tile is not structurally reachable");
            output.square(tile).owners[local(tile)]=id;
            output.components.computeIfAbsent(id,k -> new Component(id,tile)).add(tile);
            // Two bits per side (N,E,S,W): 1 exterior, 2 other production component; global lookup avoids square seams.
            int[] dx={0,1,0,-1},dy={1,0,-1,0}; int flags=0;
            for(int side=0;side<4;side++) {
                int next=routing.searchIndex(pack(x(tile)+dx[side], y(tile)+dy[side],p(tile)));
                int kind=next<0 ? 1 : routing.routingComponent(next)!=id ? 2 : 0;
                flags |= kind << (side*2);
            }
            output.square(tile).boundaries[local(tile)]=(byte)flags;
        }
        for (int i=0;i<routing.crossingCount();i++) {
            int from=routing.siteTile(routing.crossingFromSite(i)), to=routing.siteTile(routing.crossingToSite(i));
            Crossing crossing=new Crossing(); crossing.id=i; crossing.from=point(from); crossing.to=point(to); crossing.cost=routing.crossingCost(i);
            // Crossings can be retained on excluded land: use authoritative site membership rather than inventing search IDs.
            int[] a=routing.siteComponents(routing.crossingFromSite(i)), b=routing.siteComponents(routing.crossingToSite(i));
            if(a.length>1 || b.length>1) throw new IllegalStateException("separator endpoint has ambiguous component membership");
            crossing.fromComponent=a.length==0 ? null : a[0]; crossing.toComponent=b.length==0 ? null : b[0]; output.crossings.add(crossing);
            output.square(from).crossings.add(i); if(!path(from).equals(path(to))) output.square(to).crossings.add(i);
            for (int[] pair : a.length==0 || b.length==0 ? new int[0][] : new int[][]{{a[0],b[0]},{b[0],a[0]}}) {
                Component component=output.components.get(pair[0]);
                if(component!=null) { component.separatorCrossings++; component.neighbours.add(pair[1]); }
            }
        }
        return output;
    }
    public static void verifyFingerprint(RoutingCuts cuts, byte[] collision) {
        if(cuts.collisionFingerprint()!=RoutingCuts.fingerprint(collision)) throw new IllegalStateException("routing-cuts collision fingerprint differs from packaged collision-map.zip");
    }
    public static SortedMap<String,byte[]> collisionPlanes(byte[] zip) throws IOException {
        SortedMap<String,byte[]> result=new TreeMap<>();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while((entry=in.getNextEntry())!=null) {
                if(!entry.getName().matches("[0-9]+_[0-9]+")) throw new IOException("unexpected collision entry");
                String[] xy=entry.getName().split("_"); int sx=Integer.parseInt(xy[0])-15, sy=Integer.parseInt(xy[1])-19;
                byte[] raw=in.readAllBytes(); if(raw.length>8192) throw new IOException("oversize collision square");
                for(int p=0;p<4;p++) {
                    byte[] plane=new byte[2048]; int start=p*2048;
                    if(start<raw.length) System.arraycopy(raw,start,plane,0,Math.min(2048,raw.length-start));
                    boolean nonzero=false; for(byte b:plane) nonzero |= b!=0;
                    if(nonzero && result.put((p==0 ? "" : "p"+p+"/")+"8/"+sx+"/"+sy+".bin",plane)!=null) throw new IOException("duplicate collision entry");
                }
            }
        }
        return result;
    }
    public static void verifyCollision(Path release, SortedMap<String,byte[]> planes) throws Exception {
        JsonObject manifest=JSON.fromJson(Files.readString(release.resolve("manifest.json")),JsonObject.class);
        if(!"shortest-path-flags-v1".equals(manifest.get("format").getAsString())) throw new IOException("unsupported collision format");
        JsonObject index=JSON.fromJson(Files.readString(release.resolve("index.json")),JsonObject.class);
        if(!index.keySet().equals(planes.keySet())) throw new IOException("tile release collision square set differs from plugin");
        for(var entry:planes.entrySet()) {
            byte[] published=Files.readAllBytes(release.resolve(entry.getKey()));
            if(!sha(published).equals(index.get(entry.getKey()).getAsString()) || !Arrays.equals(published,entry.getValue()))
                throw new IOException("tile release collision differs at " + entry.getKey());
        }
    }
    static byte[] resource(String name) throws IOException { try(InputStream in=RoutingLayers.class.getResourceAsStream(name)) { if(in==null) throw new IOException("missing production resource " + name); return in.readAllBytes(); } }
    static byte[] jsonBytes(Object value) { return JSON.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    static String contentHash(SortedMap<String,byte[]> files) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(var entry:files.entrySet()) { digest.update(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte)0); digest.update(hexDigest(entry.getValue())); }
        return hex(digest.digest());
    }
    static byte[] hexDigest(byte[] data) throws Exception { return MessageDigest.getInstance("SHA-256").digest(data); }
    static String hex(byte[] data) { StringBuilder s=new StringBuilder(); for(byte b:data) s.append(String.format("%02x",b)); return s.toString(); }
    static String sha(byte[] data) throws Exception { return hex(hexDigest(data)); }
    static int x(int tile) { return tile&32767; } static int y(int tile) { return (tile>>>15)&32767; } static int p(int tile) { return tile>>>30; }
    static int pack(int x,int y,int plane) { return (x&32767)|((y&32767)<<15)|(plane<<30); }
    static int[] point(int tile) { return new int[]{x(tile),y(tile),p(tile)}; }
    static int local(int tile) { return (y(tile)&63)*64+(x(tile)&63); }
    static String path(int tile) { return "p"+p(tile)+"/"+(x(tile)>>6)+"/"+(y(tile)>>6)+".bin"; }
}
