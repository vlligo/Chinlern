# Dictionary and text learning roadmap

Remaining work from the direction agreed on 2026-11-24.

## Phase 6 · Reading exercises

- Add reading comprehension activities using the existing documents, annotations, and word lookup.
- Add manual reading bookmarks.

## Later work

- **Text portability.** Add dedicated text import and export, and decide which file formats to support.
- **Reading and recall.** Explore automatic sentence translation, automatic grading, and speech assessment.
- **Dictionary coverage.** Evaluate additional packs for coverage, extraction quality, size, and licensing. Candidates include Chinese entries from English Wiktionary via Kaikki. Verify redistribution permission before considering BKRS. Decide whether to offer online lookup and follow the provider’s caching terms if one is selected.
- **Storage.** Decide the schema and migration versioning for future lexical and sense normalization. Define reference behavior for cross-pack moves and for deleting and recreating cards before new annotations rely on those semantics.

## Pending verification

- Verify example-sentence selection on the personal phone. Android rejected injected touch events during the previous attempts.
- Run the shared-words UI checks on a disposable emulator. Inline search typing, backspace, results, and clearing passed on the personal phone.

## Design constraints

- Preserve existing vocabulary, exercises, scheduling, shared progress, profiles, packs, backups, and sync during incremental changes. Keep migrations restart-safe, retain a recovery snapshot and legacy ID mappings, and verify backup round trips and compatibility with older sync clients.
- Keep dictionary data separate from personal cards and progress. Show personal meanings first and retain source and language attribution for dictionary definitions. Preserve user wording and accepted answers across dictionary updates. Keep stable card IDs independent of mutable meanings, and preserve distinctions between readings and senses when matching cards.
- Preserve original text and bind annotations and exercise targets to the source revision and quoted span. Respect Unicode boundaries and verify a span before restoring or relocating it. Keep reading and lookup free of progress changes, and keep assisted recall distinguishable from unaided recall.
