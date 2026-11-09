# Offline stroke viewer assets

- Engine: [Hanzi Writer](https://github.com/chanind/hanzi-writer), npm `hanzi-writer@3.7.3` (MIT).
- Data: [Hanzi Writer Data](https://github.com/chanind/hanzi-writer-data), npm `hanzi-writer-data@2.0.1`, derived from Make Me a Hanzi and Arphic fonts (Arphic Public License).
- 9,574 character JSON files are bundled. Their contents are unmodified; filenames are Unicode code points in hexadecimal so URLs are unambiguous.
- `MIT.txt` and `ARPHICPL.TXT` preserve the upstream license texts. The same texts are available offline through the viewer's “Источники и лицензии” section in `licenses.json`.
- `viewer.js`, `viewer.css`, and `index.html` implement Chinlearn's interface. They do not fetch content from HanziStroke or send vocabulary to any service.

Run `python tools/vendor_strokes.py [tarball-directory]` from the repository to reproduce the third-party assets. The script pins versions and verifies SHA-512 archive integrity before reading package members. It does not execute package scripts. It leaves the viewer files intact.
