//
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
//
package org.apache.cloudstack.oauth2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.apache.cloudstack.oauth2.vo.OauthProviderVO;
import org.junit.Before;
import org.junit.Test;

import com.cloud.exception.CloudAuthenticationException;

public class OAuth2FlowCacheTest {
    private OAuth2FlowCache cache;
    private OauthProviderVO provider;
    private final Supplier<String> spentCode = () -> { throw new CloudAuthenticationException("Code already used"); };

    @Before
    public void setUp() {
        cache = new OAuth2FlowCache();
        provider = new OauthProviderVO();
        provider.setEnabled(true);
        provider.setClientId("fixture-client");
        provider.setSecretKey("fixture-secret");
        provider.setProvider("fixture");
    }

    @Test
    public void scopesAndCodesCannotShareDiscovery() {
        cache.discover(provider, 2L, "code-a", () -> "a@example.com");
        assertThrows(CloudAuthenticationException.class, () -> cache.consume(provider, 3L, "code-a", spentCode));
        assertThrows(CloudAuthenticationException.class, () -> cache.consume(provider, 2L, "code-b", spentCode));
        assertEquals("a@example.com", cache.consume(provider, 2L, "code-a", spentCode));
        assertThrows(CloudAuthenticationException.class, () -> cache.consume(provider, 2L, "code-a", spentCode));
    }

    @Test
    public void providerEditInvalidatesPriorDiscovery() {
        cache.discover(provider, 2L, "code", () -> "a@example.com");
        provider.setSecretKey("rotated-secret");
        assertThrows(CloudAuthenticationException.class, () -> cache.consume(provider, 2L, "code", spentCode));
    }

    @Test
    public void rootDomainAndGlobalUseSameScope() {
        cache.discover(provider, null, "code", () -> "a@example.com");
        assertEquals("a@example.com", cache.consume(provider, 1L, "code", spentCode));
    }

    @Test
    public void expiresWithoutExtendingOnRead() {
        AtomicLong time = new AtomicLong();
        cache = new OAuth2FlowCache(time::get);
        cache.discover(provider, 2L, "code", () -> "a@example.com");
        time.set(TimeUnit.SECONDS.toNanos(59));
        assertEquals("a@example.com", cache.discover(provider, 2L, "code", spentCode));
        time.set(TimeUnit.SECONDS.toNanos(61));
        assertThrows(CloudAuthenticationException.class, () -> cache.consume(provider, 2L, "code", spentCode));
    }

    @Test
    public void concurrentLoginCanConsumeDiscoveryOnlyOnce() throws Exception {
        cache.discover(provider, 2L, "code", () -> "a@example.com");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        cache.consume(provider, 2L, "code", spentCode);
                        accepted.incrementAndGet();
                    } catch (CloudAuthenticationException expected) {
                        // A provider rejects any second exchange of the same authorization code.
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) { future.get(5, TimeUnit.SECONDS); }
            assertEquals(1, accepted.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void directLoginAndMissingEmailDoNotPopulateCache() {
        assertEquals("a@example.com", cache.consume(provider, 2L, "code", () -> "a@example.com"));
        assertThrows(CloudAuthenticationException.class, () -> cache.consume(provider, 2L, "code", spentCode));
        assertThrows(CloudAuthenticationException.class, () -> cache.discover(provider, 2L, "empty", () -> null));
    }
}
