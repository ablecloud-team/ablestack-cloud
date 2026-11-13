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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import com.ceph.rados.IoCTX;
import com.ceph.rados.Rados;
import com.ceph.rados.exceptions.RadosException;
import com.ceph.rbd.Rbd;
import com.ceph.rbd.RbdException;
import com.ceph.rbd.RbdImage;
import com.ceph.rbd.jna.RbdImageInfo;
import com.ceph.rbd.jna.RbdSnapInfo;
import com.cloud.storage.Storage.StoragePoolType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;

public class RbdDeletionProbeTest {
    private static final String POOL = "91cae554-3fce-3f93-89d1-cefaf9bf8122";
    private static final String IMAGE = "7a927ec9-3d37-4723-872f-c7562132fc12";
    private static final String FSID = "47c73ef8-b237-11f1-aafa-002481547a21";
    private static final String XML = "<pool type='rbd'><uuid>" + POOL + "</uuid><source><host name='mon-a'/><host name='mon-b'/>"
            + "<name>rbd</name><auth type='ceph' username='admin'><secret uuid='" + POOL + "'/></auth></source></pool>";
    private final LibvirtStoragePool pool = mock(LibvirtStoragePool.class);
    private final IoCTX context = mock(IoCTX.class);
    private final RbdImage image = mock(RbdImage.class);
    private final RbdDeletionProbe probe = new RbdDeletionProbe();
    private MockedConstruction<Rados> connections;
    private MockedConstruction<Rbd> stores;

    @Before public void setup() throws Exception {
        when(pool.getType()).thenReturn(StoragePoolType.RBD);
        when(pool.getUuid()).thenReturn(POOL);
        when(pool.getSourceDir()).thenReturn("rbd");
        when(pool.getSourceHost()).thenReturn("mon-b,mon-a");
        when(pool.getAuthUserName()).thenReturn("admin");
        when(pool.getAuthSecret()).thenReturn("fixture-secret-not-for-output");
        when(context.getId()).thenReturn(3L);
        when(image.stat()).thenReturn(new RbdImageInfo());
        when(image.snapList()).thenReturn(Collections.emptyList());
        connections = mockConstruction(Rados.class, (connection, ignored) -> {
            when(connection.clusterFsid()).thenReturn(FSID);
            when(connection.ioCtxCreate("rbd")).thenReturn(context);
        });
        stores = mockConstruction(Rbd.class, (store, ignored) -> when(store.openReadOnly(IMAGE)).thenReturn(image));
    }

    @After public void cleanup() { stores.close(); connections.close(); }

    private RbdDeletionProbe.Result read() { return probe.probe(pool, IMAGE, XML); }
    private Rados connection() { return connections.constructed().get(0); }
    private Rbd store() { return stores.constructed().get(0); }
    private RbdException rbdError(int code) { RbdException e = mock(RbdException.class); when(e.getReturnValue()).thenReturn(code); return e; }
    private RadosException radosError(int code) { RadosException e = mock(RadosException.class); when(e.getReturnValue()).thenReturn(code); return e; }
    private void noNativeAccess(RbdDeletionProbe.Result result) {
        assertEquals(RbdDeletionProbe.State.UNKNOWN, result.state); assertEquals("TARGET", result.stage);
        assertTrue(connections.constructed().isEmpty()); assertTrue(stores.constructed().isEmpty());
    }

    @Test public void presentEvidenceUsesExactPoolAndClosesAllHandles() throws Exception {
        RbdDeletionProbe.Result result = read(); assertEquals(RbdDeletionProbe.State.PRESENT, result.state);
        assertEquals(FSID, result.fsid); assertEquals(3, result.poolId); assertEquals(0, result.snapshots);
        verify(connection()).confSet("mon_host", "mon-a,mon-b"); verify(context).setNamespace("");
        var order = inOrder(store(), connection()); order.verify(store()).close(image); order.verify(connection()).ioCtxDestroy(context); order.verify(connection()).shutDown();
        verify(store(), never()).remove(anyString()); verify(image, never()).snapRemove(anyString());
        assertFalse(result.describe().contains("fixture-secret"));
    }

    @Test public void onlyImageOpenEnoentIsConfirmedAbsent() throws Exception {
        stores.close(); stores = mockConstruction(Rbd.class, (s, ignored) -> when(s.openReadOnly(IMAGE)).thenThrow(rbdError(-2)));
        assertEquals(RbdDeletionProbe.State.ABSENT_CONFIRMED, read().state);
        verify(connection()).ioCtxDestroy(context); verify(connection()).shutDown(); verify(store(), never()).close(any());
    }

    @Test public void imagePermissionFailureIsUnknown() throws Exception {
        stores.close(); stores = mockConstruction(Rbd.class, (s, ignored) -> when(s.openReadOnly(IMAGE)).thenThrow(rbdError(-13)));
        RbdDeletionProbe.Result r = read(); assertEquals(RbdDeletionProbe.State.UNKNOWN, r.state); assertEquals(-13, r.nativeCode);
        verify(connection()).ioCtxDestroy(context); verify(connection()).shutDown();
    }

    @Test public void busyImageIsNotAbsent() throws Exception {
        stores.close(); stores = mockConstruction(Rbd.class, (s, ignored) -> when(s.openReadOnly(IMAGE)).thenThrow(rbdError(-16)));
        assertEquals(RbdDeletionProbe.State.UNKNOWN, read().state);
    }

