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
package com.cloud.hypervisor.kvm.storage;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.cloudstack.utils.security.ParserUtils;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.ceph.rados.IoCTX;
import com.ceph.rados.Rados;
import com.ceph.rados.exceptions.RadosException;
import com.ceph.rbd.Rbd;
import com.ceph.rbd.RbdException;
import com.ceph.rbd.RbdImage;
import com.ceph.rbd.jna.RbdImageInfo;
import com.cloud.storage.Storage.StoragePoolType;

/** Read-only evidence for RBD deletion. A libvirt lookup or its error text is not evidence of absence. */
class RbdDeletionProbe {
    enum State { PRESENT, ABSENT_CONFIRMED, UNKNOWN }

    static final class Result {
        final State state;
        final String stage;
        final int nativeCode;
        final String fsid;
        final long poolId;
        final int snapshots;
        final String imagePrefix;

        Result(State state, String stage, int nativeCode, String fsid, long poolId, int snapshots, String imagePrefix) {
            this.state = state;
            this.stage = stage;
            this.nativeCode = nativeCode;
            this.fsid = fsid;
            this.poolId = poolId;
            this.snapshots = snapshots;
            this.imagePrefix = imagePrefix;
        }

        String describe() {
            return "state=" + state + " stage=" + stage + " nativeCode=" + nativeCode + " fsid=" + fsid
                    + " poolId=" + poolId + " namespace=<default> snapshots=" + snapshots + " imagePrefix=" + imagePrefix;
        }
    }

    Result probe(KVMStoragePool pool, String imageName, String poolXml) {
        Rados connection = null;
        IoCTX context = null;
        Rbd rbd = null;
        RbdImage image = null;
        String stage = "TARGET";
        String fsid = null;
        long poolId = -1;
        Result result = unknown(stage, 0, fsid, poolId);
        try {
            validateTarget(pool, imageName, poolXml);
            stage = "CONFIGURATION";
            connection = new Rados(StringUtils.isBlank(pool.getAuthUserName()) ? null : pool.getAuthUserName());
            configure(connection, pool);
            stage = "CONNECT";
            connection.connect();
            fsid = UUID.fromString(connection.clusterFsid()).toString();
            stage = "POOL";
            context = connection.ioCtxCreate(pool.getSourceDir());
            context.setNamespace("");
            poolId = context.getId();
            if (poolId < 0) {
                throw new IllegalArgumentException("Invalid pool identity");
            }
            rbd = new Rbd(context);
            stage = "OPEN_IMAGE";
            image = rbd.openReadOnly(imageName);
            stage = "READ_IMAGE";
            RbdImageInfo info = image.stat();
            String prefix = info.block_name_prefix == null ? "" : new String(info.block_name_prefix, StandardCharsets.US_ASCII).split("\u0000", 2)[0];
            result = new Result(State.PRESENT, stage, 0, fsid, poolId, image.snapList().size(), prefix);
        } catch (RbdException error) {
            // ENOENT from connect/pool/namespace errors must never become an absent image.
            State state = "OPEN_IMAGE".equals(stage) && error.getReturnValue() == -2 ? State.ABSENT_CONFIRMED : State.UNKNOWN;
            result = new Result(state, stage, error.getReturnValue(), fsid, poolId, -1, null);
        } catch (RadosException error) {
            result = unknown(stage, error.getReturnValue(), fsid, poolId);
        } catch (Exception | LinkageError error) {
            // Do not include raw native errors, XML, configuration, or authentication material in the answer.
            result = unknown(stage, 0, fsid, poolId);
        } finally {
            boolean cleanupFailed = false;
            try {
                if (image != null) {
                    rbd.close(image);
                }
            } catch (Exception | LinkageError error) {
                cleanupFailed = true;
            }
            try {
                if (context != null) {
                    connection.ioCtxDestroy(context);
                }
            } catch (Exception | LinkageError error) {
                cleanupFailed = true;
            }
            try {
                if (connection != null) {
                    connection.shutDown();
                }
            } catch (Exception | LinkageError error) {
                cleanupFailed = true;
            }
            if (cleanupFailed) {
                result = unknown("CLEANUP", 0, fsid, poolId);
            }
        }
        return result;
    }

    static Result unknown(String stage, int code, String fsid, long poolId) {
        return new Result(State.UNKNOWN, stage, code, fsid, poolId, -1, null);
    }

