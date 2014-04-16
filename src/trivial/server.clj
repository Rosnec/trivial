(ns trivial.server
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [trivial.math :as math]
            [trivial.tftp :as tftp]
            [trivial.util :as util]
            [trivial.util :refer [dbg verbose]]))

(defn send-window
  ([socket window] (doseq [packet window]
                     (verbose "type" (type packet))
                     (tftp/send socket packet))))

(defn result-string
  "Creates a string representation of a results map.
  Returns the empty string if results map is empty."
  ([results]
     (if (not-empty results)
       (str "\n"
            (string/join "\t"
                         [(:time results)
                          (:bytes results)
                          (:window results)
                          (:drop? results)
                          (:version results)]))
       "")))

(defn write-csv
  "Writes the results to a CSV file"
  ([output results]
     (io/copy (result-string results) output)))

(defn session
  "Sends the contents of stream to client using sliding window."
  ([window-size packets socket address port timeout start-time]
     (let [recv-packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           time-to-exit (partial math/elapsed-time timeout)]
       (loop [panorama (partition-all window-size 1 packets)
              num-acked 0
              exit-time (time-to-exit)]
         (cond
          (not-empty panorama)
          (let [window (first panorama)
                ; send the window
                _ (send-window socket window)
                {current-address :address
                 current-port    :port
                 :keys [block opcode]
                 :as response}
                (try
                  (tftp/recv socket recv-packet)
                  (catch java.net.SocketTimeoutException e
                    {})
                  (catch clojure.lang.ExceptionInfo e
                    (let [{:keys [address cause port]} (ex-data e)]
                      (verbose (.getMessage e))
                      (case cause
                        :malformed
                        (tftp/error-malformed socket address port)
                        :unknown-sender
                        (tftp/error-tid socket address port)
                        nil))
                    {}))]
            (if (and (= address current-address)
                     (= port current-port)
                     (= opcode :ACK)
                     (> block num-acked))
              (let [newly-acked (- block num-acked)]
                  (recur (nthrest panorama newly-acked)
                         block
                         (time-to-exit)))
              (if (> exit-time (System/nanoTime))
                  (recur panorama num-acked exit-time)
                  (do
                    (verbose "Session with" address "at port" port "timed out.")
                    {}))))

          :default (do (verbose "Transfer complete")
                       {:time (- (System/nanoTime) start-time)
                        :bytes (+ (* tftp/BLOCK-SIZE (dec num-acked))
                                  (-> panorama
                                      last
                                      count
                                      (- tftp/OVERHEAD-SIZE)))
                        :window window-size
                        :drop? tftp/*drop*
                        :version (type address)}))))))

(defn start
  ([{:keys [output port timeout] :as options}]
     (io/delete-file output true)
     (with-open [socket (tftp/socket tftp/*timeout* port)
                 output (io/output-stream (io/file output)
                                          :append true)]
       (io/copy (string/join "\t"
                             ["#time"
                              "bytes"
                              "window"
                              "drop?"
                              "version"])
                output)
       (let [packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
             error-opcode-rrq (fn [opcode address port]
                                (tftp/error-opcode socket
                                                   opcode
                                                   :RRQ
                                                   address
                                                   port))]
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
                         :malformed (tftp/error-malformed socket address port)
                         :opcode (tftp/error-opcode socket
                                                    opcode
                                                    address
                                                    port)
                         nil))
                     {}))]
             (when (and (not (empty? msg))
                        address
                        port)
               (verbose "Connected to" address "on port" port)
               (if (= opcode :RRQ)
                 (try
                   (let [results
                         (with-open [stream (io/input-stream filename)]
                           (session window-size
                                    (tftp/stream-to-packets stream
                                                            address
                                                            port)
                                    socket
                                    address
                                    port
                                    (* timeout 1e9)
                                    (System/nanoTime)))]
                     (if (not-empty results)
                       (write-csv output results)
                       (println "Failed session.")))
                   (catch java.io.FileNotFoundException e
                     (verbose "Requested file:" filename "not found.")
                     (tftp/error-not-found socket filename address port)))
                 (do
                   (verbose "Non-request packet received:" msg)
                   (error-opcode-rrq opcode
                                     address
                                     port)))))
           (recur))))))
