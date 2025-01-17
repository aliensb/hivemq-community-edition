/*
 * Copyright 2019-present HiveMQ GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hivemq.extensions.handler;

import com.hivemq.bootstrap.ClientConnection;
import com.hivemq.common.shutdown.ShutdownHooks;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.extension.sdk.api.interceptor.pingreq.PingReqInboundInterceptor;
import com.hivemq.extension.sdk.api.interceptor.pingreq.parameter.PingReqInboundInput;
import com.hivemq.extension.sdk.api.interceptor.pingreq.parameter.PingReqInboundOutput;
import com.hivemq.extension.sdk.api.interceptor.pingresp.PingRespOutboundInterceptor;
import com.hivemq.extension.sdk.api.interceptor.pingresp.parameter.PingRespOutboundInput;
import com.hivemq.extension.sdk.api.interceptor.pingresp.parameter.PingRespOutboundOutput;
import com.hivemq.extensions.HiveMQExtension;
import com.hivemq.extensions.HiveMQExtensions;
import com.hivemq.extensions.classloader.IsolatedExtensionClassloader;
import com.hivemq.extensions.client.ClientContextImpl;
import com.hivemq.extensions.executor.PluginOutPutAsyncer;
import com.hivemq.extensions.executor.PluginOutputAsyncerImpl;
import com.hivemq.extensions.executor.PluginTaskExecutorService;
import com.hivemq.extensions.executor.PluginTaskExecutorServiceImpl;
import com.hivemq.extensions.executor.task.PluginTaskExecutor;
import com.hivemq.extensions.packets.general.ModifiableDefaultPermissionsImpl;
import com.hivemq.mqtt.message.PINGREQ;
import com.hivemq.mqtt.message.PINGRESP;
import com.hivemq.mqtt.message.ProtocolVersion;
import com.hivemq.util.ChannelAttributes;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Robin Atherton
 */
public class PingInterceptorHandlerTest {

    public static @NotNull AtomicBoolean isTriggered = new AtomicBoolean();
    @Rule
    public @NotNull TemporaryFolder temporaryFolder = new TemporaryFolder();
    private @NotNull PluginTaskExecutor executor1;
    private @NotNull EmbeddedChannel channel;
    @Mock
    private @NotNull PluginOutPutAsyncer asyncer;

    @Mock
    private @NotNull HiveMQExtension plugin;

    @Mock
    private @NotNull HiveMQExtensions hiveMQExtensions;

    @Mock
    private @NotNull PluginTaskExecutorService pluginTaskExecutorService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        isTriggered.set(false);
        executor1 = new PluginTaskExecutor(new AtomicLong());
        executor1.postConstruct();

        channel = new EmbeddedChannel();
        channel.attr(ChannelAttributes.CLIENT_CONNECTION).set(new ClientConnection(channel, null));
        channel.attr(ChannelAttributes.CLIENT_CONNECTION).get().setClientId("client");
        channel.attr(ChannelAttributes.CLIENT_CONNECTION).get().setRequestResponseInformation(true);
        when(plugin.getId()).thenReturn("plugin");

        asyncer = new PluginOutputAsyncerImpl(Mockito.mock(ShutdownHooks.class));
        pluginTaskExecutorService = new PluginTaskExecutorServiceImpl(() -> executor1, mock(ShutdownHooks.class));

