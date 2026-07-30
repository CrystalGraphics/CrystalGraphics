## CrystalGUI TODO
- Fix checkbox group (its buggy)
- Move new CgCursor back to Cursor maybe? idk



## CrystalGraphics TODO

- Text outline stroke/color, shadows
- FULL docs & root AGENTS.md/local package ones update pass for everything thats ever been updated in text/font 
  as there's gonna be a lot of old stale entries.
- Fix text depth being hardcoded inside drawInternal, what if users want 3D text drawn with depth
 testing disabled for waypoints/markers




















## CrystalGUI DONE


- Turn CgUiPaintContext into a singleton (sort of done but unfinished)
- CgUiLifecycle
- Migrate CgUiRenderer to use CgQuadRenderer and replace all CgBatchRenderers with it.
- Wtf is CrystalGuiCore? Turn all of that into platform abstraction services.








## CrystalGraphics DONE
- Why does CgTextRenderer.submitDecorations flush? Its just quads, cant we submit them in the same submitBatchedQuads 
  call? We already have the white pixel for each font atlas texture. Plan this out appropriately first, I dont want you
  to adhoc stitch-mix glyphs and rects together carelessly or cache them together as if they are the same, but I do want
  the same draw call and quad() submission interval WITHOUT flushing. Then immediately implement, dont wait on my approval.

- Why do markup parsers and shaped run store a single text decoration? We need multiple text decorations to be usable
  so make them store a set and process that

- CgShapedRun gained 3 new constructors, thats too much. Why? also cant we turn it into a record?

- Markup parser registry, reference markup parsers statically, instead of  new CgHtmlLikeMarkupParser().parse(text) 
  everytime in CgTextLayoutRequest.obtain, just do like .obtain(text).formatter or .parser(CgMarkupParser.HTML / CgMarkupParser.MINECRAFT) 
  or CgMarkupParser.MARKDOWN, OR via registry names "html", "minecraft", "markdown" so add a .parser(String name) overload.
  i.e in CgMarkupParser.HTML = register("html, new CgHtmlLikeMarkupParser)
  Also we should come up with better names for all of these, but dont rename them yet. Ill do it with my IDE refactor

- Rewrite markup parser implementation more elegantly.

- Fix max width/height not properly adhering to pose & remove CgTextConstraints

- I looked at the default max pages of each atlas page and thats 32 layers of each CgTexture2DArray. Isnt
that a lot of wasted memory? If we have 10 atlases thats 320 pages worth of memory. Or am I
misunderstanding?

- bitmap text seems to have visual artefacts, especially visible in italic

- CgTextRenderer.Draw crashes if no pose, I also want to make createScreenSized for text renderer the default create()
  and make a custom/manual sized creatable manually. I already did the refactor, just update any docs related to this
- Remove skipping Draw.font(font) crashing if layout already provided.

- in submitBatchedQuads whats the different betweens glyphCount and visibleCount? In the TextSceneConfig.kanji
string the glyphCount is 1781 but visibleCount is 1242

- Delete CgGlyphAtlas, or renamed CgGlyphAtlas into CgGlyphAtlas
 
- visually inspect the 24 page japanese atlas textures to ensure ideal packin

- If CgTextRenderer is loaded before CgQuadRenderer, TEXT_MATERIAL fails to compile as its missing QUAD_DATA SSBO

- Rewrite packing package to ensure clean elegant code and functional optimal logic. I want it to be generally usable/scalable
  because in the future we will have a standalone atlas framework for game/particle/texture atlases that users can
  fetch a registered atlas (i.e particles) and stitch textures to. The packing package might be useful for that right?
 Thats my vision

- do we even need guillotine packer anymore as its no longer utilized? 

- does CgTextRenderer render different fonts in the same flush since everything is in one atlas now or does it still flush per 
 font/old texture atlas flow?


- CgTextLayout dirty flag for UI recomputation. Check UIText.recompute, do we even need it the layout recomputable or does
 this UI recompute() do a better job at it than whatever we could implement. I want the most optimal path for this that doesnt
 keep instantiating objects on change

- if matrix rotated (not aligned to a 90 degree), NEVER use bitmap



## POSTPONED
- CgTextLayoutRequest cache
- Emoji rendering
