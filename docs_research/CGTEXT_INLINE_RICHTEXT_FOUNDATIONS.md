# Inline rich-text formatting (bold/italic/underline/color spans)

**→ See `TEXT_ENGINE_REWRITE_MASTER_PLAN.md` for the implementation-ready,
dependency-ordered phase sequence (phases 6–11 cover this document). This
document remains the source of rationale/citations; work from the master
plan.**

## Status: investigated, not started

Design sketch for letting a single logical string carry mixed inline formatting — `<b>bold</b>`,
`<i>italic</i>`, inline color — through the existing shape → layout → cache → render pipeline,
without touching the atlas/cache layers at all. Grounded in the actual current code
(`CgFontFamily`, `CgTextLayoutEngine`, `CgTextLayoutBuilder`, `CgShapedRun`, `CgLineBreaker`,
`CgQuadRenderer`), not a from-scratch proposal.

## Scope

**In scope:** inline character-level formatting — weight (bold), posture (italic), underline,
color — applied to spans of an otherwise-normal text flow, authored via a small markup syntax.

**Explicitly out of scope, on purpose:** block-level constructs (`<h1>`–`<h6>` margins, `<p>`
spacing, `<li>` bullets/indentation). Those are box-layout concerns, not text-shaping concerns —
in this codebase that's CrystalGUI's `UIElement`/Taffy tree's job, matching how a real browser
separates Blink's inline layout from its block box tree. A markup→`UIElement`-tree translator for
block tags is a separate, later piece of work layered on top of whatever this document produces,
not part of it.

**Explicitly rejected:** a fully lenient, WHATWG-grade HTML5 parser tolerating arbitrary malformed
markup. Real HTML5 parsing is complex specifically *because* of its error-recovery rules for
arbitrary web content. This is a small, fixed, developer-authored tag set (closer to how Minecraft's
own `§`-formatting-code strings work than to arbitrary web HTML) — a small tolerant-but-not-infinitely-so
parser is the right scope, not a browser-grade one.

---

## 1. New public IR: `CgStyledText` / `CgStyleSpan`

Lives in `api/text`, alongside `CgTextLayout`/`CgShapedRun`/`CgTextConstraints` — it's the same
kind of public pipeline-boundary type they already are.

```java
// api/text/CgStyledText.java
public final class CgStyledText {
    private final String plainText;           // markup stripped
    private final List<CgStyleSpan> spans;     // sorted, non-overlapping, over plainText indices
}

// api/text/CgStyleSpan.java
public final class CgStyleSpan {
    private final int start, end;              // [start, end) into CgStyledText.plainText
    private final boolean bold, italic, underline;
    private final int argbColor;                // 0 = "inherit / no override"
}
```

