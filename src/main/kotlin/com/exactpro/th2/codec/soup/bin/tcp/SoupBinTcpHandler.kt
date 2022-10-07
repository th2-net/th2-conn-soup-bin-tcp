/*
 * Copyright 2022-2022 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.codec.soup.bin.tcp

import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel
import com.exactpro.th2.conn.dirty.tcp.core.api.IHandler
import com.exactpro.th2.conn.dirty.tcp.core.api.IHandlerContext
import com.exactpro.th2.conn.dirty.tcp.core.util.eventId
import com.exactpro.th2.conn.dirty.tcp.core.util.toByteBuf
import com.exactpro.th2.conn.dirty.tcp.core.util.toErrorEvent
import com.exactpro.th2.conn.dirty.tcp.core.util.toEvent
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong
import kotlin.text.Charsets.US_ASCII

class SoupBinTcpHandler(private val context: IHandlerContext) : IHandler {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var heartbeatTask: Future<*> = CompletableFuture.completedFuture(null)
    @Volatile private var heartbeatTimeoutTask: Future<*> = CompletableFuture.completedFuture(null)
    @Volatile private var lastActivityTime = 0L
    @Volatile private var session: String = ""
    private var sequenceNumber = AtomicLong()
    @Volatile var isLoggedIn = false
    @Volatile lateinit var channel: IChannel

    private val settings = checkNotNull(context.settings as? SoupBinTcpHandlerSettings) {
        "settings is not an instance of ${SoupBinTcpHandlerSettings::class.simpleName}"
    }

    override fun onStart() {
        channel = context.createChannel(
            InetSocketAddress(settings.host, settings.port),
            settings.security,
            mapOf(),
            settings.autoReconnect,
            settings.reconnectDelay,
            settings.maxMessageRate
        )

        channel.open()
    }

    override fun onOpen(channel: IChannel) {
        val session = session.ifEmpty { settings.requestedSession }
        val sequenceNumber = if (sequenceNumber.get() > 0) sequenceNumber.get() else settings.requestedSequenceNumber
        sendLoginRequest(session, sequenceNumber)
    }

    override fun onReceive(channel: IChannel, buffer: ByteBuf): ByteBuf? {
        val readableBytes = buffer.readableBytes()
        if (readableBytes < UShort.SIZE_BYTES) return null
        val packetLength = buffer.getUnsignedShort(buffer.readerIndex())
        if (readableBytes - UShort.SIZE_BYTES < packetLength) return null
        return buffer.readRetainedSlice(UShort.SIZE_BYTES + packetLength)
    }

    override fun onIncoming(channel: IChannel, message: ByteBuf): Map<String, String> {
        lastActivityTime = System.currentTimeMillis()
        val packetType = message.skipBytes(UShort.SIZE_BYTES).readByte().toInt()

        LOGGER.debug { "Received packet - type: ${packetType.toChar()}, session: $session, sequence number: $sequenceNumber" }

        when (packetType) {
            LOGIN_ACCEPTED_TYPE -> handleLoginAccepted(message)
            LOGIN_REJECTED_TYPE -> handleLoginRejected(message)
            SERVER_HEARTBEAT_TYPE -> handleServerHeartbeat()
            SEQUENCED_DATA_TYPE -> handleSequencedData()
            END_OF_SESSION_TYPE -> handleEndOfSession()
        }

        return mapOf()
    }

    override fun onClose(channel: IChannel) {
        heartbeatTask.cancel(true)
        heartbeatTimeoutTask.cancel(true)
        isLoggedIn = false
    }

    private fun handleSequencedData() {
        LOGGER.debug { "Received sequenced data - session: $session" }
        if (isLoggedIn) sequenceNumber.incrementAndGet()
    }

    override fun send(message: RawMessage): CompletableFuture<MessageID> {
        if (!channel.isOpen) channel.open().get()

        while (channel.isOpen && !isLoggedIn) {
            LOGGER.warn { "Waiting for login to send a message" }
            Thread.sleep(1000)
        }

        return channel.send(message.body.toByteBuf(), message.metadata.propertiesMap, message.eventId)
    }

    override fun close() {
        if (::channel.isInitialized && channel.isOpen) {
            if (isLoggedIn) sendLogoutRequest()
            channel.close().exceptionally { LOGGER.error(it) { "Failed to close channel - session: $session" } }
        }

        executor.shutdown()

        if (!executor.awaitTermination(5, SECONDS)) {
            LOGGER.warn { "Failed to shutdown executor in 5 seconds" }
            executor.shutdownNow()
        }
    }

    private fun sendLoginRequest(session: String, sequenceNumber: Long) = Unpooled.buffer().run {
        LOGGER.info { "Sending login request - session: $session, sequence number: $sequenceNumber" }

        writeShort(LOGIN_REQUEST_LENGTH)
        writeByte(LOGIN_REQUEST_TYPE)
        writeField(settings.username, USERNAME_LENGTH)
        writeField(settings.password, PASSWORD_LENGTH)
        writeField(session, SESSION_LENGTH)
        writeField(sequenceNumber, SEQUENCE_NUMBER_LENGTH)

        channel.send(this).get()
        onInfo("Sent login request - session: $session, sequence number: $sequenceNumber")
    }

    private fun sendClientHeartbeat() = Unpooled.buffer().run {
        if (!channel.isOpen) return
        LOGGER.debug { "Sending client heartbeat - session: $session" }
        writeShort(EMPTY_MESSAGE_LENGTH)
        writeByte(CLIENT_HEARTBEAT_TYPE)
        channel.send(this).get()
        LOGGER.info { "Sent client heartbeat - session: $session" }
    }

    private fun sendLogoutRequest() = Unpooled.buffer().run {
        LOGGER.info { "Sending logout request - session: $session" }
        writeShort(EMPTY_MESSAGE_LENGTH)
        writeByte(LOGOUT_REQUEST_TYPE)
        channel.send(this).get()
        onInfo("Sent logout request - session: $session")
    }

    private fun handleLoginAccepted(message: ByteBuf) {
        isLoggedIn = true
        session = message.readField(SESSION_LENGTH, String::toString)
        sequenceNumber.set(message.readField(SEQUENCE_NUMBER_LENGTH, String::toLong))
        heartbeatTask = executor.scheduleAtFixedRate(::sendClientHeartbeat, settings.heartbeatInterval, settings.heartbeatInterval, MILLISECONDS)
        heartbeatTimeoutTask = executor.scheduleAtFixedRate(::checkHeartbeatTimeout, 1, 1, SECONDS)
        onInfo("Login accepted - session: $session, sequence number: $sequenceNumber")
    }

    private fun handleLoginRejected(message: ByteBuf) {
        val rejectCode = message.readByte().toInt().toChar()
        onError("Login rejected - reject code: $rejectCode, session: ${settings.requestedSession},")
    }

    private fun handleServerHeartbeat() {
        LOGGER.info { "Received server heartbeat - session: $session" }
    }

    private fun handleEndOfSession() {
        onInfo("End of session: $session")
        sequenceNumber.set(1)
        session = ""
    }

    private fun checkHeartbeatTimeout() {
        if (!channel.isOpen) return
        val idleTime = System.currentTimeMillis() - lastActivityTime
        if (idleTime < settings.idleTimeout) return
        onInfo("Logging out due to idle timeout - session: $session, idle time: $idleTime ms")
        runCatching(::sendLogoutRequest).onFailure { onError("Failed to logout - session: $session", it) }
        channel.close().exceptionally { onError("Failed to disconnect - session: $session", it) }
        executor.schedule({ if (!channel.isOpen) channel.open() }, settings.reconnectDelay, MILLISECONDS)
    }

    private fun onInfo(event: String) {
        LOGGER.info { event }
        context.send(event.toEvent())
    }

    private fun onError(event: String, cause: Throwable? = null) {
        LOGGER.error(cause) { event }
        context.send(event.toErrorEvent(cause))
    }

    companion object {
        private val LOGGER = KotlinLogging.logger {}

        private const val LOGIN_REQUEST_TYPE = 'L'.code
        private const val LOGIN_ACCEPTED_TYPE = 'A'.code
        private const val LOGIN_REJECTED_TYPE = 'J'.code
        private const val SERVER_HEARTBEAT_TYPE = 'H'.code
        private const val CLIENT_HEARTBEAT_TYPE = 'R'.code
        private const val SEQUENCED_DATA_TYPE = 'S'.code
        private const val END_OF_SESSION_TYPE = 'Z'.code
        private const val LOGOUT_REQUEST_TYPE = 'O'.code

        private const val LOGIN_REQUEST_LENGTH = 47
        private const val EMPTY_MESSAGE_LENGTH = 1

        const val USERNAME_LENGTH = 6
        const val PASSWORD_LENGTH = 10
        const val SESSION_LENGTH = 10
        private const val SEQUENCE_NUMBER_LENGTH = 20

        fun ByteBuf.writeField(value: Any, length: Int) {
            writeBytes(value.toString().padStart(length, ' ').take(length).toByteArray(US_ASCII))
        }

        fun <T> ByteBuf.readField(length: Int, convert: (String) -> T): T {
            return ByteArray(length).apply(::readBytes).toString(US_ASCII).trim().run(convert)
        }
    }
}