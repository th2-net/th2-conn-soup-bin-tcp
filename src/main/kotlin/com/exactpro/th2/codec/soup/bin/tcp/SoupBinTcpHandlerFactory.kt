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

import com.exactpro.th2.conn.dirty.tcp.core.api.IHandler
import com.exactpro.th2.conn.dirty.tcp.core.api.IHandlerContext
import com.exactpro.th2.conn.dirty.tcp.core.api.IHandlerFactory
import com.exactpro.th2.conn.dirty.tcp.core.api.IHandlerSettings
import com.google.auto.service.AutoService

@AutoService(IHandlerFactory::class)
class SoupBinTcpHandlerFactory : IHandlerFactory {
    override val name: String = "soup-bin-tcp"
    override val settings: Class<out IHandlerSettings> = SoupBinTcpHandlerSettings::class.java
    override fun create(context: IHandlerContext): IHandler = SoupBinTcpHandler(context)
}