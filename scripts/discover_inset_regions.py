#!/usr/bin/env python3
"""Label terrain spans with four-neighbour connectivity; retain exact clipping geometry."""
import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path


def merge(intervals):
    result = []
    for start, end in sorted(intervals):
        if result and start <= result[-1][1]:
            result[-1][1] = max(end, result[-1][1])
        else:
            result.append([start, end])
    return result


def rectangles(spans):
    """Merge identical spans vertically, preserving concavities and holes exactly."""
    result, active = [], {}
    previous_y = None
    for y, intervals in sorted(spans.items()):
        next_active = {}
        for x1, x2 in intervals:
            key = (x1, x2)
            if previous_y is not None and y == previous_y + 1:
                index = active.get(key)
            else:
                index = None
            if index is None:
                index = len(result)
                result.append([x1, y, x2 - x1, 1])
            else:
                result[index][3] += 1
            next_active[key] = index
        active, previous_y = next_active, y
    return result


def discover(topology, examples=()):
    if topology.get('schemaVersion') != 1:
        raise ValueError('Unsupported topology schema')
    rows = defaultdict(list)
    for span in topology['spans']:
        if (len(span) != 4 or any(type(n) is not int for n in span)
                or not 0 <= span[0] < 4 or not 0 <= span[1] < 16384
                or not 0 <= span[2] < span[3] <= 16384):
            raise ValueError(f'Invalid terrain span: {span}')
        p, y, x1, x2 = span
        rows[p, y].append([x1, x2])
    if not rows:
        raise ValueError('Empty topology')
    surface_rows = defaultdict(list)
    for p, x, y, w, h in topology['surfaceRects']:
        if not (0 <= p < 4 and 0 <= x < x + w <= 16384 and 0 <= y < y + h <= 16384):
            raise ValueError('Invalid surface coverage rectangle')
        # Upper floors at native surface XY also belong to the main-world footprint.
        for row_y in range(y, y + h):
            surface_rows[row_y].append([x, x + w])
    if not surface_rows:
        raise ValueError('Main-world coverage missing')
    surface_rows = {y: merge(intervals) for y, intervals in surface_rows.items()}
    loaded = set(topology['loadedMapSquares'])
    parents, runs = [], []

    def root(i):
        while parents[i] != i:
            parents[i] = parents[parents[i]]
            i = parents[i]
        return i

    previous, previous_key = [], None
    for key, intervals in sorted(rows.items()):
        current = []
        if previous_key != (key[0], key[1] - 1):
            previous = []
        left = 0
        for x1, x2 in merge(intervals):
            index = len(runs)
            parents.append(index)
            runs.append((key[0], key[1], x1, x2))
            current.append((x1, x2, index))
            while left < len(previous) and previous[left][1] <= x1:
                left += 1
            j = left
            while j < len(previous) and previous[j][0] < x2:
                parents[root(index)] = root(previous[j][2])
                j += 1
        previous, previous_key = current, key
    groups = defaultdict(list)
    for i, run in enumerate(runs):
        groups[root(i)].append(run)
    regions = []
    for group in groups.values():
        plane = group[0][0]
        shape = defaultdict(list)
        count = surface_count = 0
        unknown = False
        for _, y, x1, x2 in group:
            shape[y].append([x1, x2])
            count += x2 - x1
            surface_count += sum(max(0, min(x2, end) - max(x1, start))
                                 for start, end in surface_rows.get(y, []))
            # Only absent map squares are unknown; known empty terrain stays void.
            for rx in range(x1 // 64, (x2 - 1) // 64 + 1):
                if y % 64 == 0 and ((rx << 8) | (y // 64 - 1)) not in loaded:
                    unknown = True
                if y % 64 == 63 and ((rx << 8) | (y // 64 + 1)) not in loaded:
                    unknown = True
            if x1 % 64 == 0 and (((x1 // 64 - 1) << 8) | (y // 64)) not in loaded:
                unknown = True
            if x2 % 64 == 0 and (((x2 // 64) << 8) | (y // 64)) not in loaded:
                unknown = True
        mask = rectangles(shape)
        x1 = min(r[0] for r in mask)
        y1 = min(shape)
        x2 = max(r[0] + r[2] for r in mask)
        y2 = max(shape) + 1
        digest = hashlib.sha256(json.dumps(mask, separators=(',', ':')).encode()).hexdigest()[:10]
        classification = 'main-world' if surface_count == count else 'inset' if surface_count == 0 else 'mixed'
        regions.append(dict(id=f'p{plane}-{x1}-{y1}-{digest}', plane=plane,
                            bounds=[x1, y1, x2, y2], tileCount=count, mask=mask,
                            classification=classification, surfaceTiles=surface_count,
                            touchesUnknown=unknown, examples=[]))
    regions.sort(key=lambda r: (-r['tileCount'], r['id']))
    checks = []
    for example in examples:
        x, y, p = example['route'][example['detachedEndpoint']]
        matches = [r for r in regions if r['plane'] == p and any(
            rx <= x < rx + w and ry <= y < ry + h for rx, ry, w, h in r['mask'])]
        for r in matches:
            r['examples'].append(example['areaName'])
        checks.append(dict(area=example['areaName'], point=[x, y, p],
                           regionIds=[r['id'] for r in matches],
                           status='matched' if len(matches) == 1 and matches[0]['classification'] == 'inset' else 'review'))
    return dict(schemaVersion=1, mapIndexRevision=topology['mapIndexRevision'],
                connectivity=4, maskRule='visible terrain; full-tile overlays replace underlays; no gap closing',
                surfaceRule='identity sections of cache main world-map, across all planes',
                surfaceRects=topology['surfaceRects'],
                loadedMapSquareCount=len(loaded), regions=regions, checks=checks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('topology', type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/inset-metadata/regions.json'))
    parser.add_argument('--examples', type=Path, default=None)
    args = parser.parse_args()
    raw = args.topology.read_bytes()
    catalogue = discover(json.loads(raw), json.loads(args.examples.read_text()) if args.examples else [])
    catalogue['topologySha256'] = hashlib.sha256(raw).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(catalogue, separators=(',', ':')) + '\n')
    totals = {key: sum(r['classification'] == key for r in catalogue['regions'])
              for key in ('inset', 'main-world', 'mixed')}
    print(f"{len(catalogue['regions'])} components: {totals}; "
          f"{sum(c['status'] == 'matched' for c in catalogue['checks'])}/{len(catalogue['checks'])} examples matched -> {args.output}")


if __name__ == '__main__':
    main()
