// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloud.hypervisor.kvm.resource.wrapper;

import org.libvirt.Domain;
import org.libvirt.LibvirtException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class VncTlsCertificateReloader {
    static final long MIN_QEMU_VERSION = 6000000L;
    static final String QUERY_VNC = "{\"execute\":\"query-vnc\"}";
    static final String RELOAD_VNC_TLS = "{\"execute\":\"display-reload\",\"arguments\":{\"type\":\"vnc\",\"tls-certs\":true}}";

    private VncTlsCertificateReloader() {
    }

    static boolean isSupported(long qemuVersion) {
        return qemuVersion >= MIN_QEMU_VERSION;
    }

    static boolean reloadIfEnabled(Domain vm) throws LibvirtException {
        JsonObject vnc = execute(vm, QUERY_VNC);
        if (!vnc.has("auth") || vnc.get("auth").isJsonNull() || !vnc.get("auth").getAsString().startsWith("vencrypt+x509")) {
            return false;
        }
        execute(vm, RELOAD_VNC_TLS);
        return true;
    }

    private static JsonObject execute(Domain vm, String command) throws LibvirtException {
        String response = vm.qemuMonitorCommand(command, 0);
        if (response == null) {
            throw new IllegalStateException("QEMU monitor returned no response to " + command);
        }
        JsonObject reply = new JsonParser().parse(response).getAsJsonObject();
        if (reply.has("error") || !reply.has("return") || !reply.get("return").isJsonObject()) {
            throw new IllegalStateException("QEMU monitor rejected " + command + ": " + reply);
        }
        return reply.getAsJsonObject("return");
    }
}
