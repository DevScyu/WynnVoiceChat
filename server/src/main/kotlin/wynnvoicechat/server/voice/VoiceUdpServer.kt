package wynnvoicechat.server.voice

import io.micrometer.core.instrument.binder.netty4.NettyEventExecutorMetrics
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.util.concurrent.DefaultThreadFactory
import java.net.InetSocketAddress
import org.slf4j.LoggerFactory
import wynnvoicechat.server.Metrics

/**
 * UDP endpoint Simple Voice Chat clients talk to. One event loop; [onDatagram] runs on it.
 */
class VoiceUdpServer(
    private val bindAddress: String,
    private val port: Int,
    private val onDatagram: (InetSocketAddress, ByteArray) -> Unit,
) : VoiceTransport {
    private val logger = LoggerFactory.getLogger(VoiceUdpServer::class.java)
    private val group = MultiThreadIoEventLoopGroup(1, DefaultThreadFactory("voice-udp"), NioIoHandler.newFactory())
    private lateinit var channel: Channel

    val boundPort: Int get() = (channel.localAddress() as InetSocketAddress).port

    fun start() {
        NettyEventExecutorMetrics(group).bindTo(Metrics.registry)
        channel = Bootstrap()
            .group(group)
            .channel(NioDatagramChannel::class.java)
            .option(ChannelOption.SO_RCVBUF, 1 shl 20)
            .handler(object : SimpleChannelInboundHandler<DatagramPacket>() {
                override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
                    onDatagram(msg.sender(), ByteBufUtil.getBytes(msg.content()))
                }

                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                    logger.debug("Voice UDP error", cause)
                }
            })
            .bind(bindAddress, port)
            .sync()
            .channel()
        logger.info("Voice UDP listening on {}", channel.localAddress())
    }

    fun stop() {
        if (this::channel.isInitialized) channel.close().syncUninterruptibly()
        group.shutdownGracefully()
    }

    override fun send(address: InetSocketAddress, bytes: ByteArray) {
        channel.writeAndFlush(DatagramPacket(Unpooled.wrappedBuffer(bytes), address))
    }
}
