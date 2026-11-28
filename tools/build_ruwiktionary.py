#!/usr/bin/env python3
"""Build the offline Mandarin–Russian Wiktionary pack from a pinned Kaikki extract."""
import argparse
import gzip
import hashlib
import json
import re
import sqlite3
import tempfile
from collections import defaultdict
from pathlib import Path

from build_dictionary import key

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "dictionary-source/ruwiktionary-cmn.jsonl.gz"
CEDICT = ROOT / "dictionary-source/cedict.txt.gz"
OUTPUT = ROOT / "app/src/main/assets/dictionary/ruwiktionary"
DUMP_DATE = "2026-11-01"
KAIKKI_DATE = "2026-11-02"
RAW_HASH = "63fa48a5e383cfbb7c99708aae89c3042bc9687938b9cb0265af9c6d3d1c189e"
KAIKKI_URL = (
    "https://kaikki.org/ruwiktionary/"
    "%D0%9A%D0%B8%D1%82%D0%B0%D0%B9%D1%81%D0%BA%D0%B8%D0%B9%20%28%D0%93%D1%83%D0%B0%D0%BD%D1%8C%D1%85%D1%83%D0%B0%29/"
)
RUSSIAN = re.compile(r"[а-яё]+", re.IGNORECASE)
EXTERNAL = re.compile(r"БКРС|BKRS|Больш[а-я]+ китайско.русск|Ошанин", re.IGNORECASE)
HAN = re.compile(r"[㐀-鿿豈-﫿\U00020000-\U000323AF]")
NUMBERED = re.compile(r"(?:[A-Za-züÜvV:]+[1-5](?: |$))+")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def json_text(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def extract(raw_path):
    raw = raw_path.read_bytes()
    if sha(raw) != RAW_HASH:
        raise ValueError("Kaikki source does not match the pinned snapshot")
    rows = []
    for line in raw.splitlines():
        data = json.loads(line)
        word = data["word"]
        if data["lang_code"] != "cmn" or not HAN.search(word) or len(word) > 120:
            continue
        # This source is Wiktionary. Explicit excerpts or references to other dictionaries
        # whose redistribution terms have not been verified do not enter the shipped pack.
        if EXTERNAL.search(json_text(data)):
            continue
        glosses = list(dict.fromkeys(
            " ".join(gloss.split())
            for sense in data.get("senses", ())
            if "no-gloss" not in sense.get("tags", ())
            for gloss in sense.get("glosses", ())
            if RUSSIAN.search(gloss)
        ))
        if not glosses:
            continue
        forms = {}
        for feature, tag in (("traditional", "Traditional-Chinese"), ("simplified", "Simplified-Chinese")):
            variants = {form["form"] for form in data.get("forms", ()) if tag in form.get("tags", ())}
            if len(variants) == 1:
                forms[feature] = variants.pop()
        rows.append(dict(word=word, glosses=glosses, **forms))
    SOURCE.write_bytes(gzip.compress("".join(json_text(row) + "\n" for row in rows).encode(), mtime=0))


def cedict_forms():
    forms = defaultdict(set)
    readings = defaultdict(set)
    with gzip.open(CEDICT, "rt", encoding="utf-8") as source:
        for line in source:
            if not line.strip() or line.startswith("#"):
                continue
            traditional, simplified, pinyin = re.match(r"(\S+) (\S+) \[(.*?)\]", line).groups()
            pair = traditional, simplified
            for form in pair:
                forms[form].add(pair)
            readings[pair].add(pinyin.lower().replace("u:", "ü").replace("v", "ü"))
    return forms, readings


def build():
    forms, readings = cedict_forms()
    records = {}
    with gzip.open(SOURCE, "rt", encoding="utf-8") as source:
        for row in map(json.loads, source):
            word = row["word"]
            if "traditional" in row or "simplified" in row:
                pair = row.get("traditional", word), row.get("simplified", word)
            else:
                pairs = forms[word]
                pair = next(iter(pairs)) if len(pairs) == 1 else (word, word)
            choices = readings[pair]
            pinyin = next(iter(choices)) if len(choices) == 1 else ""
            if not NUMBERED.fullmatch(pinyin):
                pinyin = ""
            glosses = records.setdefault((*pair, pinyin, word), {})
            glosses.update(dict.fromkeys(row["glosses"]))
    rows = [(t, s, p, key(p), json_text(list(glosses)), article)
            for (t, s, p, article), glosses in sorted(records.items())]
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "dictionary.db"
        with sqlite3.connect(path) as db:
            db.executescript("""
                CREATE TABLE entries(id INTEGER PRIMARY KEY, traditional TEXT NOT NULL, simplified TEXT NOT NULL,
                    pinyin TEXT NOT NULL, pinyin_key TEXT NOT NULL, gloss TEXT NOT NULL, article TEXT NOT NULL);
                CREATE INDEX traditional_idx ON entries(traditional);
                CREATE INDEX simplified_idx ON entries(simplified);
                CREATE INDEX pinyin_idx ON entries(pinyin_key);
                CREATE TABLE definitions(token TEXT NOT NULL, entry_id INTEGER NOT NULL,
                    PRIMARY KEY(token, entry_id)) WITHOUT ROWID;
            """)
            db.executemany("INSERT INTO entries(traditional,simplified,pinyin,pinyin_key,gloss,article) VALUES(?,?,?,?,?,?)", rows)
            db.executemany("INSERT INTO definitions(token, entry_id) VALUES(?,?)", (
                (token, index) for index, row in enumerate(rows, 1)
                for token in sorted(set(RUSSIAN.findall(row[4].lower().replace("ё", "е"))))
            ))
            db.commit()
            db.execute("VACUUM")
            assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        data = path.read_bytes()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    pack = gzip.compress(data, mtime=0)
    (OUTPUT / "dictionary.pack").write_bytes(pack)
    metadata = dict(schema=2, source_dump=DUMP_DATE, extracted=KAIKKI_DATE, entries=len(rows),
                    articles=len({row[5] for row in rows}), readings=sum(bool(row[2]) for row in rows),
                    source_sha256=sha(SOURCE.read_bytes()), upstream_sha256=RAW_HASH,
                    database_sha256=sha(data), database_bytes=len(data))
    (OUTPUT / "manifest.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    (OUTPUT / "NOTICE.txt").write_text(
        "Русский Викисловарь · китайский (гуаньхуа) → русский\n"
        "Авторы · участники русского Викисловаря. Ссылки на статьи и историю авторов доступны в словаре.\n"
        "Данные извлечены Kaikki.org / Wiktextract из снимка Викисловаря от " + DUMP_DATE + ".\n"
        + KAIKKI_URL + "index.html\n"
        "Изменения · выбраны русские определения гуаньхуа, нормализованы пробелы, добавлены индексы. "
        "При однозначном совпадении добавлены формы и пиньинь CC-CEDICT (см. его уведомление).\n"
        "Распространяется по лицензии Creative Commons Attribution-ShareAlike 4.0 International.\n"
        "https://creativecommons.org/licenses/by-sa/4.0/\n",
        encoding="utf-8",
    )
    print(f"{len(rows)} entries, {metadata['articles']} source articles, {metadata['readings']} readings, {len(pack)} compressed bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--extract", type=Path, help="recreate the pinned extract from the original Kaikki JSONL")
    if raw := parser.parse_args().extract:
        extract(raw)
    build()
