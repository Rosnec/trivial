(ns trivial.server
  (:require [trivial.tftp :as tftp]
            [trivial.util :as util])
  (:import [java.net InetAddress SocketException URL]))

(defn lockstep-session
  ""
  ([data client]))

(defn sliding-session
  ""
  ([data client]
     (comment
       (loop [panorama (partition tftp/*window-size* 1 packets)]
         (let [num-received ...]
           (recur (nthrest num-received panorama)))))))

(defn start
  ([options]
     (let [verbose? (:verbose? options)
           port (:port options)
           socket (tftp/socket port)
           packet (tftp/datagram-packet (byte-array DATA-SIZE))
           error (fn [code msg] (error-packet code msg
                                             (.getAddress packet)
                                             (.getPort packet)))
           optcode-error]
       (util/closed-loop
        socket []
        (try
          (let [{:keys [Filename TID address] :as msg}
                (try
                  (tftp/recv-rrq socket packet)
                  (catch Exception e
                    (.send socket
                           (error-packet ILLEGAL-OPERATION
                                         "Optcode error: RRQ only."
                                         (.getAddress packet)
                                         (.getPort packet)))
                    {}))
                url
                (try
                  (when (not-empty msg)
                    (new URL Filename))
                  (catch IOException e
                    (.send socket
                           (error-packet FILE-NOT-FOUND
                                         (str "File "
                                              Filename
                                              " not found.")
                                         (.getAddress packet)
                                         (.getPort packet)))))
                stream (if ) (util/web-stream url)]
            
            )
