(ns trivial.client
  (:require [trivial.tftp :as tftp]
            [trivial.util :as util])
  (:import [java.net Inet4Address Inet6Address SocketException]))

(defn IPv4-address
  "Returns an InetAddress wrapper around the address using IPv4"
  ([address] (Inet4Address/getByName address)))

(defn IPv6-address
  "Returns an InetAddress wrapper around the address using IPv6"
  ([address] (Inet6Address/getByName address)))

(defn lockstep-session
  "Runs a session with the provided proxy server using lockstep"
  ([url server]
     (tftp/send-rrq url server)
     (try
       (util/try-callback-times tftp/*retries*
                                #(tftp/send-rrq url server)
                                (tftp/recv-data server 1))
       (loop [block 2]
         (let [[received more?]
               (util/try-callback-times tftp/*retries*
                                        #(tftp/send-ack server (dec block))
                                        (tftp/recv-data server block))]
           (print received)
           (if more?
             (recur (inc block))
             (tftp/send-ack server block))))
       (catch SocketException e
         (util/exit 1 "Server timed out."))
       (finally
         (.close server)))))

(defn start
  ([url options]
     (let [hostname (:hostname options)
           port (:port options)
           ip-fn (if (:IPv6 options) IPv6-address IPv4-address)
           address (ip-fn hostname)
           server (tftp/socket tftp/*timeout* port address)
           sliding? (:sliding-window options)
           session-fn (if sliding? sliding-session lockstep-session)]
       (session-fn url server))))
