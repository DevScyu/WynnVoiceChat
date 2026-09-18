package wynnvoice.server

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.binder.netty4.NettyEventExecutorMetrics
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPromise
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.util.concurrent.DefaultThreadFactory
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import wynnvoice.protocol.Packet
import wynnvoice.protocol.VoicePipeline
import wynnvoice.server.Metrics.counters
import wynnvoice.server.voice.VoiceManager

enum class CloseReason { CLIENT_CLOSED, RATE_LIMITED, AUTH_FAILED, PROTOCOL_ERROR, TIMED_OUT, SERVER_SHUTDOWN }

class ControlServer(
    private val host: String,
    private val port: Int,
    private val sessions: SessionFetcher,
    private val wynn: WynnApi,
    private val voice: VoiceManager,
    private val rateLimiter: ConnectionRateLimiter = ConnectionRateLimiter(),
    private val handshakeTimeoutSeconds: Int = 10,
) {
    private val bossGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workerGroup = MultiThreadIoEventLoopGroup(0, DefaultThreadFactory("control-worker"), NioIoHandler.newFactory())
    @Volatile private var stopping = false
    private lateinit var channel: Channel

    val boundPort: Int get() = (channel.localAddress() as InetSocketAddress).port

    fun start() {
        NettyEventExecutorMetrics(workerGroup).bindTo(Metrics.registry)
        channel = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) = accept(ch)
            })
            .bind(host, port).sync().channel()
        log.info("Control server listening on {}", channel.localAddress())
    }

    private fun accept(ch: SocketChannel) {
        val ip = ch.remoteAddress().address.hostAddress
        opened.increment()
        rateLimiter.refusal(ip, handshaking.get())?.let { limit ->
            log.debug("Rate limited {}: {}", ip, limit)
            rateLimited.getValue(limit).increment()
            closed.getValue(CloseReason.RATE_LIMITED).increment()
            ch.close()
            return
        }
        open.incrementAndGet()
        handshaking.incrementAndGet()
        val handler = ControlHandler(sessions, wynn, voice) { handshaking.decrementAndGet() }
        ch.closeFuture().addListener {
            rateLimiter.release(ip)
            open.decrementAndGet()
            if (!handler.authenticated) handshaking.decrementAndGet()
            closed.getValue(if (stopping) CloseReason.SERVER_SHUTDOWN else handler.closeReason).increment()
        }
        ch.pipeline().addLast(ReadTimeoutHandler(handshakeTimeoutSeconds))
        ch.pipeline().addLast(ControlMetrics)
        VoicePipeline.install(ch.pipeline())
        ch.pipeline().addLast(ControlMetrics)
        ch.pipeline().addLast(handler)
    }

    fun awaitClose() {
        channel.closeFuture().sync()
    }

    fun stop() {
        stopping = true
        channel.close().sync()
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
    }

    /** Sits below the codec for frame bytes and above it for packet types; sharable, so one instance serves both spots. */
    @Sharable
    private object ControlMetrics : ChannelDuplexHandler() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            when (msg) {
                is ByteBuf -> bytesIn.increment(msg.readableBytes().toDouble())
                is Packet -> packetsIn[msg.javaClass]?.increment()
            }
            ctx.fireChannelRead(msg)
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            when (msg) {
                is ByteBuf -> bytesOut.increment(msg.readableBytes().toDouble())
                is Packet -> packetsOut[msg.javaClass]?.increment()
            }
            ctx.write(msg, promise)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ControlServer::class.java)
        /** Process-wide so the gauges stay right however many servers a test JVM creates. */
        private val handshaking = AtomicInteger()
        private val open = AtomicInteger()
        private val opened = Metrics.registry.counter("voice_control_connections_opened_total")
        private val bytesIn = Metrics.registry.counter("voice_control_bytes_total", "direction", "in")
        private val bytesOut = Metrics.registry.counter("voice_control_bytes_total", "direction", "out")
        private val packetsIn = packetCounters("in")
        private val packetsOut = packetCounters("out")

        private fun packetCounters(direction: String) = Packet::class.sealedSubclasses.associate {
            it.java to Metrics.registry.counter("voice_control_packets_total", "direction", direction, "type", it.simpleName!!)
        }

        init {
            Gauge.builder("voice_control_connections", handshaking) { it.get().toDouble() }.tag("state", "handshaking").register(Metrics.registry)
            Gauge.builder("voice_control_connections") { open.get() - handshaking.get() }.tag("state", "authenticated").register(Metrics.registry)
        }
        private val closed = Metrics.registry.counters<CloseReason>("voice_control_connections_closed_total", "reason")
        private val rateLimited = Metrics.registry.counters<ConnectionRateLimiter.Limit>("voice_control_rate_limited_total", "limit")
    }
}
