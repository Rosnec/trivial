(ns trivial.tftp
  (:require [gloss.core :refer [defcodec ordered-map string]]
            [gloss.io :refer [decode encode]])
  (:import [java.net DatagramPacket]))

;; Opcodes ;;
(def RRQ   (short 1))
(def WRQ   (short 2)) ; writing not implemented
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

;; String encodings ;;
(def delimited-string (string :ascii :delimiters ["\0"]))
(def open-string      (string :ascii))

;; Modes ;;
(def octet-mode "OCTET")

;; Packet types ;;
(defcodec rrq-packet
  (ordered-map
   :Opcode RRQ,
   :Filename delimited-string,
   :Mode octet-mode))

(defcodec wrq-packet
  (ordered-map
   :Opcode WRQ,
   :Filename delimited-string,
   :Mode octet-mode))

(defcodec data-packet
  (ordered-map
   :Opcode DATA,
   :Block :int16,
   :Data open-string))

(defcodec ack-packet
  (ordered-map
   :Opcode ACK,
   :Block :int16))

(defcodec error-packet
  (ordered-map
   :Opcode ERROR,
   :ErrorCode :int16,
   :ErrMsg delimited-string))
