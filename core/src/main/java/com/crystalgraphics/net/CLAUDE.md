# net — between the server and its clients

Messages, requests and networked objects between Minecraft's server and its clients, over the one channel every host
installs. **Using it: `docs/NETWORKING.md`, the guide.** This guide is where each part lives. Design and steps:
private plan `crystalgraphics/net-objects.md`; how the hosts wire it: `docs/MINECRAFT_INTEGRATION.md` § *Connections*.

```java
public static final CgMessage<Integer> COMBO = CgMessage.toClients("mymod:combo", CgCodecs.INT);
COMBO.send(CgAudience.player(id), 12);

public static final CgNetType MACHINE = CgNetType.define("mymod:machine").fields(HEAT).range(48).build();
MACHINE.spawn(CgAnchor.at(level, x, y, z)).start().setFloat(HEAT, 0.8f);
```

## Where things go

| Package | Owns |
|---|---|
| `net` | What a mod sends: `CgMessage` (one value one way), `CgRequest` (a question with one answer), `CgAudience` (who a server send reaches). And the connections: `CgNetwork` (one per player on a server, one to the server on a client; the hosts forward join, leave, ticks and server start and stop into it), `CgPeer` (a connected player by profile id), `CgHello` (each connection's opening: the namespaces and versions each side declared, so a send to a peer without them is skipped and counted), `CgTransport` and `CgInMemoryTransport` |
| `net.protocol` | The router beneath messages and requests: `CgProtocols` (contributors bound onto every connection), `CgProtocolConnection`, `CgMessageRouter` (correlation, timeout, cancel), `CgEnvelope`, `CgProtocolErrors` |
| `net.wire` | Frames over Minecraft's custom-payload channel: `CgFrameMultiplexer` (many messages over one small-framed pipe), `CgFrameCodec`, `CgWireTransport`, `CgStreamRefused` |
| `net.rate` | How often a report may travel: `CgRatePolicy` (immediate, debounce, throttle, always the last value), `CgRateGate` (applies one). Used by commands and predicted fields, and by CrystalGUI's widgets |
| `net.object` | Networked objects: `CgNetType` (a kind: its fields, events, commands, who sees it, persistence, behaviours), `CgNetObject` (one; `level()`, the STOPPING phase of a graceful end), `CgNetField`, `CgNetEvent` (a codec's tree, or `CgNetWire`'s fixed widths for an event emitted often), `CgNetCommand`, `CgAnchor`, `CgNetBehaviour`. `CgNetObjects` is the server's side: anchors followed, server behaviours ticked, one frame per player per tick, persistence under the world's `crystalgraphics/objects/`, `report()` and the `crystalgraphics.net` trace counters. `ClientObjects` is a client's side; `NetBuffer` the frame's bytes; `CgNetTestRig` a server and clients in one JVM, for tests here and in consumer mods; `CgNetStress` the live stress run (`-Dcrystalgraphics.net.stress=true`, `docs/NETWORKING.md` §15) |

## Rules

- **The server decides.** A client's object is read-only but for its owner's predicted fields; anything a client
  asks for is a command or a request, checked on the server. The sender is always the connection's, never a field.
- **A frame's records** are `CgNetObjects`' constants (`DEFINE` to `STOPPING`), read in `ClientObjects.receive`. A new
  record goes at the end, on both sides in the same change.
- **A quiet tick allocates nothing**, and `CgNetObjectsTest.aSteadyTickAllocatesNothingInTheLayer` holds it. Count at
  the site that knows (a refusal, a byte), never in a per-tick loop over types: such a loop once deoptimized the tick
  into allocating.
- **Names**: a type is `namespace:path`, the namespace the hello compares; a field, event or command is short and
  matched within its type.
- **`core/` names no Minecraft**: the hosts answer through `CgServerPlayers` (players, entities, tracking, levels) and
  `CgLevelQueries` (the world, for server behaviours), each in `platform.service`.
- **Effects are a consumer, not a special case**: `vfx.net`'s cues are types like any other. A need they have that any
  object could have belongs here (`level()`, the graceful end in front of viewers did).

---

Also loaded with this folder:

@../../../../../../../docs/NETWORKING.md
