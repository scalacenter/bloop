package bloop.nailgun;

import com.sun.jna.Native;
import com.sun.jna.Library;

public class Terminal {
    private static Libc libc;
    public static int hasTerminalAttached(int fd) {
        libc = (Libc)Native.loadLibrary("c", Libc.class);
        return libc.isatty(fd);
    }

    public static interface Libc extends Library {
        // unistd.h
        int isatty (int fd);
    }

/*
    static {
        System.out.println(System.getProperty("java.version"));
        System.out.println(System.getProperty("java.vm.name"));
        System.out.println(System.getProperty("java.library.path"));
        System.out.println(System.getProperty("java.class.path"));
        System.out.println(System.getProperty("java.io.tmpdir"));
        System.out.println("FINISHED");
        libc = (Libc)Native.loadLibrary("c", Libc.class);
        System.out.println("load library");
        System.out.println(libc.isatty(0));
    }
    */
}
