#!/usr/bin/env python3
"""Precompute geographic inset anchors from explicit, directed transport records."""
import argparse
import bisect
import csv
import hashlib
import json
from collections import Counter, defaultdict, deque
from pathlib import Path


LOCAL_FILES = {'transports.tsv', 'agility_shortcuts.tsv'}
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
    files = sorted(directory.glob('*.tsv'))
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

    def endpoint(self, point):
        if point is None:
            return None
        x, y, plane = point
        intervals = self.rows.get((plane, y), [])
        index = bisect.bisect_right(intervals, (x, 16384, '\uffff')) - 1
        region_id = intervals[index][2] if index >= 0 and x < intervals[index][1] else None
        on_surface = any(rx <= x < rx + w and ry <= y < ry + h for _, rx, ry, w, h in self.surface)
        kind = 'surface' if on_surface else 'inset' if region_id and self.regions[region_id]['classification'] == 'inset' else 'unknown'
        return dict(point=point, regionId=region_id, kind=kind,
                    membership='mask' if region_id else 'surface-footprint' if on_surface else 'unmatched')


def build_anchors(catalogue, transports):
    index = RegionIndex(catalogue)
    connections = {}
    totals = Counter()
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
        connections[connection_id] = dict(id=connection_id, source=source, target=target,
            action=row['action'], label=row['label'], conditions=row['conditions'], duration=row['duration'],
            policy=kind, reason=reason, sources=[dict(file=row['file'], line=row['line'])])
    connections = dict(sorted(connections.items()))
    by_region = defaultdict(list)
    graph = defaultdict(list)
    seeds = defaultdict(list)
    for connection in connections.values():
        source, target = connection['source'], connection['target']
        for endpoint in (source, target):
            if endpoint and endpoint['kind'] == 'inset':
                by_region[endpoint['regionId']].append(connection['id'])
        if connection['policy'] != 'local':
            continue
        if source['kind'] == target['kind'] == 'inset':
            graph[source['regionId']].append((target['regionId'], connection['id'], False))
            graph[target['regionId']].append((source['regionId'], connection['id'], True))
        elif {source['kind'], target['kind']} == {'surface', 'inset'}:
            surface, inset = (source, target) if source['kind'] == 'surface' else (target, source)
            seeds[tuple(surface['point'])].append((inset['regionId'], connection['id'], source['kind'] == 'inset'))
    region_anchors = defaultdict(list)
    for point, entries in sorted(seeds.items()):
        # Undirected traversal discovers geographic associations, never account-usable routes.
        paths = {}
        queue = deque()
        for region_id, connection_id, reversed_edge in sorted(entries, key=lambda e: (e[0], e[2], e[1])):
            if region_id not in paths:
                paths[region_id] = [dict(connectionId=connection_id, reversed=reversed_edge)]
                queue.append(region_id)
        while queue:
            current = queue.popleft()
            for neighbour, connection_id, reversed_edge in sorted(graph[current], key=lambda e: (e[0], e[2], e[1])):
                if neighbour not in paths:
                    paths[neighbour] = paths[current] + [dict(connectionId=connection_id, reversed=reversed_edge)]
                    queue.append(neighbour)
        for region_id, path in paths.items():
            region_anchors[region_id].append(dict(point=list(point), hops=len(path), path=path))
    regions = {}
    for region_id, region in sorted(index.regions.items()):
        if region['classification'] != 'inset':
            continue
        anchors = sorted(region_anchors[region_id], key=lambda a: (a['hops'], a['point'], [s['connectionId'] for s in a['path']]))
        regions[region_id] = dict(status='direct' if anchors and anchors[0]['hops'] == 1 else 'indirect' if anchors else 'unresolved',
                                 defaultAnchor=0 if anchors else None, anchors=anchors,
                                 connectionIds=sorted(set(by_region[region_id])))
    totals.update({f'{kind}Regions': sum(r['status'] == kind for r in regions.values()) for kind in ('direct', 'indirect', 'unresolved')})
    totals.update({f'{kind}Connections': sum(c['policy'] == kind for c in connections.values()) for kind in ('local', 'non-local', 'review')})
    totals['referenceEndpoints'] = len(catalogue.get('checks', []))
    totals['anchoredReferenceEndpoints'] = sum(
        len(c['regionIds']) == 1 and regions.get(c['regionIds'][0], {}).get('status') in ('direct', 'indirect')
        for c in catalogue.get('checks', []))
    return dict(schemaVersion=1, topologySha256=catalogue['topologySha256'],
                policy='physical actions in transports/agility_shortcuts; no teleport or travel network anchoring; exact mask membership',
                traversal='undirected geography; stored transport directions and conditions remain authoritative for travel',
                insetRegionIds=[region_id for region_id, record in regions.items() if record['anchors']],
                summary=dict(totals), regions=regions, connections=connections)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('catalogue', type=Path, nargs='?', default=Path('build/inset-metadata/regions.json'))
    parser.add_argument('--transports-dir', type=Path, default=Path('shortest-path/src/main/resources/transports'))
    parser.add_argument('--output', type=Path, default=Path('build/inset-metadata/anchors.json'))
    args = parser.parse_args()
    raw = args.catalogue.read_bytes()
    transports, digest = read_transports(args.transports_dir)
    result = build_anchors(json.loads(raw), transports)
    result['catalogueSha256'] = hashlib.sha256(raw).hexdigest()
    result['transportsSha256'] = digest
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, separators=(',', ':')) + '\n')
    print(json.dumps(result['summary'], sort_keys=True))
    print(f"Wrote {args.output}")


if __name__ == '__main__':
    main()
