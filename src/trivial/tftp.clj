(ns trivial.tftp
  (:require [gloss.core :refer [defcodec ordered-map repeated string]]
            [gloss.io :refer [contiguous decode encode]]
            [trivial.util :as util])
  (:import [java.net DatagramPacket DatagramSocket SocketTimeoutException]))

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
  "Receives a packet"
  ([socket packet]
     (if (and *drop* (util/prob 0.01))
       (throw (new SocketTimeoutException "Dropping packet."))
       (.receive socket packet))
     packet))

(defn recv-stream
  "Receives a stream"
  ([socket packet] nil))

(defn recv-request
  "Receives a request (RRQ or SRQ) packet from the socket, returning the mapping
    :Filename - the name of the requested file
    :TID      - the Transfer ID of the sender (i.e. the port #)
    :address  - the address of the sender
    :sliding? - whether or not to use sliding-window
  Throws a SocketException if a timeout occurs.
  Throws an Exception if the wrong kind of packet is received."
  ([socket packet]
     (let [msg (recv socket packet)
           contents (decode rrq-encoding msg)]
       (if ([RRQ SRQ] (:Opcode contents))
         {:Filename (:Filename contents),
          :TID (.getPort packet),
          :address (.getAddress packet),
          :sliding? (= SRQ (:Opcode contents))}
         (throw (Exception. "Non-request packet received."))))))

; Change this so it returns a map instead. Also change
; the client/lockstep-session function to account for this change
(defn recv-data
  "Receives a DATA packet from the socket, returning a mapping containing
  the details of the packet:
    :Block   - the block #
    :Data    - the body of the message
    :more?   - boolean indicating whether more packets will follow
    :TID     - transfer ID (i.e. port #)
    :address - the address the packet was received from"
  ([socket]
     (recv-data socket (datagram-packet (byte-array DATA-SIZE))))
  ([socket packet]
     (let [msg (.getData (recv socket packet))
           contents (decode data-encoding [msg])
           data (:Data contents)
           more? (= (count data) BLOCK-SIZE)
           block (:Block contents)]
       (if (= (:Opcode contents) DATA)
         {:Block block,
          :Data data,
          :more? more?,
          :TID (.getPort packet),
          :address (.getAddress packet)}))))

(defn recv-slide
  "Receives a SLIDE packet from the socket, returning the data and a boolean
  indicating whether there will be more packets following it."
  ([socket]
     (recv-data socket (datagram-packet (byte-array DATA-SIZE))))
  ([socket packet]
     (let [msg (recv socket packet)
           contents (decode data-encoding msg)]
       (if (= (:Opcode contents) DATA)
         {:Block (:Block contents),
          :TID (.getPort packet),
          :address (.getAddress packet)}))))

(defn recv-blocknum
  "Same as recv-data, but throws an Exception if the received packet has
  the wrong block #"
  ([socket packet block]
     (let [{:keys [Block] :as msg}
           (recv-data socket packet)]
       (if (= Block block)
         msg
         (throw (new Exception "Packet received with incorrect block #"))))))

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
