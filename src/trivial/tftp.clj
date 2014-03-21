(ns trivial.tftp
  (:require [gloss.core :refer [defcodec ordered-map string]]
            [gloss.io :refer [decode encode]])
  (:import [java.net DatagramPacket DatagramSocket]))

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
  ([length]
     (DatagramPacket. (byte-array length) length))
  ([length address port]
     (DatagramPacket. (byte-array length) length address port)))

(defn datagram-socket
  "Constructs a DatagramSocket."
  ([] (DatagramSocket.))
  ([port] (DatagramSocket. port))
  ([port address] (DatagramSocket. port address)))
