# Networking — the guide

CrystalGraphics carries data between Minecraft's server and its clients for you, on every version and loader it
runs on. You declare what travels, once, and send it; you never write a packet, register a channel or think about
which loader you are on.

It comes in two layers:

- **Plumbing**: a **message** (one piece of data, sent once) and a **request** (a question with one answer).
- **Networked objects**: state that lives on the server (a turret, a mana bar, a beam) and is kept up to date on
  every client that can see it, with nothing sent by hand.

This page is written for someone who has never networked a game. Read sections 1 to 3 first: they give you the
words and the picture that everything else uses. After that, jump to what you need.

## If you have networked a mod before

You know the routine. Here is a mana bar the usual way, on Forge 1.20.1, and then here.

**The usual way.** A channel, a packet class, its registration, and an event for every moment the client could
have missed a value:

```java
public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("mymod", "main"), () -> "1", "1"::equals, "1"::equals);

public static void registerPackets() {
    CHANNEL.messageBuilder(ManaSync.class, 0, NetworkDirection.PLAY_TO_CLIENT)   // id 0: keep the order on both sides
            .encoder(ManaSync::encode).decoder(ManaSync::new).consumerMainThread(ManaSync::handle).add();
}

public record ManaSync(int mana) {
    ManaSync(FriendlyByteBuf buf) { this(buf.readInt()); }
    void encode(FriendlyByteBuf buf) { buf.writeInt(mana); }
    void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientMana.set(mana));   // or the server crashes
    }
}

// The mana itself: a capability, its provider, its NBT save and load, attached in AttachCapabilitiesEvent. Then:
@SubscribeEvent static void login(PlayerEvent.PlayerLoggedInEvent e)                 { sync((ServerPlayer) e.getEntity()); }
@SubscribeEvent static void respawn(PlayerEvent.PlayerRespawnEvent e)                { sync((ServerPlayer) e.getEntity()); }
@SubscribeEvent static void dimension(PlayerEvent.PlayerChangedDimensionEvent e)     { sync((ServerPlayer) e.getEntity()); }
@SubscribeEvent static void clone(PlayerEvent.Clone e) { /* copy the old body's mana to the new one on death */ }
static void sync(ServerPlayer p) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new ManaSync(mana(p))); }
// ...and a call to sync() after every line, anywhere in the mod, that changes mana. Miss one and the bar is stale.
```

Then the port: NeoForge 1.20.5 wants a `CustomPacketPayload` with a `StreamCodec`, registered in
`RegisterPayloadHandlersEvent`; Fabric wants `PayloadTypeRegistry` and `ServerPlayNetworking`; 1.12 wants an
`IMessage` and an `IMessageHandler` on a `SimpleNetworkWrapper`. The same mana bar, written again for each.

**Here.** On every one of those versions and loaders, unchanged:

```java
public static final CgNetField<Integer> MANA = CgNetField.ofInt("mana").fallback(100);
public static final CgNetType MANA_BAR = CgNetType.define("mymod:mana").fields(MANA)
        .perHolder().persisted().copyOnDeath().build();

MANA_BAR.of(player).setInt(MANA, 40);                         // server, anywhere
MANA_BAR.onChange(MANA, (bar, mana) -> Hud.mana(mana));        // client
```

Login, respawn, dimension change, death, saving with the world, sending only when it changed: all handled, and
there is no `sync()` to forget.

### What goes away

| The struggle | Usually | Here |
|---|---|---|
| **Every port rewrites your networking** | each loader and each era has its own channel API, and they change (Forge 1.20.1 to NeoForge 1.20.5 is a rewrite) | one declaration, the same on Forge 1.7.10 to 26.3, NeoForge and Fabric |
| **A class per packet** | constructor, encode, decode, handler, registration with a numeric id in the same order on both sides | one line: a name, a direction, a codec |
| **The wrong thread** | a handler runs on the network thread; forget `enqueueWork` and you get a crash that happens one time in a hundred | every handler runs on the game thread |
| **Client classes crashing the server** | `DistExecutor` and `@OnlyIn` around anything that touches the client | install client handlers from your client entry point; nothing else to wrap |
| **Keeping a client in sync** | resend on login, respawn, dimension change, start tracking, and every change, by hand | a networked object's anchor decides who sees it, and newcomers get the current value |
| **Sending too much** | the whole state on every change, or dirty flags of your own | only the fields that changed, once a tick, in one frame per player |
| **Two serializations of the same data** | `FriendlyByteBuf` for the wire, NBT for saving, kept in step by hand | one codec does both, Minecraft's NBT included ([§14](#14-saving-your-own-data-and-nbt)) |
| **Trusting the client** | a packet carrying a player or entity id the client chose | the sender is always the connection's; commands come only from the owner, sanitized and rate-limited |
| **A client without your mod** | a refused connection, or packets it cannot read | it is sent nothing of yours, and the send is counted |
| **Lag on your own actions** | hand-written prediction, or none | `predict()`: shown at once, confirmed or corrected by the server |
| **Testing** | launch the game, open two clients, try it | `CgNetTestRig`: a server and clients in a JUnit test, latency included |

