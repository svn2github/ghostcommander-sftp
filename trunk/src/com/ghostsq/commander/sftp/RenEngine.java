package com.ghostsq.commander.sftp;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;

import ch.ethz.ssh2.SFTPv3Client;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Utils;

class RenEngine extends Engine {
    private Context      ctx;
    private Item[]       origList;
    private String       newName; 
    private SFTPAdapter  adapter;
    
    public RenEngine( Context ctx_, SFTPAdapter a, Item[] list, String to_name_or_path ) {
        ctx = ctx_;
        adapter = a;
        origList = list;
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
            for( int i = 0; i < origList.length; i++ ) {
                String old_name = origList[i].name;
                String old_path = base_path + old_name;
                String new_path = dest_is_dir ? newName + old_name : base_path + newName;
                sftp.mv( old_path, new_path );
            }
            sendProgress( null, Commander.OPERATION_COMPLETED_REFRESH_REQUIRED );
            return;
        } catch( Exception e ) {
            String msg = ctx.getString( Utils.RR.failed.r() ) + e.getLocalizedMessage();
            sendProgress( msg, Commander.OPERATION_FAILED );
        } finally {
            if( sftp != null ) sftp.close();
        }
    }
}
