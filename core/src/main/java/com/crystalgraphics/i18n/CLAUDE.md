# i18n — text in the player's language

| Class | Owns |
|---|---|
| `CgKitStrings` | a kit's `strings/<locale>.yaml`, text (and a line's speaker) by `<ns>:text/<id>` under the game's namespace, registered by the asset registry with the kit; read in the player's locale, `en_us` where it has none |

- **The host's language files and a kit's are separate**: `platform.service.CgI18n.translate` reads Minecraft's (`I18n`), `CgKitStrings` a
  game's own string tables, which a sequence's `subtitle` section names.
- **Render thread** for `translate`, as the host's language is; `CgKitStrings` from any thread once registered.
