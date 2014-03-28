(ns trivial.tftp
  (:require [gloss.core :refer [defcodec ordered-map repeated string]]
            [gloss.io :refer [contiguous decode encode to-byte-buffer]]
            [trivial.util :as util])
  (:import [java.net DatagramPacket DatagramSocket SocketTimeoutException]
           [util.java BlockNumberException MalformedPacketException
                      UnknownSenderException UnwantedPacketException]))

;; Defaults ;;
; default datagram timeout in ms
(def ^:dynamic *timeout* 1000)
; default number of retries
(def ^:dynamic *retries* 10)
; number of packets to send concurrently
(def ^:dynamic *window-size* 4)
; whether or not to drop packets
(def ^:dynamic *drop* false)

;; Opcodes ;;
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
   :Opcode RRQ,
   :Filename delimited-string,
   :Mode octet-mode))
; sliding request
(defcodec srq-encoding
  (ordered-map
   :Opcode SRQ,
   :Filename delimited-string,
   :Mode octet-mode))
; data
(defcodec data-encoding
  (ordered-map
   :Opcode DATA,
   :Block :uint16,
   :Data octet))
; acknowledgement
(defcodec ack-encoding
  (ordered-map
   :Opcode ACK,
   :Block :uint16))
; error
(defcodec error-encoding
  (ordered-map
   :Opcode ERROR,
   :ErrorCode :uint16,
   :ErrMsg delimited-string))

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
                                                   {:Filename filename}))
                      address port)))

(defn srq-packet
  "Create an SRQ packet."
  ([filename address port]
     (datagram-packet (util/buffers->bytes (encode srq-encoding
                                                   {:Filename filename}))
                      address port)))

(defn data-packet
  "Create a DATA packet."
  ([block data address port]
     (when (> (count data) BLOCK-SIZE)
       (throw (Exception. "Oversized block of data.")))
     (datagram-packet (util/buffers->bytes (encode data-encoding
                                                   {:Block block,
                                                    :Data data}))
                      address port)))

(defn ack-packet
  "Create an ACK packet."
  ([block address port]
     (datagram-packet (util/buffers->bytes (encode ack-encoding
                                                   {:Block block}))
                      address port)))

(defn error-packet
  "Create an ERROR packet."
  ([code message address port]
     (datagram-packet (util/buffers->bytes (encode error-encoding
                                                   {:ErrorCode code,
                                                    :ErrMsg message}))
                      address port)))

(defn recv
  "Receives a packet. If *drop* is true, has a 1% probability of dropping
  the packet and throwing a timeout exception.
  If an address and TID are given, only accepts the packet if its address
  and TID match the given ones.
  Throws a SocketTimeoutException if it times out, and returns an empty map
  if no valid packet is received. Might want to change this to throw a custom
  NoValidPacketException."
  ([socket packet]
     (if (and *drop* (util/prob 0.01))
       (throw (new SocketTimeoutException "Dropping packet."))
       (.receive socket packet))
     (let [length (util/dbg (.getLength packet))
           ; might have to use different methods (e.g. getLocalAddress)
           address (util/dbg (.getAddress packet))
           port (util/dbg (.getPort packet))
           buffer (to-byte-buffer (.getData packet))
           type (util/dbg (.getShort buffer))
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
         (assoc (decode encoding (.rewind buffer))
           :address address,
           :TID port,
           :length length)
         ; send a malformed packet error back to the sender and return nil
         (do
           (.send socket (error-packet ILLEGAL-OPERATION
                                       (str "No such Opcode " type)
                                       address
                                       port))
           (throw (new MalformedPacketException
                       (str "Received malformed packet: "
                            packet)))))))
  ([socket packet address TID]
     (let [packet (recv socket packet)]
       (if (and (= (:address packet) address) (= (:TID packet) TID))
         packet
         (let [{:keys [address TID]} packet]
           (.send socket (error-packet UNKNOWN-TID
                                       "Who are you?"
                                       address
                                       TID))
           (throw (new UnknownSenderException
                       (str "Packet received from " address
                            " on port " TID))))))))

(defn recv-data
  "Receives a data packet from the given address and port. The packet must
  have Opcode DATA, and its block # must match the given one, or else an
  Exception is thrown."
  ([socket packet address TID block]
     (let [{:keys [Block length Opcode] :as packet}
           (recv socket packet address TID)]
       (cond
        (not= Opcode DATA) (throw (new UnwantedPacketException
                                       "Expecting DATA packet."))
        (= Block block) (do
                          (.send socket (ack-packet block address TID))
                          (assoc packet
                            :more? (= length BLOCK-SIZE)))
        :default (throw (new BlockNumberException
                     (str "Expected Block # " block
                          ", received Block # " Block)))))))

(defn recv-request
  "Receives a request (RRQ or SRQ) packet from the socket."
  ([socket packet]
     (let [{:keys [Opcode] :as packet}
           (recv socket packet)]
       (if (or (= Opcode RRQ) (= Opcode SRQ))
         packet
         (throw (new UnwantedPacketException
                     "Expecting RRQ or SRQ packet."))))))

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
