(ns trivial.tftp
  (:require [gloss.core :refer [defcodec ordered-map string]]
            [gloss.io :refer [decode encode]])
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
     (datagram-packet (first (encode rrq-encoding
                                     {:Filename filename}))
                      address port)))

(defn wrq-packet
  "Create an WRQ packet."
  ([filename address port]
     (datagram-packet (first (encode wrq-encoding
                                     {:Filename filename}))
                      address port)))

(defn send-rrq
  "Sends an RRQ packet over the socket."
  ([filename socket]
     (let [address (.getInetAddress socket)
           port (.getPort socket)
           packet (rrq-packet filename)]
       (.send socket packet))))

(defn recv-rrq
  "Receives an RRQ packet from the socket, returning the filename.
  Throws a SocketException if a timeout occurs.
  Throws an Exception if the wrong kind of packet is received."
  ([socket]
     (recv-rrq socket (byte-array 4)))
  ([socket bytes]
     (let [packet (datagram-packet bytes)
           msg (.receive socket packet)
           contents (decode rrq-encoding msg)]
       (if (= (:Opcode contents) RRQ)
         (:Filename contents)
         (throw (Exception. "Non-RRQ packet received."))))))

(defn recv-data)


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
