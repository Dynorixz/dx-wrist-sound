package dev.dx.wristsound.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object WearProtocol {
    const val WRIST_PATH = "/wrist_state"
    const val HEARTBEAT_PATH = "/heartbeat"
    const val REQUEST_PATH = "/request_state"
    const val CONFIG_PATH = "/settings"
    const val ALERT_CONFIG_PATH = "/alert_settings"
    const val ALERT_CONFIG_ACK_PATH = "/alert_settings_ack"
    const val AUTHOR_LINK_PATH = "/author_link"
    const val PHONE_CAPABILITY = "dx_wrist_phone"
    const val WATCH_CAPABILITY = "dx_wrist_watch"
    const val HEARTBEAT_INTERVAL = 120_000L
    private const val VERSION = 1

    fun encode(snapshot: WatchSnapshot): ByteArray = write {
        writeInt(VERSION); writeUTF(snapshot.session); writeLong(snapshot.sequence)
        writeUTF(snapshot.state.name); writeUTF(snapshot.sensorName.take(200)); writeBoolean(snapshot.sensorAvailable)
        writeUTF(snapshot.watchName.take(100)); writeLong(snapshot.detectedAt); writeBoolean(snapshot.monitoring)
    }
    fun decodeSnapshot(bytes: ByteArray): WatchSnapshot? = read(bytes) {
        require(readInt() == VERSION)
        WatchSnapshot(readUTF(), readLong(), WristState.valueOf(readUTF()), readUTF(), readBoolean(), readUTF(), readLong(), readBoolean())
            .also { require(it.session.length in 1..64 && it.sequence >= 0) }
    }
    fun encode(config: WatchConfig): ByteArray = write {
        writeInt(2); writeLong(config.debounceMillis); writeBoolean(config.startOnBoot); writeBoolean(config.vibration)
        writeLong(config.offDebounceMillis); writeBoolean(config.phoneLossAlert); writeBoolean(config.phoneLossVoice)
        writeInt(config.phoneLossSeconds)
    }
    fun decodeConfig(bytes: ByteArray): WatchConfig? = read(bytes) {
        val version = readInt()
        require(version in 1..2)
        val base = WatchConfig(readLong(), readBoolean(), readBoolean())
        (if (version == 2) base.copy(offDebounceMillis = readLong(), phoneLossAlert = readBoolean(),
            phoneLossVoice = readBoolean(), phoneLossSeconds = readInt()) else base).also {
            require(it.debounceMillis in 1_000..10_000 && it.offDebounceMillis in 3_000..15_000 && it.phoneLossSeconds in 15..120)
        }
    }
    private fun write(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use(block); output.toByteArray()
    }
    private fun <T> read(bytes: ByteArray, block: DataInputStream.() -> T): T? {
        if (bytes.size > 4096) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { input -> input.block().also { require(input.available() == 0) } }
        }.getOrNull()
    }
}
