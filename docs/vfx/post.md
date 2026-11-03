# Post: a game's stage

## A game's stage

A game's arena is graded as a whole: its level holds post volumes and a height fog that every frame of a fight is drawn
through. A **stage** is that environment as an asset, `stages/<name>.yaml`, and your mod places it over Minecraft's world
while it wants the game's arena look, then takes it off. A move never places one; whatever runs the fight does.

```yaml
# assets/mymod/stages/arena.yaml
_v: 1
anchor: [0, 0, 7849]                    # the game's point your placement lands on, in the kit's units
volumes:
  - post:                               # a post block: the keys a sequence's post spawnable takes
      tonemapper: legacy
      vignette: 0.5
      bloom: 1
      bloom_threshold: 1
      materials:
        - { name: tint, material: mymod:post/arena_tint }
        - { name: overlay, material: mymod:post/arena_overlay,
            values: { SunVector: [0.81, -0.47, 0.34, 0] } }   # a dynamic instance: its parameters set
    unbound: true                       # everywhere; else at, radius and blend_radius place it
fog:                                    # Unreal's exponential height fog, as its component writes it
  density: 0.002
  height_falloff: 0.1
  height: 15799                         # the game's up: where the density is given
  inscattering_colour: [0.41, 0.89, 1]  # linear
  max_opacity: 0.65
  start_distance: 30000
  cutoff_distance: 4.0e6                # 0 for none
```

Load it with your other assets ahead of the fight, then place it where the fight's ground is:

```java
static final CgAssetRef<CgStageDefinition> ARENA = CgVfxAssets.ref(CgStageKinds.STAGE, "mymod:arena");

ARENA.request(CgLoadPriority.MOVE);                      // when a fight is coming: compiled, its programs built
CgStage stage = CgStage.place(ARENA.get(), x, y, z);    // the anchor at (x, y, z), on the render thread
stage.weight(0.5f);                                      // fading in: volumes, post materials and fog together
stage.close();                                           // the fight is over
```

The same stage written in Java: its `tree()` is the file above, in the same units.

```java
CgStageDefinition.Builder arena = CgStageDefinition.builder("mymod:arena").anchor(0, 0, 7849)
        .volume(new CgPostSettings().tonemap().vignette(0.5f), v -> v.unbound()
                .material("mymod:post/arena_tint")
                .material("mymod:post/arena_overlay", Map.of("SunVector", List.of(0.81, -0.47, 0.34, 0))))
        .fog(f -> f.density(0.002).heightFalloff(0.1).height(15799).inscatteringColour(0.41, 0.89, 1).maxOpacity(0.65));
String yaml = CgAssetYaml.write(arena.tree(), CgStageKinds.STAGE);
```

A height fog of your own, without a stage, is `CgHeightFog`, in blocks:

```java
CgRenderStage.Registration fog = new CgHeightFog().density(0.2f).heightFalloff(10f).height(64)
        .colour(0.41f, 0.89f, 1f).maxOpacity(0.65f).show();
fog.close();
```

- **The anchor is where the stage sits.** Every place in it (a bounded volume's `at`, the fog's `height`) keeps its
  distance from the anchor, so placing the anchor on the fight's ground puts the game's fog at the height it had over
  the game's ground. Absent, it is the game's origin.
- **`density` and `height_falloff` are the component's**, as an Unreal level saves them: Unreal divides both by 1000
  before drawing, and so does the engine. Lengths are in the kit's units.
- **The fog is drawn over the lit world before translucency**, as Unreal draws it, so smoke and glows drawn after it are
  not fogged. Directional inscattering, a cubemap and volumetric fog are not drawn: a stage naming one fails at load.
- **Its volumes blend with every other volume by priority**, a move's included. A kit's look still grades only the
  kit's own draws ([`effects.md`](effects.md) § *A kit's own look*).
- **A post material's `values` and `textures` make it a child of its material**, so a parameter the material does not
  declare fails at load, naming it. A `screen_point` fails in a stage: nothing in a level is followed on screen.
- **A placed stage keeps the definition it was placed with**: a reload reaches the next placement.
- A dedicated server compiles a stage with no post materials and draws nothing.
