"""Sets `modVersion` in a gradle.properties and prints it.

    python3 set_version.py gradle.properties patch     # 1.2.3 -> 1.2.4
    python3 set_version.py gradle.properties minor     # 1.2.3 -> 1.3.0
    python3 set_version.py gradle.properties major     # 1.2.3 -> 2.0.0
    python3 set_version.py gradle.properties as-is     # 1.2.3, unchanged: a first release
    python3 set_version.py gradle.properties 1.4.0     # exactly that
"""
import re
import sys

path, how = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8", newline="") as f:
    text = f.read()

line = re.search(r"^modVersion[ \t]*=[ \t]*(\d+)\.(\d+)\.(\d+)[ \t]*(?=\r?$)", text, re.M)
if line is None:
    sys.exit(f"{path} has no modVersion = x.y.z line")
major, minor, patch = map(int, line.groups())

if how == "major":
    version = f"{major + 1}.0.0"
elif how == "minor":
    version = f"{major}.{minor + 1}.0"
elif how == "patch":
    version = f"{major}.{minor}.{patch + 1}"
elif how == "as-is":
    version = f"{major}.{minor}.{patch}"
elif re.fullmatch(r"\d+\.\d+\.\d+", how):
    version = how
else:
    sys.exit(f"'{how}' is not patch, minor, major, as-is or x.y.z")

with open(path, "w", encoding="utf-8", newline="") as f:
    f.write(text[:line.start()] + f"modVersion = {version}" + text[line.end():])
print(version)
