/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.minifi.c2.integration.test;

import com.palantir.docker.compose.DockerComposeExtension;
import org.apache.nifi.minifi.c2.integration.test.health.HttpsStatusCodeHealthCheck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class DelegatingConfigurationProviderSecureTest extends AbstractTestSecure {
    public static final String C2_UPSTREAM_URL = "https://c2:10443/c2/config";
    private static SSLSocketFactory healthCheckSocketFactory;
    private static Path certificatesDirectory;
    private static SSLContext trustSslContext;

    // Not annotated as rule because we need to generate certificatesDirectory first
    public static DockerComposeExtension docker = DockerComposeExtension.builder()
            .file("target/test-classes/docker-compose-DelegatingProviderSecureTest.yml")
            .waitingForServices(Arrays.asList("squid", "c2-upstream"),
                    new HttpsStatusCodeHealthCheck(container -> C2_UPSTREAM_URL, containers -> containers.get(0), containers -> containers.get(1), () -> healthCheckSocketFactory, 403))
            .waitingForServices(Arrays.asList("squid", "c2"),
                    new HttpsStatusCodeHealthCheck(container -> C2_URL, containers -> containers.get(0), containers -> containers.get(1), () -> healthCheckSocketFactory, 403))
            .build();

    public DelegatingConfigurationProviderSecureTest() {
        super(docker, certificatesDirectory, trustSslContext);
    }

    /**
     * Generates certificates with the tls-toolkit and then starts up the docker compose file
     */
    @BeforeAll
    public static void initCertificates() throws Exception {
        certificatesDirectory = Paths.get(DelegatingConfigurationProviderSecureTest.class.getClassLoader()
                .getResource("docker-compose-DelegatingProviderSecureTest.yml").getFile()).getParent().toAbsolutePath().resolve("certificates-DelegatingConfigurationProviderSecureTest");
        trustSslContext = initCertificates(certificatesDirectory, Arrays.asList("c2", "c2-upstream"));
        healthCheckSocketFactory = trustSslContext.getSocketFactory();

        docker.before();
    }

    @AfterAll
    public static void cleanup() {
        docker.after();
    }

    @BeforeEach
    public void setup() {
        super.setup(docker);
    }

    @Test
    public void testUpstreamPermissionDenied() throws Exception {
        assertReturnCode("?class=raspi4", loadSslContext("user3"), 403);
    }
}
