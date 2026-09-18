package wynnvoice.server

import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.DecoderException
import io.netty.handler.timeout.ReadTimeoutException
import io.netty.handler.timeout.ReadTimeoutHandler
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID
import wynnvoice.protocol.AuthStatus
import wynnvoice.protocol.Packet
import wynnvoice.protocol.Protocol
import wynnvoice.server.Metrics.counters
import wynnvoice.server.voice.Player
import wynnvoice.server.voice.VoiceManager

class ControlHandler(
    private val sessions: SessionFetcher,
    private val wynn: WynnApi,
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
    var closeReason = CloseReason.CLIENT_CLOSED
        private set

    val authenticated get() = stage == Stage.READY

    override fun channelRead0(ctx: ChannelHandlerContext, packet: Packet) {
        when {
            stage == Stage.HELLO && packet is Packet.Hello -> onHello(ctx, packet)
            stage == Stage.AUTH && packet is Packet.Auth -> onAuth(ctx, packet)
            stage == Stage.READY -> onPacket(ctx, player!!, packet)
            else -> close(ctx, CloseReason.PROTOCOL_ERROR)
        }
    }

    private fun close(ctx: ChannelHandlerContext, reason: CloseReason) {
        closeReason = reason
        ctx.close()
    }

    private fun onPacket(ctx: ChannelHandlerContext, player: Player, packet: Packet) {
        when (packet) {
            is Packet.World -> onWorld(ctx, player, packet.world.ifEmpty { null })
            is Packet.Position -> player.position = packet
            is Packet.Join -> voice.join(player, svcCompatVersion, packet.tier, packet.instance)
            is Packet.Update -> voice.update(player, packet.tier, packet.instance, packet.svcDisabled)
            is Packet.Social -> player.apply(packet)
            is Packet.Block -> voice.block(player, packet.targetName, packet.blocked)
            is Packet.BlockList -> voice.blockList(player)
            is Packet.Report -> voice.report(player, packet.targetName, packet.reason)
            else -> log.debug("Unhandled packet from {}: {}", player.name, packet)
        }
    }

    /** The claim takes effect at once; Wynncraft's verdict lands on the event loop later and only counts while the claim stands. */
    private fun onWorld(ctx: ChannelHandlerContext, player: Player, world: String?) {
        player.world = world
        player.worldRefusal = null
        if (world == null) return
        wynn.worldCheck(player.uuid, world).thenAcceptAsync({ verdict ->
            if (verdict.refuses && player.world == world) voice.worldMismatch(player, "Wynncraft does not show you on $world")
        }, ctx.executor())
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
                // ponytail: one indexed SQLite read per login on the event loop; move onto the fetcher's thread if logins pile up
                voice.moderation.isBanned(auth.uuid) -> refuse(ctx, AuthStatus.BANNED)
                !voice.config.allows(auth.uuid) -> refuse(ctx, AuthStatus.NOT_ALLOWED)
                else -> {
                    val verifiedPlayer = Player(auth.uuid, auth.username, modVersion) { ctx.writeAndFlush(it) }
                    player = verifiedPlayer
                    stage = Stage.READY
                    ctx.pipeline().get(ReadTimeoutHandler::class.java)?.let(ctx.pipeline()::remove)
                    authResults.getValue(AuthStatus.OK).increment()
                    ctx.writeAndFlush(Packet.AuthResult(AuthStatus.OK))
                    voice.connected(verifiedPlayer)
                    wynn.membersOf(auth.uuid).thenAccept { verifiedPlayer.guildMembers = it }
                    onAuthenticated(this)
                }
            }
        }, ctx.executor())
    }

    private fun refuse(ctx: ChannelHandlerContext, status: AuthStatus) {
        authResults.getValue(status).increment()
        closeReason = CloseReason.AUTH_FAILED
        ctx.writeAndFlush(Packet.AuthResult(status)).addListener(ChannelFutureListener.CLOSE)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("Closing {}: {}", ctx.channel().remoteAddress(), cause.toString())
        if (cause is DecoderException) decodeErrors.increment()
        close(ctx, if (cause is ReadTimeoutException) CloseReason.TIMED_OUT else CloseReason.PROTOCOL_ERROR)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ControlHandler::class.java)
        private val authResults = Metrics.registry.counters<AuthStatus>("voice_auth_total", "result")
        private val decodeErrors = Metrics.registry.counter("voice_control_decode_errors_total")
        private val RANDOM = SecureRandom()
        private val USERNAME = Regex("[A-Za-z0-9_]{1,16}")
        private val MOD_VERSION = Regex("\\d+\\.\\d+\\.\\d+")
    }
}
