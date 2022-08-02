#!/usr/bin/env python

import os
import shutil
import sys
import time

is_py2 = sys.version[0] == "2"
if is_py2:
    def print_out(s):
        print >> sys.stdout, s


    def print_err(s):
        print >> sys.stderr, s


else:
    def print_out(s):
        print(s, sys.stdout)


    def print_err(s):
        print(s, sys.stderr)


def main(output_dir, args):
    shutil.rmtree(output_dir, ignore_errors=True)
    os.makedirs(output_dir)

    arg_length = len(args)

    buf = []
    buf.append("package generated")
    buf.append("// generated at %f" % time.time())
    buf.append("object NameLengths {")
    buf.append("  val args_%d = %d" % (arg_length, arg_length))
    buf.append("  val nameLengths: Map[String, Int] = Map(")
    for input_file in args:
        file_size = os.path.getsize(input_file)
        buf.append('    "%s" -> %d,' % (input_file.replace('\\', '/'), file_size))
    buf.append("  )")
    buf.append("}")

    with open("%s/NameLengths_%d.scala" % (output_dir, arg_length), "w") as f:
        f.write("\n".join(buf))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print_err("Needs at least one argument.")
        sys.exit(1)
    elif "fail_now" in sys.argv:
        print_err("Test failure.")
        sys.exit(1)
    else:
        output_directory = sys.argv[1]
        args = sys.argv[2:]
        main(output_directory, args)