        final PingInterceptorHandler handler =
                new PingInterceptorHandler(pluginTaskExecutorService, asyncer, hiveMQExtensions);
        channel.pipeline().addLast("test", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(@NotNull ChannelHandlerContext ctx, @NotNull Object msg, @NotNull ChannelPromise promise) throws Exception {
                handler.handleOutboundPingResp(ctx, ((PINGRESP) msg), promise);
            }
        });
        channel.pipeline().addLast("test2", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                handler.handleInboundPingReq(ctx, ((PINGREQ) msg));
            }
        });

    }

    @After
    public void tearDown() {
        executor1.stop();
        channel.close();
    }

    @Test(timeout = 5000, expected = ClosedChannelException.class)
    public void test_pingreq_channel_closed() {
        channel.close();
        channel.writeInbound(new PINGREQ());
    }

    @Test(timeout = 5000, expected = ClosedChannelException.class)
    public void test_pingresp_channel_closed() {
        channel.close();
        channel.writeOutbound(new PINGRESP());
    }

    @Test(timeout = 5000)
    public void test_read_simple_pingreq() throws Exception {
        final ClientContextImpl clientContext
                = new ClientContextImpl(hiveMQExtensions, new ModifiableDefaultPermissionsImpl());

        final PingReqInboundInterceptor interceptor = getIsolatedInboundInterceptor("SimplePingReqTestInterceptor");
        clientContext.addPingReqInboundInterceptor(interceptor);

        channel.attr(ChannelAttributes.CLIENT_CONNECTION).get().setExtensionClientContext(clientContext);
        channel.attr(ChannelAttributes.CLIENT_CONNECTION).get().setProtocolVersion(ProtocolVersion.MQTTv3_1);
        when(hiveMQExtensions.getExtensionForClassloader(any(IsolatedExtensionClassloader.class))).thenReturn(plugin);

        channel.writeInbound(new PINGREQ());
        PINGREQ pingreq = channel.readInbound();
        while (pingreq == null) {
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            pingreq = channel.readInbound();
        }
        Assert.assertTrue(isTriggered.get());
        Assert.assertNotNull(pingreq);
        isTriggered.set(false);

    }

    @Test(timeout = 5000)
    public void test_read_advanced_pingreq() throws Exception {
        final ClientContextImpl clientContext
                = new ClientContextImpl(hiveMQExtensions, new ModifiableDefaultPermissionsImpl());

        final PingReqInboundInterceptor interceptor =
                getIsolatedInboundInterceptor("AdvancedPingReqTestInterceptor");
        clientContext.addPingReqInboundInterceptor(interceptor);

        channel.attr(ChannelAttributes.CLIENT_CONNECTION).get().setExtensionClientContext(clientContext);
        channel.attr(ChannelAttributes.CLIENT_CONNECTION).get().setProtocolVersion(ProtocolVersion.MQTTv3_1);
        when(hiveMQExtensions.getExtensionForClassloader(any(IsolatedExtensionClassloader.class))).thenReturn(plugin);

        channel.writeInbound(new PINGREQ());
        PINGREQ pingreq = channel.readInbound();
        while (pingreq == null) {
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            pingreq = channel.readInbound();
        }
        Assert.assertTrue(isTriggered.get());
        Assert.assertNotNull(pingreq);
        isTriggered.set(false);
    }

    @Test(timeout = 5000)
    public void test_read_simple_pingresp() throws Exception {
        final ClientContextImpl clientContext
                = new ClientContextImpl(hiveMQExtensions, new ModifiableDefaultPermissionsImpl());

        final PingRespOutboundInterceptor interceptor =
                getIsolatedOutboundInterceptor("SimplePingRespTestInterceptor");
        clientContext.addPingRespOutboundInterceptor(interceptor);

        channel.attr(ChannelAttributes.CLIENT_CONNECTION).get().setExtensionClientContext(clientContext);
        channel.attr(ChannelAttributes.CLIENT_CONNECTION).get().setProtocolVersion(ProtocolVersion.MQTTv3_1);
        when(hiveMQExtensions.getExtensionForClassloader(any(IsolatedExtensionClassloader.class))).thenReturn(plugin);

        channel.writeOutbound(new PINGRESP());
        PINGRESP pingresp = channel.readOutbound();
        while (pingresp == null) {
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            pingresp = channel.readOutbound();
        }
        Assert.assertTrue(isTriggered.get());
        Assert.assertNotNull(pingresp);
        isTriggered.set(false);
    }

    @Test(timeout = 40000)
    public void test_read_advanced_pingresp() throws Exception {
        final ClientContextImpl clientContext
                = new ClientContextImpl(hiveMQExtensions, new ModifiableDefaultPermissionsImpl());

        final PingRespOutboundInterceptor interceptor =
                getIsolatedOutboundInterceptor("AdvancedPingRespTestInterceptor");
        clientContext.addPingRespOutboundInterceptor(interceptor);

        channel.attr(ChannelAttributes.CLIENT_CONNECTION).get().setExtensionClientContext(clientContext);
        channel.attr(ChannelAttributes.CLIENT_CONNECTION).get().setProtocolVersion(ProtocolVersion.MQTTv3_1);
        when(hiveMQExtensions.getExtensionForClassloader(any(IsolatedExtensionClassloader.class))).thenReturn(plugin);

        channel.writeOutbound(new PINGRESP());
        PINGRESP pingresp = channel.readOutbound();
        while (pingresp == null) {
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            pingresp = channel.readOutbound();
        }
        Assert.assertTrue(isTriggered.get());
        Assert.assertNotNull(pingresp);
        isTriggered.set(false);
    }


    private @NotNull PingReqInboundInterceptor getIsolatedInboundInterceptor(final @NotNull String name) throws Exception {
        final JavaArchive javaArchive = ShrinkWrap.create(JavaArchive.class)
                .addClass("com.hivemq.extensions.handler.PingInterceptorHandlerTest$" + name);

        final File jarFile = temporaryFolder.newFile();
        javaArchive.as(ZipExporter.class).exportTo(jarFile, true);

        final IsolatedExtensionClassloader
                cl =
                new IsolatedExtensionClassloader(new URL[]{jarFile.toURI().toURL()}, this.getClass().getClassLoader());

        final Class<?> interceptorClass =
                cl.loadClass("com.hivemq.extensions.handler.PingInterceptorHandlerTest$" + name);

        final PingReqInboundInterceptor interceptor =
                (PingReqInboundInterceptor) interceptorClass.newInstance();

        return interceptor;
    }


    private @NotNull PingRespOutboundInterceptor getIsolatedOutboundInterceptor(final @NotNull String name)
            throws Exception {
        final JavaArchive javaArchive = ShrinkWrap.create(JavaArchive.class)
                .addClass("com.hivemq.extensions.handler.PingInterceptorHandlerTest$" + name);

        final File jarFile = temporaryFolder.newFile();
        javaArchive.as(ZipExporter.class).exportTo(jarFile, true);

        final IsolatedExtensionClassloader
                cl =
                new IsolatedExtensionClassloader(new URL[]{jarFile.toURI().toURL()}, this.getClass().getClassLoader());

        final Class<?> interceptorClass =
                cl.loadClass("com.hivemq.extensions.handler.PingInterceptorHandlerTest$" + name);

        final PingRespOutboundInterceptor interceptor =
                (PingRespOutboundInterceptor) interceptorClass.newInstance();

        return interceptor;
    }

    public static class SimplePingReqTestInterceptor implements PingReqInboundInterceptor {

        @Override
        public void onInboundPingReq(
                final @NotNull PingReqInboundInput pingReqInboundInput,
                final @NotNull PingReqInboundOutput pingReqInboundOutput) {
            System.out.println("Intercepting PINGREQ at " + System.currentTimeMillis());
            isTriggered.set(true);
        }

    }

    public static class SimplePingRespTestInterceptor implements PingRespOutboundInterceptor {

        @Override
        public void onOutboundPingResp(
                final @NotNull PingRespOutboundInput pingRespOutboundInput,
                final @NotNull PingRespOutboundOutput pingRespOutboundOutput) {
            System.out.println("Intercepting PINGRESP at " + System.currentTimeMillis());
            isTriggered.set(true);
        }
    }

    public static class AdvancedPingReqTestInterceptor implements PingReqInboundInterceptor {

        @Override
        public void onInboundPingReq(
                final @NotNull PingReqInboundInput pingReqInboundInput,
                final @NotNull PingReqInboundOutput pingReqInboundOutput) {
            System.out.println(
                    "Intercepted PINGREQ for client: " + pingReqInboundInput.getClientInformation().getClientId());
            isTriggered.set(true);

        }

    }

    public static class AdvancedPingRespTestInterceptor implements PingRespOutboundInterceptor {

        @Override
        public void onOutboundPingResp(
                final @NotNull PingRespOutboundInput pingRespOutboundInput,
                final @NotNull PingRespOutboundOutput pingRespOutboundOutput) {
            System.out.println("Intercepted PINGRESP for client: " +
                    pingRespOutboundInput.getClientInformation().getClientId());
            isTriggered.set(true);
        }

    }

}
