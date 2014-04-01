(ns trivial.server
  (:require [clojure.java.io :refer [input-stream]]
            [trivial.tftp :as tftp]
            [trivial.util :as util]
            [trivial.util :refer [dbg verbose]])
  (:import [java.net URL]))

(defn lockstep-session
  "Sends the contents of stream to client using lockstep."
  ([packets socket address port timeout]
     (let [recv-packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))]
       (loop [current-block 1
              unacked-packets packets
              exit-time (+ (System/nanoTime) timeout)]
         (when (not (empty? unacked-packets))
           (tftp/send socket (next unacked-packet))
           (let [{:keys [address block port] :as response}
                 (try
                   (recv socket recv-packet)
                   (catch java.net.SocketTimeoutException e
                     {})
                   (catch clojure.lang.ExceptionInfo e
                     (let [{:keys [address cause port]} (ex-data e)]
                       (verbose (.getMessage e))
                       (case cause
                         :malformed (error-malformed address port)
                         :unknown-sender (error-tid address port)
                         nil))
                     {})
                   ; continue here
                   )]))))))

(defn sliding-session
  "Sends the contents of stream to client using sliding window."
  ([window-size packets socket timeout]
     (comment
       (loop [panorama (partition window-size 1 packets)]
         (let [num-received 1]
           (recur (nthrest num-received panorama)))))))

(defn start
  ([options]
     (let [{:keys [port timeout]} options
           socket (tftp/socket tftp/*timeout* port)
           packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           error-malformed (partial tftp/error-malformed socket)
           error-not-found (partial tftp/error-not-found socket)
           error-opcode (partial tftp/error-opcode socket)
           error-opcode-ack (fn [opcode address port]
                              (error-opcode opcode
                                            :ACK
                                            address
                                            port))
           error-opcode-rrq (fn [opcode address port]
                              (error-opcode opcode
                                            :RRQ
                                            address
                                            port))]
       (util/with-connection socket
         (loop []
           (let [{:keys [address filename length mode opcode port
                         window-size]
                  :as msg}
                 (try
                   (tftp/recv socket packet)
                   (catch java.net.SocketTimeoutException e
                     {})
                   (catch clojure.lang.ExceptionInfo e
                     (let [{:keys [address cause packet port]} (ex-data e)
                           {:keys [filename opcode window-size]} packet]
                       (verbose (.getMessage e))
                       (case cause
                         :malformed (error-malformed address port)
                         :opcode (error-opcode opcode
                                               address
                                               port)
                         nil))
                     {}))]
             (verbose "msg:" msg)
             (when (and (not (empty? msg))
                        address
                        port)
               (verbose "Connected to" address "on port" port)
               (if (= opcode :RRQ)
                 (let [session-fn (if (not= 0 window-size)
                                    sliding-session
                                    lockstep-session)]
                   (try
                     (with-open [stream (input-stream filename)]
                       (session-fn (tftp/stream-to-packets stream
                                                           address
                                                           port)
                                   socket
                                   address
                                   port
                                   timeout))
                     (catch java.io.FileNotFoundException e
                       (verbose "Requested file:" filename "not found.")
                       (error-not-found filename address port))))
                 (do
                   (verbose "Non-request packet received:" msg)
                   (error-opcode-rrq opcode
                                     address
                                     port)))))
           (recur))))))
