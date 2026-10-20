#!/usr/bin/env python3
"""Precompute where each inset area hangs off the main map, from transport records and curated anchors.

An anchor is a surface point and the chain of connections from it into an area. Every reachable area
needs one (label_inset_regions reports those without). In order of preference, anchors come from:

- physical connections (ladders, stairs, doors: `policy` local), followed in either direction;
- curated anchors in `data/inset-labels/annotations.json`, for areas no transport places, such as
  ones entered by digging. One starts on the surface or in another area, whose anchors it extends;
- any other transport with a source tile (a lever, a portal, an NPC), followed in its own direction.
  Seasonal rows and network hubs (a source tile with more than two destinations in one file, such as a
  fairy ring) never anchor.
"""
import argparse
import bisect
import csv
import hashlib
import heapq
import json
from collections import Counter, defaultdict, deque
from pathlib import Path


LOCAL_FILES = {'transports.tsv', 'agility_shortcuts.tsv'}
# Never anchors: these rows exist only in leagues and other seasonal worlds.
SEASONAL_FILES = {'seasonal_transports.tsv'}
# A source tile with more destinations than this in one file is a network station, not an entrance.
HUB_DESTINATIONS = 2
# An endpoint off every area's terrain joins the only inset area within this many tiles.
SNAP_TILES = 2
PHYSICAL_ACTIONS = {
    'ascend', 'balance', 'bottom-floor', 'cellar', 'chop-down', 'climb',
    'climb-down', 'climb-into', 'climb-off', 'climb-over', 'climb-through',
    'climb-under', 'climb-up', 'crawl', 'crawl-down', 'crawl-through',
    'crawl-under', 'cross', 'cross-bridge', 'descend', 'dodge', 'enter', 'exit',
    'exit-through', 'go-down', 'go-through', 'go-up', 'grapple', 'hang-over',
    'hurdle', 'jump', 'jump-across', 'jump-down', 'jump-from', 'jump-into',
    'jump-off', 'jump-onto', 'jump-over', 'jump-to', 'jump-up', 'leap', 'leave',
    'manoeuvre-past', 'mine', 'mines', 'move', 'open', 'pass', 'pass-through',
    'pick-lock', 'picklock', 'pull', 'push', 'quick-pass', 'search', 'slash',
    'slide', 'squeeze-past', 'squeeze-through', 'step-over', 'swim', 'swim-to',
    'swing', 'swing-across', 'swing-on', 'teeth-grip', 'top-floor', 'unlock',
    'use-lift', 'vault', 'walk', 'walk-across', 'walk-down', 'walk-on',
    'walk-through', 'walk-up',
}


def policy(filename, action):
    """An explicit conservative heuristic, not an account eligibility check."""
    if filename not in LOCAL_FILES:
        return 'non-local', 'travel-network-or-teleport-file'
    words = action.lower().split()
    if not words:
        return 'review', 'missing-action'
    if any(word in action.lower() for word in ('portal', 'teleport', 'fairy ring', 'spirit tree', 'rift')):
        return 'non-local', 'magical-transition'
    if words[0] in PHYSICAL_ACTIONS:
        return 'local', 'physical-action'
    if words[0] == 'use' and any(word in words[1:] for word in ('ladder', 'stairs', 'staircase', 'trapdoor', 'rope', 'lift', 'hatch')):
        return 'local', 'physical-object'
    return 'review', 'unrecognized-physical-action'


def coordinate(value, source):
    if not value.strip():
        return None
    try:
        point = [int(n) for n in value.split()]
    except ValueError as error:
        raise ValueError(f'{source}: invalid coordinate {value!r}') from error
    if len(point) != 3 or not all(0 <= n < 16384 for n in point[:2]) or not 0 <= point[2] < 4:
        raise ValueError(f'{source}: invalid coordinate {value!r}')
    return point


