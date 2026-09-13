package com.rover.gateway

import java.util.UUID

/**
 * Mirror of firmware/src/proto.h.
 * Commands, packet encoding, telemetry decoding.
 */
object Protocol {

    const val MAGIC_CMD = 0x52
    const val MAGIC_TELEM = 0x54

    object Uuids {
        val SERVICE = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
        val CMD = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
        val TELEMETRY = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
    }

    // Commands (match proto.h)
    const val CMD_DRIVE = 0x01
    const val CMD_STOP = 0x02
    const val CMD_LIGHT = 0x03
    const val CMD_MINE = 0x04
    const val CMD_LEG = 0x05
    const val CMD_PING = 0x06
    const val CMD_RESET = 0x07

    /**
     * Builds a packet [magic][seq][cmd][payload...][xor].
     * Final XOR of all bytes == 0 (matches firmware check).
     */
    fun buildCard(cmd: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(3 + payload.size + 1)
        out[0] = MAGIC_CMD.toByte()
        out[1] = (seq and 0xFF).toByte()
        out[2] = cmd.toByte()
        payload.copyInto(out, 3)
        var x = 0
        for (i in 0 until out.size - 1) x = x xor (out[i].toInt() and 0xFF)
        out[out.size - 1] = x.toByte()
        return out
    }

    fun driveSeq(cmd: Int, seq: Int, speed: Int, steer: Int): ByteArray =
        buildCard(cmd, seq, byteArrayOf(speed.toByte(), steer.toByte()))

    fun singleSeq(cmd: Int, seq: Int, v: Int = 0): ByteArray =
        buildCard(cmd, seq, byteArrayOf(v.toByte()))

    fun stopSeq(cmd: Int, seq: Int): ByteArray =
        buildCard(cmd, seq)

    /** Decodes telemetry from ESP32. */
    data class Telemetry(
        val connected: Boolean,
        val tilted: Boolean,
        val watchdogStop: Boolean,
        val batteryVolts: Int,     // tenths of volts
        val leftPwm: Int,          // 0..100
        val rightPwm: Int,
        val tempC: Int,            // °C
        val ackSeq: Int,           // last acknowledged command
        val ackStatus: Int,
        val tiltTenths: Int,       // tenths of degrees
        val legBits: Int,
    )

    fun decodeTelemetry(data: ByteArray): Telemetry? {
        if (data.size < 10 || (data[0].toInt() and 0xFF) != MAGIC_TELEM) return null
        val flags = data[1].toInt() and 0xFF
        return Telemetry(
            connected = flags and 0x01 != 0,
            tilted = flags and 0x02 != 0,
            watchdogStop = flags and 0x04 != 0,
            batteryVolts = data[2].toInt() and 0xFF,
            leftPwm = data[3].toInt() and 0xFF,
            rightPwm = data[4].toInt() and 0xFF,
            tempC = data[5].toInt(),
            ackSeq = data[6].toInt() and 0xFF,
            ackStatus = data[7].toInt() and 0xFF,
            tiltTenths = data[8].toInt() and 0xFF,
            legBits = data[9].toInt() and 0xFF,
        )
    }
}
