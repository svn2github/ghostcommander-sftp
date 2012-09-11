package com.ghostsq.commander.sftp;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;

import android.net.Uri;
import android.util.Log;
import android.util.SparseBooleanArray;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.SFTPv3Client;
import ch.ethz.ssh2.SFTPv3DirectoryEntry;
import ch.ethz.ssh2.SFTPv3FileHandle;
import ch.ethz.ssh2.ServerHostKeyVerifier;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CA;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapterBase;
import com.ghostsq.commander.adapters.FSAdapter;
import com.ghostsq.commander.favorites.Favorite;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.LsItem;
import com.ghostsq.commander.utils.Utils;

public class SFTPAdapter extends CommanderAdapterBase {
    private final static String TAG = "SFTPAdapter";
    private Connection   conn;
    private SFTPv3Client client;
    private Credentials  crd;
    private Uri          uri;
    private LsItem[]     items = null;

    @Override
    public String toString() {
        if( uri == null )
            return "";
        String ui = uri.getUserInfo();
        if( ui != null && crd == null )
            return Favorite.screenPwd( uri );
        if( crd == null )
            return uri.toString();
        return Favorite.screenPwd( Utils.getUriWithAuth( uri, crd ) );    
    }
    
    @Override
    public void setCredentials( Credentials crd_ ) {
        crd = crd_;
    }
    @Override
    public Credentials getCredentials() {
        return crd;
    }
    
