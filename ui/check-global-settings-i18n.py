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

import argparse
import json
import sys
from pathlib import Path

UI_ROOT = Path(__file__).resolve().parent
CATALOG = UI_ROOT / "src/locales/global-settings/ko_KR.json"
FIELDS = ("displaytext", "description")
SECTION_PREFIX = "label.global.setting.section."


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json(path, check_duplicates=False):
    with path.open(encoding="utf-8-sig") as source:
        return json.load(source, object_pairs_hook=unique_object if check_duplicates else None)


def normalize(value):
    return value.replace("\r\n", "\n").replace("\r", "\n").strip() if isinstance(value, str) else ""


def catalog_sources(data):
    if not isinstance(data, dict) or not data:
        raise ValueError("the Korean catalog must be a non-empty object")

    sources = {}
    for name, record in data.items():
        if not name.strip() or name != name.strip() or not isinstance(record, dict) or not record:
            raise ValueError(f"invalid setting key or entry: {name}")
        for field, translations in record.items():
            if field not in FIELDS or not isinstance(translations, list) or not translations:
                raise ValueError(f"{name}.{field}: expected a non-empty translation array")
            variants = set()
            for entry in translations:
                if not isinstance(entry, dict) or set(entry) != {"enSource", "ko"}:
                    raise ValueError(f"{name}.{field}: expected enSource and ko fields")
                source = normalize(entry["enSource"])
                if not source or not normalize(entry["ko"]):
                    raise ValueError(f"{name}.{field}: blank source or Korean translation")
                if source in variants:
                    raise ValueError(f"{name}.{field}: duplicate English source")
                variants.add(source)
            sources[name, field] = variants
    return sources


def compare_snapshot(path, registered, locales):
    snapshot = load_json(path, check_duplicates=True)
    cluster = snapshot.get("cluster", path.name)
    records = snapshot["settings"]
    missing = []
    count = 0

    for record in records:
        name = record["name"]
        for field in FIELDS:
            source = normalize(record.get(field))
            if not source:
                continue
            count += 1
            if source not in registered.get((name, field), set()):
                reason = "changed source" if (name, field) in registered else "missing translation"
                missing.append(f"{cluster} {name}.{field}: {reason}")

    sections = {"All Settings"}
    for entry in snapshot["groups"]:
        sections.add(entry["name"])
        sections.update(entry["subgroups"])
    for locale, messages in locales.items():
        for name in sorted(sections):
            if not normalize(messages.get(SECTION_PREFIX + name)):
                missing.append(f"{cluster} {locale}: missing section label {name}")

    print(f"{cluster}: {len(records)} settings, {count} source fields, {len(sections)} section labels")
    return missing


def main():
    parser = argparse.ArgumentParser(description="Validate the Korean global settings catalog.")
    parser.add_argument("snapshots", metavar="metadata.json", nargs="*", type=Path,
                        help="optional cluster metadata snapshots with settings and group names")
    args = parser.parse_args()

    try:
        data = load_json(CATALOG, check_duplicates=True)
        registered = catalog_sources(data)
        print(f"Catalog: {len(data)} settings, {sum(map(len, registered.values()))} translated source variants")
        if not args.snapshots:
            return 0

        locales = {locale: load_json(UI_ROOT / f"public/locales/{locale}.json") for locale in ("en", "ko_KR")}
        problems = []
        for path in args.snapshots:
            problems.extend(compare_snapshot(path, registered, locales))
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1 if problems else 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Global settings translations: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
