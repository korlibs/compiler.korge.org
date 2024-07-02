package korlibs.korge.kotlincompiler.socket

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.*
import java.nio.channels.*

class Packet(val type: Int, val data: ByteArray = byteArrayOf()) {
    val buffer = ByteBuffer.wrap(data)

    companion object {
        const val TYPE_COMMAND = 1
        const val TYPE_STDOUT = 2
        const val TYPE_STDERR = 3
        const val TYPE_END = 4
    }

    override fun toString(): String = "Packet(type=$type, data=${data.size})"
}

inline fun Packet(type: Int, initialCapacity: Int = 1024, block: ByteArrayOutputStream.() -> Unit): Packet =
    Packet(type, genBytes(initialCapacity, block))

inline fun genBytes(initialCapacity: Int = 1024, block: ByteArrayOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream(initialCapacity).also(block).toByteArray()

inline fun <T> ByteArray.processBytes(block: ByteArrayInputStream.() -> T): T =
    ByteArrayInputStream(this).run(block)

fun InputStream.readInt(): Int = ByteBuffer.wrap(readNBytes(4)).getInt()
fun InputStream.readLong(): Long = ByteBuffer.wrap(readNBytes(8)).getLong()

fun OutputStream.writeInt(value: Int) {
    write(ByteBuffer.allocate(4).putInt(value).array())
}
fun OutputStream.writeLong(value: Long) {
    write(ByteBuffer.allocate(8).putLong(value).array())
}

fun InputStream.readStringLenListLen(): List<String> {
    val len = readInt()
    return List(len) { readStringLen() }
}

fun InputStream.readStringLen(): String {
    return readNBytes(readInt()).decodeToString()
}

fun OutputStream.writeStringLenListLen(list: List<String>) {
    writeInt(list.size)
    for (s in list) writeStringLen(s)
}

fun OutputStream.writeStringLen(value: String) {
    writeInt(value.length)
    write(value.encodeToByteArray())
}

fun SocketChannel.writePacket(packet: Packet) = synchronized(this) {
    //println("Writing packet=$packet")
    val header = ByteBuffer.allocate(8)
    header.putInt(packet.type)
    header.putInt(packet.data.size)
    header.flip()
    write(arrayOf(header, ByteBuffer.wrap(packet.data)))

    //val pack = ByteBuffer.allocate(8 + packet.data.size)
    //pack.putInt(packet.type)
    //pack.putInt(packet.data.size)
    //pack.put(packet.data)
    //pack.flip()
    //write(pack)
}

fun SocketChannel.readFull(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
        read(buffer)
    }
}

fun SocketChannel.readPacket(): Packet = synchronized(this) {
    val header = ByteBuffer.allocate(8)
    readFull(header)
    header.flip()
    val type = header.int
    val size = header.int
    //println("READ PACKET: type=$type, size=$size")
    val data = ByteBuffer.allocate(size)
    readFull(data)
    return Packet(type, data.array())
}
