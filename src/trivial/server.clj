(ns trivial.server
  (:require [trivial.tftp :as tftp]
            [trivial.util :as util])
  (:import [java.net InetAddress SocketException]))

(defn start
  ([options]
     (let [port (:port options)
           socket (tftp/socket port)]
       (try
         (loop []
           (try
             (let [client ])))
         (finally
           (.close socket))))))
