package com.ghostsq.commander.sftp;

import java.io.IOException;
import java.io.OutputStream;

//import ch.ethz.ssh2.SFTPClient;
import ch.ethz.ssh2.SFTPv3Client;
import ch.ethz.ssh2.SFTPv3FileHandle;

public class SFTPFileOutputStream extends OutputStream {
    SFTPv3Client       sftp;
    SFTPv3FileHandle sftp_file;
    long pos = 0;
    
    public SFTPFileOutputStream( SFTPv3FileHandle sftp_file_ ) {
        sftp_file = sftp_file_;
        sftp = sftp_file.getClient(); 
    }
    
    @Override
    public void close() throws IOException {
        sftp.closeFile( sftp_file );
        sftp.close();
    }
    
    @Override
    public void flush() throws IOException {
    }
    
    @Override
    public void write( byte[] ba, int off, int len ) throws IOException {
        sftp.write( sftp_file, pos, ba, off, len );
        pos += len;
    }

    @Override
    public void write( byte[] ba ) throws IOException {
        int len = ba.length;
        sftp.write( sftp_file, pos, ba, 0, len );
        pos += len;
    }

    @Override
    public void write( int b ) throws IOException {
        byte[] ba = new byte[1];
        ba[0] = (byte)b;
        sftp.write( sftp_file, pos++, ba, 0, 1 );
    }

}
