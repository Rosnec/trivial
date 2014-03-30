(ns trivial.client
  (:require [trivial.tftp :as tftp]
            [trivial.util :as util]
            [trivial.util :refer [dbg verbose]]))

(defn IPv4-address
  "Returns an InetAddress wrapper around the address using IPv4"
  ([address] (java.net.Inet4Address/getByName address)))

(defn IPv6-address
  "Returns an InetAddress wrapper around the address using IPv6"
  ([address] (java.net.Inet6Address/getByName address)))

(defn lockstep-session
  "Runs a session with the provided proxy server using lockstep."
  ([url socket server-address server-port timeout]
     (let [rrq-packet (tftp/rrq-packet url server-address server-port)
           data-packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           send-rrq (partial tftp/send socket rrq-packet)
           send-ack (fn ack [block]
                      (if (zero? block)
                        (send-rrq)
                        (tftp/send socket
                                   (tftp/ack-packet block
                                                    server-address
                                                    server-port))))
           error-opcode-unknown (partial tftp/error-opcode-unknown socket)
           error-malformed      (partial tftp/error-malformed socket)
           error-not-found      (partial tftp/error-not-found socket)
           error-tid            (partial tftp/error-tid socket)
           timeout-ns (* timeout 1000000000)]
       (util/with-connection socket
         (loop [last-block     0
                expected-block 1
                file-chunks    []
                time-limit     (+ (System/nanoTime) timeout-ns)]
           (verbose (str "disconnect in " (int (/ (- time-limit
                                                     (System/nanoTime))
                                                  1e9))
                         "s"))
           (send-ack last-block)
           (let [{:keys [address block data length opcode port retry?]
                  :as response}
                 (try
                   (tftp/recv socket data-packet server-address server-port)
                   (catch java.net.SocketTimeoutException e
                     {:retry? true})
                   (catch clojure.lang.ExceptionInfo e
                     (let [{:keys [cause packet]} (ex-data e)
                           {:keys [address opcode port url]} packet]
                       (verbose (.getMessage e))
                       (case cause
                         :malformed (error-malformed address port)
                         :unknown-opcode (error-opcode-unknown opcode
                                                               address
                                                               port)
                         :unknown-sender (error-tid address port)
                         nil))
                     {:retry? true}))]
             (cond
              (and (or retry?
                       (not= block expected-block)
                       (not= address server-address)
                       (not= port server-port))
                   (> time-limit (System/nanoTime)))
              (recur last-block expected-block file-chunks time-limit)

              (= block expected-block)
              (if (= length tftp/DATA-SIZE)
                (recur expected-block
                       (inc expected-block)
                       (conj file-chunks data)
                       (+ (System/nanoTime) timeout-ns))
                (do
                  (verbose "Received wrong block")
                  (send-ack expected-block)
                  (conj file-chunks data)))

              (= opcode tftp/ERROR)
              (let [{:keys [error-code error-msg]} response]
                (case error-code
                  tftp/FILE-NOT-FOUND (println "File not found,"
                                               "terminating connection.")
                  tftp/UNDEFINED (do
                                   (println error-msg)
                                   (recur last-block expected-block
                                          file-chunks time-limit))
                  (println error-msg)))
              :default (println "Disconnected."))))))))

(defn sliding-session
  "Runs a session with the provided proxy server using sliding window."
  ([url socket server-address server-port timeout]
     nil))

(defn start
  ([url options]
     (let [{:keys [hostname IPv6? port sliding-window? timeout]} options
           ip-fn (if IPv6? IPv6-address IPv4-address)
           address (ip-fn hostname)
           server (tftp/socket tftp/*timeout*)
           session-fn (if sliding-window? sliding-session lockstep-session)]
       (verbose "Connecting to" address "at port" port)
       (session-fn url server address port timeout))))
