# Prefabs: models made of parts

A prefab is a model built from parts that move and fade together: the core of a Rasen Shuriken, its rings and blades,
a lens of nested balls. It is a tree of nodes, each with a parent, a rest transform, an opacity and the meshes it
draws, each with a material. Unreal calls it an actor and its component tree; CC2 a clump. An effect plays one; its
look draws it.

## Building one

```java
// A material asset: one definition is one material, however many parts draw with it.
static final CgMaterialDefinition LENS = CgMaterialDefinition.of("mymod:materials/lens",
        "mymod:shaders/vfx/lens.shader").build();
static final CgMaterialDefinition GLOW = CgMaterialDefinition.of("mymod:materials/lens_glow",
        "crystalgraphics:shaders/emission_only.shader").build();

static final CgVfxPrefab LENS_CORE = CgVfxPrefab.builder("mymod:lens_core")
        .node("ball03")
        .node("ball02").parent("ball03").at(0f, 1f, 0f).part(LENS, "mymod:models/core/ball02.glb")
        .node("ball01").parent("ball02").scale(0.5f).opacity(0.5f)
            .part(LENS, "mymod:models/core/ball01.glb")
            .part(GLOW, "mymod:models/core/ball01.glb")            // a second mesh on the same node
        .build();

static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).with(LENS_CORE.kit()).build();
```

| Call | Sets |
|---|---|
| `node(name)` | a new node, a root at the origin unless told otherwise; the calls after it set it |
| `parent(name)` | its parent, a node named before it |
| `at(x, y, z)`, `rotation(x, y, z, w)`, `scale(...)` | its rest transform in its parent's space; the rotation a quaternion |
| `opacity(a)` | its own opacity, 0 to 1 |
| `part(material, mesh)` | a mesh it draws with a material: a `CgMesh`, `CgMeshLods`, or a file's path loaded at its first draw; `part(material)` draws the unit sphere |

## As a file

A prefab is a `prefabs/` asset, compiled by `CgVfxPrefabAssets` (which `CgVfx.install` registers) and written back by
it, so a prefab built in Java and its file are one model. On a client it is `READY` once `CgVfxPrefabPreparer` (also
registered by `CgVfx.install`) has read its parts' meshes on a worker, uploaded their textures and precached its look,
so a sequence spawning it reads, uploads and compiles nothing; a preview of its own registers one over its system
(`assets.preparer(CgBodyKinds.PREFAB, new CgVfxPrefabPreparer(() -> mySystem))`):

```yaml
# assets/mymod/prefabs/core.yaml
_v: 1
nodes:
  - { id: n1, name: ball03 }
  - id: n2
    name: ball02
    parent: ball03
    location: [0, 1, 0]
    values: { _Glare: 0.2 }
    meshes: [ { mesh: mymod:models/core/ball02.glb, material: mymod:lens } ]
```

```java
Map<String, Object> tree = CgVfxPrefabAssets.write(CORE);   // what CgAssetYaml writes as its file
CgVfxPrefab core = CgVfxAssets.<CgVfxPrefab>ref(CgBodyKinds.PREFAB, "mymod:core").get();
```

- **A node's `values`** are material values of its own over each of its parts' materials: it draws a material derived
  with them (`value(property, ...)` in Java), shared by every node with the same values.
- **Its units convert**: a location and a scale through its file's axes, a rotation in its convention to a quaternion.
- **A mesh slot is kept as written**: several `materials`, an `overlay`, `cast_shadow` compile, and are refused only
  where a prefab would draw them (`requireDrawable`), naming the node.

A sequence spawns one (`spawnables: - { prefab: mymod:core }`, `CgVfxPrefabSpawner`) and keys its nodes: their
transforms, opacity and material values. A material value a sequence keys is a parameter of a variant made for it,
starting at the node's value, else its material's, else its shader's default:

```java
CgVfxPrefab keyed = CORE.keyed(Map.of("ball01", List.of("_U0_LocX")));
CgVfxPrefabEffect effect = VFX.play(new CgVfxPrefabEffect(keyed, x, y, z));   // its own look, the prefab's
effect.set(keyed.param(keyed.node("ball01"), "_U0_LocX"), -0.5f);
```

## Playing one

