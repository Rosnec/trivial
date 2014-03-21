(ns trivial.client
  (:require [trivial.tftp :as tftp])
  (:import [java.net Inet4Address Inet6Address]))

(defn IPv4
  "Returns an InetAddress wrapper around the address using IPv4"
  ([address] (Inet4Address. address)))

(defn IPv6
  "Returns an InetAddress wrapper around the address using IPv6"
  ([address] (Inet6Address. address)))

(defn start
  ([url options] (let [address ((if (:IPv6 options)
                                  IPv6
                                  IPv4)
                                (:hostname options))
                       server ])))
