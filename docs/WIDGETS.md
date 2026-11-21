# Widgets: interface elements as assets

A damage number popping over a target, a name plate over a head, a subtitle with its speaker's name, a combo counter
in the corner: each is a **widget**, an interface element your artist lays out in a file and your code only shows and
animates. It is Unreal's UMG widget blueprint. The layout and the look live in the file, never in code; your code
makes one, sets its text and plays its animations, as `CreateWidget`, `SetText` and `PlayAnimation` do in Unreal.

Package: `com.crystalgraphics.widget`. This page is for code that uses widgets; the guide for code inside the package
is its `CLAUDE.md`. Widgets paint onto the canvas ([`UI.md`](UI.md)) with the engine's paint values
([`PAINT.md`](PAINT.md)); text styles are [`vfx/assets.md`](vfx/assets.md).

---

## 1. How it fits together

```
mymod:hud/pop.yaml                          a widget tree: the design, shared by every use
   │  POP.loaded().newInstance()
   ▼
CgUserWidget                                an instance: its own text, colours and animations
   │  shown by a layer, or painted by you
   ▼
CgScreenWidgetLayer / CgWorldWidgetLayer    on the screen, or over a point in the world
   ▲
   │  playAnimation(...)
mymod:hud/pop_in.yaml                       a sequence: an animation of its parts, by name
```

- **A widget tree** (`CgWidgetTree`) is the design: named widgets, each placed by its parent. It is an asset, loaded
  with your kit, and shared.
- **An instance** (`CgUserWidget`) is one use of it: its text, colours and running animations are its own.
- **A layer** shows instances: `CgScreenWidgetLayer` on the screen at a z-order, `CgWorldWidgetLayer` over points in
  the world. Or you paint an instance onto a canvas yourself.
- **A sequence** animates parts of an instance, found by their names.

Take the first way of showing it that does what you need:

