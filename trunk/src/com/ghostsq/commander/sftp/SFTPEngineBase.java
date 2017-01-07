package com.ghostsq.commander.sftp;

import java.io.IOException;
import java.util.Date;
import java.util.List;

import android.content.Context;
import android.util.Log;

import ch.ethz.ssh2.SFTPv3Client;
import ch.ethz.ssh2.SFTPv3DirectoryEntry;
import ch.ethz.ssh2.SFTPv3FileAttributes;

import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Utils;

class SFTPEngineBase extends Engine {
    protected Context         ctx; 
    protected SFTPAdapter     adapter;
    protected SFTPv3Client    sftp;
    protected Item[]          mList;
    
    protected SFTPEngineBase( SFTPAdapter a, Item[] list ) {
        adapter = a;
        ctx     = adapter.ctx;
        //sftp    = adapter.getClient();
        mList   = list;
    }

    protected final boolean skip( Item f ) {
        String fn = f.name;
        if(  "/.".equals( fn ) ||
            "/..".equals( fn ) ||
              ".".equals( fn ) ||
             "..".equals( fn ) ) return true;
        return false;
    }
    
    protected final Item[] getItems( String full_fn ) {
        try {
            if( sftp == null ) sftp = adapter.getClient();
            if( sftp == null ) return null;
            List<SFTPv3DirectoryEntry> dir_entries = sftp.ls( full_fn );
            if( dir_entries != null ) {
                int num_entries = dir_entries.size();
                Item[] subItems = new Item[num_entries];
                if( num_entries > 0 )
                    for( int j = 0; j < num_entries; j++ ) {
                        Entry e = new Entry( dir_entries.get(j) );
                        //SFTPv3DirectoryEntry e = dir_entries.get(j);
                        Item item = new Item( e.getFilename() );
                        subItems[j] = item; 
                        SFTPv3FileAttributes fa = e.getAttributes();
                        item.dir  = fa.isDirectory();
                        if( !item.dir )
                            item.size = fa.size;
                        item.date = new Date( (long)fa.mtime * 1000L );
                        if( fa.isSymlink() ) try {
                            String fpath = Utils.mbAddSl( full_fn ) + item.name;
                            SFTPv3FileAttributes lfa = sftp.stat( fpath );
                            if( lfa.isDirectory() ) item.dir = true;
                        } catch( Exception ex ) {
                            Log.e( TAG, "SFTP link is invalid: " + e.getFilename() + " " + 
                                    ex.getLocalizedMessage() );
                        }
                    }
                return subItems;
            }
        } catch( IOException e ) {
            e.printStackTrace();
        }
        error( "No valid entries in " + full_fn );
        return null;
    }
    
    @Override
    public void finalize() {
        if( sftp != null ) sftp.close();
        sftp = null;
    }
}
