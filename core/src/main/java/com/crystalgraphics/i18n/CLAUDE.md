# i18n — text in the player's language

| Class | Owns |
|---|---|
| `CgKitStrings` | a kit's `strings/<locale>.yaml`, text (and a line's speaker) by `<ns>:text/<id>` under the game's namespace, registered by the asset registry with the kit; read in the player's locale, `en_us` where it has none |
| `CgLangFiles` | a mod's translations as `assets/<ns>/lang/<code>.json` on every version: read by Minecraft itself from 1.13, merged into the locale's table at each load by `LocaleHook` on 1.7.10 and legacy Forge (`LangFiles1710`, `LangFilesLegacy`), never over a key a `.lang` or resource pack set |

- **One format for a mod's translations: JSON**, lower-case file names (`en_us.json`). No `.lang` file ships, on any era.
- **The host's language files and a kit's are separate**: `platform.service.CgI18n.translate` reads Minecraft's (`I18n`), `CgKitStrings` a
  game's own string tables, which a sequence's `subtitle` section names.
- **Render thread** for `translate`, as the host's language is; `CgKitStrings` from any thread once registered.
