#!/usr/bin/env python3
"""Prepare a reviewable naming-source snapshot; never change authored annotations."""
import argparse
import csv
from datetime import date
import hashlib
import io
import json
from pathlib import Path
import urllib.request

SHEET = 'https://docs.google.com/spreadsheets/d/1859HuKw5dXqmfakFd6e6kQ_PEXQA02namB4aNVQ0qpY'
CATEGORIES = {'place', 'landmark', 'dungeon', 'minigame', 'training'}


def record(name, point, kind, source, **extra):
    result = dict(name=name, point=point, kind=kind, source=source, **extra)
    result['id'] = 's-' + hashlib.sha256(json.dumps(result, sort_keys=True).encode()).hexdigest()[:16]
    return result


def mejrs_records(raw):
    records = []
    for line, row in enumerate(csv.reader(io.StringIO(raw.decode('utf-8'))), 1):
        if len(row) != 4 or not row[3].strip():
            raise ValueError(f'Mejrs row {line}: expected x,y,plane,name')
        try:
            point = list(map(int, row[:3]))
        except ValueError:
            if row[0] or row[1]:
                raise ValueError(f'Mejrs row {line}: malformed coordinates') from None
            point = None  # Four known named rows have no XY; retain, never spatially match.
        records.append(record(row[3], point, 'mejrs', SHEET + '/edit', row=line))
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gps-resources', required=True, type=Path)
    parser.add_argument('--gps-revision', required=True, help='Actual Git revision of the input checkout')
    parser.add_argument('--mejrs-csv', type=Path, help='Use an existing CSV instead of downloading')
    parser.add_argument('--existing', type=Path, help='Retain previously researched wiki records')
    parser.add_argument('--wiki-json', type=Path, action='append', default=[], help='Add wiki_locations.py page output')
    parser.add_argument('--date', default=date.today().isoformat())
    parser.add_argument('--output', required=True, type=Path, help='Write a draft for review before replacing sources.json')
    args = parser.parse_args()
    if args.mejrs_csv:
        raw = args.mejrs_csv.read_bytes()
    else:
        request = urllib.request.Request(SHEET + '/gviz/tq?tqx=out:csv', headers={'User-Agent': 'OSRS inset label research'})
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
    records = mejrs_records(raw)
    manifests = [dict(name='Mejrs map labels', retrieved=args.date,
                      sha256=hashlib.sha256(raw).hexdigest(), url=SHEET + '/gviz/tq?tqx=out:csv',
                      reuseTerms='Not specified by the public sheet; attribution retained. Confirm terms before external redistribution.')]
    for filename in ('destinations.tsv', 'destinations-curated.tsv', 'destinations-wiki.tsv'):
        raw = (args.gps_resources / filename).read_bytes()
        for line, row in enumerate(csv.DictReader(io.StringIO(raw.decode()), delimiter='\t'), 2):
            if row['category'] not in CATEGORIES:
                continue
            records.append(record(row['name'], [int(row[k]) for k in ('x', 'y', 'plane')], 'gps',
                                  f'runelite-gps-plugin@{args.gps_revision}:src/main/resources/{filename}:{line}',
                                  category=row['category'], upstreamSource=row.get('verified_source', '')))
        manifests.append(dict(name=f'GPS {filename}', revision=args.gps_revision,
                              sha256=hashlib.sha256(raw).hexdigest(), path=f'src/main/resources/{filename}'))
    if args.existing:
        records.extend(r for r in json.loads(args.existing.read_text())['records'] if r['kind'] == 'wiki')
    for path in args.wiki_json:
        for page in json.loads(path.read_text()):
            for group in page.get('groups', []):
                records.append(record(page['page'], group['point'], 'wiki', group['source'],
                                      url='https://oldschool.runescape.wiki/w/' + page['page'].replace(' ', '_')
                                      + '?oldid=' + str(page['revid'])))
    records = list({r['id']: r for r in records}.values())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dict(schemaVersion=1, manifests=manifests, records=records),
                                     ensure_ascii=False, indent=2) + '\n')
    print(f'{len(records)} source rows -> {args.output}; review the diff before adopting')


if __name__ == '__main__':
    main()
