package com.ghostsq.commander.sftp;

import java.util.Date;
import java.util.Locale;

import android.os.Handler;
import android.text.format.DateFormat;
import android.text.format.Formatter;

import com.ghostsq.commander.Commander;
import com.ghostsq.commander.utils.LsItem;
import com.ghostsq.commander.utils.Utils;

class CalcSizesEngine extends SFTPEngineBase {
    private int num = 0, dirs = 0, depth = 0;

    CalcSizesEngine( SFTPAdapter a, LsItem[] list ) {
        super( a, list );
    }

    @Override
    public void run() {
        try {
            String path = Utils.mbAddSl( adapter.getUri().getPath() );
            long sum = getSizes( Utils.mbAddSl( path ), mList );
            StringBuffer result = new StringBuffer();
            if( mList.length == 1 ) {
                LsItem f = mList[0];
                if( f.isDirectory() ) {
                    result.append( ctx.getString( Utils.RR.sz_folder.r(), f.getName(), num ) );
                    if( dirs > 0 )
                        result.append( ctx.getString( Utils.RR.sz_dirnum.r(), dirs, ( dirs > 1 ? ctx.getString( Utils.RR.sz_dirsfx_p.r() ) : ctx.getString( Utils.RR.sz_dirsfx_s.r() ) ) ) );
                }
                else
                    result.append( ctx.getString( Utils.RR.sz_file.r(), f.getName() ) );
            } else
                result.append( ctx.getString( Utils.RR.sz_files.r(), num ) );
            if( sum > 0 )
                result.append( ctx.getString( Utils.RR.sz_Nbytes.r(), Formatter.formatFileSize( ctx, sum ).trim() ) );
            if( sum > 1024 )
                result.append( ctx.getString( Utils.RR.sz_bytes.r(), sum ) );
            if( mList.length == 1 ) {
                Date item_date = mList[0].getDate();
                if( item_date != null ) {
                    result.append( ctx.getString( Utils.RR.sz_lastmod.r() ) );
                    result.append( " " );
                    String date_s;
                    if( Locale.getDefault().getLanguage().compareTo( "en" ) != 0 ) {
                        java.text.DateFormat locale_date_format = DateFormat.getDateFormat( ctx );
                        java.text.DateFormat locale_time_format = DateFormat.getTimeFormat( ctx );
                        date_s = locale_date_format.format( item_date ) + " " + locale_time_format.format( item_date );
                    } else 
                        date_s = (String)DateFormat.format( "MMM dd yyyy hh:mm:ss", item_date );
                    result.append( date_s );
                }
            }
            sendReport( result.toString() );            
            super.run();
        } catch( Exception e ) {
            sendProgress( e.getMessage(), Commander.OPERATION_FAILED );
        }
    }

    protected final long getSizes( String path, LsItem[] list ) throws Exception {
        long count = 0;
        for( int i = 0; i < list.length; i++ ) {
            if( isStopReq() ) return -1;
            LsItem f = list[i];
            if( skip( f ) ) continue;
            String full_fn = path + f.getName();
            if( f.isDirectory() ) {
                sendProgress( f.getName(), (int)(i * 100. / list.length) );
                dirs++;
                if( depth++ > 20 )
                    throw new Exception( ctx.getString( Utils.RR.too_deep_hierarchy.r() ) );
                LsItem[] subItems = getItems( full_fn );
                if( subItems != null && subItems.length > 0 ) {
                    long sz = getSizes( Utils.mbAddSl( full_fn ), subItems );
                    if( sz < 0 ) return -1;
                    count += sz;
                }
                depth--;
            }
            else {
                num++;
                count += f.length();
            }
        }
        return count;
    }
}
