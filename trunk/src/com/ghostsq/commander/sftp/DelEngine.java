package com.ghostsq.commander.sftp;

import android.os.Handler;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.utils.LsItem;
import com.ghostsq.commander.utils.Utils;

class DelEngine extends SFTPEngineBase {
    private int so_far = 0;
    
    public DelEngine( Handler h, SFTPAdapter a, LsItem[] list ) {
        super( h, a, list );
    }
    @Override
    public void run() {
        try {
            String path = Utils.mbAddSl( adapter.getUri().getPath() );
            int total;
            total = deleteFiles( Utils.mbAddSl( path ), mList );
            sendResult( total > 0 ? "Deleted files/folders: " + total : "Nothing was deleted" );
            super.run();
        } catch( Exception e ) {
            sendProgress( e.getMessage(), Commander.OPERATION_FAILED );
        }
    }
    private final int deleteFiles( String path, LsItem[] l ) throws Exception {
        if( l == null ) return 0;
        int cnt = 0;
        int num = l.length;
        double conv = 100./(double)num;
        for( int i = 0; i < num; i++ ) {
            if( stop || isInterrupted() )
                throw new Exception( ctx.getString( Utils.RR.interrupted.r() ) );
            LsItem f = l[i];
            if( skip( f ) ) continue;
            sendProgress( ctx.getString( Utils.RR.deleting.r(), f.getName() ), so_far, (int)(i * conv) );
            String full_fn = path + f.getName();
            if( f.isDirectory() ) {
                LsItem[] subItems = getItems( full_fn );
                if( subItems == null ) break;
                if( subItems.length > 0 )
                    cnt += deleteFiles( Utils.mbAddSl( full_fn ), subItems );
                sftp.rmdir( full_fn );
            } else
                sftp.rm( full_fn );
            cnt++;
        }
        so_far += cnt; 
        return cnt;
    }
}