def read_transports(directory):
    # Account restrictions describe teleport families, not geographic transport edges.
    files = sorted(file for file in directory.glob('*.tsv') if file.name != 'teleport_restrictions.tsv')
    if not files or not any(f.name == 'transports.tsv' for f in files):
        raise ValueError(f'Transport directory missing transports.tsv: {directory}')
    rows = []
    digest = hashlib.sha256()
    for file in files:
        raw = file.read_bytes()
        digest.update(file.name.encode() + b'\0' + raw + b'\0')
        lines = raw.decode('utf-8').splitlines()
        header = lines[0].lstrip('# ').split('\t')
        if 'Destination' not in header:
            raise ValueError(f'{file.name}: missing Destination column')
        for line_number, line in enumerate(lines[1:], 2):
            if not line.strip() or line.startswith('#'):
                continue
            values = next(csv.reader([line], delimiter='\t'))
            if len(values) > len(header) and any(values[len(header):]):
                raise ValueError(f'{file.name}:{line_number}: unexpected columns')
            fields = dict(zip(header, values))
            source = f'{file.name}:{line_number}'
            rows.append(dict(file=file.name, line=line_number,
                             source=coordinate(fields.get('Origin', ''), source),
                             target=coordinate(fields.get('Destination', ''), source),
                             action=fields.get('menuOption menuTarget objectID', ''),
                             label=fields.get('Display info', ''),
                             conditions={k: v for k, v in fields.items() if v and k not in {
                                 'Origin', 'Destination', 'menuOption menuTarget objectID', 'Display info', 'Duration'}},
                             duration=fields.get('Duration', '')))
    return rows, digest.hexdigest()


class RegionIndex:
    def __init__(self, catalogue):
        if catalogue.get('schemaVersion') != 1 or not catalogue.get('surfaceRects'):
            raise ValueError('Regenerate regions.json with surfaceRects before computing anchors')
        self.regions = {r['id']: r for r in catalogue['regions']}
        self.rows = defaultdict(list)
        for region in catalogue['regions']:
            for x, y, w, h in region['mask']:
                for row in range(y, y + h):
                    self.rows[region['plane'], row].append((x, x + w, region['id']))
        for intervals in self.rows.values():
            intervals.sort()
            if any(a[1] > b[0] for a, b in zip(intervals, intervals[1:])):
                raise ValueError('Region masks overlap on the same plane')
        self.surface = catalogue['surfaceRects']

    def region_at(self, x, y, plane):
        intervals = self.rows.get((plane, y), [])
        index = bisect.bisect_right(intervals, (x, 16384, '\uffff')) - 1
        return intervals[index][2] if index >= 0 and x < intervals[index][1] else None

    def nearby_insets(self, x, y, plane):
        """Inset areas with terrain within SNAP_TILES of a point."""
        found = set()
        for row in range(y - SNAP_TILES, y + SNAP_TILES + 1):
            for start, end, region_id in self.rows.get((plane, row), []):
                if start <= x + SNAP_TILES and end > x - SNAP_TILES and self.regions[region_id]['classification'] == 'inset':
                    found.add(region_id)
        return found

    def endpoint(self, point):
        if point is None:
            return None
        x, y, plane = point
        region_id = self.region_at(x, y, plane)
        on_surface = any(rx <= x < rx + w and ry <= y < ry + h for _, rx, ry, w, h in self.surface)
        membership = 'mask' if region_id else 'surface-footprint' if on_surface else 'unmatched'
        if membership == 'unmatched':
            # Stairs often land a tile or two off the floor they lead to. Snap only when that is unambiguous.
            nearby = self.nearby_insets(x, y, plane)
            if len(nearby) == 1:
                region_id, membership = nearby.pop(), 'snapped'
        kind = 'surface' if on_surface else 'inset' if region_id and self.regions[region_id]['classification'] == 'inset' else 'unknown'
        return dict(point=point, regionId=region_id, kind=kind, membership=membership)


def reachable(index, connections):
    """Inset regions some chain of transports reaches, following each row's own direction.

    Every row counts, teleports and travel networks included: this is what a player can get to,
    not geography. Chains start on the main world (any non-inset region) or at a row with no
    source, such as a spell, which can be used from anywhere.
    """
    def entered(endpoint):
        return endpoint is None or endpoint['kind'] == 'surface' or bool(
            endpoint['regionId'] and index.regions[endpoint['regionId']]['classification'] != 'inset')

    edges = defaultdict(set)
    reached = set()
    for connection in connections.values():
        source, target = connection['source'], connection['target']
        if connection['policy'] == 'curated':
            continue  # placement, not a way in
        if not target or not target['regionId'] or index.regions[target['regionId']]['classification'] != 'inset':
            continue
        if entered(source):
            reached.add(target['regionId'])
        elif source['regionId']:
            edges[source['regionId']].add(target['regionId'])
    queue = deque(sorted(reached))
    while queue:
        for neighbour in sorted(edges[queue.popleft()]):
            if neighbour not in reached:
                reached.add(neighbour)
                queue.append(neighbour)
    return sorted(reached)


