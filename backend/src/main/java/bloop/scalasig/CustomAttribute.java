// Copyright (c) 2017-2019 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
// NOTE: This file has been partially copy/pasted from scala/asm.
package org.objectweb.asm;

import org.objectweb.asm.Attribute;

/**
 * A subclass of ASM's Attribute for the sole purpose of accessing a protected field there.
 */
public class CustomAttribute extends Attribute {
  public CustomAttribute(final String type, byte[] value) {
    super(type);
    // TODO: Re-enable when this code is used, broken because of https://gitlab.ow2.org/asm/asm/commit/f08474649b4e94cc7deda21bdf4c11af383eace9
    // super.value = value;
  }
}