package bloop.dap

import com.microsoft.java.debug.core.adapter._
import com.sun.jdi.{Bootstrap, VirtualMachineManager}

object VirtualMachineManagerProvider extends IVirtualMachineManagerProvider {
  def getVirtualMachineManager: VirtualMachineManager = Bootstrap.virtualMachineManager
}