    @Test public void connectEnoentDoesNotMeanImageAbsent() throws Exception {
        connections.close(); connections = mockConstruction(Rados.class, (c, ignored) -> doThrow(radosError(-2)).when(c).connect());
        RbdDeletionProbe.Result r = read(); assertEquals(RbdDeletionProbe.State.UNKNOWN, r.state); assertEquals("CONNECT", r.stage); verify(connection()).shutDown();
    }

    @Test public void connectPermissionFailureDoesNotMeanAbsent() throws Exception {
        connections.close(); connections = mockConstruction(Rados.class, (c, ignored) -> doThrow(radosError(-13)).when(c).connect());
        assertEquals(RbdDeletionProbe.State.UNKNOWN, read().state); verify(connection()).shutDown();
    }

    @Test public void connectionTimeoutDoesNotMeanAbsent() throws Exception {
        connections.close(); connections = mockConstruction(Rados.class, (c, ignored) -> doThrow(radosError(-110)).when(c).connect());
        assertEquals(RbdDeletionProbe.State.UNKNOWN, read().state);
    }

    @Test public void missingPoolDoesNotMeanImageAbsent() throws Exception {
        connections.close(); connections = mockConstruction(Rados.class, (c, ignored) -> {
            when(c.clusterFsid()).thenReturn(FSID); when(c.ioCtxCreate("rbd")).thenThrow(radosError(-2));
        });
        RbdDeletionProbe.Result r = read(); assertEquals(RbdDeletionProbe.State.UNKNOWN, r.state); assertEquals("POOL", r.stage); verify(connection()).shutDown();
    }

    @Test public void imageVanishingDuringStatRequiresRevalidation() throws Exception {
        when(image.stat()).thenThrow(rbdError(-2)); RbdDeletionProbe.Result r = read();
        assertEquals(RbdDeletionProbe.State.UNKNOWN, r.state); assertEquals("READ_IMAGE", r.stage);
        verify(store()).close(image); verify(connection()).ioCtxDestroy(context); verify(connection()).shutDown();
    }

    @Test public void snapshotReadFailureIsNotAbsence() throws Exception {
        when(image.snapList()).thenThrow(rbdError(-16)); assertEquals(RbdDeletionProbe.State.UNKNOWN, read().state);
        verify(store()).close(image); verify(connection()).shutDown();
    }

    @Test public void snapshotEvidenceDoesNotModifyReferences() throws Exception {
        when(image.snapList()).thenReturn(Collections.singletonList(mock(RbdSnapInfo.class)));
        assertEquals(1, read().snapshots); verify(image, never()).snapRemove(anyString()); verify(image, never()).snapUnprotect(anyString());
    }

    @Test public void failedImageCloseInvalidatesEvidenceButReleasesConnection() throws Exception {
        stores.close(); stores = mockConstruction(Rbd.class, (s, ignored) -> {
            when(s.openReadOnly(IMAGE)).thenReturn(image); doThrow(rbdError(-5)).when(s).close(image);
        });
        RbdDeletionProbe.Result r = read(); assertEquals(RbdDeletionProbe.State.UNKNOWN, r.state); assertEquals("CLEANUP", r.stage);
        verify(connection()).ioCtxDestroy(context); verify(connection()).shutDown();
    }

    @Test public void failedContextCloseStillShutsDownConnection() throws Exception {
        connections.close(); connections = mockConstruction(Rados.class, (c, ignored) -> {
            when(c.clusterFsid()).thenReturn(FSID); when(c.ioCtxCreate("rbd")).thenReturn(context); doThrow(new IllegalStateException()).when(c).ioCtxDestroy(context);
        });
        assertEquals(RbdDeletionProbe.State.UNKNOWN, read().state); verify(connection()).shutDown();
    }

    @Test public void configurationFailureClosesUnconnectedHandle() throws Exception {
        connections.close(); connections = mockConstruction(Rados.class, (c, ignored) -> doThrow(radosError(-22)).when(c).confSet(eq("mon_host"), anyString()));
        RbdDeletionProbe.Result r = read(); assertEquals("CONFIGURATION", r.stage); assertEquals(RbdDeletionProbe.State.UNKNOWN, r.state); verify(connection()).shutDown();
    }

    @Test public void unsafeImageAndNamespacePathsNeverReachNativeApi() {
        for (String value : new String[]{"rbd/" + IMAGE, "../" + IMAGE, IMAGE + "@snap", "ns/" + IMAGE, "", "a\\b", "a\nkey"}) {
            noNativeAccess(probe.probe(pool, value, XML));
        }
    }

    @Test public void wrongPoolAndNamespaceNeverBecomeAbsence() {
        for (String xml : new String[]{XML.replace("<name>rbd</name>", "<name>other</name>"),
                XML.replace("<source>", "<source><namespace>other</namespace>"),
                XML.replace("</pool>", "<config><option name='rbd_namespace' value='other'/></config></pool>"),
                XML.replace("mon-b", "wrong-mon"), XML.replace("<host name='mon-a'/>", "<host name='mon-a' port='6789'/>"),
                XML.replace("username='admin'", "username='other'"), XML.replace("<uuid>" + POOL, "<uuid>00000000-0000-0000-0000-000000000000"),
                "<pool type='rbd'>"}) {
            noNativeAccess(probe.probe(pool, IMAGE, xml));
        }
    }

    @Test public void malformedPoolConfigurationNeverConnects() {
        when(pool.getSourceDir()).thenReturn("rbd/namespace"); noNativeAccess(read());
    }
}
