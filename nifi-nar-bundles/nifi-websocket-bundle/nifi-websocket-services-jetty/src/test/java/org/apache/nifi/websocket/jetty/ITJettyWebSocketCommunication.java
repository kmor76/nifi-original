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
package org.apache.nifi.websocket.jetty;

import org.apache.nifi.processor.Processor;
import org.apache.nifi.websocket.BinaryMessageConsumer;
import org.apache.nifi.websocket.ConnectedListener;
import org.apache.nifi.websocket.TextMessageConsumer;
import org.apache.nifi.websocket.WebSocketClientService;
import org.apache.nifi.websocket.WebSocketServerService;
import org.apache.nifi.websocket.WebSocketSessionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class ITJettyWebSocketCommunication {

    protected int serverPort;
    protected String serverPath = "/test";
    protected WebSocketServerService serverService;
    protected ControllerServiceTestContext serverServiceContext;
    protected WebSocketClientService clientService;
    protected ControllerServiceTestContext clientServiceContext;

    protected boolean isSecure() {
        return false;
    }

    @BeforeEach
    public void setup() throws Exception {
        setupServer();

        setupClient();
    }

    private void setupServer() throws Exception {
        // Find an open port.
        try (final ServerSocket serverSocket = new ServerSocket(0)) {
            serverPort = serverSocket.getLocalPort();
        }
        serverService = new JettyWebSocketServer();
        serverServiceContext = new ControllerServiceTestContext(serverService, "JettyWebSocketServer1");
        serverServiceContext.setCustomValue(JettyWebSocketServer.LISTEN_PORT, String.valueOf(serverPort));
        serverServiceContext.setCustomValue(JettyWebSocketServer.BASIC_AUTH, "true");
        serverServiceContext.setCustomValue(JettyWebSocketServer.USERS_PROPERTIES_FILE,
                getClass().getResource("/users.properties").getPath());
        serverServiceContext.setCustomValue(JettyWebSocketServer.AUTH_ROLES, "user,test");

        customizeServer();

        serverService.initialize(serverServiceContext.getInitializationContext());
        serverService.startServer(serverServiceContext.getConfigurationContext());
    }

    protected void customizeServer() {
    }

    private void setupClient() throws Exception {
        clientService = new JettyWebSocketClient();
        clientServiceContext = new ControllerServiceTestContext(clientService, "JettyWebSocketClient1");
        clientServiceContext.setCustomValue(JettyWebSocketClient.WS_URI, (isSecure() ? "wss" : "ws") + "://localhost:" + serverPort + serverPath);

        clientServiceContext.setCustomValue(JettyWebSocketClient.USER_NAME, "user2");
        clientServiceContext.setCustomValue(JettyWebSocketClient.USER_PASSWORD, "password2");

        customizeClient();

        clientService.initialize(clientServiceContext.getInitializationContext());
        clientService.startClient(clientServiceContext.getConfigurationContext());
    }

    protected void customizeClient() {
    }

    @AfterEach
    public void teardown() throws Exception {
        clientService.stopClient();
        serverService.stopServer();
    }

    protected interface MockWebSocketProcessor extends Processor, ConnectedListener, TextMessageConsumer, BinaryMessageConsumer {
    }

    private boolean isWindowsEnvironment() {
        return System.getProperty("os.name").toLowerCase().startsWith("windows");
    }

    @Test
    public void testClientServerCommunication() throws Exception {
        // Expectations.
        final CountDownLatch serverIsConnectedByClient = new CountDownLatch(1);
        final CountDownLatch clientConnectedServer = new CountDownLatch(1);
        final CountDownLatch serverReceivedTextMessageFromClient = new CountDownLatch(1);
        final CountDownLatch serverReceivedBinaryMessageFromClient = new CountDownLatch(1);
        final CountDownLatch clientReceivedTextMessageFromServer = new CountDownLatch(1);
        final CountDownLatch clientReceivedBinaryMessageFromServer = new CountDownLatch(1);

        final String textMessageFromClient = "Message from client.";
        final String textMessageFromServer = "Message from server.";

        final MockWebSocketProcessor serverProcessor = mock(MockWebSocketProcessor.class);
        doReturn("serverProcessor1").when(serverProcessor).getIdentifier();
        final AtomicReference<String> serverSessionIdRef = new AtomicReference<>();

        doAnswer(invocation -> assertConnectedEvent(serverIsConnectedByClient, serverSessionIdRef, invocation))
                .when(serverProcessor).connected(any(WebSocketSessionInfo.class));

        doAnswer(invocation -> assertConsumeTextMessage(serverReceivedTextMessageFromClient, textMessageFromClient, invocation))
                .when(serverProcessor).consume(any(WebSocketSessionInfo.class), anyString());

        doAnswer(invocation -> assertConsumeBinaryMessage(serverReceivedBinaryMessageFromClient, textMessageFromClient, invocation))
                .when(serverProcessor).consume(any(WebSocketSessionInfo.class), any(byte[].class), anyInt(), anyInt());

        serverService.registerProcessor(serverPath, serverProcessor);

        final String clientId = "client1";

        final MockWebSocketProcessor clientProcessor = mock(MockWebSocketProcessor.class);
        doReturn("clientProcessor1").when(clientProcessor).getIdentifier();
        final AtomicReference<String> clientSessionIdRef = new AtomicReference<>();


        doAnswer(invocation -> assertConnectedEvent(clientConnectedServer, clientSessionIdRef, invocation))
                .when(clientProcessor).connected(any(WebSocketSessionInfo.class));

        doAnswer(invocation -> assertConsumeTextMessage(clientReceivedTextMessageFromServer, textMessageFromServer, invocation))
                .when(clientProcessor).consume(any(WebSocketSessionInfo.class), anyString());

        doAnswer(invocation -> assertConsumeBinaryMessage(clientReceivedBinaryMessageFromServer, textMessageFromServer, invocation))
                .when(clientProcessor).consume(any(WebSocketSessionInfo.class), any(byte[].class), anyInt(), anyInt());

        clientService.registerProcessor(clientId, clientProcessor);

        clientService.connect(clientId);

        assertTrue(clientConnectedServer.await(5, TimeUnit.SECONDS), "WebSocket client should be able to fire connected event.");
        assertTrue(serverIsConnectedByClient.await(5, TimeUnit.SECONDS), "WebSocket server should be able to fire connected event.");

        clientService.sendMessage(clientId, clientSessionIdRef.get(), sender -> sender.sendString(textMessageFromClient));
        clientService.sendMessage(clientId, clientSessionIdRef.get(), sender -> sender.sendBinary(ByteBuffer.wrap(textMessageFromClient.getBytes())));


        assertTrue(serverReceivedTextMessageFromClient.await(5, TimeUnit.SECONDS), "WebSocket server should be able to consume text message.");
        assertTrue(serverReceivedBinaryMessageFromClient.await(5, TimeUnit.SECONDS), "WebSocket server should be able to consume binary message.");

        serverService.sendMessage(serverPath, serverSessionIdRef.get(), sender -> sender.sendString(textMessageFromServer));
        serverService.sendMessage(serverPath, serverSessionIdRef.get(), sender -> sender.sendBinary(ByteBuffer.wrap(textMessageFromServer.getBytes())));

        assertTrue(clientReceivedTextMessageFromServer.await(5, TimeUnit.SECONDS), "WebSocket client should be able to consume text message.");
        assertTrue(clientReceivedBinaryMessageFromServer.await(5, TimeUnit.SECONDS), "WebSocket client should be able to consume binary message.");

        clientService.deregisterProcessor(clientId, clientProcessor);
        serverService.deregisterProcessor(serverPath, serverProcessor);
    }

    @Test
    public void testClientServerCommunicationRecovery() throws Exception {
        // Expectations.
        final CountDownLatch serverIsConnectedByClient = new CountDownLatch(1);
        final CountDownLatch clientConnectedServer = new CountDownLatch(1);
        final CountDownLatch serverReceivedTextMessageFromClient = new CountDownLatch(1);
        final CountDownLatch serverReceivedBinaryMessageFromClient = new CountDownLatch(1);
        final CountDownLatch clientReceivedTextMessageFromServer = new CountDownLatch(1);
        final CountDownLatch clientReceivedBinaryMessageFromServer = new CountDownLatch(1);

        final String textMessageFromClient = "Message from client.";
        final String textMessageFromServer = "Message from server.";

        final MockWebSocketProcessor serverProcessor = mock(MockWebSocketProcessor.class);
        doReturn("serverProcessor1").when(serverProcessor).getIdentifier();
        final AtomicReference<String> serverSessionIdRef = new AtomicReference<>();

        doAnswer(invocation -> assertConnectedEvent(serverIsConnectedByClient, serverSessionIdRef, invocation))
                .when(serverProcessor).connected(any(WebSocketSessionInfo.class));

        doAnswer(invocation -> assertConsumeTextMessage(serverReceivedTextMessageFromClient, textMessageFromClient, invocation))
                .when(serverProcessor).consume(any(WebSocketSessionInfo.class), anyString());

        doAnswer(invocation -> assertConsumeBinaryMessage(serverReceivedBinaryMessageFromClient, textMessageFromClient, invocation))
                .when(serverProcessor).consume(any(WebSocketSessionInfo.class), any(byte[].class), anyInt(), anyInt());

        serverService.registerProcessor(serverPath, serverProcessor);

        final String clientId = "client1";

        final MockWebSocketProcessor clientProcessor = mock(MockWebSocketProcessor.class);
        doReturn("clientProcessor1").when(clientProcessor).getIdentifier();
        final AtomicReference<String> clientSessionIdRef = new AtomicReference<>();


        doAnswer(invocation -> assertConnectedEvent(clientConnectedServer, clientSessionIdRef, invocation))
                .when(clientProcessor).connected(any(WebSocketSessionInfo.class));

        doAnswer(invocation -> assertConsumeTextMessage(clientReceivedTextMessageFromServer, textMessageFromServer, invocation))
                .when(clientProcessor).consume(any(WebSocketSessionInfo.class), anyString());

        doAnswer(invocation -> assertConsumeBinaryMessage(clientReceivedBinaryMessageFromServer, textMessageFromServer, invocation))
                .when(clientProcessor).consume(any(WebSocketSessionInfo.class), any(byte[].class), anyInt(), anyInt());

        clientService.registerProcessor(clientId, clientProcessor);

        clientService.connect(clientId, Collections.emptyMap());

        assertTrue(clientConnectedServer.await(5, TimeUnit.SECONDS), "WebSocket client should be able to fire connected event.");
        assertTrue(serverIsConnectedByClient.await(5, TimeUnit.SECONDS), "WebSocket server should be able to fire connected event.");

        // Nothing happens if maintenance is executed while sessions are alive.
        ((JettyWebSocketClient) clientService).maintainSessions();

        // Restart server.
        serverService.stopServer();
        serverService.startServer(serverServiceContext.getConfigurationContext());

        // Sessions will be recreated with the same session ids.
        ((JettyWebSocketClient) clientService).maintainSessions();

        clientService.sendMessage(clientId, clientSessionIdRef.get(), sender -> sender.sendString(textMessageFromClient));
        clientService.sendMessage(clientId, clientSessionIdRef.get(), sender -> sender.sendBinary(ByteBuffer.wrap(textMessageFromClient.getBytes())));

        assertTrue(serverReceivedTextMessageFromClient.await(5, TimeUnit.SECONDS), "WebSocket server should be able to consume text message.");
        assertTrue(serverReceivedBinaryMessageFromClient.await(5, TimeUnit.SECONDS), "WebSocket server should be able to consume binary message.");

        serverService.sendMessage(serverPath, serverSessionIdRef.get(), sender -> sender.sendString(textMessageFromServer));
        serverService.sendMessage(serverPath, serverSessionIdRef.get(), sender -> sender.sendBinary(ByteBuffer.wrap(textMessageFromServer.getBytes())));

        assertTrue(clientReceivedTextMessageFromServer.await(5, TimeUnit.SECONDS), "WebSocket client should be able to consume text message.");
        assertTrue(clientReceivedBinaryMessageFromServer.await(5, TimeUnit.SECONDS), "WebSocket client should be able to consume binary message.");

        clientService.deregisterProcessor(clientId, clientProcessor);
        serverService.deregisterProcessor(serverPath, serverProcessor);
    }

    protected Object assertConnectedEvent(CountDownLatch latch, AtomicReference<String> sessionIdRef, InvocationOnMock invocation) {
        final WebSocketSessionInfo sessionInfo = invocation.getArgument(0);
        assertNotNull(sessionInfo.getLocalAddress());
        assertNotNull(sessionInfo.getRemoteAddress());
        assertNotNull(sessionInfo.getSessionId());
        assertEquals(isSecure(), sessionInfo.isSecure());
        sessionIdRef.set(sessionInfo.getSessionId());
        latch.countDown();
        return null;
    }

    protected Object assertConsumeTextMessage(CountDownLatch latch, String expectedMessage, InvocationOnMock invocation) {
        final WebSocketSessionInfo sessionInfo = invocation.getArgument(0);
        assertNotNull(sessionInfo.getLocalAddress());
        assertNotNull(sessionInfo.getRemoteAddress());
        assertNotNull(sessionInfo.getSessionId());
        assertEquals(isSecure(), sessionInfo.isSecure());

        final String receivedMessage = invocation.getArgument(1);
        assertNotNull(receivedMessage);
        assertEquals(expectedMessage, receivedMessage);
        latch.countDown();
        return null;
    }

    protected Object assertConsumeBinaryMessage(CountDownLatch latch, String expectedMessage, InvocationOnMock invocation) {
        final WebSocketSessionInfo sessionInfo = invocation.getArgument(0);
        assertNotNull(sessionInfo.getLocalAddress());
        assertNotNull(sessionInfo.getRemoteAddress());
        assertNotNull(sessionInfo.getSessionId());
        assertEquals(isSecure(), sessionInfo.isSecure());

        final byte[] receivedMessage = invocation.getArgument(1);
        final byte[] expectedBinary = expectedMessage.getBytes();
        final int offset = invocation.getArgument(2);
        final int length = invocation.getArgument(3);
        assertNotNull(receivedMessage);
        assertEquals(expectedBinary.length, receivedMessage.length);
        assertEquals(expectedMessage, new String(receivedMessage));
        assertEquals(0, offset);
        assertEquals(expectedBinary.length, length);
        latch.countDown();
        return null;
    }

}