    @Override
    public void setUri( Uri uri_ ) {
        if( uri_ == null ) return;
        String ui = uri_.getUserInfo();
        if( ui != null )
            crd = new Credentials( ui );
        uri = Utils.updateUserInfo( uri_, null );   // store the uri without credentials!        
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public int getType() {
        return CA.SFTP;
    }

    public int getSortMode() {
        return mode & ( MODE_SORTING | MODE_HIDDEN | MODE_SORT_DIR );
    }

    public SFTPv3Client getClient() {
        return client;
    }
    
    public final static int WAS_IN      =  1;
    public final static int LOGGED_IN   =  2;
    public final static int NEUTRAL     =  0;
    public final static int NO_CONNECT  = -1;
    public final static int NO_LOGIN    = -2;
    
    public final int connectAndLogin( ServerHostKeyVerifier verifier ) throws IOException, InterruptedException {
        try {
            Uri u = uri; 
            String host = u.getHost();
            if( conn == null ) {
                client = null;
                conn = new Connection( host );
            }
            if( !host.equalsIgnoreCase( conn.getHostname() ) ) {
                disconnect();
                conn = new Connection( host );
            }
            try {
                conn.getConnectionInfo();   // is there any other way to know it was connected already? 
            } catch( Throwable e ) {
                client = null;
                conn.connect( verifier );
            }
            if( conn.isAuthenticationComplete() ) {
                if( client == null ) {
                    client = new SFTPv3Client( conn );
                    client.setCharset( "UTF-8" );
                }
                return WAS_IN;
            }
            
            if( crd == null ) {
                String ui = u.getUserInfo();
                if( ui == null ) {
                    client = null;
                    conn.close();
                    Log.w( TAG, "No credentials provided" );
                    return NO_LOGIN;
                }
                crd = new Credentials( ui );
            }
            if( conn.authenticateWithPassword( crd.getUserName(), crd.getPassword() ) ) {
                if( client == null ) {
                    client = new SFTPv3Client( conn /*, System.out*/ );
                    client.setCharset( "UTF-8" );
                }
                return LOGGED_IN;
            } else {
                disconnect();
                Log.w( TAG, "Invalid credentials." );
                return NO_LOGIN;
            }
        } catch( Exception e ) {
            Log.e( TAG, null, e );
            disconnect();
        }
        return NO_CONNECT;
    }
    
    public void disconnect() {
        client = null;
        if( conn != null ) conn.close();
    }
    
    @Override
    public boolean readSource( Uri tmp_uri, String pass_back_on_done ) {
        if( tmp_uri != null )
            setUri( tmp_uri );
        if( uri == null )
            return false;
        reader = new ListEngine( readerHandler, this, commander.getContext(), pass_back_on_done );
        reader.setName( TAG + ".ListEngine" );
        reader.start();
        return true;
    }

    @Override
    protected void onReadComplete() {
        Log.v( TAG, "UI thread finishes the items obtaining. reader=" + reader );
        if( reader instanceof ListEngine ) {
            ListEngine list_engine = (ListEngine)reader;
            items = list_engine.getItems();
            numItems = items != null ? items.length + 1 : 1;
            String path = uri.getPath();
            parentLink = path == null || path.length() == 0 || path.equals( SLS ) ? SLS : PLS;
            notifyDataSetChanged();
        }
    }

    @Override
    protected void reSort() {
        if( items == null || items.length == 0 ) return;
        LsItem item = items[0];
        com.ghostsq.commander.utils.LsItem.LsItemPropComparator cmpr;
        
        cmpr = item.new LsItemPropComparator( 
                     mode & CommanderAdapter.MODE_SORTING, 
                    (mode & CommanderAdapter.MODE_CASE) != 0, 
                    (mode & CommanderAdapter.MODE_SORT_DIR) == 0 );

        Arrays.sort( items, cmpr );
    }
    
    @Override
    public void openItem( int position ) {
        if( position == 0 ) { // ..
            if( uri != null && parentLink != SLS ) {
                String path = uri.getPath();
                int len_ = path.length()-1;
                if( len_ > 0 ) {
                    if( path.charAt( len_ ) == SLC )
                        path = path.substring( 0, len_ );
                    path = path.substring( 0, path.lastIndexOf( SLC ) );
                    if( path.length() == 0 )
                        path = SLS;
                    // passing null instead of credentials keeps the current authentication session
                    commander.Navigate( uri.buildUpon().path( path ).build(), null, uri.getLastPathSegment() );
                }
            }
            return;
        }
        if( items == null || position < 0 || position > items.length )
            return;
        LsItem item = items[position - 1];
        
        if( item.isDirectory() ) {
            String cur = uri.getPath();
            if( cur == null || cur.length() == 0 ) 
                cur = SLS;
            else
                if( cur.charAt( cur.length()-1 ) != SLC )
                    cur += SLS;
            Uri item_uri = uri.buildUpon().appendEncodedPath( item.getName() ).build();
            commander.Navigate( item_uri, null, null );
        }
        else {
            Uri auth_item_uri = getUri().buildUpon().appendEncodedPath( item.getName() ).build();
            commander.Open( auth_item_uri, crd );
        }
    }

    @Override
    public Uri getItemUri( int position ) {
        Uri u = getUri();
        if( u == null ) return null;
        return u.buildUpon().appendEncodedPath( getItemName( position, false ) ).build();
    }
    
    @Override
    public String getItemName( int position, boolean full ) {
        if( items != null && position > 0 && position <= items.length ) {
            if( full ) {
                String path = toString();
                if( path != null && path.length() > 0 ) {
                    if( path.charAt( path.length() - 1 ) != SLC )
                        path += SLS;
                    return path + items[position-1].getName();
                }
            }
            return items[position-1].getName();
        }
        return null;
    }

    @Override
    public boolean renameItem( int position, String newName, boolean copy ) {
        if( position <= 0 || position > items.length )
            return false;
        if( copy ) {
            notify( s( Utils.RR.not_supported.r() ), Commander.OPERATION_FAILED );
            return false;
        }
        notify( Commander.OPERATION_STARTED );
        String path = Utils.mbAddSl( uri.getPath() );
        worker = new RenEngine( commander.getContext(), workerHandler, this,
                 path + items[position - 1].getName(), path + newName );
        worker.start();
        return true;
    }

    @Override
    public boolean copyItems( SparseBooleanArray cis, CommanderAdapter to, boolean move ) {
        String err_msg = null;
        try {
            LsItem[] subItems = bitsToItems( cis );
            if( subItems == null ) {
                notify( s( Utils.RR.copy_err.r() ), Commander.OPERATION_FAILED );
                return false;
            } 
            if( worker != null ) {
                notify( s( Utils.RR.busy.r() ), Commander.OPERATION_FAILED );
                return false;
            } 
            File dest = null;
            int rec_h = 0;
            if( to instanceof FSAdapter  ) {
                String dest_fn = to.toString();
                dest = new File( dest_fn );
                if( !dest.exists() ) dest.mkdirs();
                if( !dest.isDirectory() )
                    throw new RuntimeException( ctx.getString( Utils.RR.file_exist.r(), dest_fn ) );
            } else {
                dest = new File( createTempDir() );
                rec_h = setRecipient( to ); 
            }
            notify( Commander.OPERATION_STARTED );
            worker = new CopyFromEngine( workerHandler, commander, this, subItems, dest, move, rec_h );
            worker.start();
            return true;
        }
        catch( Exception e ) {
            err_msg = "Exception: " + e.getMessage();
        }
        notify( err_msg, Commander.OPERATION_FAILED );
        return false;
    }

    @Override
    public boolean receiveItems( String[] fileURIs, int move_mode ) {
        try {
            if( isWorkerStillAlive() ) {
                notify( s( Utils.RR.busy.r() ), Commander.OPERATION_FAILED );
                return false;
            }
            if( fileURIs == null || fileURIs.length == 0 ) {
                notify( s( Utils.RR.copy_err.r() ), Commander.OPERATION_FAILED );
                return false;
            }
            File[] list = Utils.getListOfFiles( fileURIs );
            if( list == null || list.length == 0 ) {
                notify( "Something wrong with the files", Commander.OPERATION_FAILED );
                return false;
            }
            notify( Commander.OPERATION_STARTED );
            worker = new CopyToEngine( commander, workerHandler, this, list, move_mode );
            worker.setName( TAG + ".CopyToEngine" );
            worker.start();
            return true;
        } catch( Exception e ) {
            notify( "Exception: " + e.getMessage(), Commander.OPERATION_FAILED );
        }
        return false;
    }

    @Override
    public boolean createFile( String fileURI ) {
        return false;
    }

    @Override
    public void createFolder( String name ) {
        notify( Commander.OPERATION_STARTED );
        worker = new MkDirEngine( workerHandler, this, Utils.mbAddSl( uri.getPath() ) + name );
        worker.start();
    }

    @Override
    public void reqItemsSize( SparseBooleanArray cis ) {
        try {
            LsItem[] list = bitsToItems( cis );
            if( worker != null && worker.reqStop() )
                return;
            notify( Commander.OPERATION_STARTED );
            worker = new CalcSizesEngine( workerHandler, this, list );
            worker.start();
        }
        catch(Exception e) {
        }
    }
    
    @Override
    public boolean deleteItems( SparseBooleanArray cis ) {
        try {
            if( worker != null ) return false;
            LsItem[] subItems = bitsToItems( cis );
            if( subItems != null ) {
                if( worker != null ) {
                    notify( s( Utils.RR.copy_err.r() ), Commander.OPERATION_FAILED );
                    return false;
                }
                notify( Commander.OPERATION_STARTED );
                worker = new DelEngine( workerHandler, this, subItems );
                worker.setName( TAG + ".DelEngine" );
                worker.start();
                return true;
            }
        }
        catch( Exception e ) {
            commander.showError( "Exception: " + e.getMessage() );
        }
        return false;
    }

    @Override
    public Object getItem( int position ) {
        Item item = new Item();
        item.name = "???";
        {
            if( position == 0 ) {
                item.name = parentLink;
            }
            else {
                if( items != null && position > 0 && position <= items.length ) {
                    LsItem curItem;
                    curItem = items[position - 1];
                    item.dir = curItem.isDirectory();
                    item.name = item.dir ? SLS + curItem.getName() : curItem.getName();
                    item.size = !item.dir || curItem.length() > 0 ? curItem.length() : -1;
                    item.date = curItem.getDate();
                }
            }
        }
        return item;
    }
    private final LsItem[] bitsToItems( SparseBooleanArray cis ) {
        try {
            int counter = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) )
                    counter++;
            LsItem[] subItems = new LsItem[counter];
            int j = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) )
                    subItems[j++] = items[ cis.keyAt( i ) - 1 ];
            return subItems;
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return null;
    }

    @Override
    public void prepareToDestroy() {
        super.prepareToDestroy();
        client = null;
        if( conn != null ) conn.close();
        items = null;
    }
