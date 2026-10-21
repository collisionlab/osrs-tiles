#!/usr/bin/env python3
"""Compile reviewed area annotations; generate evidence for the next naming pass."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path

from anchor_inset_regions import RegionIndex


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'data/inset-labels'


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n'


def distance_squared(region, point):
    """Distance to actual tile centres, never to the bounding box or another plane."""
    x, y, plane = point
    if plane != region['plane']:
        return None
    return min(max(rx - x, 0, x - rx - w + 1) ** 2
               + max(ry - y, 0, y - ry - h + 1) ** 2
               for rx, ry, w, h in region['mask'])


def fallback(region):
    x, y = region['bounds'][:2]
    return f"Unidentified terrain at {x}, {y}"


def compile_labels(catalogue, anchors, sources, annotations, catalogue_hash):
    if annotations.get('schemaVersion') != 1 or sources.get('schemaVersion') != 1:
        raise ValueError('Unsupported naming data schema')
    if anchors.get('schemaVersion') != 1 or anchors.get('catalogueSha256') != catalogue_hash:
        raise ValueError('Entrance graph does not match catalogue')
    index = RegionIndex(catalogue)
    evidence = {r['id']: r for r in sources['records']}
    if len(evidence) != len(sources['records']):
        raise ValueError('Duplicate source IDs')
    for ref, source in evidence.items():
        point = source.get('point')
        if (not isinstance(ref, str) or not isinstance(source.get('name'), str)
                or not source['name'].strip() or not isinstance(source.get('source'), str)
                or (point is not None and (len(point) != 3 or any(type(n) is not int for n in point)
                    or not all(0 <= n < 16384 for n in point[:2]) or not 0 <= point[2] < 4))):
            raise ValueError(f'Invalid source record: {ref}')
    assigned = defaultdict(list)
    issues, area_ids = [], set()
    for area in annotations['areas']:
        key = area['key']
        if not isinstance(key, str) or not key or key in area_ids:
            raise ValueError(f'Duplicate or invalid area key: {key}')
        area_ids.add(key)
        if not isinstance(area['name'], str) or not area['name'].strip():
            raise ValueError(f'{key}: empty name')
        if area['status'] not in {'named', 'descriptive'} or not area.get('reason'):
            raise ValueError(f'{key}: missing decision status/reason')
        if not area.get('components'):
            raise ValueError(f'{key}: no component associations')
        seen = set()
        for association in area['components']:
            rid = association['componentId']
            if rid in seen:
                raise ValueError(f'{key}: duplicate component association {rid}')
            seen.add(rid)
            point = association['seed']
            if (len(point) != 3 or any(type(n) is not int for n in point)
                    or not all(0 <= n < 16384 for n in point[:2]) or not 0 <= point[2] < 4):
                raise ValueError(f'{key}: invalid interior seed')
            endpoint = index.endpoint(point)
            if endpoint['regionId'] != rid or endpoint['kind'] != 'inset':
                issues.append(dict(area=key, componentId=rid, seed=point,
                                   nowResolvesTo=endpoint['regionId'], reason='geometry-or-seed-changed'))
                continue
            anchor = association.get('anchor')
            if anchor is not None and (not isinstance(anchor.get('reason'), str) or not anchor['reason'].strip()
                                       or not isinstance(anchor.get('point'), list) or len(anchor['point']) != 3
                                       or any(type(n) is not int for n in anchor['point'])):
                raise ValueError(f'{key}/{rid}: an anchor needs a point and a reason')
            refs = association.get('evidence', [])
            if not refs or not association.get('reason'):
                raise ValueError(f'{key}/{rid}: missing association evidence/reason')
            missing_evidence = False
            for ref in refs:
                if ref not in evidence and ref not in anchors['connections']:
                    issues.append(dict(area=key, componentId=rid, evidence=ref, reason='evidence-removed'))
                    missing_evidence = True
            if missing_evidence:
                continue
            assigned[rid].append(dict(areaKey=key, name=area['name'], status=area['status'],
                                      aliases=area.get('aliases', []), evidence=refs,
                                      reason=association['reason'], seed=point))
    labels = {}
    for rid, region in sorted(index.regions.items()):
        if region['classification'] != 'inset':
            continue
        matches = assigned.get(rid, [])
        labels[rid] = dict(name=' / '.join(dict.fromkeys(m['name'] for m in matches)) or fallback(region),
                           status='named' if any(m['status'] == 'named' for m in matches)
                           else 'descriptive' if matches else 'unresolved',
                           plane=region['plane'], areas=matches)
    # Every inset region a player can reach needs a name, which the planner's area search lists, and an
    # anchor, which places it beside the main map.
    required = set(anchors['reachableRegionIds']) | set(annotations.get('routeRequiredRegionIds', []))
    issues.extend(anchors.get('curatedIssues', []))
    issues.extend(catalogue.get('cutIssues', []))
    # One inset shows one area: two areas on one component is terrain that needs a cut (annotations.json `cuts`).
    for rid, label in labels.items():
        keys = list(dict.fromkeys(m['areaKey'] for m in label['areas']))
        if len(keys) > 1:
            issues.append(dict(componentId=rid, areas=keys, reason='component-has-several-areas'))
    for rid in sorted(required):
        if rid not in labels or labels[rid]['status'] == 'unresolved':
            issues.append(dict(componentId=rid, reason='required-component-unnamed'))
        if not anchors['regions'].get(rid, {}).get('anchors'):
            issues.append(dict(componentId=rid, reason='required-component-unanchored'))
    report = dict(schemaVersion=1, catalogueSha256=catalogue_hash,
                  totals=dict(Counter(r['status'] for r in labels.values())),
                  required=len(required), requiredNamed=sum(labels.get(r, {}).get('status') == 'named' for r in required),
                  requiredDescriptive=sum(labels.get(r, {}).get('status') == 'descriptive' for r in required),
                  requiredAnchored=sum(bool(anchors['regions'].get(r, {}).get('anchors')) for r in required),
                  issues=issues)
    used_evidence = {ref for matches in assigned.values() for match in matches for ref in match['evidence']}
    return dict(schemaVersion=1, catalogueSha256=catalogue_hash,
                annotationsSha256=hashlib.sha256(encoded(annotations).encode()).hexdigest(),
                sourcesSha256=hashlib.sha256(encoded(sources).encode()).hexdigest(),
                evidence={ref: evidence[ref] for ref in sorted(used_evidence) if ref in evidence},
                regions=labels), report


def candidate_worklist(catalogue, anchors, sources, labels, radius=32):
    """Suggestions are never promoted to authored decisions by this script."""
    index = RegionIndex(catalogue)
    by_region = defaultdict(list)
    for source in sources['records']:
        if not source.get('point'):
            continue
        endpoint = index.endpoint(source['point'])
        if endpoint['regionId']:
            by_region[endpoint['regionId']].append(source['id'])
    result = []
    for rid, region in sorted(index.regions.items()):
        if region['classification'] != 'inset':
            continue
        candidates = []
        x1, y1, x2, y2 = region['bounds']
        for source in sources['records']:
            point = source.get('point')
            if (not point or point[2] != region['plane']
                    or not x1 - radius <= point[0] < x2 + radius
                    or not y1 - radius <= point[1] < y2 + radius):
                continue
            distance = distance_squared(region, point)
            if distance <= radius ** 2:
                candidates.append(dict(sourceId=source['id'], name=source['name'],
                                       distanceSquared=distance,
                                       pinComponentId=index.endpoint(point)['regionId']))
        record = anchors['regions'].get(rid, {})
        # Physical neighbours are evidence suggestions, not name propagation.
        neighbours = []
        for cid in record.get('connectionIds', []):
            connection = anchors['connections'][cid]
            if connection['policy'] != 'local':
                continue
            for endpoint in (connection['source'], connection['target']):
                if endpoint and endpoint['regionId'] != rid:
                    neighbours.append(dict(connectionId=cid, point=endpoint['point'],
                                           regionId=endpoint['regionId'],
                                           sourceIds=by_region.get(endpoint['regionId'], [])))
        result.append(dict(componentId=rid, plane=region['plane'], bounds=region['bounds'],
                           tileCount=region['tileCount'], decision=labels['regions'].get(rid),
                           candidates=sorted(candidates, key=lambda c: (c['distanceSquared'], c['sourceId'])),
                           physicalNeighbours=neighbours,
                           surfaceAnchors=record.get('anchors', [])[:3]))
    return dict(schemaVersion=1, catalogueSha256=labels['catalogueSha256'], regions=result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalogue', type=Path, default=ROOT / 'build/inset-metadata/regions.json')
    parser.add_argument('--anchors', type=Path, default=ROOT / 'build/inset-metadata/anchors.json')
    parser.add_argument('--sources', type=Path, default=DATA / 'sources.json')
    parser.add_argument('--annotations', type=Path, default=DATA / 'annotations.json')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/inset-metadata/labels.json')
    parser.add_argument('--report', type=Path, default=ROOT / 'build/inset-metadata/coverage.json')
    parser.add_argument('--worklist', type=Path, help='Also generate candidate evidence for all inset components')
    parser.add_argument('--allow-unresolved', action='store_true', help='Publish safe fallback labels and a review report for uncertain associations')
    parser.add_argument('--check', action='store_true', help='Check committed outputs without rewriting them')
    args = parser.parse_args()
    raw = args.catalogue.read_bytes()
    catalogue = json.loads(raw)
    anchors, sources, annotations = (json.loads(p.read_text()) for p in (args.anchors, args.sources, args.annotations))
    labels, report = compile_labels(catalogue, anchors, sources, annotations, hashlib.sha256(raw).hexdigest())
    # Emit review evidence on drift, but leave the previous runtime lookup intact.
    if args.worklist:
        args.worklist.parent.mkdir(parents=True, exist_ok=True)
        args.worklist.write_text(encoded(candidate_worklist(catalogue, anchors, sources, labels)))
    if report['issues'] and not args.allow_unresolved:
        if not args.check:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(encoded(report))
        print(encoded(report))
        raise SystemExit('Naming decisions need review; runtime lookup was not overwritten')
    for path, content in ((args.output, labels), (args.report, report)):
        expected = encoded(content)
        if args.check:
            if not path.exists() or path.read_text() != expected:
                raise SystemExit(f'Stale generated naming asset: {path}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(expected)
    print(f"{report['requiredNamed']} named + {report['requiredDescriptive']} descriptive, "
          f"{report['requiredAnchored']} anchored / {report['required']} required components; all insets: {report['totals']}")


if __name__ == '__main__':
    main()
