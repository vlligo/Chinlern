"""Build the offline generator. Python fontTools is required for this build only."""
import base64
import json
from pathlib import Path

from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
assets = ROOT / 'app/src/main/assets/strokes'
data = {
    chr(int(p.stem, 16)): {'strokes': json.loads(p.read_text())['strokes']}
    for p in sorted((assets / 'data').glob('*.json'))
}
licenses = json.loads((assets / 'licenses.json').read_text())
licenses['DejaVu Sans — Bitstream Vera license'] = (HERE / 'DejaVuSans-LICENSE.txt').read_text()

font_path = HERE / 'DejaVuSans.ttf'
font_bytes = font_path.read_bytes()
with TTFont(font_path) as font:
    head = font['head']
    hhea = font['hhea']
    def points(value):
        return round(value * 1000 / head.unitsPerEm)

    font_info = {
        'chars': {
            chr(code): [font.getGlyphID(name), points(font['hmtx'][name][0])]
            for code, name in font.getBestCmap().items()
        },
        'bounds': [points(n) for n in (head.xMin, head.yMin, head.xMax, head.yMax)],
        'ascent': points(hhea.ascent),
        'descent': points(hhea.descent),
    }

def embedded(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).replace('<', '\\u003c')

output = HERE / 'hanzi-worksheets.html'
html = (HERE / 'template.html').read_text()
replacements = {
    '__DATA__': embedded(data),
    '__LICENSES__': embedded('\n\n'.join(f'{k}\n{v}' for k, v in licenses.items())),
    '__FONT_INFO__': embedded(font_info),
    '__FONT_BYTES__': base64.b64encode(font_bytes).decode('ascii'),
}
for key, value in replacements.items():
    html = html.replace(key, value)
output.write_text(html)
print(f'{output}: {output.stat().st_size / 1e6:.1f} MB; {len(data)} characters')
