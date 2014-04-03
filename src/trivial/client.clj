(ns trivial.client
  (:require [clojure.java.io :as io]
            [trivial.tftp :as tftp]
            [trivial.util :as util]
            [trivial.util :refer [dbg verbose]]))

(defn IPv4-address
  "Returns an InetAddress wrapper around the address using IPv4"
  ([address] (java.net.Inet4Address/getByName address)))

(defn IPv6-address
  "Returns an InetAddress wrapper around the address using IPv6"
  ([address] (java.net.Inet6Address/getByName address)))

(defn final-ack
  "Sends the final ack repeatedly until timeout has elapsed without receiving
  any data packets from the server."
  ([block socket server-address server-port timeout data-packet]
     (loop [time-limit (+ (System/nanoTime) timeout)]
       ; send the ack
       (tftp/send socket (tftp/ack-packet block server-address server-port))
       ; await a response
       (let [{:keys [address opcode port] :as response}
             (try
               (tftp/recv socket data-packet server-address server-port)
               (catch java.net.SocketTimeoutException e
                 {})
               (catch clojure.lang.ExceptionInfo e
                 {}))]
         (cond
          ; received data packet from server, resend ACK
          (and (= address server-address)
               (= port server-port)
               (= opcode :DATA))
          (recur (+ (System/nanoTime) timeout))

          ; haven't timed out yet, resend ACK
          (> timeout (System/nanoTime))
          (recur time-limit)

          ; timed out, exit
          :default nil)))))

(defn lockstep-session
  "Runs a session with the provided proxy server using lockstep."
  ([url output-stream socket server-address server-port timeout]
     (let [file-writer (agent 0)
           write-bytes (fn write-bytes [bytes]
                         (io/copy (byte-array bytes) output-stream))
           send-write (fn send-write [bytes]
                        (send-off file-writer (fn [_] (write-bytes bytes))))
           rrq-packet (tftp/rrq-packet url 0 server-address server-port)
           data-packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           send-rrq (partial tftp/send socket rrq-packet)
           send-ack (fn ack [block]
                      (if (zero? block)
                        (send-rrq)
                        (tftp/send socket
                                   (tftp/ack-packet block
                                                    server-address
                                                    server-port))))
           error-malformed (partial tftp/error-malformed socket)
           error-not-found (partial tftp/error-not-found socket)
           error-tid       (partial tftp/error-tid socket)
           exit-time #(+ (System/nanoTime) timeout)]
       (util/with-connection socket
         (loop [last-block     0
                expected-block 1
                time-limit     (exit-time)]
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
              (recur last-block expected-block time-limit)

              (= block expected-block)
              (do
                (verbose (str "Received:\n"
                              "  block#: " block "\n"
                              "  length: " length))
                (if (= length tftp/DATA-SIZE)
                  (do
                    (send-write data)
                    (recur expected-block
                           (inc expected-block)
                           (exit-time)))
                  (do
                    (send-write data)
                    (final-ack expected-block
                               socket
                               server-address
                               server-port
                               2e9 ; wait 2 seconds on final ack
                               data-packet)
                    (await file-writer)
                    (println "Transfer successful.")
                    (util/exit 0))))

              (= opcode :ERROR)
              (case error-code
                :FILE-NOT-FOUND (println "File not found,"
                                         "terminating connection.")
                :ILLEGAL-OPERATION (println "ERROR:" error-msg)
                :UNDEFINED (do
                             (verbose error-msg)
                             (recur last-block expected-block time-limit))
                (println "ERROR:" error-msg))
              :default (println "Disconnected."))))))))

(defn sliding-session
  "Runs a session with the provided proxy server using sliding window."
  ([window-size url output-stream socket server-address server-port timeout]
     (let [file-writer (agent 0)
           write-bytes (fn write-bytes [bytes]
                         (io/copy (byte-array bytes) output-stream))
           send-write (fn send-write [bytes]
                        (send-off file-writer (fn [_] (write-bytes bytes))))
           rrq-packet (tftp/rrq-packet url
                                       window-size
                                       server-address
                                       server-port)
           data-packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           send-rrq (partial tftp/send socket rrq-packet)
           send-ack (fn send-ack [block]
                      (if (zero? block)
                        (send-rrq)
                        (tftp/send socket
                                   (tftp/ack-packet block
                                                    server-address
                                                    server-port))))
           exit-time #(+ (System/nanoTime) timeout)
           window-time #(+ (System/nanoTime) tftp/*window-time*)]
       (util/with-connection socket
         (loop [last-block 0
                time-limit (exit-time)]
           (send-ack last-block)
           (let [current-block ; I think this is being incremented when
                               ; it shouldn't be
                 (loop [next-block (inc last-block)
                        final-block (+ next-block window-size)
                        time-limit (window-time)]
                   (let [{:keys [address block data error-code error-msg length
                                 opcode port]}
                         (try (tftp/recv socket
                                         data-packet
                                         server-address
                                         server-port)
                              (catch java.net.SocketTimeoutException e
                                {})
                              (catch clojure.lang.ExceptionInfo e
                                (let [{:keys [address cause port]} (ex-data e)]
                                  (verbose (.getMessage e))
                                  (case cause
                                    :malformed (tftp/error-malformed socket
                                                                     address
                                                                     port)
                                    :unknown-sender (tftp/error-tid socket
                                                                    address
                                                                    port)
                                    nil))
                                {}))]
                     (cond
                      (and (or (not= block next-block)
                               (not= address server-address)
                               (not= port server-port))
                           (> time-limit (System/nanoTime)))
                      (recur next-block final-block time-limit)

                      ; correct block received
                      (and (= block next-block)
                           (= address server-address)
                           (= port server-port))
                      (if (= length tftp/DATA-SIZE)
                        (do
                          (send-write (dbg data))
                          (if (or (= block final-block)
                                  (> (System/nanoTime) time-limit))
                            (dbg next-block)
                            (recur (inc block) final-block time-limit)))
                        (do
                          (send-write data)
                          (final-ack block
                                     socket
                                     server-address
                                     server-port
                                     2e9 ; wait 2 seconds on final ack
                                     data-packet)
                          (await file-writer)
                          (println "Transfer successful.")
                          (util/exit 0)))

                      (= opcode :error)
                      (case error-code
                        :FILE-NOT-FOUND
                        (util/exit 1 (str "File not found, "
                                          "terminating connection."))
                        :UNDEFINED (do
                                     (verbose error-msg)
                                     (recur next-block final-block time-limit))
                        (println "ERROR:" error-msg))

                      :default (do
                                 (verbose "Window timed out.")
                                 (dbg (dec next-block))))))]
             (verbose current-block)
             (cond
              (> current-block last-block)
              (do (send-ack current-block)
                  (recur current-block (exit-time)))

              (> time-limit (System/nanoTime))
              (recur last-block time-limit)

              :default (util/exit 1 "Disconnected."))))))))

(defn start
  ([url options]
     (let [{:keys [hostname IPv6? output port window-size timeout]} options
           ip-fn (if IPv6? IPv6-address IPv4-address)
           address (ip-fn hostname)
           socket (tftp/socket tftp/*timeout*)
           session-fn (if (not= 0 window-size)
                        (partial sliding-session window-size)
                        lockstep-session)]
       (verbose "Connecting to" address "at port" port)
       (io/delete-file output true)
       (with-open [output-stream (io/output-stream (io/file output)
                                                   :append true)]
         (session-fn url output-stream socket address port
                     (* timeout 1e9))))))
