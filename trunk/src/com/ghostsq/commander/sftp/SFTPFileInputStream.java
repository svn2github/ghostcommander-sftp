package com.ghostsq.commander.sftp;

import java.io.IOException;
import java.io.InputStream;

import ch.ethz.ssh2.SFTPClient;
import ch.ethz.ssh2.SFTPv3Client;
import ch.ethz.ssh2.SFTPv3FileHandle;

class SFTPFileInputStream extends InputStream {
    SFTPClient       sftp;
    SFTPv3FileHandle sftp_file;
    long             pos, mark;
    
    SFTPFileInputStream( SFTPv3FileHandle sftp_file_, long skip ) {
        sftp_file = sftp_file_;
        sftp = sftp_file.getClient();
        pos = skip;
    }
    
    @Override
    public void close() throws IOException {
        sftp.closeFile( sftp_file );
        sftp.close();
    }
    
    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void mark(int readlimit) {
        mark = pos;
    }
    
    @Override
    public void   reset() {
        pos = mark;
    }

    @Override
    public int read() throws IOException {
        byte[] ba = new byte[1];
        sftp.read( sftp_file, pos++, ba, 0, 1 );
        return ba[0];
    }
    
    @Override
    public int read( byte[] ba ) throws IOException {
        int n = sftp.read( sftp_file, pos, ba, 0, ba.length > 32768 ? 32768 : ba.length );
        pos += n;
        return n;
    }
    
    @Override
    public int read( byte[] ba, int off, int len ) throws IOException {
        int n = sftp.read( sftp_file, pos, ba, off, len > 32768 ? 32768 : len );
        pos += n;
        return n;
    }
    
    @Override
    public long skip( long n ) throws IOException {
        pos += n;
        return n;
    }
}
