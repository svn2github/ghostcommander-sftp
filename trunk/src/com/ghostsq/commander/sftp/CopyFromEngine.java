package com.ghostsq.commander.sftp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Handler;
import android.util.Log;

import ch.ethz.ssh2.SFTPv3FileHandle;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.adapters.CommanderAdapterBase;
import com.ghostsq.commander.utils.ForwardCompat;
import com.ghostsq.commander.utils.LsItem;
import com.ghostsq.commander.utils.Utils;

class CopyFromEngine extends SFTPEngineBase 
{
    private   final static int BLOCK_SIZE = 32768;
    private   Commander     commander;
    private   String        src_path;
    private   File          dest_folder;
    private   boolean       move;
    private   int           recipient_hash;
    protected WifiLock      wifiLock;
    protected String        progressMessage = null;

    CopyFromEngine( Handler h, Commander c, SFTPAdapter a, LsItem[] list, File dest, boolean move_, int rec_h ) {
        super( h, a, list );
        commander = c;
        src_path = Utils.mbAddSl( adapter.getUri().getPath() );
        mList = list;
        dest_folder = dest;
        move = move_;
        recipient_hash = rec_h;

        WifiManager manager = (WifiManager)ctx.getSystemService( Context.WIFI_SERVICE );
        wifiLock = manager.createWifiLock( TAG );
        wifiLock.setReferenceCounted( false );
    }
    @Override
    public void run() {
        try {
            wifiLock.acquire();
            int total = copyFiles( mList, "" );
            wifiLock.release();
            
            if( recipient_hash != 0 ) {
                  sendReceiveReq( recipient_hash, dest_folder );
                  return;
            }
            sendResult( Utils.getOpReport( ctx, total, Utils.RR.copied.r() ) );
        } catch( InterruptedException e ) {
            Log.e( TAG, null, e );
            error( ctx.getString( Utils.RR.interrupted.r() ) );
        } catch( Exception e ) {
            Log.e( TAG, null, e );
            error( ctx.getString( Utils.RR.failed.r(), e.getLocalizedMessage() ) );
        }
    }
    
    private final int copyFiles( LsItem[] list, String path ) throws InterruptedException {
        int counter = 0;
        try {
            long   dir_size = 0, byte_count = 0;
            for( int i = 0; i < list.length; i++ ) {
                LsItem f = list[i];
                synchronized( f ) {
                    if( !f.isDirectory() )
                        dir_size += f.length();
                }
            }
            double conv = 100. / (double)dir_size;
            for( int i = 0; i < list.length; i++ ) {
                if( stop || isInterrupted() ) {
                    error( ctx.getString( Utils.RR.interrupted.r() ) );
                    break;
                }
                LsItem f = list[i];
                if( f != null ) {
                    String fn = f.getName();
                    if( skip( f ) ) continue;
                    
                    String rel_path_name = path + fn;
                    String sftp_path_name = src_path + rel_path_name; 
                    File dest_file = new File( dest_folder, rel_path_name );
                    if( f.isDirectory() ) {
                        if( !dest_file.mkdir() ) {
                            if( !dest_file.exists() || !dest_file.isDirectory() ) {
                                errMsg = "Can't create folder \"" + dest_file.getCanonicalPath() + "\"";
                                break;
                            }
                        }
                        LsItem[] subItems = getItems( sftp_path_name );
                        if( subItems != null & subItems.length > 0 )
                            counter += copyFiles( subItems, rel_path_name + CommanderAdapterBase.SLS );
                        if( !noErrors() ) break;
                        if( move )
                            sftp.rmdir( sftp_path_name );
                    }
                    else {
                        if( dest_file.exists()  ) {
                            int res = askOnFileExist( ctx.getString( Utils.RR.file_exist.r(), dest_file.getAbsolutePath() ), commander );
                            if( res == Commander.ABORT ) break;
                            if( res == Commander.SKIP )  continue;
                            if( res == Commander.REPLACE ) {
                                if( !dest_file.delete() ) {
                                    error( ctx.getString( Utils.RR.cant_del.r(), dest_file.getAbsoluteFile() ) );
                                    break;
                                }
                            }
                        }
                        progressMessage = ctx.getString( Utils.RR.retrieving.r(), sftp_path_name ); 
                        sendProgress( progressMessage, 0 );
                        SFTPv3FileHandle sftp_file = sftp.openFileRO( sftp_path_name );
                        FileOutputStream out = new FileOutputStream( dest_file );
                        byte buf[] = new byte[BLOCK_SIZE];
                        long done = 0;
                        int  n = 0;
                        int so_far = (int)(byte_count * conv);
                        try {
                            String retr_s = ctx.getString( Utils.RR.retrieving.r(), sftp_path_name );
                            String   sz_s = Utils.getHumanSize( f.length() );
                            int speed = 0;
                            while( true ) {
                                long start_time = System.currentTimeMillis();
                                if( isStopReq() ) {
                                    sftp.closeFile( sftp_file );
                                    out.close();
                                    error( ctx.getString( Utils.RR.fail_del.r(), dest_file.getName() ) );
                                    dest_file.delete();
                                    break;
                                }
                                n = sftp.read( sftp_file, done, buf, 0, BLOCK_SIZE );
                                if( n < 0 ) {
                                    Log.w( TAG, "EOF is reached" );
                                    break;
                                }                                
                                out.write( buf, 0, n );
                                byte_count += n;
                                done       += n;
                                long time_delta = System.currentTimeMillis() - start_time;
                                if( time_delta > 0 )
                                    speed = (int)(1000 * n / time_delta);
                                sendProgress( retr_s + sizeOfsize( done, sz_s ), so_far, (int)(byte_count * conv), speed );
                            }
                            sftp.closeFile( sftp_file );
                            out.close();
                        }
                        catch( Exception e ) {
                            Log.e( TAG, "file: " + sftp_path_name, e );
                            sftp.closeFile( sftp_file );
                            out.close();
                            error( e.getMessage() );
                            error( ctx.getString( Utils.RR.fail_del.r(), dest_file.getName() ) );
                            dest_file.delete();
                            break;
                        }
                        progressMessage = "";
                    }
                    Date ftp_file_date = f.getDate();
                    if( ftp_file_date != null )
                        dest_file.setLastModified( ftp_file_date.getTime() );
                    
                    final int GINGERBREAD = 9;
                    if( android.os.Build.VERSION.SDK_INT >= GINGERBREAD )
                        ForwardCompat.setFullPermissions( dest_file );
                    counter++;
                }
            }
        }
        catch( RuntimeException e ) {
            e.printStackTrace();
            error( "Runtime Exception: " + e.getMessage() );
        }
        catch( IOException e ) {
            e.printStackTrace();
            error( "Input-Output Exception: " + e.getMessage() );
        }
        return counter;
    }
}
