package com.ghostsq.commander.sftp;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.util.SparseBooleanArray;

import ch.ethz.ssh2.ConnectionInfo;
import ch.ethz.ssh2.InteractiveCallback;
import ch.ethz.ssh2.SFTPv3Client;
import ch.ethz.ssh2.SFTPv3DirectoryEntry;
import ch.ethz.ssh2.SFTPv3FileHandle;
import ch.ethz.ssh2.ServerHostKeyVerifier;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CA;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.CommanderAdapterBase;
import com.ghostsq.commander.adapters.Engines;
import com.ghostsq.commander.adapters.Engines.IReciever;
import com.ghostsq.commander.adapters.FSAdapter;
import com.ghostsq.commander.adapters.ItemComparator;
import com.ghostsq.commander.favorites.Favorite;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.LsItem;
import com.ghostsq.commander.utils.Utils;

public class SFTPAdapter extends CommanderAdapterBase implements InteractiveCallback, Engines.IReciever {
    private final static String TAG = "SFTPAdapter";
    private SFTPConnection conn;
    private Credentials crd;
    private Uri uri;
    private Item[] items = null;
    private static int instance_count = 0;
    private String fingerprint;

    public SFTPAdapter(Context ctx_) {
        super( ctx_ );
    }

    @Override
    public void Init( Commander c ) {
        super.Init( c );
        Log.d( TAG, "Created instance #" + ++instance_count );
    }

    @Override
    public String toString() {
        if( uri == null )
            return "";
        String ui = uri.getUserInfo();
        if( ui != null && crd == null )
            return Utils.mbAddSl( Favorite.screenPwd( uri ) );
        if( crd == null )
            return Utils.mbAddSl( uri.toString() );
        return Utils.mbAddSl( Favorite.screenPwd( Utils.getUriWithAuth( uri, crd ) ) );
    }
    
    public final String getFingerPrint() {
        return fingerprint == null ? "" : fingerprint;
    }
    
    private final void setFingerprint( ConnectionInfo ci )
    {
        fingerprint = null;
        if( ci == null ) return; 
        try {
            MessageDigest md = MessageDigest.getInstance( "MD5" );
            md.update( ci.serverHostKey, 0, ci.serverHostKey.length );
            fingerprint = ci.serverHostKeyAlgorithm + " " + Utils.toHexString( md.digest(), ":" );
        } catch( NoSuchAlgorithmException e ) {
            Log.e( TAG, "", e );
        }
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
        if( uri_ == null )
            return;
        String ui = uri_.getUserInfo();
        if( ui != null )
            crd = new Credentials( ui );
        uri = Utils.updateUserInfo( uri_, null ); // store the uri without
                                                  // credentials!
    }

    @Override
    public Uri getUri() {
        return uri.buildUpon().encodedPath( Utils.mbAddSl( uri.getEncodedPath() ) ).build();
    }

    @Override
    public String getScheme() {
        return "sftp";
    }

    @Override
    public boolean hasFeature( Feature feature ) {
        switch( feature ) {
        case REAL:
            return true;
        default:
            return super.hasFeature( feature );
        }
    }

    public int getSortMode() {
        return mode & ( MODE_SORTING | MODE_HIDDEN | MODE_SORT_DIR );
    }

    public synchronized SFTPv3Client getClient() {
        try {
            SFTPv3Client client = new SFTPv3Client( conn );
            client.setCharset( "UTF-8" );
            return client;
        } catch( IOException e ) {
            Log.e( TAG, "", e );
        }
        return null;
    }

