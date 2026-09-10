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

import com.sun.net.httpserver.HttpServer;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;

/** Checks the assembled management classpath against a loopback HTTP fixture. */
public class ManagementRuntimeSmoke {
    public static void main(String[] args) throws Exception {
        // Reflection prevents javac from inlining the version from its own classpath.
        Object version = Class.forName("okhttp3.OkHttp").getField("VERSION").get(null);
        if (!"5.1.0".equals(version)) {
            throw new AssertionError("Expected packaged OkHttp 5.1.0, found " + version);
        }
        Security.addProvider(new BouncyCastleProvider());
        var generator = KeyPairGenerator.getInstance("RSA", "BC");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var name = new X500Name("CN=S2 packaging verification");
        var builder = new JcaX509v3CertificateBuilder(name, BigInteger.ONE, new Date(0),
                new Date(System.currentTimeMillis() + 60000), name, pair.getPublic());
        var holder = builder.build(new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(pair.getPrivate()));
        new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
                .verify(pair.getPublic(), "BC");
        System.out.println("PASS packaged BC: RSA and X.509 signing/verification");

        AtomicInteger lists = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        AtomicInteger pings = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if ("/ping".equals(path)) {
                    pings.incrementAndGet();
                    exchange.getResponseHeaders().add("X-Influxdb-Version", "1.8.10");
                    exchange.sendResponseHeaders(204, -1);
                } else if ("/write".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                    if (requestBody.contains("s2_probe")) {
                        writes.incrementAndGet();
                    }
                    exchange.sendResponseHeaders(204, -1);
                } else if ("PUT".equals(exchange.getRequestMethod()) && "/s2-bucket/probe".equals(path)) {
                    if (requestBody.equals("s2-probe")) {
                        uploads.incrementAndGet();
                    }
                    exchange.getResponseHeaders().add("ETag", "\"s2fixture\"");
                    exchange.sendResponseHeaders(200, -1);
                } else if ("GET".equals(exchange.getRequestMethod()) && "/".equals(path)) {
                    lists.incrementAndGet();
                    byte[] response = ("<?xml version=\"1.0\"?><ListAllMyBucketsResult "
                            + "xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                            + "<Owner><ID>s2</ID><DisplayName>s2</DisplayName></Owner>"
                            + "<Buckets/></ListAllMyBucketsResult>").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/xml");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } else {
                    exchange.sendResponseHeaders(400, -1);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            try (var minio = MinioClient.builder().endpoint(endpoint).region("us-east-1")
                    .credentials("s2fixture", "s2fixturepassword").build()) {
                if (!minio.listBuckets().isEmpty()) {
                    throw new AssertionError("Unexpected bucket list");
                }
                byte[] payload = "s2-probe".getBytes(StandardCharsets.UTF_8);
                minio.putObject(PutObjectArgs.builder().bucket("s2-bucket").object("probe")
                        .stream(new ByteArrayInputStream(payload), payload.length, -1).build());
            }
            try (var influx = InfluxDBFactory.connect(endpoint)) {
                if (!influx.ping().isGood()) {
                    throw new AssertionError("InfluxDB ping failed");
                }
                influx.setDatabase("s2");
                influx.write(Point.measurement("s2_probe").addField("value", 1).build());
            }
            if (lists.get() != 1 || uploads.get() != 1 || pings.get() != 1 || writes.get() != 1) {
                throw new AssertionError("Expected MinIO list/upload and InfluxDB ping/write requests");
            }
            System.out.println("PASS packaged OkHttp " + version + ": MinIO list/upload and InfluxDB ping/write");
        } finally {
            server.stop(0);
        }
    }
}
