# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Validate the Epic #987 ledger against the fixed Git source graph.

Read-only: does not checkout branches, apply patches, or modify a database.
Run from anywhere inside the container repository. No third-party packages.
"""

import collections
import csv
import io
from pathlib import Path
import re
import subprocess

BASE = "3166e64891fc75d4d32b66d874cff3f613b09b52"
TARGET = "463f8d0294702a920e8020b62ae3b67f52ae1473"
EUROPA = "014895d8f3dc2f062f379b51ee62d36a0adae88a"
DIRECTORY = Path(__file__).resolve().parent
ALLOWED = {"Pending", "In Progress", "Blocked", "Deferred", "Applied", "Adapted", "Already Satisfied", "Excluded"}


def git(*args):
    return subprocess.check_output(["git", *args], cwd=DIRECTORY, text=True).strip()


def read_tsv(name):
    text = "".join(line for line in (DIRECTORY / name).read_text().splitlines(keepends=True) if not line.startswith("#"))
    return list(csv.DictReader(io.StringIO(text), delimiter="\t"))


def split(value):
    return value.split(";") if value else []


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    source = git("rev-list", "--reverse", "--topo-order", BASE + ".." + TARGET).splitlines()
    rows = read_tsv("inventory.tsv")
    merges = read_tsv("merges.tsv")
    evidence = read_tsv("evidence.tsv")
    deps = read_tsv("dependencies.tsv")
    streams = read_tsv("workstreams.tsv")
    require(len(source) == 299 and len(rows) == 299, "Expected 299 source commits")
    require(len({r["sha"] for r in rows}) == 299, "Duplicate inventory SHA")
    require(set(source) == {r["sha"] for r in rows}, "Source SHA set mismatch")
    require([int(r["sequence"]) for r in rows] == list(range(1, 300)), "Sequence must be contiguous")
    positions = {r["sha"]: int(r["sequence"]) for r in rows}
    actual_parents = dict(line.split(" ", 1) for line in git("log", "--format=%H %P", BASE + ".." + TARGET).splitlines())
    by_sha = {r["sha"]: r for r in rows}
    by_code = {w["code"]: w for w in streams}
    require(len(by_code) == 10, "Expected ten workstreams")
    require(len({w["issue"] for w in streams}) == 10, "Workstream issue collision")
    for row in rows:
        sha = row["sha"]
        parents = split(row["parents"])
        require(parents == actual_parents[sha].split(), "Parent mismatch: " + sha)
        for parent in parents:
            require(parent not in positions or positions[parent] < positions[sha], "Invalid topological order: " + sha)
        require(row["kind"] == ("merge" if len(parents) > 1 else "commit"), "Kind mismatch: " + sha)
        require(row["decision"] in ALLOWED, "Invalid decision: " + sha)
        require(row["workstream"] in by_code, "Unknown workstream: " + sha)
        require(row["issue"] == by_code[row["workstream"]]["issue"], "Issue mismatch: " + sha)
        require(row["reviewed_europa_sha"] == EUROPA, "Changed baseline evidence: " + sha)
        require(row["source_url"].endswith("/commit/" + sha), "Source URL mismatch: " + sha)
        require(bool(row["notes"] and row["validation"]), "Missing review context: " + sha)
        files = set(split(row["files"]))
        for key in ["db_files", "api_files", "ui_files"]:
            require(set(split(row[key])) <= files, "Impact paths not in diff: " + sha)
        for code in row["related_workstreams"].split(","):
            require(not code or code in by_code, "Unknown related workstream: " + sha)
        for peer in split(row["equivalent_upstream"]):
            require(peer in by_sha and by_sha[peer]["patch_id"] == row["patch_id"], "Invalid patch-equivalent link: " + sha)
            require(sha in split(by_sha[peer]["equivalent_upstream"]), "Asymmetric equivalent link: " + sha)
        for peer in split(row["same_subject_upstream"]):
            require(peer in by_sha and by_sha[peer]["subject"] == row["subject"], "Subject peer mismatch: " + sha)
        if row["decision"] in {"Applied", "Adapted"}:
            require(bool(re.fullmatch("[0-9a-f]{40}", row["europa_sha"])) and bool(row["pr"]), "Missing applied SHA/PR: " + sha)
        if row["decision"] in {"Applied", "Adapted", "Already Satisfied", "Excluded"}:
            require("runtime tests not run" not in row["validation"], "Final decision needs explicit final verification/review evidence: " + sha)
    expected_merges = {r["sha"] for r in rows if r["kind"] == "merge"}
    require(len(merges) == 19 and {r["sha"] for r in merges} == expected_merges, "Merge coverage mismatch")
    for row in merges:
        require(row["parents"] == by_sha[row["sha"]]["parents"], "Merge parents mismatch")
        require(len(split(row["remerge_files"])) == int(row["remerge_file_count"]), "Merge path count mismatch")
        require(set(split(row["in_scope_side_commits"])) == set(split(row["side_commits"])) & set(source), "Merge side scope mismatch")
        require(bool(re.fullmatch("[0-9a-f]{64}", row["remerge_sha256"])), "Missing remerge hash")
        require(bool(row["review_notes"]), "Missing merge analysis")
    normal = {r["sha"] for r in rows if r["kind"] == "commit"}
    require(len(evidence) == 280 and {r["sha"] for r in evidence} == normal, "Evidence coverage mismatch")
    for ev in evidence:
        require(ev["patch_id"] == by_sha[ev["sha"]]["patch_id"], "Patch evidence mismatch")
        require(ev["reverse_check"] == by_sha[ev["sha"]]["reverse_check"], "Reverse evidence mismatch")
        require(bool(re.fullmatch("[0-9a-f]{40}", ev["patch_id"])), "Missing patch ID")
    require(len({(d["prerequisite"], d["dependent"]) for d in deps}) == len(deps), "Duplicate dependency")
    for dep in deps:
        before, after = dep["prerequisite"], dep["dependent"]
        require(after in by_sha and dep["owner_issue"] == by_sha[after]["issue"], "Invalid dependency target")
        if before.startswith("europa:"):
            subprocess.run(["git", "merge-base", "--is-ancestor", before.split(":", 1)[1], EUROPA], cwd=DIRECTORY, check=True)
        else:
            require(before in positions and positions[before] < positions[after], "Dependency is not forward ordered")
        require(dep["kind"] in {"baseline_contract", "code_dependency", "review_order", "merge_followup"}, "Invalid dependency kind")
    for row in rows:
        require(set(split(row["dependencies"])) == {d["prerequisite"] for d in deps if d["dependent"] == row["sha"]}, "Dependency ledger mismatch")
    counts = collections.Counter(row["workstream"] for row in rows)
    for stream in streams:
        require(int(stream["source_count"]) == counts[stream["code"]], "Workstream count mismatch")
        require(set(split(stream["gate_dependencies"])) <= set(by_code), "Unknown gate dependency")
    print("PASS: 299 unique source SHAs; 280 commits; 19 merges; all parent edges topologically valid")
    print("PASS: 280 evidence rows; 19 merge analyses; " + str(len(deps)) + " dependency edges; 10 linked workstreams")
    print("Source allocation:", dict(sorted(counts.items())))
    print("Decisions:", dict(collections.Counter(row["decision"] for row in rows)))
    print("Validation is read-only ledger verification, not product functional testing.")


if __name__ == "__main__":
    main()