    public final int connectAndLogin( ServerHostKeyVerifier verifier ) throws IOException, InterruptedException {
        try {
            Log.v( TAG, "connectAndLogin() is called in thread " + Thread.currentThread().getId() );
            this.fingerprint = null;
            Uri u = uri;
            int port = u.getPort();
            if( port == -1 )
                port = 22;

            String host = u.getHost();
            if( conn == null || !host.equalsIgnoreCase( conn.getHostname() ) ) {
                Log.v( TAG, "creating a new connection" );
                conn = new SFTPConnection( host, port );
            }
            ConnectionInfo ci = conn.mbConnect( verifier );
            if( ci == null ) {
                Log.d( TAG, "can't connect" );
                return SFTPConnection.NO_CONNECT;
            }
            // Log.d( TAG, "connected " + ci.serverHostKey.toString() );
            if( conn.isAuthenticationComplete() ) {
                return SFTPConnection.WAS_IN;
            }
            File key_file = getPrivateKeyFile();
            if( crd == null ) {
                if( !key_file.exists() )
                    setFingerprint( ci );
                String ui = u.getUserInfo();
                if( ui == null ) {
                    conn.close();
                    Log.w( TAG, "No credentials provided" );
                    return SFTPConnection.NO_LOGIN;
                }
                crd = new Credentials( ui );
            }

            boolean auth_ok = false;
            if( conn.isAuthMethodAvailable( crd.getUserName(), "publickey" ) ) {
                if( key_file.exists() )
                    try {
                        Log.d( TAG, "authenticateWithPublicKey" );
                        auth_ok = conn.authenticateWithPublicKey( crd.getUserName(), key_file, crd.getPassword() );
                    } catch( IOException e ) {
                        Log.w( TAG, "Keyfile " + key_file.getAbsolutePath() + " auth failed. Reason: " + e.toString() );
                        disconnect();
                        return SFTPConnection.NO_LOGIN;
                    }
            }
            if( !auth_ok && conn.isAuthMethodAvailable( crd.getUserName(), "password" ) )
                try {
                    Log.d( TAG, "authenticateWithPassword" );
                    auth_ok = conn.authenticateWithPassword( crd.getUserName(), crd.getPassword() );
                } catch( IOException e ) {
                    Log.w( TAG, "wrong password?" );
                    setFingerprint( ci );
                    disconnect();
                    return SFTPConnection.NO_LOGIN;
                }
            if( !auth_ok && conn.isAuthMethodAvailable( crd.getUserName(), "keyboard-interactive" ) )
                try {
                    Log.d( TAG, "authenticateWithKeyboardInteractive" );
                    auth_ok = conn.authenticateWithKeyboardInteractive( crd.getUserName(), this );
                } catch( IOException e ) {
                    Log.e( TAG, "", e );
                    disconnect();
                }
            if( auth_ok ) {
                return SFTPConnection.LOGGED_IN;
            } else {
                setFingerprint( ci );
                disconnect();
                Log.w( TAG, "Invalid credentials." );
                return SFTPConnection.NO_LOGIN;
            }
        } catch( Exception e ) {
            Log.e( TAG, uri.toString(), e );
            disconnect();
        }
        return SFTPConnection.NO_CONNECT;
    }

    private final File getPrivateKeyFile() {
        return new File( Environment.getExternalStorageDirectory(), ".GhostCommander/keys/" + uri.getHost() );
    }
    
    /**
     * InteractiveCallback implementation
     */
    @Override
    public String[] replyToChallenge( String name, String instruction, int numPrompts, String[] prompt, boolean[] echo ) throws Exception {
        Log.d( TAG, "name: " + name );
        Log.d( TAG, "instruction: " + instruction );
        Log.d( TAG, "numPrompts: " + numPrompts );
        Log.d( TAG, "prompts: " + prompt.length );
        for( String p : prompt ) {
            Log.d( TAG, "prompt: " + p );
        }
        String[] response = new String[numPrompts];
        for( int i = 0; i < numPrompts; i++ )
            response[i] = crd.getPassword();
        return response;
    }

    @Override
    public boolean readSource( Uri tmp_uri, String pass_back_on_done ) {
        Log.v( TAG, "readSource() is called in thread " + Thread.currentThread().getId() );
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
        if( items == null || items.length == 0 )
            return;
        Item item = items[0];
        ItemComparator cmpr = new ItemComparator( mode & CommanderAdapter.MODE_SORTING, ( mode & CommanderAdapter.MODE_CASE ) != 0,
                ( mode & CommanderAdapter.MODE_SORT_DIR ) == 0 );
        Arrays.sort( items, cmpr );
    }

    @Override
    public void openItem( int position ) {
        if( position == 0 ) { // ..
            if( uri != null && parentLink != SLS ) {
                String path = uri.getPath();
                int len_ = path.length() - 1;
                if( len_ > 0 ) {
                    if( path.charAt( len_ ) == SLC )
                        path = path.substring( 0, len_ );
                    path = path.substring( 0, path.lastIndexOf( SLC ) );
                    if( path.length() == 0 )
                        path = SLS;
                    // passing null instead of credentials keeps the current
                    // authentication session
                    commander.Navigate( uri.buildUpon().path( path ).build(), null, uri.getLastPathSegment() );
                }
            }
            return;
        }
        if( items == null || position < 0 || position > items.length )
            return;
        Item item = items[position - 1];

        if( item.dir ) {
            String cur = uri.getPath();
            if( cur == null || cur.length() == 0 )
                cur = SLS;
            else if( cur.charAt( cur.length() - 1 ) != SLC )
                cur += SLS;
            Uri item_uri = uri.buildUpon().appendPath( item.name ).build();
            commander.Navigate( item_uri, null, null );
        } else {
            Uri auth_item_uri = getUri().buildUpon().appendEncodedPath( item.name ).build();
            commander.Open( auth_item_uri, crd );
        }
    }

