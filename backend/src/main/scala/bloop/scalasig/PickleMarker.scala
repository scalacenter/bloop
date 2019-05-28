// Imported from twitter/rsc with minor modifications
// Copyright (c) 2017-2019 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
package bloop.scalasig

import org.objectweb.asm.CustomAttribute

final class PickleMarker extends CustomAttribute("ScalaSig", PickleMarker.bytes)

object PickleMarker {
  val bytes: Array[Byte] = {
    val writer = new PickleWriter
    writer.writeVarint(5) // Major pickle version
    writer.writeVarint(0) // Minor pickle version
    writer.writeVarint(0)
    writer.toByteArray
  }

  final class PickleWriter {
    private var bytes = new Array[Byte](1024)
    var offset = 0

    def writeByte(x: Int): Unit = {
      val requestedLen = offset + 1
      if (requestedLen > bytes.length) {
        val bytes1 = new Array[Byte](requestedLen * 2)
        Array.copy(bytes, 0, bytes1, 0, offset)
        bytes = bytes1
      }
      bytes(offset) = x.toByte
      offset += 1
    }

    // NOTE: Write a 32-bit number as a base-128 varint.
    // To learn more what a varint means, check out:
    // https://developers.google.com/protocol-buffers/docs/encoding#varints
    def writeVarint(x: Int): Unit = {
      writeVarlong(x.toLong & 0X00000000FFFFFFFFL)
    }

    // NOTE: Write a 64-bit number as a base-128 varint.
    // To learn more what a varint means, check out:
    // https://developers.google.com/protocol-buffers/docs/encoding#varints
    def writeVarlong(x: Long): Unit = {
      def writePrefix(x: Long): Unit = {
        val y = x >>> 7
        if (y != 0L) writePrefix(y)
        writeByte(((x & 0x7f) | 0x80).toInt)
      }
      val y = x >>> 7
      if (y != 0L) writePrefix(y)
      writeByte((x & 0x7f).toInt)
    }

    def toByteArray: Array[Byte] = {
      import java.util.Arrays
      Arrays.copyOfRange(bytes, 0, offset)
    }
  }
}
