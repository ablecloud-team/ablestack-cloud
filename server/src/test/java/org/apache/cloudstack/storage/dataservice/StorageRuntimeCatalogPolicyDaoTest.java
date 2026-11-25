// Licensed under the Apache License, Version 2.0. See NOTICE.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import com.cloud.agent.api.StorageServiceRuntimeOperation;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleValidationDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleEventDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDaoImpl;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** Real catalogue manager -> MySQL DAO/requery -> unchanged native VM policy receiver. */
public class StorageRuntimeCatalogPolicyDaoTest {
    private Field datasource;
    private DataSource previous;
    private TransactionLegacy transaction;
    private StorageServiceRuntimeBundleDaoImpl bundles;
    private StorageServiceRuntimeBundleValidationDaoImpl validations;
    private StorageServiceRuntimeBundleEventDaoImpl events;
    private StorageServiceInstanceDaoImpl instances;
    private StorageServiceInstanceVO instance;
    private StorageServiceRuntimeCatalogManagerImpl catalog;
    private StorageServiceRuntimeUpgradeManagerImpl runtime;
    private final List<Long> owned = new ArrayList<>();
    private Path vm;
    private final AtomicReference<byte[]> verifier = new AtomicReference<>("updater-before".getBytes(StandardCharsets.UTF_8));
    private final byte[] verifierScript = "catalog-verifier".getBytes(StandardCharsets.UTF_8);
    private final byte[] key = ("-----BEGIN PUBLIC KEY-----\n" +
            Base64.getEncoder().encodeToString("synthetic-public-key".getBytes(StandardCharsets.UTF_8)) +
            "\n-----END PUBLIC KEY-----\n").getBytes(StandardCharsets.US_ASCII);
    private final AtomicBoolean failTransport = new AtomicBoolean();
    private JsonObject latestWire;
    private org.mockito.MockedStatic<org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext> jobs;
    private static final Path UPDATER = Path.of("../systemvm/debian/usr/local/lib/ablestack-storage/runtime_updater.py");

    private void caller() {
        Account account = mock(Account.class); when(account.getId()).thenReturn(2L);
        User user = mock(User.class); when(user.getId()).thenReturn(2L);
        CallContext.register(user, account);
    }

    @Before public void open() throws Exception {
        String url = System.getProperty("configuration.dao.test.url");
        Assume.assumeTrue("Dedicated loopback database required", url != null);
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13344/cloud?"));
        MysqlDataSource ds = new MysqlDataSource(); ds.setURL(url); ds.setUser("root");
        datasource = TransactionLegacy.class.getDeclaredField("s_ds"); datasource.setAccessible(true);
        previous = (DataSource) datasource.get(null); datasource.set(null, ds);
        transaction = TransactionLegacy.open(TransactionLegacy.CLOUD_DB);
        caller();
        jobs = mockStatic(org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext.class);
        bundles = new StorageServiceRuntimeBundleDaoImpl();
        validations = new StorageServiceRuntimeBundleValidationDaoImpl();
        events = new StorageServiceRuntimeBundleEventDaoImpl();
        instances = new StorageServiceInstanceDaoImpl();
        instance = instances.persist(new StorageServiceInstanceVO("policy-dao-" + UUID.randomUUID(), null,
                1, 2, 13, 1L, "SYSTEM_VM"));
        vm = Files.createTempDirectory("runtime-policy-dao-");
        runtime = mock(StorageServiceRuntimeUpgradeManagerImpl.class);
        when(runtime.resource(anyString())).thenAnswer(call ->
                call.<String>getArgument(0).endsWith("runtime_updater.py") ? verifier.get() : verifierScript);
        catalog = spy(new StorageServiceRuntimeCatalogManagerImpl());
        catalog.bundleDao = bundles; catalog.validationDao = validations; catalog.eventDao = events;
        catalog.upgradeDao = mock(StorageServiceRuntimeUpgradeDao.class);
        catalog.instanceDao = instances; catalog.runtime = runtime;
        doReturn(key).when(catalog).trustedKey("test-key");
        when(runtime.invoke(any(), eq(StorageServiceRuntimeOperation.SYNC_CATALOG_POLICY), anyString(), any()))
                .thenAnswer(call -> {
                    if (failTransport.get()) throw new CloudRuntimeException("TEST_TRANSPORT_UNAVAILABLE");
                    JsonObject wire = call.getArgument(3);
                    latestWire = wire.deepCopy();
                    // This re-query is a separate read of committed DAO state at the transport boundary.
                    for (var item : wire.getAsJsonArray("bundles")) {
                        JsonObject entry = item.getAsJsonObject();
                        StorageServiceRuntimeBundleVO stored = bundles.findByUuid(entry.get("bundleId").getAsString());
                        assertEquals(stored.getStateRevision(), entry.get("revision").getAsLong());
                        assertEquals(stored.getValidationStatus() == StorageServiceRuntimeBundleVO.ValidationStatus.VERIFIED ?
                                "VERIFIED" : "UNKNOWN", entry.get("validation").getAsString());
                    }
                    return receiver(wire, true);
                });
    }