    @Override
    public Uri getItemUri( int position ) {
        Uri u = getUri();
        if( u == null )
            return null;
        return u.buildUpon().appendPath( getItemName( position, false ) ).build();
    }

    @Override
    public String getItemName( int position, boolean full ) {
        if( items != null && position > 0 && position <= items.length ) {
            if( full ) {
                String path = toString();
                if( path != null && path.length() > 0 ) {
                    if( path.charAt( path.length() - 1 ) != SLC )
                        path += SLS;
                    return path + items[position - 1].name;
                }
            }
            return items[position - 1].name;
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
        Item[] list = new Item[1];
        list[0] = items[position - 1]; 
        commander.startEngine( new RenEngine( commander, this, list, newName ) );
        return true;
    }

    @Override
    public boolean copyItems( SparseBooleanArray cis, CommanderAdapter to, boolean move ) {
        String err_msg = null;
        try {
            Item[] subItems = bitsToItems( cis );
            if( subItems == null ) {
                notify( s( Utils.RR.copy_err.r() ), Commander.OPERATION_FAILED );
                return false;
            }
            File dest = null;
            Engines.IReciever recipient = null;
            if( to instanceof FSAdapter ) {
                String dest_fn = to.toString();
                dest = new File( dest_fn );
                if( !dest.exists() )
                    dest.mkdirs();
                if( !dest.isDirectory() )
                    throw new RuntimeException( ctx.getString( Utils.RR.file_exist.r(), dest_fn ) );
            } else if( move && to.getClass().getName().indexOf( "SFTPAdapter" ) >= 0 ) {
                Uri dest_uri = to.getUri();
                if( uri.getHost().equals( dest_uri.getHost() ) ) { 
                    String new_path = Utils.mbAddSl( dest_uri.getPath() );
                    commander.startEngine( new RenEngine( commander, this, subItems, new_path ) );
                    return true;
                }
            } 
            if( dest == null ) {
                dest = new File( createTempDir() );
                recipient = to.getReceiver();
            }
            notify( Commander.OPERATION_STARTED );
            CopyFromEngine cfe = new CopyFromEngine( commander, this, subItems, dest, move, recipient );
            commander.startEngine( cfe );
            return true;
        } catch( Exception e ) {
            err_msg = "Exception: " + e.getMessage();
        }
        notify( err_msg, Commander.OPERATION_FAILED );
        return false;
    }

    @Override
    public boolean receiveItems( String[] fileURIs, int move_mode ) {
        try {
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
            commander.startEngine( new CopyToEngine( commander, this, list, move_mode ) );
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
        commander.startEngine( new MkDirEngine( this, Utils.mbAddSl( uri.getPath() ) + name ) );
    }

    @Override
    public void reqItemsSize( SparseBooleanArray cis ) {
        try {
            Item[] list = bitsToItems( cis );
            notify( Commander.OPERATION_STARTED );
            commander.startEngine( new CalcSizesEngine( this, list ) );
        } catch( Exception e ) {
        }
    }

    @Override
    public boolean deleteItems( SparseBooleanArray cis ) {
        try {
            Item[] subItems = bitsToItems( cis );
            if( subItems != null ) {
                notify( Commander.OPERATION_STARTED );
                commander.startEngine( new DelEngine( this, subItems ) );
                return true;
            }
        } catch( Exception e ) {
            commander.showError( "Exception: " + e.getMessage() );
        }
        return false;
    }

    protected int getPredictedAttributesLength() {
        return 25;
    }

    @Override
    public Object getItem( int position ) {
        Item item = new Item();
        item.name = "???";
        {
            if( position == 0 ) {
                item.name = parentLink;
            } else {
                if( items != null && position > 0 && position <= items.length ) {
                    Item curItem;
                    curItem = items[position - 1];
                    if( curItem == null ) {
                        Log.e( TAG, "Item is null: " + ( position - 1 ) );
                        return null;
                    }
                    item.dir = curItem.dir;
                    item.name = item.dir ? SLS + curItem.name : curItem.name;
                    item.size = !item.dir || curItem.size > 0 ? curItem.size : -1;
                    item.date = curItem.date;
                    item.attr = curItem.attr;
                    if( curItem.origin != null )
                        item.name += LsItem.LINK_PTR + curItem.origin;
                }
            }
        }
        return item;
    }

    @Override
    public Item getItem( Uri u ) {
        try {
            List<String> segs = u.getPathSegments();
            if( segs.size() == 0 ) {
                Item item = new Item( "/" );
                item.dir = true;
                return item;
            }
            String prt_path = "";
            for( int i = 0; i < segs.size() - 1; i++ ) {
                prt_path += "/" + segs.get( i );
            }
            String fn = segs.get( segs.size() - 1 );
            if( fn == null )
                return null;
            uri = u;
            if( connectAndLogin( null ) > 0 ) {
                SFTPv3Client client = getClient();
                if( client == null )
                    return null;
                try {
                    List dir_entries = client.ls( prt_path );
                    if( dir_entries != null ) {
                        int num_entries = dir_entries.size();
                        for( int i = 0; i < num_entries; i++ ) {
                            SFTPv3DirectoryEntry entry = (SFTPv3DirectoryEntry)dir_entries.get( i );
                            if( !fn.equals( entry.filename ) )
                                continue;
                            LsItem ls_item = new LsItem( entry.longEntry );
                            String ifn = ls_item.getName();
                            Item item = new Item( ifn );
                            item.size = ls_item.length();
                            item.date = ls_item.getDate();
                            item.dir = ls_item.isDirectory();
                            return item;
                        }
                    }
                } catch( Throwable e ) {
                    Log.e( TAG, u.toString(), e );
                } finally {
                    client.close();
                }
            }
        } catch( Throwable e ) {
            Log.e( TAG, u.toString(), e );
        }
        return null;
    }

    private final Item[] bitsToItems( SparseBooleanArray cis ) {
        try {
            int counter = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) )
                    counter++;
            Item[] subItems = new Item[counter];
            int j = 0;
            for( int i = 0; i < cis.size(); i++ )
                if( cis.valueAt( i ) )
                    subItems[j++] = items[cis.keyAt( i ) - 1];
            return subItems;
        } catch( Exception e ) {
            Log.e( TAG, "", e );
        }
        return null;
    }

