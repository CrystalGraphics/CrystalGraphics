# Widgets: a HUD element as an asset

Read this before your mod draws an interface element: a damage number popping over a target, a name plate over a head,
a subtitle with its speaker's name, a HUD panel. An element is a **widget tree**, a tree of named widgets laid out by
Slate's rules and painted onto a canvas. It is Unreal's UMG widget blueprint: its `UWidgetTree`, its panels and their
slots. Your code only makes one, sets its text and plays its animations, as `CreateWidget`, `SetText` and
`PlayAnimation` do. The animations find its parts by name, and the layout and look live in its file, never in code.

Package: `com.crystalgraphics.widget`. The guide for code inside it is its `CLAUDE.md`; this page is for code that uses
it. Text styles, which every text block draws in, are `vfx/assets.md`; the canvas everything lands on is
[`CANVAS.md`](CANVAS.md).

It comes in layers. Take the first that does what you need:

| Layer | For | Section |
|---|---|---|
| **`CgWorldWidgetLayer`** | a widget over a point in the world, following it: a damage number, a name plate, a marker | [7](#7-over-a-point-in-the-world-a-world-widget-layer) |
| **`CgCanvasWidgetPainter`** | a widget on the screen: a HUD panel, a subtitle | [8](#8-on-the-screen) |
| **`CgUserWidget`** | an instance you place and paint yourself | [8](#8-on-the-screen) |

---

## 1. The short version

```yaml
# assets/mymod/widgets/hud/pop.yaml: the widget mymod:hud/pop
_v: 1
root: Root
widgets:
  Root: { kind: canvas, children: [Number] }
  Number:
    kind: text_block
    slot: { anchors: [0.5, 0.5], alignment: [0.5, 0.5], auto_size: true }
    style: mymod:hud/gold
    text: "0"
```

```java
static final CgAssetRef<CgWidgetTree> POP = CgVfxAssets.ref(CgLookKinds.WIDGET, "mymod:hud/pop");
static final CgAssetRef<CgSequenceDefinition> POP_IN = CgVfxAssets.ref(CgSequenceKinds.SEQUENCE, "mymod:hud/pop_in");
static final CgWorldWidgetLayer POPS = CgWorldWidgetLayer.builder("mymod:pops").kit("mymod").build();

// a hit: over the target's middle, released when its animation ends
CgWidgetComponent pop = POPS.add(POP.loaded(), CgEntityAttachments.of(targetId));
pop.widget().text("Number", "1,250");
pop.play(POP_IN.loaded(), "number");
```

That is a whole damage number. The asset loads with your kit behind the loading screen; the layer places it on the
target every frame, plays its animation, and hands it back to its pool when the animation ends.

---

## 2. A widget, as data and in Java

`widgets` maps every widget's name to it, and a panel lists its children by name. Names are Unreal's (letters, digits,
`_`) and unique in the tree: a sequence's `node` names one.

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
    colour_and_opacity: [1, 1, 1, 0]                     # hidden until its animation fades it in
    brush: { texture: mymod:textures/hud/banner.png, image_size: [224, 56] }
```

The same tree built in Java, for a widget no file holds:

```java
static final CgWidgetTree POP = CgWidgetTree.of("mymod:hud/pop", CgWidget.canvas("Root")
        .child(CgWidget.text("Number").style(GOLD).text("0")
                .slot(CgWidgetSlot.builder().anchors(0.5f, 0.5f).alignment(0.5f, 0.5f).autoSize(true).build()))
        .child(CgWidget.image("Banner")
                .brush(CgWidgetBrush.texture("mymod:textures/hud/banner.png", 224f, 56f))
                .slot(CgWidgetSlot.builder().anchors(0.5f, 0.5f).alignment(0.5f, 0f).offsets(0f, 30f, 224f, 56f).build())
                .colour(new CgLinearColour(1f, 1f, 1f, 0f)))
        .build());
```

- A widget no panel reaches, a name a panel lists that `widgets` lacks, or a widget two panels list fails at load,
  naming it.
- An instance holds what changes per use; the tree is shared by every use of it: `tree.newInstance()`, or a layer's
  pool.

---

## 3. The widgets

| Kind | Unreal | Holds | Its size |
|---|---|---|---|
| `canvas` | `UCanvasPanel` | `children`, each placed by its slot's anchors and offsets, painted in `z_order`, then list order | the largest child's reach |
| `horizontal_box` | `UHorizontalBox` | `children` in a row, left to right | its children's widths and paddings added |
| `border` | `UBorder` | `content`, one widget, padded; a `background` brush, its `brush_colour`; `content_colour_and_opacity` tints everything inside | its content's and its padding |
| `retainer_box` | `URetainerBox` | `content`, one widget filling it, painted into an image of its own and drawn as one quad; `effect`, a UI material that draws that image ([5](#5-fading-as-one-image-the-retainer-box)) | its content's |
| `image` | `UImage` | a `brush`; `colour_and_opacity` | its brush's `image_size` |
| `text_block` | `UTextBlock` | `text`, a string id or the literal text; its `style`; `colour_and_opacity`; `justification`; `wrap` | its text's, in its style |
| `user_widget` | a nested `UUserWidget` | `widget`, another widget asset; `colour_and_opacity` | its tree's |

Every widget has UMG's common properties:

| Field | Holds |
|---|---|
| `transform` | `{ translation, scale, shear, angle }`: after layout, about `pivot`, so it never moves a sibling. The angle is in degrees, clockwise |
| `pivot` | a fraction of its own size; the middle, `[0.5, 0.5]`, by default |
| `render_opacity` | 0 to 1, multiplied into it and everything under it |
| `visibility` | `visible`; `hidden` keeps its space and draws nothing; `collapsed` takes no space |

### Slots

A slot is how a widget's parent lays it out, and what it holds depends on the parent:

```yaml
# in a canvas: anchors on the parent, 0 to 1; on an axis whose anchors meet, offsets are the position and the size
slot: { anchors: [1, 1], offsets: [115, 48, 224, 56], alignment: [1, 1] }      # bottom-right corner 115 left, 48 up
slot: { anchors: [[0, 0.5], [1, 0.5]], offsets: [0, 0, 0, 60] }                # stretched across, 60 high
slot: { anchors: [0.5, 0.5], alignment: [0.5, 0.5], auto_size: true }           # centred, at its own size

# in a horizontal box or a border
slot: { padding: [-13, 0, -13, 0], vertical_alignment: centre }                 # overlapping its neighbours by 13
slot: { size: { fill: 1 } }                                                      # a share of what auto children leave
```

An unset field takes its slot class's default, as UMG's: a canvas child is `[0, 0, 100, 30]` at the top left; a border
pads its content by `[4, 2, 4, 2]`; a box child fills its row's height.

### Brushes and colours

- **A brush** is a `texture` or a `material` (of the UI domain: its shader tagged `"Domain" = "UI"`), its `image_size`
  (`[32, 32]` by default), `tint` and `draw_as: image | none`. With neither texture nor material it still draws, a box
  of its tint, as Slate's does: a border's background is that until set, so `background: { draw_as: none }` hides it.
- **Colours are linear**, `[r, g, b, a]`, as Unreal's `FLinearColor`: they multiply down the tree in linear and are
  encoded to sRGB where drawn. In Java, `CgLinearColour.argb(0xFFFFC040)` decodes a colour picked by eye.
- **A string id** (`<ns>:text/<id>`) is read in the player's language when the tree lays out; a missing string shows
  its id, as Unreal shows a missing string's key.

---

## 4. A variant: a child file

A child changes its parent's widgets by name, field by field; a widget whose `kind` differs replaces it whole;
`remove` drops widgets nothing reaches any more:

```yaml
# assets/mymod/widgets/hud/damage_pop.yaml: a game's damage number, its digit images one text block
_v: 1
parent: kakarot_kit:Art/UI/Battle_Dmg/Battle_Dmg
widgets:
  Number_Center: { kind: text_block, style: mymod:damage/gold, text: "0" }
remove: [Ins_Dmg00_08, Ins_Dmg00_07, Ins_Dmg00_00]
```

The parent is a hard reference: it must load for the child to.

---

## 5. Fading as one image: the retainer box

Text in a style is layers: an outline under a fill, a shadow under both. Faded, each layer fades on its own, so the
outline shows through the half-faded fill. A retainer box paints what it holds into an image first, then draws that
image as one quad, so a fade above it fades one picture, as UMG's Retainer Box does:

```yaml
widgets:
  NumberPanel:
    kind: border
    background: { draw_as: none }
    content: Number                    # the border's content colour fades it: the one image, whole
  Number: { kind: retainer_box, content: NumberText }
  NumberText: { kind: text_block, style: mymod:damage/gold, text: "0" }
  Glow: { kind: retainer_box, content: GlowText, effect: mymod:hud/silhouette }   # the same text's shape, added
```

An `effect` is a material asset of the UI domain whose shader samples the retained image at its `texture_parameter`
(`Texture` unless set):

```glsl
// assets/mymod/shaders/hud/silhouette.shader
Tags { "RenderType" = "Transparent" "Domain" = "UI" }
Properties { Texture ("The retained image", sampler2D) = "white" }
// ...in the fragment: the image's shape in the quad's colour, added
fragColor = vec4(i.color.rgb * i.color.a * texture(Texture, i.uv).a, 0.0);
```

- A retainer box has no colour of its own: what fades it is above it, a border's content colour or a render opacity.
- A UI material takes its tint in linear, as Slate's material shaders read the vertex colour.
- Every instance a frame draws retains before any paints: the retained image is drawn before anything on the canvas
  reads it. A layer does this for you; by hand, `retain` every instance, then `paint` every instance.
- The retained image is sized in widget units (`retaining(width, height, margin)`), `margin` round each region for
  what draws past its box. A frame needing more fails, naming the size to give.

---

## 6. Animating it

A widget's animations are sequences on the world's **widget sequencer**, Unreal's widget animation: UMG plays them on
a linker of their own, ticked from the interface's frame. A character's hit-stop never slows them, and they keep
playing behind the pause menu. They do follow the world's time dilation (`CgWorld.timeDilation`), unlike UMG's, so a
dev slow-motion or frame step slows the HUD with everything else; gameplay never sets it.

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
    binding: { role: number, node: Banner }
    property: colour_and_opacity
    sections:
      - { id: s1, range: [0, 400], value: { a: { keys: [[0, 0, linear], [400, 1, linear]] } } }
```

```java
CgSequencer ui = CgSequencer.widgets(CgWorld.client());           // added on first ask; client only
CgSequencePlayer player = ui.play(POP_IN.loaded(), Map.of("number", pop));
```

| Property | Channels | Unreal |
|---|---|---|
| `translation`, `scale`, `shear` (degrees), `angle` (degrees) | x, y | `FWidgetTransform` |
| `render_opacity` | x | `UWidget::RenderOpacity`, multiplied into everything under it |
| `colour_and_opacity` | r, g, b, a, linear | an image's, a text block's or a user widget's `ColorAndOpacity` |
| `content_colour_and_opacity` | r, g, b, a, linear | a border's `ContentColorAndOpacity` |

- **The role binds the instance**, and `node: <name>` finds a widget in its tree, as `FindRuntimeObject` does. A
  `user_widget` is reached whole: a track on it writes that instance's own transform and colour, never a widget inside
  it, which its own animations play on.
- **Its first frame is in force before it is drawn**, and **a finished animation holds its last frame** until it is
  stopped, as UMG's player holds its last valid frame: a fade-out stays faded. Stopping puts back what it wrote.
- What an animation writes is render-only and never lays the tree out again; text, visibility and brushes do.
- A name the tree lacks fails the play, naming it.

---

## 7. Over a point in the world: a world widget layer

A widget that follows something in the world is a component on a `CgWorldWidgetLayer`, Unreal's screen-space
`UWidgetComponent` on its world widget layer. Each frame the layer puts each component's pivot on its attachment's
point as the camera sees it, at its draw size or its desired size, the nearer over the farther, in the kit's UI scale.
It skips one behind the camera, and one whose attachment is gone stays where it was last seen.

```java
static final CgWorldWidgetLayer NUMBERS = CgWorldWidgetLayer.builder("mymod:damage_numbers")
        .kit("mymod")                       // its ui.yaml scales it (§ 8)
        .retaining(1024f, 1024f, 20f)       // its retainer boxes' image, in widget units
        .retainedScale(1.5f)                // its animations scale it up to 1.5: retained at that, never magnified
        .most(8, 16)                        // eight at once, sixteen more waiting, the newest started first
        .build();

CgWidgetComponent pop = NUMBERS.add(POP.loaded(), CgEntityAttachments.of(targetId));
pop.widget().text("Number", "1,250").style("Number", critical ? ORANGE : GOLD);
pop.play(critical ? CRITICAL.loaded() : NORMAL.loaded(), "number");   // released when it ends
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
| `play(sequence, role)` | `PlayAnimation`, then removed on `OnAnimationFinished` | what it plays as it starts, its widget bound as `role`; released when it ends |
| `remove()` | `DestroyComponent` | released now, queued or drawn |

- **It starts at the layer's next tick**, before the frame's widget animations evaluate, so its first draw is its
  animation's first frame, never its designer's values.
- **Its widget is pooled** (`CgUserWidgetPool`, Unreal's `FUserWidgetPool`), handed out at its designer's values: set
  it up as you add it, and never keep it past `remove()` or its animation's end.
- **A level change releases every component**: their attachments were the old level's.

---

## 8. On the screen

A widget on the screen fills it in **UI units**: the screen's pixels over the kit's UI scale, as `AddToViewport` sizes
a user widget over its DPI scale. A kit sets the scale in `ui.yaml`; without one it is Unreal's default, 1 at 1080
pixels on the shortest side.

```yaml
# assets/mymod/ui.yaml
_v: 1
rule: shortest_side                                        # or longest_side, horizontal, vertical
curve: [[480, 0.444], [720, 0.666], [1080, 1], [8640, 8]]   # side in pixels to scale: linear, held past its ends
application_scale: 1
```

```java
static final CgCanvasWidgetPainter PAINTER = new CgCanvasWidgetPainter("mymod.hud", CgCanvasMaterials.SCREEN);
static final Matrix4f PLACEMENT = new Matrix4f();

CgRenderStage.HUD.register(CgRenderStage.VIEWPORT_WIDGETS, frame -> {
    float scale = CgUiScale.of("mymod").scale(frame.host().width(), frame.host().height());   // 2560 x 1440: 1.333
    PAINTER.begin(frame.canvas(), frame.recording(), scale);
    hud.allot(frame.host().width() / scale, frame.host().height() / scale);   // the whole screen, in UI units
    hud.retain(PAINTER);                                                     // every instance, before any paints
    hud.paint(PAINTER, PLACEMENT.scaling(scale));
});
```

- **One painter per HUD, kept**: it loads a brush's texture at its first draw and keeps it.
- A widget's size is its root's desired size unless `allot` gives one; `layout(painter, out)` answers it.

---

## 9. Loading

- **A widget asset is READY with its UI materials built and its text styles' faces read** (`CgWidgetPreparer`,
  `CgTextStylePreparer`), so no first draw compiles a shader or reads a font. A kit loads whole behind the loading
  screen unless its `preload.yaml` says `all: false` ([`LOADING.md`](LOADING.md)): nothing of your own to load.
- **A tree built in Java** is no asset: `painter.prepare(tree)` in a load task builds its UI materials.

---

## 10. Easy to get wrong

- **A brush's or an effect's material must be of the UI domain**: another fails at load, naming the widget.
- **A pooled instance comes back reset**; one you pool yourself needs `reset()` before its next use.
- **Find the widget sequencer by `CgSequencer.widgets(world)`**, never `world.subsystem(CgSequencer.class)`, which
  returns whichever sequencer was added first. A dedicated server's world takes none: adding one throws.
- **Retain before painting**, across every instance of a frame: a retained region opened after a draw onto the canvas
  fails.
- **Paint allocates nothing**; laying out allocates only where a wrapped or justified text block lays its text out
  again. Layout runs on change alone: text, visibility, a brush, an allotted size.

---

## Where the code is

| Package / type | Role |
|---|---|
| `widget.CgWidgetTree`, `CgWidget`, `CgWidgetSlot`, `CgWidgetBrush`, `CgLinearColour` | the tree as designed, its builders |
| `widget.CgUserWidget` | an instance: live state, Slate's layout, retain and paint, a sequence target |
| `widget.CgWorldWidgetLayer`, `CgWidgetComponent`, `CgUserWidgetPool` | widgets over points in the world, and the pool |
| `widget.CgWidgetPainter`, `CgCanvasWidgetPainter` | where leaves go: the canvas, retained images, UI materials |
| `widget.CgUiScale` | a kit's `ui.yaml`, Unreal's DPI curve |
| `widget.CgWidgetCodec`, `CgWidgetPreparer`; `text.style.CgTextStylePreparer` | the asset's compiler; what makes it READY |
| `sequence.CgSequencer` (`widgets(world)`) | the widget sequencer |

Package guide: `widget/CLAUDE.md`. Gates: `CgUserWidgetTest`, `CgWidgetCodecTest`, `CgWorldWidgetLayerTest`.
