# Input — the guide

CrystalGraphics hands your mod the player's mouse and keyboard on every version and loader it runs on, in one
vocabulary, and lets you take what is yours before anything else sees it. A panel you draw over the inventory, a field
you type into over the chat, a key that opens your screen: you say what you want, and the engine's hosts deliver it.
You never subscribe to a loader's events, never write a mixin, and never convert a coordinate.

It comes in two parts:

- **Input stages**: a listener registered on a stage hears every event heading for the screen in front, and taking one
  (consuming it) keeps the screen, and the game, from seeing it.
- **Key bindings**: a key the player can rebind, declared once, listed in the game's controls, and run when pressed.

Read sections 1 to 3 first: they give you the words and the one picture everything else follows. After that, jump to
what you need.

## If you have handled input in a mod before

You know the routine. Here is "a click on my panel over the inventory does not reach the inventory", the usual way, and
then here.

**The usual way.** One hook per loader and per era, coordinates in the GUI's scale, and a mixin where there is no
event:

```java
// Forge 1.19+, NeoForge
@SubscribeEvent
public static void onClick(ScreenEvent.MouseButtonPressed.Pre event) {
    if (panel.contains(event.getMouseX(), event.getMouseY())) {     // GUI-scaled: wrong at any other GUI Scale
        panel.click(event.getButton());
        event.setCanceled(true);
    }
}
// Forge 1.17-1.18: GuiScreenEvent.MouseClickedEvent.Pre; 1.13-1.16: another name again
// Fabric: no cancellable screen event at all; chain GLFW's mouse callback yourself, or ScreenMouseEvents per screen
// Forge 1.8-1.12: GuiScreenEvent.MouseInputEvent.Pre, reading LWJGL 2's queue, y measured from the bottom
// 1.7.10: no event; a mixin on GuiScreen.handleInput
// Minecraft 26.3: SDL instead of GLFW; every key number changes
```

**Here.**

```java
CgInputStage.SCREEN.register(0, new CgInputListener() {
    @Override public boolean mouseButton(CgSystemInput.Mouse.Event e) {
        if (!panel.contains(e.x(), e.y())) return false;   // raw pixels from the top left, on every version
        panel.click(e.button(), e.state());                // e.button(): CgMouseCodes.LEFT_BUTTON, ...
        return true;                                       // taken: the inventory never sees it
    }
});
```

One listener, every version and loader; the pixels are the window's own, the button and key numbers the engine's.

### What goes away

| The struggle | Usually | Here |
|---|---|---|
| **One hook per loader and era** | `ScreenEvent`, `GuiScreenEvent`, Fabric callbacks, a 1.7.10 mixin | one listener on `CgInputStage.SCREEN`; every era's host fires it |
| **Coordinates** | GUI-scaled doubles, y from the bottom on LWJGL 2 | raw window pixels from the top left, as integers |
| **Key numbers** | LWJGL 2's, GLFW's or SDL's, by version | `CgKeyCodes`, the same everywhere |
| **The wheel** | 120 a notch, or 1, up positive or down | one a notch, positive rolled down |
| **Taking an event** | cancel a loader event, or don't forward a callback | return true |
| **Pointer moves** | no move event for a screen on 1.20 | a move every frame, with the pointer where it is |
| **A rebindable key** | a `KeyMapping`, registered in the right event per loader; 26.3's numbers | `CgKeyBindings.declare`, once |

## Contents

