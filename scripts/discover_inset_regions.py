#!/usr/bin/env python3
"""Label terrain spans with four-neighbour connectivity; retain exact clipping geometry.

Curated cuts (annotations.json `cuts`) keep a set of map squares or tile rectangles apart from the
terrain around them. Some areas fill their map squares edge to edge with filler terrain, such as the
lava around the Fire Altar, so neighbouring areas would otherwise join into one.
"""
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


def cut_domains(cuts):
    """plane -> [(x1, y1, x2, y2, cut index)]: the tile rectangles each cut keeps apart.

    A cut lists map `squares` ([x, y], 64 tiles each), tile `rects` ([x, y, width, height]), or both.
    """
    domains = defaultdict(list)
    for index, cut in enumerate(cuts):
        plane, squares, rects = cut.get('plane'), cut.get('squares', []), cut.get('rects', [])
        if (type(plane) is not int or not 0 <= plane < 4 or not (squares or rects)
                or not isinstance(cut.get('reason'), str) or not cut['reason'].strip()):
            raise ValueError(f'A cut needs a plane, squares or rects, and a reason: {cut}')
        boxes = []
        for square in squares:
            if (not isinstance(square, list) or len(square) != 2 or any(type(n) is not int for n in square)
                    or not all(0 <= n < 256 for n in square)):
                raise ValueError(f'Invalid cut square: {square}')
            boxes.append((square[0] * 64, square[1] * 64, square[0] * 64 + 64, square[1] * 64 + 64))
        for rect in rects:
            if (not isinstance(rect, list) or len(rect) != 4 or any(type(n) is not int for n in rect)
                    or rect[2] <= 0 or rect[3] <= 0 or not (0 <= rect[0] and rect[0] + rect[2] <= 16384
                                                           and 0 <= rect[1] and rect[1] + rect[3] <= 16384)):
                raise ValueError(f'Invalid cut rect: {rect}')
            boxes.append((rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3]))
        for box in boxes:
            if any(box[0] < b[2] and b[0] < box[2] and box[1] < b[3] and b[1] < box[3] for b in domains[plane]):
                raise ValueError(f'Cuts overlap on plane {plane}: {cut}')
            domains[plane].append((*box, index))
    return domains


def domain_at(boxes, x, y):
    return next((b[4] for b in boxes if b[0] <= x < b[2] and b[1] <= y < b[3]), -1)


def split_at_cuts(plane, y, intervals, domains):
    """Split a row's spans wherever they cross from one cut's tiles to another's (or to none)."""
    boxes = [b for b in domains.get(plane, ()) if b[1] <= y < b[3]]
    edges = sorted({edge for b in boxes for edge in (b[0], b[2])})
    result = []
    for x1, x2 in intervals:
        start = x1
        for edge in edges:
            if start < edge < x2 and domain_at(boxes, edge - 1, y) != domain_at(boxes, edge, y):
                result.append([start, edge])
                start = edge
        result.append([start, x2])
    return result


def discover(topology, examples=(), cuts=()):
    if topology.get('schemaVersion') != 1:
        raise ValueError('Unsupported topology schema')
    domains = cut_domains(cuts)
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
        for x1, x2 in split_at_cuts(key[0], key[1], merge(intervals), domains):
            domain = domain_at(domains.get(key[0], ()), x1, key[1])
            index = len(runs)
            parents.append(index)
            runs.append((key[0], key[1], x1, x2))
            current.append((x1, x2, index, domain))
            while left < len(previous) and previous[left][1] <= x1:
                left += 1
            j = left
            while j < len(previous) and previous[j][0] < x2:
                if previous[j][3] == domain:
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
    # A cut part with no terrain no longer separates anything: the terrain moved.
    def has_terrain(plane, x1, y1, x2, y2):
        return any(a < x2 and x1 < b for y in range(y1, y2) for a, b in rows.get((plane, y), ()))
    cut_issues = []
    for index, cut in enumerate(cuts):
        squares = [s for s in cut.get('squares', []) if not has_terrain(cut['plane'], s[0] * 64, s[1] * 64, s[0] * 64 + 64, s[1] * 64 + 64)]
        rects = [r for r in cut.get('rects', []) if not has_terrain(cut['plane'], r[0], r[1], r[0] + r[2], r[1] + r[3])]
        if squares or rects:
            cut_issues.append(dict(cut=index, plane=cut['plane'], reason='cut-without-terrain',
                                   **({'squares': squares} if squares else {}), **({'rects': rects} if rects else {})))
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
                surfaceRects=topology['surfaceRects'], cuts=list(cuts), cutIssues=cut_issues,
                loadedMapSquareCount=len(loaded), regions=regions, checks=checks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('topology', type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/inset-metadata/regions.json'))
    parser.add_argument('--examples', type=Path, default=None)
    parser.add_argument('--annotations', type=Path, default=Path('data/inset-labels/annotations.json'))
    args = parser.parse_args()
    raw = args.topology.read_bytes()
    cuts = json.loads(args.annotations.read_text()).get('cuts', []) if args.annotations.is_file() else []
    catalogue = discover(json.loads(raw), json.loads(args.examples.read_text()) if args.examples else [], cuts)
    catalogue['topologySha256'] = hashlib.sha256(raw).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(catalogue, separators=(',', ':')) + '\n')
    totals = {key: sum(r['classification'] == key for r in catalogue['regions'])
              for key in ('inset', 'main-world', 'mixed')}
    print(f"{len(catalogue['regions'])} components: {totals}; "
          f"{sum(c['status'] == 'matched' for c in catalogue['checks'])}/{len(catalogue['checks'])} examples matched -> {args.output}")


if __name__ == '__main__':
    main()