```java
public final class Shuriken extends CgVfxEffect<Shuriken> {
    private final CgVfxPrefabInstance core = new CgVfxPrefabInstance(LENS_CORE);
    private final int ball01 = LENS_CORE.node("ball01");
    private final Matrix4f spin = new Matrix4f();

    @Override protected void tick(float dt) {
        core.place(spin.rotationY(age * 6f));                 // its roots, from the effect's origin
        core.opacity(ball01, 1f - age / 2f);                  // fades ball01's parts; its children keep their own
    }

    @Override protected void submit(CgVfxFrame frame) {
        core.submit(this, frame);
    }
}
```

| Call | Does |
|---|---|
| `place(matrix)` | where its roots stand, from the effect's origin |
| `location`, `rotation`, `scale`, `opacity(node, ...)` | a node's transform or opacity from now on, by its index (`prefab.node(name)`) |
| `reset()` | every node back at its rest |
| `evaluate()` | each node's matrix and presence, parents first; `submit` calls it |
| `world(node)`, `presence(node)` | a node's matrix from the effect's origin, and its own opacity |
| `submit(effect, frame)` | evaluates, then draws each node's parts at its presence |

## Things to know

- **A node's opacity is its own** (as an Unreal component's is its material's): a parent at 0 leaves its
  children drawn. A game whose opacity passes down (CC2's) has it baked into each node at import. It reaches the shader as `FX_PRESENCE`, so a material fades only if it multiplies by it, as every engine
  shader does.
- **A material is one slot renderer however many parts use it.** The kit holds a slot renderer per distinct material,
  on a slot of the prefab's own, so a prefab of hundreds of nodes and a few materials is a few draws: a look draws at
  most 256.
- **Its materials read no user parameter of yours**: a part's colour is its material's, its node's values, and the
  values a `keyed` variant binds.
- **Transforms multiply as matrices, as glTF's do**: a rotated child under a non-uniformly scaled parent shears, as it
  did in the game it came from.
- Evaluating is CPU matrix work, about 8 microseconds for 458 nodes; nothing allocates once the instance is made.
  Each node's part is a draw, and draws of one material batch.
- Names are unique and a parent comes before its children; the builder throws otherwise.
- `--mode=vfx-prefab` is the gate: 458 nodes, a subtree cut and a chain dimmed.

# Skinned meshes: models with bones

A skinned mesh is geometry whose vertices follow the bones of a skeleton: a character, a beast, a tail. Each vertex
names up to four bones and how much it follows each; the skeleton names the bones and holds their rest pose; clips
move the bones over time. Unreal calls them a skeletal mesh, a skeleton and anim sequences.

## Loading one

From a glTF or GLB, the format every engine exports:

```java
CgSkeletalMesh kurama = CgMeshLoader.skeletal("mymod:models/kurama.glb");
CgSkeleton bones = kurama.skeleton();
int pelvis = bones.indexOf("pelvis");                 // -1 if it has no bone by that name
kurama.mesh();                                        // the geometry: a submesh per glTF primitive
kurama.material(0);                                   // the material its first submesh names
CgAnimSequence attack = kurama.sequence("atk010");    // a clip that came in the same file
```

Clips of their own, in a file of clips alone (an animation exported without its mesh):

```java
List<CgAnimSequence> moves = CgMeshLoader.sequences("mymod:anims/moves.glb");
```

From a GeckoLib model, the Bedrock files Blockbench writes. The geometry bakes into a skinned mesh, each cube
following its bone, and each animation becomes a keyed clip of that model that plays exactly as GeckoLib plays it:

```java
CgGeckoModel saiyan = CgGeckoModel.load("rpgcore:geo/model.geo.json");      // CgMeshLoader.skeletal reads it too
CgSkeletalMesh body = saiyan.mesh();                                        // its locators are its sockets
for (CgGeckoAnimation a : saiyan.animations("rpgcore:animations/core/styles/default/idle.animation.json")) {
    CgAnimSequence clip = a.sequence();                                     // keyed: every easing as GeckoLib's
    CgGeckoAnimation.Loop loop = a.loop();                                  // PLAY_ONCE, LOOP, HOLD_ON_LAST_FRAME
}
```

- **A clip is its model's**: GeckoLib adds a clip's rotations to each bone's rest angles, so convert it for the model
  it plays on. A bone the model lacks is skipped, as GeckoLib skips it.
- **What fails at load**, naming the file, the animation, the bone and the channel: an easing GeckoLib does not name,
  a molang value (a constant only), keys out of time order, a bone whose parent is not in the file, a poly mesh.
- **The files are read as they are, never rewritten**: put them under `geo/` and `animations/` as GeckoLib does. Their
  markers are the clip's notifies ([`sequences.md`](sequences.md) § *A GeckoLib clip's markers*).

