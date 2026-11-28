#!/usr/bin/env python3
"""Build a reproducible CC-CEDICT SQLite asset from the pinned upstream gzip."""
import gzip, hashlib, json, re, sqlite3, tempfile, unicodedata
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
source = ROOT / 'dictionary-source/cedict.txt.gz'
output = ROOT / 'app/src/main/assets/dictionary'

def key(value):
    value = unicodedata.normalize('NFD', value.lower().replace('u:', 'ü'))
    value = value.replace('u\u0308', 'v')
    return ''.join(c for c in value if c.isalpha() and not unicodedata.combining(c))

def build():
    text = gzip.decompress(source.read_bytes()).decode('utf-8')
    rows = []
    for line in text.splitlines():
        if not line or line.startswith('#'): continue
        match = re.fullmatch(r'(\S+) (\S+) \[(.*?)\] /(.*)/', line)
        if not match: raise ValueError('Invalid CC-CEDICT line: ' + line[:100])
        traditional, simplified, pinyin, gloss = match.groups()
        rows.append((traditional, simplified, pinyin, key(pinyin), gloss))
    assert len(rows) > 100000
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / 'cedict.db'
        db = sqlite3.connect(path)
        db.executescript('''CREATE TABLE entries(id INTEGER PRIMARY KEY, traditional TEXT NOT NULL, simplified TEXT NOT NULL, pinyin TEXT NOT NULL, pinyin_key TEXT NOT NULL, gloss TEXT NOT NULL);
        CREATE INDEX traditional_idx ON entries(traditional);
        CREATE INDEX simplified_idx ON entries(simplified);
        CREATE INDEX pinyin_idx ON entries(pinyin_key);
        CREATE TABLE definitions(token TEXT NOT NULL, entry_id INTEGER NOT NULL, PRIMARY KEY(token,entry_id)) WITHOUT ROWID;''')
        db.executemany('INSERT INTO entries(traditional,simplified,pinyin,pinyin_key,gloss) VALUES(?,?,?,?,?)', rows)
        db.executemany('INSERT INTO definitions(token,entry_id) VALUES(?,?)', ((token, i) for i, row in enumerate(rows, 1) for token in sorted(set(re.findall(r'[a-z]+', row[4].lower())))))
        db.commit(); db.execute('VACUUM')
        assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
        db.close()
        data = path.read_bytes()
    output.mkdir(parents=True, exist_ok=True)
    (output/'cedict.pack').write_bytes(gzip.compress(data, mtime=0))
    metadata = dict(schema=1, entries=len(rows), source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                    database_sha256=hashlib.sha256(data).hexdigest(), database_bytes=len(data),
                    source_header='\n'.join(line for line in text.splitlines() if line.startswith('#')))
    (output/'manifest.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2)+'\n')
    (output/'NOTICE.txt').write_text('CC-CEDICT — Chinese–English dictionary\nCopyright CC-CEDICT contributors; published by MDBG.\nIncludes CEDICT, Copyright (C) 1997, 1998 Paul Andrew Denisowski.\nSource: https://www.mdbg.net/chinese/dictionary?page=cc-cedict\nLicense: Creative Commons Attribution-ShareAlike 4.0 International\nhttps://creativecommons.org/licenses/by-sa/4.0/\nChanges: converted to SQLite, with normalized search keys and indexes; definitions unchanged.\nThe adapted dictionary database is distributed under CC BY-SA 4.0.\n\n'+metadata['source_header']+'\n')
    print(f'{len(rows)} entries; database {len(data)} bytes; compressed {(output/"cedict.pack").stat().st_size} bytes')
if __name__ == '__main__': build()
