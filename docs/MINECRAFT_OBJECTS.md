# Minecraft's own objects — the guide

Minecraft draws its own objects (items, entities, blocks, tooltips, anything a mod draws with Minecraft's API) into
**pictures** that your canvas shows. Nothing is captured or imitated: the game's own code draws them, so a mod's item
renderer, a resource pack and a tooltip mod show exactly as in vanilla. This guide covers what exists today: the
picture, the draw point, custom pictures and item icons. Entities, blocks and tooltips arrive as their own kinds on the
same base.

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
5. [The draw point](#5-the-draw-point)
6. [Hosts](#6-hosts)
7. [Testing and the Game Gallery](#7-testing-and-the-game-gallery)

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

// An item's icon: any thread, the same handle for an equal stack
canvas.picture(CgItemIcons.of(stack), x, y, 16, 16);
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

## 5. The draw point

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

## 6. Hosts

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

## 7. Testing and the Game Gallery

- `crystalgraphics:pictures` (gauntlet): the draw point's parity test. The host draws the same objects with vanilla's
  own GUI code beside pictures and icons of them; the capture is compared pixel for pixel, within 1 level (8 on a glint, which
  scrolls with the clock; 2 under a tooltip's translucent layers). A throwing renderer shows the missing
  texture; a dev run refuses a draw point inside a section. `-Dcrystalgraphics.pictures.items=a+b` adds mods' items.
  Its 100-slot grid checks `icons.static` (a still grid draws nothing once drawn) and `icons.glint` (a glinted one
  draws its looks every frame).
- CrystalGUI's Game Gallery (`crystalgui:game-gallery`, the `game-gallery` harness scene) shows every kind on a page
  with its checks.
