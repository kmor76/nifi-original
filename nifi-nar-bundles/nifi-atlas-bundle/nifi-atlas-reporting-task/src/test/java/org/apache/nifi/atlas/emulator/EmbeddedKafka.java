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
package org.apache.nifi.atlas.emulator;

import kafka.server.KafkaConfig;
import kafka.server.KafkaServerStartable;
import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Properties;

/**
 * Embedded Kafka server, primarily to be used for testing.
 */
public class EmbeddedKafka {

    private final KafkaServerStartable kafkaServer;

    private final Properties zookeeperConfig;

    private final Properties kafkaConfig;

    private final ZooKeeperServer zkServer;

    private final Logger logger = LoggerFactory.getLogger(EmbeddedKafka.class);

    private boolean started;

    /**
     * Will create instance of the embedded Kafka server. Kafka and Zookeeper
     * configuration properties will be loaded from 'server.properties' and
     * 'zookeeper.properties' located at the root of the classpath.
     */
    public EmbeddedKafka() {
        this(loadPropertiesFromClasspath("/server.properties"), loadPropertiesFromClasspath("/zookeeper.properties"));
    }

    /**
     * Will create instance of the embedded Kafka server.
     *
     * @param kafkaConfig
     *            Kafka configuration properties
     * @param zookeeperConfig
     *            Zookeeper configuration properties
     */
    public EmbeddedKafka(Properties kafkaConfig, Properties zookeeperConfig) {
        this.cleanupKafkaWorkDir();

        this.kafkaConfig = kafkaConfig;
        this.zookeeperConfig = zookeeperConfig;

        this.kafkaServer = new KafkaServerStartable(new KafkaConfig(kafkaConfig));
        this.zkServer = new ZooKeeperServer();
    }

    /**
     * Will start embedded Kafka server. Its data directories will be created
     * at 'kafka-tmp' directory relative to the working directory of the current
     * runtime. The data directories will be deleted upon JVM exit.
     *
     */
    public void start() {
        if (!this.started) {
            logger.info("Starting Zookeeper server");
            this.startZookeeper();

            logger.info("Starting Kafka server");
            this.kafkaServer.startup();

            logger.info("Embedded Kafka is started at localhost:" + this.kafkaServer.staticServerConfig().port()
                    + ". Zookeeper connection string: " + this.kafkaConfig.getProperty("zookeeper.connect"));
            this.started = true;
        }
    }

    /**
     * Will stop embedded Kafka server, cleaning up all working directories.
     */
    public void stop() {
        if (this.started) {
            logger.info("Shutting down Kafka server");
            this.kafkaServer.shutdown();
            this.kafkaServer.awaitShutdown();
            logger.info("Shutting down Zookeeper server");
            this.shutdownZookeeper();
            logger.info("Embedded Kafka is shut down.");
            this.cleanupKafkaWorkDir();
            this.started = false;
        }
    }

    /**
     *
     */
    private void cleanupKafkaWorkDir() {
        File kafkaTmp = new File("target/kafka-tmp");
        try {
            FileUtils.deleteDirectory(kafkaTmp);
        } catch (Exception e) {
            logger.warn("Failed to delete " + kafkaTmp.getAbsolutePath());
        }
    }

    /**
     * Will start Zookeeper server via {@link ServerCnxnFactory}
     */
    private void startZookeeper() {
        QuorumPeerConfig quorumConfiguration = new QuorumPeerConfig();
        try {
            quorumConfiguration.parseProperties(this.zookeeperConfig);

            ServerConfig configuration = new ServerConfig();
            configuration.readFrom(quorumConfiguration);

            FileTxnSnapLog txnLog = new FileTxnSnapLog(configuration.getDataLogDir(), configuration.getDataDir());

            zkServer.setTxnLogFactory(txnLog);
            zkServer.setTickTime(configuration.getTickTime());
            zkServer.setMinSessionTimeout(configuration.getMinSessionTimeout());
            zkServer.setMaxSessionTimeout(configuration.getMaxSessionTimeout());
            ServerCnxnFactory zookeeperConnectionFactory = ServerCnxnFactory.createFactory();
            zookeeperConnectionFactory.configure(configuration.getClientPortAddress(),
                    configuration.getMaxClientCnxns());
            zookeeperConnectionFactory.startup(zkServer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start Zookeeper server", e);
        }
    }

    /**
     * Will shut down Zookeeper server.
     */
    private void shutdownZookeeper() {
        zkServer.shutdown();
    }

    /**
     * Will load {@link Properties} from properties file discovered at the
     * provided path relative to the root of the classpath.
     */
    private static Properties loadPropertiesFromClasspath(String path) {
        try {
            Properties kafkaProperties = new Properties();
            kafkaProperties.load(Class.class.getResourceAsStream(path));
            return kafkaProperties;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