| Type | What it is | Unreal |
|---|---|---|
| `CgSkeletalMesh` | the geometry, its skeleton, the bind pose tying them, its materials, morph targets and clips | `USkeletalMesh` |
| `CgSkeleton` | bones by name, each with a parent and a rest pose; one root, parents before children | `FReferenceSkeleton` |
| `CgAnimSequence` | each animated bone's translation, rotation and scale at evenly spaced frames, and float curves; or, keyed, each channel a curve with keys at their own times (a GeckoLib clip) | `UAnimSequence` |
| `CgGeckoModel`, `CgGeckoAnimation` | a GeckoLib model baked into a skinned mesh, and its clips converted for it | none |
| `CgMorphTargets` | named per-vertex offsets mixed in by weight | `UMorphTarget` |

## Building one in Java

```java
CgSkeleton arm = CgSkeleton.builder()
        .bone("shoulder", null, new Vector3f(0, 1.4f, 0))
        .bone("elbow", "shoulder", new Vector3f(0, -0.3f, 0))
        .build();

CgMesh sleeve = CgMesh.build(CgVertexFormat.SKINNED, m -> {
    int a = m.vertex().position(0, 1.4f, 0).normal(1, 0, 0).uv(0, 0).bone(0, 1f).end();
    int b = m.vertex().position(0, 1.1f, 0).normal(1, 0, 0).uv(0, 1).bone(0, 0.5f).bone(1, 0.5f).end();
    int c = m.vertex().position(0, 0.8f, 0).normal(1, 0, 0).uv(1, 1).bone(1, 1f).end();
    m.triangle(a, b, c);
});

CgAnimSequence wave = CgAnimSequence.builder("wave", 30, 31)       // 31 keys at 30 a second: one second
        .track("elbow", new float[]{0, -0.3f, 0}, elbowRotations, new float[]{1, 1, 1})
        .build();

CgSkeletalMesh mesh = CgSkeletalMesh.builder(sleeve, arm).material(0, "mymod:sleeve").sequence(wave).build();
```

## Playing clips

A `CgAnimInstance` is a skinned mesh posed by the engine: clips placed as sections on a timeline of its own,
evaluated every frame in `POSE` on workers by `CgAnimation`, and its palette written for the draw.

```java
CgAnimInstance kurama = CgAnimation.client().add(new CgAnimInstance(mesh));
kurama.play(CgAnimSection.of(mesh.sequence("idle")).until(Double.POSITIVE_INFINITY).build());   // loops for ever
kurama.play(CgAnimSection.of(mesh.sequence("roar")).at(2.0).easeIn(0.25f).easeOut(0.25f).build());

// SUBMIT, the same frame: POSE has written this frame's palette
world.draw(mesh.mesh(), skin).bones(kurama.palette()).at(x, y, z).submit();

kurama.seek(3.25);              // the pose playing reaches at 3.25 s, exactly
kurama.playing(false);          // held; kurama.rate(0.5f) plays at half speed
CgAnimation.client().remove(kurama);
```

