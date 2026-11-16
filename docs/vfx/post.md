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

A stage compiles to a prefab (Unreal's packed level actor): its anchor is the root node, each volume a post node
(`volume0`, `volume1` ... in file order), its fog a fog node (`fog`). Load it with your other assets ahead of the fight,
then spawn it where the fight's ground is:

```java
static final CgAssetRef<CgPrefab> ARENA = CgVfxAssets.ref(CgStageKinds.STAGE, "mymod:arena");

ARENA.request(CgLoadPriority.MOVE);                                       // when a fight is coming: compiled, its programs built
CgPrefabInstance arena = CgScene.client().spawn(ARENA.get()).at(x, y, z).spawn();   // the anchor at (x, y, z), render thread
arena.post("volume0").weight(0.5f);                                       // a volume and its post materials, halfway
arena.fog("fog").weight(0.5f);
arena.remove();                                                           // the fight is over
```

The same stage written in Java: its `tree()` is the file above, in the same units.

```java
CgStageAssets.Builder arena = CgStageAssets.builder("mymod:arena").anchor(0, 0, 7849)
        .volume(new CgPostSettings().tonemap().vignette(0.5f), v -> v.unbound()
                .material("mymod:post/arena_tint")
                .material("mymod:post/arena_overlay", Map.of("SunVector", List.of(0.81, -0.47, 0.34, 0))))
        .fog(f -> f.density(0.002).heightFalloff(0.1).height(15799).inscatteringColour(0.41, 0.89, 1).maxOpacity(0.65));
String yaml = CgAssetYaml.write(arena.tree(), CgStageKinds.STAGE);
```

A prefab file of your own takes the same nodes directly: a node's `post:` block (its `settings.materials` too), `fog:`
and `light:`.

A height fog of your own, without a stage, is a scene component, in blocks, its height where it stands:

```java
CgHeightFogComponent fog = CgScene.client().heightFog().at(0, 64, 0).density(0.2f).heightFalloff(10f)
        .colour(0.41f, 0.89f, 1f).maxOpacity(0.65f).spawn();
fog.remove();
```

A scene draws one height fog, the first spawned that shows; a stage's fog node is one.

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
- **A spawned stage keeps the prefab it was spawned from**: a reload reaches the next spawn.
- A dedicated server compiles a stage with no post materials and draws nothing.
