# i18n — text in the player's language

| Class | Owns |
|---|---|
| `CgKitStrings` | text by `<ns>:text/<id>` in the player's language: a kit's `strings/<locale>.yaml` (and a line's speaker) under the game's namespace, registered by the asset registry with the kit, `en_us` where its locale has none; else a mod's translations through the host (`CgI18n`), the key `<ns>.<id>` then `<id>` |
| `CgLangFiles` | a mod's translations as `assets/<ns>/lang/<code>.json` on every version: read by Minecraft itself from 1.13, merged into the locale's table at each load by `LocaleHook` on 1.7.10 and legacy Forge (`LangFiles1710`, `LangFilesLegacy`), never over a key a `.lang` or resource pack set |

- **One format for a mod's translations: JSON**, lower-case file names (`en_us.json`). No `.lang` file ships, on any era.
- **One string id reaches both**: a game's own tables first, then the host's language files (`CgI18n`, Minecraft's
  `I18n`), so a mod never keeps a second table beside its `lang/<locale>.json`.
- **Render thread** for `translate`, as the host's language is; `CgKitStrings` from any thread once registered.
