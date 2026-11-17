"""Build the CurseForge import zip: manifest.json (from resolve.py) + our mod jar in overrides/mods.
Usage: python modpack/pack.py   (after ./gradlew build)   -> modpack/SideDash-Create-Pack.zip"""
import glob, json, os, zipfile

here = os.path.dirname(os.path.abspath(__file__))
jars = sorted(glob.glob(os.path.join(here, "..", "build", "libs", "sidedash-*.jar")))
assert jars, "run ./gradlew build first"
manifest = json.load(open(os.path.join(here, "manifest.json")))
out = os.path.join(here, f"SideDash-Create-Pack-{manifest['version']}.zip")
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("manifest.json", json.dumps(manifest, indent=2))
    z.write(jars[-1], "overrides/mods/" + os.path.basename(jars[-1]))
print(out, len(manifest["files"]), "CurseForge mods +", os.path.basename(jars[-1]))