| Section setting | Does (Unreal's skeletal animation section) |
|---|---|
| `at(t)`, `until(t)` | when it plays on the instance's timeline; past the clip's length it loops, else it holds its last frame |
| `playRate(r)` | clip seconds a timeline second; negative plays from the end |
| `startOffset`, `endOffset`, `firstLoopOffset` | clip seconds cut from each loop's start and end, and how far into the clip the first loop starts |
| `reverse()`, `loop()` | played backwards; looped however long it lasts |
| `weight(w)`, `easeIn(s)`, `easeOut(s)` | its weight, rising and falling over those seconds along Cubic InOut (`CgEasings.IN_OUT_CUBIC`), or the `CgEasing` given |

- **Sections cross-fade by their weights, normalised**: one easing in alone plays at full weight, as Sequencer's does;
  only two overlapping blend. Where none plays, the rest pose.
- **A clip binds to the skeleton by bone name ignoring case**, so Goku's `Root` track moves his `root`.
- **A pose is a pure function of the instance's time**: a seek, a replay or a late joiner lands on the same pose,
  bit for bit.
- **A montage slot plays over its sections for one pose**: `slot(clip, position, weight)`, set every frame it should
  hold and cleared by the next evaluation, Unreal's `DefaultSlot`. Its sections keep `1 -` the slot's total weight;
  past 1 the slot's entries are normalised and its sections are gone. A sequence's `animation` track sets it on its
  role (`sequences.md` § *Playing a sequence*).

```java
goku.slot(roar, 0.4f, 1f);      // this pose: roar at 0.4 s over whatever his sections play
```
- By hand, without `CgAnimation`: `instance.advance(dt).evaluate()`, then `instance.skinning()` into
  `CgBonePalettes.write`. Lower down, `clip.sample(pose, time)` into a `CgPose`, `pose.blend(...)`, `pose.resolve()`.
- About 9 µs to pose a 228-bone puppet; past `crystalgraphics.animation.parallelAt` (8) instances, `POSE` splits them
  across workers.
- `--mode=skinned-play` shows them: Goku and Kurama, Tab to pick, N/B clips, P pause, `,` `.` a frame, I/O rate,
  K a Kamehameha charging on its right hand.
- **While the game is paused** every instance holds its time and is still posed, so it still draws. A new level or
  dimension drops the client's instances.

## Layers, bone writes, mirroring and hiding

What plays on part of the body, what code moves after the clips, a clip played on the other side, and bones not
drawn. In the order a frame applies them: sections and the slot, then each layer, then each bone write, then hiding.

```java
// A layer over part of the body: branches (each ramping in over its depth), or a weight per bone
CgAnimLayer arms = role.addLayer("Arms").branch("right_arm", 0).branch("left_arm", 0);
CgAnimLayer lean = role.addLayer("Lean").bone("body", 0.5f).bone("head", 0.25f);
arms.slot(holdSword, 0f, 1f);       // this frame, over the arms
arms.weight(0.6f);                  // the whole layer's weight, scaling its mask

// Code after the clips: Unreal's Transform (Modify) Bone
CgBoneWrite look = role.write("head");
look.rotation(CgBoneWrite.Mode.ADD, CgBoneWrite.Space.COMPONENT, yawPitch);   // each frame
CgBoneWrite drop = role.write("model");
drop.translation(CgBoneWrite.Mode.ADD, CgBoneWrite.Space.COMPONENT, 0f, -0.125f, 0f).alpha(crouch);

// A clip authored for the right side, played on the left
CgMirrorTable mirror = CgMirrorTable.byName(mesh.skeleton(), CgMirrorTable.Axis.X).self("body").build();
role.slot(swing, phase, 1f, mirror);

// Bones not drawn: their skinning collapses, never their pose
role.hide("head", CgAnimInstance.Hide.BONE);          // the head's own vertices; a hat on a child bone stays
role.hide("left_arm", CgAnimInstance.Hide.BRANCH);    // the arm and all below it, as Unreal's HideBone
role.hideFromOwner("head", CgAnimInstance.Hide.BRANCH);
world.draw(role, skin).bones(role.palette(ownerView)).submit();   // the owner's palette has the head collapsed
```

- **A layer's mask** is Unreal's layered blend per bone: a branch gives a bone `d` levels below it `(d + 1) / depth`
  (0 for the whole branch at once), and branches add up to 1; `bone(name, w)` sets one bone's weight alone (a blend
  mask entry). Each bone then takes the layer's pose by its mask weight times the layer's `weight`. With nothing
  playing, a layer leaves the body as it is.
- **A bone write** applies scale, then rotation, then translation, each ignored, replaced or added to (an added
  rotation is `q * current`) in its own space: `COMPONENT` (the mesh's), `PARENT` (the bone's own local transform) or
  `BONE` (relative to where the bone is). Writes apply in the order made; a write in another space sees earlier writes.
  `alpha` blends it over the pose. A whole pose composed elsewhere is written bone by bone with `REPLACE` in `PARENT`.
