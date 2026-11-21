# Checklist: before you ship a character

**What you will have:** a character that loads on the first try on every Minecraft version, with nothing sliding,
popping or facing backwards.

Tick each before you export and test.

## The model

- [ ] It faces **+Z**, with **+Y up**, its feet on the origin.
- [ ] Its armature's rotation and scale are **applied** (Blender: *Ctrl+A → All Transforms*).
- [ ] **One root bone**, `root`, on the floor; everything else under it.
- [ ] Bones named as [`naming-conventions.md`](naming-conventions.md) says, with no duplicates.
- [ ] **At most four bone weights a vertex**, normalised.
- [ ] One **material slot** for each part that draws differently; the prefab lists a material for each.
- [ ] Grip bones `right_hand_item` and `left_hand_item` if it holds items.

## Its clips

- [ ] One action (Blender) or animation (Blockbench) a clip, named for what it is.
- [ ] Loops start and end on the same pose.
- [ ] A move that travels keys its travel on `root`, and its clip asset says `root_motion`.
- [ ] Walks and runs have `foot_l` and `foot_r` sync markers.
- [ ] An additive clip (a flinch, a breath) has its clip asset's `additive`.
- [ ] No Molang expressions that are not constants (Blockbench).

## The files

- [ ] Model, textures and clips under `assets/<your mod>/`.
- [ ] A material per slot, on `entity.shader` for Minecraft's look.
- [ ] A prefab naming the mesh and its materials.
- [ ] A graph whose every clip id resolves (the humanoid template: every state given a clip).
- [ ] An actor file binding the entity types it draws.

## In game

- [ ] Start the game and read the log: a file that failed is named, with its reason.
- [ ] Spawn the entity, look at it, `/cgc anim debug`: every variable changes as it moves.
- [ ] Walk, sprint, jump, swim: no foot slides, nothing pops.
- [ ] On a server with a second player: they see the same moves.

## Where next

- Something on the list fails: the guide for that part, from [`../README.md`](../README.md).
