(ns trivial.tftp
  (:require [gloss.core :refer [defcodec enum header ordered-map repeated
                                string]]
            [gloss.io :refer [contiguous decode encode to-byte-buffer]]
            [trivial.math :as math]
            [trivial.seq :as seq]
            [trivial.util :as util]
            [trivial.util :refer [dbg verbose]])
  (:refer-clojure :exclude [send]))

;; Defaults ;;
; default datagram timeout in ms
(def ^:dynamic *timeout* 50) ; 50ms
; default sliding window timeout in ns
(def ^:dynamic *window-time* (* 100 1e6)) ; 100ms
; whether or not to drop packets
(def ^:dynamic *drop* false)

(defcodec opcode
  (enum :uint16-be
        :RRQ :WRQ :DATA :ACK :ERROR))

(defcodec error-code
  (enum :uint16-be
        :UNDEFINED :FILE-NOT-FOUND :ACCESS-VIOLATION :DISK-FULL
        :ILLEGAL-OPERATION :UNKNOWN-TID :FILE-EXISTS :NO-SUCH-USER))

;; Size constants ;;
(def BLOCK-SIZE 512)
(def DATA-SIZE (+ BLOCK-SIZE 4))

;; Octet encoding ;;
(def octet (repeated :ubyte :prefix :none))

;; String encodings ;;
(def delimited-string (string :ascii :delimiters ["\0"]))
(def open-string      (string :ascii))

;; Packet encodings ;;
; read request
(defcodec rrq-encoding
  (ordered-map
   :opcode :RRQ
   :filename delimited-string,
   :window-size :uint16-be))
; sliding request
(defcodec wrq-encoding
  {})
; data
(defcodec data-encoding
  (ordered-map
   :opcode :DATA
   :block  :uint16-be,
   :data   octet))
; acknowledgement
(defcodec ack-encoding
  (ordered-map
   :opcode :ACK
   :block  :uint16-be))
; error
(defcodec error-encoding
  (ordered-map
   :opcode :ERROR
   :error-code error-code
   :error-msg  delimited-string))

(defcodec packet-encoding
  (header
   opcode
   {:RRQ rrq-encoding, :WRQ wrq-encoding,    :DATA data-encoding,
    :ACK ack-encoding, :ERROR error-encoding}
   :opcode))

(defn datagram-packet
  "Constructs a DatagramPacket.
  If only length is specified, constructs a DatagramPacket for receiving
  packets of length.
  If address and port are also specified, constructs a DatagramPacket for
  sending packets to that address and port."
  ([bytes]
     (new java.net.DatagramPacket bytes (alength bytes)))
  ([bytes address port]
     (new java.net.DatagramPacket bytes (alength bytes) address port)))

(defn rrq-packet
  "Create an RRQ packet."
  ([filename window-size address port]
     (datagram-packet (seq/buffers->bytes (encode packet-encoding
                                                  {:opcode :RRQ
                                                   :filename filename
                                                   :window-size window-size}))
                      address port)))

(defn wrq-packet
  "Create an SRQ packet."
  ([filename window-size address port]
     (throw (ex-info "Unsupported Operation"))))

(defn data-packet
  "Create a DATA packet."
  ([block data address port]
     (when (> (count data) BLOCK-SIZE)
       (throw (ex-info "Oversized block of data." {:cause :block-size})))
     (datagram-packet (seq/buffers->bytes (encode packet-encoding
                                                  {:opcode :DATA
                                                   :block block,
                                                   :data data}))
                      address port)))

(defn ack-packet
  "Create an ACK packet."
  ([block address port]
     (datagram-packet (seq/buffers->bytes (encode packet-encoding
                                                  {:opcode :ACK
                                                   :block block}))
                      address port)))

(defn error-packet
  "Create an ERROR packet."
  ([code message address port]
     (datagram-packet (seq/buffers->bytes (encode packet-encoding
                                                  {:opcode :ERROR
                                                   :error-code code,
                                                   :error-msg message}))
                      address port)))

(defn send
  "Sends the packet over the socket"
  ([socket packet] (.send socket packet)))

(defn error-opcode
  "Sends an unwanted opcode error through the socket to the
  specified address and port."
  ([socket opcode-unwanted opcode-wanted address port]
     (send socket
           (error-packet :ILLEGAL-OPERATION
                         (str "Unwanted opcode: "
                              opcode-unwanted
                              ". Want opcode(s): "
                              opcode-wanted)
                         address
                         port))))

(defn error-malformed
  "Sends a malformed packet error packet through the socket to the
  specified address and port."
  ([socket address port]
     (send socket
           (error-packet :UNDEFINED
                         "Malformed packet"
                         address
                         port))))

(defn error-not-found
  "Sends a file not found error packet through the socket to the
  specified address and port."
  ([socket filename address port]
     (send socket
           (error-packet :FILE-NOT-FOUND
                         (str "File " filename " not found.")
                         address
                         port))))

(defn error-tid
  "Sends an unknown TID error packet through the socket to the
  specified address and port."
  ([socket address port]
     (send socket
           (error-packet :UNKNOWN-TID
                         (str "TID " port " not recognized.")
                         address
                         port))))

(defn recv*
  ([socket packet]
     (if (and *drop* (math/prob 0.01))
       (throw (new java.net.SocketTimeoutException "Dropping packet."))
       (.receive socket packet))))

(defn recv
  "Receives a packet. If *drop* is true, has a 1% probability of dropping
  the packet and throwing a timeout exception.
  If an address and port are given, only accepts the packet if its address
  and port match the given ones.
  Throws a SocketTimeoutException if it times out, and returns an empty map
  if no valid packet is received. Might want to change this to throw a custom
  NoValidPacketException."
  ([socket packet]
     (recv* socket packet)
     (let [length (.getLength packet)
           ; might have to use different methods (e.g. getLocalAddress)
           address (.getAddress packet)
           port (.getPort packet)
           data (.getData packet)
           buffer (java.nio.ByteBuffer/wrap data 0 length)]
       (assoc (try
                (decode packet-encoding buffer)
                (catch Exception e
                  (throw (ex-info "Malformed packet"
                                  {:cause :malformed
                                   :address address
                                   :port port}
                                  e))))
         :address address
         :length length
         :port port)))
  ([socket packet address port]
     (let [packet (recv socket packet)]
       (if (and (= (:address packet) address) (= (:port packet) port))
         packet
         (let [{:keys [address port]} packet]
           (throw (ex-info "Unknown sender"
                           {:cause :unknown-sender
                            :address address
                            :port port})))))))

(defn stream-to-packets
  "Takes a stream of bytes along with an address and port to send to,
  and returns a lazy sequence of packets containing that data."
  ([stream address port]
     (let [packet-data (->> stream
                            seq/lazy-input
                            (seq/partition-extra BLOCK-SIZE))]
       (map (fn [data block] (data-packet block data address port))
            packet-data
            (next (range))))))

(defn empty-data-packet
  "Returns an empty data packet with the given block #. Used as the terminating
  signal for TFTP data transfer."
  ([block address port]
     (data-packet block [] address port)))

(defn socket
  "Constructs a DatagramSocket."
  ([timeout]
     (doto (new java.net.DatagramSocket)
       (.setSoTimeout timeout)))
  ([timeout port]
     (doto (new java.net.DatagramSocket port)
       (.setSoTimeout timeout)))
  ([timeout port address]
     (doto (new java.net.DatagramSocket port address)
       (.setSoTimeout timeout))))
