import json, hashlib, struct, zipfile
from pathlib import Path
root = Path(__file__).resolve().parent
checks = []
def check(value, note):
    assert value, note
    checks.append(note)
def read(path):
    if (root / path).is_file():
        return (root / path).read_bytes()
    with zipfile.ZipFile(root / "browser-evidence.zip") as archive:
        return archive.read(path)
def load(path):
    return json.loads(read(path).decode("utf-8"))
for cluster in ["31", "32"]:
    for theme in ["light", "dark"]:
        for width in [390, 768, 1280]:
            prefix = f"browser/{cluster}-final-{theme}-{width}"
            vm, snap = [load(prefix + "-" + name + ".json") for name in ["vm", "snapshot"]]
            check(len(snap["columns"]) == len(vm["columns"]) == 2, prefix + " shared two columns")
            for name, data in [("vm", vm), ("snapshot", snap)]:
                check(data["viewport"]["width"] == width, prefix + name + " actual viewport")
                image = read(prefix + "-" + name + ".png")
                check(image[:8] == b"\x89PNG\r\n\x1a\n" and struct.unpack(">II", image[16:24]) == (width, 720), prefix + name + " image dimensions")
            check([c["class"].split("autogen-toolbar")[0].strip() for c in snap["columns"]] == [c["class"].split("autogen-toolbar")[0].strip() for c in vm["columns"]], prefix + " common column spans")
            check(snap["documentWidth"] <= width, prefix + " no page overflow")
            for prop in ["fontSize", "lineHeight", "padding", "backgroundColor", "color", "width", "height"]:
                check(snap["filter"]["style"][prop] == vm["filter"]["style"][prop], prefix + " filter " + prop)
            refresh = next(b for b in snap["buttons"] if b["text"] == "업데이트")
            reference = next(b for b in vm["buttons"] if b["text"] == "업데이트")
            check(refresh["rect"]["height"] == reference["rect"]["height"] == 24, prefix + " small refresh")
            check(len(snap["popup"]) == 0, prefix + " closed filters")
            for i, first in enumerate(snap["buttons"]):
                for second in snap["buttons"][i+1:]:
                    a, b = first["rect"], second["rect"]
                    overlap = min(a["right"], b["right"]) - max(a["left"], b["left"]) > 1 and min(a["bottom"], b["bottom"]) - max(a["top"], b["top"]) > 1
                    check(not overlap, prefix + " header controls separated")
            focus = load(prefix + "-escape.json")
            check(focus["aria"] == "고급 검색" and focus["expanded"] == "false", prefix + " Escape focus")
            popup = load(prefix + "-snapshot-filter.json")
            check(len(popup["popup"]) == 1, prefix + " filter popover")
            box = popup["popup"][0]
            reference_popup = load(prefix + "-vm-filter.json")["popup"][0]
            for prop in ["fontSize", "lineHeight", "padding", "color"]:
                check(box["labels"][0]["style"][prop] == reference_popup["labels"][0]["style"][prop], prefix + " VM filter label " + prop)
            check(box["rect"]["left"] >= 0 and box["rect"]["right"] <= width + 1 and box["rect"]["top"] >= 0 and box["rect"]["bottom"] <= 721, prefix + " popup within viewport")
            check(box["formScrollHeight"] > box["formClientHeight"], prefix + " bounded form scroll")
            for label, control in zip(box["labels"], box["controls"]):
                check(label["rect"]["bottom"] <= control["top"] + 1, prefix + " label/control separation " + label["text"])
for cluster in ["31", "32"]:
    before = load("browser/" + cluster + "-before-default-network.json")["requests"]
    after = load("browser/" + cluster + "-after-default-network.json")["requests"]
    before = next(r["result"] for r in before if r["command"] == "listSnapshots")
    after = [r["result"] for r in after if r["command"] == "listSnapshots"][-1]
    check(before == after, cluster + " API count and page order unchanged")
    flow = load("browser/" + cluster + "-search-flow.json")
    check(flow["ctrlEnter"] and flow["detailBack"] and flow["reset"] and flow["lookupFailureRetry"], cluster + " search/retry/back/reset")
    native = load("browser/" + cluster + "-native-submit.json")
    check(native["button"] and native["enter"] and native["keywordPreservesType"], cluster + " actual button and Enter")
    for method in ["native-button", "native-enter"]:
        requests = [r for r in load("browser/" + cluster + "-" + method + "-network.json")["requests"] if r["command"] == "listSnapshots"]
        check(len(requests) == 1 and requests[0]["status"] == 200, cluster + method + " single completed GET")
    row = load(cluster + "-filtered-db.json")[0]
    check(row["id"] == flow["target"]["id"] and row["name"] == flow["target"]["name"] and row["volume"] == flow["target"]["volumeid"] and row["status"] == "BackedUp", cluster + " API/UI/DB exact name")
    project = [r for r in load("browser/" + cluster + "-project-scope-network.json")["requests"] if r["command"] == "listSnapshots"][-1]
    check(project["args"]["projectid"] == "-1" and project["result"]["count"] == before["count"] + 1, cluster + " project scope/count")
    busy = load("browser/" + cluster + "-refresh-busy.json")
    recovery = load("browser/" + cluster + "-refresh-recovery.json")
    check(busy["refreshDisabled"] and busy["checked"] == 1 and busy["actions"] == 0, cluster + " pending action guard")
    check(recovery["checked"] == 1 and recovery["rows"] == busy["rows"], cluster + " retry selection retained")
    check(load(cluster + "-db-before.json") == load(cluster + "-db-after.json"), cluster + " DB projections unchanged")
    deployed = load(cluster + "-ui-deploy.json")
    check(deployed["verified"] and deployed["webInf"] and deployed["metaInfPreserved"] and deployed["clientHttp"] == 200 and deployed["mold"] == "active", cluster + " safe deployed runtime")
    check(all(deployed["markers"].values()), cluster + " active bundle markers")
for system in ["windows", "wsl"]:
    check(load("protected-" + system + "-before.json") == load("protected-" + system + "-after.json"), system + " FT worktree preserved")
for item in load("source-manifest.json"):
    path = root.parents[2] / item["path"]
    check(hashlib.sha256(path.read_bytes()).hexdigest() == item["sha256"], item["path"] + " source hash")
manifest = load("browser-manifest.json")
with zipfile.ZipFile(root / "browser-evidence.zip") as archive:
    check(set(archive.namelist()) == {item["path"] for item in manifest}, "archive exact manifest")
    for item in manifest:
        check(item["path"].startswith("browser/") and ".." not in Path(item["path"]).parts and hashlib.sha256(archive.read(item["path"])).hexdigest() == item["sha256"], item["path"] + " archive hash")
result = {"status": "PASS", "checks": len(checks), "snapshotLayouts": 12, "vmReferenceLayouts": 12, "snapshotFilterLayouts": 12, "vmFilterLayouts": 12, "escapeCases": 12, "archivedProofFiles": len(manifest)}
(root / "verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(result))
