# Widgets: a HUD element as an asset

A damage number popping over a target, a subtitle with its speaker's name: an interface element is a **widget tree**,
a tree of named widgets laid out by Slate's rules and painted onto a canvas. It is Unreal's UMG widget blueprint: its
`UWidgetTree`, its panels and their slots. Your code only makes one, sets its text and plays its animations, as
`CreateWidget`, `SetText` and `PlayAnimation` do; the animations find its parts by name.

## A first widget, in Java

```java
// once: the tree, shared by every use of it
static final CgTextStyle GOLD = ...;                                   // a text style (text styles in assets.md)
static final CgWidgetTree POP = CgWidgetTree.of("mymod:hud/pop", CgWidget.canvas("Root")
        .child(CgWidget.text("Number").style(GOLD).text("0")
                .slot(CgWidgetSlot.builder().anchors(0.5f, 0.5f).alignment(0.5f, 0.5f).autoSize(true).build()))
        .child(CgWidget.image("Banner")
                .brush(CgWidgetBrush.texture("mymod:textures/hud/banner.png", 224f, 56f))
                .slot(CgWidgetSlot.builder().anchors(0.5f, 0.5f).alignment(0.5f, 0f).offsets(0f, 30f, 224f, 56f).build())
                .colour(new CgLinearColour(1f, 1f, 1f, 0f)))                // hidden until its animation fades it in
        .build());

// once: where it draws, over a point in the world
static final CgWorldWidgetLayer POPS = CgWorldWidgetLayer.builder("mymod:pops").kit("mymod").build();

// a hit: over the target's middle, released to the layer's pool when its animation ends
CgWidgetComponent pop = POPS.add(POP, CgEntityAttachments.of(targetId));
pop.widget().text("Number", "1,250");
pop.play(POP_ANIMATION.loaded(), "number");
```

