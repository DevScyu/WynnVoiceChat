package wynnvoice.server

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.timeout.ReadTimeoutHandler
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import wynnvoice.protocol.VoicePipeline

class ControlServer(
    private val host: String,
    private val port: Int,
    private val sessions: SessionFetcher,
    private val rateLimiter: ConnectionRateLimiter = ConnectionRateLimiter(),
    private val handshakeTimeoutSeconds: Int = 10,
) {
    private val bossGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workerGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
    private val handshaking = AtomicInteger()
    private lateinit var channel: Channel

    val boundPort: Int get() = (channel.localAddress() as InetSocketAddress).port

    fun start() {
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
        if (!rateLimiter.tryAcquire(ip, handshaking.get())) {
            log.debug("Rate limited {}", ip)
            ch.close()
            return
        }
        handshaking.incrementAndGet()
        val handler = ControlHandler(sessions) { handshaking.decrementAndGet() }
        ch.closeFuture().addListener {
            rateLimiter.release(ip)
            if (!handler.authenticated) handshaking.decrementAndGet()
        }
        ch.pipeline().addLast(ReadTimeoutHandler(handshakeTimeoutSeconds))
        VoicePipeline.install(ch.pipeline())
        ch.pipeline().addLast(handler)
    }

    fun awaitClose() {
        channel.closeFuture().sync()
    }

    fun stop() {
        channel.close().sync()
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ControlServer::class.java)
    }
}
