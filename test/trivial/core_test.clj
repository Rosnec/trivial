(ns trivial.core-test
  (:use clojure.test
        trivial.server
        trivial.tftp
        trivial.util))

(defn random-bytes
  "Returns an infinite lazy seq of random bytes"
  ([] (map unchecked-byte (repeatedly #(rand-int 0x100000)))))

(defn random-packet
  ""
  ([block address port]
     (random-packet block address port BLOCK-SIZE))
  ([block address port size]
     (data-packet block (take size (random-bytes)) address port)))

(defn random-packets
  ""
  ([address port] (map #(random-packet (inc %) address port) (range)))
  ([n address port]
     (take n (random-packets address port)))
  ([n last-size address port]
     (lazy-cat (-> n dec random-packets)
               (random-packet n address port last-size))))

(deftest server-test
  (testing "Server"
    (binding [*verbose* true]
      (let [address (java.net.Inet4Address/getByName "localhost")
            port 8888
            window-size 4
            two-windows-full-packets (random-packets (* 2 window-size)
                                                     address port)
            window-and-half-full-packets (random-packets (* 1.5 window-size)
                                                         address port)
            two-windows-partial-packet (random-packets (* 2 window-size)
                                                       (/ DATA-SIZE 2)
                                                       address port)
            window-and-half-partial-packet (random-packets (* 1.5 window-size)
                                                           (/ DATA-SIZE 2)
                                                           address port)
            double-full-panorama (partition window-size 1
                                            two-windows-full-packets)
            one-and-half-full-panorama (partition window-size 1
                                                  window-and-half-full-packets)
            double-partial-panorama (partition window-size 1
                                               two-windows-partial-packet)
            one-and-half-partial-panorama
            (partition window-size 1 window-and-half-partial-packet)
            double-full-finalized (window-finalizer double-full-panorama
                                                    window-size
                                                    0 address port false)
            single-full-finalized
            (window-finalizer (next double-full-panorama)
                              window-size
                              window-size address port false)]
        (is (= window-size
               (-> double-full-finalized first count)
               (-> double-full-finalized second count)))
        (is (and (= window-size
                    (-> single-full-finalized first count))
                 (= 1 (-> single-full-finalized second count))))
        (println single-full-finalized)))))
