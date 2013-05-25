package com.ghostsq.commander.sftp;

import android.content.Context;
import android.os.Handler;

import ch.ethz.ssh2.SFTPv3Client;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Utils;

class RenEngine extends Engine {
    private Context      ctx;
    private String       from, to; 
    private SFTPAdapter  adapter;
    
    public RenEngine( Context ctx_, SFTPAdapter a, String from_, String to_ ) {
        ctx = ctx_;
        adapter = a;
        from = from_;
        to = to_;
    }
    @Override
    public void run() {
        try {
            SFTPv3Client sftp = adapter.getClient();
            sftp.mv( from, to );
            sendProgress( null, Commander.OPERATION_COMPLETED_REFRESH_REQUIRED );
            return;
        } catch( Exception e ) {
            String msg = ctx.getString( Utils.RR.failed.r() ) + e.getLocalizedMessage();
            sendProgress( msg, Commander.OPERATION_FAILED );
        }
    }
}