def curated_connections(index, curated):
    """Connections for annotations.json's anchors: from a surface point, or a point in another area, to the area's seed."""
    connections, issues = {}, []
    for entry in curated:
        source, target = index.endpoint(entry['point']), index.endpoint(entry['seed'])
        if (source['kind'] not in ('surface', 'inset') or source['regionId'] == entry['componentId']
                or target['regionId'] != entry['componentId'] or target['kind'] != 'inset'):
            issues.append(dict(area=entry['area'], componentId=entry['componentId'], point=entry['point'],
                               reason='curated-anchor-not-into-component'))
            continue
        connection_id = 'a-' + hashlib.sha256(json.dumps([entry['area'], entry['componentId']]).encode()).hexdigest()[:16]
        connections[connection_id] = dict(id=connection_id, source=source, target=target, action='', label=entry['reason'],
                                          conditions={}, duration='', policy='curated', reason='annotations.json',
                                          sources=[dict(file='annotations.json', area=entry['area'])])
    return connections, issues


def curated_anchors(annotations):
    """The `anchor` of every component association in annotations.json."""
    return [dict(area=area['key'], componentId=association['componentId'], seed=association['seed'],
                 point=association['anchor']['point'], reason=association['anchor']['reason'])
            for area in (annotations or {}).get('areas', []) for association in area['components']
            if association.get('anchor')]


def hubs(transports):
    """(file, source point) pairs with more than HUB_DESTINATIONS destinations: network stations."""
    targets = defaultdict(set)
    for row in transports:
        if row['source'] and row['target']:
            targets[row['file'], tuple(row['source'])].add(tuple(row['target']))
    return {key for key, points in targets.items() if len(points) > HUB_DESTINATIONS}