Both are plain immutable DTOs (`@Value`-shaped, matching the project's Lombok conventions) —
no HarfBuzz/FreeType/GL types touch this layer. `CgStyledText` is the rich-text equivalent of the
raw `String` that `CgTextLayoutEngine.layout(String, ...)` takes today; a plain `String` remains a
valid trivial case (`CgStyledText.plain(text)` → no spans) so nothing already calling
`layout(String, ...)` needs to change.

## 2. Markup parsing — new `text/richtext/` package, kept isolated

A small package, matching the isolation `text/msdf`/`text/atlas` already model: converts markup
text into a `CgStyledText` and nothing else — no font/atlas/GL knowledge at all.

```java
// text/richtext/CgMarkupParser.java (interface — pluggable syntax)
public interface CgMarkupParser {
    CgStyledText parse(String markup);
}

// text/richtext/CgHtmlLikeMarkupParser.java — <b>/<strong>, <i>/<em>, <u>, <color=#RRGGBB>...</color>
// text/richtext/CgMcFormattingCodeParser.java — optional sibling: §l/§o/§n/§r-style codes
```

**Open decision, not resolved here:** which syntax is the primary one to ship. This is a
Minecraft-adjacent project — `§`/`&`-style formatting codes (`§lBold§r`) are the convention every
Minecraft player and mod author already knows, and are trivially unambiguous to parse (no nesting,
no closing-tag matching, single-character codes). HTML-like tags (`<b>...</b>`) are what the
original conversation's Javadoc-rendering comparison was about, and support real nesting
(`<b><i>bold italic</i></b>`) more naturally. **Recommendation: implement `CgMarkupParser` as an
interface from day one** (above) so both can exist side by side, and let the first real consumer
decide which default `CgTextLayoutBuilder`/CrystalGUI wires up — don't force the choice now.

Parser tolerance policy (deliberately lenient, matching the "IntelliJ renders loose Javadoc HTML"
behavior this design grew out of): an unclosed opening tag is implicitly closed at the end of the
string; an unmatched closing tag is a no-op, not an error. Strict-mode validation (throwing on
malformed input) can be a constructor flag on the HTML-like parser for callers that want it (e.g.
a build-time lint over authored UI strings), but should not be the default runtime behavior.

## 3. Style→font-face resolution: `CgFontFamilyGroup` (new type)

This is the one piece that genuinely doesn't exist yet and needs new API surface, not just wiring.
`CgFontFamily` today (`CgFontFamily.java`) is a flat list of `CgFontSource`s used purely for
**codepoint-coverage** fallback (`resolveSourceForCodePoint`/`resolveRuns`) — there is no concept
of "this family, but at a different weight/style." `CgFontKey` already carries a `CgFontStyle`
(`REGULAR`/`BOLD`/`ITALIC`/`BOLD_ITALIC`) field, so the *font-file* side of this is already solved
— what's missing is a grouping concept on top:

```java
// api/font/CgFontFamilyGroup.java
public final class CgFontFamilyGroup {
    private final Map<CgFontStyle, CgFontFamily> byStyle;  // not required to have all 4

    public CgFontFamily resolve(CgFontStyle requested) {
        CgFontFamily exact = byStyle.get(requested);
        if (exact != null) return exact;
        // v1: fall back to REGULAR, no synthetic bold/oblique synthesis.
        // (Real synthetic-style synthesis — skew shear for oblique, stroke-expand for
        // faux-bold — is its own follow-up, not required to ship inline styling at all.)
        return byStyle.get(CgFontStyle.REGULAR);
    }
}
```

This mirrors how browsers resolve `font-weight`/`font-style` against whatever faces are actually
registered for a `font-family`, with a documented fallback when the exact face is missing — v1 here
deliberately skips synthetic style synthesis (faux-bold stroke expansion, oblique shear) since it's
a rendering-quality nice-to-have, not a blocker for the pipeline plumbing this document is about.

## 4. Layout engine changes

### 4.1 New entry point, old one unaffected

`CgTextLayoutEngine`/`CgTextLayoutBuilder` gain a `layout(CgStyledText, CgFontFamilyGroup, maxWidth,
maxHeight)` overload alongside the existing `layout(String, CgFontFamily, ...)` — the existing
single-style path is untouched and remains the common case for plain HUD/label text.

### 4.2 Where the real change lives: intersecting BiDi runs with style spans

`CgTextLayoutEngine.splitAndShapeRuns` (`CgTextLayoutEngine.java:127-141`) currently walks each
BiDi-level run once and calls the abstract `collectShapedRuns(text, start, end, rtl, family, out)`
hook (`CgTextLayoutEngine.java:143`) directly on the whole run. For the rich-text path, before
calling that hook, further split each BiDi run at any style-span boundary that falls inside it — a
plain sorted-interval intersection between the BiDi run's `[start, end)` and `CgStyledText`'s
non-overlapping, already-sorted span list. For each resulting sub-range, resolve
`group.resolve(spanStyleAt(subRange))` to get the right `CgFontFamily`, then call the **existing**
`collectShapedRuns(text, subStart, subEnd, rtl, resolvedFamily, out)` hook unchanged.

**This is the key simplification: `collectShapedRuns`'s abstract signature doesn't need to change
at all.** The style resolution happens in the engine's splitting loop, one layer up — the hook still
just receives "shape this range against this one family," exactly as today. `CgTextLayoutBuilder`'s
existing override (`CgTextLayoutBuilder.java:75-93`, which calls `family.resolveRuns` for
coverage-based fallback within that sub-range) needs no changes either — coverage fallback still
happens *within* a style-homogeneous sub-range exactly as it does today; style selection and
coverage fallback are cleanly orthogonal once the splitting happens at the right layer.

### 4.3 `CgShapedRun` needs a color field

`CgShapedRun` (`CgShapedRun.java`) has no color today — confirmed by reading it in full. Needs an
additive field (`argbColor`, `0` = "no override, use `Draw.color(...)`'s default"), added via a new
constructor overload the same way `sourceText`/`sourceStart`/`sourceEnd` were added on top of the
"backward-compatible constructor without source-text context"
(`CgShapedRun.java:154-160`) — same pattern, not a new one.

### 4.4 `CgLineBreaker`/line-height: must move from one constant to per-line max

`CgTextLayoutEngine.layout()` currently computes `CgFontMetrics metrics = family.getLayoutMetrics()`
**once** for the whole call (`CgTextLayoutEngine.java:49`) and both `CgLineBreaker.breakLines` and
the final layout assembly use that single constant for every line's height
(`CgTextLayoutEngine.java:67,86,99`). Once a line can contain runs from *different* style-sibling
`CgFontFamily` instances (bold and regular faces commonly have slightly different metrics), a single
family-wide constant is wrong. This needs to become a per-line max over the metrics of whichever
`CgFontFamily` each placed run actually came from — `CgFontFamily.combineMetrics`
(`CgFontFamily.java:169-186`) already implements exactly this max-over-sources reduction, just at
the wrong granularity (per-family, computed once, not per-line, computed after line breaking); the
same reduction needs to run again per finished line in `CgLineBreaker`, not just once in the family
constructor.

### 4.5 Reshaping across the style-group

`RunReshaper`/`createRunReshaper` (`CgTextLayoutBuilder.java:103-116`) resolves the shaping `HBFont`
via `family.requireShapingFont(run.getFontKey())` against **one fixed family** captured in the
closure. Since a bold run's `CgFontKey` already correctly carries `CgFontStyle.BOLD`
(`CgFontKey.java:39`), reshaping "just works" for style-mixed text only if the reshaper can resolve
*any* style-sibling family's font by key, not just the one `family` it was built against. Fix: build
the reshaper against the `CgFontFamilyGroup` instead of a single `CgFontFamily`, resolving
`group.resolve(run.getFontKey().getStyle())` before calling `requireShapingFont`. Small, contained
change — the reshaping mechanism itself (HarfBuzz re-shape via `CgTextShaper`) needs nothing new.

## 5. Render-side color plumbing — mostly already there

**The GPU instance format already supports this for free.** `CgQuadRenderer.INSTANCE_FORMAT`
(`CgQuadRenderer.java:100-105`) already has a per-instance `vec4 color` field, and
`CgTextRenderer.submitSortedQuads` already calls `quadRenderer.quad()....color(...)` **per glyph**
(`CgTextRenderer.java:864-869`) — the batching/GPU-submission layer has never been the bottleneck
here. The only thing standing in the way is that `submitSortedQuads` currently reads one uniform
`rgba` value for the whole draw call (`CgTextRenderer.java:818`, the `rgba` parameter threaded down
from `Draw.color(int)`, `CgTextRenderer.java:640-643`) instead of per-glyph.

Fix: once `CgShapedRun` carries an optional per-run color (§4.3) and `CgResolvedGlyphs` threads it
through alongside the existing per-glyph font key/glyph id/pen position it already flattens
(`CgResolvedGlyphs.java:92-132`), `submitSortedQuads` resolves each glyph's effective color as
`run.getArgbColor() != 0 ? run.getArgbColor() : draw.rgba` instead of always using `draw.rgba`. Pure
CPU-side plumbing — no new GPU-side capability required.

## 6. What does *not* need to change

- **`text/atlas`, `text/atlas/packing`, `text/msdf`, `text/cache`** — untouched. Bold/italic are
  just different `CgFontKey`s (different `CgFontStyle`), which the glyph cache already keys
  correctly; color is a draw-time-only concern that never reaches the atlas.
- **`CgQuadRenderer.INSTANCE_FORMAT`** — already has per-instance color (§5).
- **`CgTextShaper`** — shapes whatever `(text, start, end, fontKey, rtl, hbFont)` it's given;
  doesn't care that the caller now resolves `fontKey`/`hbFont` per style sub-range instead of per
  BiDi run.
- **`collectShapedRuns`'s abstract signature** — per §4.2, the style resolution happens one layer up
  in the engine's splitting loop, not inside the hook.

## 7. Ordered implementation sequence

1. `CgStyledText`/`CgStyleSpan` (api/text) — new, no dependencies on anything else here.
2. `CgFontFamilyGroup` (api/font) — new, depends only on existing `CgFontFamily`/`CgFontStyle`.
3. `CgShapedRun` color field — additive constructor, matching the existing source-text-field pattern.
4. `CgTextLayoutEngine` BiDi∩style-span splitting + per-line metrics max in `CgLineBreaker` — the
   real algorithmic work in this list.
5. `CgTextLayoutBuilder`'s reshaper resolved against the family group instead of one family (§4.5).
6. `CgResolvedGlyphs`/`CgTextRenderer.submitSortedQuads` per-glyph color resolution (§5).
7. `text/richtext/` markup parser(s) — can be built and unit-tested fully in parallel with 1–6,
   since its only output is a `CgStyledText`; it has no dependency on the layout-engine changes.
8. (Separate, later, out of scope here) a block-tag → `UIElement`-tree translator in CrystalGUI,
   once inline formatting is proven end-to-end.

Steps 1–3 and 7 have no sequencing dependency on each other and could be parallelized; 4–6 are a
strict chain (splitting must exist before per-run color has anything to attach to; color plumbing
is the last step since it depends on the shaped-run field from step 3 actually being populated by
step 4's splitting).
