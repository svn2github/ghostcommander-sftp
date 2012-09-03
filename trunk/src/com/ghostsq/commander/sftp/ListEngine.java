package com.ghostsq.commander.sftp;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Vector;
import java.util.Comparator;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import ch.ethz.ssh2.KnownHosts;
import ch.ethz.ssh2.SFTPv3Client;
import ch.ethz.ssh2.SFTPv3DirectoryEntry;
import ch.ethz.ssh2.ServerHostKeyVerifier;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.LsItem;
import com.ghostsq.commander.utils.Utils;

class ListEngine extends Engine implements ServerHostKeyVerifier {
    private Context ctx;
    private SFTPAdapter  adapter;
    private LsItem[]     items_tmp = null;
    private String       pass_back_on_done, real_host, crypt;
    
    
    ListEngine( Handler h, SFTPAdapter a, Context ctx_, String pass_back_on_done_ ) {
        super( h );
        ctx = ctx_;
        adapter = a;
        pass_back_on_done = pass_back_on_done_;
    }
    public LsItem[] getItems() {
        return items_tmp;
    }       
    @Override
    public void run() {
        try {
            Log.i( TAG, "ListEngine started" );
            threadStartedAt = System.currentTimeMillis();
            
            Credentials crd = adapter.getCredentials();
            int cl_res = adapter.connectAndLogin( this );
            if( cl_res < 0 ) {
                if( cl_res == SFTPAdapter.NO_LOGIN ) 
                    sendLoginReq( adapter.toString(), crd, pass_back_on_done );
                else
                    sendProgress( null, Commander.OPERATION_FAILED, pass_back_on_done );
                return;
            }
            if( cl_res == SFTPAdapter.LOGGED_IN )
                sendProgress( ctx.getString( Utils.RR.ftp_connected.r(),  
                        real_host + " (" + crypt + ")", crd.getUserName() ), Commander.OPERATION_STARTED );

            SFTPv3Client client = adapter.getClient();
            if( client != null ) {
                Uri u = adapter.getUri();
                String path = u.getPath();
                if( !Utils.str( path ) ) path = File.separator;
                @SuppressWarnings("unchecked")
                Vector<SFTPv3DirectoryEntry> list = client.ls( path );  // obtain the list of entries
                if( list != null ) {
                    int num = list.size(), cnt = 0;
                    int mode = adapter.getSortMode();
                    boolean hide = ( mode & CommanderAdapter.MODE_HIDDEN ) == CommanderAdapter.HIDE_MODE;
                    for( int i = 0; i < num; i++ ) 
                        if( toShow( list.get(i), hide ) ) cnt++;
                    
                    items_tmp = null;
                    items_tmp = LsItem.createArray( cnt );
                    if( cnt > 0 ) {
                        cnt = 0;
                        for( int i = 0; i < num; i++ ) {
                            SFTPv3DirectoryEntry e = list.get(i);
                            if( toShow( e, hide ) ) {
                                items_tmp[cnt++] = new LsItem( e.longEntry );
                                //Log.v( TAG, e.longEntry );
                            }
                        }
                        LsItem item = items_tmp[0];
                        com.ghostsq.commander.utils.LsItem.LsItemPropComparator cmpr;
                        
                        cmpr = item.new LsItemPropComparator( 
                                     mode & CommanderAdapter.MODE_SORTING, 
                                    (mode & CommanderAdapter.MODE_CASE) != 0, 
                                    (mode & CommanderAdapter.MODE_SORT_DIR) == 0 );
                        Arrays.sort( items_tmp, cmpr );
                    }
                    sendProgress( null, Commander.OPERATION_COMPLETED, pass_back_on_done );
                    return;
                }
                Log.e( TAG, "Can't get the items list" );
            }
        }
        catch( IOException e ) {
            e.printStackTrace();
        }
        catch( Exception e ) {
            e.printStackTrace();
        }
        finally {
            super.run();
        }
        adapter.disconnect();
        sendProgress( null, Commander.OPERATION_FAILED, pass_back_on_done );
    }
    protected final boolean toShow( SFTPv3DirectoryEntry entry, boolean hide ) {
        String fn = entry.filename;
        if( Utils.str( fn ) ) {
            if( hide && fn.charAt( 0 ) == '.' ) return false;
            if(  "/.".equals( fn ) ||
                "/..".equals( fn ) ||
                  ".".equals( fn ) ||
                 "..".equals( fn ) ) return false;
        }
        return true;
    }

    // ServerHostKeyVerifier implemenation
    public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey)
            throws Exception
    {
        real_host = hostname;
        crypt = serverHostKeyAlgorithm;
        return true; // the user is responsible where he does connect to, ok?
    }
}
