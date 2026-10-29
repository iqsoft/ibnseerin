# Ibn Seerin Dream Dictionary — Android

Offline Android dictionary based on the supplied PDF.

## Features

- 4,300+ indexed dictionary entries in SQLite.
- Search entry names and interpretation text.
- Cross-references and related entries in parentheses are clickable.
- Multiple references separated by semicolons are independently clickable.
- Numbered/subscripted variants are resolved intelligently.
  - The source PDF extraction represents subscript 1 inconsistently as `1` or `l` and may insert a space; the app recognizes these forms only when a matching sibling variant exists.
  - A reference to a base name such as `Mercury` opens a chooser when both `Mercury₁` and `Mercury₂` exist.
  - A direct reference such as `Mercury2` or `Mercury₂` resolves to the second variant.
  - The app displays genuine numbered variants with Unicode subscripts (`₁`, `₂`) while leaving the underlying source database unchanged.
- Variant chooser shows a short description so similarly named entries can be distinguished.
- Original interpretation wording is preserved; clickable spans are added only to resolvable references.

## Build

Open this folder in Android Studio and build the app. The project intentionally does not require network access at runtime.


## Parenthesis and cross-reference repair (October 2026)

- Repaired 27 adjacent PDF-extraction splits where the end of one dictionary entry had been incorrectly stored as the next index row.
- Preserved extracted continuation terms as aliases where no separate canonical row was available.
- Added tolerant parenthesis balancing in the detail display and a depth-aware cross-reference parser, so malformed OCR punctuation does not disable valid links or leave visibly unclosed parentheses.
- The source PDF remains authoritative for any wording genuinely omitted during extraction; the display repair does not reconstruct missing prose.
