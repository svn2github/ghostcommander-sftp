package com.ghostsq.commander.sftp;

import android.util.Log;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.ConnectionInfo;
import ch.ethz.ssh2.ConnectionMonitor;
import ch.ethz.ssh2.ServerHostKeyVerifier;

public class SFTPConnection extends Connection implements ConnectionMonitor {
    private static final String TAG = "SFTPConnection";
    public final static int WAS_IN = 1;
    public final static int LOGGED_IN = 2;
    public final static int NEUTRAL = 0;
    public final static int NO_CONNECT = -1;
    public final static int NO_LOGIN = -2;

    public SFTPConnection(String hostname, int port) {
        super( hostname, port );
        addConnectionMonitor( this );
    }
    public SFTPConnection(String hostname) {
        super( hostname );
        addConnectionMonitor( this );
    }
    @Override
    public void finalize() {
        Log.d( TAG, "SFTPConnection finalizing..." );
        close();
    }

    @Override
    public void connectionLost(Throwable cause) {
        Log.w( TAG, "connection lost: " + (cause != null ? cause.getMessage() : "") );
        close();
    }
    
    public final ConnectionInfo mbConnect( ServerHostKeyVerifier verifier ) {
        Log.v( TAG, "mbConnect() is called in thread " + Thread.currentThread().getId() );
        try {
            if( this.isAuthenticationComplete() ) {
                Log.v( TAG, "believe it was already connected" );
                return getConnectionInfo();
            }
            Log.v( TAG, "was not connected. trying to connect now" );
            ConnectionInfo ci = connect( verifier );
            Log.v( TAG, "connected " + ci );
            return ci;
        } catch( Throwable e ) {
            Log.e( TAG, "!!! Exception", e );
        }
        close();
        return null;
    }
}

