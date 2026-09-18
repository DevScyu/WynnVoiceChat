package wynnvoice.server

import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import wynnvoice.protocol.AuthStatus
import wynnvoice.protocol.Packet
import wynnvoice.protocol.Protocol
import wynnvoice.protocol.VoicePipeline
import wynnvoice.server.voice.VoiceConfig
import wynnvoice.server.voice.VoiceManager
import wynnvoice.server.voice.testModeration

class ControlServerTest {
    private val uuid = UUID.randomUUID()
    private val voice = VoiceManager(VoiceConfig(true, true, "voice.test", 24454, "127.0.0.1", 32.0, 1000, "build/tmp/reports", 1_000_000),
        testModeration("control-server-test"), { _, _ -> }, { CompletableFuture.completedFuture(null) })
    private val server = ControlServer("127.0.0.1", 0, { _, _ -> CompletableFuture.completedFuture(uuid) },
        GuildResolver({ CompletableFuture.completedFuture(null) }), voice,
        ConnectionRateLimiter(maxConcurrentPerIp = 2, maxPerMinutePerIp = 100))
    private val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())

    init {
        server.start()
    }

    @AfterTest
    fun stop() {
        clientGroup.shutdownGracefully()
        server.stop()
    }

    @Test
    fun `handshake completes over a real socket`() {
        val received = LinkedBlockingQueue<Packet>()
        val channel = Bootstrap().group(clientGroup).channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    VoicePipeline.install(ch.pipeline())
                    ch.pipeline().addLast(object : SimpleChannelInboundHandler<Packet>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, packet: Packet) {
                            received.add(packet)
                        }
                    })
                }
            })
            .connect("127.0.0.1", server.boundPort).sync().channel()

        channel.writeAndFlush(Packet.Hello(Protocol.VERSION, 20, "1.0.0"))
        val challenge = received.poll(5, TimeUnit.SECONDS) as Packet.AuthChallenge
        assertEquals(Protocol.SERVER_ID_BYTES, challenge.serverId.size)

        channel.writeAndFlush(Packet.Auth("Player", uuid))
        assertEquals(Packet.AuthResult(AuthStatus.OK), received.poll(5, TimeUnit.SECONDS))
        channel.close().sync()
    }

    @Test
    fun `connections beyond the per ip limit are dropped at accept`() {
        val kept = List(2) { Socket("127.0.0.1", server.boundPort) }
        val extra = Socket("127.0.0.1", server.boundPort)
        assertEquals(-1, extra.getInputStream().read())
        kept.forEach { it.close() }
        extra.close()
    }
}
