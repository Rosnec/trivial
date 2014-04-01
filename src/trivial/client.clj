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
     (let [rrq-packet (tftp/rrq-packet url 0 server-address server-port)
           data-packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           send-rrq (partial tftp/send socket rrq-packet)
           send-ack (fn ack [block]
                      (if (zero? block)
                        (send-rrq)
                        (tftp/send socket
                                   (tftp/ack-packet block
                                                    server-address
                                                    server-port))))
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
           (let [{:keys [address block data error-code error-msg length
                         opcode port retry?]
                  :as response}
                 (try
                   (tftp/recv socket data-packet server-address server-port)
                   (catch java.net.SocketTimeoutException e
                     {:retry? true})
                   (catch clojure.lang.ExceptionInfo e
                     (let [{:keys [address cause port]} (ex-data e)]
                       (verbose (.getMessage e))
                       (case cause
                         :malformed (error-malformed address port)
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
                  (send-ack expected-block)
                  (conj file-chunks data)))

              (= opcode :ERROR)
              (case error-code
                :FILE-NOT-FOUND (println "File not found,"
                                         "terminating connection.")
                :UNDEFINED (do
                             (println error-msg)
                             (recur last-block expected-block
                                    file-chunks time-limit))
                (println "ERROR:" error-msg))
              :default (println "Disconnected."))))))))

(defn sliding-session
  "Runs a session with the provided proxy server using sliding window."
  ([window-size url socket server-address server-port timeout]
     nil))

(defn start
  ([url options]
     (let [{:keys [hostname IPv6? port window-size timeout]} options
           ip-fn (if IPv6? IPv6-address IPv4-address)
           address (ip-fn hostname)
           server (tftp/socket tftp/*timeout*)
           session-fn (if (not= 0 window-size)
                        (partial sliding-session window-size)
                        lockstep-session)]
       (verbose "Connecting to" address "at port" port)
       (session-fn url server address port timeout))))
