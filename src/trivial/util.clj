(ns trivial.util
  (:require [gloss.io :refer [contiguous]]))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn try-times*
  "Executes thunk. If an exception is thrown, will retry. At most n retries
  are done. If still some exception is thrown it is bubbled upwards in
  the call chain."
  [n thunk]
  (loop [n n]
    (if-let [result (try
                      [(thunk)]
                      (catch Exception e
                        (when (zero? n)
                          (throw e))))]
      (result 0)
      (recur (dec n)))))

(defmacro try-times
  "Executes body. If an exception is thrown, will retry. At most n retries
  are done. If still some exception is thrown it is bubbled upwards in
  the call chain."
  [n & body]
  `(try-times* ~n (fn [] ~@body)))

(defn try-callback-times*
  "Executes thunk. If an exception is thrown, will call callback and then
  retry. At most n retries are done. If still some exception is thrown it is
  bubbled upwards in the call chain."
  ([n callback initial? thunk]
     (when initial? (callback))
     (loop [n n]
       (if-let [result (try
                         [(thunk)]
                         (catch Exception e
                           (if (zero? n)
                             (throw e)
                             (callback))))]
         (result 0)
         (recur (dec n))))))

(defmacro try-callback-times
  "Executes body. If an exception is thrown, will call callback and then retry.
  At most n retries are done. If still some exception is thrown it is bubbled
  upwards in the call chain."
  [n callback initial? & body]
  `(try-callback-times* ~n ~callback ~initial? (fn [] ~@body)))

(defmacro buffers->bytes
  "Takes a sequence of ByteBuffers and returns a single contiguous byte-array."
  [buf-seq] `(.array (contiguous ~buf-seq)))
