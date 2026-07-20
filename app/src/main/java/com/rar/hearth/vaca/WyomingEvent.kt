package com.rar.hearth.vaca

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One Wyoming event: JSON header line + optional JSON data block + optional binary payload. */
data class WyomingEvent(
    val type: String,
    val data: JsonObject = JsonObject(emptyMap()),
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is WyomingEvent && type == other.type && data == other.data &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        31 * (31 * type.hashCode() + data.hashCode()) + payload.contentHashCode()
}

object WyomingCodec {
    private val json = Json { ignoreUnknownKeys = true }
    const val WYOMING_VERSION = "1.7.1"
    private const val MAX_HEADER_BYTES = 1 shl 20
    private const val MAX_DATA_LENGTH = 1 shl 20 // 1 MiB
    private const val MAX_PAYLOAD_LENGTH = 10 shl 20 // 10 MiB

    fun write(event: WyomingEvent, out: OutputStream) {
        val dataBytes: ByteArray? = if (event.data.isNotEmpty()) {
            json.encodeToString(JsonObject.serializer(), event.data).toByteArray(Charsets.UTF_8)
        } else {
            null
        }
        val header = buildJsonObject {
            put("type", event.type)
            put("version", WYOMING_VERSION)
            if (dataBytes != null) put("data_length", dataBytes.size)
            if (event.payload.isNotEmpty()) put("payload_length", event.payload.size)
        }
        out.write(json.encodeToString(JsonObject.serializer(), header).toByteArray(Charsets.UTF_8))
        out.write('\n'.code)
        if (dataBytes != null) out.write(dataBytes)
        if (event.payload.isNotEmpty()) out.write(event.payload)
        out.flush()
    }

    /** Null on clean EOF; IOException on garbage headers or mid-frame EOF (framing is unrecoverable). */
    fun read(input: InputStream): WyomingEvent? {
        val line = readLine(input) ?: return null
        val header = try {
            json.parseToJsonElement(line.toString(Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw IOException("malformed wyoming header", e)
        }
        val type: String
        val dataLength: Int
        val payloadLength: Int
        var data: JsonObject
        try {
            type = (header["type"] ?: throw IOException("wyoming header missing type"))
                .jsonPrimitive.content
            data = header["data"] as? JsonObject ?: JsonObject(emptyMap())
            dataLength = header["data_length"]?.jsonPrimitive?.int ?: 0
            payloadLength = header["payload_length"]?.jsonPrimitive?.int ?: 0
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("malformed wyoming header", e)
        }
        if (dataLength < 0 || dataLength > MAX_DATA_LENGTH) {
            throw IOException("wyoming data_length out of range: $dataLength")
        }
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH) {
            throw IOException("wyoming payload_length out of range: $payloadLength")
        }
        if (dataLength > 0) {
            val block = try {
                json.parseToJsonElement(
                    readExactly(input, dataLength).toString(Charsets.UTF_8)
                ).jsonObject
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("malformed wyoming data block", e)
            }
            data = JsonObject(data + block)
        }
        val payload = if (payloadLength > 0) readExactly(input, payloadLength) else ByteArray(0)
        return WyomingEvent(type, data, payload)
    }

    private fun readLine(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (buf.size() == 0) return null
                throw IOException("EOF inside wyoming header")
            }
            if (b == '\n'.code) return buf.toByteArray()
            buf.write(b)
            if (buf.size() > MAX_HEADER_BYTES) throw IOException("wyoming header too long")
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = input.read(out, off, n - off)
            if (read == -1) throw IOException("EOF inside wyoming event body")
            off += read
        }
        return out
    }
}
