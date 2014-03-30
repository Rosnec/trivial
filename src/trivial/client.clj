(ns trivial.client
  (:require [trivial.tftp :as tftp]
            [trivial.util :as util]
            [trivial.util :refer [dbg verbose]])
  (:import [java.net Inet4Address Inet6Address SocketTimeoutException]))

(defn IPv4-address
  "Returns an InetAddress wrapper around the address using IPv4"
  ([address] (dbg (Inet4Address/getByName address))))

(defn IPv6-address
  "Returns an InetAddress wrapper around the address using IPv6"
  ([address] (dbg (Inet6Address/getByName address))))

(defn lockstep-session
  "Runs a session with the provided proxy server using lockstep."
  ([url server address port]
     (let [rrq-packet (tftp/rrq-packet url address port)
           data-packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           send-rrq #(dbg (.send server rrq-packet))]
       (try
         (send-rrq)
         ; there's probably some off-by-one errors in the block #'s
         (loop [block 0]
           (let [callback (if (zero? block)
                            send-rrq
                            #(dbg (.send server (tftp/ack-packet block
                                                                 address
                                                                 port))))
                 {:keys [address Block Data length more? TID]}
                 (util/try-callback-times tftp/*retries*
                                          callback
                                          false
                                          (tftp/recv-data server
                                                          data-packet
                                                          address
                                                          port
                                                          (inc block)))]
             (util/print-byte-buffer Data (/ length 2))
             (when more?
               (recur (inc block)))))
         (catch SocketTimeoutException e
           (util/exit 1 "Server timed out."))
         (finally
           (.close server))))))

(defn sliding-session
  "Runs a session with the provided proxy server using sliding window."
  ([url server]
     nil))

(defn start
  ([url options]
     (let [hostname (:hostname options)
           port (:port options)
           ip-fn (if (:IPv6? options) IPv6-address IPv4-address)
           address (ip-fn hostname)
           server (tftp/socket tftp/*timeout*)
           sliding? (:sliding-window options)
           session-fn (if sliding? sliding-session lockstep-session)]
       (session-fn url server address port))))
