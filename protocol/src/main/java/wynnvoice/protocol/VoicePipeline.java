package wynnvoice.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;

public final class VoicePipeline {
    private VoicePipeline() {}

    public static void install(ChannelPipeline pipeline) {
        pipeline.addLast(new LengthFieldBasedFrameDecoder(Protocol.MAX_FRAME_BYTES, 0, 4, 0, 4));
        pipeline.addLast(new LengthFieldPrepender(4));
        pipeline.addLast(new Decoder());
        pipeline.addLast(new Encoder());
    }

    private static final class Encoder extends MessageToByteEncoder<Packet> {
        @Override
        protected void encode(ChannelHandlerContext ctx, Packet packet, ByteBuf out) {
            PacketCodec.encode(packet, out);
        }
    }

    private static final class Decoder extends MessageToMessageDecoder<ByteBuf> {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            out.add(PacketCodec.decode(in));
            if (in.isReadable()) throw new DecoderException(in.readableBytes() + " trailing bytes");
        }
    }
}
