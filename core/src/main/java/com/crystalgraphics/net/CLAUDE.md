# net — between the server and its clients

Messages, requests and networked objects between Minecraft's server and its clients, over the one channel every host
installs. **Using it: `docs/NETWORKING.md`, the guide.** This guide is where each part lives. Design and steps:
private plan `crystalgraphics/net-objects.md`; how the hosts wire it: `docs/MINECRAFT_INTEGRATION.md` § *Connections*.

```java
public static final CgMessage<Integer> COMBO = CgMessage.toClients("mymod:combo", CgCodecs.INT);
COMBO.send(CgAudience.player(id), 12);

public static final CgNetType<CgNetObject> MACHINE = CgNetType.define("mymod:machine").fields(HEAT).range(48).build();
MACHINE.spawn(CgAnchor.at(level, x, y, z)).start().setFloat(HEAT, 0.8f);
```

## Where things go

| Package | Owns |
|---|---|
| `net` | What a mod sends: `CgMessage` (one value one way), `CgRequest` (a question with one answer), `CgAudience` (who a server send reaches). And the connections: `CgNetwork` (one per player on a server, one to the server on a client; the hosts forward join, leave and connections into it; its ticks and the server's start and stop come from `CgEngineLifecycle`), `CgPeer` (a connected player by profile id), `CgHello` (each connection's opening: the namespaces and versions each side declared, so a send to a peer without them is skipped and counted), `CgTransport` and `CgInMemoryTransport` |
| `net.protocol` | The router beneath messages and requests: `CgProtocols` (contributors bound onto every connection), `CgProtocolConnection`, `CgMessageRouter` (correlation, timeout, cancel), `CgEnvelope`, `CgProtocolErrors` |
| `net.wire` | Frames over Minecraft's custom-payload channel: `CgFrameMultiplexer` (many messages over one small-framed pipe), `CgFrameCodec`, `CgWireTransport`, `CgStreamRefused` |
| `net.rate` | How often a report may travel: `CgRatePolicy` (immediate, debounce, throttle, always the last value), `CgRateGate` (applies one). Used by commands and predicted fields, and by CrystalGUI's widgets |
| `net.object` | Networked objects: `CgNetType<T>` (a kind: its fields, events, commands, who sees it, persistence, behaviours; `T` is what its handlers are handed, `CgNetObject` for one declared field by field), `CgNetModel` (a type defined from a data class's codec, `define(name, codec)`: its keys compiled to fields once, a nested object codec a part with an object of its own, the server's instance read once a tick while watched and compared by value or by encoding, a watcher's edit a predicted field's set under the key's rules and `allowEdits`, each client's own instance filled through the codec's setters; a `keyedListOf` key a `CgNetList` diffed by key, a `mapOf` key its keys alone with each entry an object opened on demand (`open`, `put`, `remove` calls), a `CgNbt.methods` key its tag), `CgNetObject` (one; `level()`, the STOPPING phase of a graceful end), `CgNetField`, `CgNetEvent` (a codec's tree, or `CgNetWire`'s fixed widths for an event emitted often), `CgNetCommand` (and its `REFUSED_*` reasons), `CgNetCall` (a command that answers; `onCall`, `allow`). A request asked for (`ask`, `call`, `edit`) carries an id and is answered by an `OUTCOME` record, done, refused with its reason or a call's value; `send` asks nothing and allocates nothing. `CgAnchor`, `CgNetBehaviour`. Watching (`watched()` types and fields, `watch`/`unwatch`, `CgNetUnwatched`): membership set when told and applied in the send at the tick's end, never polled. `CgNetEntities` keeps the objects on entities (`onEntities`: an entity's properties) on each side and runs their tick and frame, and saves an entity's objects with the entity (`world.CgEntitySaves`: restored as they are made, written at the entity's save). `CgNetTypeCompiler` is the `net_type` kind: a type declared in a file, compiled at pre-init on each side (`CgAssetMounts.declareNetTypes`), the same type its Java declaration is; `Builder.buildOnce` is how a compiler declares (a reload answers the same type, or fails by name when its shape changed). `CgNetObjects` is the server's side: anchors followed, server behaviours ticked, one frame per player per tick, persistence under the world's `crystalgraphics/objects/` (an entity's go with the entity, through `CgNetEntities`; a player's per-holder ones in `crystalgraphics/players/<uuid>.jsonc`, through `PlayerFiles`; a namespace given a `CgStorageLayout` by `CgNetType.storage` saves under its own root), each field saved by its own `persisted()` or its type's (or a type's own `CgNetSaveForm`, `savedAs`), the field encoding shared with `CgNetEntities`, `report()` and the `crystalgraphics.net` trace counters. `ClientObjects` is a client's side; `NetBuffer` the frame's bytes; `CgNetTestRig` a server and clients in one JVM, for tests here and in consumer mods; `CgNetStress` the live stress run (`-Dcrystalgraphics.net.stress=true`) and `CgNetProbe` the live check through a death (the gauntlet's `crystalgraphics:net`), both `docs/NETWORKING.md` §16 |

## Rules

- **The server decides.** A client's object is read-only but for its owner's predicted fields and its `local()`
  fields, which are never sent; anything a client
  asks for is a command or a request, checked on the server. The sender is always the connection's, never a field.
- **A frame's records** are `CgNetObjects`' constants (`DEFINE` to `EDITABLE`), read in `ClientObjects.receive`. A new
  record goes at the end, on both sides in the same change.
- **A quiet tick allocates nothing**, and `CgNetObjectsTest.aSteadyTickAllocatesNothingInTheLayer` holds it. Count at
  the site that knows (a refusal, a byte), never in a per-tick loop over types: such a loop once deoptimized the tick
  into allocating.
- **Names**: a type is `namespace:path`, the namespace the hello compares; a field, event or command is short and
  matched within its type.
- **`core/` names no Minecraft**: the hosts answer through `CgPlayers` (players, tracking) and `CgLevels`
  (levels, entity objects, the world for server behaviours), in `platform.world`, read through `CgPlayers`,
  `CgWorldQueries` and `CgEntityQueries`.
- **Which side code runs on** is `CgNetwork.side()`: the server's thread is `SERVER`, a client's every other thread.
- **Effects are a consumer, not a special case**: `vfx.net`'s cues are types like any other. A need they have that any
  object could have belongs here (`level()`, the graceful end in front of viewers did).

---

Also loaded with this folder:

@../../../../../../../docs/NETWORKING.md
