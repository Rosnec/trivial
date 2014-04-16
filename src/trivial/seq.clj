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
           step (fn step [prev]
                  (let [n (.read input-stream buf)]
                    (if-not (== n -1)
                      (cons (doall (take n (map unchecked-byte (seq buf))))
                            (lazy-seq (step n)))
                      (when (== prev bufsize)
                        [()]))))]
       (lazy-seq (step 0)))))

(defn partition-extra
  "Like partition-all, but if the final partition is exactly of size n, adds
  an empty partition to the end (i.e. guarantees that the last partition will
  not be full)."
  ([n coll]
     (partition-extra n n coll))
  ([n step coll]
     (let [next (fn next [prev coll]
                  (if-let [s (seq coll)]
                    (let [seg (doall (take n s))]
                      (cons seg (lazy-seq (next seg (nthrest s step)))))
                    (when (= (count prev) n)
                      [()])))]
       (lazy-seq (next () coll)))))