// ----------------------------------------
    
    
    @Override
    public InputStream getContent( Uri u, long skip ) {
        try {
            if( uri != null && !uri.getHost().equals( u.getHost() ) )
                return null;
            uri = u;
            String sftp_path_name = u.getPath();
            if( Utils.str( sftp_path_name ) && connectAndLogin( null ) > 0 && client != null ) {
                SFTPv3FileHandle sftp_file = client.openFileRO( sftp_path_name );
                if( sftp_file != null && !sftp_file.isClosed() )
                    return new SFTPFileInputStream( sftp_file, skip );
            }
        } catch( Exception e ) {
            Log.e( TAG, u.getPath(), e );
        }
        return null;
    }
    @Override
    public OutputStream saveContent( Uri u ) {
        try {
            if( uri != null && !uri.getHost().equals( u.getHost() ) )
                return null;
            uri = u;
            String sftp_path_name = u.getPath();
            if( Utils.str( sftp_path_name ) && connectAndLogin( null ) > NEUTRAL && client != null ) {
                SFTPv3FileHandle sftp_file = client.createFileTruncate( sftp_path_name );
                if( sftp_file != null && !sftp_file.isClosed() )
                    return new SFTPFileOutputStream( sftp_file );
            }
        } catch( Exception e ) {
            Log.e( TAG, u.getPath(), e );
        }
        return null;
    }
    @Override
    public void closeStream( Closeable s ) {
        try {
            if( s != null )
                s.close();
        } catch( IOException e ) {
            e.printStackTrace();
        }
    }
    

}
