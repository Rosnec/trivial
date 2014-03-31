(ns trivial.tftp
  (:require [gloss.core :refer [defcodec ordered-map repeated string]]
            [gloss.io :refer [contiguous decode encode to-byte-buffer]]
            [trivial.util :as util]
            [trivial.util :refer [dbg verbose]])
  (:import [java.net DatagramPacket DatagramSocket])
  (:refer-clojure :exclude [send]))

;; Defaults ;;
; default datagram timeout in ms
(def ^:dynamic *timeout* 1000)
; default number of retries
(def ^:dynamic *retries* 10)
; number of packets to send concurrently
(def ^:dynamic *window-size* 4)
; whether or not to drop packets
(def ^:dynamic *drop* false)

;; opcodes ;;
(def RRQ   (short 1))
(def SRQ   (short 2)) ; replace WRQ with SRQ for sliding window reads
(def DATA  (short 3))
(def ACK   (short 4))
(def ERROR (short 5))

;; Error codes ;;
(def UNDEFINED           (short 0))
(def FILE-NOT-FOUND      (short 1))
(def ACCESS-VIOLATION    (short 2))
(def DISK-FULL           (short 3))
(def ILLEGAL-OPERATION   (short 4))
(def UNKNOWN-TID         (short 5))
(def FILE-ALREADY-EXISTS (short 6))
(def NO-SUCH-USER        (short 7))

;; Size constants ;;
(def BLOCK-SIZE 512)
(def DATA-SIZE (+ BLOCK-SIZE 4))

;; Octet encoding ;;
(def octet (repeated :byte))

;; String encodings ;;
(def delimited-string (string :ascii :delimiters ["\0"]))
(def open-string      (string :ascii))

;; Modes ;;
(def octet-mode "OCTET")

;; Packet encodings ;;
; read request
(defcodec rrq-encoding
  (ordered-map
   :opcode :int16-be,
   :filename delimited-string,
   :mode open-string))
; sliding request
(defcodec srq-encoding
  (ordered-map
   :opcode :int16-be,
   :filename delimited-string,
   :mode open-string))
; data
(defcodec data-encoding
  (ordered-map
   :opcode :int16-be,
   :block :int16-be,
   :data octet))
; acknowledgement
(defcodec ack-encoding
  (ordered-map
   :opcode :int16-be,
   :block :int16-be))
; error
(defcodec error-encoding
  (ordered-map
   :opcode :int16-be,
   :error-code :int16,
   :error-msg delimited-string))

(defn datagram-packet
  "Constructs a DatagramPacket.
  If only length is specified, constructs a DatagramPacket for receiving
  packets of length.
  If address and port are also specified, constructs a DatagramPacket for
  sending packets to that address and port."
  ([bytes]
     (new DatagramPacket bytes (alength bytes)))
  ([bytes address port]
     (new DatagramPacket bytes (alength bytes) address port)))

(defn rrq-packet
  "Create an RRQ packet."
  ([filename address port]
     (datagram-packet (util/buffers->bytes (encode rrq-encoding
                                                   {:opcode RRQ
                                                    :filename filename
                                                    :mode octet-mode}))
                      address port)))

(defn srq-packet
  "Create an SRQ packet."
  ([filename address port]
     (datagram-packet (util/buffers->bytes (encode srq-encoding
                                                   {:opcode SRQ
                                                    :filename filename
                                                    :mode octet-mode}))
                      address port)))

(defn data-packet
  "Create a DATA packet."
  ([block data address port]
     (when (> (count data) BLOCK-SIZE)
       (throw (ex-info "Oversized block of data." {:cause :block-size})))
     (datagram-packet (util/buffers->bytes (encode data-encoding
                                                   {:opcode DATA
                                                    :block block,
                                                    :data data}))
                      address port)))

(defn ack-packet
  "Create an ACK packet."
  ([block address port]
     (datagram-packet (util/buffers->bytes (encode ack-encoding
                                                   {:opcode ACK
                                                    :block block}))
                      address port)))

(defn error-packet
  "Create an ERROR packet."
  ([code message address port]
     (datagram-packet (util/buffers->bytes (encode error-encoding
                                                   {:opcode ERROR
                                                    :error-code code,
                                                    :error-msg message}))
                      address port)))

(defn send
  "Sends the packet over the socket"
  ([socket packet] (.send socket packet)))

