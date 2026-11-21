# Weights and relevance: why a hidden clip does not advance

For the reader who wonders why a clip restarts after being blended out, or why a sync group's leader changes.

## In plain words

A graph mixes poses like a sound desk mixes tracks: each has a slider, its **weight**, from 0 (silent) to 1 (full).
A track whose slider is at 0 is not just quiet: it is switched off. It does not play on in the background, so when its
slider comes up again it starts where it stopped, or from the beginning if it asks to.

## How weights flow

The output asks its input at weight 1. A blend of two at alpha 0.3 asks one at 0.7 and the other at 0.3. A layer
asks its base fully and its top pose at the layer's weight. A state machine asks each state at its share of the
crossfade. So every node knows how much it shows.

## Relevance

- **A node at weight 0 is irrelevant**: it is not updated, and its clips do not advance.
- **A node becoming relevant again** is initialized: a player with `resetOnActivate()`, or a state with
  `alwaysResetOnEntry()`, starts over; others resume.
- **A sync group's leader** is its heaviest relevant member, so the leader changes as a blend moves.

## What follows

- A run blended to 0 under a fall, then back, resumes its stride unless told to reset.
- A cheap graph shows few things at once: weight is cost.
- A blend space only advances the clips near its input.

## Where next

- The frame these run in: [`the-frame.md`](the-frame.md).
- Keeping blended clips in step: [`../guides/sync-groups.md`](../guides/sync-groups.md).
