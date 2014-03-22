(ns trivial.server
  (:require [trivial.tftp :as tftp]
            [trivial.util :as util])
  (:import [java.net InetAddress SocketException]))

(defn lockstep-session
  ""
  ([url client]))

(defn sliding-session
  ""
  ([url client]
     (comment
       (loop [panorama (partition window-size 1 packets)]
         (let [num-received ...]
           (recur (nthrest num-received panorama)))))))

(defn start
  ([options]
     (let [port (:port options)
           socket (tftp/socket port)
           packet (tftp/datagram-packet (byte-array DATA-SIZE))]
       (util/closed-loop socket []
                         (try
                           (let [packet (tftp/recv socket packet)
                                 ]))))))