1. [The words you need](#1-the-words-you-need)
2. [Quick start](#2-quick-start)
3. [The picture: who sees an event first](#3-the-picture-who-sees-an-event-first)
4. [Listening](#4-listening)
5. [The events](#5-the-events)
6. [Taking an event](#6-taking-an-event)
7. [Key bindings](#7-key-bindings)
8. [A stage of your own](#8-a-stage-of-your-own)
9. [What each host does](#9-what-each-host-does)
10. [Testing](#10-testing)
11. [Questions people ask](#11-questions-people-ask)

Where each class lives is `platform/CLAUDE.md`, which loads itself for anyone editing a platform file.

---

## 1. The words you need

| Word | What it is | Class |
|---|---|---|
| **Stage** | A point where the player's input passes through: listeners there hear it first. `SCREEN` is the screen in front. | `CgInputStage` |
| **Listener** | Your code at a stage: one method per kind of event, each answering whether it took the event. | `CgInputListener` |
| **Event** | One move, button, wheel turn, key or character, in the engine's vocabulary. | `CgSystemInput.Mouse.Event`, `CgSystemInput.Keyboard.Event` |
| **Consume** | Take an event: return true, and nothing after you, the screen included, sees it. | — |
| **Registration** | What `register` returns; `close()` takes the listener off. | `CgInputStage.Registration` |
| **Host** | The code for one Minecraft era that builds each event and fires the stage. It decides nothing. | `ScreenInputModern`, `ScreenInputLegacy`, `ScreenInput1710` |
| **Pointer grab** | Whether the game holds the cursor (it is the camera): a fact an event cannot tell you. | `CgInput.pointerGrabbed()` |
| **Key binding** | A key the player can rebind, in the game's controls, with an action run when it is pressed. | `CgKeyBindings.Binding` |

How they fit together:

```
the player                  a host (per era)                 the stage                      the screen
----------                  ----------------                 ---------                      ----------
clicks, types       ──▶     builds the event in the   ──▶    each listener in order:  ──▶   sees what no
                            engine's vocabulary              true takes it, and the          listener took
                                                             walk stops there

presses a bound key ──▶     its key binding           ──▶    your action, on the client tick (no screen up)
```

---

## 2. Quick start

### Take the clicks that land on your panel

```java
CgInputStage.Registration taking = CgInputStage.SCREEN.register(0, new CgInputListener() {
    @Override public boolean mouseButton(CgSystemInput.Mouse.Event e) {
        return panel.visible() && panel.contains(e.x(), e.y()) && panel.click(e.button(), e.state());
    }
    @Override public boolean scrolled(CgSystemInput.Mouse.Event e) {
        return panel.visible() && panel.contains(e.x(), e.y()) && panel.scroll(e.wheelDelta());
    }
});
taking.close();                                            // when the panel goes for good
```

### Type into your field, over any screen

```java
CgInputStage.SCREEN.register(0, new CgInputListener() {
    @Override public boolean key(CgSystemInput.Keyboard.Event e) {
        return field.focused() && field.key(e.key(), e.pressed());       // e.key(): CgKeyCodes.KEY_BACK, ...
    }
    @Override public boolean character(CgSystemInput.Keyboard.Event e) {
        return field.focused() && field.type(e.character());
    }
});
```

### A key that opens your screen

```java
// your client listener (CgLifecycleListener, declared "client" in META-INF/crystalgraphics/listeners)
@Override public void onPreInit() {
    CgKeyBindings.declare("mymod:map", CgKeyCodes.KEY_M, "mymod:keys", MapScreen::open);
}
```

```json
// assets/mymod/lang/en_us.json
{ "key.mymod.map": "Open Map", "key.category.mymod.keys": "My Mod" }
```

That is most of what mods do. Everything else builds on these three.

---

## 3. The picture: who sees an event first

**Listeners first, in order; the screen gets what is left.** This is the one idea to hold on to. Browsers dispatch an
event the same way: a handler that calls `preventDefault` keeps the page's default from running.

1. The player clicks, turns the wheel, presses a key or types.
2. That era's host builds the event in the engine's vocabulary and fires the stage.
3. Each listener is asked, lowest order first. The first to return true takes it, and the walk stops.
4. Nothing took it: the screen in front handles it as it always would.

### Threads

| Call | Thread |
|---|---|
| A listener's methods | the client thread, where the game delivers input, on every version |
| `register`, `close` | any thread |
| A key binding's action | the client thread, on the client tick |
| `CgKeyBindings.declare` | any thread, early (§7) |

### The rules

- **`SCREEN` fires only while a screen is up**: a menu, an inventory, the chat, a screen of your own. In play, with no
  screen, input is the game's.
- **A move reaches every listener and is never taken**: something under the pointer has to keep tracking it.
- **A listener that throws** is logged with the stage's name and counts as not having taken the event. It stays
  registered.
- **Close what you register.** A registration kept past its owner fires into a dead object.

---

## 4. Listening

```java
CgInputStage.Registration r = CgInputStage.SCREEN.register(order, listener);
r.close();
```

- **`order`**: lower runs first; listeners of one order run in registration order. Give a panel drawn over another
  mod's a lower order than that mod's, so the one on top is asked first.
- **Every method defaults to "not taken"**, so a listener writes only what it wants:

| Method | Hears |
|---|---|
| `mouseMoved(Mouse.Event)` | the pointer moved. Every listener hears it; the answer is ignored |
| `mouseButton(Mouse.Event)` | a button went down (`state()` true) or up |
| `scrolled(Mouse.Event)` | the wheel turned |
| `key(Keyboard.Event)` | a key went down (`pressed()` true) or up. Its `character()` is 0 |
| `character(Keyboard.Event)` | a character was typed. Its `key()` is `KEY_NONE` |
| `composition(String text, int caret)` | an input method's text in progress (Japanese, Chinese); an empty text ends it |
| `wants()` | whether you want input at all right now (below) |

**`wants()` is a hint to the host, not a filter.** Some hosts do work to produce input (legacy Forge drains a screen's
queue every frame while a listener wants it). Answer false while your panel is hidden and they skip that work. Your
other methods are still called: check your own state in them too.

---

## 5. The events

**One vocabulary on every version.** The host converts once; a listener never sees GLFW, SDL, LWJGL 2 or a GUI scale.

`CgSystemInput.Mouse.Event(x, y, dx, dy, button, state, wheelDelta, millis)`:

| Field | Means |
|---|---|
| `x`, `y` | the pointer, in the window's own pixels, from its top left. Not the GUI's scaled units |
| `dx`, `dy` | how far it moved since the last move, on a move; down is positive |
| `button` | `CgMouseCodes`: `LEFT_BUTTON`, `RIGHT_BUTTON`, `MIDDLE_BUTTON`, ...; `NONE` for a move or a wheel |
| `state` | down (true) or up, for a button |
| `wheelDelta` | notches, positive rolled **down**; 0 unless the wheel turned |
| `millis` | when a button changed; -1 on a move or a wheel, so a slow double click never counts as three |

`CgSystemInput.Keyboard.Event(character, key, pressed, repeat, millis)`:

| Field | Means |
|---|---|
| `key` | `CgKeyCodes`, LWJGL 2's numbers: `KEY_A`, `KEY_RETURN`, `KEY_ESCAPE`, ... `KEY_NONE` on a character |
| `character` | the typed character on a character; 0 on a key |
| `pressed` | down (true) or up |
| `repeat`, `millis` | not set by the screen hosts; a held key's repeats arrive as presses |

- **A key and its character are two events.** Pressing A sends `key(KEY_A, down)` then `character('a')`. Take the
  character to type text; take the key for shortcuts and editing (Backspace, arrows, Enter).
- **A held key repeats as presses** on every loader, so holding Backspace deletes on.
- **Moves come once a frame on 1.13 to 26.3**, with the pointer where it is (1.20 posts no move to a screen), from the
  hook that fires `CgRenderStage.SCREEN`: Fabric 1.14.4-1.15.2 have none, so no moves there. Legacy Forge and 1.7.10
  send one per mouse event LWJGL queued.
- **To convert to GUI units**, divide by the game's GUI scale; to Minecraft's own key numbers, you do not need to:
  stay in `CgKeyCodes`.

### What is held now, and the clipboard

An event says what changed; what is held as it lands, and whether the game holds the pointer, is asked of `CgInput`,
in the same codes. The clipboard is `CgClipboard`.

```java
@Override public boolean key(CgSystemInput.Keyboard.Event e) {
    boolean ctrl = (CgInput.modifiers() & CgModifiers.CTRL) != 0;
    if (ctrl && e.key() == CgKeyCodes.KEY_V && e.pressed()) { field.insert(CgClipboard.get()); return true; }
    if (ctrl && e.key() == CgKeyCodes.KEY_C && e.pressed()) { CgClipboard.set(field.selection()); return true; }
    return false;
}
boolean dragging = CgInput.isMouseDown(CgMouseCodes.LEFT_BUTTON);
```

- **The window toolkit answers both** (GLFW, SDL from 26.3, LWJGL 2), filled by each host's client install; a host
  building an event turns the toolkit's numbers into the engine's with `CgInput.key` and `mouseButton`.
- **With no toolkit** (a dedicated server, a test) nothing is held and the clipboard is empty: a test provides its
  own (`CgPlatform.provide(CgInput.HOST, ...)`).
- `CgClipboard.get()` is never null; text it cannot read is `""`.

---

## 6. Taking an event

**Return true to take it, and take only what is yours.**

```java
@Override public boolean mouseButton(CgSystemInput.Mouse.Event e) {
    if (!panel.contains(e.x(), e.y())) return false;   // not ours: the screen gets it
    panel.click(e.button(), e.state());
    return true;                                       // ours: nothing else sees it
}
```

- **Take the release with the press.** A press you took and a release you did not leaves the screen seeing a release
  it never saw pressed, and the other way round leaves a drag stuck on. Once you take a press, take its release
  wherever the pointer is: keep a flag.
- **Take keys only while you own the keyboard.** A listener taking keys while nothing of yours is focused leaves the
  game's controls dead until the player clicks away. Gate every `key` and `character` on your own focus.
- **Escape**: take it only to close something of yours (a menu, a dialog). Left alone, the screen closes on it.
- **A move is never taken**: return what you like, everyone hears it.

---

## 7. Key bindings

A key the player can rebind is a `CgKeyBindings.Binding`: declared once, in `CgKeyCodes`, listed in the game's controls
under its category on every version, its action run once per press.

```java
static final CgKeyBindings.Binding MAP =
        CgKeyBindings.declare("mymod:map", CgKeyCodes.KEY_M, "mymod:keys", MapScreen::open);
```

| Argument | Is |
|---|---|
| `id` | `namespace:path`, unique: what the binding is |
| `defaultKey` | the key it starts on, a `CgKeyCodes` code; the player's choice is saved by the game |
| `category` | `namespace:path` of the group it is listed under |
| `action` | what a press does: client thread, client tick |

**Its names come from the ids**, one language entry for every version:

| Shown | Translation key | For the example |
|---|---|---|
| The binding | `key.<namespace>.<path>` | `key.mymod.map` |
| Its category | `key.category.<namespace>.<path>` | `key.category.mymod.keys` |

- **Declare early: in a `client` lifecycle listener's `onPreInit`.** Forge and NeoForge take keys only in their
  registration event, and a binding declared after it never appears; it is logged as
  `[cg] key binding '<id>' was declared after the loader registered keys`. Fabric, legacy Forge and 1.7.10 take one at
  any time, but declare early there too.
- **A press counts only while no screen is up**, as the game's own bindings do: in a menu, the key types or does
  nothing. To act on a key over a screen, listen on `SCREEN` instead.
- An id is declared once: a second `declare` with it throws.
- **Client only**: never declare from code a dedicated server runs.

---

## 8. A stage of your own

A stage is a name and a list of listeners, so a mod can make one for input it routes itself (a radial menu, a
controller):

```java
public static final CgInputStage RADIAL = CgInputStage.define("mymod:radial");   // once; a second define throws

// your own hook, on the client thread: fire it, and act only on what nobody took
if (!RADIAL.mouseButton(new CgSystemInput.Mouse.Event(x, y, 0, 0, button, down, 0f, System.currentTimeMillis()))) {
    radial.click(button, down);
}
```

- **`listening()`** is whether anyone registered: skip building events when it is false.
- **`wanted()`** asks every listener's `wants()`: what a hook asks before doing work to produce input.
- You build the event, so you convert: raw pixels from the top left, `CgKeyCodes`, a notch positive rolled down.

---

## 9. What each host does

For those touching a host; a mod never calls these. Each host builds the event and fires `SCREEN`; none decides
anything.

| Era | Hook | Host |
|---|---|---|
| Forge 1.13.2-26.3 | the screen input events (`ScreenEvent.*Pre`, `GuiScreenEvent.*Pre`, by generation), cancelled when taken | `ScreenInputForge` |
| NeoForge 1.20.2-26.3 | `ScreenEvent.*Pre` | `ScreenInputNeoForge` |
| Fabric 1.14.4-26.3 | the window's callbacks, chained at `CLIENT_STARTED`; SDL's event filter from 26.3. Not forwarding is the cancel | `ScreenInputFabric` |
| Forge 1.8-1.12.2 | `GuiScreenEvent.MouseInputEvent.Pre`, `KeyboardInputEvent.Pre`, once per queued event, drained every frame while a listener wants input | `ScreenInputLegacy` |
| Forge 1.7.10 | a mixin on `GuiScreen.handleInput`, which has no event; drained every frame while a listener wants input | `MixinGuiScreen`, `ScreenInput1710` |

Every modern loader builds its events in `ScreenInputModern`, which also fires the frame's move from the hook that
fires `CgRenderStage.SCREEN`. What is held and the pointer grab are `CgInput`'s host: `InputModern` (Minecraft's grab
over GLFW, or SDL from 26.3), `InputLwjgl2` on legacy Forge and 1.7.10.

Key bindings:

| Era | Host | Registered |
|---|---|---|
| Modern | `KeyBindingsModern`, a `KeyMapping` each | Forge's `RegisterKeyMappingsEvent` from 1.19, client setup before it (`KeysForge`); NeoForge's `RegisterKeyMappingsEvent`; Fabric's `KeyBindingHelper` (`KeyMappingHelper` from 26.1) |
| Forge 1.8-1.12.2 | `KeyBindingsLegacy`, a `KeyBinding` each | `ClientRegistry`, as declared |
| Forge 1.7.10 | `KeyBindings1710`, a `KeyBinding` each | `ClientRegistry`, as declared |

---

## 10. Testing

A stage needs no game: fire it from a test as a host would.

```java
@Test
public void aClickOnThePanelIsTaken() {
    Panel panel = new Panel(0, 0, 100, 100);
    CgInputStage stage = CgInputStage.define("test:panel-" + System.nanoTime());     // a fresh stage per test
    try (CgInputStage.Registration r = stage.register(0, new PanelInput(panel))) {
        assertTrue(stage.mouseButton(new CgSystemInput.Mouse.Event(50, 50, 0, 0,
                CgMouseCodes.LEFT_BUTTON, true, 0f, 0L)));                            // inside: taken
        assertFalse(stage.mouseButton(new CgSystemInput.Mouse.Event(150, 50, 0, 0,
                CgMouseCodes.LEFT_BUTTON, true, 0f, 0L)));                            // outside: the screen's
    }
}
```

- **A stage of your own per test**: `define` throws on a second id, and listeners left on `SCREEN` leak into the next
  test.
- **A key binding's action is `binding.press()`**: call it directly. With no host, `declare` records the binding and
  registers nothing.

---

## 11. Questions people ask

### A listener, or a key binding?

| You want | Use |
|---|---|
| A key the player sees in the controls and can rebind, acting in play | `CgKeyBindings.declare` |
| Clicks, typing or the wheel over a screen | a listener on `CgInputStage.SCREEN` |
| A shortcut inside your own screen | your screen's own key handling, or a listener gated on your screen being up |

### Can I hear input during play, with no screen up?

Not through `SCREEN`: in play, input is the game's (movement, attacks, the hotbar). A key that acts in play is a key
binding.

### Why are my coordinates twice what I expect?

They are the window's pixels, not the GUI's scaled units. Divide by the game's GUI scale to compare with a vanilla
widget's position; draw your own panels in pixels and never convert.

### Why did my key binding not show in the controls?

It was declared after the loader registered keys: the log names it. Declare it in a `client` listener's `onPreInit`.

### Why does my field type every letter twice?

You take both the key and its character and act on each. Type from `character`; use `key` for editing keys only.

### Does my listener run on a dedicated server?

No: there is no player input there, and the stage never fires.

### Is that how other engines do it?

Yes. Unreal routes input through a stack of input components, each consuming what it handles before the next is
asked; browsers dispatch an event to handlers that can `preventDefault`; Unity's input system lets a UI layer take an
event before gameplay sees it. Key bindings follow Minecraft's own `KeyMapping`, declared once instead of per loader.

### Where is the code?

`com.crystalgraphics.platform.input` (`CgInputStage`, `CgInputListener`, `CgKeyBindings`, `CgInput`, `CgClipboard`, the
code tables); each toolkit's devices and clipboard under `runtime/lwjgl/` (`InputGlfw`, `ClipboardGlfw`, ...); each era's
host under `runtime/mc/` (`ScreenInput*`, `InputHost*`, `KeyBindings*`). CrystalGUI's desktop is one listener,
`HostInputListener`.
