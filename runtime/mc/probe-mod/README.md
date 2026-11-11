# probe-mod

A resource-only Forge mod (`lowcodefml`, Forge 1.20.1 and later) holding one cue, its tag and its shake: what the
world probe and `serverSmoke` play to prove that a mod jar's `assets/<ns>/` is mounted and its cue played from the
server on the client. Its `pack.mcmeta` is required: without one Forge drops the mod's pack and opens its warning
screen in place of the first menu.

```bash
# from this folder: the jar into CrystalGUI's Forge 1.20.1 dev run, client and server
jar --create --file <CrystalGUI>/runtime/mc/modern/forge/versions/1.20.1/runs/client/mods/cgprobe.jar -C . META-INF assets pack.mcmeta
cp <CrystalGUI>/runtime/mc/modern/forge/versions/1.20.1/runs/client/mods/cgprobe.jar <CrystalGUI>/runtime/mc/modern/forge/versions/1.20.1/runs/server/mods/
# then a dev client with -Dcrystalgraphics.gauntlet=crystalgraphics:world "-Dcrystalgraphics.worldprobe.cue=cgprobe:shake|cgprobe:cue.shake"
```
