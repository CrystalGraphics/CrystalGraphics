# Writing the docs: the rules

**DRAFT.** These rules are being tried on the first pages before they are final; until then, a rule that does not work
is fixed here, not worked around in a page.

Every page in this folder is published on the docs site and read by a beginner. This file is how they are written. It
loads itself (through `docs/CLAUDE.md`) the moment you open anything here, and it binds every page that has a
front-matter header. A page without one is an old page waiting to be rewritten: leave it alone unless you are
rewriting its subject, and never add to it.

Not covered: docs for people working *on* the engine (the build, the gauntlet, conventions, package guides). They
follow `AGENTS.md` at the repository root.

**Check your work with `DocsRulesTest`** (`./gradlew :core:headlessTest --tests "com.crystalgraphics.docs.DocsRulesTest"`).
It checks every rule a machine can; the rest is the checklist at the end.

---

## 1. Who you are writing for

**One person**: a Minecraft modder who has made a small Forge or Fabric mod (an item, a block), knows basic Java, and
has never heard of an animation graph, a material, a montage or a codec. They may be young. They are excited about
what they want to make, and they quit the moment they are lost.

- **Don't teach Java or modding basics.** Say what is assumed and link to where it is taught.
- **Teach every idea of ours from zero**, the first time it is needed.
- **Experts get the reference pages and search**, not denser lessons.
- **Unreal users are a second reader.** What maps to Unreal goes in a collapsed *Coming from Unreal?* box (§ 6),
  never in the running text.

## 2. Where a page goes

```
docs/
  README.md                 what the engine is, why you'd want it, when not to use it, and the map
  glossary.md               every term, one plain-words line each, linking to where it is taught
  get-started/              the tour: one move, played whole, then opened up chapter by chapter
  <subject>/                one thing the reader wants to do: animation, effects, networking, …
    README.md               its overview
    first-steps.md          its first lesson, standalone, from the template mod
    <the-task>.md           task pages, named for the task in the reader's words
    when-it-goes-wrong.md   symptom → cause → fix
    reference/              lookup pages
    img/                    its pictures and clips
  projects/                 bigger builds, each a short book of lessons
```

- **Subjects are named for what the reader wants**, never for a package: `networking`, not `net`.
- **There is no `concepts/`, `guides/` or `start/` folder.** An idea is explained inside the page that needs it.
- **Advanced subjects** (GPU-driven rendering, render stages) say *advanced* in their overview and sit last in the menu.
- **A new page goes into an existing subject.** A new subject needs the user's say.

## 3. The five kinds of page

Every page is one kind, named in its header (`kind:`). Start from its template in [`_templates/`](_templates/): never
from memory, never from another page.

| `kind:` | Its job | Its shape |
|---|---|---|
| `overview` | What this is, and is it for me? | two sentences on what it is · a picture or clip of the result · why you'd want it, and when not to · the big picture in plain words, five ideas at most · the map: every page under it, one line each |
| `lesson` | Do it once, guided | what you'll have at the end (a picture) · what you need · numbered steps, each ending in **You should see** and **If you don't** · what you just did · *Try it yourself* · what's next |
| `task` | Do this one thing | a title that is the task · a picture of the result · the complete working example, every file whole · **You should see** · its parts explained · variations · see also |
| `reference` | Look up one exact fact | a table or entry per thing: name, one-line meaning, type and units, default, a tiny example. Generated from the code wherever the code declares it |
| `troubleshooting` | I'm stuck | a table: symptom (in the reader's words), cause, fix, linking to the page that explains it |

The glossary is `kind: glossary`.

**Limits:**

- A lesson takes about **15 minutes**. Longer, split it in two.
- A section brings in at most **three new terms**.
- A task page fits on about **three screens** before its variations.

## 4. Writing

- **Second person, present tense, short sentences.** *You add a tag*, not *a tag can be added*.
- **Show it, then name it.** Something happens on screen first; then it gets its word.
- **Explain every term of ours in plain words the first time it appears on each page.** Readers arrive from search,
  in the middle of things. After that, link the term to the glossary.

  > ❌ *The actor's anim graph evaluates the locomotion state machine.*
  >
  > ✅ *An **animation graph** is a recipe that decides which animation plays, and how they mix. Yours picks
  > "walk" or "run" by how fast the zombie moves.*
- **An analogy explains one idea, in one or two sentences.** Never stretch it to a second idea.
- **No internal words.** None of these appear:
  - plan step ids or session names;
  - package paths in running text;
  - Unreal class names, outside the *Coming from Unreal?* box.

  A type name appears in prose only where the reader types it.
- **Real names in examples.** Use the tour's move, real Minecraft mobs and real items. Never `foo`, `MyThing`,
  `example_id`, or a variable that is never defined.
- **Say the critical line twice**: once in the text, and once as a comment inside the code. Many readers read only
  the code.
- **Every lesson step ends in what the reader should see**, and the likeliest reason they don't. A task page says it
  once, after its example.
- **Promise only what the code does.** If the zombies keep walking while they wave, say so; never "they stop and wave
  at you" when nothing stops them or turns them. A reader who sees something else thinks it is broken.
- **Explain every line a reader types that is not plain Java**, in a lesson: an array the game fills, a `1f` whose
  meaning isn't obvious. One line each, beside the code.