def build_anchors(catalogue, transports, annotations=None):
    index = RegionIndex(catalogue)
    connections = {}
    totals = Counter()
    hub_sources = hubs(transports)
    for row in transports:
        totals['transportRows'] += 1
        source, target = index.endpoint(row['source']), index.endpoint(row['target'])
        kind, reason = policy(row['file'], row['action'])
        if source is None or target is None:
            kind, reason = 'non-local', 'global-or-generated-network-endpoint'
        if not any(endpoint and endpoint['kind'] == 'inset' for endpoint in (source, target)):
            if kind == 'local' and source and target and any(e['kind'] == 'unknown' for e in (source, target)):
                totals['unmatchedLocalRows'] += 1
            else:
                continue
        elif source and target and source['regionId'] == target['regionId']:
            continue
        identity = {k: v for k, v in row.items() if k != 'line'}
        connection_id = 'c-' + hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(',', ':')).encode()).hexdigest()[:16]
        if connection_id in connections:
            connections[connection_id]['sources'].append(dict(file=row['file'], line=row['line']))
            continue
        if kind == 'local' and any(e and e['kind'] == 'unknown' for e in (source, target)):
            kind, reason = 'review', 'endpoint-not-classified-as-inset-or-surface'
        # Whether this row places its target when nothing physical does (see the module docstring).
        directed = (kind != 'local' and source is not None and row['file'] not in SEASONAL_FILES
                    and (row['file'], tuple(row['source'])) not in hub_sources)
        connections[connection_id] = dict(id=connection_id, source=source, target=target,
            action=row['action'], label=row['label'], conditions=row['conditions'], duration=row['duration'],
            policy=kind, reason=reason, anchors='directed' if directed else 'undirected' if kind == 'local' else 'no',
            sources=[dict(file=row['file'], line=row['line'])])
    curated, curated_issues = curated_connections(index, curated_anchors(annotations))
    connections.update(curated)
    connections = dict(sorted(connections.items()))
    by_region = defaultdict(list)
    # Edges carry how many non-physical connections they cost: anchors use as few as they can.
    graph = defaultdict(list)
    seeds = defaultdict(list)
    for connection in connections.values():
        source, target = connection['source'], connection['target']
        for endpoint in (source, target):
            if endpoint and endpoint['kind'] == 'inset':
                by_region[endpoint['regionId']].append(connection['id'])
        if connection['policy'] == 'curated':
            if source['kind'] == 'surface':
                seeds[tuple(source['point'])].append((target['regionId'], connection['id'], False, 0))
            else:
                graph[source['regionId']].append((target['regionId'], connection['id'], False, 0))
            continue
        if (connection['anchors'] == 'no' or not source or not target
                or source['kind'] == 'unknown' or target['kind'] == 'unknown'):
            continue
        cost = 0 if connection['anchors'] == 'undirected' else 1
        if source['kind'] == target['kind'] == 'inset':
            graph[source['regionId']].append((target['regionId'], connection['id'], False, cost))
            if not cost:
                graph[target['regionId']].append((source['regionId'], connection['id'], True, cost))
        elif source['kind'] == 'surface' and target['kind'] == 'inset':
            seeds[tuple(source['point'])].append((target['regionId'], connection['id'], False, cost))
        elif source['kind'] == 'inset' and target['kind'] == 'surface' and not cost:
            seeds[tuple(target['point'])].append((source['regionId'], connection['id'], True, cost))
    region_anchors = defaultdict(list)
    for point, entries in sorted(seeds.items()):
        # Geographic associations, never account-usable routes: physical connections count both ways.
        # Paths are cheapest first by (non-physical connections, hops).
        best = {}
        # Ties go to the path whose connections sort first by (reversed, id), as before costs existed.
        heap = [((cost, 1), region_id, ((reversed_edge, connection_id),))
                for region_id, connection_id, reversed_edge, cost in entries]
        heapq.heapify(heap)
        while heap:
            key, current, path = heapq.heappop(heap)
            if current in best:
                continue
            best[current] = (key, path)
            for neighbour, connection_id, reversed_edge, cost in graph[current]:
                if neighbour not in best:
                    heapq.heappush(heap, ((key[0] + cost, key[1] + 1), neighbour, path + ((reversed_edge, connection_id),)))
        for region_id, ((indirect, hops), path) in best.items():
            region_anchors[region_id].append(dict(point=list(point), hops=hops, indirectConnections=indirect,
                                                  curated=any(connections[c]['policy'] == 'curated' for _, c in path),
                                                  path=[dict(connectionId=c, reversed=r) for r, c in path]))
    regions = {}
    for region_id, region in sorted(index.regions.items()):
        if region['classification'] != 'inset':
            continue
        anchors = region_anchors[region_id]
        if anchors:
            # Only the anchors that need the fewest non-physical connections, curated ones first on a tie.
            fewest = min(a['indirectConnections'] for a in anchors)
            anchors = [a for a in anchors if a['indirectConnections'] == fewest]
        anchors = sorted(anchors, key=lambda a: (a['hops'], not a['curated'], a['point'], [s['connectionId'] for s in a['path']]))
        regions[region_id] = dict(status='direct' if anchors and anchors[0]['hops'] == 1 else 'indirect' if anchors else 'unresolved',
                                 defaultAnchor=0 if anchors else None, anchors=anchors,
                                 connectionIds=sorted(set(by_region[region_id])))
    totals.update({f'{kind}Regions': sum(r['status'] == kind for r in regions.values()) for kind in ('direct', 'indirect', 'unresolved')})
    totals.update({f'{kind}Connections': sum(c['policy'] == kind for c in connections.values()) for kind in ('local', 'non-local', 'review')})
    reachable_ids = reachable(index, connections)
    totals['reachableRegions'] = len(reachable_ids)
    totals['referenceEndpoints'] = len(catalogue.get('checks', []))
    totals['anchoredReferenceEndpoints'] = sum(
        len(c['regionIds']) == 1 and regions.get(c['regionIds'][0], {}).get('status') in ('direct', 'indirect')
        for c in catalogue.get('checks', []))
    totals['curatedAnchors'] = len(curated)
    return dict(schemaVersion=1, topologySha256=catalogue['topologySha256'],
                policy='physical actions in transports/agility_shortcuts, then curated anchors, then other sourced rows '
                       'except seasonal ones and network hubs; mask membership, or the only inset area within 2 tiles',
                traversal='physical connections undirected, others in their own direction; stored transport '
                          'directions and conditions remain authoritative for travel',
                curatedIssues=curated_issues,
                insetRegionIds=[region_id for region_id, record in regions.items() if record['anchors']],
                reachability='directed; every transport row; from the main world or a sourceless row',
                reachableRegionIds=reachable_ids,
                summary=dict(totals), regions=regions, connections=connections)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('catalogue', type=Path, nargs='?', default=Path('build/inset-metadata/regions.json'))
    parser.add_argument('--transports-dir', type=Path, default=Path('shortest-path/src/main/resources/transports'))
    parser.add_argument('--annotations', type=Path, default=Path('data/inset-labels/annotations.json'))
    parser.add_argument('--output', type=Path, default=Path('build/inset-metadata/anchors.json'))
    args = parser.parse_args()
    raw = args.catalogue.read_bytes()
    transports, digest = read_transports(args.transports_dir)
    result = build_anchors(json.loads(raw), transports, json.loads(args.annotations.read_text()))
    result['catalogueSha256'] = hashlib.sha256(raw).hexdigest()
    result['transportsSha256'] = digest
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, separators=(',', ':')) + '\n')
    print(json.dumps(result['summary'], sort_keys=True))
    print(f"Wrote {args.output}")


if __name__ == '__main__':
    main()
