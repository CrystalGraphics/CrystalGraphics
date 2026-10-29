# i18n — text in the player's language

| Class | Owns |
|---|---|
| `CgI18n` | a host translation key's text (`translate`) and the player's language as a kit names it (`language`): the one reader of the `platform.service.CgHostI18n` slot (`WorldServiceReadersTest`) |
| `CgKitStrings` | a kit's `strings/<locale>.yaml`, text (and a line's speaker) by `<ns>:text/<id>` under the game's namespace, registered by the asset registry with the kit; read in the player's locale, `en_us` where it has none |

- **The host's language files and a kit's are separate**: `translate` reads Minecraft's (`I18n`), `CgKitStrings` a
  game's own string tables, which a sequence's `subtitle` section names.
- **Render thread** for `translate`, as the host's language is; `CgKitStrings` from any thread once registered.
