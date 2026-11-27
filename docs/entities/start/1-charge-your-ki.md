# Tutorial 1: charge your ki

**What you will have at the end:** press **K** and your player starts charging ki. The server fills it, 20 points a
second. At 100 you are launched into the air and it empties. Leave the world halfway through a charge, come back, and
your ki is where you left it.

On the way you will use the four things most entity gameplay is made of:

1. a **property** on an entity, kept by the server (your ki);
2. a **tag** the player's own client asks for (`charging`), shown at once and checked by the server;
3. **server logic** that runs every tick for every player;
4. **movement**: the launch.

About 60 lines, all in your mod. It runs on every Minecraft version and loader the engine supports.

## 1. The ki itself

Common code, run on both sides. It declares what the ki is, what the server does with it every tick, and which tag a
player's own client may change.

```java
package com.example.mymod;

import com.crystalgraphics.net.object.CgNetBehaviour;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.platform.world.CgEntityMovement;
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.tag.CgTagRegistry;
import com.crystalgraphics.world.CgEntityTags;
import com.crystalgraphics.world.actor.CgEntities;
import com.crystalgraphics.world.actor.CgEntity;

public final class Ki {
    /** Whether the player is charging: a tag, so their own client can ask for it. */
    public static final CgGameplayTag CHARGING = CgTagRegistry.global().register("mymod:state.charging");

    /** How much ki they have, 0 to 100: saved in their player file. */
    public static final CgNetField<Float> CURRENT = CgNetField.ofFloat("current").persisted();

    /** One object per player, made as they join. */
    public static final CgNetType<CgNetObject> TYPE = CgNetType.define("mymod:ki").fields(CURRENT)
            .onEntities(type -> type.equals("minecraft:player"))
            .build();

    public static void declare() {
        TYPE.onServer(ki -> new Charge());
        CgEntityTags.acceptTags((entity, tag, delta) -> tag == CHARGING);   // a player may start and stop charging
    }

    /** The server, every tick, for every player. */
    static final class Charge implements CgNetBehaviour {
        @Override public void tick(CgNetObject ki) {
            Object player = ki.entity();
            CgEntity handle = CgEntities.of(player);
            if (!handle.hasTag(CHARGING)) return;

            float now = ki.get(CURRENT) + 1f;                      // 20 a second
            if (now < 100f) {
                ki.set(CURRENT, now);
                return;
            }
            ki.set(CURRENT, 0f);
            handle.removeTag(CHARGING);
            CgEntityMovement.push(player, 0, 1.4, 0);              // launched: 1.4 blocks a tick, upward
        }
    }
}
```

What each part does:

- **`onEntities(...)`** puts one `mymod:ki` object on every entity whose type it accepts, here every player, as it
  enters the level. You never create one by hand.
- **`persisted()`** saves the field. A player's objects go in their own file and come back as they join.
- **`onServer`** gives every object a server behaviour. Its `tick` runs once a server tick. `ki.entity()` is the
  player it sits on.
- **`CgEntities.of(player)`** is the player's gameplay handle: tags, cues, and who may change them.
- **`acceptTags`** is the server's rule for what a player's own client may change. Without it, the server takes
  nothing.

## 2. The key

Client code, in its own class, so a dedicated server never loads it.

```java
package com.example.mymod.client;

import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.platform.input.CgKeyBindings;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.world.actor.CgEntities;
import com.crystalgraphics.world.actor.CgEntity;
import com.example.mymod.Ki;

public final class MyModClient implements CgLifecycleListener {
    @Override public void onPreInit() {
        CgKeyBindings.declare("mymod:charge", CgKeyCodes.KEY_K, "mymod:keys", MyModClient::toggleCharge);
    }

    static void toggleCharge() {
        CgEntity me = CgEntities.local();                          // this client's own player
        CgEntities.predicted(() -> {
            if (me.hasTag(Ki.CHARGING)) me.removeTag(Ki.CHARGING);
            else me.addTag(Ki.CHARGING);
        });
    }
}
```

- **`CgEntities.local()`** is your own player's handle. Only on that handle may a client change tags.
- **`predicted`** shows the change on this client at once and asks the server. If the server's rule refuses it, the
  change is put back, so a refused charge flickers on and off rather than lying.

## 3. Start-up

The common class and the client class each need calling once. Name them in one file in your jar:

```
# src/main/resources/META-INF/crystalgraphics/listeners
common com.example.mymod.MyModLifecycle
client com.example.mymod.client.MyModClient
```

```java
package com.example.mymod;

import com.crystalgraphics.lifecycle.CgLifecycleListener;

public final class MyModLifecycle implements CgLifecycleListener {
    @Override public void onPreInit() {
        Ki.declare();
    }
}
```

The key's name in the controls screen comes from your language file:

```json
{ "key.mymod.charge": "Charge ki", "key.category.mymod.keys": "My Mod" }
```

## 4. Try it

1. Start a world and press **K**. Nothing shows yet, but the server is counting.
2. Five seconds later you are thrown upward.
3. Press **K**, wait two seconds, quit to the title screen and rejoin. Press **K** again: you are launched after three
   more seconds, not five.

## The same ki as a file

The type can be data instead of Java. This file declares the same `mymod:ki`, and the Java finds it by name:

```yaml
# src/main/resources/assets/mymod/net_types/ki.yaml
_v: 1
fields:
  current: { kind: float, persisted: true }
entities: players
```

```java
public static final CgNetType<?> TYPE = CgNetType.named("mymod:ki");
@SuppressWarnings("unchecked")
public static final CgNetField<Float> CURRENT = (CgNetField<Float>) TYPE.field("current");
```

A pack or a designer can then add fields without touching your code. Declare a type in one place only: a file and Java
that disagree on its shape fail at start-up, naming the type.

## What goes wrong

| You see | Because |
|---|---|
| Pressing K does nothing, and nothing is logged | the key was declared after the loader registered keys. Declare it in a `client` listener's `onPreInit`, as above |
| `addTag on entity#… is the server's or its owner's: this client is neither` | you called `addTag` on another entity's handle from a client. Only `CgEntities.local()` is yours |
| The charge starts, then stops by itself a moment later | the server refused it. `acceptTags` was never called, or its rule said no |
| Ki is 0 again after a relog | `persisted()` is missing on the field, or the type was declared after the player joined |

## Where next

- Show the ki on screen: [`../../WIDGETS.md`](../../WIDGETS.md).
- Play an aura while charging: a cue on the handle, `me.addCue(AURA_CUE)` in the same `predicted` block
  ([`../guides/the-gameplay-handle.md`](../guides/the-gameplay-handle.md)).
- Make the player's body play a charging pose while the tag is on: a graph's `tags:` block
  ([`../../animation/guides/entity-variables.md`](../../animation/guides/entity-variables.md)).
- Everything a property can do: [`../guides/properties.md`](../guides/properties.md).
