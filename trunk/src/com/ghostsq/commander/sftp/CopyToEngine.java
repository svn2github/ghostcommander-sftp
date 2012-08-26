package com.ghostsq.commander.sftp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Handler;

import ch.ethz.ssh2.SFTPv3Client;
import ch.ethz.ssh2.SFTPv3FileAttributes;
import ch.ethz.ssh2.SFTPv3FileHandle;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapter;
import com.ghostsq.commander.adapters.Engine;
import com.ghostsq.commander.utils.Credentials;
import com.ghostsq.commander.utils.Utils;

class CopyToEngine extends Engine // From a local fs to SFTP share  
{
    protected final static int BLOCK_SIZE = 32768;
    private   Commander commander;
    private   Context ctx;
    private   File[]  mList;
    private   SFTPAdapter  adapter;
    private   SFTPv3Client sftp;
    private   boolean move = false;
    private   boolean del_src_dir = false;
    private   WifiLock  wifiLock;
    
    CopyToEngine( Commander c, Handler h, SFTPAdapter a, File[] list, int move_mode_ ) {
        super( h );
        commander = c;
        ctx = commander.getContext();
        mList = list;
        adapter = a;
        move = ( move_mode_ & CommanderAdapter.MODE_MOVE ) != 0;
        del_src_dir = ( move_mode_ & CommanderAdapter.MODE_DEL_SRC_DIR ) != 0;
        WifiManager manager = (WifiManager)ctx.getSystemService( Context.WIFI_SERVICE );
        wifiLock = manager.createWifiLock( TAG );
        wifiLock.setReferenceCounted( false );
    }

    @Override
    public void run() {
        
        try {
            Credentials crd = adapter.getCredentials();
            int cl_res = adapter.connectAndLogin( null );
            if( cl_res < 0 ) {
                if( cl_res < 0 ) { 
                    sendProgress( null, Commander.OPERATION_FAILED );
                    return;
                }
            }
            sftp = adapter.getClient();
            if( sftp == null ) { 
                sendProgress( null, Commander.OPERATION_FAILED );
                return;
            }
            wifiLock.acquire();
            String dest_path = adapter.getUri().getPath();
            if( !Utils.str( dest_path ) )
                dest_path = "/";
            int cnt = copyFiles( mList, dest_path );
            wifiLock.release();
            if( del_src_dir ) {
                File src_dir = mList[0].getParentFile();
                if( src_dir != null )
                    src_dir.delete();
            }
            sendResult( Utils.getOpReport( ctx, cnt, move ? Utils.RR.moved.r() : Utils.RR.copied.r() ) );
            super.run();
        } catch( IOException e ) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch( InterruptedException e ) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private final int copyFiles( File[] list, String dest ) {
        int counter = 0;
        try {
            long num = list.length;
            long dir_size = 0, byte_count = 0;
            for( int i = 0; i < num; i++ ) {
                File f = list[i];               
                if( !f.isDirectory() )
                    dir_size += f.length();
            }
            double conv = 100./(double)dir_size;
            for( int i = 0; i < num; i++ ) {
                if( stop || isInterrupted() ) {
                    error( ctx.getString( Utils.RR.interrupted.r() ) );
                    break;
                }
                File f = list[i];
                if( f != null && f.exists() ) {
                    boolean dir = f.isDirectory(); 
                    String fn = f.getName();
                    if( dir ) fn += "/";
                    boolean create_dir = dir; 
                    
                    String sftp_fn = Utils.mbAddSl( dest )  + fn;
                    try {
                        SFTPv3FileAttributes sftp_file_attr = sftp.stat( sftp_fn );
                        if( true ) {    // TODO: what of the attributes to consider?
                            int res = askOnFileExist( ctx.getString( Utils.RR.file_exist.r(), sftp_fn ), commander );
                            if( res == Commander.ABORT ) break;
                            if( res == Commander.SKIP )  continue;
                            if( res == Commander.REPLACE ) 
                                if( dir ) 
                                    create_dir = false;
                                else
                                    sftp.rm( sftp_fn );
                        }
                    } catch( Exception e1 ) {
                    }
                    if( dir ) {
                        if( create_dir )
                            sftp.mkdir( sftp_fn, 0777 );
                        counter += copyFiles( f.listFiles(), sftp_fn );
                        if( errMsg != null ) break;
                    } else if( f.isFile() ) {
                        SFTPv3FileHandle new_sftp_file = sftp.createFile( sftp_fn, null ); // TODO: set correct attributes
                        
                        
                        FileInputStream in = new FileInputStream( f );
                        byte buf[] = new byte[BLOCK_SIZE];
                        long done = 0;
                        int  n = 0;
                        int  so_far = (int)(byte_count * conv);
                        try {
                            String cur_op_s = ctx.getString( Utils.RR.uploading.r(), f.getAbsolutePath() );
                            String     sz_s = Utils.getHumanSize( f.length() );
                            int speed = 0;
                            while( true ) {
                                long start_time = System.currentTimeMillis();
                                n = in.read( buf );
                                if( n < 0 ) break;
                                sftp.write( new_sftp_file, done, buf, 0, n );
                                byte_count += n;
                                done       += n;
                                long time_delta = System.currentTimeMillis() - start_time;
                                if( time_delta > 0 )
                                    speed = (int)(1000 * n / time_delta);
                                sendProgress( cur_op_s + sizeOfsize( done, sz_s ), so_far, (int)(byte_count * conv), speed );
                            }
                            in.close();
                            sftp.closeFile( new_sftp_file );
                        } catch( Exception e ) {
                            in.close();
                            sftp.closeFile( new_sftp_file );
                            error( ctx.getString( Utils.RR.fail_del.r(), sftp_fn ) );
                            sftp.rm( sftp_fn );
                            break;
                        }
                    }
                    //new_file.setLastModified( f.lastModified() ); // TODO
                    counter++;
                    if( move && !f.delete() ) {
                        error( ctx.getString( Utils.RR.cant_del.r(), f.getCanonicalPath() ) );
                        break;
                    }
                }
            }
        }
        catch( Exception e ) {
            e.printStackTrace();
            error( e.getLocalizedMessage() );
        }
        return counter;
    }
}