    @After public void close() throws Exception {
        try {
            if (events != null) {
                for (var row : events.listAll()) if (owned.contains(row.getBundleId())) events.expunge(row.getId());
                for (var row : validations.listAll()) if (owned.contains(row.getBundleId())) validations.expunge(row.getId());
                for (Long id : owned) bundles.expunge(id);
                if (instance != null) instances.expunge(instance.getId());
            }
        } finally {
            CallContext.unregister();
            if (jobs != null) jobs.close();
            if (transaction != null) transaction.close();
            if (datasource != null) datasource.set(null, previous);
            if (vm != null) {
                try (var paths = Files.walk(vm)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(path);
                }
            }
        }
    }

    private String verifierDigest() {
        return StorageServiceRuntimeCatalogManagerImpl.digest((
                StorageServiceRuntimeCatalogManagerImpl.digest(verifier.get()) + ":" +
                StorageServiceRuntimeCatalogManagerImpl.digest(verifierScript)).getBytes(StandardCharsets.US_ASCII));
    }

    private StorageServiceRuntimeBundleVO fixture(StorageServiceRuntimeBundleVO.State state, long revision) {
        StorageServiceRuntimeBundleVO b = new StorageServiceRuntimeBundleVO("policy-" + UUID.randomUUID(),
                "1", "1", StorageServiceRuntimeBundleVO.ServiceImpact.NONE, "http://127.0.0.1/b",
                "http://127.0.0.1/m", "http://127.0.0.1/s", 100L, "a".repeat(64), "b".repeat(64), "test-key");
        b.setState(state); b.setStateRevision(revision);
        b.setValidationStatus(StorageServiceRuntimeBundleVO.ValidationStatus.VERIFIED);
        b = bundles.persist(b); owned.add(b.getId());
        StorageServiceRuntimeBundleValidationVO receipt = receipt(b, revision);
        b.setCurrentValidationId(receipt.getId()); bundles.update(b.getId(), b);
        assertTrue(catalog.verified(bundles.findById(b.getId())));
        if (instance.getCurrentRuntimeBundleId() == null) {
            instance.setCurrentRuntimeBundleId(b.getId()); instances.update(instance.getId(), instance);
        }
        return bundles.findById(b.getId());
    }

    private StorageServiceRuntimeBundleValidationVO receipt(StorageServiceRuntimeBundleVO b, long revision) {
        StorageServiceRuntimeBundleValidationVO v = new StorageServiceRuntimeBundleValidationVO();
        v.setBundleId(b.getId()); v.setRequestId(UUID.randomUUID().toString()); v.setObservedRevision(revision);
        v.setState("VERIFIED"); v.setPhase("COMPLETE"); v.setCompatibility("COMPATIBLE");
        v.setProfileId(StorageServiceRuntimeCatalogManagerImpl.PROFILE);
        v.setMetadataDigest(StorageServiceRuntimeCatalogManagerImpl.digest((b.getVersion() + "|" + b.getSha256() +
                "|" + b.getManifestSha256() + "|" + b.getSigningKeyId() + "|" + b.getRuntimeAbiVersion() + "|" +
                b.getDesiredStateSchemaVersion() + "|" + b.getServiceImpact() + "|" + b.getArtifactSize())
                .getBytes(StandardCharsets.UTF_8)));
        v.setTrustFingerprint(StorageServiceRuntimeCatalogManagerImpl.digest("synthetic-public-key".getBytes(StandardCharsets.UTF_8)));
        v.setVerifierDigest(verifierDigest());
        return validations.persist(v);
    }

