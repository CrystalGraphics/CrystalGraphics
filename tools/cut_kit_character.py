"""Cuts one character out of a kit into a kit of its own: its mesh, skeleton and prefab, every asset they reach by id
(materials and their parents, textures, shaders and what they include), the clip folders asked for, and an actor.yaml
the animation demo reads. The kit's namespace is renamed in every text file it copies.

    python -I tools/cut_kit_character.py --kit <kit folder> --namespace kakarot_kit --to kk \
        --shading-from kakarot --shading kk --name Goku --mesh models/.../Cpl001p1c01s1.glb \
        --skeleton skeletons/.../Cpl001p1_Skeleton.yaml --prefab prefabs/.../AT_Character_cpl001_BP.yaml \
        --clips models/Art/ANM/cpl/C001A/BAS ... --out core/src/main/resources/assets/kk

Its shading models' ids (--shading-from:) become --shading's too. Prints what it copied and the size. Run it again to
refresh the cut: the output folder is replaced, so keep anything else of the namespace (kk's humanoid/ and
preload.yaml) aside first.
"""
import argparse
import os
import re
import shutil
import sys
import time

TEXT = ('.yaml', '.yml', '.shader', '.glsl', '.compute', '.factory', '.primitive')


def main():
    a = argparse.ArgumentParser()
    a.add_argument('--kit', required=True)
    a.add_argument('--namespace', required=True)
    a.add_argument('--to', required=True)
    a.add_argument('--shading', required=True)
    a.add_argument('--shading-from', help="the namespace the kit's materials name their shading models by")
    a.add_argument('--name', required=True)
    a.add_argument('--mesh', required=True)
    a.add_argument('--skeleton', required=True)
    a.add_argument('--prefab', required=True)
    a.add_argument('--clips', nargs='*', default=[])
    a.add_argument('--out', required=True)
    args = a.parse_args()
    start = time.time()
    kit = os.path.abspath(args.kit)
    kinds = [d for d in os.listdir(kit) if os.path.isdir(os.path.join(kit, d))]
    ref = re.compile(re.escape(args.namespace) + r':([A-Za-z0-9_./\-]+)')

    def resolve(path):
        """An id's path to a file under the kit: as written where it names one, else an asset under its kind's folder."""
        direct = os.path.join(kit, path)
        if os.path.isfile(direct):
            return path
        for kind in kinds:
            candidate = os.path.join(kind, path + '.yaml')
            if os.path.isfile(os.path.join(kit, candidate)):
                return candidate
        return None

    wanted = [args.mesh, args.skeleton, args.prefab, 'units.yaml', 'shading.yaml']
    sidecar = os.path.splitext(args.mesh)[0] + '.yaml'
    if os.path.isfile(os.path.join(kit, sidecar)):
        wanted.append(sidecar)
    files, missing, queue = set(), set(), list(wanted)
    while queue:
        rel = queue.pop()
        if rel in files:
            continue
        if not os.path.isfile(os.path.join(kit, rel)):
            missing.add(rel)
            continue
        files.add(rel)
        if rel.endswith(TEXT):
            text = open(os.path.join(kit, rel), encoding='utf-8', errors='replace').read()
            for path in ref.findall(text):
                found = resolve(path.rstrip('.'))
                if found is None:
                    missing.add(args.namespace + ':' + path)
                elif found not in files:
                    queue.append(found)
            # A shader's relative includes
            for inc in re.findall(r'#include\s+"([^":]+)"', text):
                queue.append(os.path.normpath(os.path.join(os.path.dirname(rel), inc)).replace('\\', '/'))
    for folder in args.clips:
        for f in sorted(os.listdir(os.path.join(kit, folder))):
            if f.lower().endswith(('.glb', '.yaml')):
                files.add(folder + '/' + f)

    out = os.path.abspath(args.out)
    if os.path.isdir(out):
        shutil.rmtree(out)
    size = 0
    rename = re.compile(r'\b' + re.escape(args.namespace) + ':')
    for rel in sorted(files):
        src, dst = os.path.join(kit, rel), os.path.join(out, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if rel.endswith(TEXT):
            text = open(src, encoding='utf-8', errors='replace').read()
            with open(dst, 'w', encoding='utf-8', newline='\n') as w:
                text = rename.sub(args.to + ':', text)
                if args.shading_from:
                    text = re.sub(r'\b' + re.escape(args.shading_from) + ':', args.shading + ':', text)
                w.write(text)
        else:
            shutil.copyfile(src, dst)
        size += os.path.getsize(dst)
    with open(os.path.join(out, 'actor.yaml'), 'w', encoding='utf-8', newline='\n') as w:
        w.write('# The animation demo\'s character: this folder is its kit (namespace %s).\n' % args.to)
        w.write('name: %s\nnamespace: %s\nshading: %s\nmesh: %s\nskeleton: %s\nprefab: %s\n'
                % (args.name, args.to, args.shading, args.mesh, args.skeleton, args.prefab))
        w.write('clips:\n' + ''.join('  - %s\n' % c for c in args.clips))
    print('%d files, %.1f MB, in %.1f s' % (len(files) + 1, size / 1e6, time.time() - start))
    if missing:
        print('not found (%d):' % len(missing))
        for m in sorted(missing):
            print('  ' + m)
    return 0


if __name__ == '__main__':
    sys.exit(main())
