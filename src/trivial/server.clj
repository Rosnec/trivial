
(ns trivial.server
  (:require [clojure.java.io :refer [input-stream]]
            [trivial.math :as math]
            [trivial.tftp :as tftp]
            [trivial.util :as util]
            [trivial.util :refer [dbg verbose]]))

(defn lockstep-session
  "Sends the contents of stream to client using lockstep."
  ([packets socket address port timeout]
     (let [recv-packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           time-to-exit (partial math/elapsed-time timeout)]
       (loop [block 1
              prev-length nil
              unacked-packets packets
              exit-time (time-to-exit)]
         (cond
          ; still have packets to send
          (not-empty unacked-packets)
          (let [packet (first unacked-packets)
                length (.getLength packet)
                _ (tftp/send socket packet)
                {current-address :address
                 current-block   :block
                 current-port    :port
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
                        (do
                          (tftp/error-tid socket address port))
                        nil))
                    {}))]
            (if (and (= address current-address) (= port current-port))
              (if (= block current-block)
                (recur (inc block)
                       length
                       (next unacked-packets)
                       (time-to-exit))
                (if (> exit-time (System/nanoTime))
                  (recur block prev-length unacked-packets exit-time)
                  (do
                    (verbose "Session with" address "at port" port
                             "timed out.")
                    false)))
              (when (not (or (nil? current-address) (nil? current-port)))
                (tftp/error-tid socket current-address current-port))))
          ; sent all packets, but final packet had 512B of data,
          ; so we need to send a terminating 0B packet
          (= prev-length tftp/DATA-SIZE)
          (recur block nil [(tftp/data-packet block [] address port)]
                 (time-to-exit))

          :default (do (verbose "Transfer complete.")
                       true))))))

(defn make-final-packet
  "Makes the final packet for a file transfer, for use when the last packet had
  exactly 512 bytes of data. Signals EOF to the client on the socket."
  ([block packets-left address port]
     (tftp/empty-data-packet (dbg (+ block packets-left 1)) ; off-by-one? probably
                             address port)))



(defn window-finalizer
  "If the next window in the panorama is the final window, adds an
  empty data packet to the end of it to signal EOF to the client, in
  the event that the final packet is exactly the maximum size. If the
  window is full, puts packet in the next window. If the window has
  already been finalized, nothing is done."
  ([panorama window-size block address port processed?]
     (if (or (dbg (nnext panorama)) processed?)
       ; nothing needs to be done if there is another window or if the
                                        ; panorama has already been processed
       (do
         (verbose "do nothing")
         panorama)
       (let [window (first panorama)
             last-packet-size (-> window last .getLength)]
         (if (= last-packet-size tftp/DATA-SIZE)
           (let [packets-in-window (count window)
                 final-packet (make-final-packet block   packets-in-window
                                                 address port)]
             (if (= window-size packets-in-window)
               (dbg [window [final-packet]])
               (dbg [(lazy-cat window [final-packet])])))
           (do
             (verbose "aready done lol")
             panorama))))))

(defn send-window
  ([socket window] (doseq [packet window]
                     (verbose "type" (type packet))
                     (tftp/send socket packet))))

(defn sliding-session
  "Sends the contents of stream to client using sliding window."
  ([window-size packets socket address port timeout]
     (verbose "it slides!")
     (let [recv-packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           time-to-exit (partial math/elapsed-time timeout)]
       (loop [panorama (partition window-size 1 packets)
              num-acked 0
              exit-time (time-to-exit)]
         (verbose "ack count:" num-acked)
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
              (let [newly-acked (dbg (- (dbg block) (dbg num-acked)))]
                  (recur (nthrest panorama newly-acked)
                         block
                         (time-to-exit)))
              (if (> exit-time (System/nanoTime))
                  (recur panorama num-acked exit-time)
                  (do
                    (verbose "Session with" address "at port" port "timed out.")
                    false))))

          :default (do (verbose "Transfer complete")
                       true))))))

(defn start
  ([{:keys [port timeout] :as options}]
     (with-open [socket (tftp/socket tftp/*timeout* port)]
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
                 (let [session-fn
                       (if (zero? window-size)
                         (dbg lockstep-session)
                         (dbg (partial sliding-session window-size)))]
                   (try
                     (let [start-time (System/nanoTime)
                           success?
                           (with-open [stream (input-stream filename)]
                             (session-fn (tftp/stream-to-packets stream
                                                                 address
                                                                 port)
                                         socket
                                         address
                                         port
                                         (* timeout 1e9)))]
                       (if success?
                         (println (str "Transfer Time: "
                                       (/ (- (System/nanoTime) start-time)
                                          1e9)
                                       "s"))
                         (println "Failure.")))
                     (catch java.io.FileNotFoundException e
                       (verbose "Requested file:" filename "not found.")
                       (tftp/error-not-found socket filename address port))))
                 (do
                   (verbose "Non-request packet received:" msg)
                   (error-opcode-rrq opcode
                                     address
                                     port)))))
           (recur))))))
