(ns trivial.tftp
  (:require [gloss.core :refer [defcodec ordered-map string]]
            [gloss.io :refer [contiguous decode encode]]
            [trivial.util :as util])
  (:import [java.net DatagramPacket DatagramSocket]))

;; Defaults ;;
; default datagram timeout in ms
(def *timeout* 1000)
; default number of retries
(def *retries* 10)

;; Opcodes ;;
(def RRQ   (short 1))
(def WRQ   (short 2)) ; writing not implemented
(def DATA  (short 3))
(def ACK   (short 4))
(def ERROR (short 5))
(def SLIDE (short 6)) ; sliding window DATA

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
; write request
(defcodec wrq-encoding
  (ordered-map
   :Opcode WRQ,
   :Filename delimited-string,
   :Mode octet-mode))
; lock-step data
(defcodec data-encoding
  (ordered-map
   :Opcode DATA,
   :Block :int16,
   :Data open-string))
; sliding-window data
(defcodec slide-encoding
  (ordered-map
   :Opcode SLIDE,
   :Block :int16,
   :Data open-string))
; acknowledgement
(defcodec ack-encoding
  (ordered-map
   :Opcode ACK,
   :Block :int16))
; error
(defcodec error-encoding
  (ordered-map
   :Opcode ERROR,
   :ErrorCode :int16,
   :ErrMsg delimited-string))

; Probably unnecessary, since making a socket without specifying a
; port causes it to pick a port in the interval [0x0 0x10000)
(defn transfer-identifier
  "Randomly generates a transfer-identifier, which can be any number
  between 0 and 65535."
  ([] (rand-int 0x10000)))

(defn datagram-packet
  "Constructs a DatagramPacket.
  If only length is specified, constructs a DatagramPacket for receiving
  packets of length.
  If address and port are also specified, constructs a DatagramPacket for
  sending packets to that address and port."
  ([bytes]
     (DatagramPacket. bytes (alength bytes)))
  ([bytes address port]
     (DatagramPacket. bytes (alength bytes) address port)))

(defn rrq-packet
  "Create an RRQ packet."
  ([filename address port]
     (datagram-packet (util/buffers->bytes (encode rrq-encoding
                                                   {:Filename filename}))
                      address) port))

(defn wrq-packet
  "Create an WRQ packet."
  ([filename address port]
     (datagram-packet (util/buffers->bytes (encode wrq-encoding
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

(defn data-packet
  "Create a SLIDE packet."
  ([block data address port]
     (when (> (count data) BLOCK-SIZE)
       (throw (Exception. "Oversized block of data.")))
     (datagram-packet (util/buffers->bytes (encode slide-encoding
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

(defn recv-rrq
  "Receives an RRQ packet from the socket, returning a map containing:
    :Filename - the name of the requested file
    :TID      - the Transfer ID of the sender (i.e. the port #)
    :address  - the address of the sender
  Throws a SocketException if a timeout occurs.
  Throws an Exception if the wrong kind of packet is received."
  ([socket]
     (recv-rrq socket (byte-array 4)))
  ([socket bytes]
     (let [packet (datagram-packet bytes)
           msg (.receive socket packet)
           contents (decode rrq-encoding msg)]
       (if (= (:Opcode contents) RRQ)
         {:Filename (:Filename contents),
          :TID (.getPort packet),
          :address (.getAddress packet)}
         (throw (Exception. "Non-RRQ packet received."))))))

; Change this so it returns a map instead. Also change
; the client/lockstep-session function to account for this change
(defn recv-data
  "Receives a DATA packet from the socket, returning the data and a boolean
  indicating whether there will be more packets following it."
  ([socket]
     (recv-data socket (byte-array DATA-SIZE)))
  ([socket bytes]
     nil))


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