Messages are still there when a packet is all you need ([§4](#4-messages-sending-your-own-data)), and they already
take away the rewrite per port, the packet class, the thread, the side, the trusted sender and the missing mod.
Networked objects ([§6](#6-networked-objects-state-that-lives-on-the-server)) take away the rest.

## Contents

1. [The words you need](#1-the-words-you-need)
2. [Quick start](#2-quick-start)
3. [Client and server: the picture](#3-client-and-server-the-picture)
4. [Messages: sending your own data](#4-messages-sending-your-own-data)
5. [Requests: asking and getting an answer](#5-requests-asking-and-getting-an-answer)
6. [Networked objects: state that lives on the server](#6-networked-objects-state-that-lives-on-the-server)
7. [Who sees an object: anchors](#7-who-sees-an-object-anchors)
8. [Data on a player or a chunk](#8-data-on-a-player-or-a-chunk)
9. [Events and commands](#9-events-and-commands)
10. [Owners, and who is sent which field](#10-owners-and-who-is-sent-which-field)
11. [The server deciding: server behaviours](#11-the-server-deciding-server-behaviours)
12. [Showing it at once: prediction](#12-showing-it-at-once-prediction)
13. [Saving with the world](#13-saving-with-the-world)
14. [Saving your own data, and NBT](#14-saving-your-own-data-and-nbt)
15. [Testing without Minecraft](#15-testing-without-minecraft)
16. [Performance and debugging](#16-performance-and-debugging)
17. [Questions people ask](#17-questions-people-ask)

Effects over the network (cues) are built on networked objects and have their own guide: `docs/VFX.md` § 6.

---

## 1. The words you need

| Word | What it is | Class |
|---|---|---|
| **Server** | The one copy of the game that decides: where everything is, who has how much health. In singleplayer it runs inside your client, but it is still the server. | — |
| **Client** | A player's copy of the game. It draws, plays sounds and reads the keyboard. What it believes about the world is what the server told it. | — |
| **Peer** | The other end of a connection, as the server sees it: one connected player, with their profile id and name. | `CgPeer` |
| **Codec** | How a Java value turns into bytes and back. Every piece of data that travels has one. | `CgCodec`, `CgCodecs` |
| **Message** | One piece of data sent one way: "play this sound here", "the player pressed purge". Declared once, sent as often as you like. | `CgMessage` |
| **Audience** | Which players a server-side send reaches: everyone, one player, whoever is near a point. | `CgAudience` |
| **Request** | A question one side asks and the other answers, once. | `CgRequest` |
| **Networked object** | A thing that lives on the server and is mirrored on the clients that can see it. You change it on the server; the clients' copies follow. | `CgNetObject` |
| **Type** | A kind of networked object: its fields, who sees it, what it is on a client. Declared once, like a message. | `CgNetType` |
| **Field** | One named value of an object: `heat`, `ammo`, `owner`. Only what changed is sent. | `CgNetField` |
| **Anchor** | Where an object lives, which decides who sees it: a point, an entity, a player, a chunk, a dimension, everyone. | `CgAnchor` |
| **Viewer** | A player whose client currently has an object, because its anchor says they can see it. | — |
| **Event** | Something that happens to an object, sent once to its viewers: a shot, a hit. | `CgNetEvent` |
| **Command** | A client asking the server to act on an object: aim, fire, defuse. | `CgNetCommand` |
| **Behaviour** | Your code that reacts to an object on one side: a model on a client, the authority on the server. | `CgNetBehaviour` |
| **Owner** | The player an object belongs to. Only they may send it commands, and some fields are theirs alone. | `CgNetObject.owner` |
| **Prediction** | A client showing the result of its own action at once, before the server has answered. | `CgNetType.predict` |

How they fit together:

```
Plumbing          CgMessage ─── a codec ──▶ send to a CgAudience (server) or to the server (client)
                  CgRequest ─── a codec each way ──▶ ask, get one answer

Objects           CgNetType ─ fields: heat, ammo, ...        (what an object holds)
                            ─ events: fired, jammed           (what happens to it)
                            ─ commands: aim, purge            (what a client may ask)
                  CgNetObject = a type + an anchor + values   → spawned on the server, mirrored to its viewers
```

---

## 2. Quick start

### Send one piece of data to players

```java
// common, at init: what travels, and how it becomes bytes
public static final CgMessage<Integer> COMBO = CgMessage.toClients("mymod:combo", CgCodecs.INT);

// client, at init, from client code: what to do when it arrives
COMBO.onReceive(count -> Hud.showCombo(count));

// server, whenever
COMBO.send(CgAudience.player(player.getUUID()), 12);
```

### Keep a value in sync on every player who can see it

A furnace-like machine whose heat every nearby player sees:

```java
// common, at init
public static final CgNetField<Float> HEAT = CgNetField.ofFloat("heat");
public static final CgNetType MACHINE = CgNetType.define("mymod:machine").fields(HEAT).range(48).build();

// server: make one, then change it whenever you like
CgNetObject machine = MACHINE.spawn(CgAnchor.at(level, x, y, z)).start();
machine.setFloat(HEAT, 0.8f);         // every player within 48 blocks sees the new heat at the end of this tick

// client, at init, from client code
MACHINE.onChange(HEAT, (obj, heat) -> Glow.set(obj.x(), obj.y(), obj.z(), heat));
```

A player who walks into range later is sent the current heat. A player who walks away stops getting updates.

### Give every player a mana bar

```java
// common, at init: one object per player, saved with the world, kept through death
public static final CgNetField<Integer> MANA = CgNetField.ofInt("mana").fallback(100);
public static final CgNetType MANA_BAR = CgNetType.define("mymod:mana").fields(MANA)
        .perHolder().persisted().copyOnDeath().build();

// server
CgNetObject bar = MANA_BAR.of(player);
bar.setInt(MANA, bar.getInt(MANA) - 10);

// client
MANA_BAR.onChange(MANA, (obj, mana) -> Hud.mana(mana));
```

That is [§8](#8-data-on-a-player-or-a-chunk). Everything else builds on these three.

---

## 3. Client and server: the picture

**The server decides; clients show.** This is the one idea to hold on to.

A client can be modified, lag, or lie. So anything that matters (damage, money, who owns what) is decided on the
server, and clients are told. A client may *ask* the server to do something (a request, a command), and the server
checks that the ask is allowed.

| What | Who does it |
|---|---|
| Changing a networked object's fields | the server (and, for one kind of field, its owner's client: [§12](#predicted-fields)) |
| Spawning, despawning an object | the server (a client may predict one: [§12](#12-showing-it-at-once-prediction)) |
| Deciding who sees an object | the server, from its anchor |
| Drawing, sounds, HUD | each client, from what it was sent |
| Asking for something | a client, through a request or a command; the server decides |

- **In singleplayer**, Minecraft runs a server inside your client. Both sides of your code run, in one process.
  That is expected: code that works in singleplayer works on a dedicated server, provided it is on the right side.
- **On a dedicated server**, no client code exists. Install client handlers and behaviours **from client code** (your
  client entry point), so the server never loads your rendering classes.
- **A player without your mod**, or with an older version, is sent nothing of yours. Each connection opens with a
  hello in which both sides say which namespaces they declared; anything else is skipped and counted, never sent and
  never logged per send.

### Threads

| Call | Thread |
|---|---|
| A message's `send`, a request's `ask` on the server, everything on a server's `CgNetObject` | the server thread |
| A message's `sendToServer`, a request's `ask` on the client, a command's `send` | the client thread (the render thread) |
| Every handler, listener and behaviour | the receiving side's game thread, at its tick |

**Send from the game thread.** A message leaves at once, in order with what Minecraft sends on that connection that
tick, so a hit's effect arrives with the hit.

### Declare everything at init

Messages, requests, fields and types are declared once, as `static final` constants, in code **both sides run**
(common code), before any player connects. A connection is told what was declared when it opens; something declared
later reaches nobody who connected before it. A saved type declared after the server started is loaded then, with a
warning in the log, so its saved objects are not lost.

---

## 4. Messages: sending your own data

A message is a name, a direction and a codec.

```java
public static final CgMessage<Integer> COMBO = CgMessage.toClients("mymod:combo", CgCodecs.INT);   // server → clients
public static final CgMessage<Purge>   PURGE = CgMessage.toServer("mymod:purge", Purge.CODEC);     // client → server
public static final CgMessage<Ping>    PING  = CgMessage.both("mymod:ping", Ping.CODEC);           // either way
```

- **The name** is `namespace:path`. The namespace is your mod id: it is what the hello compares.
- **The direction** is checked: `sendToServer` on a `toClients` message throws, and so does `send` on a `toServer`
  one.
- **Declare it once**: a second message of the same name throws.

`COMBO` carries a plain `int`, which has a codec ready (`CgCodecs.INT`). `PURGE` and `PING` carry records of your
own, so each record brings its codec, written once beside it:

```java
// The player asks to purge a machine: which one, and whether to empty it completely.
public record Purge(int machine, boolean full) {
    public static final CgCodec<Purge> CODEC = new CgCodec<Purge>() {
        // Value to data: one entry per component, under a short key.
        @Override public <T> T encode(CgDynamicOps<T> ops, Purge p) {
            return CgCodecs.map(ops)
                    .field("m", CgCodecs.INT, p.machine())
                    .field("f", CgCodecs.BOOL, p.full())
                    .build();
        }
        // Data to value: the same keys, read back with the same codecs.
        @Override public <T> Purge decode(CgDynamicOps<T> ops, T in) {
            CgCodecs.MapCodecReader<T> r = CgCodecs.read(ops, in);
            return new Purge(r.field("m", CgCodecs.INT), r.field("f", CgCodecs.BOOL));
        }
    };
}

// A round-trip timer: the client sends the time it sent at, the server sends the same value straight back.
public record Ping(long sentAt) {
    public static final CgCodec<Ping> CODEC = new CgCodec<Ping>() {
        @Override public <T> T encode(CgDynamicOps<T> ops, Ping p) {
            return CgCodecs.map(ops).field("t", CgCodecs.LONG, p.sentAt()).build();
        }
        @Override public <T> Ping decode(CgDynamicOps<T> ops, T in) {
            return new Ping(CgCodecs.read(ops, in).field("t", CgCodecs.LONG));
        }
    };
}
```

`ops` is the format being written (bytes on the wire, a tree when saving); a codec written this way works for every
format, so you never write one per format. [Your own data](#your-own-data-a-codec) below has the rest of what a codec
can do.

### Server to clients

```java
COMBO.send(CgAudience.player(id), 12);                  // server
COMBO.onReceive(count -> Hud.showCombo(count));         // client, from client code
```

### Client to server

The server's handler is told **who sent it**. Never put the sender in the payload: a client that could name its
sender could name another player.

```java
PURGE.sendToServer(new Purge(machineId));                         // client
PURGE.onReceiveFrom((peer, purge) -> purge(peer.id(), purge));    // server
```

`peer.id()` is the player's profile id, stable across death and dimension changes: key everything on it.
`peer.player()` is the live player entity, looked up on each call. Never keep it: Minecraft makes a new one each
respawn.

### Either way

A `both` message has a handler on each side. `PING` goes to the server and comes straight back to the player who
sent it:

```java
// client: send the time now
PING.sendToServer(new Ping(System.nanoTime()));

// server: send the same value back to whoever sent it
PING.onReceiveFrom((peer, ping) -> PING.send(CgAudience.player(peer.id()), ping));

// client, from client code: the round trip is now minus the time it left
PING.onReceive(ping -> Hud.showPing((System.nanoTime() - ping.sentAt()) / 1_000_000 + " ms"));
```

### Audiences: who a server send reaches

| Audience | Reaches |
|---|---|
| `CgAudience.player(id)` | one player |
| `CgAudience.all()` | every connected player |
| `CgAudience.dimension(level)` | every player in a dimension (a level, an entity in it, or its id: `"minecraft:the_nether"`) |
| `CgAudience.near(level, x, y, z, radius)` | every player in that dimension within `radius` blocks of a point |
| `CgAudience.tracking(entity)` | every player whose client has the entity loaded, not the entity itself |
| `CgAudience.trackingAndSelf(entity)` | the same, and the entity too when it is a player |
| `CgAudience.tracking(level, chunkX, chunkZ)` | every player whose client has that chunk loaded |
| `CgAudience.except(audience, id)` | an audience minus one player: the one who already showed it themselves |

An audience is worked out when you send. Build one once and send through it as often as you like.

### Your own data: a codec

A built-in value has a codec ready: `CgCodecs.INT`, `LONG`, `FLOAT`, `DOUBLE`, `BOOL`, `STRING`, `UUID`, `BYTES`,
and `CgCodecs.enumOf(MyEnum.class)`, `listOf(codec)`, `mapOf(keyCodec, valueCodec)`. A record of your own gets a codec
written once beside it, a key per component, as `Purge` and `Ping` above do. The same codec saves it too
([§14](#14-saving-your-own-data-and-nbt)).

- **One of several kinds** (a skill that is a beam, a dash or a buff, each with its own fields) is `dispatch`: a
  key names the kind, and each kind's codec reads the rest.

  ```java
  static final CgCodec<Skill> CODEC = CgCodecs.dispatch("kind", CgCodecs.STRING,
          Skill::kind,                       // which kind a value is
          kind -> switch (kind) {            // and the codec for each
              case "beam" -> Beam.CODEC;
              case "dash" -> Dash.CODEC;
              default -> null;               // an unknown kind fails, naming it
          });
  ```

- **A component that is usually its default** can be left out: the builder's `optional(key, codec, value, fallback)`
  writes nothing when `value` equals `fallback`, and the reader's `optional(key, codec, fallback)` reads it back.

  ```java
  // encode: a full purge is rare, so most sends carry no "f" at all
  .optional("f", CgCodecs.BOOL, p.full(), false)
  // decode
  r.optional("f", CgCodecs.BOOL, false)
  ```
- **A record inside a record** uses the inner record's codec as a field's: `.field("s", Stats.CODEC, value)`.
- **Short keys keep messages small**: the key travels with every send.
- **Encode and decode must use the same keys and codecs**: a key read that was never written throws on the
  receiving side.

### Versions

When you change a message's payload in a way an older copy of your mod cannot read, raise your namespace's version:

```java
CgMessage.version("mymod", 2);   // at init, before declaring
```

A peer on another version is logged once and sent nothing of that namespace.

### Counting what was not sent

`COMBO.skipped()` counts sends passed over because the peer lacked your mod or your version.

---

## 5. Requests: asking and getting an answer

Most of the time one side just tells the other: use a message. When the asker needs an answer back, use a request.

### A whole example: a machine's screen

A player right-clicks a machine block and a screen opens showing its heat, its fuel and who owns it. Those numbers
live on the server, in the machine's block entity; the client has none of them. So the screen asks, and shows the
answer when it comes. Every piece is below, in Minecraft 1.20.1's names; the CrystalGraphics side is the same on
every version.

**1. The answer: a record and its codec** (common code).

```java
public record Stats(float heat, int fuel, String owner) {
    public static final CgCodec<Stats> CODEC = new CgCodec<Stats>() {
        @Override public <T> T encode(CgDynamicOps<T> ops, Stats s) {
            return CgCodecs.map(ops)
                    .field("h", CgCodecs.FLOAT, s.heat())
                    .field("f", CgCodecs.INT, s.fuel())
                    .field("o", CgCodecs.STRING, s.owner())
                    .build();
        }
        @Override public <T> Stats decode(CgDynamicOps<T> ops, T in) {
            CgCodecs.MapCodecReader<T> r = CgCodecs.read(ops, in);
            return new Stats(r.field("h", CgCodecs.FLOAT), r.field("f", CgCodecs.INT), r.field("o", CgCodecs.STRING));
        }
    };
}
```

**2. The request** (common code, at init). The question is the machine's block position, packed into a `long` the
way Minecraft packs one (`BlockPos.asLong`), so it travels with the built-in `LONG` codec. The answer is a `Stats`.

```java
public final class MachineNet {
    public static final CgRequest<Long, Stats> STATS =
            CgRequest.toServer("mymod:machine/stats", CgCodecs.LONG, Stats.CODEC);

    // The answers a refusal can carry, named once so both sides spell them the same.
    public static final String NO_MACHINE = "mymod/no_machine";
    public static final String TOO_FAR = "mymod/too_far";
}
```

**3. The machine** (your mod's own block entity; CrystalGraphics needs nothing from it). It keeps the numbers; its
constructor and saving are as for any block entity.

```java
public class MachineBlockEntity extends BlockEntity {
    float heat;
    int fuel;
    String ownerName = "nobody";
    // ... constructor, load and saveAdditional as for any block entity ...
}
```

**4. The server's answer** (common code, at init). The handler is told who asked (`peer`). It checks that the
position holds a machine and that the player is close enough to be using it: a client could send any position, and
the server decides.

```java
MachineNet.STATS.onServer((peer, packed, reply) -> {
    ServerPlayer player = (ServerPlayer) peer.player();
    if (player == null) return;                             // they left while asking: nobody to answer
    BlockPos pos = BlockPos.of(packed);
    if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 8 * 8) {
        reply.fail(MachineNet.TOO_FAR);
        return;
    }
    if (!(player.level().getBlockEntity(pos) instanceof MachineBlockEntity machine)) {
        reply.fail(MachineNet.NO_MACHINE);
        return;
    }
    reply.ok(statsOf(machine));
});

static Stats statsOf(MachineBlockEntity machine) {
    return new Stats(machine.heat, machine.fuel, machine.ownerName);
}
```

**5. The client's screen** (client code). It asks once when it opens, shows "Loading..." until the answer comes, and
cancels the question if the player closes it first.

```java
public class MachineScreen extends Screen {
    private final BlockPos pos;
    private String status = "Loading...";
    private CgReply<Stats> pending;

    public MachineScreen(BlockPos pos) {
        super(Component.literal("Machine"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        if (pending != null) return;                        // init runs again on every resize: ask once
        pending = MachineNet.STATS.ask(pos.asLong())
                .then(stats -> status = "Heat " + stats.heat() + "   Fuel " + stats.fuel() + "   Owner " + stats.owner())
                .onError(error -> status = switch (error.code()) {
                    case MachineNet.TOO_FAR -> "Too far away";
                    case MachineNet.NO_MACHINE -> "The machine is gone";
                    case CgRequest.UNSUPPORTED -> "The server does not have this mod";
                    default -> "Could not load (" + error + ")";
                });
    }

    @Override
    public void removed() {
        pending.cancel();                                   // closed before the answer: drop the question
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, status, width / 2, height / 2, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
```

What happens, in order: the screen opens and calls `ask`; the question reaches the server; the handler runs on the
server thread, checks, and calls `reply.ok`; the answer comes back; the `then` callback runs on the client thread
and the next frame draws it. Had the player walked away first, `reply.fail(TOO_FAR)` would have sent the error
instead, and the `onError` callback would have run.

The other way round, the server asks one client:

```java
public static final CgRequest<Integer, String> SETTING =
        CgRequest.toClient("mymod:client/setting", CgCodecs.INT, CgCodecs.STRING);
SETTING.onClient((key, reply) -> reply.ok(localSetting(key)));                     // client entry point
SETTING.ask(playerId, 3).then(value -> remember(playerId, value));                 // server
```

- **`ask` answers a `CgReply`**: `then` hears the answer, `onError` a failure, `always` either, once, on the asking
  side's game thread. It composes with jobs and other asks (`CgReply.both`, `CgReply.all`). A failure nobody listens
  for is logged.
- **The answer may come later** than the handler returns: keep the `reply` and call `ok` when you have it. A reply
  answers once; later calls are ignored. `reply.fail("mymod/no_such_machine")` sends an error instead.
- **Work too heavy for the tick** answers from a job: `reply.from(CgJob.run(() -> scan(snapshot)))` sends what the
  job returns, or its failure. Hand the job a snapshot, never the level ([`JOBS.md`](JOBS.md) §5).
- **Errors you may get**, as `error.code()`: your handler's own text, `CgRequest.UNSUPPORTED` (the other side lacks
  your mod or your version), `CgReplyError.TIMEOUT` (past `STATS.timeout(millis)`), `CgReplyError.CANCELLED` (you
  called `cancel()` on what `ask` returned) and `protocol/handlerFailed` (the handler threw; `error.detail()` says
  what).

---

## 6. Networked objects: state that lives on the server

A message is fire and forget: a player who was not there when you sent it never hears of it. Much of what a mod
shares is not like that. A turret's heat, a ward's owner, a player's mana are **state**: they hold a value now, every
player who can see them should have that value, and a player who arrives later should get it too.

Doing that with messages means tracking who has seen what, resending to newcomers, and sending only what changed.
A networked object does all of it:

- the server holds the object and its fields;
- each client that can see it (each **viewer**) gets a copy when it starts seeing it, with every field's current
  value;
- when you change a field, viewers get the new value at the end of the tick, once, however many times you changed it;
- a viewer who stops seeing it has its copy removed.

Unreal calls this replication; Minecraft's own entity data works the same way.

### Declaring a type

Fields first, then the type that lists them:

```java
public final class Turret {
    public static final CgNetField<Float>   HEAT   = CgNetField.ofFloat("heat");
    public static final CgNetField<Integer> AMMO   = CgNetField.ofInt("ammo");
    public static final CgNetField<UUID>    TARGET = CgNetField.of("target", CgCodecs.UUID);

    public static final CgNetType TYPE = CgNetType.define("mymod:turret").fields(HEAT, AMMO, TARGET).range(96).build();
}
```

- **The type's name** is `namespace:path`, like a message's.
- **A field's name** is short (`"heat"`): it only has to be unique within its type, whose name carries your
  namespace. Lowercase, digits and `_ . / -`.
- **Field kinds**: `ofInt`, `ofLong`, `ofFloat`, `ofDouble`, `ofBool`, and `of(name, codec)` for anything else.
- **List fields in the order a client needs them**: a client's code reacting to one field sees every earlier one
  already in.
- **Finish with `build()`**, at init, on both sides.

### On the server: spawn, change, end

```java
CgNetObject turret = Turret.TYPE.spawn(CgAnchor.at(level, x, y, z))   // where it lives: §7
        .owner(player)                                                 // optional: §10
        .setInt(Turret.AMMO, 50)                                       // its first values
        .start();                                                      // live: viewers get it at the end of the tick

turret.setFloat(Turret.HEAT, 0.4f);   // any time on the server thread
turret.despawn();                     // ends it gracefully; kill() ends it at once
```

| Ending | What viewers see |
|---|---|
| `despawn()` | a graceful end: each viewer's behaviour can finish what it shows (a fade, a power-down) |
| `kill()` | gone at once |
| `.lifetime(ticks)` before `start()` | `despawn()` after that many server ticks |
| its anchor going away | an entity dying, a player leaving: [§7](#7-who-sees-an-object-anchors) |

- **Read fields anywhere**: `getFloat(HEAT)`, `getInt(AMMO)`, `get(TARGET)`. A field never set reads its fallback (0,
  false, null, or what `.fallback(v)` said).
- **Primitives unboxed**: `setFloat`/`getFloat` and friends allocate nothing. `set`/`get` work for any field.
- **Only what changed is sent**: setting a field to the value it already holds sends nothing.
- `Turret.TYPE.forEach(obj -> ...)` and `Turret.TYPE.size()` reach every live object of a type on the server.

### A one-off: fired, not started

Some things happen once and are gone: a spark, a sound. `fire()` instead of `start()` sends it to whoever sees it now
and keeps nothing on the server; a later viewer never sees it.

```java
SPARK.spawn(CgAnchor.at(level, x, y, z)).setFloat(SIZE, 2f).fire();
```

### On a client: react

A client never changes an object; it reacts to one. Three ways, from the simplest:

```java
// 1. One field changed (and every value a new object arrives with)
Turret.TYPE.onChange(Turret.HEAT, (obj, heat) -> glow(obj, heat));

// 2. An event happened: §9
Turret.TYPE.onEvent(Turret.FIRED, (obj, shot) -> flash(obj, shot));

// 3. A behaviour: an object of your own per networked object, for something with a life (a model, a sound loop)
Turret.TYPE.onClient(obj -> new CgNetBehaviour() {
    TurretModel model;
    @Override public void spawned(CgNetObject obj, float age) { model = TurretModel.place(obj.x(), obj.y(), obj.z()); }
    @Override public void changed(CgNetObject obj, CgNetField<?> field) { if (field == Turret.HEAT) model.glow(obj.getFloat(Turret.HEAT)); }
    @Override public void despawned(CgNetObject obj, boolean killed) { model.remove(); }
});
```

A behaviour's calls, in the order they come:

| Call | When |
|---|---|
| `spawned(obj, age)` | it arrived, every field in. `age` is server ticks since it started: above 0 for a player who arrives late, so catch up silently |
| `changed(obj, field)` | one field changed; called per field, in the type's order, after all of the frame's changes are in |
| `received(obj)` | once after a frame's changes, for a reaction needing several fields at once |
| `moved(obj)` | a point-anchored object moved |
| `event(obj, event, value)` | an event, after that tick's changes |
| `tick(obj)` | every client tick while it lives |
| `stopping(obj)` | the server is ending it, and its server behaviour is finishing ([§11](#ending-in-front-of-the-viewers)) |
| `despawned(obj, killed)` | it ended; `killed` true for at once, or for leaving this player's view |
| `done()` | after a graceful end, asked each tick until it answers true; the object is dropped then |

- Install all three **from client code**.
- On a client, `obj.isMine()` says whether this client's player owns it.

---

## 7. Who sees an object: anchors

An object's anchor is where it lives, and that decides which players are its viewers. Choose the one that matches the
thing:

| Anchor | Lives | Seen by |
|---|---|---|
| `CgAnchor.at(level, x, y, z)` | at a point | players in that dimension within the type's `range` (the whole dimension without one) |
| `CgAnchor.on(entity)` | on an entity, moving with it | players tracking the entity, and the entity if it is a player. Ends when the entity dies or changes dimension |
| `CgAnchor.player(player)` | on a player, by profile id: across deaths and dimensions | that player alone; with the type's `visibleToTrackers()`, the players tracking them too |
| `CgAnchor.chunk(level, chunkX, chunkZ)` | in a chunk | players with that chunk loaded. While it is unloaded the object waits, seen by nobody |
| `CgAnchor.dimension(level)` | in a dimension | every player in it |
| `CgAnchor.global()` | nowhere in particular | every player |

- `level` is a level, an entity in it, or a dimension id (`"minecraft:overworld"`).
- **A point-anchored object can move**: `obj.moveTo(x, y, z)`; who is in range is worked out again.
- **An entity or player anchor follows by itself**: `obj.x()` reads where it is now, on the server.
  `obj.entity()` is the entity on either side: the server's, or the one of `obj.entityId()` in a client's level.
- **A player who is away** (logged out) leaves a player-anchored object waiting, `obj.isDormant()`, unless the type
  is not persisted, when it ends.
- **A rule of your own** replaces the anchor's: `.visibleTo((obj, peer) -> sameTeam(peer.id(), obj))` on the type,
  asked once a tick per object and player. Never across dimensions.
- **On the server, `obj.level()`** is the level it is in, for what your code asks of the world:
  `CgWorldQueries.of(obj.level())`, `CgCollisionWorld.of(obj.level())`.

---

## 8. Data on a player or a chunk

Often the object *is* data about something: a player's mana, a chunk's pollution. A **per-holder** type has exactly
one object per holder, made the first time you ask for it:

```java
public static final CgNetField<Integer> MANA = CgNetField.ofInt("mana").fallback(100);
public static final CgNetField<Integer> MAX = CgNetField.ofInt("max").fallback(100);
public static final CgNetType MANA_BAR = CgNetType.define("mymod:mana").fields(MANA, MAX)
        .perHolder().persisted().copyOnDeath().build();

CgNetObject bar = MANA_BAR.of(player);                        // the player's: made now if absent
bar.setInt(MANA, Math.min(bar.getInt(MAX), bar.getInt(MANA) + 1));

CgNetObject smog = POLLUTION.ofChunk(level, chunkX, chunkZ);   // a chunk's
```

- `of(player)` takes a player entity or a profile id, online or not; `of(entity)` any other entity.
- **`find(holder)`** only looks, on either side: on a server the holder's object if it has one (null, and nothing
  made, if not); on a client this client's copy of it, null until it arrives. A client reaches its own object, to
  send it a command, with `find(localPlayer)`.

  ```java
  CgNetObject mine = MANA_BAR.find(Minecraft.getInstance().player);   // client
  if (mine != null) mine.send(CAST, spell);
  ```
- **`persisted()`** keeps it with the world ([§13](#13-saving-with-the-world)).
- **`copyOnDeath()`** keeps a player's object through death; without it, dying despawns it.
- **`visibleToTrackers()`** lets the players around them see it too: a health bar over their head.
- A per-holder type's objects come only from `of` and `ofChunk`; `spawn` throws.

---

## 9. Events and commands

Fields are what an object *is*. Two more things travel:

### Events: something happened (server to viewers)

```java
public static final CgNetEvent<Shot> FIRED = CgNetEvent.of("fired", Shot.CODEC);
public static final CgNetEvent<Void> JAMMED = CgNetEvent.of("jammed");
// listed in the type: .events(FIRED, JAMMED)

turret.emit(FIRED, new Shot(dx, dy, dz));              // server: every viewer
turret.emitToOwner(JAMMED, null);                      // server: its owner alone
Turret.TYPE.onEvent(FIRED, (obj, shot) -> flash(shot)); // client
```

- An event arrives after the tick's changes, so a client reading fields sees the state the event happened in.
- **A viewer who was not watching that tick never gets it**, and a later viewer never does: an event is a moment,
  not state. What a newcomer must know is a field.
- An event emitted in the tick an object despawns still reaches its viewers, before the end.
- **An event emitted often** (hits, shots) takes a `CgNetWire` instead of a codec: you write its fields straight into
  the frame and read them back, so neither side allocates per event. Its javadoc has the pattern: one mutable value
  refilled per emit on the server, one reused instance read back on the client.

  ```java
  public static final CgNetEvent<Shot> SHOT = CgNetEvent.of("shot", Shot.WIRE);
  ```

### Commands: a client asks (client to server)

```java
public static final CgNetCommand<Aim> AIM = CgNetCommand.of("aim", Aim.CODEC)
        .rate(CgRatePolicy.throttle(50))                          // the client sends at most 20 a second
        .sanitizedBy((obj, aim) -> aim.clampedTo(obj.getFloat(ARC)));
// listed in the type: .commands(AIM)

Turret.TYPE.onCommand(AIM, (obj, sender, aim) -> obj.set(TARGET, aim.target()));   // server, at init
myTurret.send(AIM, new Aim(yaw, pitch));                                         // client, its owner
```

- **Only the owner may send a command**, and only while they see the object, unless it says `fromAnyViewer()`.
  Anything else is refused and counted (`AIM.refused()`), never handled.
- **Sanitize, don't reject**: `sanitizedBy` turns an arriving value into one a fair player could have sent (clamp the
  aim to the turret's arc). Return null to drop it.
- **Rates**: `rate(...)` is how often the client lets one go (`CgRatePolicy.IMMEDIATE`, `DRAGGING`, `TYPING`,
  `throttle(ms)`); a throttled command always sends its last value, so the end of a drag is never lost.
  `perSecond(n)` and `burst(n)` are how many the server accepts from one player.

---

## 10. Owners, and who is sent which field

An object may have an **owner**: a player it belongs to. Give one with `.owner(player)` before `start()`; a player
anchor's player owns it by default.

The owner is the one who may send commands, and fields can treat them apart:

| Field rule | Who is sent it |
|---|---|
| (none) | every viewer |
| `.ownerOnly()` | the owner alone: their ammo, their cooldowns, hidden information |
| `.skipOwner()` | every viewer but the owner, who showed it already |
| `.initialOnly()` | every viewer, once, when they start seeing it: a seed, a colour that never changes |
| `.serverOnly()` | nobody: kept, and saved, on the server only |
| `.predicted()` | every viewer, and the owner may set it themselves ([§12](#predicted-fields)) |

```java
public static final CgNetField<Integer> AMMO = CgNetField.ofInt("ammo").ownerOnly();
public static final CgNetField<Integer> SEED = CgNetField.ofInt("seed").initialOnly();
public static final CgNetField<Long> LAST_FIRED = CgNetField.ofLong("last_fired").serverOnly();
```

---

## 11. The server deciding: server behaviours

Some objects need logic that runs on the server every tick: a turret that cools down and picks targets, a ward that
drains mana. That logic is a **server behaviour**, the authority over each object of the type:

```java
Turret.TYPE.onServer(obj -> new CgNetBehaviour() {          // common, at init
    @Override public void tick(CgNetObject obj) {
        obj.setFloat(Turret.HEAT, Math.max(0, obj.getFloat(Turret.HEAT) - 0.01f));
        if (obj.getFloat(Turret.HEAT) == 0 && hasTarget(obj)) obj.emit(Turret.FIRED, shotAt(obj));
    }
});
```

- One per object, made at its first server tick, ticked every server tick while it lives.
- **What it changes leaves the same tick**: a behaviour's changes are sent after it runs.
- **Not while dormant**: a chunk-anchored object in an unloaded chunk, or a player's while they are away, does not
  tick.
- A behaviour built from what the spawner knows goes on the object instead: `.behaviour(new TurretBrain(target))`
  before `start()`.

### Ending in front of the viewers

An object's end often has its own moment: a turret powers down, a beam's blast goes off as it fades. With a server
behaviour, `despawn()` gives it that time, **in front of the viewers**:

1. The server behaviour hears `despawned(obj, false)`.
2. Each viewer's behaviour hears `stopping(obj)`, so it can wind down. Viewers keep the object: what the server
   behaviour changes and emits still reaches them. Nobody new starts seeing it.
3. The server behaviour keeps ticking until its `done()` answers true, or for the type's `linger` (200 ticks unless
   said).
4. Then every viewer hears `despawned(obj, false)`.

```java
Turret.TYPE.onServer(obj -> new CgNetBehaviour() {
    int cooling = -1;
    @Override public void tick(CgNetObject obj) { if (cooling > 0 && --cooling == 0) obj.emit(VENTED, null); }
    @Override public void despawned(CgNetObject obj, boolean killed) { cooling = 20; }
    @Override public boolean done() { return cooling == 0; }   // a second of cooling, then gone
});
```

- `obj.isStopping()` says it is in that phase, on either side.
- `kill()` ends it at once, stopping or not; nothing lingers.

---

## 12. Showing it at once: prediction

A round trip to the server takes time: 50 ms on a good connection, far more on a bad one. If a player's own action
only shows once the server answers, the game feels sluggish. **Prediction** shows it at once on that player's client,
and lets the server confirm or correct it.

### Predicted objects

```java
SPARK.acceptPredictionsFrom((peer, spark) -> withinReach(peer, spark));   // server, at init: which to accept
SPARK.predict(CgAnchor.at(x, y, z)).setFloat(SIZE, 1f).fire();            // client, the player who struck
```

- **Accepted**: the server makes the object real and takes the client's copy over. The predicting player sees one
  spark, never two.
- **Refused** (no rule, the rule said no, or no answer within the type's `predictionTimeout`): the client's copy is
  killed, or finishes gracefully with the type's `keepRefused()`. `SPARK.refusedPredictions()` counts them.
- **The rule may change the proposal** before accepting it: clamp its fields to what is fair.
- On a client, an anchor names no level: `CgAnchor.at(x, y, z)` is in the player's dimension, and
  `CgAnchor.onEntity(entityId)` names an entity by its network id.

### Predicted fields

A field the owner changes constantly (a turret's aim) can be set on the owner's client itself, shown there at once,
and sent to the server, which checks it:

```java
public static final CgNetField<Float> YAW = CgNetField.ofFloat("yaw")
        .predicted(CgRatePolicy.throttle(50))            // leaves the client at most 20 times a second
        .sanitizedBy((obj, yaw) -> clampToArc(obj, yaw)); // the server's say

myTurret.setFloat(YAW, yaw);   // client, its owner: applied here now, sent to the server
```

The other viewers are sent the sanitized value; the owner is sent it only when sanitizing changed it.

---

## 13. Saving with the world

A `persisted()` type's live objects are saved with the world, under its folder (`crystalgraphics/objects/`), when
the server stops and every five minutes, and come back when it starts again, before any player joins.

- Each object keeps its anchor, owner, fields (`serverOnly()` ones too) and age; a server behaviour is made again and
  hears `spawned` with that age.
- **Not saved**: fired objects, and objects anchored on an entity (`persisted()` with `CgAnchor.on` throws). Anchor
  to a point, a chunk or a player instead.

---

## 14. Saving your own data, and NBT

### Files of your own: no NBT needed

Data that is not a networked object saves with the codec it is sent with. `CgFormats` writes it to a file of your
own, compressed, in the same binary format the wire uses:

```java
Path file = worldDir.resolve("mymod/players/" + uuid + ".dat");
CgFormats.write(file, PlayerData.CODEC, data);                 // makes the folders; replaces the file whole
PlayerData data = CgFormats.read(file, PlayerData.CODEC);      // null when there is no file yet
```

A crash mid-save leaves the last save, never half of one: the file is written beside itself and moved over the old.

### When Minecraft wants NBT

Minecraft's own containers (an entity's saved data, a block entity's, an item's) take only its tags. `CgFormats`
turns any codec's value into them and back, on every version, so you still write no NBT code:

```java
// saving
tag.put("character", CgFormats.toNbt(Character.CODEC, character));   // typed as put takes it
// loading
Character character = CgFormats.fromNbt(Character.CODEC, tag.get("character"));
```

- A map becomes a compound, a list a list tag, a number the tag of its width, `BYTES` a byte array; a boolean is a
  byte, as in Minecraft's own `NbtOps`.
- NBT's rules apply: map keys must encode as text (`mapOf(CgCodecs.STRING, …)`, or a codec writing a string), and a
  list holds one kind of value.
- `CgFormats.nbt()` is the same format as a `CgDynamicOps`, for a codec read or written by hand.
- NBT exists only inside Minecraft: in a test or the harness, `toNbt` throws, naming the `crystalgraphics:host_formats`
  slot. Test the codec with `CgFormats.write`/`read`, or `CgPlainOps`.

---

## 15. Testing without Minecraft

`CgNetTestRig` is a server and any number of clients in one JVM, with real connections and frames, a tick you step
and latency you choose. Test your types in a plain JUnit test:

```java
@Test
public void aTurretsHeatReachesWhoeverIsNear() {
    try (CgNetTestRig rig = CgNetTestRig.of(2)) {
        CgNetObject turret = Turret.TYPE.spawn(rig.at(0, 64, 0)).start();
        rig.client(1).moveTo(500, 64, 0);                       // out of range
        turret.setFloat(Turret.HEAT, 0.5f);
        rig.tick(2);
        assertEquals(0.5f, rig.client(0).visible(Turret.TYPE).get(0).getFloat(Turret.HEAT), 0f);
        assertEquals(0, rig.client(1).visible(Turret.TYPE).size());
    }
}
```

| Rig call | Does |
|---|---|
| `rig.at(x, y, z)` | a point anchor in the rig's dimension (`CgNetTestRig.DIMENSION`) |
| `rig.entity(x, y, z)` | an entity to anchor on; `.moveTo`, `.kill()`, `.dimension(id)` |
| `rig.client(i)` | a client: `.id()`, `.moveTo`, `.die()`, `.respawn()`, `.disconnect()`, `.connect()`, `.visible(type)`, `.predict(type, anchor)` |
| `rig.tick(n)` | n server ticks, and the clients' |
| `rig.latencyTicks(n)` | frames arrive n ticks late |
| `rig.unloadChunk`, `loadChunk` | for chunk anchors |
| `rig.saveTo(folder).restart()` | the server stops, saves, starts again; clients reconnect |

- **Declare types before `of`**, as at mod init.
- One rig at a time; it replaces the process's network while open and resets it on `close()`.

---

## 16. Performance and debugging

### What it costs

- **A quiet tick costs nothing**: the layer allocates nothing per tick and sends nothing for an object that did not
  change.
- **A change costs its bytes, once a tick**: 50 changed float fields reach a player as about 480 bytes. One frame
  per player per tick carries every object's changes; new objects wait on the budget below.
- **Viewers are worked out per object per tick**: cheap for anchors, your own work for a `visibleTo` rule, which is
  asked per object and player.

### The budget: a crowd arrives over several ticks

Each player is sent about `CgNetObjects.budget()` bytes a tick (32 KB unless said). Changes to objects they already
have always go. New objects fill the rest, **nearest first**, and the others wait for the next tick: a player joining
a world with 5000 objects gets them over ten ticks, the closest in the first, instead of in one 250 KB frame.

```java
CgNetObjects.budget(64 * 1024);   // at init, server: 64 KB a tick per player
```

- Also `-Dcrystalgraphics.net.budget=<bytes>`.
- **First**: a player's own objects and anything not at a place (on a player, a dimension, everyone). A prediction
  is never held back.
- **Then by distance**, with credit for age, so a far object is not kept waiting forever by near ones arriving.
- New objects always get a quarter of the budget, so a tick full of changes still lets them in.
- An event on an object a player has not been sent yet does not reach them: it is a moment, and they were not there.

### Small things are never stuck behind big ones

A message that fits in one frame (an object frame, a ping, a command, most messages) leaves before any part of a large
one, and a large message leaves part of the connection's window unspent for them. A 4 MB transfer to a player
therefore does not delay their object changes or your small messages: they arrive the next tick, as they would
without it. The transfer itself takes about a second.

### Measured

On a Minecraft 26.2 dedicated server with two clients (`CgNetStress`, below): 5000 objects with a tenth changing each
tick cost the server 0.5 ms a tick for the network and reach each player as about 1.8 KB a tick; changes and events
arrive within 60 ms; under a 4 MB transfer, within 65 ms; commands are handled within 55 ms; a quiet tick sends and
allocates nothing. On 1.7.10 the same run passes with the same margins.

```bash
# The stress run: a server, then two clients joining it. It starts once both are in and reports after two minutes.
./gradlew :runtime:mc:modern:fabric:26.2:runServer -PcgLiveServer -PcgAcceptEula -PcgNoLanguage -Dcrystalgraphics.net.stress=true
./gradlew :runtime:mc:modern:fabric:26.2:runClient -PcgJoin=localhost:25599 -PcgUser=Alice -PcgNoLanguage -Dcrystalgraphics.net.stress=true
./gradlew :runtime:mc:modern:fabric:26.2:runClient -PcgJoin=localhost:25599 -PcgUser=Bob -PcgClientDir=runs/client-bob -PcgNoLanguage \
        -Dcrystalgraphics.net.stress=true
```

The report is in the server's log and in `runs/server/net-stress.txt`, and each process then ends itself
(`-Dcrystalgraphics.net.stress.keepOpen=true` keeps them running). A second client's directory needs an
`options.txt` (copy `runs/client`'s), or Minecraft's first-launch screen stops it joining.

On 1.7.10 it is `:runtime:mc:1710:runServer -PcgLiveServer` and `:runtime:mc:1710:runClient` with the same flags (Bob's
directory `run/client-bob`), and each client with `-Dcrystalgraphics.demo=false`: a client joining straight from launch
builds the world demo while it logs in, and FML drops a client whose thread is busy for 5 seconds then.

### What it is doing

```java
LOGGER.info(CgNetObjects.report());
// networked objects: 2 types live, 3 objects, 2 players
//   mymod:turret                     2 live   4 seen   96 B a tick   0 refused
//   mymod:mana                       1 live   1 seen   0 B a tick   0 refused
```

- **Trace channel `crystalgraphics.net`** (`docs/PROFILING.md`): per type, each server tick, `net.objects/<type>`,
  `net.viewers/<type>`, `net.bytes/<type>` and `net.refused/<type>`.
- **Counters on the declarations**: `message.skipped()`, `command.refused()`, `command.malformed()`,
  `type.refusedPredictions()`.

### When nothing arrives

| Symptom | Usually |
|---|---|
| A client gets nothing of yours | the declaration ran after the player connected, or on one side only; or that client lacks your mod or version (`skipped()` counts it) |
| An object never shows on a client | the player is out of its `range` or not tracking its entity; the type was declared after they joined; the handler was installed on the server only |
| A command does nothing | the sender is not the owner (`fromAnyViewer()` lifts that), does not see the object, or is over `perSecond`: `refused()` counts it |
| A field shows its fallback | it was never set, or it is `ownerOnly()` and this client is not the owner |
| It works in singleplayer, crashes on a server | client classes reached from common code: install handlers and behaviours from client code |

---

## 17. Questions people ask

### Message, request or networked object?

| You want | Use |
|---|---|
| Tell the other side something once: "play this", "the player pressed purge" | a message |
| Ask and get an answer | a request |
| A thing with values that players should see as they change, including players who arrive later | a networked object |
| Data about a player or a chunk | a per-holder networked object |
| Something that happened to a thing: a shot, a hit | an event on its object |
| A player asking to act on a thing they own | a command on its object |
| A visual effect seen by every player | a cue (`docs/VFX.md` § 6), itself a networked object |

### Do I register a channel, or packets, per loader?

No. CrystalGraphics installs one channel on every loader and version it supports, and everything here travels over
it. You declare in common code and never touch a loader's networking.

### What if a client does not have my mod?

It is sent nothing of yours: the hello tells the server which namespaces each client declared. Nothing is logged per
send; `skipped()` counts it.

### Can a client change a networked object?

Only through what the server allows: a command, or a predicted field it owns, both checked by the server. Its own
copy is otherwise read-only, and writing to it throws.

### How is this different from Minecraft's own packets?

Minecraft's packets, and each loader's channel API, are the plumbing: you write each packet, send it, and track
who needs what. A message is that plumbing made typed and loader-blind. A networked object is what Minecraft does for
its own entities' data, made available for any state of yours: who sees it, sending only changes, catching up
newcomers, saving. Side by side, with code: [If you have networked a mod before](#if-you-have-networked-a-mod-before).

### Is that how other engines do it?

Yes. Unreal replicates actors and their properties to the connections relevant to them, with RPCs for commands and
events, and owner-only conditions on properties; Unity Netcode has network objects, network variables with
owner-only read permission, and RPCs. Anchors are this layer's relevancy; `ownerOnly` is Unreal's `COND_OwnerOnly`;
prediction with server correction is the same model as both.

### Where is the code?

`com.crystalgraphics.net` (messages, requests, audiences, connections) and `com.crystalgraphics.net.object` (networked
objects), each class's javadoc carrying its examples. How the hosts wire it into each Minecraft is
`docs/MINECRAFT_INTEGRATION.md` § *Connections*.
