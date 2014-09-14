#!/usr/bin/env python

#
# Setup:
#   Install the `nrepl-python-client` python module. There are instructions
#   for how to do this: https://github.com/cemerick/nrepl-python-client.
#
# Usage:
#   In one terminal do `boot2 repl -p 7888`.
#   In another do `./boot.py <arg> <arg>...`, giving regular boot args.
#

from __future__ import print_function
import sys, os, nrepl, json

c = nrepl.connect("nrepl://localhost:7888")
wc = nrepl.WatchableConnection(c)

stop = False

def watch_out (msg, wc, key):
    global stop
    ex  = msg.get("ex", None)
    out = msg.get("out", None)
    err = msg.get("err", None)
    val = msg.get("value", None)
    sta = msg.get("status", None)
    if ex:
        stop = True
    if sta == ["done"]:
        os._exit(0)
    if err:
        print(err.rstrip(), file=sys.stderr)
    if out:
        print(out.rstrip(), file=sys.stdout)
        
args = ''

sys.argv.pop(0)

if len(sys.argv) > 0:
    for arg in sys.argv:
        args += ' '+json.dumps(arg)
else:
    args = '"help"'
    
wc.watch("out", {}, watch_out)

wc.send({"op": "eval",
         "code": '(boot %s) nil' % args})

while True:
    pass
