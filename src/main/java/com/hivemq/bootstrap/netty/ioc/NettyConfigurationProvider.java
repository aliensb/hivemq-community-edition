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
package com.hivemq.bootstrap.netty.ioc;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hivemq.bootstrap.netty.NettyConfiguration;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.concurrent.ThreadFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This Provider creates the configuration for Netty.
 *
 * @author Dominik Obermaier
 */
@Singleton
public class NettyConfigurationProvider implements Provider<NettyConfiguration> {

    private static final Logger log = LoggerFactory.getLogger(NettyConfigurationProvider.class);

    private static boolean isLinuxPlatform = false;
    private static boolean isWindowsPlatform = false;

    public static final String OS_NAME = System.getProperty("os.name");

    static {
        if (OS_NAME != null && OS_NAME.toLowerCase().contains("linux")) {
            isLinuxPlatform = true;
        }

        if (OS_NAME != null && OS_NAME.toLowerCase().contains("windows")) {
            isWindowsPlatform = true;
        }
    }

    @NotNull
    @Override
    public NettyConfiguration get() {

        final EventLoopGroup parentGroup = createParentEventLoop();
        final EventLoopGroup childGroup = createChildEventLoop();
        log.debug("create parent event loop is {}",parentGroup);
        log.debug("create child event loop is {}",childGroup);
        return useEpoll() ? new NettyConfiguration(EpollServerSocketChannel.class,
                EpollSocketChannel.class,
                parentGroup,
                childGroup) :
                new NettyConfiguration(NioServerSocketChannel.class, NioSocketChannel.class, parentGroup, childGroup);
    }

    /**
     * Creates the Parent Eventloop. Creates either a NIO Eventloop or a native Epoll Eventloop with a preference
     * for native Epoll.
     *
     * @return the Boss EventLoopGroup
     */
    @NotNull
    private EventLoopGroup createParentEventLoop() {
        return useEpoll() ? new EpollEventLoopGroup(1,createThreadFactory("hivemq-epolleventloop-parent-%d") ) : new NioEventLoopGroup(1, createThreadFactory("hivemq-eventloop-parent-%d"));
    }

    /**
     * Creates the Child Eventloop. Creates either a NIO Eventloop or a native Epoll Eventloop with a preference
     * for native Epoll.
     *
     * @return the Boss EventLoopGroup
     */
    @NotNull
    private EventLoopGroup createChildEventLoop() {
        //Default Netty Threads.
        return useEpoll() ? new EpollEventLoopGroup(0,createThreadFactory("hivemq-epolleventloop-child-%d") ) : new NioEventLoopGroup(0, createThreadFactory("hivemq-eventloop-child-%d"));
    }

    /**
     * Creates a Thread Factory that names Threads with the given format
     *
     * @param nameFormat the format
     * @return a ThreadFactory that names Threads with the given format
     */
    private ThreadFactory createThreadFactory(final @NotNull String nameFormat) {

        checkNotNull(nameFormat, "Thread Factory Name Format must not be null");
        return new ThreadFactoryBuilder().
                setNameFormat(nameFormat).
                build();
    }

    private boolean useEpoll() {
        return isLinuxPlatform && Epoll.isAvailable();
    }
}
