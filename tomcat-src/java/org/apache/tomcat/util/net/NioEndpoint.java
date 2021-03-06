/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.jsse.JSSESupport;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 *
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public class NioEndpoint extends AbstractJsseEndpoint<NioChannel> {


    // -------------------------------------------------------------- Constants


    private static final Log log = LogFactory.getLog(NioEndpoint.class);


    public static final int OP_REGISTER = 0x100; //register interest op

    // ----------------------------------------------------------------- Fields

    private NioSelectorPool selectorPool = new NioSelectorPool();

    /**
     * Server socket "pointer".
     */
    private volatile ServerSocketChannel serverSock = null;

    /**
     *
     */
    private volatile CountDownLatch stopLatch = null;

    /**
     * Cache for poller events
     */
    private SynchronizedStack<PollerEvent> eventCache;

    /**
     * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
     */
    private SynchronizedStack<NioChannel> nioChannels;


    // ------------------------------------------------------------- Properties


    /**
     * Generic properties, introspected
     */
    @Override
    public boolean setProperty(String name, String value) {
        final String selectorPoolName = "selectorPool.";
        try {
            if (name.startsWith(selectorPoolName)) {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else {
                return super.setProperty(name, value);
            }
        }catch ( Exception x ) {
            log.error("Unable to set attribute \""+name+"\" to \""+value+"\"",x);
            return false;
        }
    }


    /**
     * Use System.inheritableChannel to obtain channel from stdin/stdout.
     */
    private boolean useInheritedChannel = false;
    public void setUseInheritedChannel(boolean useInheritedChannel) { this.useInheritedChannel = useInheritedChannel; }
    public boolean getUseInheritedChannel() { return useInheritedChannel; }

    /**
     * Priority of the poller threads.
     */
    private int pollerThreadPriority = Thread.NORM_PRIORITY;
    public void setPollerThreadPriority(int pollerThreadPriority) { this.pollerThreadPriority = pollerThreadPriority; }
    public int getPollerThreadPriority() { return pollerThreadPriority; }


    /**
     * Poller thread count.
     */
    private int pollerThreadCount = Math.min(2,Runtime.getRuntime().availableProcessors());
    public void setPollerThreadCount(int pollerThreadCount) { this.pollerThreadCount = pollerThreadCount; }
    public int getPollerThreadCount() { return pollerThreadCount; }

    private long selectorTimeout = 1000;
    public void setSelectorTimeout(long timeout){ this.selectorTimeout = timeout;}
    public long getSelectorTimeout(){ return this.selectorTimeout; }
    /**
     * The socket poller.
     */
    private Poller[] pollers = null;
    private AtomicInteger pollerRotater = new AtomicInteger(0);
    /**
     * Return an available poller in true round robin fashion.
     *
     * @return The next poller in sequence
     */
    // 获取poller
    public Poller getPoller0() {
        int idx = Math.abs(pollerRotater.incrementAndGet()) % pollers.length;
        return pollers[idx];
    }


    public void setSelectorPool(NioSelectorPool selectorPool) {
        this.selectorPool = selectorPool;
    }

    public void setSocketProperties(SocketProperties socketProperties) {
        this.socketProperties = socketProperties;
    }

    /**
     * Is deferAccept supported?
     */
    @Override
    public boolean getDeferAccept() {
        // Not supported
        return false;
    }


    // --------------------------------------------------------- Public Methods
    /**
     * Number of keep-alive sockets.
     *
     * @return The number of sockets currently in the keep-alive state waiting
     *         for the next request to be received on the socket
     */
    public int getKeepAliveCount() {
        if (pollers == null) {
            return 0;
        } else {
            int sum = 0;
            for (int i=0; i<pollers.length; i++) {
                sum += pollers[i].getKeyCount();
            }
            return sum;
        }
    }


    // ----------------------------------------------- Public Lifecycle Methods

    /**
     * Initialize the endpoint.
     * 对endpoint的初始化
     * 1. 创建severSocketchannel
     * 2. 绑定地址端口
     * 3. 初始化ssl
     * 4. 初始化 selectorPool
     */
    @Override
    public void bind() throws Exception {

        if (!getUseInheritedChannel()) {
            // nio 经典创建 serverSocketChannel
            serverSock = ServerSocketChannel.open();
            // 此是对 socket 进行属性配置-- connectionTimeout  socketTimeout  reuse 等
            socketProperties.setProperties(serverSock.socket());
            // 创建地址
            InetSocketAddress addr = (getAddress()!=null?new InetSocketAddress(getAddress(),getPort()):new InetSocketAddress(getPort()));
            // 地址绑定
            serverSock.socket().bind(addr,getAcceptCount());
        } else {
            // Retrieve the channel provided by the OS
            Channel ic = System.inheritedChannel();
            if (ic instanceof ServerSocketChannel) {
                serverSock = (ServerSocketChannel) ic;
            }
            if (serverSock == null) {
                throw new IllegalArgumentException(sm.getString("endpoint.init.bind.inherited"));
            }
        }
        // 设置了 阻塞 ???
        serverSock.configureBlocking(true); //mimic APR behavior

        // Initialize thread count defaults for acceptor, poller
        if (acceptorThreadCount == 0) {
            // FIXME: Doesn't seem to work that well with multiple accept threads
            acceptorThreadCount = 1;
        }
        if (pollerThreadCount <= 0) {
            //minimum one poller thread
            pollerThreadCount = 1;
        }
        setStopLatch(new CountDownLatch(pollerThreadCount));

        // Initialize SSL if needed
        // ssl的初始化
        initialiseSsl();
        // 对selectPool的 初始化
        selectorPool.open();
    }

    /**
     * Start the NIO endpoint, creating acceptor, poller threads.
     */
    // socket接收的 后台线程
    @Override
    public void startInternal() throws Exception {

        if (!running) {
            running = true;
            paused = false;
            // 默认长度大小为 128
            // 三个缓存的 列表都是 128
            processorCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getProcessorCache());
            eventCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                            socketProperties.getEventCache());
            nioChannels = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getBufferPool());

            // Create worker collection
            // 创建  executor
            if ( getExecutor() == null ) {
                createExecutor();
            }
            // 限流使用
            // 变相的限制 连接数
            initializeConnectionLatch();

            // Start poller threads
            // 控制pollers线程数量的是
            // pollerThreadCount = Math.min(2,Runtime.getRuntime().availableProcessors())
            // poller 用于进行 读写处理
            pollers = new Poller[getPollerThreadCount()];
            for (int i=0; i<pollers.length; i++) {
                // 看一下poller的run方法
                pollers[i] = new Poller();
                Thread pollerThread = new Thread(pollers[i], getName() + "-ClientPoller-"+i);
                pollerThread.setPriority(threadPriority);
                //pollerThread.setDaemon(true);
                // 同步
                pollerThread.setDaemon(false);
                pollerThread.start();
            }
            // 接收器 用于接收
            startAcceptorThreads();
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    @Override
    public void stopInternal() {
        releaseConnectionLatch();
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            unlockAccept();
            for (int i=0; pollers!=null && i<pollers.length; i++) {
                if (pollers[i]==null) continue;
                pollers[i].destroy();
                pollers[i] = null;
            }
            try {
                if (!getStopLatch().await(selectorTimeout + 100, TimeUnit.MILLISECONDS)) {
                    log.warn(sm.getString("endpoint.nio.stopLatchAwaitFail"));
                }
            } catch (InterruptedException e) {
                log.warn(sm.getString("endpoint.nio.stopLatchAwaitInterrupted"), e);
            }
            shutdownExecutor();
            eventCache.clear();
            nioChannels.clear();
            processorCache.clear();
        }
    }


    /**
     * Deallocate NIO memory pools, and close server socket.
     */
    @Override
    public void unbind() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Destroy initiated for "+new InetSocketAddress(getAddress(),getPort()));
        }
        if (running) {
            stop();
        }
        doCloseServerSocket();
        destroySsl();
        super.unbind();
        if (getHandler() != null ) {
            getHandler().recycle();
        }
        selectorPool.close();
        if (log.isDebugEnabled()) {
            log.debug("Destroy completed for "+new InetSocketAddress(getAddress(),getPort()));
        }
    }


    @Override
    protected void doCloseServerSocket() throws IOException {
        if (!getUseInheritedChannel() && serverSock != null) {
            // Close server socket
            serverSock.socket().close();
            serverSock.close();
        }
        serverSock = null;
    }


    // ------------------------------------------------------ Protected Methods


    public int getWriteBufSize() {
        return socketProperties.getTxBufSize();
    }

    public int getReadBufSize() {
        return socketProperties.getRxBufSize();
    }

    public NioSelectorPool getSelectorPool() {
        return selectorPool;
    }


    @Override
    protected AbstractEndpoint.Acceptor createAcceptor() {
        return new Acceptor();
    }


    protected CountDownLatch getStopLatch() {
        return stopLatch;
    }


    protected void setStopLatch(CountDownLatch stopLatch) {
        this.stopLatch = stopLatch;
    }


    /**
     * Process the specified connection.
     * @param socket The socket channel
     * @return <code>true</code> if the socket was correctly configured
     *  and processing may continue, <code>false</code> if the socket needs to be
     *  close immediately
     */
    // 把socket注册到selector上
    protected boolean setSocketOptions(SocketChannel socket) {
        // Process the connection
        try {
            //disable blocking, APR style, we are gonna be polling it
            // 配置socket为非阻塞
            socket.configureBlocking(false);
            // 获取socket
            Socket sock = socket.socket();
            // socket的配置 设置到socket
            socketProperties.setProperties(sock);
            // 去缓存中获取一个 Niochannel
            // 去 nioChannels中获取一个 NioChannel,用于本次处理
            // 可见NioChannel是循环利用的
            NioChannel channel = nioChannels.pop();
            // 如果不存在可用的NioChannel
            // 则创建一个
            if (channel == null) {
                // 这里主要是创建了 缓冲区  ByteBuffer
                SocketBufferHandler bufhandler = new SocketBufferHandler(
                        // read size 8192 == writeBuffer size
                        socketProperties.getAppReadBufSize(),
                        socketProperties.getAppWriteBufSize(),
                        // 默认不适用 directBuffer
                        socketProperties.getDirectBuffer());
                if (isSSLEnabled()) {
                    // 如果使能了 ssl,则创建 SecureNioChannel
                    channel = new SecureNioChannel(socket, bufhandler, selectorPool, this);
                } else {
                    // 如果是正常的, 则创建NioChannel
                    channel = new NioChannel(socket, bufhandler);
                }
            } else {
                // 存在 channel,则配置channel为新接收的socket
                channel.setIOChannel(socket);
                // reset此channel
                channel.reset();
            }
            // 获取一个poller,把此channel注册到 其中
            // 即 后面的读写操作 就由poller来进行处理了
            getPoller0().register(channel);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error("",t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(tt);
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }


    @Override
    protected Log getLog() {
        return log;
    }


    @Override
    protected NetworkChannel getServerSocket() {
        return serverSock;
    }


    // --------------------------------------------------- Acceptor Inner Class
    /**
     * The background thread that listens for incoming TCP/IP connections and
     * hands them off to an appropriate processor.
     */
    // 具体的接收 thread
    protected class Acceptor extends AbstractEndpoint.Acceptor {

        @Override
        public void run() {

            int errorDelay = 0;

            // Loop until we receive a shutdown command
            while (running) {

                // Loop if endpoint is paused
                // 1.如果设置的 pause,则持续sleep
                while (paused && running) {
                    state = AcceptorState.PAUSED;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                if (!running) {
                    break;
                }
                // 2. 更新状态为 正在运行
                state = AcceptorState.RUNNING;

                try {
                    //if we have reached max connections, wait
                    // 3. 限制连接数
                    countUpOrAwaitConnection();

                    SocketChannel socket = null;
                    try {
                        // Accept the next incoming connection from the server
                        // socket  接收连接
                        // 4. 阻塞式的接收连接
                        socket = serverSock.accept();
                    } catch (IOException ioe) {
                        // We didn't get a socket
                        countDownConnection();
                        if (running) {
                            // Introduce delay if necessary
                            errorDelay = handleExceptionWithDelay(errorDelay);
                            // re-throw
                            throw ioe;
                        } else {
                            break;
                        }
                    }
                    // Successful accept, reset the error delay
                    errorDelay = 0;

                    // Configure the socket
                    if (running && !paused) {
                        // setSocketOptions() will hand the socket off to
                        // an appropriate processor if successful
                        // 5. todo 对socket进行处理
                        if (!setSocketOptions(socket)) {
                            closeSocket(socket);
                        }
                    } else {
                        closeSocket(socket);
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }
            state = AcceptorState.ENDED;
        }


        private void closeSocket(SocketChannel socket) {
            countDownConnection();
            try {
                socket.socket().close();
            } catch (IOException ioe)  {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("endpoint.err.close"), ioe);
                }
            }
            try {
                socket.close();
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("endpoint.err.close"), ioe);
                }
            }
        }
    }


    @Override
    protected SocketProcessorBase<NioChannel> createSocketProcessor(
            SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
        // 创建一个处理器,对接收到的socket的进行处理
        return new SocketProcessor(socketWrapper, event);
    }

    // 关闭 socket
    private void close(NioChannel socket, SelectionKey key) {
        try {
            // 移除对应的selectionKey
            if (socket.getPoller().cancelledKey(key) != null) {
                // SocketWrapper (attachment) was removed from the
                // key - recycle the key. This can only happen once
                // per attempted closure so it is used to determine
                // whether or not to return the key to the cache.
                // We do NOT want to do this more than once - see BZ
                // 57340 / 57943.
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + socket + "] closed");
                }
                if (running && !paused) {
                    if (!nioChannels.push(socket)) {
                        // nioChannel 释放, 主要是释放使用的buffer
                        socket.free();
                    }
                }
            }
        } catch (Exception x) {
            log.error("",x);
        }
    }

    // ----------------------------------------------------- Poller Inner Classes

    /**
     *
     * PollerEvent, cacheable object for poller events to avoid GC
     */
    public static class PollerEvent implements Runnable {
        // 记录socketchannel
        private NioChannel socket;
        // 记录感兴趣的事件
        private int interestOps;
        // 此pollerEvent中的包装的 NioSocketWrapper
        private NioSocketWrapper socketWrapper;
        //
        public PollerEvent(NioChannel ch, NioSocketWrapper w, int intOps) {
            reset(ch, w, intOps);
        }

        public void reset(NioChannel ch, NioSocketWrapper w, int intOps) {
            socket = ch;
            interestOps = intOps;
            socketWrapper = w;
        }
        // 复位此 pollerEvent 以备下次重复使用
        public void reset() {
            reset(null, null, 0);
        }

        // pollerEvent事件的run方法,对感兴趣事件的处理
        @Override
        public void run() {
            // 如果事件是OP_REGISTER,则注册下次的感兴趣事件是OP_READ
            if (interestOps == OP_REGISTER) {
                try {
                    // 进行注册操作
                    // socket.getPoller().getSelector()  获取 selector
                    // SelectionKey.OP_READ   感兴趣的事件
                    // socketWrapper  socket的包装,此会 attach 到 selectionKey上
                    socket.getIOChannel().register(
                            socket.getPoller().getSelector(), SelectionKey.OP_READ, socketWrapper);
                } catch (Exception x) {
                    log.error(sm.getString("endpoint.nio.registerFail"), x);
                }
            } else {
                // 获取此 channel对应的key
                final SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                try {
                    // 如果socket的对应的key不存在了,则取消此key
                    if (key == null) {
                        // The key was cancelled (e.g. due to socket closure)
                        // and removed from the selector while it was being
                        // processed. Count down the connections at this point
                        // since it won't have been counted down when the socket
                        // closed.
                        socket.socketWrapper.getEndpoint().countDownConnection();
                        ((NioSocketWrapper) socket.socketWrapper).closed = true;
                    } else {
                        // 存在,则获取此key对应的socketWrapper,设置其下一次的事件为 key.interestOps() | interestOps
                        final NioSocketWrapper socketWrapper = (NioSocketWrapper) key.attachment();
                        if (socketWrapper != null) {
                            //we are registering the key to start with, reset the fairness counter.
                            int ops = key.interestOps() | interestOps;
                            socketWrapper.interestOps(ops);
                            key.interestOps(ops);
                        } else {
                            socket.getPoller().cancelledKey(key);
                        }
                    }
                } catch (CancelledKeyException ckx) {
                    try {
                        socket.getPoller().cancelledKey(key);
                    } catch (Exception ignore) {}
                }
            }
        }

        @Override
        public String toString() {
            return "Poller event: socket [" + socket + "], socketWrapper [" + socketWrapper +
                    "], interestOps [" + interestOps + "]";
        }
    }

    /**
     * Poller class.
     */
    public class Poller implements Runnable {

        private Selector selector;
        // events记录感兴趣的事件
        private final SynchronizedQueue<PollerEvent> events =
                new SynchronizedQueue<>();

        private volatile boolean close = false;
        private long nextExpiration = 0;//optimize expiration handling
        // 记录唤醒的此处
        private AtomicLong wakeupCounter = new AtomicLong(0);

        private volatile int keyCount = 0;

        public Poller() throws IOException {
            // 创建了 nioselector
            this.selector = Selector.open();
        }

        public int getKeyCount() { return keyCount; }

        public Selector getSelector() { return selector;}

        /**
         * Destroy the poller.
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
            close = true;
            selector.wakeup();
        }
        // 发布事件
        private void addEvent(PollerEvent event) {
            events.offer(event);
            // 注册完socket后,就立即唤醒一个selector
            // 如果wakeupCounter 值为0,则立即进行一次 唤醒操作
            if ( wakeupCounter.incrementAndGet() == 0 ) selector.wakeup();
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socket to add to the poller
         * @param interestOps Operations for which to register this socket with
         *                    the Poller
         */
        public void add(final NioChannel socket, final int interestOps) {
            PollerEvent r = eventCache.pop();
            if ( r==null) r = new PollerEvent(socket,null,interestOps);
            else r.reset(socket,null,interestOps);
            addEvent(r);
            if (close) {
                NioEndpoint.NioSocketWrapper ka = (NioEndpoint.NioSocketWrapper)socket.getAttachment();
                processSocket(ka, SocketEvent.STOP, false);
            }
        }

        /**
         * Processes events in the event queue of the Poller.
         *
         * @return <code>true</code> if some events were processed,
         *   <code>false</code> if queue was empty
         */
        // 对队列中事件的处理
        public boolean events() {
            boolean result = false;

            PollerEvent pe = null;
            for (int i = 0, size = events.size(); i < size && (pe = events.poll()) != null; i++ ) {
                result = true;
                try {
                    // 此处的run相当于重新注册了socket的感兴趣的事件
                    pe.run();
                    // 之后复位pollerEvent, 并放置到eventCache中,是为了复用此pollerEvent
                    pe.reset();
                    if (running && !paused) {
                        // 复位之后,再次缓存起来,用于进行循环使用
                        eventCache.push(pe);
                    }
                } catch ( Throwable x ) {
                    log.error("",x);
                }
            }

            return result;
        }

        /**
         * Registers a newly created socket with the poller.
         *
         * @param socket    The newly created socket
         */
        // 注册socket到poller中
        public void register(final NioChannel socket) {
            // 更新 socket的poller
            socket.setPoller(this);
            // 使用NioSocketWrapper 封装一下 接收到的socket
            NioSocketWrapper ka = new NioSocketWrapper(socket, NioEndpoint.this);
            socket.setSocketWrapper(ka);
            ka.setPoller(this);
            // 设置 读写超时时间
            // 此读写超时时间是 soTimeout 的值 默认为 20000
            ka.setReadTimeout(getSocketProperties().getSoTimeout());
            ka.setWriteTimeout(getSocketProperties().getSoTimeout());
            // 设置是否keepalive
            // 最大值为100
            ka.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            // 是否是 ssl 打开
            ka.setSecure(isSSLEnabled());
            // 读写时间  超时, 设置为 connectionTimeout
            // 具体值为 soTimeout 20000
            ka.setReadTimeout(getConnectionTimeout());
            ka.setWriteTimeout(getConnectionTimeout());
            // 这里对一个socket注册前, 会先去缓存中看是否有可复用的pollerEvent
            // 这里可以看到 PollerEvent 同样也是复用的
            PollerEvent r = eventCache.pop();
            // 这里可以看到,当socket进行注册时,感兴趣事件是 OP_READ,而第一次注册的事件OP_REGISTER
            // 那么当下一次此socket准备好时,就会注册op_read事件
            ka.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.
            if ( r==null) r = new PollerEvent(socket,ka,OP_REGISTER);
            else r.reset(socket,ka,OP_REGISTER);
            // 发布一个 PollerEvent 事件到队列中
            addEvent(r);
        }

        public NioSocketWrapper cancelledKey(SelectionKey key) {
            NioSocketWrapper ka = null;
            try {
                // 参数为null,直接返回
                if ( key == null ) return null;//nothing to do
                // 设置attach为null
                ka = (NioSocketWrapper) key.attach(null);
                // 原来有值,则此 NioSocketWrapper,则把此ka对应的processor 回收
                if (ka != null) {
                    // If attachment is non-null then there may be a current
                    // connection with an associated processor.
                    getHandler().release(ka);
                }
                // 把key cancel
                if (key.isValid()) key.cancel();
                // If it is available, close the NioChannel first which should
                // in turn close the underlying SocketChannel. The NioChannel
                // needs to be closed first, if available, to ensure that TLS
                // connections are shut down cleanly.
                if (ka != null) {
                    try {
                        // 对应的socket 关闭
                        ka.getSocket().close(true);
                    } catch (Exception e){
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.socketCloseFail"), e);
                        }
                    }
                }
                // The SocketChannel is also available via the SelectionKey. If
                // it hasn't been closed in the block above, close it now.
                if (key.channel().isOpen()) {
                    try {
                        key.channel().close();
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.channelCloseFail"), e);
                        }
                    }
                }
                try {
                    if (ka != null && ka.getSendfileData() != null
                            && ka.getSendfileData().fchannel != null
                            && ka.getSendfileData().fchannel.isOpen()) {
                        ka.getSendfileData().fchannel.close();
                    }
                } catch (Exception ignore) {
                }
                if (ka != null) {
                    countDownConnection();
                    ka.closed = true;
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) log.error("",e);
            }
            return ka;
        }

        /**
         * The background thread that adds sockets to the Poller, checks the
         * poller for triggered events and hands the associated socket off to an
         * appropriate processor as events occur.
         */
        @Override
        public void run() {
            // Loop until destroy() is called
            while (true) {

                boolean hasEvents = false;

                try {
                    if (!close) {
                        // 检测现在是否有事件, 并对事件进行处理
                        // 这里修改事件中 对应的socket的 感兴趣的事件,以及进行了具体的注册动作
                        hasEvents = events();
                        // wakeupCounter大于0,表示有pollerEvent事件进入
                        // 那么就selectNow,不会有超时等待; 也就是为了及时处理请求
                        if (wakeupCounter.getAndSet(-1) > 0) {
                            //if we are here, means we have other stuff to do
                            //do a non blocking select
                            keyCount = selector.selectNow();
                        } else {
                            // 如果到此,说明暂时没有注册的socket
                            keyCount = selector.select(selectorTimeout);
                        }
                        // 把此设置为0,用于下次有事件后,能快速检测到
                        wakeupCounter.set(0);
                    }
                    if (close) {
                        // 要关闭了,对events中的事件的处理
                        events();
                        //
                        timeout(0, false);
                        try {
                            // nioselector 关闭
                            selector.close();
                        } catch (IOException ioe) {
                            log.error(sm.getString("endpoint.nio.selectorCloseFail"), ioe);
                        }
                        break;
                    }
                } catch (Throwable x) {
                    ExceptionUtils.handleThrowable(x);
                    log.error("",x);
                    continue;
                }
                //either we timed out or we woke up, process events first
                // 如果之前检测没有事件,那么此处在检测一次
                // 如果被唤醒,先进行一次事件的处理; 即为了timeout处理,也为了及时对事件响应
                if ( keyCount == 0 ) hasEvents = (hasEvents | events());
                // 获取所有可用的key的迭代器
                // 如果检测没有事件, 则不会对key进行遍历
                Iterator<SelectionKey> iterator =
                    keyCount > 0 ? selector.selectedKeys().iterator() : null;
                // Walk through the collection of ready keys and dispatch
                // any active event.
                // todo  遍历所有的可用的事件, 并进行处理
                while (iterator != null && iterator.hasNext()) {
                    SelectionKey sk = iterator.next();
                    NioSocketWrapper attachment = (NioSocketWrapper)sk.attachment();
                    // Attachment may be null if another thread has called
                    // cancelledKey()
                    if (attachment == null) {
                        // selectionKey没有attachment 则把此key remote掉
                        iterator.remove();
                    } else {
                        iterator.remove();
                        // 对就绪的key对应的socket的channel进行处理
                        processKey(sk, attachment);
                    }
                }//while

                //process timeouts
                // timeout的处理
                timeout(keyCount,hasEvents);
            }//while

            getStopLatch().countDown();
        }
        // 针对不同就绪事件的处理
        protected void processKey(SelectionKey sk, NioSocketWrapper attachment) {
            try {
                if ( close ) {
                    // 如果关系了,则取消key
                    cancelledKey(sk);
                } else if ( sk.isValid() && attachment != null ) {
                    // 如果可读 或者 可写,则进行处理
                    if (sk.isReadable() || sk.isWritable() ) {
                        if ( attachment.getSendfileData() != null ) {
                            // 发送文件
                            processSendfile(sk,attachment, false);
                        } else {
                            // sk.readyOps() 就绪的key
                            // --- 重点 ---
                            // 这里重新做了感兴趣事件的注册,把就绪的事件移除了
                            unreg(sk, attachment, sk.readyOps());
                            boolean closeSocket = false;
                            // Read goes before write
                            if (sk.isReadable()) {
                                // 处理可读事件
                                // todo  继续看此方法对socket的处理
                                if (!processSocket(attachment, SocketEvent.OPEN_READ, true)) {
                                    closeSocket = true;
                                }
                            }
                            if (!closeSocket && sk.isWritable()) {
                                // 处理可写事件
                                if (!processSocket(attachment, SocketEvent.OPEN_WRITE, true)) {
                                    closeSocket = true;
                                }
                            }
                            if (closeSocket) {
                                cancelledKey(sk);
                            }
                        }
                    }
                } else {
                    //invalid key
                    cancelledKey(sk);
                }
            } catch ( CancelledKeyException ckx ) {
                cancelledKey(sk);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error("",t);
            }
        }

        public SendfileState processSendfile(SelectionKey sk, NioSocketWrapper socketWrapper,
                boolean calledByProcessor) {
            NioChannel sc = null;
            try {
                unreg(sk, socketWrapper, sk.readyOps());
                SendfileData sd = socketWrapper.getSendfileData();

                if (log.isTraceEnabled()) {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                if (sd.fchannel == null) {
                    // Setup the file channel
                    File f = new File(sd.fileName);
                    @SuppressWarnings("resource") // Closed when channel is closed
                    FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                // Configure output channel
                sc = socketWrapper.getSocket();
                // TLS/SSL channel is slightly different
                WritableByteChannel wc = ((sc instanceof SecureNioChannel)?sc:sc.getIOChannel());

                // We still have data in the buffer
                if (sc.getOutboundRemaining()>0) {
                    if (sc.flushOutbound()) {
                        socketWrapper.updateLastWrite();
                    }
                } else {
                    long written = sd.fchannel.transferTo(sd.pos,sd.length,wc);
                    if (written > 0) {
                        sd.pos += written;
                        sd.length -= written;
                        socketWrapper.updateLastWrite();
                    } else {
                        // Unusual not to be able to transfer any bytes
                        // Check the length was set correctly
                        if (sd.fchannel.size() <= sd.pos) {
                            throw new IOException("Sendfile configured to " +
                                    "send more data than was available");
                        }
                    }
                }
                if (sd.length <= 0 && sc.getOutboundRemaining()<=0) {
                    if (log.isDebugEnabled()) {
                        log.debug("Send file complete for: "+sd.fileName);
                    }
                    socketWrapper.setSendfileData(null);
                    try {
                        sd.fchannel.close();
                    } catch (Exception ignore) {
                    }
                    // For calls from outside the Poller, the caller is
                    // responsible for registering the socket for the
                    // appropriate event(s) if sendfile completes.
                    if (!calledByProcessor) {
                        switch (sd.keepAliveState) {
                        case NONE: {
                            if (log.isDebugEnabled()) {
                                log.debug("Send file connection is being closed");
                            }
                            close(sc, sk);
                            break;
                        }
                        case PIPELINED: {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, processing pipe-lined data");
                            }
                            if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                                close(sc, sk);
                            }
                            break;
                        }
                        case OPEN: {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, registering back for OP_READ");
                            }
                            reg(sk,socketWrapper,SelectionKey.OP_READ);
                            break;
                        }
                        }
                    }
                    return SendfileState.DONE;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("OP_WRITE for sendfile: " + sd.fileName);
                    }
                    if (calledByProcessor) {
                        add(socketWrapper.getSocket(),SelectionKey.OP_WRITE);
                    } else {
                        reg(sk,socketWrapper,SelectionKey.OP_WRITE);
                    }
                    return SendfileState.PENDING;
                }
            } catch (IOException x) {
                if (log.isDebugEnabled()) log.debug("Unable to complete sendfile request:", x);
                if (!calledByProcessor && sc != null) {
                    close(sc, sk);
                }
                return SendfileState.ERROR;
            } catch (Throwable t) {
                log.error("", t);
                if (!calledByProcessor && sc != null) {
                    close(sc, sk);
                }
                return SendfileState.ERROR;
            }
        }
        // 此函数的主要作用: 重新注册感兴趣事件,把就绪key 移除
        protected void unreg(SelectionKey sk, NioSocketWrapper attachment, int readyOps) {
            //this is a must, so that we don't have multiple threads messing with the socket
            // sk.interestOps()& (~readyOps) 这里移除了 就绪的key
            // 之后重新注册了感兴趣事件
            reg(sk,attachment,sk.interestOps()& (~readyOps));
        }

        protected void reg(SelectionKey sk, NioSocketWrapper attachment, int intops) {
            // 这里修改了 感兴趣的事件
            sk.interestOps(intops);
            attachment.interestOps(intops);
        }

        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            // This method is called on every loop of the Poller. Don't process
            // timeouts on every loop of the Poller since that would create too
            // much load and timeouts can afford to wait a few seconds.
            // However, do process timeouts if any of the following are true:
            // - the selector simply timed out (suggests there isn't much load)
            // - the nextExpiration time has passed
            // - the server socket is being closed
            if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            //timeout
            int keycount = 0;
            try {
                // 遍历所有的 selectionKey
                for (SelectionKey key : selector.keys()) {
                    keycount++;
                    try {
                        NioSocketWrapper ka = (NioSocketWrapper) key.attachment();
                        if ( ka == null ) { // attachment没有值,则取消此 key
                            cancelledKey(key); //we don't support any keys without attachments
                        } else if (close) {
                            // 如果关闭了,则把感兴趣设置设置为0
                            key.interestOps(0);
                            ka.interestOps(0); //avoid duplicate stop calls
                            processKey(key,ka);
                        } else if ((ka.interestOps()&SelectionKey.OP_READ) == SelectionKey.OP_READ ||
                                  (ka.interestOps()&SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                            boolean isTimedOut = false;
                            // Check for read timeout
                            // 判断是否读超时
                            if ((ka.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
                                long delta = now - ka.getLastRead();
                                long timeout = ka.getReadTimeout();
                                isTimedOut = timeout > 0 && delta > timeout;
                            }
                            // Check for write timeout
                            // 判断是否写 超时
                            if (!isTimedOut && (ka.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                                long delta = now - ka.getLastWrite();
                                long timeout = ka.getWriteTimeout();
                                isTimedOut = timeout > 0 && delta > timeout;
                            }
                            // 如果超时了,则清空 事件;设置error
                            if (isTimedOut) {
                                key.interestOps(0);
                                ka.interestOps(0); //avoid duplicate timeout calls
                                ka.setError(new SocketTimeoutException());
                                if (!processSocket(ka, SocketEvent.ERROR, true)) {
                                    cancelledKey(key);
                                }
                            }
                        }
                    }catch ( CancelledKeyException ckx ) {
                        cancelledKey(key);
                    }
                }//for
            } catch (ConcurrentModificationException cme) {
                // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
                log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
            }
            long prevExp = nextExpiration; //for logging purposes only
            // 下次超时时间
            nextExpiration = System.currentTimeMillis() +
                    socketProperties.getTimeoutInterval();
            if (log.isTraceEnabled()) {
                log.trace("timeout completed: keys processed=" + keycount +
                        "; now=" + now + "; nextExpiration=" + prevExp +
                        "; keyCount=" + keyCount + "; hasEvents=" + hasEvents +
                        "; eval=" + ((now < prevExp) && (keyCount>0 || hasEvents) && (!close) ));
            }

        }
    }

    // ---------------------------------------------------- Key Attachment Class
    public static class NioSocketWrapper extends SocketWrapperBase<NioChannel> {

        private final NioSelectorPool pool;
        // 记录此 NioSocketWrapper 注册在哪一个 poller 上
        private Poller poller = null;
        // 感兴趣事件
        private int interestOps = 0;
        private CountDownLatch readLatch = null;
        private CountDownLatch writeLatch = null;
        private volatile SendfileData sendfileData = null;
        // 上次读取的时间
        private volatile long lastRead = System.currentTimeMillis();
        // 上次写的时间
        private volatile long lastWrite = lastRead;
        // 是否关闭
        private volatile boolean closed = false;

        public NioSocketWrapper(NioChannel channel, NioEndpoint endpoint) {
            super(channel, endpoint);
            pool = endpoint.getSelectorPool();
            // socket 读写buffer, SocketBufferHandler
            socketBufferHandler = channel.getBufHandler();
        }

        public Poller getPoller() { return poller;}
        public void setPoller(Poller poller){this.poller = poller;}
        public int interestOps() { return interestOps;}
        // 设置此NioSocketWrapper 感兴趣事件
        public int interestOps(int ops) { this.interestOps  = ops; return ops; }
        public CountDownLatch getReadLatch() { return readLatch; }
        public CountDownLatch getWriteLatch() { return writeLatch; }
        protected CountDownLatch resetLatch(CountDownLatch latch) {
            if ( latch==null || latch.getCount() == 0 ) return null;
            else throw new IllegalStateException("Latch must be at count 0");
        }
        public void resetReadLatch() { readLatch = resetLatch(readLatch); }
        public void resetWriteLatch() { writeLatch = resetLatch(writeLatch); }

        protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
            if ( latch == null || latch.getCount() == 0 ) {
                return new CountDownLatch(cnt);
            }
            else throw new IllegalStateException("Latch must be at count 0 or null.");
        }
        public void startReadLatch(int cnt) { readLatch = startLatch(readLatch,cnt);}
        public void startWriteLatch(int cnt) { writeLatch = startLatch(writeLatch,cnt);}

        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
            if ( latch == null ) throw new IllegalStateException("Latch cannot be null");
            // Note: While the return value is ignored if the latch does time
            //       out, logic further up the call stack will trigger a
            //       SocketTimeoutException
            latch.await(timeout,unit);
        }
        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(readLatch,timeout,unit);}
        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(writeLatch,timeout,unit);}

        public void setSendfileData(SendfileData sf) { this.sendfileData = sf;}
        public SendfileData getSendfileData() { return this.sendfileData;}

        public void updateLastWrite() { lastWrite = System.currentTimeMillis(); }
        public long getLastWrite() { return lastWrite; }
        public void updateLastRead() { lastRead = System.currentTimeMillis(); }
        public long getLastRead() { return lastRead; }


        @Override
        public boolean isReadyForRead() throws IOException {
            socketBufferHandler.configureReadBufferForRead();

            if (socketBufferHandler.getReadBuffer().remaining() > 0) {
                return true;
            }

            fillReadBuffer(false);

            boolean isReady = socketBufferHandler.getReadBuffer().position() > 0;
            return isReady;
        }


        @Override
        public int read(boolean block, byte[] b, int off, int len) throws IOException {
            int nRead = populateReadBuffer(b, off, len);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // Fill the read buffer as best we can.
            nRead = fillReadBuffer(block);
            updateLastRead();

            // Fill as much of the remaining byte array as possible with the
            // data that was just read
            if (nRead > 0) {
                socketBufferHandler.configureReadBufferForRead();
                nRead = Math.min(nRead, len);
                socketBufferHandler.getReadBuffer().get(b, off, nRead);
            }
            return nRead;
        }

        // 从socketChannel中读取数据
        @Override
        public int read(boolean block, ByteBuffer to) throws IOException {
            // 把 socketBufferHandler中的readBuf内容拷贝到 to buffer中
            int nRead = populateReadBuffer(to);
            // 如果有数据流动,则直接返回
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // The socket read buffer capacity is socket.appReadBufSize
            int limit = socketBufferHandler.getReadBuffer().capacity();
            // 如果to buffer的可用空间大于  readBuffer的容量
            // 则直接从socketChannel中读取数据到 to buffer中
            if (to.remaining() >= limit) {
                to.limit(to.position() + limit);
                // 则直接从 socketChannel中读取数据到  to  buffer中
                nRead = fillReadBuffer(block, to);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read direct from socket: [" + nRead + "]");
                }
                // 更新 NioSocketWrapper中的上次读取时间
                updateLastRead();
            } else {
                // 否则,读取数到 socketBufferHandler的readBuf中
                // Fill the read buffer as best we can.
                nRead = fillReadBuffer(block);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read into buffer: [" + nRead + "]");
                }
                // 更新上次读取的时间
                updateLastRead();

                // Fill as much of the remaining byte array as possible with the
                // data that was just read
                if (nRead > 0) {
                    // 如果读取到数据,则把socketBufferHandler.readBuffer中的数据拷贝到to buffer中
                    nRead = populateReadBuffer(to);
                }
            }
            return nRead;
        }


        @Override
        public void close() throws IOException {
            getSocket().close();
        }


        @Override
        public boolean isClosed() {
            return closed;
        }

        // 从socketChannel中读取数据到 socketBufferHandler.readBuffer
        private int fillReadBuffer(boolean block) throws IOException {
            // 配置 readBuffer 到写模式
            socketBufferHandler.configureReadBufferForWrite();
            // 从socketChannel读取数据到 socketBufferHandler.readBuffer
            return fillReadBuffer(block, socketBufferHandler.getReadBuffer());
        }

        // 从socketChannel中读取数据
        private int fillReadBuffer(boolean block, ByteBuffer to) throws IOException {
            int nRead;
            // 获取 NioChannel
            NioChannel channel = getSocket();
            // 如果是 阻塞的,则使用 selectorPool来读取
            // 此处先不展开
            if (block) {
                Selector selector = null;
                try {
                    selector = pool.get();
                } catch (IOException x) {
                    // Ignore
                }
                try {
                    NioEndpoint.NioSocketWrapper att = (NioEndpoint.NioSocketWrapper) channel
                            .getAttachment();
                    if (att == null) {
                        throw new IOException("Key must be cancelled.");
                    }
                    // 从channel中读取数据到 to中
                    nRead = pool.read(to, channel, selector, att.getReadTimeout());
                } finally {
                    if (selector != null) {
                        pool.put(selector);
                    }
                }
            } else {
                // 不是阻塞的,则直接从 socketChannel中读取数据
                // 直接从 nioChannel中读取数据到 to buffer中
                nRead = channel.read(to);
                if (nRead == -1) {
                    throw new EOFException();
                }
            }
            return nRead;
        }

        // 写数据到 SocketChannel中
        @Override
        protected void doWrite(boolean block, ByteBuffer from) throws IOException {
            // 写超时
            long writeTimeout = getWriteTimeout();
            Selector selector = null;
            try {
                // 默认是有 shared 的 nioselector
                selector = pool.get();
            } catch (IOException x) {
                // Ignore
            }
            try {
                // 写入到 socketChannel中
                pool.write(from, getSocket(), selector, writeTimeout, block);
                if (block) {
                    // Make sure we are flushed
                    do {
                        if (getSocket().flush(true, selector, writeTimeout)) {
                            break;
                        }
                    } while (true);
                }
                // 更新写时间
                updateLastWrite();
            } finally {
                if (selector != null) {
                    pool.put(selector);
                }
            }
            // If there is data left in the buffer the socket will be registered for
            // write further up the stack. This is to ensure the socket is only
            // registered for write once as both container and user code can trigger
            // write registration.
        }

        // 此操作是把接收到的socket 以及相应的事件 包装为 pollerEvent放置到 events中
        // 等后面poll处理时,会处理这些event,即把这些socket 按照对应的事件注册到 nioselector中
        @Override
        public void registerReadInterest() {
            getPoller().add(getSocket(), SelectionKey.OP_READ);
        }


        @Override
        public void registerWriteInterest() {
            getPoller().add(getSocket(), SelectionKey.OP_WRITE);
        }


        @Override
        public SendfileDataBase createSendfileData(String filename, long pos, long length) {
            return new SendfileData(filename, pos, length);
        }


        @Override
        public SendfileState processSendfile(SendfileDataBase sendfileData) {
            setSendfileData((SendfileData) sendfileData);
            SelectionKey key = getSocket().getIOChannel().keyFor(
                    getSocket().getPoller().getSelector());
            // Might as well do the first write on this thread
            return getSocket().getPoller().processSendfile(key, this, true);
        }


        @Override
        protected void populateRemoteAddr() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getInetAddress();
            if (inetAddr != null) {
                remoteAddr = inetAddr.getHostAddress();
            }
        }


        @Override
        protected void populateRemoteHost() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getInetAddress();
            if (inetAddr != null) {
                remoteHost = inetAddr.getHostName();
                if (remoteAddr == null) {
                    remoteAddr = inetAddr.getHostAddress();
                }
            }
        }


        @Override
        protected void populateRemotePort() {
            remotePort = getSocket().getIOChannel().socket().getPort();
        }


        @Override
        protected void populateLocalName() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getLocalAddress();
            if (inetAddr != null) {
                localName = inetAddr.getHostName();
            }
        }


        @Override
        protected void populateLocalAddr() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getLocalAddress();
            if (inetAddr != null) {
                localAddr = inetAddr.getHostAddress();
            }
        }


        @Override
        protected void populateLocalPort() {
            localPort = getSocket().getIOChannel().socket().getLocalPort();
        }


        /**
         * {@inheritDoc}
         * @param clientCertProvider Ignored for this implementation
         */
        @Override
        public SSLSupport getSslSupport(String clientCertProvider) {
            if (getSocket() instanceof SecureNioChannel) {
                SecureNioChannel ch = (SecureNioChannel) getSocket();
                SSLSession session = ch.getSslEngine().getSession();
                return ((NioEndpoint) getEndpoint()).getSslImplementation().getSSLSupport(session);
            } else {
                return null;
            }
        }


        @Override
        public void doClientAuth(SSLSupport sslSupport) throws IOException {
            SecureNioChannel sslChannel = (SecureNioChannel) getSocket();
            SSLEngine engine = sslChannel.getSslEngine();
            if (!engine.getNeedClientAuth()) {
                // Need to re-negotiate SSL connection
                engine.setNeedClientAuth(true);
                sslChannel.rehandshake(getEndpoint().getConnectionTimeout());
                ((JSSESupport) sslSupport).setSession(engine.getSession());
            }
        }

        // 记录readBufferHandler
        @Override
        public void setAppReadBufHandler(ApplicationBufferHandler handler) {
            getSocket().setAppReadBufHandler(handler);
        }
    }


    // ---------------------------------------------- SocketProcessor Inner Class

    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketProcessor extends SocketProcessorBase<NioChannel> {

        public SocketProcessor(SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
            super(socketWrapper, event);
        }
        // 对socket的处理
        @Override
        protected void doRun() {
            // 获取此NioSocketWrapper 绑定的nioChannel
            NioChannel socket = socketWrapper.getSocket();
            // 获取此 socketChannel的 selectionKey
            SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());

            try {
                int handshake = -1;

                try {
                    if (key != null) {
                        if (socket.isHandshakeComplete()) {
                            // No TLS handshaking required. Let the handler
                            // process this socket / event combination.
                            // 握手完成  设置 handshake为0
                            handshake = 0;
                        } else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT ||
                                event == SocketEvent.ERROR) {
                            // Unable to complete the TLS handshake. Treat it as
                            // if the handshake failed.
                            // 如果事件是 stop disconnect  error,则设置 handshake为-1, 对于handshake为-1,后续会关闭
                            handshake = -1;
                        } else {
                            // 开始握手
                            handshake = socket.handshake(key.isReadable(), key.isWritable());
                            // The handshake process reads/writes from/to the
                            // socket. status may therefore be OPEN_WRITE once
                            // the handshake completes. However, the handshake
                            // happens when the socket is opened so the status
                            // must always be OPEN_READ after it completes. It
                            // is OK to always set this as it is only used if
                            // the handshake completes.
                            event = SocketEvent.OPEN_READ;
                        }
                    }
                } catch (IOException x) {
                    handshake = -1;
                    if (log.isDebugEnabled()) log.debug("Error during SSL handshake",x);
                } catch (CancelledKeyException ckx) {
                    handshake = -1;
                }
                // 如果handshake完成,
                if (handshake == 0) {
                    SocketState state = SocketState.OPEN;
                    // Process the request from this socket
                    if (event == null) {
                        // event为null, 默认是对 read 事件进行处理
                        state = getHandler().process(socketWrapper, SocketEvent.OPEN_READ);
                    } else {
                        // 调用handler对 socket的event事件进行处理
                        state = getHandler().process(socketWrapper, event);
                    }
                    // 处理后的状态为 clsoe,则关闭对应的 socket
                    if (state == SocketState.CLOSED) {
                        // 关闭 socket
                        close(socket, key);
                    }
                } else if (handshake == -1 ) {
                    // 如果握手失败,则关闭socket
                    close(socket, key);
                } else if (handshake == SelectionKey.OP_READ){
                    // 此是吧 接收到的socketchannel 包装为 pollerevent 放置到 events中
                    // 之后在poller中会对这里pollerevent进行处理
                    // 会把其中的socketChannel 按照事件重新注册到 nioselector中
                    socketWrapper.registerReadInterest();
                } else if (handshake == SelectionKey.OP_WRITE){
                    socketWrapper.registerWriteInterest();
                }
            } catch (CancelledKeyException cx) {
                socket.getPoller().cancelledKey(key);
            } catch (VirtualMachineError vme) {
                ExceptionUtils.handleThrowable(vme);
            } catch (Throwable t) {
                log.error("", t);
                socket.getPoller().cancelledKey(key);
            } finally {
                socketWrapper = null;
                event = null;
                //return to cache
                if (running && !paused) {
                    processorCache.push(this);
                }
            }
        }
    }

    // ----------------------------------------------- SendfileData Inner Class
    /**
     * SendfileData class.
     */
    public static class SendfileData extends SendfileDataBase {

        public SendfileData(String filename, long pos, long length) {
            super(filename, pos, length);
        }

        protected volatile FileChannel fchannel;
    }
}
