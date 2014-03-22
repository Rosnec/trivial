(ns trivial.client
  (:require [trivial.tftp :as tftp]
            [trivial.util :as util])
  (:import [java.net Inet4Address Inet6Address SocketTimeoutException]))

(defn IPv4-address
  "Returns an InetAddress wrapper around the address using IPv4"
  ([address] (Inet4Address/getByName address)))

(defn IPv6-address
  "Returns an InetAddress wrapper around the address using IPv6"
  ([address] (Inet6Address/getByName address)))

(defn lockstep-session
  "Runs a session with the provided proxy server using lockstep."
  ([url server]
     (let [address (.getInetAddress server)
           port (.getPort server)
           rrq-packet (tftp/rrq-packet url address port)
           send-rrq #(.send server rrq-packet)]
       (try
         ; there's probably some off-by-one errors in the block #'s
         (loop [block 0]
           (let [callback (if (zero? block)
                            send-rrq
                            #(.send server (tftp/ack-packet block
                                                            address
                                                            port)))
                 {:keys [Block Data more? TID address]}
                 (util/try-callback-times tftp/*retries*
                                          callback
                                          true
                                          (tftp/recv-data server (inc block)))]
             (if (and (pos? block)
                      (not= block Block))
               (recur block)
               (do (print Data)
                   (if more?
                     (recur (inc block))
                     (.send server (tftp/ack-packet (inc block)
                                                    address
                                                    port)
                            server))))))
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
           ip-fn (if (:IPv6 options) IPv6-address IPv4-address)
           address (ip-fn hostname)
           server (tftp/socket tftp/*timeout* port address)
           sliding? (:sliding-window options)
           session-fn (if sliding? sliding-session lockstep-session)]
       (session-fn url server))))
