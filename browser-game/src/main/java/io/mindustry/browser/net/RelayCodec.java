package io.mindustry.browser.net;

import arc.func.Cons;
import arc.util.io.ByteBufferInput;
import arc.util.io.ByteBufferOutput;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.net.Net;
import mindustry.net.Packet;

import java.nio.ByteBuffer;

/**
 * Encodes Mindustry packets for the co-op relay using ArcNet's framing,
 * {@code [id u8][length u16][compression u8 = 0][body]}, so a JVM server can
 * later read the same bytes with {@code ArcNetProvider.PacketSerializer}.
 * Several packets are concatenated into one WebSocket message.
 */
final class RelayCodec{
    /** Largest packet body; stream chunks are far smaller. */
    static final int maxBody = 32768;

    private final ByteBuffer body = ByteBuffer.allocate(maxBody);
    private final Writes writes = new Writes(new ByteBufferOutput(body));
    private final Reads reads = new Reads(new ByteBufferInput(body));

    /** Appends one framed packet to the outbox. */
    void write(Packet packet, Outbox out){
        body.position(0);
        body.limit(body.capacity());
        packet.write(writes);
        int length = body.position();
        out.ensure(4 + length);
        out.put(Net.getPacketId(packet));
        out.put((byte)(length >> 8));
        out.put((byte)length);
        out.put((byte)0);
        out.put(body.array(), 0, length);
    }

    /**
     * Reads every packet in a batch.
     *
     * @param server whether this side is the server, to reject packets only a server may send
     */
    void read(byte[] batch, boolean server, Cons<Packet> consumer){
        int position = 0;
        while(position < batch.length){
            if(batch.length - position < 4) throw new IllegalArgumentException("Truncated packet header");
            byte id = batch[position];
            int length = ((batch[position + 1] & 0xff) << 8) | (batch[position + 2] & 0xff);
            if(batch[position + 3] != 0) throw new IllegalArgumentException("Compressed relay packets are not supported");
            position += 4;
            if(length > batch.length - position) throw new IllegalArgumentException("Truncated packet body");

            Packet packet = Net.newPacket(id);
            if(!packet.allow(server)) throw new IllegalArgumentException("Invalid packet type for endpoint: " + packet.getClass());
            body.position(0);
            body.limit(length);
            body.put(batch, position, length);
            body.position(0);
            packet.read(reads, length);
            position += length;
            consumer.get(packet);
        }
    }

    /** A growable byte buffer for one destination's pending packets. */
    static final class Outbox{
        byte[] data = new byte[4096];
        int size;

        void ensure(int extra){
            if(size + extra <= data.length) return;
            byte[] grown = new byte[Math.max(data.length * 2, size + extra)];
            System.arraycopy(data, 0, grown, 0, size);
            data = grown;
        }

        void put(byte value){
            data[size++] = value;
        }

        void put(byte[] bytes, int offset, int length){
            System.arraycopy(bytes, offset, data, size, length);
            size += length;
        }

        boolean isEmpty(){
            return size == 0;
        }

        void clear(){
            size = 0;
            // Do not keep a world-sized buffer around after a join.
            if(data.length > 1 << 20) data = new byte[4096];
        }
    }
}
