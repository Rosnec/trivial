(ns trivial.client
  (:require [clojure.java.io :as io]
            [gloss.io :refer [decode]]
            [trivial.math :as math]
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
     (let [get-time (partial math/elapsed-time timeout)]
       (loop [time-limit (get-time)]
         (verbose "ACKing")
         ; send the ack
         (tftp/send socket (tftp/ack-packet block server-address server-port))
         ; await a response
         (let [{:keys [address opcode port] :as response}
               (try
                 (tftp/recv socket data-packet server-address server-port)
                 (catch java.net.SocketTimeoutException e
                   (verbose "Timed out")
                   {})
                 (catch clojure.lang.ExceptionInfo e
                   (verbose "Other exception")
                   {}))]
           (cond
            ; received data packet from server, resend ACK
            (and (= address server-address)
                 (= port server-port)
                 (= opcode :DATA))
            (recur (get-time))
            (and (= address server-address)
                 (= port server-port))
            (case opcode
              :DATA (recur (get-time))
              :ERROR (verbose "error received on final ack")
              (do
                (verbose "unexpected response:" response)
                (recur (get-time))))
            

            ; haven't timed out yet, resend ACK
            (> timeout (System/nanoTime))
            (recur time-limit)

            ; timed out, exit
            :default (verbose "final timeout")))))))

(defn write-bytes
  "Takes an arbitrary number of byte sequences and writes them to the
  output-stream in-order."
  ([output-stream & bytes]
     (doseq [b bytes]       
       (io/copy (byte-array b) output-stream))))

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
           exit-time (partial math/elapsed-time timeout)]
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
                  (final-ack block
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
;              :ILLEGAL-OPERATION (println "ERROR:" error-msg)
              :UNDEFINED (do
                           (verbose error-msg)
                           (recur last-block expected-block time-limit))
              (println "ERROR:" error-msg))
            :default (println "Disconnected.")))))))

(defn add-packet
  "Adds a packet to the :packets map of a window-agent's map.
  Input packet is just the DatagramPacket, but what gets put in the map is
  actually the packet's block# mapped to the decoded packet."
  ([m packet address port]
     (let [packet-address (.getAddress packet)
           packet-port    (.getPort packet)]
       (if (and (= address packet-address) (= port packet-port))
         (let [length (.getLength packet)
               data   (.getData packet)
               buffer (java.nio.ByteBuffer/wrap data 0 length)
               decoded-packet (try
                                (decode tftp/packet-encoding buffer)
                                (catch Exception e
                                  (verbose "Malformed packet received.")
                                  nil))]
           (verbose "Received block#" (:block decoded-packet)
                    "with length" length)
           (if decoded-packet
             (assoc-in m [:packets (:block decoded-packet)]
                       (assoc decoded-packet
                         :length length))
             m))
         m))))

(defn clear-packets
  "Clears the :packets map from the window-agent's map. Sets the :block
  number to the new value. Writes the packets with block# less than the given
  block number."
  ([m block write-agent output-stream]
     (let [packets (vals (take-while (fn [[k v]] (<= k block))
                                     (:packets m)))
           bytes (map :data packets)
           _ (send-off write-agent write-bytes output-stream bytes)
           more? (= (-> packets last :length) tftp/DATA-SIZE)]
       (assoc m
         :block block
         :packets (sorted-map)
         :more? more?))))

(defn window-results
  ""
  ([window-agent write-agent output-stream]
     (await window-agent)
     (let [{:keys [block packets]} @window-agent
           highest-block (math/highest-sequential (keys packets) block 1)]
       (send-off window-agent
                 clear-packets highest-block write-agent output-stream)
       (await window-agent)
       [highest-block (-> packets vals last :more?)])))

(def window-agent-map
  {:block 0,
   :packets (sorted-map)
   :more? true})

(defn make-writer-agent
  "Returns an empty file writer agent"
  ([] (agent 0)))

(defn make-window-agent
  "Returns an empty window agent"
  ([] (agent window-agent-map)))

(defn sliding-session
  "Runs a session with the provided proxy server using sliding window."
  ([window-size url output-stream socket server-address server-port timeout]
     (let [; need 1 less than the window size in the receive loop
           window-size-dec (dec window-size)
           window-agent (make-window-agent)
           file-writer (make-writer-agent)
           write-bytes (partial write-bytes output-stream)
           window-results #(window-results window-agent
                                           file-writer
                                           output-stream)
           rrq-packet (tftp/rrq-packet url
                                       window-size
                                       server-address
                                       server-port)
           make-data-packet #(tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           send-rrq (partial tftp/send socket rrq-packet)
           send-ack (fn send-ack [block]
                      (if (zero? block)
                        (send-rrq)
                        (tftp/send socket
                                   (tftp/ack-packet block
                                                    server-address
                                                    server-port))))
           exit-time (partial math/elapsed-time timeout)
           window-time (partial math/elapsed-time tftp/*window-time*)]
       (loop [last-block 0
              session-time-limit (exit-time)
              packets (repeatedly make-data-packet)]
         (send-ack last-block)
         (verbose "ACK:" last-block)
         (let [packets ; infinite lazy sequence of unused packets
               (loop [received 0
                      packets packets
                      window-time-limit (exit-time)]
                 (verbose "received:" received
                          "window-size:" window-size)
                 (if-let [received?
                          (try
                            (tftp/recv* socket (first packets))
                            true
                            (catch java.net.SocketTimeoutException e
                              false))]
                   (do
                     (send-off window-agent
                               add-packet (first packets)
                               server-address server-port)
                     (if (dbg (and (< received window-size-dec)
                                   (> window-time-limit (System/nanoTime))))
                       (recur (inc received) (rest packets) window-time-limit)
                       (rest packets)))
                   (if (> window-time-limit (System/nanoTime))
                     (recur received packets window-time-limit)
                     packets)))
               [highest-block more?] (window-results)]
           (verbose "highest block:" highest-block
                    ", more?:" more?)
           (cond
            (> (System/nanoTime) session-time-limit)
            (util/exit 1 "Session timed out.")

            (= highest-block last-block)
            (recur highest-block session-time-limit packets)

            more?
            (recur highest-block (exit-time) packets)

            :default
            (final-ack highest-block socket server-address server-port
                       2e9 (first packets))))))))

(comment
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
             exit-time (partial math/elapsed-time timeout)
             window-time (partial math/elapsed-time tftp/*window-time*)]
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
  ([url {:keys [hostname IPv6? output port window-size timeout] :as options}]
     (let [ip-fn (if IPv6? IPv6-address IPv4-address)
           address (ip-fn hostname)
           session-fn (if (not= 0 window-size)
                        (partial sliding-session window-size)
                        lockstep-session)]
       (verbose "Connecting to" address "at port" port)
       (io/delete-file output true)
       (with-open [socket (tftp/socket tftp/*timeout*)
                   output-stream (io/output-stream (io/file output)
                                                   :append true)]
         (session-fn url output-stream socket address port
                     (* timeout 1e9))))))
