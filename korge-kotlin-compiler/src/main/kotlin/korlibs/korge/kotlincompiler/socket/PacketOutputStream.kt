package korlibs.korge.kotlincompiler.socket

import java.io.OutputStream
import java.nio.channels.SocketChannel

class PacketOutputStream(val socket: SocketChannel, val packetType: Int) : OutputStream() {
    override fun write(b: Int) {
        socket.writePacket(Packet(packetType, byteArrayOf(b.toByte())))
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        socket.writePacket(Packet(packetType, b.copyOfRange(off, off + len)))
    }

    override fun flush() {
    }
}