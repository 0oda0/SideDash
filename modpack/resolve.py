"""Resolve the modpack: latest 1.21.1 NeoForge file of each mod (+ required deps) via Modrinth,
then map every jar to its CurseForge project/file id (via cfwidget) for manifest.json.
Usage: python resolve.py [download_dir]   -> writes manifest.json, optionally downloads jars for testing."""
import json, sys, os, urllib.request, urllib.parse, time

MC, LOADER, NEO = "1.21.1", "neoforge", "21.1.267"
MODS = [
    # base
    "create", "jei", "jade", "xaeros-minimap", "xaeros-world-map",
    "sodium", "modernfix", "ferrite-core",
    # create addons
    "createaddition", "copycats", "create-deco", "slice-and-dice", "create-connected",
    "create-diesel-generators", "create-enchantment-industry", "create-new-age",
    "create-central-kitchen", "farmers-delight", "bellsandwhistles", "create-design-n-decor",
    "interiors", "create-jetpack", "create-big-cannons",
    "create-goggles", "create-tfmg",  # bits-n-bobs dropped: incompatible with TFMG <=1.2.2
    "numismatics", "create-power-loader",
]
# ponytail: Aeronautics (+Sable physics) and Stuff'N Additions left out — CF ids not resolvable via cfwidget, add by id when wanted
# cfwidget only resolves some slugs; for the rest use the CurseForge project id
CF_ID = {"ferrite-core": 429235, "createaddition": 439890, "create-tfmg": 693815,
         "rpl": 1279407, "architectury-api": 419699}

def get(url):
    for i in range(6):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "sidedash-pack-resolver"})
            return json.load(urllib.request.urlopen(req, timeout=30))
        except Exception as e:
            err = e; time.sleep(2 * (i + 1))
    raise err

def latest(pid):
    q = urllib.parse.urlencode({"loaders": json.dumps([LOADER]), "game_versions": json.dumps([MC])})
    vs = get(f"https://api.modrinth.com/v2/project/{pid}/version?{q}")
    return vs[0] if vs else None

resolved, queue = {}, list(MODS)
while queue:
    pid = queue.pop(0)
    proj = get(f"https://api.modrinth.com/v2/project/{pid}")
    if proj["slug"] in resolved:
        continue
    v = latest(proj["id"])
    if not v:
        print("!! no 1.21.1 neoforge version:", pid); continue
    f = next(x for x in v["files"] if x["primary"]) if any(x["primary"] for x in v["files"]) else v["files"][0]
    resolved[proj["slug"]] = {"title": proj["title"], "version": v["version_number"], "file": f["filename"], "url": f["url"],
                              "side": proj["client_side"] + "/" + proj["server_side"]}
    for d in v["dependencies"]:
        if d["dependency_type"] == "required" and d.get("project_id"):
            queue.append(d["project_id"])

files, missing = [], []
for slug, m in resolved.items():
    cf = None
    try:
        d = get(f"https://api.cfwidget.com/{CF_ID[slug]}" if slug in CF_ID else "https://api.cfwidget.com/minecraft/mc-mods/" + slug)
        hit = [x for x in d.get("files", []) if x["name"] == m["file"]] or \
              sorted([x for x in d.get("files", []) if MC in x["versions"] and "NeoForge" in x["versions"]],
                     key=lambda x: x["uploaded_at"], reverse=True)
        if hit:
            cf = {"projectID": d["id"], "fileID": hit[0]["id"], "required": True}
            m["cf_file"] = hit[0]["name"]
    except Exception as e:
        pass
    print(f'{slug:32} {m["version"]:28} {"CF " + str(cf["fileID"]) if cf else "!! NOT ON CF"}  {m.get("cf_file","")}')
    (files if cf else missing).append(cf or slug)

manifest = {"minecraft": {"version": MC, "modLoaders": [{"id": f"neoforge-{NEO}", "primary": True}]},
            "manifestType": "minecraftModpack", "manifestVersion": 1, "name": "SideDash Create Pack",
            "version": "1.0.0", "author": "0oda0", "files": files, "overrides": "overrides"}
here = os.path.dirname(os.path.abspath(__file__))
json.dump(manifest, open(os.path.join(here, "manifest.json"), "w"), indent=2)
json.dump(resolved, open(os.path.join(here, "resolved.json"), "w"), indent=2)
print("missing on CF:", missing)

if len(sys.argv) > 1:
    os.makedirs(sys.argv[1], exist_ok=True)
    keep = {m["file"] for m in resolved.values()}
    for f in os.listdir(sys.argv[1]):  # drop jars that are no longer in the pack
        if f.endswith(".jar") and f not in keep and not f.startswith("sidedash"):
            os.remove(os.path.join(sys.argv[1], f))
    for m in resolved.values():
        p = os.path.join(sys.argv[1], m["file"])
        if not os.path.exists(p):
            urllib.request.urlretrieve(m["url"], p)
    print("downloaded", len(resolved))