- **Mirroring** is Unreal's mirror data table: pairs by name (`r_`/`l_`, `_l`/`_r`, `right`/`left`, ...), centre
  bones (`spine`, `root`, `pelvis`, `neck`, `head` ...) onto themselves, and anything else left as it is, so name your
  other centre bones with `self(name)`. Sections, slot entries and layer entries each take a table.
- **Hiding** keeps sockets and the bones below moving. `hideFromOwner` costs a second palette only while a bone is
  hidden from the owner.

### On a dedicated server

The server poses its instances each game tick, for their bones and sockets, and draws nothing:

```java
CgAnimInstance body = CgAnimation.server().add(new CgAnimInstance(mesh));   // CgMeshLoader.skeletal reads headless
body.play(CgAnimSection.of(mesh.sequence("swing")).build());
body.socket(body.socket("hand_r"), point, turn);   // this tick's pose
```

## Sockets: attaching to a posed bone

A socket is a named place on a bone (Unreal's `USkeletalMeshSocket`), and every bone is a socket by its own name. An
effect following one rides the bone as `POSE` posed it that frame, so it never lags the mesh.

```java
CgSkeletalMesh goku = CgMeshLoader.skeletal(path)
        .withSockets(List.of(CgSocket.of("right_hand", "hand_r", new Vector3f(0, -0.08f, 0.02f))));
CgAnimInstance body = CgAnimation.client().add(new CgAnimInstance(goku)).place(x, y, z, turnAndScale);

VFX.play(CgEnergyWave.instance(CgEnergyWave.kamehameha(), x, y, z).follow(body, "right_hand"));   // or "hand_r"
world.draw(body, skin).pad(2f).submit();                                                         // SUBMIT

int hand = body.socket("right_hand");          // looked up once
body.socket(hand, point, turn);                // this frame's, after POSE: absolute place and turn
```

- **Found as Unreal finds one**: the mesh's socket of that name, else the bone of that name, ignoring case; a name
  that is neither stands where the instance does.
- **Where it is**: `place(x, y, z, transform) × bone × socket`, the instance's placement being Unreal's
  component-to-world. `world.draw(instance, material)` draws it there.
- **An effect following a socket** takes its origin and turn each frame before it ticks; its named sockets
  (`socket_location` spawning) are the instance's; it stops once the instance is removed from `CgAnimation`.
- **Late attach**: after the effects tick and before they draw (`LATE_ATTACH`), every followed point is read again, an
  entity's freshest mark included, so what draws at a point sits on it as it is drawn (Unreal's late update).
- A loaded file carries no sockets: they come from the skeleton asset (`withSockets`) or `CgSkeletalMesh.Builder.socket`.

### Attaching one skinned mesh to another