| You want | Use | Section |
|---|---|---|
| A widget on the screen: a HUD panel, a combo counter | `CgScreenWidgetLayer` | [6.1](#61-on-the-screen) |
| A widget that follows a point in the world: a damage number, a name plate | `CgWorldWidgetLayer` | [6.2](#62-over-a-point-in-the-world) |
| A widget standing in the world on a quad: a quest board | `scene.quad(...).widget(...)` | [6.3](#63-on-a-quad-in-the-world) |
| An instance you place and paint yourself | `widget.paint(canvas)` | [6.4](#64-painting-it-yourself) |

---

## 2. Your first widget

A damage number: one text block, centred on its point.

```yaml
# assets/mymod/widgets/hud/pop.yaml: the widget mymod:hud/pop
_v: 1
root: Root
widgets:
  Root: { kind: canvas, children: [Number] }
  Number:
    kind: text_block
    slot: { anchors: [0.5, 0.5], alignment: [0.5, 0.5], auto_size: true }   # centred, at its own size
    style: mymod:hud/gold
    text: "0"
```

```java
static final CgAssetRef<CgWidgetTree> POP = CgVfxAssets.ref(CgLookKinds.WIDGET, "mymod:hud/pop");
static final CgAssetRef<CgSequenceDefinition> POP_IN = CgVfxAssets.ref(CgSequenceKinds.SEQUENCE, "mymod:hud/pop_in");
static final CgWorldWidgetLayer POPS = CgWorldWidgetLayer.builder("mymod:pops").kit("mymod").build();

// a hit: over the target's middle, gone when its animation ends
CgWidgetComponent pop = POPS.add(POP.loaded(), CgEntityAttachments.of(targetId));
pop.widget().text("Number", "1,250");
pop.play(POP_IN.loaded());
```

That is a whole damage number. The asset loads with your kit behind the loading screen; the layer places it on the
target every frame, plays its animation, and hands it back to its pool when the animation ends.

---

## 3. Writing a tree

### In a file

`widgets` maps every widget's name to the widget. A panel lists its children by name, and `root` names the top one.
Names are letters, digits and `_`, unique in the tree: animations find widgets by them.

```yaml
# assets/mymod/widgets/hud/pop.yaml
_v: 1
root: Root
widgets:
  Root: { kind: canvas, children: [Number, Banner] }
  Number:
    kind: text_block
    slot: { anchors: [0.5, 0.5], alignment: [0.5, 0.5], auto_size: true }
    style: mymod:hud/gold
    text: "0"
  Banner:
    kind: image
    slot: { anchors: [0.5, 0.5], offsets: [0, 30, 224, 56], alignment: [0.5, 0] }
    colour_and_opacity: [1, 1, 1, 0]                     # hidden until an animation fades it in
    brush: { texture: mymod:textures/hud/banner.png, image_size: [224, 56] }
```

### In Java

The same tree built in code, for a widget no file holds:

```java
static final CgWidgetTree POP = CgWidgetTree.of("mymod:hud/pop", CgWidget.canvas("Root")
        .child(CgWidget.text("Number").style(GOLD).text("0")
                .slot(CgWidgetSlot.builder().anchors(0.5f, 0.5f).alignment(0.5f, 0.5f).autoSize(true).build()))
        .child(CgWidget.image("Banner")
                .brush(CgBrush.texture("mymod:textures/hud/banner.png").size(224f, 56f))
                .slot(CgWidgetSlot.builder().anchors(0.5f, 0.5f).alignment(0.5f, 0f).offsets(0f, 30f, 224f, 56f).build())
                .colour(new CgColor(1f, 1f, 1f, 0f)))
        .build());
```

- **The tree is checked as it loads.** A widget no panel reaches, a name a panel lists that `widgets` lacks, or a
  widget two panels list fails, naming it.
- **The tree is shared; an instance is yours.** `tree.newInstance()` makes one; a world layer pools them for you.

### The widgets

| Kind | Unreal | Holds | Its size |
|---|---|---|---|
| `canvas` | `UCanvasPanel` | `children`, each placed by its slot's anchors and offsets, painted in `z_order`, then list order | the largest child's reach |
| `horizontal_box` | `UHorizontalBox` | `children` in a row, left to right | its children's widths and paddings added |
| `border` | `UBorder` | `content`, one widget, padded; a `background` brush and its `brush_colour`; `content_colour_and_opacity` tints everything inside | its content's and its padding |
| `scale_box` | `UScaleBox` | `content`, one widget, laid out and drawn at `user_specified_scale`; `stretch: user_specified` is required | its content's and its padding, times the scale |
| `retainer_box` | `URetainerBox` | `content`, drawn as one picture, optionally through an `effect` material or a `blur` ([§ 8](#8-drawing-a-part-as-one-picture-the-retainer-box)) | its content's |
| `image` | `UImage` | a `brush`; `colour_and_opacity` | its brush's `image_size` |
| `text_block` | `UTextBlock` | `text`, a string id or the literal text; its `style`; `colour_and_opacity`; `justification`; `wrap` | its text's, in its style |
| `user_widget` | a nested `UUserWidget` | `widget`, another widget asset; `colour_and_opacity` | its tree's |

Every widget also takes:

| Field | Holds |
|---|---|
| `transform` | `{ translation, scale, shear, angle }`, applied after layout about `pivot`, so it never moves a sibling. The angle is in degrees, clockwise |
| `pivot` | a fraction of its own size; the middle, `[0.5, 0.5]`, by default |
| `render_opacity` | 0 to 1, multiplied into it and everything under it |
| `visibility` | `visible`; `hidden` keeps its space and draws nothing; `collapsed` takes no space |

---

## 4. Laying it out: slots

A **slot** is how a widget's parent places it, so what it holds depends on the parent.

**In a canvas**, a slot pins the widget to its parent by **anchors**, fractions of the parent from 0 (left or top) to
1 (right or bottom). Where an axis's two anchors are the same point, the offsets are a position and a size from it;
where they differ, the widget stretches between them and the offsets are margins. `alignment` says which point of the
widget sits on its position.

```yaml
slot: { anchors: [1, 1], offsets: [115, 48, 224, 56], alignment: [1, 1] }    # its bottom-right 115 left of and 48 above the parent's
slot: { anchors: [[0, 0.5], [1, 0.5]], offsets: [0, 0, 0, 60] }              # stretched across, 60 high, at the middle
slot: { anchors: [0.5, 0.5], alignment: [0.5, 0.5], auto_size: true }         # centred, at its own size
```

**In a horizontal box or a border**, a slot pads and aligns:

```yaml
slot: { padding: [-13, 0, -13, 0], vertical_alignment: centre }               # overlapping its neighbours by 13
slot: { size: { fill: 1 } }                                                    # a share of the room auto children leave
```

**Defaults** are UMG's: a canvas child is `[0, 0, 100, 30]` at the top left; a border pads its content by
`[4, 2, 4, 2]`; a box child fills its row's height; a scale box centres its content.

**A scale box** fits a smaller copy of a look into a row at the size it shows. A `transform` scale would shrink it
but leave its neighbours spaced for the full size:

```yaml
LabelBox: { kind: scale_box, stretch: user_specified, user_specified_scale: 0.418, content: Label }   # 46 / 110
Label: { kind: text_block, style: mymod:hud/counter, text: mymod:text/combo.hits }
```

---

## 5. How it looks

### Brushes

A widget's `brush` is the same `CgBrush` the canvas draws ([`PAINT.md`](PAINT.md) § *Images: brushes*), written as
fields:

| Field | Is |
|---|---|
| `texture`, `svg` or `material` | the image, an SVG icon, or a UI material (`#domain ui`, on `Primitive Element`) |
| `image_size` | the size it wants to be, `[32, 32]` by default |
| `tint` | its colour multiplied |
| `region` | `[x, y, w, h]`, part of the image in its pixels |
| `layer` | a layer of a texture array |
| `nine_slice` | `[left, top, right, bottom]`: corners that keep their size |
| `tiling` | how it repeats |
| `draw_as` | `image`, or `none` to draw nothing |

```yaml
brush: { texture: mymod:textures/hud/banner.png, image_size: [224, 56] }
brush: { svg: mymod:ui/icons/sword.svg, image_size: [24, 24] }                # fitted, centred, sharp at any size
background: { draw_as: none }                                                  # a border with no background
```

- **A brush with no image still draws**: a box of its tint, as Slate's does. A border's background is that until you
  set it, so hide it with `background: { draw_as: none }`.
- **An SVG brush**'s `currentColor` is its tint. In Java: `CgBrush.svg(path).size(24f, 24f)`.

### Colours

Widget colours are **linear**, `[r, g, b, a]` from 0 to 1, as Unreal's `FLinearColor`. They multiply down the tree in
linear light and turn into screen colours where drawn. In Java they are `CgColor`s: `CgColor.argb(0xFFFFC040)` turns a
picked colour into one.

### Text

A text block's `text` is the literal text or a **string id**, `<namespace>:text/<id>`, read in the player's language
when the tree lays out: a game kit's own table first, else your mod's translations. `mymod:text/combo.nice` reads
`"mymod.combo.nice"` from `lang/en_us.json`. A missing string shows its id, as Unreal shows a missing key.

### A variant: a child file

A child file changes its parent's widgets by name, field by field. A widget whose `kind` differs replaces it whole,
and `remove` drops widgets nothing reaches any more:

```yaml
# assets/mymod/widgets/hud/damage_pop.yaml: a game's damage number, its digit images become one text block
_v: 1
parent: kakarot_kit:Art/UI/Battle_Dmg/Battle_Dmg
widgets:
  Number_Center: { kind: text_block, style: mymod:damage/gold, text: "0" }
remove: [Ins_Dmg00_08, Ins_Dmg00_07, Ins_Dmg00_00]
```

The parent must load for the child to.

---

## 6. Showing it

### 6.1 On the screen

A widget on the screen goes on a `CgScreenWidgetLayer`, UMG's viewport: `AddToViewport` puts a widget in a
full-screen canvas at a z-order, `RemoveFromParent` takes it off.

```java
static final CgScreenWidgetLayer HUD = CgScreenWidgetLayer.builder("mymod:hud").kit("mymod").build();

CgUserWidget counter = COUNTER.loaded().newInstance();   // made once, shown and hidden as often as needed
HUD.add(counter, 0);                                     // drawn from this frame, over lower z-orders
counter.text("Number", "12");
counter.playAnimation(POP.loaded());
HUD.remove(counter);
```

```java
// one that takes itself off when its last animation ends
banner.onAnimationFinished(OUT.loaded(), () -> HUD.remove(banner));
banner.playAnimation(OUT.loaded());
```

| Call | Unreal | Does |
|---|---|---|
| `builder(id)...build()` | the game viewport | a layer on `HUD` at `SCREEN_WIDGETS`; `kit(ns)` its UI scale, `order(n)`, `world(w)` |
| `add(widget, zOrder)` | `AddToViewport(ZOrder)` | on the screen over lower z-orders, over equal ones already there; added again, it moves |
| `remove(widget)` | `RemoveFromParent` | off the screen, its animations left as they are |
| `contains(widget)` | `IsInViewport` | whether it is on it |

- **The widget is yours**, never pooled: keep it, set it up, add and remove it as often as it shows.
- **A level change takes every widget off**, as Unreal's `OnLevelRemovedFromWorld`: add it again in the new one.

#### Its size on any screen: the UI scale

The layer fills the screen in **UI units**: the screen's pixels divided by the kit's UI scale, as `AddToViewport`
sizes a widget by its DPI scale. So the root canvas's anchors are fractions of the screen and hold at any window size,
and a widget designed for 1080 lines looks the same at 720 or 1440. A kit sets the scale in `ui.yaml`:

```yaml
# assets/mymod/ui.yaml
_v: 1
rule: shortest_side                                        # or longest_side, horizontal, vertical
curve: [[480, 0.444], [720, 0.666], [1080, 1], [8640, 8]]   # side in pixels to scale: linear, held past its ends
application_scale: 1
```

- Without a `ui.yaml`, or without a `curve`, it is Unreal's default: the shortest side over 1080 (0.666 at 720, 1.333
  at 1440), held at 0.444 below 480.
- A kit tuned at another size keeps its look with `application_scale` alone: 0.75 makes 1440 pixels one unit.

### 6.2 Over a point in the world

A widget that follows something in the world is a **component** on a `CgWorldWidgetLayer`, Unreal's screen-space
`UWidgetComponent`. Each frame the layer puts each component's pivot on its attachment's point as the camera sees it,
the nearer over the farther, in the kit's UI scale. It skips one behind the camera, and one whose attachment is gone
stays where it was last seen.

```java
static final CgWorldWidgetLayer NUMBERS = CgWorldWidgetLayer.builder("mymod:damage_numbers")
        .kit("mymod")                       // its ui.yaml scales it
        .most(8, 16)                        // eight at once, sixteen more waiting, the newest started first
        .build();

CgWidgetComponent pop = NUMBERS.add(POP.loaded(), CgEntityAttachments.of(targetId));
pop.widget().text("Number", "1,250").style("Number", critical ? ORANGE : GOLD);
pop.play(critical ? CRITICAL.loaded() : NORMAL.loaded());   // released when it ends
```

```java
// a name plate: its bottom middle on the head, until the mob goes
CgWidgetComponent plate = PLATES.add(PLATE.loaded(), CgEntityAttachments.of(mobId, CgEntityAttachments.HEAD))
        .pivot(0.5f, 1f).drawSize(160f, 24f);
plate.widget().text("Name", name);
plate.remove();                             // it plays nothing, so it stays until removed
```

| Call | Unreal | Does |
|---|---|---|
| `builder(id)...build()` | `SharedLayerName` | a layer on `HUD` at `WORLD_WIDGETS`, under the game's interface; `order(n)` elsewhere, `world(w)` a world of your own |
| `add(tree, attachment)` | a screen-space `UWidgetComponent` | a component following anything a `CgAttachment` is: an entity's point, a socket, a sequence's locator |
| `drawSize(w, h)`, `pivot(x, y)` | `DrawSize`, `Pivot` | its box, its desired size by default; the point of it on the attachment, the middle by default |
| `play(sequence)` | `PlayAnimation`, then removed on `OnAnimationFinished` | what its widget plays as it starts; released when it ends |
| `remove()` | `DestroyComponent` | released now, queued or drawn |

- **It starts at the layer's next tick**, before the frame's animations run, so its first draw is its animation's
  first frame, never its designer's values.
- **Its widget is pooled** (`CgUserWidgetPool`, Unreal's `FUserWidgetPool`) and handed out at its designer's values:
  set it up as you add it, and never keep it past `remove()` or its animation's end.
- **A level change releases every component**: their attachments were the old level's.

### 6.3 On a quad in the world

UMG's world-space `UWidgetComponent`: the widget painted into a picture shown on a quad standing in the world, turned
or facing the camera, hidden behind what is in front of it like any surface. It is laid out at its design size and
stretched over the quad, so give the quad the same aspect.

```java
CgUserWidget board = QUEST_BOARD.loaded().newInstance();
scene.quad(2f, 1.2f, null).widget(board, 500f, 300f).at(x, y + 2, z).rotation(facingSouth).spawn();
board.text("Title", "Gather 10 crystals");          // its picture painted again next frame
```

- Its picture is painted at the size the quad stands on screen, and again only when the widget changes (its text, its
  layout, a move or fade an animation writes) or that size steps.
- A text change shows a frame after a new glyph is first needed, as on the screen.

### 6.4 Painting it yourself

An instance paints onto any canvas, its box at the canvas's origin; the canvas's transform places and scales it:

```java
CgCanvas canvas = frame.canvas();               // a HUD stage's turn
canvas.save().translate(x, y).scale(scale, scale);
counter.paint(canvas);
canvas.restore();
```

**Its drawing is kept and replayed**, as a browser keeps a box's paint. A frame where nothing it shows changed draws
it from what was kept, and an animation that only **moves** it (translation, scale, shear, angle) or **fades** it
(render opacity, a colour's alpha, a border's content alpha) changes a value rather than drawing it again. Its first
move or fade paints it once more, into a node of its own ([`UI.md`](UI.md) § 12).

- **Drawn again** after a change it cannot replay: text, a style, a brush, visibility, a layout, a colour's red, green
  or blue, material parameters, a blur, the scale it is painted at.
- **Drawn every frame**: a widget holding a retainer box, or an SVG with a drop shadow. Each is a layer, a pass of its
  own. It costs what painting costs, plus one attempt to keep it every 64th frame.
- `repaints()` counts the paints that were not replays: a widget an animation only moves and fades adds none.

---

## 7. Animating it

A widget's animations are **sequences** played on the world's **widget sequencer**, Unreal's widget animations. They
tick with the interface, not the characters: a hit-stop never slows them, and they keep playing behind the pause menu.
They do follow the world's time dilation (`CgWorld.timeDilation`), unlike UMG's, so a dev slow-motion or frame step
slows the HUD with everything else; gameplay never sets it.

```yaml
# assets/mymod/sequences/hud/pop_in.yaml: the banner fades in over 0.4 s
_v: 1
tick_resolution: 1000
display_rate: 50
range: [0, 400]
roles:
  - { id: b1, name: number, kind: caster }
tracks:
  - id: t1
    kind: property
    binding: { role: number, node: Banner }        # this instance's widget named Banner
    property: colour_and_opacity
    sections:
      - { id: s1, range: [0, 400], value: { a: { keys: [[0, 0, linear], [400, 1, linear]] } } }
```

An instance plays its own animations, as UMG's `PlayAnimation`: one player per animation, every role bound to this
instance, on its layer's world.

```java
pop.playAnimation(POP_IN.loaded());                    // played again while it plays: restarts, never stacks
pop.playAnimation(SHAKE.loaded());                     // several at once, each on its own properties
pop.onAnimationFinished(FADE.loaded(), () -> HUD.remove(pop));   // bound once, heard at each end
pop.stopAnimation(SHAKE.loaded());                     // mid-play: what it wrote is put back
```

One animation keyed through several states can play just between two of them:

```java
double from = pop.isAnimationPlaying(RAMP.loaded()) ? pop.animationCurrentTime(RAMP.loaded()) : 0.4;
pop.playAnimationTo(RAMP.loaded(), from, 0.8, 1f);     // from where it is to 0.8 s, then holds there
// behind by more than a step: faster, so it still arrives in one step's time
pop.playAnimationTo(RAMP.loaded(), from, 0.8, (float) Math.max(1, (0.8 - from) / 0.4));
```

| Call | Unreal | Does |
|---|---|---|
| `playAnimation(animation)` | `PlayAnimation` | plays it, or restarts it from its start where it already plays |
| `playAnimationTo(animation, start, end, speed)` | `PlayAnimationTimeRange` | plays forward from `start` at `speed`, finishing and holding at `end` |
| `animationCurrentTime(animation)` | `GetAnimationCurrentTime` | its seconds while it plays; 0 once finished or stopped |
| `stopAnimation(animation)`, `stopAllAnimations()` | `StopAnimation`, `StopAllAnimations` | stops one still playing, putting back what it wrote |
| `isAnimationPlaying(animation)` | `IsAnimationPlaying` | playing and not yet at its end |
| `onAnimationFinished(animation, listener)` | `BindToAnimationFinished` | each time it reaches its end, its last frame applied |

### What an animation can change

| Property | Channels | Unreal |
|---|---|---|
| `translation`, `scale`, `shear` (degrees), `angle` (degrees) | x, y | `FWidgetTransform` |
| `render_opacity` | x | `RenderOpacity`, multiplied into everything under it |
| `colour_and_opacity` | r, g, b, a, linear | an image's, a text block's or a user widget's `ColorAndOpacity` |
| `content_colour_and_opacity` | r, g, b, a, linear | a border's `ContentColorAndOpacity` |
| `material_parameters` | x, y, z, w | its brush's UI material's `_Parameters` (§ 8) |
| `blur` | x | a retainer box's blur, a sigma in units (§ 8) |

- **The role binds the instance; `node` finds a widget in it by name.** A `user_widget` is reached whole: a track on
  it writes that instance's own transform and colour, never a widget inside it.
- **Its first frame is in force before it is drawn.**
- **A finished animation keeps what it wrote and steps aside**: its last frame stays, its finish is heard, and another
  animation on the same properties then writes them alone. A fade-out stays faded. Stopping one mid-play puts back
  what it wrote.
- **What an animation writes never lays the tree out again**; text, visibility and brushes do.
- `reset()` stops its animations too, and keeps what `onAnimationFinished` bound.
- A name the tree lacks fails the play, naming it.
- A sequence can be played on the widget sequencer by hand, its roles bound in a map:
  `CgSequencer.widgets(world).play(POP_IN.loaded(), Map.of("number", pop))`.

---

## 8. Drawing a part as one picture: the retainer box

Text in a style is drawn in layers: an outline under a fill, a shadow under both. Fade it, and each layer fades on its
own, so the outline shows through the half-faded fill. A **retainer box** first draws what it holds into a picture of
its own, then lays that picture down once, so a fade above it fades one picture, as UMG's Retainer Box does.

```yaml
widgets:
  NumberPanel:
    kind: border
    background: { draw_as: none }
    content: Number                    # the border's content colour fades the picture, whole
  Number: { kind: retainer_box, content: NumberText }
  NumberText: { kind: text_block, style: mymod:damage/gold, text: "0" }
```

- **A retainer box has no colour of its own**: fade it from above, with a border's content colour or a render opacity.
- **Its picture is padded** by how far its text draws past its box (`CgTextStyle.reach()`: the widest outline, the
  shadows, the slant), so a heavy outline or a wide glow is never cut at its edge.
- **It costs a pass every frame**: a widget holding one is painted every frame (§ 6.4).
- A retainer box inside another is not drawn: it fails at paint, naming it.

### Through a material: the effect

An `effect` is a UI material that draws the box's picture, sampling it at its `texture_parameter` (`Texture` unless
set): a glow, a silhouette, a dissolve.

```yaml
Glow: { kind: retainer_box, content: GlowText, effect: mymod:hud/silhouette }   # the same text's shape, added
```

```glsl
// assets/mymod/shaders/hud/silhouette.shader: the picture's shape in the box's colour (its material's state additive)
#domain ui
Material { Primitive Element }
Properties { Texture ("The box's picture", sampler2D) = "white" }
void surface(in CgUiInput i, inout CgUiSurface s) { s.color.a *= texture(Texture, i.uv).a; }
```

A UI material takes its tint in linear, as Slate's material shaders read the vertex colour.

### Per-widget material values

A brush's UI material may read four numbers per widget as its `vec4` property `_Parameters`
(`CgUserWidget.PARAMETERS`): an image's or a border's `material_parameters`. They are zero until
`materialParameters(name, x, y, z, w)` sets them or an animation keys them. Each widget draws through its own instance
of the material, as UMG animates a brush's parameters on a dynamic material instance; a material that declares no
`_Parameters` draws as it is.

```glsl
Properties { _Parameters ("A widget's material parameters", vec4) = (0.0, 0.0, 0.0, 0.0) }
void surface(in CgUiInput i, inout CgUiSurface s) { s.color.rgb *= 1.0 + _Parameters.x; }
```

```yaml
# an animation growing a streak's energy, its shader reading _Parameters.x
- { id: energy, kind: property, binding: { role: counter, node: Streak }, property: material_parameters,
    sections: [ { id: grows, range: [0, 24000], value: { x: { keys: [[0, 0.4, quad_out], [24000, 0.6, linear]] } } } ] }
```

### Blurred

A retainer box's `blur` blurs what it holds by a Gaussian of that sigma, in units, as CSS's `filter: blur()`: a count
that lands blurred and sharpens, a word dissolving away. Its effect is blurred with it.

```yaml
Number: { kind: retainer_box, content: NumberText, blur: 0 }     # sharp as designed; an animation keys it
```

```yaml
# the count lands at a sigma of 4 and sharpens in 0.18 s
- { id: sharpen, kind: property, binding: { role: counter, node: Number }, property: blur,
    sections: [ { id: sharpens, range: [0, 10800], value: { x: { keys: [[0, 4.0, quad_out], [10800, 0.0, linear]] } } } ] }
```

```java
counter.blur("Number", 4f);                  // from Java; 0 for sharp
CgWidget.retainerBox("Number").blur(4f)      // as built
```

- **Only a retainer box blurs**, since what it holds is one picture already. `blur` on any other widget fails, by name.
- **One axis**: `blur_axis: vertical` (or `horizontal`; `both` unless set) blurs along it alone, as a motion blur:
  `CgWidget.retainerBox("Number").blur(4f).blurAxis(CgWidget.BlurAxis.VERTICAL)`.
- **It spreads past the box unclipped**: its picture is padded by three sigma.
- **It runs a compute pass**: GL 4.3 and up, or Vulkan. A widget asset with a retainer box precaches the blur.
- To blur what is *behind* a widget instead (glass), use a canvas box's `backdropFilter` ([`UI.md`](UI.md) § 10).

---

## 9. Loading

- **A widget asset is ready with its UI materials built and its text styles' fonts read** (`CgWidgetPreparer`,
  `CgTextStylePreparer`), so no first draw compiles a shader or reads a font. A kit loads whole behind the loading
  screen unless its `preload.yaml` says `all: false` ([`LOADING.md`](LOADING.md)): there is nothing of your own to load.
- **A tree built in Java** is no asset: precache its brushes' UI materials in a load task
  (`CgMaterial.of(definition).precache()`), or their first draw builds them.

---

## 10. Easy to get wrong

- **A brush's or an effect's material must be a UI material** (`#domain ui`): another fails at load, naming the
  widget.
- **A pooled instance comes back reset**; one you pool yourself needs `reset()` before its next use.
- **Find the widget sequencer with `CgSequencer.widgets(world)`**, never `world.subsystem(CgSequencer.class)`, which
  returns whichever sequencer was added first. A dedicated server's world has none: adding one throws.
- **Fade with alpha or render opacity.** A colour animated in red, green or blue paints the widget every frame; one
  animated in alpha alone replays.
- **Painting allocates nothing**, and layout runs only on a change (text, visibility, a brush, the size it is given);
  then only a wrapped or justified text block allocates, laying its text out again.

---

## Where the code is

| Type | Role |
|---|---|
| `widget.CgWidgetTree`, `CgWidget`, `CgWidgetSlot` (with `api.paint`'s `CgBrush` and `CgColor`) | the tree as designed, its builders |
| `widget.CgUserWidget` | an instance: its state, layout, painting (kept and replayed), its animations |
| `widget.CgScreenWidgetLayer` | widgets on the screen, by z-order |
| `widget.CgWorldWidgetLayer`, `CgWidgetComponent`, `CgUserWidgetPool` | widgets over points in the world, and the pool |
| `widget.CgUiScale` | a kit's `ui.yaml`, Unreal's DPI curve |
| `widget.CgWidgetCodec`, `CgWidgetPreparer`; `text.style.CgTextStylePreparer` | reading the asset; what makes it ready |
| `sequence.CgSequencer` (`widgets(world)`) | the widget sequencer |

Package guide: `widget/CLAUDE.md`. Tests: `CgUserWidgetTest`, `CgWidgetCodecTest`, `CgWorldWidgetLayerTest`,
`CgScreenWidgetLayerTest`.
