package com.ghostsq.commander.sftp;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import ch.ethz.ssh2.SFTPv3Client;
import ch.ethz.ssh2.SFTPv3FileAttributes;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Utils;

class RenEngine extends SFTPEngineBase {
    private Commander    commander;
    private Context      ctx;
    private String       newName; 
    
    public RenEngine( Commander commander_, SFTPAdapter a, Item[] list, String to_name_or_path ) {
        super( a, list );
        commander = commander_;
        ctx = commander.getContext();
        newName = to_name_or_path;
    }
    @Override
    public void run() {
        SFTPv3Client sftp = null;
        try {
            sftp = adapter.getClient();
            Uri u = adapter.getUri();
            String base_path = Utils.mbAddSl( u.getPath() );
            boolean dest_is_dir = newName.indexOf( '/' ) >= 0;
            if( move( sftp, mList, base_path, newName, dest_is_dir ) ) {
                String rep = Utils.getOpReport( ctx, mList.length, Utils.RR.moved.r() );
                sendResult( rep );
                return;
            }
        } catch( Exception e ) {
            error( ctx.getString( Utils.RR.failed.r() ) + e.getLocalizedMessage() );
        } finally {
            if( sftp != null ) sftp.close();
        }
        sendResult( "" );
    }

    public boolean move( SFTPv3Client sftp, Item[] origList, String base_path, String new_name, boolean dest_is_dir ) {
        int ok_cnt = 0;
        for( int i = 0; i < origList.length; i++ ) {
            String old_path = null, new_path = null;
            try {
                String old_name = origList[i].name;
                if( ".".equals( old_name ) || "..".equals( old_name ) )
                    continue;
                old_path = base_path + old_name;
                new_path = dest_is_dir ? new_name + old_name : base_path + new_name;
                if( old_path.equals( new_path ) )
                    continue;
                try {
                    SFTPv3FileAttributes fa = sftp.stat( new_path );
                    int res = askOnFileExist( ctx.getString( Utils.RR.file_exist.r(), new_path ), commander );
                    if( res == Commander.ABORT ) break;
                    if( res == Commander.SKIP )  continue;
                    if( res == Commander.REPLACE ) {
                        if( !dest_is_dir && fa.isDirectory() )
                            sftp.rmdir( new_path );
                        else if( dest_is_dir && fa.isDirectory() && origList[i].dir ) {
                            Item[] sub_items = getItems( old_path );
                            if( move( sftp, sub_items, Utils.mbAddSl( old_path ), Utils.mbAddSl( new_path ), true ) ) {
                                sftp.rmdir( old_path );
                                ok_cnt++;
                                continue;
                            }
                        } else
                            sftp.rm( new_path );
                    }
                } catch( Exception e1 ) {}
                sftp.mv( old_path, new_path );
                ok_cnt++;
            } catch( Exception e ) {
                Log.e( TAG, "Moving from " + old_path + " to " + new_path, e );
                error( ctx.getString( Utils.RR.failed.r() ) + e.getLocalizedMessage() );
            }
        }
        return true;
    }

}