(defn error-opcode-unknown
  "Sends an unknown opcode error packet through the socket to the
  specified address and port."
  ([socket opcode address port]
     (send socket
           (error-packet ILLEGAL-OPERATION
                         (str "Unknown opcode: "
                              opcode)
                         address
                         port))))

(defn error-opcode-unwanted
  "Sends an unwanted opcode error through the socket to the
  specified address and port."
  ([socket opcode-unwanted opcode-wanted address port]
     (send socket
           (error-packet ILLEGAL-OPERATION
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
           (error-packet UNDEFINED
                         "Malformed packet"
                         address
                         port))))

(defn error-not-found
  "Sends a file not found error packet through the socket to the
  specified address and port."
  ([socket filename address port]
     (send socket
           (error-packet FILE-NOT-FOUND
                         (str "File " filename " not found.")
                         address
                         port))))

(defn error-tid
  "Sends an unknown TID error packet through the socket to the
  specified address and port."
  ([socket address port]
     (send socket
           (error-packet UNKNOWN-TID
                         (str "TID " port " not recognized.")
                         address
                         port))))

(defn recv
  "Receives a packet. If *drop* is true, has a 1% probability of dropping
  the packet and throwing a timeout exception.
  If an address and port are given, only accepts the packet if its address
  and port match the given ones.
  Throws a SocketTimeoutException if it times out, and returns an empty map
  if no valid packet is received. Might want to change this to throw a custom
  NoValidPacketException."
  ([socket packet]
     (if (and *drop* (util/prob 0.01))
       (throw (new java.net.SocketTimeoutException "Dropping packet."))
       (.receive socket packet))
     (let [length (util/dbg (.getLength packet))
           ; might have to use different methods (e.g. getLocalAddress)
           address (util/dbg (.getAddress packet))
           port (util/dbg (.getPort packet))
           buffer (to-byte-buffer (.getData packet))
           type (util/dbg (.getShort buffer))
           ; somehow this is nil when it shouldn't be
           encoding (case type
                      RRQ   rrq-encoding
                      SRQ   srq-encoding
                      DATA  data-encoding
                      ACK   ack-encoding
                      ERROR error-encoding
                      nil)]
       (if encoding
         ; return the decoded packet if it is one of the defined types
         ; includes the sender's address and port, as well as the length
         ; of the data in the packet
         (assoc
             (try
               (dbg (decode encoding (.rewind buffer)))
               (catch Exception e
                 (throw (ex-info "Malformed packet"
                                 {:cause :malformed
                                  :address address
                                  :port port}
                                 e))))
           :address address
           :port port
           :length length)
         (throw (ex-info "Unknown opcode"
                         {:cause :unknown-opcode
                          :address address
                          :port port})))))
  ([socket packet address port]
     (let [packet (recv socket packet)]
       (if (and (= (:address packet) address) (= (:port packet) port))
         packet
         (let [{:keys [address port]} packet]
           (throw (ex-info "Unknown sender"
                           {:cause :unknown-sender
                            :address address
                            :port port})))))))

(defn recv-data
  "Receives a data packet from the given address and port. The packet must
  have opcode DATA, and its block # must match the given one, or else an
  Exception is thrown."
  ([socket packet address port expected-block]
     (let [{:keys [block length opcode] :as packet}
           (recv socket packet address port)]
       (cond
        (not= opcode DATA) (throw (ex-info "Unwanted opcode"
                                           {:cause :opcode
                                            :opcode opcode}))
        (not= block expected-block) (throw (ex-info "Incorrect block #"
                                                    {:cause :block}))
        :default (assoc packet :more? (= length BLOCK-SIZE))))))

(defn recv-request
  "Receives a request (RRQ or SRQ) packet from the socket."
  ([socket packet]
     (let [{:keys [opcode] :as packet}
           (recv socket packet)]
       (if (or (= opcode RRQ) (= opcode SRQ))
         packet
         (throw (ex-info "Unwanted opcode"
                         {:cause :opcode
                          :opcode opcode}))))))

(defn socket
  "Constructs a DatagramSocket."
  ([timeout]
     (doto (DatagramSocket.)
       (.setSoTimeout timeout)))
  ([timeout port]
     (doto (DatagramSocket. port)
       (.setSoTimeout timeout)))
  ([timeout port address]
     (doto (DatagramSocket. port address)
       (.setSoTimeout timeout))))
