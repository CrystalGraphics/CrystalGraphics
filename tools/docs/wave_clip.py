"""Writes the docs' wave clip: a glTF binary keyed on Minecraft's humanoid bones, which plays on a vanilla zombie.

    python -I tools/docs/wave_clip.py examples/animation/first-steps/src/main/resources/assets/zombiemod/anims/wave.glb

The bones and pivots are CgVanillaHumanoid.SKELETON's: metres, y up, facing -z, the right side at +x.
"""
import json
import math
import struct
import sys

PIXEL = 1 / 16

# name, parent, translation from the parent
BONES = [
    ("root", None, (0, 0, 0)),
    ("hips", "root", (0, 12 * PIXEL, 0)),
    ("body", "hips", (0, 12 * PIXEL, 0)),
    ("head", "hips", (0, 12 * PIXEL, 0)),
]
for side, x in (("right", 1), ("left", -1)):
    BONES += [
        (side + "_arm", "hips", (5 * PIXEL * x, 10 * PIXEL, 0)),
        (side + "_hand", side + "_arm", (0, -10 * PIXEL, 0)),
        (side + "_leg", "hips", (1.9 * PIXEL * x, 0, 0)),
        (side + "_foot", side + "_leg", (0, -12 * PIXEL, 0)),
    ]

# seconds; the last key equals the first, so it loops
TIMES = [0.0, 0.3, 0.6, 0.9, 1.2]
# degrees about z: the right arm raised out to the side (180 is straight up), the head tilting with it
TRACKS = {
    "right_arm": [140, 170, 140, 170, 140],
    "head": [-6, 6, -6, 6, -6],
}


def about_z(degrees):
    half = math.radians(degrees) / 2
    return (0.0, 0.0, math.sin(half), math.cos(half))


def main(out):
    index = {name: i for i, (name, _, _) in enumerate(BONES)}
    nodes = [{"name": name, "translation": list(t)} for name, _, t in BONES]
    for name, parent, _ in BONES:
        if parent is not None:
            nodes[index[parent]].setdefault("children", []).append(index[name])

    blob = bytearray()
    views, accessors = [], []

    def accessor(floats, kind, count, minmax=False):
        offset = len(blob)
        blob.extend(struct.pack("<%df" % len(floats), *floats))
        views.append({"buffer": 0, "byteOffset": offset, "byteLength": len(floats) * 4})
        a = {"bufferView": len(views) - 1, "componentType": 5126, "count": count, "type": kind}
        if minmax:
            a["min"], a["max"] = [min(floats)], [max(floats)]
        accessors.append(a)
        return len(accessors) - 1

    time = accessor(TIMES, "SCALAR", len(TIMES), minmax=True)
    samplers, channels = [], []
    for bone, degrees in TRACKS.items():
        values = [c for d in degrees for c in about_z(d)]
        samplers.append({"input": time, "output": accessor(values, "VEC4", len(degrees)), "interpolation": "LINEAR"})
        channels.append({"sampler": len(samplers) - 1, "target": {"node": index[bone], "path": "rotation"}})

    gltf = {
        "asset": {"version": "2.0", "generator": "CrystalGraphics tools/docs/wave_clip.py"},
        "scene": 0,
        "scenes": [{"nodes": [index["root"]]}],
        "nodes": nodes,
        "animations": [{"name": "wave", "samplers": samplers, "channels": channels}],
        "buffers": [{"byteLength": len(blob)}],
        "bufferViews": views,
        "accessors": accessors,
    }
    text = json.dumps(gltf, separators=(",", ":")).encode()
    text += b" " * (-len(text) % 4)
    blob += b"\0" * (-len(blob) % 4)
    with open(out, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(text) + 8 + len(blob)))
        f.write(struct.pack("<II", len(text), 0x4E4F534A) + text)
        f.write(struct.pack("<II", len(blob), 0x004E4942) + bytes(blob))


if __name__ == "__main__":
    main(sys.argv[1])