- **Every file the reader makes is named by its whole path** from the project root (`src/main/resources/assets/...`),
  and the page says whether it is new or replaces one from an earlier page.
- **What you need** links only to rewritten pages. Where only an old page has it, link the exact section and say that
  nothing else on that page is needed.
- **Older versions spell some things differently.** Where a step's file, command or id differs on an older Minecraft
  (the language file before 1.13, entity ids before 1.11), a `> [!NOTE]` gives the old spelling beside it.
- **No fun that doesn't teach.** A joke or a fun fact pulls attention off the lesson.

## 5. Examples

- **Complete and copy-pasteable.** No `...` standing for code the reader needs, and no placeholders. A class the
  reader makes is shown whole, imports included; a bare method never stands for a file.
- **Data and Java side by side, data first.** Anything that exists in both forms shows the data file, then
  `The same in Java:` and the Java, both open. They say the same thing in the same order, so the eye can match them
  line for line.
  - Behaviour that exists only in Java is shown in Java alone.
  - A pair is shown once per idea. A later step that changes three lines shows those three lines, in both forms.
- **Every code block names its language** (` ```java `, ` ```yaml `, ` ```json `, ` ```text `), and may carry a file
  tab: ` ```java title="ZombieMod.java" `.
- **Examples are tested.** The code lives in `examples/`, which the build compiles. A block in a page is tagged with
  the file and region it comes from, on the line just before it:

  ````markdown
  <!-- example: examples/animation/first-steps/src/main/java/ZombieMod.java#play -->
  ```java
  …
  ```
  ````

  In the file, the region sits between two comment lines, `// docs:begin play` and `// docs:end play` (`#` in YAML).
  A whole file is a region from its first line to its last; a region inside another shows without its markers. A
  tag with no `#region` names the whole file, for JSON, which has no comments. `DocsRulesTest` fails when a block
  and its region differ.
- **A block that cannot be tested** is tagged `<!-- example: untested, <the reason> -->`. Keep these rare: the test
  lists every one.

## 6. Callouts: four, each with one job

| Write | It means | Where |
|---|---|---|
| `> [!WARNING]` | a **pitfall**: the mistake people make here | at the exact step where it happens |
| `> [!NOTE]` | something that holds only sometimes: a version, a side, a loader | beside what it qualifies |
| `> [!TIP]` | a shortcut or a better way, for a reader who has it working | after the working version |
| `<details><summary>…</summary>` | **optional depth**: a deep dive, *Coming from Unreal?*, a *Try it yourself* answer | collapsed, after the part it deepens |

No `[!IMPORTANT]` or `[!CAUTION]`. A page with a callout every few lines has none that matter.

## 7. Pictures

- **A picture is required:**
  - at the top of every overview and task page: the result;
  - after every lesson step that changes the screen: what you should see;
  - for any idea that is a movement (blending, warping, IK, a spring): a short clip;
  - for anything with boxes and arrows (a graph, a state machine, who sends what to whom): a Mermaid diagram,
    ` ```mermaid `.
- **No picture that doesn't teach**: no mascots, memes or decorative banners.
- **Where they live:** in `img/` beside the page, lowercase-kebab, named for what they show
  (`zombie-walks-then-runs.webp`).
- **Clips** are animated WebP: five seconds at most, under 1 MB.
- **Every picture has alt text** that says what it shows.
- **Every picture names what remakes it** in an HTML comment beside it
  (`<!-- remake: harness --mode=anim-zombie, 640x360 -->`).

## 8. Markdown the site can publish

The site (Docusaurus) publishes this folder as it is. These keep every page publishable, and readable on GitHub:

- **The header**, first thing in the file:

  ```yaml
  ---
  title: Make a mob look at the player
  description: Turn a mob's head toward whoever is nearest, with limits so it never looks behind itself.
  sidebar_position: 3
  kind: task
  ---
  ```

  Add `minecraft: "1.13.2-26.3"` and `loaders: [forge, neoforge, fabric]` only when the feature does not run
  everywhere, together with a `> [!NOTE]` at the top saying so. By default a page holds for every supported version
  and loader, and says nothing about it.
- **One `# Title`**, the same text as `title:`.
- **Headings are plain words**: no backticks or symbols. Headings become link anchors.
- **File names** are lowercase-kebab and never change, because they become URLs. No number prefixes: order comes
  from `sidebar_position`.
- **Links:**
  - to another page: a relative path to its `.md` (`../networking/first-steps.md`);
  - to code: a full GitHub URL;
  - never into `plan/`.
- **Plain Markdown.** The only HTML allowed is `<details>`, `<summary>` and comments. A `<` or `{` in a sentence
  goes inside backticks.
- **Generated sections stay generated.** Fix the generator, never the page.

## 9. Before you commit a page

1. `DocsRulesTest` passes.
2. You followed your page's template, and its kind's shape (§ 3).
3. Every term of ours has its plain-words line on this page (§ 4).
4. Every example is complete, tagged, and in both forms where both exist (§ 5).
5. The pictures § 7 requires are there.
6. You read the page once as the reader in § 1: nothing on it assumes what they don't know yet.
7. The old page it replaces is deleted in the same commit, and links to it now point here.
