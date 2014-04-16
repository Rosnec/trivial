(ns trivial.client
  (:require [clojure.core.reducers :as r]
            [clojure.java.io :as io]
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
         ; send the ack
         (tftp/send socket (tftp/ack-packet block server-address server-port))
         ; await a response
         (let [{:keys [address opcode port] :as response}
               (try
                 (tftp/recv socket data-packet server-address server-port)
                 (catch java.net.SocketTimeoutException e
                   {})
                 (catch clojure.lang.ExceptionInfo e
                   (verbose "unexpected exception" e)
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
  ([m output-stream & bytes]
     (doseq [b bytes]
       (io/copy (byte-array b) output-stream))
     m))

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
  ([m end-block write-agent output-stream]
     (verbose "time to clear some things up")
     write-agent
     output-stream
     (let [start-block (:block m)
           blocks-to-write (dbg (range (inc start-block) (inc end-block)))
           packets (-> (:packets m)
                       (subseq > start-block <= end-block)
                        vals)
           bytes (map :data packets)
           _ (apply send-off write-agent write-bytes output-stream bytes)
           more? (= (-> packets last :length) tftp/DATA-SIZE)]
       (assoc m
         :block end-block
         :packets (sorted-map)
         :more? more?))))

(defn window-results
  ""
  ([window-agent write-agent output-stream]
     (await window-agent)
     (let [{:keys [block packets]} @window-agent
           highest-block (math/highest-sequential (keys packets) block 1)]
       window-agent
       write-agent
       (send-off window-agent
                 clear-packets highest-block write-agent output-stream)
       (await window-agent)
       [highest-block (:more? @window-agent)])))

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

(defn session
  "Runs a session with the provided proxy server using sliding window."
  ([window-size url output-stream socket server-address server-port timeout]
     (let [; need 1 less than the window size in the receive loop,
           ; computing that number ahead of time
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
         (let [packets ; infinite lazy sequence of unused packets
               (loop [received 0
                      packets packets
                      window-time-limit (exit-time)]
                 (when (zero? received)
                   (send-ack last-block))
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
                     (if (and (< received window-size-dec)
                              (> window-time-limit (System/nanoTime)))
                       (recur (inc received) (rest packets) window-time-limit)
                       (rest packets)))
                   (if (> window-time-limit (System/nanoTime))
                     (recur received packets window-time-limit)
                     packets)))
               [highest-block more?] (window-results)]
           (cond
            (> (System/nanoTime) session-time-limit)
            (do
              (shutdown-agents)
              (util/exit 1 "Session timed out."))

            (= highest-block last-block)
            (recur highest-block session-time-limit packets)

            more?
            (do
              (verbose "There's moar to come")
              (recur highest-block (exit-time) packets))

            :default
            (do (final-ack highest-block socket server-address server-port
                           2e9 (first packets))
                (shutdown-agents))))))))

(defn start
  ([url {:keys [hostname IPv6? output port window-size timeout] :as options}]
     (let [ip-fn (if IPv6? IPv6-address IPv4-address)
           address (ip-fn hostname)]
       (verbose "Connecting to" address "at port" port)
       (io/delete-file output true)
       (with-open [socket (tftp/socket tftp/*timeout*)
                   output-stream (io/output-stream (io/file output)
                                                   :append true)]
         (session window-size url output-stream socket address port
                  (* timeout 1e9))))))
