# Minecraft's own objects — the guide

Minecraft draws its own objects (items, entities, blocks, tooltips, anything a mod draws with Minecraft's API) into
**pictures** that your canvas shows. Nothing is captured or imitated: the game's own code draws them, so a mod's item
renderer, a resource pack and a tooltip mod show exactly as in vanilla. This guide covers what exists today: the
picture, the draw point, custom pictures, item icons with their overlays, tooltips, entities and blocks,
Minecraft's objects standing in the engine's own scene, and pocket levels: blocks of your own that Minecraft draws
under a scene of yours.

## If you have drawn Minecraft's objects in a UI before

| The struggle | Usually | Here |
|---|---|---|
| **Where to draw** | inside your screen's render, at the GUI's scale, into whatever target is bound | `canvas.picture(picture, x, y, w, h)` anywhere a canvas draws, at any scale |
| **Every version differs** | `GuiGraphics` from 1.20, a `PoseStack` before, `GlStateManager` on legacy, a recorded GUI from 1.21.6 | one `CgPictureRenderer`; the context hands it the era's own objects |
| **Alpha comes out wrong** | the immediate GUI writes alpha raw, so a picture drawn off screen shows the background through it | handled: drawn twice over black and white where the GUI does that, composited premultiplied |
| **One renderer crashes the screen** | an exception in a mod's renderer takes the frame with it | that picture shows the missing texture, logged once; the rest draw |
| **It costs every frame** | drawn again each frame whether or not it changed | drawn when it changes: `ONCE`, `ON_CHANGE`, `TICK` for what moves on a game tick, or `REALTIME` for what animates |
| **A hundred slots of items** | `renderItem` a hundred times a frame | one atlas cell per look, drawn once; only what animates draws again |

## Contents

1. [Quick start](#1-quick-start)
2. [Pictures](#2-pictures)
3. [Custom pictures](#3-custom-pictures)
4. [Item icons](#4-item-icons)
5. [Entities and blocks](#5-entities-and-blocks)
6. [The draw point](#6-the-draw-point)
7. [Hosts](#7-hosts)
8. [In the scene](#8-in-the-scene)
9. [Pocket levels](#9-pocket-levels)
10. [Testing and the Game Gallery](#10-testing-and-the-game-gallery)

---

## 1. Quick start

```java
static final CgCustomPicture RADAR = CgCustomPicture.of("mymod:radar")
        .refresh(CgPictureRefresh.REALTIME)            // it animates: drawn every frame it is shown
        .renderer(ctx -> {
            GuiGraphics g = (GuiGraphics) ctx.gui();   // 1.20.1 - 1.21.11; see § 3 for the other eras
            g.fill(0, 0, (int) ctx.width(), (int) ctx.height(), 0x80000000);
            g.renderItem(new ItemStack(Items.COMPASS), 4, 4);
        });

// In any canvas, a stage's or a CrystalGUI widget's:
canvas.picture(RADAR, x, y, 128, 128);

// An item's icon and its count, bar and cooldown: any thread, the same handle for an equal stack
CgItemIcon icon = CgItemIcons.of(stack);
canvas.picture(icon, x, y, 16, 16);
canvas.picture(icon.overlay(), x, y, 16, 16);

// Its tooltip as Minecraft and every tooltip mod draw it, at its own size
canvas.picture(CgTooltipPicture.of(stack), pointerX + 12, pointerY - 12);
```

In CrystalGUI, a `PictureView` shows one: `new PictureView(RADAR)`.

## 2. Pictures

`api.canvas.CgPicture` is what a canvas composites that another producer keeps drawing. A `CgKeptLayer` is one; every
picture Minecraft draws (`mc.render.CgHostPicture`) is another.

- **Asked for at its size**: `canvas.picture(p, x, y, w, h)` asks for the picture at the rect's device pixels and the
  canvas's scale, so it is crisp at any scale. A picture shown at two sizes is drawn at each, four at most.
- **Shown, then drawn**: asking marks the picture shown; Minecraft draws it at the next draw point (§ 4). A picture shown
  for the first time appears a frame later. A new size shows the nearest size already drawn until its own is.
- **Premultiplied**, as Mojang's GUI blits its own pictures.
- **`version()`** moves whenever its pixels do: something that keeps its paint (a kept layer, a CrystalGUI window)
  repaints when it moves.
- **States**: `PENDING` (not drawn yet), `READY`, `FAILED` (its draw threw: Minecraft's missing texture shows).
- A size nobody showed for 300 frames gives its targets back; the next show draws it again.

## 3. Custom pictures

`CgCustomPicture` draws anything with Minecraft's own API. Its `CgPictureRenderer` gets a `CgHostDrawContext`:

| Era | `ctx.gui()` | `ctx.poseStack()` | `ctx.buffers()` |
|---|---|---|---|
| 26.1 on | `GuiGraphicsExtractor` | — | — |
| 1.21.6 - 1.21.11 | `GuiGraphics` (recorded) | — | — |
| 1.20.1 - 1.21.5 | `GuiGraphics` | its `PoseStack` | the buffer source |
| 1.15.2 - 1.19.4 | — | a `PoseStack` | the buffer source |
| 1.13.2 - 1.14.4, legacy, 1.7.10 | — | — | — (draw through `GlStateManager` or GL at once, as the era's GUI code does) |

- Units are the rect's: `(0, 0)` its top-left, `ctx.width()` by `ctx.height()`, `ctx.scale()` pixels a unit.
  Minecraft's own GUI scale plays no part.
- `refresh`: `ONCE` (the default: drawn once at each size; `invalidate()` draws it again), `ON_CHANGE`, `TICK`
  (each game tick while shown, every such picture on the same frames: `crystalgraphics.pictures.tickHz`,
  `CgHostPictures.TICK_HZ`, 20 a second; 0 every frame), `REALTIME` (every frame it shows).
- `lighting`: `ITEMS_3D` (the default, the GUI's own), `ITEMS_FLAT`, `ENTITY_IN_UI`. From 1.21.6 the GUI picks per
  element itself.
- A throw marks the picture `FAILED`: logged once with the renderer's class, the missing texture shown, retried after a
  resource reload. Whatever the renderer changed through Minecraft's API is put back.

## 4. Item icons

```java
CgItemIcon sword = CgItemIcons.of(CgItemStack.of("minecraft:diamond_sword", 1));
canvas.picture(sword, x, y, 16, 16);                         // at the rect's device pixels: crisp at any scale

CgItemIcons.prewarm(screenStacks, Math.round(16 * scale));   // a screen opening: every icon at once, past the budget
```

- **Keyed by look**: stacks Minecraft draws alike share one cell, whatever their count. From 1.21.6 the look is
  Mojang's own (the model identity and animated flag its GUI atlas keys by); 1.14 to 1.21.3 the stack's stamp with the
  model its overrides resolve to, so a clock's frame is a look of its own; legacy and 1.7.10 the same with their
  models and icons. Each host answers through a `CgItemIcons.Looks`, which keeps every look it has met: a steady
  frame asks every shown icon's look and allocates nothing.
- **Drawn when it changes**: a cell draws when it gets a slot, and again while it shows and animates, at
  `crystalgraphics.icons.animatedHz` (`CgItemIcons.ANIMATED_HZ`): 20 a second, the game's tick, by default, on a
  fixed step (`CgPace`); 0 draws it every frame. What animates: a glint, a custom renderer (BEWLR, TEISR, `IItemRenderer`, a mod's built-in renderer), an animated sprite. A look the host
  cannot classify counts as animated: never wrong, only slower. 1.21.4 and 1.21.5 have no identity to read, so every
  icon there counts as animated.
- **The atlas**: a uniform grid per size in device pixels, laid out in GUI units (a cell every 18, one of gutter each
  side) so every icon starts on a whole unit, where vanilla draws a slot. Space comes from cells not shown this frame,
  then the page grows to 1.5 times what the frame needs, to a power of two. A size unshown for 300 frames gives its cell
  back.
- **Budget**: first draws share the draw point's `firstDrawMs`; `prewarm` draws past it.
- **Statistics**: `CgItemIcons.stats()` (icons held, cells, pages, first draws, redraws, deferred, an icon redrawn
  and why) and the counters `icons-cells`, `icons-pages`, `icons-first-draws`, `icons-redraws`. A frame that redraws
  icons names one in the trace's `ICON-REDRAWN` hint.
- An item the game does not know shows nothing; a stack whose drawing throws shows the missing texture, logged once.
  1.13.2 draws no icons yet: `CgItems` cannot make its stacks.

### Overlays

`icon.overlay()` is the stack's decorations, drawn by Minecraft's own decoration code: the count, the durability bar
(or any item's own bar), the cooldown sweep, and every mod's `IItemDecorator` on Forge and NeoForge. Shown in the same
rect as the icon, over it; the count's text reaches past the rect as it does in a slot (the overlay's image is drawn 4
units past it on every side). Keyed by the stack's data and count; drawn again every frame while the item cools down. A
stack with nothing to decorate draws none, except on Forge and NeoForge, where a decorator may draw on any stack.

### Tooltips

```java
CgTooltipPicture tip = CgTooltipPicture.of(stack);
canvas.picture(tip, pointerX + 12, pointerY - 12);   // its box's top-left there, at its natural size

CgItemTooltip data = CgItemTooltip.of(stack);         // the same lines, for a UI that draws its own
for (CgStyledText line : data.lines()) ...
for (CgPicture image : data.images()) canvas.picture(image, x, y);   // a bundle's contents, at its own size (1.20+)
```

- **`CgTooltipPicture`** is the whole tooltip a screen draws, through each loader's tooltip events, so a tooltip mod's
  restyle and a bundle's contents show. Its size is the host's own measure (`naturalWidth`, 0 until the first draw
  point); a 12-unit margin around the box holds what a tooltip mod draws past it. Drawn again each game tick it shows
  (`TICK`): a tooltip's lines and a tooltip mod's animated frame move on a tick, so a change shows within 50 ms;
  `CgHostPictures.TICK_HZ.set(0f)` draws it every frame, as vanilla does.
- **`CgItemTooltip`** reads the lines every frame it is asked for (Shift, advanced tooltips), as styled text: colour,
  bold, italic, underline, strikethrough. Its `version()` moves when they change.
- Where Minecraft defers a tooltip to the end of its GUI (1.21.6 on), the host draws it at once into the picture.
- Before 1.20 a tooltip is drawn by a screen that is never shown, sized as the game's GUI: a tooltip wraps only where
  vanilla's would, never to the picture it is drawn into.

## 5. Entities and blocks

```java
CgEntityPicture me = CgEntityPicture.of(CgEntityRef.player());
me.pose(CgEntityPose.followingPointer(pointerX - midX, pointerY - midY));   // each frame, as the inventory does
canvas.picture(me, x, y, 50, 70);                                          // centred in the rect, fitted to its height

CgEntityPicture sheep = CgEntityPicture.of(CgEntityRef.of(CgEntityData.parse("sheep{Color:2b}")))
        .pose(CgEntityPose.facing(-30, 0)).scale(20);                      // 20 units a block

CgBlockPicture chest = CgBlockPicture.of(CgBlockState.parse("chest[facing=south]{Items:[...]}"));
canvas.picture(chest, x, y, 32, 32);
```

- **`CgEntityPicture`** draws what a `CgEntityRef` names: the local player, a live entity by UUID (nothing once it
  leaves the level), a profile's mannequin (the default skin), or an entity made from `CgEntityData`, client-side and
  never added to the level. Minecraft's own renderers draw it, so a mod's mob and every layer show as in the game.
  Drawn every frame it shows; any thread may set its pose.
- **`CgEntityPose`** is the inventory's angles: `followingPointer(dx, dy)` (the pointer from the middle of the box,
  right and down positive) ports vanilla's `atan(d / 40)`; `facing(yaw, pitch)` and `of(body, head, pitch)` set them
  outright. Positive yaw turns the entity to the viewer's left; positive pitch looks down. From 1.19.4 any pose draws;
  before it (and on legacy and 1.7.10) Minecraft turns a model only toward a pointer, so a yaw past about 60 degrees is
  the furthest a pointer turns it.
- A picture clips to its rect, as Minecraft clips the inventory's player from 1.21.6: a model reaching past its bounding
  box (a plant's leaves, wings) needs a rect with room or a smaller `scale`.
- **`CgBlockPicture`** is a block as a slot shows a block item, turned the same way: its model, then its block entity's
  renderer with the state's data (a chest's lid, a banner's pattern). Drawn once, or every frame where the state has
  block entity data. From 1.21.6, and on legacy, Minecraft's GUI draws it by its item: a chest still looks like one,
  without the state's data. 1.7.10 has no block states and draws none.

| Era | Entities | Blocks |
|---|---|---|
| 1.7.10 | `GuiInventory.func_147046_a` (pointer form) | none |
| legacy 1.8.9 - 1.12.2 | `GuiInventory.drawEntityOnScreen` (pointer form) | by item |
| 1.13.2 - 1.14.4 | none | none |
| 1.15.2 - 1.19.3 | `renderEntityInInventory` (pointer form) | model and block entity |
| 1.19.4 - 1.21.10 | the rotation form of `renderEntityInInventory` | model and block entity to 1.21.5; by item from 1.21.6 |
| 1.21.11 - 26.x | the entity's render state, submitted as vanilla does | by item |

## 6. The draw point

`mc.render.CgHostPictures.drawPoint()` draws every picture due in one batch, at the head of each GUI firing (the HUD and
a screen), **before** the engine's device section opens: Minecraft's code runs where Minecraft runs GUI code, with its
own state. A host calls it; a mod never does.

- First draws of a frame stop at `-Dcrystalgraphics.pictures.firstDrawMs` (2 ms); the rest land next frame. Redraws are
  not budgeted: vanilla redraws its own every frame.
- `CgHostPictures.stats()` is the last batch's numbers (held, drawn, first draws, deferred, failed, CPU time), published
  while something reads them; the same go to the trace on `crystalgraphics.pictures`.
- A dev run refuses a draw point inside a device section, by name (`-Dcrystalgraphics.pictures.check=throw|log|off`); in
  a checked run the host also reports state a batch left changed. `-Dcrystalgraphics.host.verify=true` adds a GL
  census entry `pictures` on modern GL.

## 7. Hosts

Pictures are cells on shared **pages**: each size a rect packed on a page (`MaxRectsPacker`, as the font atlas packs
glyphs), 2048 pixels square where the host allows (a larger cell gets a page of its own). Each page is **one host
call**, `Host.drawPage`, with every cell due on it: a framebuffer switch costs a GL driver its next draw, so a target
per picture cost more than Minecraft's draw itself (`plan/crystalgraphics/renderer/steps/NR11.md`). A size given back
leaves its rect; one that does not fit repacks its page, and every cell that moved draws again in that batch.

Each era fills `CgHostPictures.HOST`: a target of Minecraft's own per page, and its GUI pointed at each cell.

| Era | Page | A cell | Alpha |
|---|---|---|---|
| 1.7.10 | `Framebuffer`, bound once | its rect cleared, the viewport on it, the GUI's matrices in GL | one draw; icons two clears |
| legacy 1.8.9 - 1.12.2 | `Framebuffer`, bound once | its rect cleared, the viewport on it, the GUI's matrices in `GlStateManager` | two clears |
| 1.13.2 - 1.14.4 | `RenderTarget`, bound once (`bindWrite`) | its rect cleared, the viewport on it, the GUI's projection over its units | two clears |
| 1.15.2 - 1.21.1 | `RenderTarget`, `TextureTarget`, bound once | as 1.13.2; a scissor the GUI sets moved onto the cell (`GuiScissorHook`, 1.16.5 on) | one draw, alpha paired (`GuiBlendHook`) |
| 1.21.2 - 1.21.4 | `TextureTarget`, bound once, and answered for the main target (`GuiTargetHook`): a render type's `MAIN_TARGET` binds it | as 1.15.2 | one draw, alpha paired |
| 1.21.5 | `TextureTarget` | drawn into a scratch target its size, answered for the main target, then copied into the page (`copyTextureToTexture`) | one draw: its pipelines pair alpha |
| 1.21.6 on | `TextureTarget` | a `GuiRenderer` of its own into a scratch target its size, the main target and window answered for it, then copied into the page | one draw |

While a cell draws, Minecraft's window is the cell's: `Window`'s size, GUI scale and scaled size answer the cell's
pixels, scale and units (`GuiWindowHook`; from 26.1 the window render state). A scissor, a tooltip's screen clamp or a
mod's own window maths therefore land in the cell. Forge 1.13.2 loads no Mixin, so there the window stays the game's.
After each page the host binds back the framebuffer and viewport that were bound before. From 1.21.5 a cell cannot be
reached by viewport -- the encoder binds a target whole, and `GuiRenderer` opens its own passes -- hence the scratch
target and the copy.

**One draw** over a page cleared to `(0, 0, 0, 0)` leaves premultiplied colour and coverage alpha -- what the canvas
composites a picture as -- wherever each blend writes alpha as Minecraft's own from 1.21.6 does (`BlendFunction`):
`TRANSLUCENT` and the premultiplied over take coverage (`ONE, ONE_MINUS_SRC_ALPHA`), the glint keeps what is below
(`ZERO, ONE`), and an unblended draw, opaque on screen, writes 1. 1.21.5's pipelines already blend so; from 1.15.2
to 1.21.4 `GuiBlendHook` pairs each blend `GlStateManager` is asked for while a picture draws.

**Two clears** where nothing can pair it: legacy and 1.13.2 - 1.14.4 blend by calls no hook reaches every one of, and
1.7.10's glint calls GL itself. Each picture draws over black and over white, and a ui material
(`shaders/ui/picture_matte.shader`) takes alpha from the two as the canvas composites it (`CgPicture.Image.matted`).
A host says which with `twoClears()`, and for the icon atlas `iconsTwoClears()`.

Where no Minecraft runs, `CgStandInPictures` draws each page with the engine's own canvas into a `CgRenderTarget`,
each cell clipped and translated, handing the canvas as `ctx.gui()`: the harness runs everything above the draw point
as in game; it keeps a page, emptying a cell with the canvas's own blends before drawing it.

A picture may draw past the rect it is shown in: an image's `outset` (each side a fraction of the rect) widens the quad
and what a kept layer damages. A `CgHostPicture` kind asks for it with `bleed()`, units its targets hold around the
rect. A picture with a size of its own answers `naturalWidth`/`naturalHeight`, and `canvas.picture(p, x, y)` shows it at
that size.

## 8. In the scene

Minecraft's objects can stand in the engine's own scene: an item in an actor's hand, a mob a sequence places, a block
at a point. Each is a **host-drawn component**: the scene places it like any primitive (attached, moved, hidden), and
Minecraft's own renderers draw it, into the level's depth, so a mod's item renderer and every layer show as in the
game, and the engine's lights light it.

```java
CgItemStackComponent sword = CgItemStackComponent.of(CgScene.client(), CgItemStack.of("mymod:blade", 1))
        .display(CgItemDisplay.THIRD_PERSON_RIGHT_HAND)     // the model's own hand transform
        .attach(actor.prefab().node("hand_r"), null)        // rides the socket, as any primitive
        .spawn();

CgChildEntityComponent zombie = CgChildEntityComponent.of(CgScene.client(),
        CgEntityRef.of(CgEntityData.parse("minecraft:zombie{IsBaby:1b}")))
        .at(x, y, z).spawn();                               // its feet at the origin; never added to the level
zombie.pose(CgEntityPose.of(20, 45, -10));                  // relative to the component's facing

CgBlockStateComponent chest = CgBlockStateComponent.of(CgScene.client(), CgBlockState.parse("chest[facing=south]"))
        .at(x, y, z).spawn();                               // its corner, as a block's position names it
```

A sequence places an entity with its `entity` spawnable ([sequences](vfx/sequences.md#what-a-sequence-spawns)).

- Client scene only: `of` throws for any other scene. On a dedicated server nothing draws and nothing is made.
- An entity is made from its data once, client-side, and never joins the level: it neither ticks nor collides. Its
  age runs from its spawn, so a mob's idle animation plays.
- What the game lacks (an unknown item, block or property) is logged once as the component first draws, and the
  component hides; the rest draw.
- **Culled as Minecraft culls its own**: by the view, each component's box tested against the frame camera's frustum
  as the engine's own primitives are, and past the distance Minecraft draws the object at. An entity by its culling
  box (an entity Minecraft draws whole wherever it is, never) and its own render distance, which follows the player's
  entity distance; a block by its cube with a block around it (a banner draws above its block), a block entity past
  its renderer's view distance, and one drawn past its block never; an item stack by a cube two blocks across, and at
  any distance, as it may ride a character drawn far off. The host answers each (`CgHostWorld.Host.bounds`,
  `inRange`); a host that does not, culls nothing.
- `CgHostWorld.drawn()` and `culled()` are the last frame's counts. Where no Minecraft runs (the harness), nothing
  draws.

| Era | Where they draw | How |
|---|---|---|
| 1.7.10 | the opaque hook | GL's model-view, `RenderManager`, `ItemRenderer`; no blocks |
| legacy 1.8.9 - 1.12.2 | the opaque hook | GL's model-view, `RenderItem`, `RenderManager`, the tile entity dispatcher |
| 1.13.2 - 1.14.4 | the opaque hook | GL's model-view; entities from data from 1.14.3 |
| 1.15.2 - 1.21.8 | the opaque hook | a `PoseStack` and the level's buffers, flushed before the engine's section |
| 1.21.10 - 26.x | as the level submits its entities (`WorldSubmitHook`) | submitted into the level's own collector under its camera, drawn in its frame with its entities |

## 9. Pocket levels

A **pocket level** is a client-only world of blocks of your own: a structure in a guidebook, a multiblock preview, a
building editor. Minecraft meshes and draws its blocks with its own section compiler, so a mod's block model, a
resource pack and a block entity's renderer show exactly as in the game. It draws under a view of a scene of yours:
your meshes stand among its blocks, depth-tested against them, lit by your lights.

```java
CgStructure tower = CgStructure.load("mymod:tower");          // data/mymod/structure(s)/tower.nbt, vanilla's format
CgPocketLevel level = CgPocketLevel.create();                  // a client world must be open
List<CgChildEntityComponent> mobs = level.place(tower, 0, 0, 0, scene);   // its entities as the scene's components

scene.hostLevel(level);                                        // Minecraft draws the level under this scene
canvas.view(CgView.of(scene), x, y, w, h);                     // every view of the scene shows it
```

A structure built in code, for a tool or a test:

```java
CgStructure wall = CgStructure.builder(5, 3, 1)
        .fill(0, 0, 0, 4, 2, 0, CgBlockState.parse("minecraft:stone_bricks"))
        .block(2, 1, 0, CgBlockState.parse("minecraft:glass"))
        .entity(2.5, 0, 0.5, CgEntityData.parse("minecraft:armor_stand"))
        .build();
level.place(wall, 0, 0, 0).setBlock(2, 2, 0, CgBlockState.parse("minecraft:torch"));
```

A picture of the level alone, with no scene of yours, for a slot or a page; it redraws only when the level or its
camera changes:

```java
CgLevelPicture page = CgLevelPicture.of(level).camera(camera);
canvas.picture(page, x, y, 200, 200);
```

Picking and outlining a block, as the crosshair does:

```java
CgPocketLevel.Hit hit = level.pick(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, 8);
CgLineBatchComponent outline = CgLineBatchComponent.of(scene).thickness(3).spawn();
if (hit != null) for (CgAABBc box : hit.outline()) outline.box(box, 0xFF000000);
```

Only the blocks of some layers, as a guidebook slices a build; and the block entities' own animation:

```java
level.visibleRange(0, 4);       // only blocks with 0 <= y <= 4 exist; level.visibleRange() shows them all again
level.ticking(true);            // the client tickers run: an enchanting table's book turns, a furnace flickers
```

- Client only. `create` runs on any thread and throws where pocket levels cannot exist, with the reason; Minecraft's
  copy is made at the next draw point, a client world open. The harness's host is `CgStandInPocketLevels`: each block
  its shaded faces, so everything above the host runs as in game.
- A CrystalGUI UI shows one through `SceneView` (CrystalGUI's `docs/CGUI_WIDGETS.md` § 12h): navigation, picking,
  markers, tools and undo over this API.
- A pocket level is never a world: no server ticks it and nothing is sent anywhere. Its light is full and its biome
  plains; Minecraft's own world neither lights nor shadows it, and the scene's lights do.
- Changes are applied at the next draw point, on the render thread; a section is meshed again only where a block in
  or beside it changed.
- A view's first frame at a new size shows the scene without the level: the level is drawn at the draw point, before
  the view asks for it.
- `pick` answers blocks only; a structure's entities are the scene's components, which `CgView.pick` answers.
- The outline follows the era's crosshair: a block's shape from 1.13 (a stair's two boxes), its one selection box
  before. `CgPocketLevel.picksByShape()` says which.
- `close()` frees its meshes and targets; a scene that still names it draws without it.

| Era | How Minecraft draws it |
|---|---|
| 26.3 | one render pass on the view's target: each section's uploaded buffer, then the level's features |
| 26.2 | each section uploaded once and drawn from its buffer; block entities through the feature dispatcher |
| 26.1 | meshed as the section compiler meshes, written as a moving block; the kept bytes drawn by the block render types |
| 1.21.5 - 1.21.11 | the kept bytes drawn by the chunk layer's render type; from 1.21.10 block entities submitted and rendered as a picture-in-picture renderer does |
| 1.17 - 1.21.4 | a vertex buffer per section and layer, drawn as `renderChunkLayer` draws one |
| 1.15 - 1.16 | fixed function: the projection in GL's stack, each section drawn under its pose |
| 1.14.3 - 1.14.4 | 1.14's own terrain path: `RenderChunk.compile`, `VboRenderList`'s client arrays |
| legacy 1.8.9 - 1.12.2 | `RenderChunk.rebuildChunk` per layer a block renders in, `VboRenderList`; tile entities by the dispatcher |
| 1.13.2, 1.7.10 | none: 1.13.2 meshes a block only through a whole world, and 1.7.10 has no block states. `create` throws with the reason |

A host level draws in the depth convention of Minecraft's world, not the GUI's where its draw point falls: reversed from
26.2, as the world draws. `CgHostLevel` is the engine's side of it, for a host level of another kind.

## 10. Testing and the Game Gallery

- `crystalgraphics:world_objects` (gauntlet): § 8's components in the level. An actor holds a sword at its hand
  (`-Dcrystalgraphics.world_objects.item=<id>` for a mod's), a sequence places a zombie, a chest stands beside
  them, and a point light of ours lights them: `world.rides`, `world.sequence`, `world.drawn` (pixels that change
  as they show) and `world.lit` (those pixels brighter under the light).
- `crystalgraphics:pocket_level` (gauntlet): § 9's tower in a view of a scene of ours: `pocket.drawn`, `pocket.depth`
  (a cube of ours partly behind its wall), `pocket.lit` (the wall brighter under our light), `pocket.pick` and
  `pocket.outline` (a stair picked and outlined as the era's crosshair does), `pocket.picture`, `pocket.range`,
  `pocket.ticking` and `pocket.entities`. It waits for pipelines still building, so a cold client's captures are of
  a built engine.
- `crystalgraphics:pictures` (gauntlet): the draw point's parity test. The host draws the same objects with vanilla's
  own GUI code beside pictures and icons of them; the capture is compared pixel for pixel, within 1 level (8 on a glint, which
  scrolls with the clock; 2 under a tooltip's translucent layers). A throwing renderer shows the missing
  texture; a dev run refuses a draw point inside a section. `-Dcrystalgraphics.pictures.items=a+b` adds mods' items.
  Its 100-slot grid checks `icons.static` (a still grid draws nothing once drawn) and `icons.glint` (a glinted one
  draws its looks every frame). Decorated cells are icon and overlay (an ender pearl kept cooling down, a stick marked by
  the probe's decorator on Forge and NeoForge); tooltips are `CgTooltipPicture`s, and a second capture compares a book's
  and a bundle's (from 1.20.5, where items carry components) under `pictures.parity.tooltips.*`. Entity and block cells
  (the player following the pointer, a sheep from its data, a chest) are drawn by their host slots straight into the HUD
  and as pictures; `-Dcrystalgraphics.pictures.entity=<data>` puts a mod's mob in the sheep's cell, and
  `pictures.made.<cell>` fails where the game makes none.
- CrystalGUI's Game Gallery (`crystalgui:game-gallery`, the `game-gallery` harness scene) shows every kind on a page
  with its checks.