Hair, a tail, a hat or an ear is its own mesh hung from a body's bone: attach its instance to the body's socket
(Unreal's `AttachToComponent`). It is posed after the body every frame and stands where the socket is that frame.

```java
CgAnimInstance body = CgAnimation.client().add(new CgAnimInstance(bodyMesh)).place(x, y, z, turnAndScale);
CgAnimInstance hair = CgAnimation.client().add(new CgAnimInstance(hairMesh)).attachTo(body, "head");

hair.write("strand_0").rotation(CgBoneWrite.Mode.REPLACE, CgBoneWrite.Space.PARENT, sway);   // its own bones, as usual
world.draw(body);                                                                            // SUBMIT
world.draw(hair);
```

A part that sits off the bone takes a relative place: while attached, `place` is the offset, turn and scale in the
socket's space.

```java
CgAnimInstance hat = CgAnimation.client().add(new CgAnimInstance(hatMesh))
        .attachTo(body, "head")
        .place(0, 0.45, 0, new Matrix4f().rotateY((float) Math.PI).scale(1.1f));   // up the head, turned round
hat.detach();                                                                      // stays where it last stood
```

- **A name the body has neither as a socket nor a bone is refused**, rather than placing the part at the body's feet;
  `attachTo(body, null)` attaches to where the body stands.
- **Attachments chain** (a ribbon on the hat on the head), parents always posed first. Attaching into a loop is
  refused.
- **Hiding the body's bone does not hide what hangs from it**: hide the part itself, for instance from its owner in
  first person with `hair.hideFromOwner("root", Hide.BRANCH)`.
- Both instances are added to `CgAnimation`; `x()`, `y()`, `z()` and `transform()` answer where the part stood at the
  last `POSE`.

## Drawing one

A posed instance draws itself, each submesh in its slot's material, as Unreal's skinned mesh component draws its
sections with `GetMaterial(slot)`:

```java
for (int s = 0; s < goku.materialSlots(); s++) goku.material(s, CgMaterial.of(kitMaterial(s)));   // its prefab's, in slot order
world.draw(goku);                       // SUBMIT: every slot, skinned by this frame's palette, where it stands
world.draw(kurama, skin).submit();      // or the whole mesh in one material
goku.boundsScale(1.5f);                 // Unreal's BoundsScale: culled by its bind pose's bounds grown about their centre
```

- **A slot with no material throws**, naming it: Unreal draws its default material there, and this engine has none.
- Its bounds are the bind pose's, grown by `boundsScale` (1 unless set): a pose reaching far past them needs more.

An overlay and CustomDepth, Unreal's `OverlayMaterial` and `bRenderCustomDepth`:

```java
goku.overlay(outline);                  // every slot again in it; overlay(slot, m) for one slot's own
goku.overlayMaxDistance(40f);           // beyond 40 blocks the overlay is not drawn (0: as far as the mesh)
goku.customDepth(1, 0xFF);              // into CgFrameKeys.CUSTOM_DEPTH with stencil 1, even behind a wall
world.draw(goku);
```

- A slot's overlay replaces the mesh's for that slot; a slot with neither draws none.
- CustomDepth is tested against itself, not the scene: a post material compares it with the scene's depth to outline
  what is hidden (`render/world/CLAUDE.md`, *CustomDepth*).

By hand, each frame, write the pose's skinning matrices, one a bone in the skeleton's order, then draw the mesh naming
where they start. Any material on the `Mesh` factory skins it with no code of its own: the mesh's format picks the material's skinned
variant (Unreal's GPU skin vertex factory), in every pass the material draws.

```java
Matrix4f[] skinning = ...;                                    // pose(bone) * inverseBind(bone), every bone
int base = CgBonePalettes.write(skinning, kurama.skeleton().boneCount());
world.draw(kurama.mesh(), skin).bones(base).at(x, y, z).submit();

CgMesh posed = CgSkinning.skin(kurama.mesh(), skinning);      // the same pose on the CPU, as a static mesh
```

- **A base is this frame's**: write the palette and submit the draw in the same frame, from the client world's
  schedule as any world draw.
- **A skinned mesh must name its bones**, and only a skinned one may: either mistake throws at `submit()`.
- **It is culled by its bind pose's bounds**: a pose reaching past them needs `.bounds(...)` or `.pad(r)`.
- In the shader, `cg_Position` and `cg_Normal` are the skinned vertex: position by the weighted sum of its bones' 3×4
  transforms, normal by the same matrix, renormalised. Then the draw's transform, as for any mesh.
- About 0.1 ns a vertex a pass (RTX 4070 SUPER, Vulkan): a 30,000-vertex character costs microseconds.
- `--mode=skinned-mesh` is the gate (the GPU's skinning against `CgSkinning`'s); `--mode=skinned-bench` measures it.

### In Minecraft's entity look

A character drawn into Minecraft looks like the game's own entities with the engine's entity materials: vanilla's
`rendertype_entity_cutout` and `rendertype_eyes`, the same formula on every version.

```java
static final CgMaterialDefinition BODY = CgMaterialDefinition.of("mymod:materials/hero",
        "crystalgraphics:shaders/role/entity.shader")
        .texture("_MainTex", "mymod:hero", CgSampler.Filter.NEAREST, CgSampler.Address.CLAMP).build();   // a texture asset
static final CgMaterialDefinition EYES = CgMaterialDefinition.of("mymod:materials/hero_eyes",
        "crystalgraphics:shaders/role/entity_emissive.shader")
        .texture("_MainTex", "mymod:hero_eyes", CgSampler.Filter.NEAREST, CgSampler.Address.CLAMP).build();

hero.material(0, BODY).material(1, EYES);
world.draw(hero);
```

- **`role/entity.shader`**: Minecraft's two fixed lights on the normal (`CG_ENTITY_LIGHT0`, `CG_ENTITY_LIGHT1`, the
  Nether's where the host says so), cutout below `_Cutoff` (0.1), the lightmap at the draw's light and the world's
  fog. Translucent or double-sided is its `CgMaterialState`.
- **`role/entity_emissive.shader`**: the glowing layer, eyes and glowmasks: no shading and no lightmap, added over
  what is behind, faded by the fog.
- **The material contract** rides on top: `_Tint` multiplies by its alpha, `_Flash` is the overlay mixed over the
  texture by its alpha (the hurt flash is red at 0.3), and `_RimColor`, `_RimStrength`, `_RimPower` add a rim. A
  sequence's `material` track keys them through the role's bindings:

  ```java
  CgRoleFlash flash = new CgRoleFlash(hero, "_Tint", null, "_Flash", null);         // tint, flash and their strengths
  CgRoleRim rim = new CgRoleRim(hero, "_RimStrength", "_RimPower", "_RimColor");     // rim_strength, rim_power, rim
  ```

### Under a shader pack

A shader pack (Iris, Oculus) lights, fogs and shadows only what goes through Minecraft's own entity render types, and
it draws the shadow map by calling each entity's renderer a second time. So while a pack is on, a character is skinned
on the CPU and its vertices written into the render type, from inside the entity's renderer: the pack treats it as
any mob, in both passes.

```java
// MyHeroRenderer.render(entity, yaw, partialTick, poseStack, buffers, light), 1.20.1
if (CgShaderPacks.packInUse()) {
    PoseStack.Pose pose = poseStack.last();
    int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0f);          // the hurt flash, as vanilla's
    for (int s = 0; s < hero.materialSlots(); s++) {
        VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURES[s]));
        hero.cpuSkin(s, false, (x, y, z, u, v, argb, nx, ny, nz) -> out.vertex(pose.pose(), x, y, z)
                .color(argb).uv(u, v).overlayCoords(overlay).uv2(light).normal(pose.normal(), nx, ny, nz).endVertex());
    }
} else {
    world.draw(hero);                                // no pack: the engine's own skinned draw, in SUBMIT
}
```

- **Positions are relative to where the instance stands**, turned and scaled by its placement: place it where the
  renderer's pose stack stands (the entity's interpolated position), and the stack adds the camera.
- **Each call writes quads**, as entity render types draw them; the pack fills in its tangents and texture centres
  per quad.
- `CgShaderPacks.renderingShadowPass()` says the renderer is being called for the shadow map: skip what should cast
  no shadow (a glow layer).
- Under a pack, the engine's entity material is not used: a tint is yours to multiply into `argb`, and the hurt flash
  is the overlay, as above.
- Its mesh must keep its CPU copy (never `dropCpuCopy()` it). The mesh is decoded once; each call allocates nothing.

## Cooked

A release ships meshes cooked: `.cgmesh` holds the vertices as the engine lays them out, read in one copy, and
`.cganim` beside it the clips as columns. `CgMeshLoader` reads them by extension, the same calls:

```java
CgSkeletalMesh kurama = CgMeshLoader.skeletal("mymod:cooked/kurama.cgmesh");   // kurama.cganim beside it, if any
byte[] mesh = CgCookedMesh.write(kurama), clips = CgCookedAnimation.write(kurama.sequences());
```

## Things to know

- **Four bones a vertex.** A vertex naming more fails the load, naming it. Weights are stored to 1/255, heaviest
  first, summing to exactly 1: give them as they are, and a bone given twice keeps its larger weight.
- **Up to 256 bones** a mesh in `CgVertexFormat.SKINNED`; a larger skeleton's mesh is `SKINNED_WIDE`, with 16-bit
  indices. The loader picks; `CgVertexFormat.skinned(bones)` says which.
- **The skeleton is the skins' joints and every node above one**, so an armature's own transform (which often stands
  the model up) is a bone too. One root; two bones of one name fail the load.
- **A clip is baked to evenly spaced frames** at the first of the usual rates (30, 24, 23.976, 25, 29.97, 48, 50, 60,
  ...) its length lands on, as Unreal imports one. A channel that never moves keeps one key.
- **Tracks name their bones**, so a clip plays on any skeleton that has them. A bone a clip does not animate holds its
  rest pose.
- **Names are kept as written**: what a socket or a sequence names a bone by.
