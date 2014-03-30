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
  ([stream client]))

(defn sliding-session
  "Sends the contents of stream to client using sliding window."
  ([stream client]
     (comment
       (loop [panorama (partition tftp/*window-size* 1 packets)]
         (let [num-received ...]
           (recur (nthrest num-received panorama)))))))

(defn start
  ([options]
     (let [{:keys [port timeout]} options
           socket (tftp/socket tftp/*timeout* port)
           packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
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
                                                     port))

           file-not-found #(str "File " % " not found.")
           file-not-found-error #(error tftp/FILE-NOT-FOUND (file-not-found %))]
       (util/with-connection socket
         (loop []
           (let [{:keys [Filename TID address sliding?] :as msg}
                 (try
                   (let [msg (tftp/recv-request socket packet)]
                     (util/verbose "Received request")
                     msg)
                   (catch SocketTimeoutException e
                     {})
                   (catch MalformedPacketException e
                     (util/verbose (.getMessage e))
                     {})
                   (catch UnwantedPacketException e
                     (util/verbose e)
                     (util/verbose (str "Illegal Optcode: "
                                        (.getMessage e)))
                     (optcode-error)
                     {}))
                 session-fn (if sliding? sliding-session lockstep-session)]
             (when-let [session-fn (and (not (empty? msg))
                                        (if sliding?
                                          sliding-session
                                          lockstep-session))]
               (println "You shouldn't be here!")
               (try
                 (with-open [stream (input-stream Filename)]
                   (session-fn stream socket))
                 (catch FileNotFoundException e
                   (util/verbose (str "File " Filename " not found."))
                   (file-not-found-error Filename))))
             (recur)))))))
