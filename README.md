trivial
=======

CSC445 Assignment 2:

```
A proxy server/client program using a modified TFTP.

Usage: trivial [options] 
               [server [server-options]|client url [client-options]]

Options:
  -p, --port PORT     8888  Port number
  -d, --drop                Packet drop mode
  -t, --timeout TIME  30    Time to drop connection (in seconds)
  -o, --output FILE         Output filename
  -v, --verbose             Verbose
  -h, --help

Modes:
  server   Run in server mode
  client   Run in client mode. Must have a url provided.
```

Example
=======

```
## launch server
$ java -jar trivial-standalone.jar --port=8888 --timeout=10 --output=/output/directory/throughput.csv server
## launch client
$ java -jar trivial-standalone.jar --port=8888 --timeout=10 --output=/output/directory/example.html client \
                                   --hostname=example@hostname.com http://www.example.com --window-size=64
```

Credit
======

Dan Wysocki (c) 2014

Licensed under the GNU General Public License version 3.
