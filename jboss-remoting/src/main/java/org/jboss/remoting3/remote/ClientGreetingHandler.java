/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.remoting3.remote;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.remoting3.RemotingOptions;
import org.jboss.remoting3.spi.ConnectionHandlerFactory;
import org.jboss.xnio.Buffers;
import org.jboss.xnio.IoUtils;
import org.jboss.xnio.Option;
import org.jboss.xnio.OptionMap;
import org.jboss.xnio.Result;
import org.jboss.xnio.channels.MessageHandler;

import javax.security.auth.callback.CallbackHandler;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;

final class ClientGreetingHandler implements MessageHandler {
    private final RemoteConnection connection;
    private final Result<ConnectionHandlerFactory> factoryResult;
    private final CallbackHandler callbackHandler;

    public ClientGreetingHandler(final RemoteConnection connection, final Result<ConnectionHandlerFactory> factoryResult, final CallbackHandler callbackHandler) {
        this.connection = connection;
        this.factoryResult = factoryResult;
        this.callbackHandler = callbackHandler;
    }

    public void handleMessage(final ByteBuffer buffer) {
        List<String> saslMechs = new ArrayList<String>();
        switch (buffer.get()) {
            case RemoteProtocol.GREETING: {
                while (buffer.hasRemaining()) {
                    final byte type = buffer.get();
                    final int len = buffer.get() & 0xff;
                    switch (type) {
                        case RemoteProtocol.GREETING_VERSION: {
                            // We only support version zero, so knowing the other side's version is not useful presently
                            buffer.get();
                            if (len > 1) Buffers.skip(buffer, len - 1);
                            break;
                        }
                        case RemoteProtocol.GREETING_SASL_MECH: {
                            saslMechs.add(Buffers.getModifiedUtf8(Buffers.slice(buffer, len)));
                            break;
                        }
                        default: {
                            // unknown, skip it for forward compatibility.
                            Buffers.skip(buffer, len);
                            break;
                        }
                    }
                }
                // OK now send our authentication request
                final OptionMap optionMap = connection.getOptionMap();
                final String userName = optionMap.get(RemotingOptions.AUTH_USER_NAME);
                final String hostName = connection.getChannel().getPeerAddress().getHostName();
                final Map<String, ?> propertyMap = SaslUtils.createPropertyMap(optionMap);
                final SaslClient saslClient;
                try {
                    saslClient = Sasl.createSaslClient(saslMechs.toArray(new String[saslMechs.size()]), userName == null ? "anonymous" : userName, "remote", hostName, propertyMap, callbackHandler);
                } catch (SaslException e) {
                    factoryResult.setException(e);
                    // todo log exception @ error
                    // todo send "goodbye" & close
                    IoUtils.safeClose(connection);
                    return;
                }
                connection.setMessageHandler(new ClientAuthenticationHandler(connection, saslClient, factoryResult));
                return;
            }
            default: {
                // todo log invalid greeting
                IoUtils.safeClose(connection);
                return;
            }
        }
    }

    public void handleEof() {
        factoryResult.setException(new EOFException("End of file on input"));
        IoUtils.safeClose(connection);
    }

    public void handleException(final IOException e) {
        // todo log it
        factoryResult.setException(e);
        IoUtils.safeClose(connection);
    }
}