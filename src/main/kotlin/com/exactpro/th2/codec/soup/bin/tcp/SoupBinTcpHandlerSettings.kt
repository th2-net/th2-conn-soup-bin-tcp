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

import com.exactpro.th2.codec.soup.bin.tcp.SoupBinTcpHandler.Companion.PASSWORD_LENGTH
import com.exactpro.th2.codec.soup.bin.tcp.SoupBinTcpHandler.Companion.SESSION_LENGTH
import com.exactpro.th2.codec.soup.bin.tcp.SoupBinTcpHandler.Companion.USERNAME_LENGTH
import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel.Security
import com.exactpro.th2.conn.dirty.tcp.core.api.IHandlerSettings
import kotlin.text.Charsets.US_ASCII

data class SoupBinTcpHandlerSettings(
    val host: String,
    val port: Int,
    val security: Security = Security(),
    val username: String,
    val password: String,
    val requestedSession: String = "",
    val requestedSequenceNumber: Long = 0,
    val heartbeatInterval: Long = 1000L,
    val idleTimeout: Long = 15000L,
    val autoReconnect: Boolean = true,
    val reconnectDelay: Long = 5000,
    val maxMessageRate: Int = Int.MAX_VALUE,
) : IHandlerSettings {
    init {
        require(host.isNotBlank()) { "host is empty" }
        require(port in 1..65535) { "invalid port: $port" }
        require(username.isNotBlank()) { "username is empty" }
        require(username.toByteArray(US_ASCII).size <= USERNAME_LENGTH) { "username is too long: $username" }
        require(password.isNotBlank()) { "password is empty" }
        require(password.toByteArray(US_ASCII).size <= PASSWORD_LENGTH) { "password is too long: $password" }
        require(requestedSession.toByteArray(US_ASCII).size <= SESSION_LENGTH) { "requestedSession is too long: $requestedSession" }
        require(heartbeatInterval > 0) { "heartbeatInterval must be positive" }
        require(idleTimeout > 0) { "idleTimeout must be positive" }
        require(reconnectDelay > 0) { "reconnectDelay must be positive" }
        require(maxMessageRate > 0) { "maxMessageRate must be positive" }
    }
}