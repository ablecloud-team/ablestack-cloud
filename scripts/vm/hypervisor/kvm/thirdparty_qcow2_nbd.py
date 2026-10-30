# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Bounded NBD copy for ABLESTACK volume staging; no ImageServer lifecycle dependencies."""
import contextlib
import importlib
import shutil
import sys
import time

IO_BLOCK = 4 * 1024 * 1024
STATUS_BLOCK = 32 * 1024 * 1024
ALLOCATION = "base:allocation"
STATE_ZERO = 2
STATE_DIRTY = 1


def release_handle(handle, connected=False):
    if connected:
        with contextlib.suppress(Exception):
            handle.shutdown()
    # Python bindings before 1.16 release the handle when its last reference goes away.
    close = getattr(handle, "close", None)
    if callable(close):
        with contextlib.suppress(Exception):
            close()


def preflight(incremental):
    """Load libnbd only for QCOW2, before admission or source preparation."""
    try:
        module = importlib.import_module("nbd")
    except (ImportError, OSError) as exc:
        raise RuntimeError("Volume QCOW2 staging requires a working host python3-libnbd package for %s" %
                           sys.executable) from exc
    missing = [tool for tool in ("virsh", "qemu-img", "qemu-nbd") if not shutil.which(tool)]
    if missing:
        raise RuntimeError("Volume QCOW2 staging is missing host tools: " + ", ".join(missing))
    handle = module.NBD()
    try:
        required = ("set_export_name", "connect_unix", "get_size", "pread", "pwrite",
                    "is_read_only", "can_flush", "flush", "shutdown")
        missing = [name for name in required if not callable(getattr(handle, name, None))]
        if incremental:
            missing += [name for name in ("add_meta_context", "can_meta_context")
                        if not callable(getattr(handle, name, None))]
            if not any(callable(getattr(handle, name, None)) for name in ("block_status_64", "block_status")):
                missing.append("block_status/block_status_64")
            if not getattr(module, "CMD_FLAG_REQ_ONE", 0):
                missing.append("CMD_FLAG_REQ_ONE")
        if missing:
            raise RuntimeError("Host python3-libnbd lacks required QCOW2 APIs: " + ", ".join(missing))
        print("QCOW2 NBD preflight: python=%s module=%s libnbd=%s" %
              (sys.executable, getattr(module, "__file__", "unknown"), handle.get_version()), flush=True)
    finally:
        release_handle(handle)
        del handle
    return module