    private ProcessBuilder process(String... command) {
        ProcessBuilder p = new ProcessBuilder(command);
        p.environment().put("ABLESTACK_STORAGE_RUNTIME_ROOT", vm.toString());
        p.environment().put("ABLESTACK_STORAGE_RUNTIME_STATE_ROOT", vm.resolve("state").toString());
        p.environment().put("ABLESTACK_STORAGE_RUNTIME_LOCK", vm.resolve("lock").toString());
        return p;
    }

    private JsonObject receiver(JsonObject wire, boolean success) throws Exception {
        Process p = process("python3", "-I", UPDATER.toAbsolutePath().normalize().toString(), "sync_catalog_policy").start();
        try (var input = p.getOutputStream()) { input.write(wire.toString().getBytes(StandardCharsets.UTF_8)); }
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        JsonObject result = JsonParser.parseString(output).getAsJsonObject();
        assertEquals(error + output, success, p.exitValue() == 0);
        return result;
    }

    private JsonObject entry(StorageServiceRuntimeBundleVO b) throws Exception {
        return JsonParser.parseString(Files.readString(vm.resolve("catalog-policy.json"))).getAsJsonObject()
                .getAsJsonObject("bundles").getAsJsonObject(b.getVersion());
    }

    private void changedVerifier() { verifier.set("updater-after".getBytes(StandardCharsets.UTF_8)); }

    @Test public void invalidatedReceiptIsDurableRevisionedAndAcceptedByNativeVm() throws Exception {
        var b = fixture(StorageServiceRuntimeBundleVO.State.PUBLISHED, 3);
        Long originalReceipt = b.getCurrentValidationId();
        catalog.syncPolicy(instance);
        JsonObject stale = latestWire.deepCopy();
        changedVerifier();
        catalog.syncPolicy(instance);
        var stored = bundles.findById(b.getId());
        assertEquals(StorageServiceRuntimeBundleVO.ValidationStatus.UNKNOWN, stored.getValidationStatus());
        assertEquals(4, stored.getStateRevision());
        assertEquals(originalReceipt, stored.getCurrentValidationId());
        assertEquals("VERIFIED", validations.findById(originalReceipt).getState());
        assertEquals("UNKNOWN", entry(b).get("validation").getAsString());
        assertEquals(4, entry(b).get("revision").getAsLong());
        assertEquals("CATALOG_POLICY_STALE", receiver(stale, false).get("errorCode").getAsString());
        JsonObject sameRevision = latestWire.deepCopy();
        sameRevision.getAsJsonArray("bundles").get(0).getAsJsonObject().addProperty("validation", "VERIFIED");
        assertEquals("CATALOG_POLICY_STALE", receiver(sameRevision, false).get("errorCode").getAsString());
        catalog.syncPolicy(instance);
        assertEquals(4, bundles.findById(b.getId()).getStateRevision());
        assertEquals(1, events.listAll().stream().filter(e -> e.getBundleId() == b.getId()).count());
        assertEquals("INVALIDATE_VALIDATION", events.listAll().stream().filter(e -> e.getBundleId() == b.getId()).findFirst().get().getAction());
    }

