/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.netty.ws;

import com.ning.http.client.websocket.WebSocket;
import com.ning.http.client.websocket.WebSocketByteListener;
import com.ning.http.client.websocket.WebSocketCloseCodeReasonListener;
import com.ning.http.client.websocket.WebSocketListener;
import com.ning.http.client.websocket.WebSocketTextListener;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.jboss.netty.buffer.ChannelBuffers.wrappedBuffer;

public class NettyWebSocket implements WebSocket {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyWebSocket.class);

    private final Channel channel;
    private final ConcurrentLinkedQueue<WebSocketListener> listeners = new ConcurrentLinkedQueue<WebSocketListener>();

    private final StringBuilder textBuffer = new StringBuilder();
    private final ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();

    private int maxBufferSize = 128000000;

    public NettyWebSocket(Channel channel) {
        this.channel = channel;
    }

    @Override
    public WebSocket sendMessage(byte[] message) {
        channel.write(new BinaryWebSocketFrame(wrappedBuffer(message)));
        return this;
    }

    @Override
    public WebSocket stream(byte[] fragment, boolean last) {
        throw new UnsupportedOperationException("Streaming currently only supported by the Grizzly provider.");
    }

    @Override
    public WebSocket stream(byte[] fragment, int offset, int len, boolean last) {
        throw new UnsupportedOperationException("Streaming currently only supported by the Grizzly provider.");
    }

    @Override
    public WebSocket sendTextMessage(String message) {
        channel.write(new TextWebSocketFrame(message));
        return this;
    }

    @Override
    public WebSocket streamText(String fragment, boolean last) {
        throw new UnsupportedOperationException("Streaming currently only supported by the Grizzly provider.");
    }

    @Override
    public WebSocket sendPing(byte[] payload) {
        channel.write(new PingWebSocketFrame(wrappedBuffer(payload)));
        return this;
    }

    @Override
    public WebSocket sendPong(byte[] payload) {
        channel.write(new PongWebSocketFrame(wrappedBuffer(payload)));
        return this;
    }

    @Override
    public WebSocket addWebSocketListener(WebSocketListener l) {
        listeners.add(l);
        return this;
    }

    @Override
    public WebSocket removeWebSocketListener(WebSocketListener l) {
        listeners.remove(l);
        return this;
    }

    public int getMaxBufferSize() {
    	return maxBufferSize;
    }
    
    public void setMaxBufferSize(int bufferSize) {
    	maxBufferSize = bufferSize;
    	
    	if(maxBufferSize < 8192)
    		maxBufferSize = 8192;
    }
    
    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() {
        if (channel.isOpen()) {
            onClose();
            listeners.clear();
            channel.write(new CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE);
        }
    }

    public void close(int statusCode, String reason) {
        onClose(statusCode, reason);
        listeners.clear();
    }

    public void onBinaryFragment(byte[] message, boolean last) {

        if (!last) {
            try {
                byteBuffer.write(message);
            } catch (Exception ex) {
                byteBuffer.reset();
                onError(ex);
                return;
            }

            if (byteBuffer.size() > maxBufferSize) {
                byteBuffer.reset();
                Exception e = new Exception("Exceeded Netty Web Socket maximum buffer size of " + maxBufferSize);
                onError(e);
                close();
                return;
            }
        }

        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketByteListener) {
                WebSocketByteListener byteListener = (WebSocketByteListener) listener;
                try {
                    if (!last) {
                        byteListener.onFragment(message, last);
                    } else if (byteBuffer.size() > 0) {
                        byteBuffer.write(message);
                        byteListener.onFragment(message, last);
                        byteListener.onMessage(byteBuffer.toByteArray());
                    } else {
                        byteListener.onMessage(message);
                    }
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        }

        if (last) {
            byteBuffer.reset();
        }
    }

    public void onTextFragment(String message, boolean last) {

        if (!last) {
            textBuffer.append(message);

            if (textBuffer.length() > maxBufferSize) {
                textBuffer.setLength(0);
                Exception e = new Exception("Exceeded Netty Web Socket maximum buffer size of " + maxBufferSize);
                onError(e);
                close();
                return;
            }
        }

        for (WebSocketListener listener : listeners) {
            if (listener instanceof WebSocketTextListener) {
                WebSocketTextListener textlistener = (WebSocketTextListener) listener;
                try {
                    if (!last) {
                        textlistener.onFragment(message, last);
                    } else if (textBuffer.length() > 0) {
                        textlistener.onFragment(message, last);
                        textlistener.onMessage(textBuffer.append(message).toString());
                    } else {
                        textlistener.onMessage(message);
                    }
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        }

        if (last) {
            textBuffer.setLength(0);
        }
    }

    public void onError(Throwable t) {
        for (WebSocketListener listener : listeners) {
            try {
                listener.onError(t);
            } catch (Throwable t2) {
                LOGGER.error("", t2);
            }
        }
    }

    protected void onClose() {
        onClose(1000, "Normal closure; the connection successfully completed whatever purpose for which it was created.");
    }

    public void onClose(int code, String reason) {
        for (WebSocketListener l : listeners) {
            try {
                if (l instanceof WebSocketCloseCodeReasonListener) {
                    WebSocketCloseCodeReasonListener.class.cast(l).onClose(this, code, reason);
                }
                l.onClose(this);
            } catch (Throwable t) {
                l.onError(t);
            }
        }
    }

    @Override
    public String toString() {
        return "NettyWebSocket{channel=" + channel + '}';
    }
}