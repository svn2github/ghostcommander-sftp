package com.ghostsq.commander.sftp;

import ch.ethz.ssh2.SFTPv3DirectoryEntry;
import ch.ethz.ssh2.SFTPv3FileAttributes;

public class Entry extends SFTPv3DirectoryEntry {
    Entry( SFTPv3DirectoryEntry src ) {
        super.attributes = src.attributes;
        super.filename   = src.filename;
        super.longEntry  = src.longEntry;
    }
    public String getFilename() { return filename; }
    public SFTPv3FileAttributes getAttributes() { return attributes; }
}
