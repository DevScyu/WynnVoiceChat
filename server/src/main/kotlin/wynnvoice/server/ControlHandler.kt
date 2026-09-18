package wynnvoice.server

import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.timeout.ReadTimeoutHandler
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID
import wynnvoice.protocol.AuthStatus
import wynnvoice.protocol.Packet
import wynnvoice.protocol.Protocol
import wynnvoice.server.voice.Player
import wynnvoice.server.voice.VoiceManager

class ControlHandler(
    private val sessions: SessionFetcher,
    private val voice: VoiceManager,
    private val onAuthenticated: (ControlHandler) -> Unit = {},
) : SimpleChannelInboundHandler<Packet>() {
    private enum class Stage { HELLO, AUTH, VERIFYING, READY }

    private var stage = Stage.HELLO
    private val serverId = ByteArray(Protocol.SERVER_ID_BYTES).also(RANDOM::nextBytes)
    private var svcCompatVersion = 0
    private var player: Player? = null

    val uuid: UUID? get() = player?.uuid
    val username: String? get() = player?.name
    var modVersion: String = "other"
        private set

    val authenticated get() = stage == Stage.READY

    override fun channelRead0(ctx: ChannelHandlerContext, packet: Packet) {
        when {
            stage == Stage.HELLO && packet is Packet.Hello -> onHello(ctx, packet)
            stage == Stage.AUTH && packet is Packet.Auth -> onAuth(ctx, packet)
            stage == Stage.READY -> onPacket(player!!, packet)
            else -> ctx.close()
        }
    }

    private fun onPacket(player: Player, packet: Packet) {
        when (packet) {
            is Packet.World -> player.world = packet.world.ifEmpty { null }
            is Packet.Position -> player.position = packet
            is Packet.Join -> voice.join(player, svcCompatVersion, packet.tier, packet.instance)
            is Packet.Update -> voice.update(player, packet.tier, packet.instance, packet.svcDisabled)
            else -> log.debug("Unhandled packet from {}: {}", player.name, packet)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        player?.let(voice::leave)
        ctx.fireChannelInactive()
    }

    private fun onHello(ctx: ChannelHandlerContext, hello: Packet.Hello) {
        if (hello.protocolVersion != Protocol.VERSION) {
            refuse(ctx, AuthStatus.VERSION_MISMATCH)
            return
        }
        modVersion = hello.modVersion.takeIf(MOD_VERSION::matches) ?: "other"
        svcCompatVersion = hello.svcCompatVersion
        stage = Stage.AUTH
        ctx.writeAndFlush(Packet.AuthChallenge(serverId))
    }

    private fun onAuth(ctx: ChannelHandlerContext, auth: Packet.Auth) {
        if (!USERNAME.matches(auth.username)) {
            refuse(ctx, AuthStatus.BAD_SESSION)
            return
        }
        if (!voice.config.enabled) {
            refuse(ctx, AuthStatus.DISABLED)
            return
        }
        stage = Stage.VERIFYING
        sessions.hasJoined(auth.username, HexFormat.of().formatHex(serverId)).whenCompleteAsync({ verified, error ->
            when {
                error != null -> {
                    log.warn("Session server unavailable for {}: {}", auth.username, error.message)
                    refuse(ctx, AuthStatus.SESSION_UNAVAILABLE)
                }
                verified != auth.uuid -> refuse(ctx, AuthStatus.BAD_SESSION)
                else -> {
                    player = Player(auth.uuid, auth.username) { ctx.writeAndFlush(it) }
                    stage = Stage.READY
                    ctx.pipeline().get(ReadTimeoutHandler::class.java)?.let(ctx.pipeline()::remove)
                    ctx.writeAndFlush(Packet.AuthResult(AuthStatus.OK))
                    onAuthenticated(this)
                }
            }
        }, ctx.executor())
    }

    private fun refuse(ctx: ChannelHandlerContext, status: AuthStatus) {
        ctx.writeAndFlush(Packet.AuthResult(status)).addListener(ChannelFutureListener.CLOSE)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("Closing {}: {}", ctx.channel().remoteAddress(), cause.toString())
        ctx.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ControlHandler::class.java)
        private val RANDOM = SecureRandom()
        private val USERNAME = Regex("[A-Za-z0-9_]{1,16}")
        private val MOD_VERSION = Regex("\\d+\\.\\d+\\.\\d+")
    }
}
