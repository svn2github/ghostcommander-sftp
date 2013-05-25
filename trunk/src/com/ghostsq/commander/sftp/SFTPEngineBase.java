package com.ghostsq.commander.sftp;

import java.io.IOException;
import java.util.Vector;

import android.content.Context;
import android.os.Handler;

import ch.ethz.ssh2.SFTPv3Client;
import ch.ethz.ssh2.SFTPv3DirectoryEntry;

import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.LsItem;

class SFTPEngineBase extends Engine {
    protected Context         ctx; 
    protected SFTPAdapter     adapter;
    protected SFTPv3Client    sftp;
    protected LsItem[]        mList;
    
    protected SFTPEngineBase( SFTPAdapter a, LsItem[] list ) {
        adapter = a;
        ctx     = adapter.ctx;
        sftp    = adapter.getClient();
        mList   = list;
    }

    protected boolean skip( LsItem f ) {
        String fn = f.getName();
        if(  "/.".equals( fn ) ||
            "/..".equals( fn ) ||
              ".".equals( fn ) ||
             "..".equals( fn ) ) return true;
        return false;
    }
    
    protected LsItem[] getItems( String full_fn ) {
        try {
            Vector<SFTPv3DirectoryEntry> dir_entries = sftp.ls( full_fn );
            if( dir_entries != null ) {
                int num_entries = dir_entries.size();
                LsItem[] subItems = LsItem.createArray( num_entries );
                if( num_entries > 0 )
                    for( int j = 0; j < num_entries; j++ ) {
                         subItems[j] = new LsItem( dir_entries.get(j).longEntry );
                    }
                return subItems;
            }
        } catch( IOException e ) {
            e.printStackTrace();
        }
        error( "No valid entries in " + full_fn );
        return null;
    }
}
