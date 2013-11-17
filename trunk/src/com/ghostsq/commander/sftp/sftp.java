package com.ghostsq.commander.sftp;

import android.content.Context;
import com.ghostsq.commander.adapters.CommanderAdapter;

public class sftp {
    public final static CommanderAdapter createInstance( Context ctx ) {
        return new SFTPAdapter( ctx );
    }
}
