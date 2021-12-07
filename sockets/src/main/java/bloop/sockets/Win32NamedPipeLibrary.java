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

import java.nio.ByteBuffer;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import com.sun.jna.win32.W32APIOptions;

public interface Win32NamedPipeLibrary extends Library, WinNT {
    int PIPE_ACCESS_DUPLEX = 3;
    int PIPE_UNLIMITED_INSTANCES = 255;
    int FILE_FLAG_FIRST_PIPE_INSTANCE = 524288;

    Win32NamedPipeLibrary INSTANCE =
            (Win32NamedPipeLibrary) Native.loadLibrary(
                    "kernel32",
                    Win32NamedPipeLibrary.class,
                    W32APIOptions.UNICODE_OPTIONS);

    HANDLE CreateNamedPipe(
            String lpName,
            int dwOpenMode,
            int dwPipeMode,
            int nMaxInstances,
            int nOutBufferSize,
            int nInBufferSize,
            int nDefaultTimeOut,
            SECURITY_ATTRIBUTES lpSecurityAttributes);
    HANDLE CreateFile(
            String lpFileName,
            int dwDesiredAccess,
            int dwSharedMode,
            SECURITY_ATTRIBUTES lpSecurityAttributes,
            int dwCreationDisposition,
            int dwFlagsAndAttributes,
            HANDLE hTemplateFile);
    boolean ConnectNamedPipe(
            HANDLE hNamedPipe,
            Pointer lpOverlapped);
    boolean DisconnectNamedPipe(
            HANDLE hObject);
    boolean ReadFile(
            HANDLE hFile,
            Memory lpBuffer,
            int nNumberOfBytesToRead,
            IntByReference lpNumberOfBytesRead,
            Pointer lpOverlapped);
    boolean WriteFile(
            HANDLE hFile,
            ByteBuffer lpBuffer,
            int nNumberOfBytesToWrite,
            IntByReference lpNumberOfBytesWritten,
            Pointer lpOverlapped);
    boolean CloseHandle(
            HANDLE hObject);
    boolean GetOverlappedResult(
            HANDLE hFile,
            Pointer lpOverlapped,
            IntByReference lpNumberOfBytesTransferred,
            boolean wait);
    boolean CancelIoEx(
            HANDLE hObject,
            Pointer lpOverlapped);
    HANDLE CreateEvent(
            SECURITY_ATTRIBUTES lpEventAttributes,
            boolean bManualReset,
            boolean bInitialState,
            String lpName);
    int WaitForSingleObject(
            HANDLE hHandle,
            int dwMilliseconds
    );

    int GetLastError();
}
