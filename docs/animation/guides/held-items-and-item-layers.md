# Held items and item layers

**What you will have:** your character holding its sword, bow or shield in its own hands, drawn by Minecraft as the
game draws any held item, and its arms switching to a sword stance, a bow draw or a raised shield when it holds one.

## In plain words

A character holding a sword should hold it in its own hand, and stand differently than when holding a bow. **Held
items** are drawn at **sockets**, named spots on the hands. **Item layers** swap the arms' animation by what is held,
the way you change your stance when you pick up a bat.

## The code

**Items in its hands** need no code: give the body's skeleton two sockets.

| Socket | Holds |
|---|---|
| `right_hand_item`, `left_hand_item` | the grip: where an item sits, in Minecraft's held-item frame |
| `right_hand`, `left_hand` | used where the grip socket is missing |

```java
CgSkeletalMesh knight = CgMeshLoader.skeletal("mymod:models/knight.glb").withSockets(List.of(
        CgSocket.of("right_hand_item", "hand_r", new Vector3f(0, -0.06f, 0.02f)),
        CgSocket.of("left_hand_item", "hand_l", new Vector3f(0, -0.06f, 0.02f))));
```

The actor's body now holds the entity's items: the game draws each one, its own model in its third-person display, at
the socket.

**A stance per item** is the actor file's `item_layers`:

```yaml
# assets/mymod/actors/knight.yaml
_v: 1
binds: { entities: [ mymod:knight ] }
prefab: mymod:knight
anim_graph: mymod:knight
item_layers:
  - { item: "#minecraft:swords", layer: upper_body, graph: mymod:knight_sword_arms }
  - { item: minecraft:bow,       layer: upper_body, graph: mymod:knight_bow_arms }
  - { item: minecraft:shield,    layer: off_arm,    graph: mymod:knight_shield_arm }
```

While the knight holds a sword, the `mymod:knight_sword_arms` graph plays in place of its graph's `upper_body` layer.
Once it holds none, the layer is its own again.

In Java:

```java
CgEntityActors.register("mymod:knight", CgEntityActors.plain()).prefab("mymod:knight").graph("mymod:knight")
        .itemLayer(new CgItemLayer("#minecraft:swords", "upper_body", "mymod:knight_sword_arms"))
        .itemLayer(new CgItemLayer("minecraft:bow", "upper_body", "mymod:knight_bow_arms"));
```

## How it chooses

- **`item`** is an item id (`minecraft:bow`) or `#` and an item tag (`#minecraft:swords`, every item in it).
- **Per layer, the first entry the main hand matches wins**, else the first the off hand matches.
- **Chosen again only when a hand's item changes**, so a layer is not relinked every frame.
- **The layer** is a linked layer the body's graph declares ([`layers-and-masks.md`](layers-and-masks.md) § *Linked
  layers*). The humanoid template declares `upper_body` ([`vanilla-models.md`](vanilla-models.md)).

## Variations

### Which arm holds what

The right arm holds the main hand, unless the entity is left-handed. A bow or crossbow takes both arms, and the other
hand then only holds its item. The arm poses themselves are `entity.main_arm` and `entity.off_arm`
([`entity-variables.md`](entity-variables.md)), for a graph that poses arms by rule rather than by layer.

### Matching items in code

```java
int held = CgEntityProperties.MOTION.of(entityId).get(CgEntityProperties.MAIN_ITEM);
if (CgItems.matches(held, "#minecraft:swords")) { ... }
```

## What goes wrong

- **A body with no hand or grip socket draws no items.**
- **Below 1.15, modern Forge and Fabric draw no held items on an actor.** Legacy Forge (1.8.9 to 1.12.2) and 1.7.10 do;
  1.7.10 and 1.8.9 have one hand.
- **Item tags start at 1.13.** Before it, a `#` entry matches nothing.
- **An id no mod registers matches nothing**, with no error: mods register items late.
- **The item's graph must declare the layer it fills**, and the body's graph must play that layer: a layer only one of
  them knows changes nothing.

## Where next

- Linked layers in depth: [`layers-and-masks.md`](layers-and-masks.md).
- Sockets: [`../../vfx/prefabs.md`](../../vfx/prefabs.md) § *Sockets*.