The layer places, orders, scales and draws it every frame ([below](#over-a-point-in-the-world-a-world-widget-layer));
a widget that fills the screen instead is drawn by hand ([On the screen](#on-the-screen)).

## The same widget, as data

```yaml
# assets/mymod/widgets/hud/pop.yaml: the widget mymod:hud/pop
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
    colour_and_opacity: [1, 1, 1, 0]
    brush: { texture: mymod:textures/hud/banner.png, image_size: [224, 56] }
```

```java
static final CgAssetRef<CgWidgetTree> POP = CgVfxAssets.ref(CgLookKinds.WIDGET, "mymod:hud/pop");
CgUserWidget pop = POP.loaded().newInstance();
```

`widgets` maps every widget's name to it, and a panel lists its children by name. Names are Unreal's (letters,
digits, `_`) and unique in the tree: a sequence's `node` names one. A widget no panel reaches, a name a panel lists that
`widgets` lacks, or a widget two panels list fails at load, naming it.

## The widgets

| Kind | Unreal | Holds | Its size |
|---|---|---|---|
| `canvas` | `UCanvasPanel` | `children`, each placed by its slot's anchors and offsets, painted in `z_order`, then list order | the largest child's reach |
| `horizontal_box` | `UHorizontalBox` | `children` in a row, left to right | its children's widths and paddings added |
| `border` | `UBorder` | `content`, one widget, padded; a `background` brush, its `brush_colour`; `content_colour_and_opacity` tints everything inside | its content's and its padding |
| `retainer_box` | `URetainerBox` | `content`, one widget filling it, painted at full opacity into an image of its own and drawn as one quad; `effect`, a UI material that draws that image (its `texture_parameter` sampler, `Texture` unless set) | its content's |
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

A **slot** is how a widget's parent lays it out, and what it holds depends on the parent:

```yaml
# in a canvas: anchors on the parent, 0 to 1; on an axis whose anchors meet, offsets are the position and the size
slot: { anchors: [1, 1], offsets: [115, 48, 224, 56], alignment: [1, 1] }      # bottom-right corner 115 left, 48 up
slot: { anchors: [[0, 0.5], [1, 0.5]], offsets: [0, 0, 0, 60] }                # stretched across, 60 high
slot: { anchors: [0.5, 0.5], alignment: [0.5, 0.5], auto_size: true }           # centred, at its own size

# in a horizontal box or a border
slot: { padding: [-13, 0, -13, 0], vertical_alignment: centre }                 # overlapping its neighbours by 13
slot: { size: { fill: 1 } }                                                      # a share of what auto children leave
```

- An unset field takes its slot class's default, as UMG's: a canvas child is `[0, 0, 100, 30]` at the top left; a
  border pads its content by `[4, 2, 4, 2]`; a box child fills its row's height.
- A **brush** is `texture` or `material` (a material of the UI domain: its shader tagged `"Domain" = "UI"`), its `image_size`
  (`[32, 32]` by default), `tint` and `draw_as: image | none`.
  With neither texture nor material it still draws, a box of its tint, as Slate's does: a border's background is that
  until set, so `background: { draw_as: none }` hides it.
- **Colours are linear**, `[r, g, b, a]`, as Unreal's `FLinearColor`: they multiply down the tree in linear and are
  encoded to sRGB where drawn. In Java, `CgLinearColour.argb(0xFFFFC040)` decodes a colour picked by eye.

## A variant: a child file

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

## Fading as one image: the retainer box

Text in a style is layers: an outline under a fill, a shadow under both. Faded, each layer fades on its own, so the
outline shows through the half-faded fill. A retainer box paints what it holds into an image first, then draws that
image as one quad, so a fade above it fades one picture, as UMG's Retainer Box does:

```yaml
widgets:
  Number_Border:
    kind: border
    background: { draw_as: none }
    content: Number                    # the border's content colour fades it: the one image, whole
  Number: { kind: retainer_box, content: Number_Text }
  Number_Text: { kind: text_block, style: mymod:damage/gold, text: "0" }
  Glow: { kind: retainer_box, content: Glow_Text, effect: mymod:hud/silhouette }   # the same text's shape, added
```

An `effect` is a material of the UI domain whose shader samples the retained image at its texture parameter:

```glsl
// assets/mymod/shaders/hud/silhouette.shader: its tags name the UI domain
Tags { "RenderType" = "Transparent" "Domain" = "UI" }
Properties { Texture ("The retained image", sampler2D) = "white" }
// ...in the fragment: the image's shape in the quad's colour, added
fragColor = vec4(i.color.rgb * i.color.a * texture(Texture, i.uv).a, 0.0);
```

- **Retain every instance before painting any**: `retain(painter)` paints each retainer box's content into the
  painter's retained image, which must be drawn before anything on the canvas reads it.
- Give the painter room: `retaining(width, height, margin)` sizes its retained image in units, with `margin` around
  each region for what draws past its box, an outline or a slant. A frame needing more fails, naming the size.
- A retainer box has no colour of its own: what fades it is above it, a border's content colour or a render opacity.
- A UI material takes its tint in linear, as Slate's material shaders read the vertex colour.

## Animating it

A widget's animations are sequences on the world's **widget sequencer**, Unreal's widget animation: UMG plays them on
a linker of their own, ticked from the interface's frame. So they run on real time, a hit-stop or slow motion never
slows them, and they keep playing behind the pause menu.

```java
CgSequencer gameplay = CgSequencer.of(CgWorld.client());        // moves, cues, cameras: dilated, paused with the game
CgSequencer ui = CgSequencer.widgets(CgWorld.client());          // added on first ask; client only

CgSequencePlayer player = ui.play(POP_ANIMATION.loaded(), Map.of("number", pop));
player.jumpTo(0);                                                // replay a pooled pop: nothing allocated
if (player.finished()) player.stop();                            // a finished player is evaluated until stopped
```

```yaml
# assets/mymod/sequences/hud/pop.yaml: the banner fades in over 0.4 s
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

| Property | Channels | Unreal |
|---|---|---|
| `translation`, `scale`, `shear` (degrees), `angle` (degrees) | x, y | `FWidgetTransform` |
| `render_opacity` | x | `UWidget::RenderOpacity`, multiplied into everything under it |
| `colour_and_opacity` | r, g, b, a, linear | an image's, a text block's or a user widget's `ColorAndOpacity` |
| `content_colour_and_opacity` | r, g, b, a, linear | a border's `ContentColorAndOpacity` |

- **The role binds the instance**, and `node: <name>` finds a widget in its tree, as `FindRuntimeObject` does. A
  `user_widget` is reached whole: a track on it writes that instance's own transform and colour, never a widget
  inside it, which its own animations play on.
- What an animation writes is render-only and never lays the tree out again; text, visibility and brushes do.
- A name the tree lacks fails the play, naming it.
- Find the widget sequencer by `CgSequencer.widgets(world)`, never `world.subsystem(CgSequencer.class)`, which returns
  whichever was added first. A dedicated server's world takes none: adding one throws.

## Over a point in the world: a world widget layer

A damage number, a name plate, a marker: a widget that follows something in the world is a component on a
`CgWorldWidgetLayer`, Unreal's screen-space `UWidgetComponent` on its world widget layer. Each frame the layer puts
each component's pivot on its attachment's point as the camera sees it, at its draw size or its desired size, the
nearer over the farther, in the kit's UI scale; it skips one behind the camera, and one whose attachment is gone stays
where it was last seen.

```java
static final CgWorldWidgetLayer NUMBERS = CgWorldWidgetLayer.builder("mymod:damage_numbers")
        .kit("mymod")                       // its ui.yaml scales it
        .retaining(1024f, 1024f, 20f)       // its retainer boxes' image, in widget units
        .retainedScale(1.5f)                // its animations scale it up to 1.5: retained at that, never magnified
        .most(8, 16)                        // eight at once, sixteen more waiting, the newest started first
        .build();

CgWidgetComponent pop = NUMBERS.add(POP.loaded(), CgEntityAttachments.of(targetId));
pop.widget().text("Number", "1,250").style("Number", critical ? ORANGE : GOLD);
pop.play(CRITICAL.loaded(), "number");      // released when it ends
```

```java
// a name plate: its bottom middle on the head, until the mob goes
CgWidgetComponent plate = PLATES.add(PLATE.loaded(), CgEntityAttachments.of(mobId, CgEntityAttachments.HEAD))
        .pivot(0.5f, 1f).drawSize(160f, 24f);
plate.widget().text("Name", name);
plate.remove();                             // it plays nothing, so it stays until removed
```

- **It starts at the layer's next tick**, before the frame's widget animations evaluate, so it never shows its
  designer's values for a frame, as Unreal evaluates a widget animation's first frame when it plays.
- **Its widget is pooled** (`CgUserWidgetPool`, Unreal's `FUserWidgetPool`): set it up as you add it, and never keep it
  past `remove()` or its animation's end.
- **A level change releases every component**: their attachments were the old level's.
- **Load the tree as an asset**: its UI materials and its text styles' faces are built before it is READY, so no
  first draw compiles or reads one.

## On the screen

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

CgRenderStage.HUD.register(CgRenderStage.VIEWPORT_WIDGETS, frame -> {
    float scale = CgUiScale.of("mymod").scale(frame.host().width(), frame.host().height());   // 2560 x 1440: 1.333
    PAINTER.begin(frame.canvas(), frame.recording(), scale);
    hud.allot(frame.host().width() / scale, frame.host().height() / scale);   // the whole screen, in UI units
    hud.retain(PAINTER);                                                     // every instance, before any paints
    hud.paint(PAINTER, placement.scaling(scale));
});
```

## Things to know

- **One painter per HUD, kept**: `CgCanvasWidgetPainter` loads a brush's texture at its first draw and keeps it.
- **A brush's or an effect's material must be of the UI domain**: another fails at load, naming the widget.
- **A widget asset is READY with its UI materials built and its text styles' faces read.** A tree built in Java
  isn't: `painter.prepare(tree)` in a load task builds its UI materials.
- **A string id** (`<ns>:text/<id>`) is read in the player's language when the tree lays out; a missing string shows
  its id, as Unreal shows a missing string's key.
- **`reset()`** puts an instance back to its designer's values, nested widgets too: call it before a pooled instance's
  next use.
- **Paint allocates nothing**; laying out allocates only where a wrapped or justified text block lays its text out
  again.
