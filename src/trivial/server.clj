(ns trivial.server
  (:require [clojure.java.io :refer [input-stream]]
            [trivial.tftp :as tftp]
            [trivial.util :as util]
            [trivial.util :refer [dbg verbose]])
  (:import [java.io FileNotFoundException IOException]
           [java.net InetAddress SocketException URL]))

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
     (let [port (:port options)
           socket (tftp/socket port)
           packet (tftp/datagram-packet (byte-array tftp/DATA-SIZE))
           error (fn error [code msg]
                   (.send socket
                          (tftp/error-packet code msg
                                             (.getAddress packet)
                                             (.getPort packet))))
           optcode-error
           #(error tftp/ILLEGAL-OPERATION "Optcode error: awaiting requests.")
           file-not-found #(str "File " % " not found.")
           file-not-found-error #(error tftp/FILE-NOT-FOUND (file-not-found %))]
       (util/closed-loop
        socket []
        (let [{:keys [Filename TID address sliding?] :as msg}
              (try
                (tftp/recv-request socket packet)
                (catch Exception e
                  (util/verbose (str "Illegal Optcode: "
                                     (.getMessage e)))
                  (optcode-error)
                  {}))

              session-fn (if sliding? sliding-session lockstep-session)]
          (try
            (with-open [stream (input-stream Filename)]
              (session-fn stream socket))
            (catch FileNotFoundException e
              (util/verbose (str "File " Filename " not found."))
                  (file-not-found-error Filename))))))))
