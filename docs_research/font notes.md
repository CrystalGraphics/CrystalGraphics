## GlyphPlacement
- The glyph's location within the atlax texture
- Includes atlasTextureId, atlasPageIndex on the atlas, the 4 uvs corner , the 4 atlas corners (in pixels)




# FONT METRICS

![Latin alphabet font specimen with baseline and x-height grid](https://ilovetypography.com/img/2009/01/alphabet.png)

- They are shared values for all glyphs in a single font
- They are distances, not absolute positions. The baseline is not provided as its a distance
  calculated from the other metrics, such as ascender: distance above it, descender: distance below it
  Where the baseline is at is calculated from the pen origin .at(x, y+ascender) coordinates provided.

## Baseline
- The invisible line that letters sit on. 'a' 'x' sit directly on top,
  The "tails" or DESCENDERS of 'g', 'p', 'y' dip below the baseline.

## x-Height
- Height of lowercases like 'x' from baseline based on lowercase 'x'

## Cap-Height
- Height of capital letters from baseline 'T', 'A'

## Ascender
- Part of lowercase letter that extends above the x-Height 'b', 'd', 'f', 'l'
- Strictly a lowercase feature, capital letters don't have ascenders, but Cap-Height instead
- In typography and in this layout engine, the ascender line is higher than the cap height line,
  and it represents the absolute top boundary used for our text layout height calculations.

## Descenders and Descender line
- The descenders are the ACTUAL part of the letter that drops below the baseline, it's not a line.
- The descender lines are the invisible lines that those descenders touch.
- Traditionally strictly lower-case only, but some capitals like 'Q' 'J' have very small descenders

-In a single font, there is only one shared descender line. All characters

## Pen Origin
- The origin position in where you start drawing the glyph. The at(x,y) submitted in CgTextRenderer.Draw 
- Is the origin of the baseline, then it increments horizontally (x) by glyph advance
- However in our implemented, the Y provided defines the ascender line not baseline, so
  we are technically drawing from ascender line draw().at(20, 40), which gets shifted down into 
  the pen origin (20, 40 + ascender)
 
- Why doing it this way is so useful:
   If draw().at(x, y) required you to pass the raw baseline coordinate directly:
   Positioning text inside UI boxes, buttons, or layout bounds would be a nightmare because you'd have 
   to manually calculate y + ascender for every font and size.

   Text would pop out above your box if you gave it the top-left coordinate (20, 40).

   By making at(20, 40) represent the top-left of the layout box, your text neatly aligns inside UI elements, 
   while CgTextRenderer handles calculating penY for the font's actual baseline under the hood!

## Pen Position
- The accumulated incremented pen origin. If 10 glyphs processed, then it's the origin + the advance
  of the previous 9 glyphs (10th glyph's advance is added after its drawn) 
