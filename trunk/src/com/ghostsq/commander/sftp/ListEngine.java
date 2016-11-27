package com.ghostsq.commander.sftp;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import ch.ethz.ssh2.SFTPv3Client;
import ch.ethz.ssh2.SFTPv3DirectoryEntry;
import ch.ethz.ssh2.SFTPv3FileAttributes;
import ch.ethz.ssh2.ServerHostKeyVerifier;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapter.Item;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.adapters.ItemComparator;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.LsItem;
import com.ghostsq.commander.utils.Utils;

class ListEngine extends Engine implements ServerHostKeyVerifier {
    private Context ctx;
    private SFTPAdapter  adapter;
    private Item[]       items_tmp = null;
    private String       pass_back_on_done, real_host, crypt;
    
    
    ListEngine( Handler h, SFTPAdapter a, Context ctx_, String pass_back_on_done_ ) {
        super.setHandler( h );
        ctx = ctx_;
        adapter = a;
        pass_back_on_done = pass_back_on_done_;
    }
    public Item[] getItems() {
        return items_tmp;
    }       
    @Override
    public void run() {
        SFTPv3Client client = null;
        try {
            Log.i( TAG, "ListEngine started" );
            threadStartedAt = System.currentTimeMillis();
            
            Credentials crd = adapter.getCredentials();
            int cl_res = adapter.connectAndLogin( this );
            Log.d( TAG, "connectAndLogin() returned " + cl_res );
            if( cl_res < 0 ) {
                if( cl_res == SFTPConnection.NO_LOGIN ) 
                    sendLoginReq( adapter.toString(), crd, pass_back_on_done );
                else
                    sendProgress( null, Commander.OPERATION_FAILED, pass_back_on_done );
                return;
            }
            if( cl_res == SFTPConnection.LOGGED_IN )
                sendProgress( ctx.getString( Utils.RR.ftp_connected.r(),  
                        real_host + " (" + crypt + ")", crd.getUserName() ), Commander.OPERATION_STARTED );

            client = adapter.getClient();
            if( client != null ) {
                Uri u = adapter.getUri();
                String path = u.getPath();
                if( !Utils.str( path ) ) path = File.separator;
                @SuppressWarnings("unchecked")
                List<SFTPv3DirectoryEntry> list = client.ls( path );  // obtain the list of entries
                if( list != null ) {
                    int num = list.size(), cnt = 0;
                    int mode = adapter.getSortMode();
                    boolean hide = ( mode & CommanderAdapter.MODE_HIDDEN ) == CommanderAdapter.HIDE_MODE;
                    for( int i = 0; i < num; i++ ) 
                        if( toShow( list.get(i), hide ) ) cnt++;
                    
                    items_tmp = null;
                    items_tmp = new Item[cnt];
                    if( cnt > 0 ) {
                        cnt = 0;
                        
                        for( int i = 0; i < num; i++ ) {
                            Entry e = new Entry( list.get(i) );
                            if( toShow( e, hide ) ) {
                                Item item = new Item( e.getFilename() );
                                items_tmp[cnt++] = item; 
                                SFTPv3FileAttributes fa = e.getAttributes();
                                //Log.v( TAG, e.longEntry + " " + fa.toString() );
                                item.dir  = fa.isDirectory();
                                if( !item.dir )
                                    item.size = fa.size;
                                item.date = new Date( (long)fa.mtime * 1000L );
                                LsItem lsi = new LsItem( e.longEntry );
                                item.attr = lsi.getAttr();
                                if( fa.isSymlink() ) try {
                                    String fpath = Utils.mbAddSl( path ) + item.name;
                                    SFTPv3FileAttributes lfa = client.stat(  fpath );
                                    if( lfa.isDirectory() ) item.dir = true;
                                    item.origin = client.readLink( fpath );
                                } catch( Exception ex ) {
                                    Log.e( TAG, "SFTP link is invalid: " + e.getFilename() + " " + 
                                            ex.getLocalizedMessage() );
                                }
                            }
                        }
                        Item item = items_tmp[0];
                        ItemComparator cmpr = new ItemComparator( 
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
            sendProgress( e.getLocalizedMessage(), Commander.OPERATION_FAILED );
            e.printStackTrace();
        }
        catch( Exception e ) {
            e.printStackTrace();
        }
        finally {
            if( client != null ) client.close();
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
        Log.d( TAG, "Host key:" + Utils.toHexString( serverHostKey, ":" ) );
        return true; // the user is responsible where he does connect to, ok?
    }
}
