# CC-CEDICT source snapshot

`cedict.txt.gz` is the unmodified CC-CEDICT v1 export published by MDBG, dated 2026-11-24T20:43:35Z, with 125,474 entries. Source URL: https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz

Copyright CC-CEDICT contributors; includes CEDICT, Copyright (C) 1997, 1998 Paul Andrew Denisowski. Licensed under Creative Commons Attribution-ShareAlike 4.0 International. The complete license and attribution are bundled in `app/src/main/assets/dictionary/` and visible in the app.

Run `python3 tools/build_dictionary.py` to rebuild the SQLite pack and manifest from this snapshot. The script rejects malformed lines, checks database integrity, and records hashes of both source and output. Definitions are preserved; SQLite conversion adds normalized search keys and indexes. The adapted database is distributed under CC BY-SA 4.0. This data license is separate from the application's source-code licensing.

For an update, replace the source deliberately, rebuild, review the manifest/header and entry count, then run OfflineDictionaryTest and OfflineDictionaryUiTest. Runtime database names include the database hash, so an app update installs the new snapshot independently of profile data. Old snapshots are not automatically removed yet. The `.pack` asset contains gzip-compressed SQLite; using `.gz` would cause Android's asset merger to transform the filename.

## Russian Wiktionary snapshot

`ruwiktionary-cmn.jsonl.gz` contains the reduced Mandarin extract from [Kaikki's Russian Wiktionary dataset](https://kaikki.org/ruwiktionary/), extracted on 2026-11-02 from the 2026-11-01 dump. Each record retains its original article title for author attribution. The app links each definition group to its source article and history. Wiktionary contributors' text is redistributed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/), with attribution and adaptation details in the Russian pack notice. The extract omits audio, examples, etymologies, and records explicitly referring to outside dictionaries such as BKRS.

Run `python3 tools/build_ruwiktionary.py` to build the versioned SQLite asset. The builder can recreate the reduced input from the pinned raw Kaikki Mandarin JSONL with `--extract /path/to/raw.jsonl` and verifies the raw SHA-256. It adds written forms and numbered pinyin from the separate CC-CEDICT snapshot when the match is unambiguous. Entries with several possible CC-CEDICT readings or no match retain their Russian definitions with a blank reading.
