(ns trivial.server
  (:require [clojure.java.io :refer [input-stream]]
            [trivial.tftp :as tftp]
            [trivial.util :as util]
            [trivial.util :refer [dbg verbose]]))

(defn lockstep-session
  "Sends the contents of stream to client using lockstep."
  ([packets socket address port timeout]
     (let [recv-packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           time-to-exit (fn [] (+ (System/nanoTime) timeout))]
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
                         (tftp/error-tid socket address port)
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
               (tftp/error-tid socket current-address current-port)))
           ; sent all packets, but final packet had 512B of data,
           ; so we need to send a terminating 0B packet
           (= prev-length tftp/DATA-SIZE)
           (recur block nil [(tftp/data-packet block [] address port)]
                  (time-to-exit))

           :default (do (verbose "Transfer complete.")
                        true))))))

(defn sliding-session
  "Sends the contents of stream to client using sliding window."
  ([window-size packets socket address port timeout]
     (let [recv-packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           time-to-exit (fn [] (+ (System/nanoTime) timeout))]
       (loop [panorama (partition window-size 1 packets)
              num-acked 0
              empty-packet nil
              exit-time (time-to-exit)]
         (cond
          (not-empty panorama)
          (let [window (first panorama)
                ; determine whether an empty packet needs to be sent
                empty-packet (and (nil? (next panorama)) ; last window
                                  (= tftp/DATA-SIZE
                                     (.getLength (last window))))
                [window empty-packet]
                (if empty-packet
                  (let [current-window-size (count window)]
                    (if (< current-window-size window-size)
                      [(lazy-cat window
                                 [(tftp/data-packet (+ num-acked
                                                        current-window-size
                                                        1)
                                                     []
                                                     address
                                                     port)])
                       false]
                      [window
                       (tftp/data-packet (+ num-acked
                                            current-window-size
                                            1)
                                         []
                                         address
                                         port)]))
                  [window false])
                ; send the window
                _ (doseq [packet window] (tftp/send socket packet))
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
                     (= opcode :ACK))
              (if (> block num-acked)
                (let [newly-acked (- block num-acked)]
                  (recur (nthrest newly-acked panorama)
                         block
                         ; get the length of the last acked packet in the window
                         (.getLength (nth (dec newly-acked) window))
                         (time-to-exit)))
                (if (> exit-time (System/nanoTime))
                  (recur panorama num-acked empty-packet exit-time)
                  (do
                    (verbose "Session with" address "at port" port "timed out.")
                    false)))))

          empty-packet
          (tftp/send socket empty-packet)

          :default (do (verbose "Transfer complete")
                       true))))))

(defn start
  ([options]
     (let [{:keys [port timeout]} options
           socket (tftp/socket tftp/*timeout* port)
           packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           error-opcode-rrq (fn [opcode address port]
                              (tftp/error-opcode socket
                                                 opcode
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
                 (let [session-fn (if (not= 0 window-size)
                                    (partial sliding-session window-size)
                                    lockstep-session)]
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
                       (when success?
                         (println (str "Transfer Time: "
                                       (/ (- (System/nanoTime) start-time)
                                          1e9)
                                       "s"))))
                     (catch java.io.FileNotFoundException e
                       (verbose "Requested file:" filename "not found.")
                       (tftp/error-not-found socket filename address port))))
                 (do
                   (verbose "Non-request packet received:" msg)
                   (error-opcode-rrq opcode
                                     address
                                     port)))))
           (recur))))))
