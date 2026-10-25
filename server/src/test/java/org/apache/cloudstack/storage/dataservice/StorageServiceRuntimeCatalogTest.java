// Licensed under the Apache License, Version 2.0. See NOTICE.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.util.*;
import org.apache.cloudstack.api.command.admin.storage.dataservice.*;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.*;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.AccountManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;
import org.junit.*;
import org.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StorageServiceRuntimeCatalogTest {
    @Mock StorageServiceRuntimeBundleDao bundleDao;
    @Mock StorageServiceRuntimeBundleValidationDao validationDao;
    @Mock StorageServiceRuntimeBundleEventDao eventDao;
    @Mock StorageServiceRuntimeUpgradeDao upgradeDao;
    @Mock StorageServiceInstanceDao instanceDao;
    @Mock SharedFSDao sharedFSDao;
    @Mock VMInstanceDao vmDao;
    @Mock AccountManager accountManager;
    @Mock StorageServiceRuntimeUpgradeManagerImpl runtime;
    @Spy @InjectMocks StorageServiceRuntimeCatalogManagerImpl catalog;
    AutoCloseable mocks;
    MockedStatic<org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext> jobs;
    List<StorageServiceRuntimeBundleEventVO> events;
    StorageServiceRuntimeBundleVO bundle;
    RegisterStorageServiceRuntimeBundleCmd registration;
    @Before public void before() {
        mocks=MockitoAnnotations.openMocks(this);
        jobs=mockStatic(org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext.class);
        Account caller=mock(Account.class);when(caller.getId()).thenReturn(2L);
        User user=mock(User.class);when(user.getId()).thenReturn(2L);
        CallContext.register(user,caller);when(accountManager.isRootAdmin(2L)).thenReturn(true);
        bundle=new StorageServiceRuntimeBundleVO("v2","1","1",StorageServiceRuntimeBundleVO.ServiceImpact.NONE,
                "http://127.0.0.1/b","http://127.0.0.1/m","http://127.0.0.1/s",100L,"a".repeat(64),"b".repeat(64),"key");
        ReflectionTestUtils.setField(bundle,"id",9L);
        when(bundleDao.lockRow(9L,true)).thenReturn(bundle);
        when(bundleDao.findByIdIncludingRemoved(9L)).thenReturn(bundle);
        events=new ArrayList<>();when(eventDao.listAll()).thenAnswer(call->new ArrayList<>(events));
        when(eventDao.persist(any())).thenAnswer(call->{StorageServiceRuntimeBundleEventVO e=call.getArgument(0);ReflectionTestUtils.setField(e,"id",(long)events.size()+1);events.add(e);return e;});
        registration=mock(RegisterStorageServiceRuntimeBundleCmd.class);
        when(registration.getArtifactSize()).thenReturn(null);
        when(registration.getVersion()).thenReturn("v2");when(registration.getRuntimeAbiVersion()).thenReturn("1");
        when(registration.getDesiredStateSchemaVersion()).thenReturn("1");when(registration.getServiceImpact()).thenReturn("NONE");
        when(registration.getSigningKeyId()).thenReturn("key");when(registration.getArtifactUrl()).thenReturn("http://127.0.0.1/b");
        when(registration.getManifestUrl()).thenReturn("http://127.0.0.1/m");when(registration.getSignatureUrl()).thenReturn("http://127.0.0.1/s");
        when(registration.getSha256()).thenReturn("a".repeat(64));when(registration.getManifestSha256()).thenReturn("b".repeat(64));
    }
    @After public void after() throws Exception {CallContext.unregister();jobs.close();mocks.close();}
    private TransitionStorageServiceRuntimeBundleCmd action(String kind,String request,long revision) {
        TransitionStorageServiceRuntimeBundleCmd c=mock(TransitionStorageServiceRuntimeBundleCmd.class);
        when(c.getBundleId()).thenReturn(9L);when(c.getAction()).thenReturn(kind);
        when(c.getRequestId()).thenReturn(request);when(c.getExpectedRevision()).thenReturn(revision);when(c.getReason()).thenReturn("operator reason");
        return c;
    }
    @Test public void registrationDoesNotVerifyOrPublish() {
        when(bundleDao.persist(any())).thenReturn(bundle);
        catalog.register(registration);
        assertEquals(StorageServiceRuntimeBundleVO.State.DRAFT,bundle.getState());
        assertEquals(StorageServiceRuntimeBundleVO.ValidationStatus.UNVERIFIED,bundle.getValidationStatus());
        verify(runtime,never()).download(anyString(),anyInt());assertEquals("REGISTER",events.get(0).getAction());
    }
    @Test public void sameIdentityKeepsSuspensionAndDoesNotRewriteOptionalUrls() {
        when(bundleDao.findVersionIncludingRemoved("v2")).thenReturn(bundle);
        bundle.setState(StorageServiceRuntimeBundleVO.State.SUSPENDED);bundle.setStateRevision(7);
        when(registration.getArtifactUrl()).thenReturn("http://127.0.0.1/another");
        catalog.register(registration);
        assertEquals(StorageServiceRuntimeBundleVO.State.SUSPENDED,bundle.getState());assertEquals(7,bundle.getStateRevision());
        verify(bundleDao,never()).persist(any());assertEquals("http://127.0.0.1/b",bundle.getArtifactUrl());
    }
    @Test(expected=CloudRuntimeException.class) public void conflictingHashIsRejected() {
        when(bundleDao.findVersionIncludingRemoved("v2")).thenReturn(bundle);when(registration.getSha256()).thenReturn("c".repeat(64));
        catalog.register(registration);
    }
    @Test public void removedIdentityCannotBeResurrectedByRegistration() {
        when(bundleDao.findVersionIncludingRemoved("v2")).thenReturn(bundle);
        bundle.setState(StorageServiceRuntimeBundleVO.State.DELETED);bundle.setRemoved(new Date());catalog.register(registration);
        assertEquals(StorageServiceRuntimeBundleVO.State.DELETED,bundle.getState());verify(bundleDao,never()).persist(any());
    }
    @Test(expected=CloudRuntimeException.class) public void nonRootIsRejectedBeforeAnyDaoOrDownload() {
        when(accountManager.isRootAdmin(2L)).thenReturn(false);
        try {catalog.register(registration);}finally{verifyNoInteractions(bundleDao,eventDao,runtime);}
    }
    @Test public void publishSuspendRepublishAreRevisionedAndRequestIdempotent() {
        doReturn(true).when(catalog).verified(bundle);
        catalog.transition(action("PUBLISH","pub-1",0));assertEquals(StorageServiceRuntimeBundleVO.State.PUBLISHED,bundle.getState());
        catalog.transition(action("PUBLISH","pub-1",0));assertEquals(1,bundle.getStateRevision());assertEquals(1,events.size());
        catalog.transition(action("SUSPEND","suspend-1",1));assertEquals(StorageServiceRuntimeBundleVO.State.SUSPENDED,bundle.getState());
        catalog.transition(action("PUBLISH","pub-2",2));assertEquals(StorageServiceRuntimeBundleVO.State.PUBLISHED,bundle.getState());
        assertEquals(3,bundle.getStateRevision());assertEquals(3,events.size());
    }
    @Test(expected=CloudRuntimeException.class) public void unknownReceiptCannotPublish() {catalog.transition(action("PUBLISH","pub-unknown",0));}
    @Test(expected=CloudRuntimeException.class) public void staleRevisionCannotChangeState() {catalog.transition(action("RETIRE","retire-stale",7));}
    @Test public void unusedDraftDeletionKeepsTombstoneAndAudit() {
        catalog.transition(action("DELETE","delete-1",0));
        assertEquals(StorageServiceRuntimeBundleVO.State.DELETED,bundle.getState());assertNotNull(bundle.getRemoved());
        assertEquals("DELETE",events.get(0).getAction());verify(bundleDao,never()).remove(anyLong());
    }
    @Test(expected=CloudRuntimeException.class) public void historicalOperationBlocksDeletion() {
        StorageServiceRuntimeUpgradeVO history=new StorageServiceRuntimeUpgradeVO(10,9,null,"old-operation",2L);
        history.setState(StorageServiceRuntimeUpgradeVO.State.COMPLETE);
        when(upgradeDao.listAll()).thenReturn(List.of(history));
        catalog.transition(action("DELETE","delete-referenced",0));
    }
    @Test public void securityRevokeIsIrreversibleButNotAServiceRestart() {
        catalog.transition(action("REVOKE","revoke-1",0));
        assertEquals(StorageServiceRuntimeBundleVO.State.REVOKED,bundle.getState());verifyNoInteractions(runtime);
        try {catalog.transition(action("PUBLISH","publish-revoked",2));fail();}
        catch(CloudRuntimeException expected){assertEquals(StorageServiceRuntimeBundleVO.State.REVOKED,bundle.getState());}
    }
    @Test public void serviceExportsTheCatalogAndExistingRuntimeCommands() {
        List<Class<?>> commands=new StorageServiceManagerImpl().getCommands();
        for(Class<?> api:List.of(RegisterStorageServiceRuntimeBundleCmd.class,ListStorageServiceRuntimeBundlesCmd.class,
                ValidateStorageServiceRuntimeBundleCmd.class,TransitionStorageServiceRuntimeBundleCmd.class,
                ListStorageServiceRuntimeBundleValidationsCmd.class,ListStorageServiceRuntimeBundleUsagesCmd.class,
                ListStorageServiceRuntimeBundleEventsCmd.class,PreflightStorageServiceRuntimeUpgradeCmd.class,
                UpgradeStorageServiceRuntimeCmd.class,RollbackStorageServiceRuntimeUpgradeCmd.class))assertTrue(api.getName(),commands.contains(api));
    }
    @Test public void inheritedJobIdSerializesInEveryCatalogRecordEnvelope() {
        org.apache.cloudstack.api.response.StorageServiceRuntimeCatalogRecordResponse record=new org.apache.cloudstack.api.response.StorageServiceRuntimeCatalogRecordResponse();
        record.setId("record-uuid");record.setBundleid("bundle-uuid");record.setJobId("original-job-uuid");
        record.setState("VERIFIED");record.setObjectName("storageserviceruntimecatalogrecord");
        record.setResponseName("validatestorageserviceruntimebundleresponse");
        String single=com.cloud.api.response.ApiResponseSerializer.toSerializedString(record,"json");
        com.google.gson.JsonObject value=com.google.gson.JsonParser.parseString(single).getAsJsonObject()
                .getAsJsonObject("validatestorageserviceruntimebundleresponse").getAsJsonObject("storageserviceruntimecatalogrecord");
        assertEquals("original-job-uuid",value.get("jobid").getAsString());
        for(String kind:List.of("validations","usages","events")) {
            org.apache.cloudstack.api.response.ListResponse<org.apache.cloudstack.api.response.StorageServiceRuntimeCatalogRecordResponse> list=new org.apache.cloudstack.api.response.ListResponse<>();
            String root="liststorageserviceruntimebundle"+kind+"response";
            list.setResponseName(root);list.setResponses(List.of(record),1);
            com.google.gson.JsonObject response=com.google.gson.JsonParser.parseString(com.cloud.api.response.ApiResponseSerializer.toSerializedString(list,"json")).getAsJsonObject().getAsJsonObject(root);
            assertEquals(1,response.get("count").getAsInt());
            assertEquals("original-job-uuid",response.getAsJsonArray("storageserviceruntimecatalogrecord").get(0).getAsJsonObject().get("jobid").getAsString());
        }
        for(java.lang.reflect.Field field:org.apache.cloudstack.api.response.StorageServiceRuntimeCatalogRecordResponse.class.getDeclaredFields()) {
            com.google.gson.annotations.SerializedName name=field.getAnnotation(com.google.gson.annotations.SerializedName.class);
            if(name!=null)assertFalse("BaseResponse already owns "+name.value(),List.of("jobid","jobstatus").contains(name.value()));
        }
    }
    @Test public void realApiSerializerRetainsNamedRecordAndLifecycleFields() throws Exception {
        org.apache.cloudstack.api.response.StorageServiceRuntimeBundleResponse response=new org.apache.cloudstack.api.response.StorageServiceRuntimeBundleResponse();
        response.setResponseName("liststorageserviceruntimebundlesresponse");response.setObjectName("storageserviceruntimebundle");
        response.setId("bundle-uuid");response.setState("SUSPENDED");response.setValidationstatus("VERIFIED");response.setRevision(4L);response.setEligible(false);
        String wire=com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json");
        assertTrue(wire.contains("\"validationstatus\":\"VERIFIED\""));assertTrue(wire.contains("\"eligible\":false"));
        assertTrue(wire.contains("\"revision\":4"));assertTrue(wire.contains("\"state\":\"SUSPENDED\""));
        String output=System.getProperty("catalog.wire.fixture");
        if(output!=null) {
            org.apache.cloudstack.api.response.ListResponse<org.apache.cloudstack.api.response.StorageServiceRuntimeBundleResponse> list=new org.apache.cloudstack.api.response.ListResponse<>();
            list.setResponseName("liststorageserviceruntimebundlesresponse");list.setResponses(List.of(response),1);
            org.apache.cloudstack.api.response.StorageServiceRuntimeCatalogRecordResponse record=new org.apache.cloudstack.api.response.StorageServiceRuntimeCatalogRecordResponse();
            record.setId("validation-uuid");record.setBundleid("bundle-uuid");record.setState("VERIFIED");record.setPhase("COMPLETE");
            record.setCompatibility("COMPATIBLE");record.setDetailsjson("{\"signatureVerified\":true,\"workerUid\":65534}");
            record.setObjectName("storageserviceruntimecatalogrecord");
            org.apache.cloudstack.api.response.ListResponse<org.apache.cloudstack.api.response.StorageServiceRuntimeCatalogRecordResponse> records=new org.apache.cloudstack.api.response.ListResponse<>();
            records.setResponseName("liststorageserviceruntimebundlevalidationsresponse");records.setResponses(List.of(record),1);
            com.google.gson.JsonObject fixture=new com.google.gson.JsonObject();
            fixture.add("bundles",com.google.gson.JsonParser.parseString(com.cloud.api.response.ApiResponseSerializer.toSerializedString(list,"json")));
            fixture.add("validations",com.google.gson.JsonParser.parseString(com.cloud.api.response.ApiResponseSerializer.toSerializedString(records,"json")));
            java.nio.file.Path path=java.nio.file.Paths.get(output);assertTrue(path.toAbsolutePath().startsWith("/root/work/epic1438-preparation/"));
            java.nio.file.Files.writeString(path,fixture.toString()+"\n");
        }

    }
}
