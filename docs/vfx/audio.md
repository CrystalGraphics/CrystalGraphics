# Audio

Sound has its own guide: **[`docs/AUDIO.md`](../AUDIO.md)**. For an effect's sound, start at its
[§10, *In cues and sequences*](../AUDIO.md#10-in-cues-and-sequences):

- **A cue's `sounds`**: played once at application or removal, or looping with the cue, each placed or riding its
  target's socket as its `placement` says.
- **A sequence's `sound` track**: on its sample, bound to a role, or riding stepped `attach` points on a track that
  binds nothing.
- **A sequence's `subtitle` track** and a kit's `subtitle_style.yaml`
  ([§9](../AUDIO.md#9-subtitles)).

```yaml
# a cue's looping sound on the caster's hand
looping:
  sounds: [ { sound: mymod:sounds/beam_loop.ogg, fade_out: 0.5, placement: { socket: hand_r, attach: attach_to_target } } ]
```

```java
// the same sound from Java, riding the player's hand until stopped
long id = CgSounds.playAttached("mymod:sounds/beam_loop.ogg", CgEntityAttachments.of(player, CgEntityAttachments.RIGHT_HAND));
```
