package wynnvoicechat.server

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
import kotlin.test.assertTrue
import wynnvoicechat.protocol.AuthStatus
import wynnvoicechat.protocol.Packet
import wynnvoicechat.protocol.Protocol
import wynnvoicechat.protocol.VoicePipeline
import wynnvoicechat.server.voice.VoiceConfig
import wynnvoicechat.server.voice.VoiceManager
import wynnvoicechat.server.voice.testModeration

class ControlServerTest {
    private val uuid = UUID.randomUUID()
    private val voice = VoiceManager(VoiceConfig(true, true, "voice.test", 24454, "127.0.0.1", 32.0, 1000, "build/tmp/reports", 1_000_000),
        testModeration("control-server-test"), { _, _ -> }, { CompletableFuture.completedFuture(null) })
    private val server = ControlServer("127.0.0.1", 0, { _, _ -> CompletableFuture.completedFuture(uuid) },
        WynnApi({ CompletableFuture.completedFuture(null) }), voice,
        ConnectionRateLimiter(maxConcurrentPerIp = 2, maxPerMinutePerIp = 100))
    private val clientGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())

    init {
        server.start()
    }

    private fun sample(series: String) = MetricsTest.sample(Metrics.scrape(), series)!!.toDouble()

    private fun awaitSample(series: String, expected: Double, timeoutMs: Long = 2_000): Double {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (sample(series) != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return sample(series)
    }

    @AfterTest
    fun stop() {
        clientGroup.shutdownGracefully()
        server.stop()
    }

    @Test
    fun `handshake completes over a real socket`() {
        val opened = sample("voice_control_connections_opened_total")
        val closed = sample("voice_control_connections_closed_total{reason=\"client_closed\"}")
        val hellos = sample("voice_control_packets_total{direction=\"in\",type=\"Hello\"}")
        val challenges = sample("voice_control_packets_total{direction=\"out\",type=\"AuthChallenge\"}")
        val bytesIn = sample("voice_control_bytes_total{direction=\"in\"}")

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
        assertEquals(Packet.AuthResult(AuthStatus.OK, 1, ""), received.poll(5, TimeUnit.SECONDS))
        // AuthResult reaches the client before the server thread runs the onAuthenticated hook that moves the gauge
        assertEquals(1.0, awaitSample("voice_control_connections{state=\"authenticated\"}", 1.0))
        channel.close().sync()
        Thread.sleep(200)

        assertEquals(opened + 1, sample("voice_control_connections_opened_total"))
        assertEquals(closed + 1, sample("voice_control_connections_closed_total{reason=\"client_closed\"}"))
        assertEquals(hellos + 1, sample("voice_control_packets_total{direction=\"in\",type=\"Hello\"}"))
        assertEquals(challenges + 1, sample("voice_control_packets_total{direction=\"out\",type=\"AuthChallenge\"}"))
        assertTrue(sample("voice_control_bytes_total{direction=\"in\"}") > bytesIn)
        assertEquals(0.0, sample("voice_control_connections{state=\"authenticated\"}"))
        assertTrue(Metrics.scrape().contains("netty_eventexecutor_tasks_pending{name=\"control-worker-"), "netty binder on the worker group")
    }

    @Test
    fun `connections beyond the per ip limit are dropped at accept`() {
        val limited = sample("voice_control_rate_limited_total{limit=\"concurrent_per_ip\"}")
        val kept = List(2) { Socket("127.0.0.1", server.boundPort) }
        val extra = Socket("127.0.0.1", server.boundPort)
        assertEquals(-1, extra.getInputStream().read())
        kept.forEach { it.close() }
        extra.close()
        assertEquals(limited + 1, sample("voice_control_rate_limited_total{limit=\"concurrent_per_ip\"}"))
    }
}