    @Test public void unselectedInvalidatedBundlesDoNotBlockCurrentAndRevocationFenceRemains() throws Exception {
        var current = fixture(StorageServiceRuntimeBundleVO.State.PUBLISHED, 5);
        var unrelated = fixture(StorageServiceRuntimeBundleVO.State.PUBLISHED, 3);
        var revoked = fixture(StorageServiceRuntimeBundleVO.State.REVOKED, 6);
        catalog.syncPolicy(instance);
        changedVerifier();
        var fresh = receipt(current, 7);
        current.setCurrentValidationId(fresh.getId()); current.setStateRevision(7);
        bundles.update(current.getId(), current);
        catalog.syncPolicy(instance);
        assertEquals("VERIFIED", entry(current).get("validation").getAsString());
        assertEquals(7, entry(current).get("revision").getAsLong());
        assertEquals("UNKNOWN", entry(unrelated).get("validation").getAsString());
        assertEquals(4, entry(unrelated).get("revision").getAsLong());
        assertEquals("REVOKED", entry(revoked).get("state").getAsString());
        assertEquals(7, entry(revoked).get("revision").getAsLong());
        String script = "import importlib.util,json,sys\n" +
                "s=importlib.util.spec_from_file_location('u',sys.argv[1]);u=importlib.util.module_from_spec(s);s.loader.exec_module(u)\n" +
                "try:u.RuntimeUpdater().require_catalog_destination(sys.argv[2],'rollback');raise AssertionError('revocation bypassed')\n" +
                "except u.RuntimeUpgradeError as e:print(json.dumps({'code':e.code}))\n";
        Process p = process("python3", "-I", "-c", script, UPDATER.toAbsolutePath().normalize().toString(), revoked.getVersion()).start();
        JsonObject result = JsonParser.parseString(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, p.exitValue());
        assertEquals("CATALOG_DESTINATION_BLOCKED", result.get("code").getAsString());
    }

    @Test public void transportFailureKeepsCommittedInvalidationAndRetryDoesNotIncrementAgain() throws Exception {
        var b = fixture(StorageServiceRuntimeBundleVO.State.PUBLISHED, 3);
        catalog.syncPolicy(instance); changedVerifier(); failTransport.set(true);
        assertThrows(CloudRuntimeException.class, () -> catalog.syncPolicy(instance));
        assertEquals(4, bundles.findById(b.getId()).getStateRevision());
        assertEquals(StorageServiceRuntimeBundleVO.ValidationStatus.UNKNOWN, bundles.findById(b.getId()).getValidationStatus());
        failTransport.set(false); catalog.syncPolicy(instance);
        assertEquals(4, entry(b).get("revision").getAsLong());
    }

    @Test public void runningValidationAndFreshConcurrentReceiptAreNotOverwritten() {
        var b = fixture(StorageServiceRuntimeBundleVO.State.PUBLISHED, 3);
        b.setValidationStatus(StorageServiceRuntimeBundleVO.ValidationStatus.RUNNING);
        b.setStateRevision(4); bundles.update(b.getId(), b); changedVerifier();
        assertEquals(4, catalog.policyEntry(b.getId()).get("revision").getAsLong());
        assertEquals(StorageServiceRuntimeBundleVO.ValidationStatus.RUNNING, bundles.findById(b.getId()).getValidationStatus());
        var fresh = receipt(b, 5);
        b.setCurrentValidationId(fresh.getId()); b.setValidationStatus(StorageServiceRuntimeBundleVO.ValidationStatus.VERIFIED);
        b.setStateRevision(5); bundles.update(b.getId(), b);
        JsonObject policy = catalog.policyEntry(b.getId());
        assertEquals("VERIFIED", policy.get("validation").getAsString());
        assertEquals(5, policy.get("revision").getAsLong());
        assertEquals(fresh.getId(), bundles.findById(b.getId()).getCurrentValidationId());
    }

    @Test public void concurrentBuildersInvalidateOnceWithOneAuditRecord() throws Exception {
        var b = fixture(StorageServiceRuntimeBundleVO.State.PUBLISHED, 3); changedVerifier();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<JsonObject>> futures = new ArrayList<>();
            for (int n = 0; n < 2; n++) futures.add(pool.submit(() -> {
                caller();
                try (var workerJobs = mockStatic(org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext.class);
                     TransactionLegacy ignored = TransactionLegacy.open(TransactionLegacy.CLOUD_DB)) {
                    start.await();
                    return catalog.policyEntry(b.getId());
                } finally { CallContext.unregister(); }
            }));
            start.countDown();
            for (var future : futures) assertEquals(4, future.get(20, TimeUnit.SECONDS).get("revision").getAsLong());
        } finally { pool.shutdownNow(); }
        assertEquals(4, bundles.findById(b.getId()).getStateRevision());
        assertEquals(1, events.listAll().stream().filter(e -> e.getBundleId() == b.getId()).count());
    }
}
