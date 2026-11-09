#!/usr/bin/env python3
"""Reproduce the offline assets from pinned npm tarballs (no npm install/scripts).

Pass a directory containing the two .tgz files, or omit it to download them.
Archive integrity is verified before individual files are read. Stroke JSON bytes
are preserved; filenames use hexadecimal Unicode code points for local URLs.
"""
import base64
import hashlib
import io
import json
from pathlib import Path
import sys
import tarfile
import urllib.request

PACKAGES = {
    'hanzi-writer': ('3.7.3', 'fdOFrb1cXWL/pV/oplJkcdziCvjJzhhf+qoIBm5IpVGxPBZEu4eLB6ZG5RJDbKXyNbNlyx8oIpa3XrcUBcSXpg=='),
    'hanzi-writer-data': ('2.0.1', 'nbQwM+MaryGoq7pBMIZLCd3lFq03nXuJuwku1+6UbjL58uU+9OULVcMkoNvNuJSoIV7f1bbPRfD4D/LQa5S7qg=='),
}
DEST = Path(__file__).resolve().parents[1] / 'app/src/main/assets/strokes'


def package(name):
    version, digest = PACKAGES[name]
    filename = f'{name}-{version}.tgz'
    if len(sys.argv) > 1:
        data = (Path(sys.argv[1]) / filename).read_bytes()
    else:
        data = urllib.request.urlopen(f'https://registry.npmjs.org/{name}/-/{filename}', timeout=60).read()
    if base64.b64encode(hashlib.sha512(data).digest()).decode() != digest:
        raise ValueError(f'Integrity mismatch: {filename}')
    return tarfile.open(fileobj=io.BytesIO(data), mode='r:gz')


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    with package('hanzi-writer') as engine, package('hanzi-writer-data') as data:
        (DEST / 'hanzi-writer.min.js').write_bytes(engine.extractfile('package/dist/hanzi-writer.min.js').read())
        licenses = {
            'Hanzi Writer — MIT': engine.extractfile('package/LICENSE').read().decode(),
            'Hanzi Writer Data — Arphic Public License': data.extractfile('package/ARPHICPL.TXT').read().decode(),
        }
        (DEST / 'licenses.json').write_text(json.dumps(licenses, ensure_ascii=False, indent=2) + '\n')
        (DEST / 'MIT.txt').write_text(licenses['Hanzi Writer — MIT'])
        (DEST / 'ARPHICPL.TXT').write_bytes(data.extractfile('package/ARPHICPL.TXT').read())
        target = DEST / 'data'
        target.mkdir(exist_ok=True)
        expected = set()
        for member in data.getmembers():
            name = member.name.removeprefix('package/')
            if not member.isfile() or not name.endswith('.json') or len(name[:-5]) != 1:
                continue
            raw = data.extractfile(member).read()
            parsed = json.loads(raw)
            assert parsed['strokes'] and len(parsed['strokes']) == len(parsed['medians']), name
            filename = f'{ord(name[:-5]):x}.json'
            (target / filename).write_bytes(raw)
            expected.add(filename)
        for stale in target.glob('*.json'):
            if stale.name not in expected:
                stale.unlink()
        print(f'Vendored Hanzi Writer 3.7.3 and {len(expected)} characters from Hanzi Writer Data 2.0.1.')


if __name__ == '__main__':
    main()
