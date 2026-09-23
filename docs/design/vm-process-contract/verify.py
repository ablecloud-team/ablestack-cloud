#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
"""Contract examples and candidate payload validator; never executes guest commands."""
import argparse
import json
from datetime import datetime
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

ROOT = Path(__file__).resolve().parent
SCHEMA = json.loads((ROOT / "schema.json").read_text(encoding="utf-8"))
Draft202012Validator.check_schema(SCHEMA)
VALIDATOR = Draft202012Validator(SCHEMA, format_checker=FormatChecker())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def stamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def validate(payload):
    VALIDATOR.validate(payload)
    require(len(json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")) <= 1048576,
            "wire payload exceeds 1 MiB")
    kind = payload["kind"]
    authority = payload["authority"]
    if kind == "capability":
        ready = payload["readiness"] == "READY"
        if ready:
            require(all(v == "ENABLED" for v in payload["rpcs"].values()), "READY requires eight enabled RPCs")
            require("1.0" in payload["supportedSchemaVersions"], "READY needs negotiated version")
            require("process.list" in payload["allowedActions"], "READY needs list")
            require(payload["os"]["family"] != "unknown" and payload["os"]["arch"] == "x86_64", "unsupported OS")
            require(payload["hostToolsVersion"] and payload["guestAdapterVersion"], "READY needs tools versions")
            require(payload["error"] is None, "READY cannot have error")
        else:
            require(not payload["allowedActions"] and payload["error"] is not None, "unready must disable actions")
            require(payload["error"]["code"] == payload["readiness"], "readiness/error mismatch")
    if kind == "snapshot":
        require(stamp(payload["expiresAt"]) > stamp(payload["observedAt"]), "invalid snapshot interval")
        require((stamp(payload["expiresAt"]) - stamp(payload["observedAt"])).total_seconds() <= 10,
                "snapshot TTL exceeds ten seconds")
        identities = set()
        for process in payload["processes"]:
            identity = process["identity"]
            require(identity["vmUuid"] == authority["vmUuid"] and identity["bootId"] == payload["bootId"],
                    "snapshot identity mismatch")
            key = (identity["vmUuid"], identity["bootId"], identity["pid"], identity["startTicks"])
            require(key not in identities, "duplicate process identity")
            identities.add(key)
            require("service.restart" not in process["allowedActions"] or process["services"],
                    "restart requires service mapping")
        require(not payload["truncated"] or payload["status"] == "PARTIAL", "truncated must be PARTIAL")
        require(payload["totalKnown"] is None or payload["totalKnown"] >= len(payload["processes"]), "invalid count")
        if payload["status"] == "OK":
            require(payload["totalKnown"] == len(payload["processes"]), "complete count must match")
    if kind == "readRequest":
        require((payload["operation"] == "operation.get") == (payload["operationId"] is not None),
                "operationId only for operation.get")
    if kind in ("actionRequest", "actionResult"):
        require(payload["identity"]["vmUuid"] == authority["vmUuid"], "cross-VM identity")
        require((payload["action"] == "service.restart") == (payload["service"] is not None),
                "service target only for restart")
        if kind == "actionRequest":
            limit = 90000 if payload["action"] == "service.restart" else 15000
            require(payload["budgetMs"] <= limit, "action budget too large")
    if kind == "actionResult":
        state = payload["state"]
        if payload["completedAt"] is not None:
            require(stamp(payload["completedAt"]) >= stamp(payload["submittedAt"]), "completion precedes submission")
        if state == "SUCCEEDED":
            expected = "SERVICE_RESTART_VERIFIED" if payload["action"] == "service.restart" else "TARGET_EXITED"
            require(payload["effect"] == "VERIFIED" and payload["postcondition"] == expected,
                    "success requires action-specific postcondition")
            require(payload["error"] is None and payload["completedAt"] is not None, "invalid success")
            require(payload["guestExitCode"] in (None, 0), "nonzero guest exit cannot certify success")
        elif state == "UNKNOWN":
            require(payload["effect"] == "MAY_HAVE_RUN" and payload["completedAt"] is None, "UNKNOWN is unresolved")
            require(payload["postcondition"] == "NOT_CHECKED", "UNKNOWN lacks verified postcondition")
            require(payload["error"] is not None and payload["error"]["code"] in ("RESULT_UNKNOWN", "DEADLINE_EXCEEDED")
                    and payload["error"]["retryMode"] == "READ_ONLY", "UNKNOWN must never replay")
        elif state == "FAILED":
            require(payload["error"] is not None and payload["completedAt"] is not None, "failure needs evidence")
            require(payload["effect"] != "VERIFIED" and payload["postcondition"] == "NOT_CHECKED", "invalid failure")
        else:
            require(payload["completedAt"] is None and payload["error"] is None
                    and payload["postcondition"] == "NOT_CHECKED", "pending result cannot be final")
            require(payload["effect"] == ("NOT_STARTED" if state == "ACCEPTED" else "MAY_HAVE_RUN"),
                    "pending effect mismatch")
    if kind == "failure":
        require(payload["error"]["code"] not in ("RESULT_UNKNOWN", "DEADLINE_EXCEEDED"),
                "dispatched mutation ambiguity requires actionResult")


def load_wire(text):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "duplicate JSON key: " + key)
            result[key] = value
        return result

    def reject_constant(value):
        raise ValueError("non-finite JSON value: " + value)

    return json.loads(text, object_pairs_hook=pairs, parse_constant=reject_constant)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, help="Validate one producer envelope (not a fixture wrapper)")
    args = parser.parse_args()
    if args.file:
        validate(load_wire(args.file.read_text(encoding="utf-8")))
        print("PASS: producer payload conforms to schema and static invariants")
        return
    cases = json.loads((ROOT / "fixtures.json").read_text(encoding="utf-8"))
    require(len({c["name"] for c in cases}) == len(cases), "duplicate fixture name")
    accepted = rejected = 0
    for case in cases:
        try:
            validate(case["payload"])
        except (ValueError, ValidationError) as error:
            require(not case["valid"], case["name"] + ": unexpected rejection: " + str(error))
            rejected += 1
        else:
            require(case["valid"], case["name"] + ": invalid fixture accepted")
            accepted += 1
    for text in ('{"key":1,"key":2}', '{"value":NaN}', '{"value":Infinity}'):
        try:
            load_wire(text)
        except ValueError:
            pass
        else:
            raise ValueError("ambiguous JSON accepted")
    print(f"PASS: {accepted} accepted, {rejected} rejected, 3 malformed JSON rejected; schema and static invariants only")


if __name__ == "__main__":
    main()
