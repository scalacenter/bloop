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

import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.SECURITY_DESCRIPTOR;
import com.sun.jna.platform.win32.WinNT.PSID;
import com.sun.jna.platform.win32.WinNT.PSIDByReference;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.SID_AND_ATTRIBUTES;
import com.sun.jna.platform.win32.WinNT.ACL;
import com.sun.jna.platform.win32.WinNT.ACCESS_ALLOWED_ACE;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinBase.SECURITY_ATTRIBUTES;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.W32Errors;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.Native;

public class Win32SecurityLibrary {
	private static final long SE_GROUP_LOGON_ID = 0xC0000000L;

	public static SECURITY_ATTRIBUTES createSecurityWithLogonDacl(int accessMask) {
		SECURITY_DESCRIPTOR sd = new SECURITY_DESCRIPTOR(64 * 1024);
		Advapi32.INSTANCE.InitializeSecurityDescriptor(sd, WinNT.SECURITY_DESCRIPTOR_REVISION);
		Native.getLastError();

		ACL pAcl;
		int cbAcl = 0;
		PSIDByReference psid = new PSIDByReference();
		getLogonSID(psid);
		int sidLength = Advapi32.INSTANCE.GetLengthSid(psid.getValue());
		cbAcl = Native.getNativeSize(ACL.class, null);
		cbAcl += Native.getNativeSize(ACCESS_ALLOWED_ACE.class, null);
		cbAcl += (sidLength - DWORD.SIZE);
		cbAcl = Advapi32Util.alignOnDWORD(cbAcl);
		pAcl = new ACL(cbAcl);
		Advapi32.INSTANCE.InitializeAcl(pAcl, cbAcl, WinNT.ACL_REVISION);
		Native.getLastError();
		Advapi32.INSTANCE.AddAccessAllowedAce(pAcl, WinNT.ACL_REVISION, accessMask, psid.getValue());
		Native.getLastError();
		Advapi32.INSTANCE.SetSecurityDescriptorDacl(sd, true, pAcl, false);
		Native.getLastError();

		SECURITY_ATTRIBUTES sa = new SECURITY_ATTRIBUTES();
		sa.dwLength = new DWORD(sd.size());
		sa.lpSecurityDescriptor = sd.getPointer();
		sa.bInheritHandle = false;

		return sa;
	}

	public static void getOwnerSID(PSIDByReference psid) {
		HANDLEByReference phToken = new HANDLEByReference();
		try {
			Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, phToken);
			Native.getLastError();
			IntByReference tokenInformationLength = new IntByReference();
			Advapi32.INSTANCE.GetTokenInformation(phToken.getValue(),
				WinNT.TOKEN_INFORMATION_CLASS.TokenOwner, null, 0, tokenInformationLength);
			WinNT.TOKEN_OWNER owner = new WinNT.TOKEN_OWNER(tokenInformationLength.getValue());
			Advapi32.INSTANCE.GetTokenInformation(phToken.getValue(),
				WinNT.TOKEN_INFORMATION_CLASS.TokenOwner, owner,
				tokenInformationLength.getValue(), tokenInformationLength);
			Native.getLastError();
			psid.setValue(owner.Owner);
		} finally {
			Kernel32Util.closeHandleRef(phToken);
		}
	}

	public static void getLogonSID(PSIDByReference psid) {
		HANDLEByReference phToken = new HANDLEByReference();
		try {
			Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, phToken);
			Native.getLastError();
			IntByReference tokenInformationLength = new IntByReference();
			Advapi32.INSTANCE.GetTokenInformation(phToken.getValue(),
				WinNT.TOKEN_INFORMATION_CLASS.TokenGroups, null, 0, tokenInformationLength);
			WinNT.TOKEN_GROUPS groups = new WinNT.TOKEN_GROUPS(tokenInformationLength.getValue());
			Advapi32.INSTANCE.GetTokenInformation(phToken.getValue(),
				WinNT.TOKEN_INFORMATION_CLASS.TokenGroups, groups,
				tokenInformationLength.getValue(), tokenInformationLength);
			Native.getLastError();

			for (SID_AND_ATTRIBUTES sidAndAttribute: groups.getGroups()) {
				if ((sidAndAttribute.Attributes & SE_GROUP_LOGON_ID) == SE_GROUP_LOGON_ID) {
					psid.setValue(sidAndAttribute.Sid);
					return;
				}
			}
			throw new RuntimeException("LogonSID was not found.");
		} finally {
			Kernel32Util.closeHandleRef(phToken);
		}
	}
}
