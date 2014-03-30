(ns trivial.server
  (:require [clojure.java.io :refer [input-stream]]
            [trivial.tftp :as tftp]
            [trivial.util :as util]
            [trivial.util :refer [dbg verbose]])
  (:import [java.io FileNotFoundException IOException]
           [java.net InetAddress SocketException SocketTimeoutException URL]
           [util.java UnwantedPacketException]))

(defn lockstep-session
  "Sends the contents of stream to client using lockstep."
  ([stream socket address port] (println "yee boiii")))

(defn sliding-session
  "Sends the contents of stream to client using sliding window."
  ([stream socket]
     (comment
       (loop [panorama (partition tftp/*window-size* 1 packets)]
         (let [num-received ...]
           (recur (nthrest num-received panorama)))))))

(defn start
  ([options]
     (let [{:keys [port timeout]} options
           socket (tftp/socket tftp/*timeout* port)
           packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           error-malformed (partial tftp/error-malformed socket)
           error-not-found (partial tftp/error-not-found socket)
           error-opcode-unknown (partial tftp/error-opcode-unknown socket)
           error-opcode-unwanted (partial tftp/error-opcode-unwanted socket)
           error-opcode-ack (fn [opcode address port]
                              (error-opcode-unwanted opcode
                                                     [ACK ERROR]
                                                     address
                                                     port))
           error-opcode-req (fn [opcode address port]
                              (error-opcode-unwanted opcode
                                                     [RRQ SRQ]
                                                     address
                                                     port))]
       (util/with-connection socket
         (loop []
           (let [{:keys [address filename length mode opcode port] :as msg}
                 (try
                   (tftp/recv socket packet)
                   (catch java.net.SocketTimeoutException e
                     {})
                   (catch clojure.lang.ExceptionInfo e
                     (let [{:keys [cause packet]} (ex-data e)
                           {:keys [address opcode port url]} packet]
                       (verbose (.getMessage e))
                       (case cause
                         :malformed (error-malformed address port)
                         :unknown-opcode (error-opcode-unknown opcode
                                                               address
                                                               port)
                         :unwanted-opcode 
                         nil))
                     {}))]
             (if (contains? [RRQ SRQ] opcode)
               (let [sliding-window? (= opcode SRQ)
                     session-fn (if sliding-window?
                                  sliding-session
                                  lockstep-session)]
                 (try
                   (with-open [stream (input-stream filename)]
                     (session-fn stream socket address port))))
               (do
                 (verbose "Non-request packet received:" msg)
                 (error-opcode-req opcode
                                   address
                                   port))))
           (recur))))))
