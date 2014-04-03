(ns trivial.seq
  "Functions for dealing with sequences."
  (:require [gloss.io :refer [contiguous]]))

(defmacro buffers->bytes
  "Takes a sequence of ByteBuffers and returns a single contiguous byte-array."
  [buf-seq] `(.array (contiguous ~buf-seq)))

(defn lazy-input
  "Returns a lazy sequence of bytes from an input stream or Reader."
  ([input-stream]
     (let [step (fn step []
                  (let [c (.read input-stream)]
                    (when-not (== c -1)
                      (cons (unchecked-byte c) (lazy-seq (step))))))]
       (lazy-seq (step))))
  ([input-stream bufsize]
     (let [buf (byte-array bufsize)
           step (fn step []
                  (let [n (.read input-stream buf)]
                    (when-not (== n -1)
                      (cons (doall (take n (map unchecked-byte (seq buf))))
                            (lazy-seq (step))))))]
       (lazy-seq (step)))))