    @Override
    public void prepareToDestroy() {
        super.prepareToDestroy();
        new Thread( new Runnable() {
            @Override
            public void run() {
                SFTPAdapter.this.disconnect();
            }
        } ).start();
        items = null;
        uri = null;
        Log.d( TAG, "Destroying instance #" + instance_count-- );
    }

    public void disconnect() {
        Log.v( TAG, "disconnect() is called in thread " + Thread.currentThread().getId() );
        Log.d( TAG, "Disconnecting..." );
        try {
            if( conn != null )
                conn.close();
        } catch( Exception e ) {
        }
        conn = null;
    }

    @Override
    public void finalize() {
        Log.d( TAG, "Finalizing..." );
        disconnect();
    }

    // ----------------------------------------

    private int content_requests_counter = 0;

    @Override
    public InputStream getContent( Uri u, long skip ) {
        try {
            Log.v( TAG, "getContent() was called, " + ++content_requests_counter );
            String sftp_path_name = u.getPath();
            if( uri == null ) {
                File f = new File( sftp_path_name );
                uri = u.buildUpon().path( f.getParent() ).build();
            }
            if( Utils.str( sftp_path_name ) && connectAndLogin( null ) > 0 ) {
                SFTPv3Client client = getClient();
                if( client == null )
                    return null;
                SFTPv3FileHandle sftp_file = client.openFileRO( sftp_path_name );
                if( sftp_file != null )
                    return new SFTPFileInputStream( sftp_file, skip );
                else
                    Log.e( TAG, "Can't opent the requested file. " + u );
            } else
                Log.e( TAG, "Can't connect. Reqested URI: " + u );
        } catch( Exception e ) {
            Log.e( TAG, "Exception on request of the file " + u, e );
        }
        return null;
    }

    @Override
    public OutputStream saveContent( Uri u ) {
        try {
            String sftp_path_name = u.getPath();
            if( uri == null ) {
                File f = new File( sftp_path_name );
                uri = u.buildUpon().path( f.getParent() ).build();
            }
            if( Utils.str( sftp_path_name ) && connectAndLogin( null ) > SFTPConnection.NEUTRAL ) {
                SFTPv3Client client = getClient();
                if( client == null )
                    return null;
                SFTPv3FileHandle sftp_file = client.createFileTruncate( sftp_path_name );
                if( sftp_file != null )
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
            Log.v( TAG, "closeStream() was called, " + --content_requests_counter );
            if( s != null )
                s.close();
        } catch( IOException e ) {
            Log.e( TAG, "closeStream()" + ( uri != null ? uri.toString() : "" ), e );
        }
    }

    @Override
    public IReciever getReceiver() {
        return this;
    }
}