def first_extent(entries):
    """Validate classic flat entries and 64-bit tuple entries without accumulating extents."""
    if not entries:
        raise RuntimeError("Empty NBD block status response")
    tuples = isinstance(entries[0], (tuple, list))
    if not tuples and len(entries) % 2:
        raise RuntimeError("Unpaired NBD block status entries")
    first = None
    for i in range(len(entries) if tuples else len(entries) // 2):
        pair = entries[i] if tuples else (entries[2 * i], entries[2 * i + 1])
        if not isinstance(pair, (tuple, list)) or len(pair) != 2:
            raise RuntimeError("Invalid NBD block status entry format")
        length, flags = pair
        if (type(length) is not int or type(flags) is not int
                or length <= 0 or length > (1 << 64) - 1 or flags < 0 or flags > (1 << 64) - 1):
            raise RuntimeError("Invalid NBD block status length or flags")
        if first is None:
            first = (length, flags)
    return first


class Connection:
    def __init__(self, module, socket, expected_size, export=None, dirty_context=None, writable=False):
        self.module = module
        self.socket = str(socket)
        self.expected_size = expected_size
        self.export = export
        self.context = dirty_context or (None if writable else ALLOCATION)
        self.incremental = dirty_context is not None
        self.writable = writable
        self.handle = None
        self.connected = False
        self.status = None
        self.minimum = 512
        self.maximum = IO_BLOCK

    def __enter__(self):
        self.handle = self.module.NBD()
        try:
            if self.export:
                self.handle.set_export_name(self.export)
            self.status = getattr(self.handle, "block_status_64", None) or getattr(self.handle, "block_status", None)
            metadata = (callable(self.status) and callable(getattr(self.handle, "add_meta_context", None))
                        and callable(getattr(self.handle, "can_meta_context", None))
                        and bool(getattr(self.module, "CMD_FLAG_REQ_ONE", 0)))
            if self.context and metadata:
                try:
                    self.handle.add_meta_context(self.context)
                except InterruptedError:
                    raise
                except Exception as exc:
                    if self.incremental:
                        raise RuntimeError("Cannot request the incremental dirty bitmap") from exc
                    self.disable_allocation(str(exc))
            elif self.incremental:
                raise RuntimeError("Host libnbd cannot query the required incremental dirty bitmap")
            elif self.context:
                self.disable_allocation("host libnbd lacks allocation metadata APIs")
            else:
                self.context = None
            self.handle.connect_unix(self.socket)
            self.connected = True
            self.size = self.handle.get_size()
            if type(self.size) is not int or self.size <= 0 or self.size != self.expected_size:
                raise RuntimeError("NBD image size differs from the planned volume: expected=%s actual=%s" %
                                   (self.expected_size, self.size))
            if self.writable and (self.handle.is_read_only() or not self.handle.can_flush()):
                raise RuntimeError("QCOW2 output NBD export must be writable and support flush")
            if self.context:
                try:
                    if not self.handle.can_meta_context(self.context):
                        raise RuntimeError("Source does not expose the requested metadata context")
                except InterruptedError:
                    raise
                except Exception as exc:
                    if self.incremental:
                        raise RuntimeError("Source does not expose the requested incremental dirty bitmap") from exc
                    self.disable_allocation(str(exc))
            if callable(getattr(self.handle, "get_block_size", None)):
                self.minimum = self.handle.get_block_size(getattr(self.module, "SIZE_MINIMUM", 0)) or 512
                self.maximum = min(IO_BLOCK, self.handle.get_block_size(getattr(self.module, "SIZE_MAXIMUM", 2)) or IO_BLOCK)
            if (self.minimum <= 0 or self.minimum > 65536 or self.minimum & (self.minimum - 1)
                    or self.maximum < self.minimum or self.size % self.minimum):
                raise RuntimeError("Incompatible NBD block size constraints")
            return self
        except BaseException:
            self.close()
            raise

    def close(self):
        self.status = None  # A bound method would otherwise keep an old binding's handle alive.
        if self.handle is not None:
            release_handle(self.handle, self.connected)
            self.handle = None
        self.connected = False

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()

    def disable_allocation(self, reason):
        print("QCOW2 sparse read optimization disabled; reading source data: %s" % reason, flush=True)
        self.context = None

    def extent(self, offset, count):
        """Return (length, dirty, known_zero); missing dirty information is always fatal."""
        if self.context is None:
            return count, True, False
        result = []
        errors = []

        def callback(name, start, entries, error):
            if name != self.context:
                return 0
            try:
                if getattr(error, "value", error):
                    raise RuntimeError("NBD block status callback reports an error")
                if start != offset or result:
                    raise RuntimeError("NBD block status has an unexpected or duplicate offset")
                result.append(first_extent(entries))
                return 0
            except Exception as exc:
                errors.append(exc)
                return -1

        try:
            # Both APIs use (count, offset). Never guess argument order by swapping integers.
            try:
                self.status(count, offset, callback, flags=self.module.CMD_FLAG_REQ_ONE)
            except Exception as exc:
                if errors:
                    raise errors[0] from exc
                raise
            if errors:
                raise errors[0]
            if not result:
                raise RuntimeError("NBD block status omitted the requested metadata context")
            length, flags = result[0]
            if self.incremental and flags & ~STATE_DIRTY:
                raise RuntimeError("Unknown incremental dirty bitmap flags")
            return min(count, length), (bool(flags & STATE_DIRTY) if self.incremental else True), (
                not self.incremental and bool(flags & STATE_ZERO))
        except InterruptedError:
            raise
        except Exception as exc:
            if self.incremental:
                raise RuntimeError("Unable to confirm the incremental dirty bitmap at offset %d" % offset) from exc
            self.disable_allocation(str(exc))
            return count, True, False

    def read(self, count, offset):
        self.check_range(count, offset)
        data = self.handle.pread(count, offset)
        if not isinstance(data, (bytes, bytearray)) or len(data) != count:
            raise RuntimeError("NBD source returned incomplete data at offset %d" % offset)
        return data

    def write(self, data, offset):
        if not self.writable:
            raise RuntimeError("Cannot write to the source NBD connection")
        self.check_range(len(data), offset)
        self.handle.pwrite(data, offset)

    def check_range(self, count, offset):
        if (count <= 0 or offset < 0 or offset + count > self.size or count > self.maximum
                or offset % self.minimum or count % self.minimum):
            raise RuntimeError("NBD request is outside the image or its block size constraints")


def copy_volume(source, destination, incremental, check_cancel, throttle, progress, deadline):
    if source.size != destination.size or source.incremental != incremental:
        raise RuntimeError("NBD copy source, destination or incremental mode differs from the plan")
    alignment = max(source.minimum, destination.minimum)
    block = min(source.maximum, destination.maximum)
    block -= block % alignment
    if block <= 0 or source.size % alignment:
        raise RuntimeError("Source and output NBD block size constraints are incompatible")

    def check_running():
        check_cancel()
        if time.monotonic() >= deadline:
            raise RuntimeError("QCOW2 volume export timed out")

    offset = 0
    while offset < source.size:
        check_running()
        count = min(STATUS_BLOCK, source.size - offset)
        length, dirty, zero = source.extent(offset, count)
        if length % alignment:
            # Read the containing aligned range; metadata cannot certify the neighbouring bytes.
            length = min(count, ((length + alignment - 1) // alignment) * alignment)
            dirty, zero = True, False
        end = offset + length
        if not dirty or zero:
            # Only explicit ZERO metadata permits a Full read to be skipped; HOLE alone does not.
            offset = end
            progress(offset / source.size)
            continue
        while offset < end:
            check_running()
            count = min(block, end - offset)
            started = time.monotonic()
            data = source.read(count, offset)
            # Always allocate changed zero data in an incremental overlay before adding its parent.
            if incremental or any(data):
                destination.write(data, offset)
            throttle(count, started)
            offset += count
            progress(offset / source.size)
    check_running()
    destination.handle.flush()
    check_running()