    static void configure(Rados connection, KVMStoragePool pool) throws RadosException {
        connection.confSet("mon_host", monitors(pool));
        connection.confSet("auth_supported", StringUtils.isBlank(pool.getAuthUserName()) ? "none" : "cephx");
        if (StringUtils.isNotBlank(pool.getAuthUserName())) {
            connection.confSet("key", pool.getAuthSecret());
        }
        connection.confSet("client_mount_timeout", "30");
        connection.confSet("rados_mon_op_timeout", "30");
        connection.confSet("rados_osd_op_timeout", "30");
    }

    private static Set<String> hosts(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("Missing monitors");
        }
        Set<String> hosts = Arrays.stream(value.split(",", -1)).map(String::trim).map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if (hosts.stream().anyMatch(host -> !host.matches("[a-z0-9._:-]+"))) {
            throw new IllegalArgumentException("Invalid monitors");
        }
        return hosts;
    }

    private static String monitors(KVMStoragePool pool) {
        return hosts(pool.getSourceHost()).stream().sorted().map(host -> {
            String address = host.contains(":") ? "[" + host + "]" : host;
            return pool.getSourcePort() > 0 ? address + ":" + pool.getSourcePort() : address;
        }).collect(Collectors.joining(","));
    }

    static void validateTarget(KVMStoragePool pool, String imageName, String poolXml) throws Exception {
        if (pool.getType() != StoragePoolType.RBD || StringUtils.isBlank(pool.getSourceDir())
                || !pool.getSourceDir().matches("[a-zA-Z0-9_.-]+") || StringUtils.isBlank(imageName)
                || !imageName.matches("[a-zA-Z0-9_.-]+") || pool.getSourcePort() < 0 || pool.getSourcePort() > 65535) {
            throw new IllegalArgumentException("Unqualified RBD target");
        }
        Element root = ParserUtils.getSaferDocumentBuilderFactory().newDocumentBuilder()
                .parse(new InputSource(new StringReader(poolXml))).getDocumentElement();
        if (!"pool".equals(root.getTagName()) || !"rbd".equals(root.getAttribute("type"))
                || !UUID.fromString(pool.getUuid()).equals(UUID.fromString(root.getElementsByTagName("uuid").item(0).getTextContent().trim()))) {
            throw new IllegalArgumentException("Pool identity mismatch");
        }
        NodeList nodes = root.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String tag = element.getTagName().toLowerCase(Locale.ROOT);
            if (tag.equals("namespace") || tag.endsWith(":namespace")) {
                throw new IllegalArgumentException("Unverified namespace");
            }
            if ((tag.equals("option") || tag.endsWith(":option"))
                    && !Set.of("rbd_default_data_pool", "rbd_default_format").contains(element.getAttribute("name"))) {
                throw new IllegalArgumentException("Unverified RBD configuration override");
            }
        }
        Element source = (Element) root.getElementsByTagName("source").item(0);
        if (!pool.getSourceDir().equals(source.getElementsByTagName("name").item(0).getTextContent().trim())) {
            throw new IllegalArgumentException("RBD pool mismatch");
        }
        Set<String> xmlHosts = new HashSet<>();
        NodeList endpoints = source.getElementsByTagName("host");
        for (int i = 0; i < endpoints.getLength(); i++) {
            Element endpoint = (Element) endpoints.item(i);
            int port = StringUtils.isBlank(endpoint.getAttribute("port")) ? 0 : Integer.parseInt(endpoint.getAttribute("port"));
            if (port != pool.getSourcePort()) {
                throw new IllegalArgumentException("Monitor port mismatch");
            }
            xmlHosts.add(endpoint.getAttribute("name").trim().toLowerCase(Locale.ROOT));
        }
        if (!hosts(pool.getSourceHost()).equals(xmlHosts)) {
            throw new IllegalArgumentException("Monitor mismatch");
        }
        Element auth = (Element) source.getElementsByTagName("auth").item(0);
        if (auth == null) {
            if (StringUtils.isNotBlank(pool.getAuthUserName()) || StringUtils.isNotBlank(pool.getAuthSecret())) {
                throw new IllegalArgumentException("Authentication mismatch");
            }
        } else if (!"ceph".equals(auth.getAttribute("type")) || !StringUtils.equals(pool.getAuthUserName(), auth.getAttribute("username"))
                || StringUtils.isBlank(pool.getAuthSecret()) || !UUID.fromString(pool.getUuid()).equals(
                        UUID.fromString(((Element) auth.getElementsByTagName("secret").item(0)).getAttribute("uuid")))) {
            throw new IllegalArgumentException("Authentication mismatch");
        }
    }
}
