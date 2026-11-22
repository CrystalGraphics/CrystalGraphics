# Minecraft's own objects — the guide

Minecraft draws its own objects (items, entities, blocks, tooltips, anything a mod draws with Minecraft's API) into
**pictures** that your canvas shows. Nothing is captured or imitated: the game's own code draws them, so a mod's item
renderer, a resource pack and a tooltip mod show exactly as in vanilla. This guide covers what exists today: the
picture, the draw point, custom pictures, item icons with their overlays, tooltips, entities and blocks, and
Minecraft's objects standing in the engine's own scene.

## If you have drawn Minecraft's objects in a UI before

| The struggle | Usually | Here |
|---|---|---|
| **Where to draw** | inside your screen's render, at the GUI's scale, into whatever target is bound | `canvas.picture(picture, x, y, w, h)` anywhere a canvas draws, at any scale |
| **Every version differs** | `GuiGraphics` from 1.20, a `PoseStack` before, `GlStateManager` on legacy, a recorded GUI from 1.21.6 | one `CgPictureRenderer`; the context hands it the era's own objects |
| **Alpha comes out wrong** | the immediate GUI writes alpha raw, so a picture drawn off screen shows the background through it | handled: drawn twice over black and white where the GUI does that, composited premultiplied |
| **One renderer crashes the screen** | an exception in a mod's renderer takes the frame with it | that picture shows the missing texture, logged once; the rest draw |
| **It costs every frame** | drawn again each frame whether or not it changed | drawn when it changes: `ONCE`, `ON_CHANGE`, or `REALTIME` for what animates |
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
9. [Testing and the Game Gallery](#9-testing-and-the-game-gallery)

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
- `refresh`: `ONCE` (the default: drawn once at each size; `invalidate()` draws it again), `ON_CHANGE`, `REALTIME`.
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
  models and icons.
- **Drawn when it changes**: a cell draws when it gets a slot, and again every frame it shows while animated: a glint,
  a custom renderer (BEWLR, TEISR, `IItemRenderer`, a mod's built-in renderer), an animated sprite. A look the host
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
  point); a 12-unit margin around the box holds what a tooltip mod draws past it. Drawn every frame it shows.
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
- `CgHostPictures.stats()` is the last batch's numbers (held, drawn, first draws, deferred, failed, CPU time); the same go
  to the trace on `crystalgraphics.pictures`.
- A dev run refuses a draw point inside a device section, by name (`-Dcrystalgraphics.pictures.check=throw|log|off`); in
  a checked run the host also reports state a batch left changed. `-Dcrystalgraphics.host.verify=true` adds a GL
  census entry `pictures` on modern GL.

## 7. Hosts

Each era fills `CgHostPictures.HOST`: a target of Minecraft's own per picture, and its GUI pointed at it.

| Era | Target | Redirect | Alpha |
|---|---|---|---|
| 1.7.10, legacy 1.8.9 - 1.12.2 | `Framebuffer` | bound; the GUI's matrices in GL or `GlStateManager` | two clears |
| 1.13.2 - 1.21.1 | `RenderTarget`, `TextureTarget` | bound (`bindWrite`); the GUI's projection over the picture's units | two clears |
| 1.21.2 - 1.21.4 | `TextureTarget` | bound, and answered for the main target (`GuiTargetHook`): a render type's `MAIN_TARGET` binds it | two clears |
| 1.21.5 | `TextureTarget` | answered for the main target (`GuiTargetHook`) | two clears |
| 1.21.6 on | `TextureTarget` | a `GuiRenderer` of its own, the main target and window answered for it | one draw |

While a picture draws, Minecraft's window is the picture's: `Window`'s size, GUI scale and scaled size answer the
picture's pixels, scale and units (`GuiWindowHook`; from 26.1 the window render state). A scissor, a tooltip's screen
clamp or a mod's own window maths therefore land in the picture. Forge 1.13.2 loads no Mixin, so there the window
stays the game's. After each picture the host binds back the framebuffer and viewport that were bound before.

"Two clears": where the GUI writes alpha raw, each picture draws over black and over white, and a ui material
(`shaders/ui/picture_matte.shader`) takes alpha from the two as the canvas composites it (`CgPicture.Image.matted`).

Where no Minecraft runs, `CgStandInPictures` draws each picture with the engine's own canvas into a `CgRenderTarget`,
handing the canvas as `ctx.gui()`: the harness runs everything above the draw point as in game.

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
- `CgHostWorld.drawn()` is the last frame's count. Where no Minecraft runs (the harness), nothing draws.

| Era | Where they draw | How |
|---|---|---|
| 1.7.10 | the opaque hook | GL's model-view, `RenderManager`, `ItemRenderer`; no blocks |
| legacy 1.8.9 - 1.12.2 | the opaque hook | GL's model-view, `RenderItem`, `RenderManager`, the tile entity dispatcher |
| 1.13.2 - 1.14.4 | the opaque hook | GL's model-view; entities from data from 1.14.3 |
| 1.15.2 - 1.21.8 | the opaque hook | a `PoseStack` and the level's buffers, flushed before the engine's section |
| 1.21.10 - 26.x | as the level submits its entities (`WorldSubmitHook`) | submitted into the level's own collector under its camera, drawn in its frame with its entities |

## 9. Testing and the Game Gallery

- `crystalgraphics:world_objects` (gauntlet): § 8's components in the level. An actor holds a sword at its hand
  (`-Dcrystalgraphics.world_objects.item=<id>` for a mod's), a sequence places a zombie, a chest stands beside
  them, and a point light of ours lights them: `world.rides`, `world.sequence`, `world.drawn` (pixels that change
  as they show) and `world.lit` (those pixels brighter under the light).
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
