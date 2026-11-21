# Sides and authority: who decides what

Every Minecraft game runs at least twice: once on the **server**, which keeps the world, and once on each **client**,
which draws it for one player. Even single-player runs a server inside the game. This page is why the entity API is
shaped the way it is: why a client cannot simply set a value, what "owner" means, and what prediction buys.

## The server's word is the truth

```
   client A (Steve)                server                     client B (Alex)
   ┌──────────────┐          ┌──────────────────┐          ┌──────────────┐
   │ a copy       │ ◀─────── │ the zombie       │ ───────▶ │ a copy       │
   │ reads        │  sends   │ rage = 3         │  sends   │ reads        │
   └──────────────┘          └──────────────────┘          └──────────────┘
```

A zombie exists once, on the server. Each client has a copy, refreshed by what the server sends. If client A changed
its copy, B would never know and the server would overwrite it next tick. So:

- **The server changes things**: properties, tags, cues for everyone, moves. Its calls simply happen.
- **A client reads**, and asks the server for anything it wants changed.

This also keeps a modified client from giving itself a thousand ki. Anything a client asks for, the server checks.

## The owner: one client may go first

Waiting for the server makes a player's own actions feel slow: on a 100 ms connection, a key press shows a tenth of a
second late. So one client gets a privilege over one entity: **the player's own client, over that player**. It is the
entity's **owner**.

```
   Steve's client                         server
   press K
   ├─ CHARGING shown now  ──── asks ───▶  rule: may Steve charge?
   │                                      ├─ yes: takes it; everyone sees it
   │  ◀────────────── answer ──────────── └─ no:  refuses
   └─ refused: CHARGING taken back
```

That is **prediction**: the owner shows the change at once, and the server takes it or refuses it. A refusal puts back
everything the prediction showed. `CgEntities.predicted` groups one input's changes under one **prediction key**, so
the server takes or refuses them together.

What the owner may predict is the server's decision, written once at start-up:

```java
CgEntityTags.acceptTags((player, tag, delta) -> tag == CHARGING);
```

## The three positions

Every call on an entity's handle asks which of three a side is in:

| Position | Who | `isAuthority()` | `isOwner()` | Changes |
|---|---|---|---|---|
| **Authority** | the server | true | false | happen |
| **Owner** | the player's own client, over that player | false | true | predicted, then taken or refused |
| **Other** | any client, over anything else | false | false | throw, naming the verb |

A call that throws is a bug in your code, not a player's cheat: the API refuses it before anything is sent.

## What each side works out for itself

Not everything travels. What both sides can see for themselves, such as how fast an entity moves or whether it is on
the ground, is worked out by each side from the game (`CgEntityProperties.MOTION`). Sending it would only add lag.
Your own `local()` fields are the same: each side's own, never sent.

| | Travels | Example |
|---|---|---|
| a property's field | server to clients | ki, rage, a form |
| a `local()` field | never | a glow fading in |
| a tag | server to clients; the owner's predictions to the server | stunned, charging |
| an event | server to clients, once | transformed |
| a cue | its effects, to the clients near it | a hit spark |
| motion | never: each side's own | speed, landed |

## Where next

- The handle these rules apply to: [`../guides/the-gameplay-handle.md`](../guides/the-gameplay-handle.md).
- Prediction in general, for any networked object: [`../../NETWORKING.md`](../../NETWORKING.md) §12.
