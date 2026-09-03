package com.sap.sailing.domain.igtimiadapter;

import java.net.InetSocketAddress;

public interface LiveDataConnection {
    /**
     * Disconnects this connection. Afterwards, the connection will no longer feed live data to its listener(s).
     */
    void stop() throws Exception;

    /**
     * Waits for a successful connection for <code>timeoutInMillis</code> milliseconds. If the connection is successful
     * within this time, <code>true</code> is returned; <code>false</code> otherwise.
     * 
     * @param timeoutInMillis
     *            use 0 to wait indefinitely
     */
    boolean waitForConnection(long timeoutInMillis) throws InterruptedException;
    
    /**
     * Returns whether the connection has successfully completed its initial handshake.
     */
    boolean isConnected();
    
    void addListener(BulkFixReceiver listener);
    
    void removeListener(BulkFixReceiver listener);
    
    InetSocketAddress getRemoteAddress();
}
