package wynnvoicechat.mod.net;

import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wynnvoicechat.protocol.AuthStatus;
import wynnvoicechat.protocol.Packet;
import wynnvoicechat.protocol.Protocol;
import wynnvoicechat.protocol.VoicePipeline;

public final class VoiceClient {
    public static final int SVC_COMPAT_VERSION = 20;
    private static final Logger LOG = LoggerFactory.getLogger("wynnvoicechat");

    public record Identity(String username, UUID uuid, String modVersion) {}

    public interface SessionJoiner {
        void joinServer(String serverId) throws Exception;
    }

    public interface Listener {
        void onAuthResult(AuthStatus status);

        void onPacket(Packet packet);

        void onClosed();
    }

    private final MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final Identity identity;
    private final SessionJoiner joiner;
    private final Listener listener;
    private Channel channel;

    public VoiceClient(Identity identity, SessionJoiner joiner, Listener listener) {
        this.identity = identity;
        this.joiner = joiner;
        this.listener = listener;
    }

    public void connect(String host, int port) {
        close();
        channel = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        VoicePipeline.install(ch.pipeline());
                        ch.pipeline().addLast(new Handler(identity, joiner, ForkJoinPool.commonPool(), listener));
                    }
                })
                .connect(host, port)
                .addListener(future -> {
                    if (!future.isSuccess()) {
                        LOG.warn("Could not connect to relay {}:{}: {}", host, port, future.cause().toString());
                        listener.onClosed();
                    }
                })
                .channel();
    }

    public void send(Packet packet) {
        if (channel != null && channel.isActive()) channel.writeAndFlush(packet);
    }

    public void close() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    public void shutdown() {
        close();
        group.shutdownGracefully();
    }

    static final class Handler extends SimpleChannelInboundHandler<Packet> {
        private final Identity identity;
        private final SessionJoiner joiner;
        private final Executor joinExecutor;
        private final Listener listener;
        private boolean authenticated;

        Handler(Identity identity, SessionJoiner joiner, Executor joinExecutor, Listener listener) {
            this.identity = identity;
            this.joiner = joiner;
            this.joinExecutor = joinExecutor;
            this.listener = listener;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            ctx.writeAndFlush(new Packet.Hello(Protocol.VERSION, SVC_COMPAT_VERSION, identity.modVersion()));
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Packet packet) {
            switch (packet) {
                case Packet.AuthChallenge challenge -> answerChallenge(ctx, challenge);
                case Packet.AuthResult result -> {
                    authenticated = result.status() == AuthStatus.OK;
                    listener.onAuthResult(result.status());
                }
                default -> {
                    if (authenticated) listener.onPacket(packet);
                }
            }
        }

        private void answerChallenge(ChannelHandlerContext ctx, Packet.AuthChallenge challenge) {
            String serverId = HexFormat.of().formatHex(challenge.serverId());
            CompletableFuture.runAsync(() -> {
                try {
                    joiner.joinServer(serverId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, joinExecutor).whenCompleteAsync((ignored, error) -> {
                if (error != null) {
                    Throwable cause = error;
                    while (cause.getCause() != null) cause = cause.getCause();
                    LOG.warn("Mojang joinServer failed: {}", cause.toString());
                    listener.onAuthResult(cause instanceof AuthenticationUnavailableException
                            ? AuthStatus.SESSION_UNAVAILABLE : AuthStatus.BAD_SESSION);
                    ctx.close();
                } else {
                    ctx.writeAndFlush(new Packet.Auth(identity.username(), identity.uuid()));
                }
            }, ctx.executor());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            listener.onClosed();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.warn("Relay connection error: {}", cause.toString());
            ctx.close();
        }
    }
}
