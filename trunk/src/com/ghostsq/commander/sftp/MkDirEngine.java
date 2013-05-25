package com.ghostsq.commander.sftp;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.utils.Utils;

class MkDirEngine extends SFTPEngineBase {
    private String  full_name; 
    public MkDirEngine( SFTPAdapter a, String full_name_ ) {
        super( a, null );
        full_name = full_name_;
    }
    
    @Override
    public void run() {
        try {
            sftp.mkdir( full_name, 0777 );
            sendProgress( null, Commander.OPERATION_COMPLETED_REFRESH_REQUIRED );
            return;
        } catch( Exception e ) {
            String msg = ctx.getString( Utils.RR.cant_md.r(), full_name ) + "\n - " + e.getLocalizedMessage();
            sendProgress( msg, Commander.OPERATION_FAILED );
        }
    }
}
