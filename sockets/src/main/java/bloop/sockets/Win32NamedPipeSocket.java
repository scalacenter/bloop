/*

 Copyright 2004-2019, Martian Software, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.

 */
package bloop.sockets;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

public class Win32NamedPipeSocket extends Socket {
    private static final Win32NamedPipeLibrary API = Win32NamedPipeLibrary.INSTANCE;
    private static HANDLE createFile(String pipeName) {
        return API.CreateFile(
            pipeName,
            WinNT.GENERIC_READ | WinNT.GENERIC_WRITE,
            0,     // no sharing
            null,  // default security attributes
            WinNT.OPEN_EXISTING,
            WinNT.FILE_FLAG_OVERLAPPED,     // need overlapped for true asynchronous read/write access
            null); // no template file
    }
    private static CloseCallback emptyCallback() {
        return new CloseCallback() {
            public void onNamedPipeSocketClose(HANDLE handle) throws IOException {
            }
        };
    }

    static final boolean DEFAULT_REQUIRE_STRICT_LENGTH = false;
    private final HANDLE handle;
    private final CloseCallback closeCallback;
    private boolean isClosed = false;
    private final boolean requireStrictLength;
    private final InputStream is;
    private final OutputStream os;
    private final HANDLE readerWaitable;
    private final HANDLE writerWaitable;

    interface CloseCallback {
        void onNamedPipeSocketClose(HANDLE handle) throws IOException;
    }

    /**
     * The doc for InputStream#read(byte[] b, int off, int len) states that
     * "An attempt is made to read as many as len bytes, but a smaller number may be read."
     * However, using requireStrictLength, NGWin32NamedPipeSocketInputStream can require that
     * len matches up exactly the number of bytes to read.
     */
    public Win32NamedPipeSocket(
            HANDLE handle,
            CloseCallback closeCallback,
            boolean requireStrictLength) throws IOException {
        this.handle = handle;
        this.closeCallback = closeCallback;
        this.requireStrictLength = requireStrictLength;
        this.readerWaitable = API.CreateEvent(null, true, false, null);
        if (readerWaitable == null) {
            throw new IOException("CreateEvent() failed ");
        }
        writerWaitable = API.CreateEvent(null, true, false, null);
        if (writerWaitable == null) {
            throw new IOException("CreateEvent() failed ");
        }
        this.is = new Win32NamedPipeSocketInputStream(handle);
        this.os = new Win32NamedPipeSocketOutputStream(handle);
    }

    public Win32NamedPipeSocket(
            HANDLE handle,
            CloseCallback closeCallback) throws IOException {
        this(handle, closeCallback, DEFAULT_REQUIRE_STRICT_LENGTH);
    }

    public Win32NamedPipeSocket(String pipeName) throws IOException {
        this(createFile(pipeName), emptyCallback(), DEFAULT_REQUIRE_STRICT_LENGTH);
    }

    @Override
    public InputStream getInputStream() {
        return is;
    }

    @Override
    public OutputStream getOutputStream() {
        return os;
    }

    @Override
    public boolean isClosed() {
        return this.isClosed;
    }

    @Override
    public void close() throws IOException {
        // Follow shutdown procedure in pynailgun.py
        shutdownInput();
        shutdownOutput();
        API.CloseHandle(handle);

        this.isClosed = true;
        closeCallback.onNamedPipeSocketClose(handle);
    }

    @Override
    public void shutdownInput() throws IOException {
        API.CloseHandle(readerWaitable);
    }

    @Override
    public void shutdownOutput() throws IOException {
        API.CloseHandle(writerWaitable);
    }

    private class Win32NamedPipeSocketInputStream extends InputStream {
        private final HANDLE handle;

        Win32NamedPipeSocketInputStream(HANDLE handle) {
            this.handle = handle;
        }

        @Override
        public int read() throws IOException {
            int result;
            byte[] b = new byte[1];
            if (read(b) == 0) {
                result = -1;
            } else {
                result = 0xFF & b[0];
            }
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            Memory readBuffer = new Memory(len);

            WinBase.OVERLAPPED olap = new WinBase.OVERLAPPED();
            olap.hEvent = readerWaitable;
            olap.write();

            boolean immediate = API.ReadFile(handle, readBuffer, len, null, olap.getPointer());
            if (!immediate) {
                int lastError = API.GetLastError();
                if (lastError != WinError.ERROR_IO_PENDING) {
                    throw new IOException("ReadFile() failed: " + lastError);
                }
            }

            IntByReference r = new IntByReference();
            if (!API.GetOverlappedResult(handle, olap.getPointer(), r, true)) {
                int lastError = API.GetLastError();
                throw new IOException("GetOverlappedResult() failed for read operation: " + lastError);
            }
            int actualLen = r.getValue();
            if (requireStrictLength && (actualLen != len)) {
                throw new IOException("ReadFile() read less bytes than requested: expected " +
                    len + " bytes, but read " + actualLen + " bytes");
            }
            byte[] byteArray = readBuffer.getByteArray(0, actualLen);
            System.arraycopy(byteArray, 0, b, off, actualLen);
            return actualLen;
        }
    }

    private class Win32NamedPipeSocketOutputStream extends OutputStream {
        private final HANDLE handle;

        Win32NamedPipeSocketOutputStream(HANDLE handle) {
            this.handle = handle;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) (0xFF & b)});
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            ByteBuffer data = ByteBuffer.wrap(b, off, len);

            WinBase.OVERLAPPED olap = new WinBase.OVERLAPPED();
            olap.hEvent = writerWaitable;
            olap.write();

            boolean immediate = API.WriteFile(handle, data, len, null, olap.getPointer());
            if (!immediate) {
                int lastError = API.GetLastError();
                if (lastError != WinError.ERROR_IO_PENDING) {
                    throw new IOException("WriteFile() failed: " + lastError);
                }
            }
            IntByReference written = new IntByReference();
            if (!API.GetOverlappedResult(handle, olap.getPointer(), written, true)) {
                int lastError = API.GetLastError();
                throw new IOException("GetOverlappedResult() failed for write operation: " + lastError);
            }
            if (written.getValue() != len) {
                throw new IOException("WriteFile() wrote less bytes than requested");
            }
        }
    }
}
