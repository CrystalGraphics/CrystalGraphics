# Text and Fonts

CrystalGraphics' text system: fonts with per-character fallback and the installed fonts behind them,
HarfBuzz shaping with BiDi and UAX #14 line breaking, styled text and markup, and a batched, instanced
renderer drawing bitmap and distance-field glyphs from two shared atlases — with outlines, shadows and
text gamma.

| Read | For |
|---|---|
| [`api-guide.md`](api-guide.md) | **Using it**: fonts, layouts, the draw request, world text |
| [`architecture.md`](architecture.md) | Which package owns what, and the boundaries between them |
| [`pipeline-map-and-glossary.md`](pipeline-map-and-glossary.md) | What happens to a string on its way to pixels, and the terms used for it |

Each package under `core/src/main/java/com/crystalgraphics/{api,text}` also has an `AGENTS.md` with its
implementation detail.
