# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Durable kernel identifiers, separate from desired protocol configuration."""
import fcntl
import json
import os
from pathlib import Path
import re
import tempfile
import uuid


class NamespaceIdentityStore:
    ATTRIBUTES = {"device_uuid": 32, "device_nguid": 32, "device_eui64": 16}

    def __init__(self, path):
        self.path = Path(path)

    @staticmethod
    def key(nqn, namespace):
        nsid = int(namespace.get("lunOrNamespace") or 1)
        if not nqn or not 1 <= nsid <= 4294967295:
            raise ValueError("NVMEOF_NAMESPACE_IDENTITY_BINDING_INVALID")
        resource = str(uuid.UUID(str(namespace["uuid"])))
        volume = str(uuid.UUID(str(namespace["volumeUuid"])))
        return json.dumps([str(nqn), nsid, resource, volume], separators=(",", ":"))

    @classmethod
    def normalize(cls, name, value):
        token = str(value).strip().replace("-", "").lower()
        if not re.fullmatch("[0-9a-f]{" + str(cls.ATTRIBUTES[name]) + "}", token):
            raise ValueError("NVMEOF_NAMESPACE_IDENTITY_INVALID")
        if name == "device_uuid" and int(token, 16) == 0:
            raise ValueError("NVMEOF_NAMESPACE_IDENTITY_INVALID")
        return token

    @classmethod
    def observe(cls, directory):
        values = {}
        for name in cls.ATTRIBUTES:
            path = Path(directory) / name
            if path.exists():
                values[name] = cls.normalize(name, path.read_text())
        if "device_uuid" not in values:
            raise ValueError("NVMEOF_NAMESPACE_IDENTITY_UNSUPPORTED")
        return values

    def load(self):
        if not self.path.exists():
            return {"version": 1, "entries": {}}
        value = json.loads(self.path.read_text())
        if not isinstance(value, dict) or value.get("version") != 1 or not isinstance(value.get("entries"), dict):
            raise ValueError("NVMEOF_NAMESPACE_IDENTITY_STORE_INVALID")
        return value

    def save(self, value):
        fd, temporary = tempfile.mkstemp(prefix=".nvme-identity-", dir=self.path.parent)
        try:
            with os.fdopen(fd, "w") as stream:
                json.dump(value, stream, sort_keys=True)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
            directory_fd = os.open(self.path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def prepare(self, directory, nqn, namespace, backing_path):
        directory = Path(directory)
        binding = self.key(nqn, namespace)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with open(str(self.path) + ".lock", "a") as lock:
            os.chmod(lock.name, 0o600)
            fcntl.flock(lock, fcntl.LOCK_EX)
            value = self.load()
            actual = self.observe(directory)
            expected = value["entries"].get(binding)
            if expected is not None:
                if not isinstance(expected, dict) or "device_uuid" not in expected or any(k not in self.ATTRIBUTES for k in expected):
                    raise ValueError("NVMEOF_NAMESPACE_IDENTITY_STORE_INVALID")
                expected = {name: self.normalize(name, token) for name, token in expected.items()}
            enabled = (directory / "enable").read_text().strip() == "1"
            if enabled:
                current_path = (directory / "device_path").read_text().strip()
                if os.path.realpath(current_path) != os.path.realpath(backing_path):
                    raise ValueError("NVMEOF_NAMESPACE_IDENTITY_BACKING_CHANGED")
                if expected is not None and any(actual.get(k) != v for k, v in expected.items()):
                    raise ValueError("NVMEOF_NAMESPACE_IDENTITY_ACTIVE_MISMATCH")
            if expected is None:
                # Adopt existing native identity without changing a live device. Persist before enable.
                expected = actual
                value["entries"][binding] = expected
                self.save(value)
            if not enabled:
                for name, token in expected.items():
                    path = directory / name
                    if not path.exists():
                        raise ValueError("NVMEOF_NAMESPACE_IDENTITY_UNSUPPORTED")
                    if actual.get(name) != token:
                        if not path.stat().st_mode & 0o222:
                            raise ValueError("NVMEOF_NAMESPACE_IDENTITY_UNSUPPORTED")
                        rendered = str(uuid.UUID(token)) if self.ATTRIBUTES[name] == 32 else token
                        path.write_text(rendered)
            self.verify(directory, nqn, namespace)

    def verify(self, directory, nqn, namespace):
        expected = self.load()["entries"].get(self.key(nqn, namespace))
        actual = self.observe(directory)
        if not expected or any(actual.get(name) != token for name, token in expected.items()):
            raise ValueError("NVMEOF_NAMESPACE_IDENTITY_VERIFY_FAILED")
