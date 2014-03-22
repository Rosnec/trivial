(ns trivial.util
  (:require [gloss.io :refer [contiguous]])
  (:import [java.io BufferedReader InputStreamReader]))

; Verbose flag
(def :^dynamic *verbose* false)

(defn exit
  "Exit the program with the status and message if given, otherwise status 0."
  ([]                         (System/exit 0))
  ([status]                   (System/exit status))
  ([status msg] (println msg) (System/exit status)))

(defn print-err
  "Same as print but outputs to stdout."
  ([& more] (binding [*print-readably* nil, *out* *err*] (apply pr more))))

(defn println-err
  "Same as println but outputs to stdout."
  ([& more] (binding [*print-readably* nil, *out* *err*] (apply prn more))))

(defn verbose
  "When *verbose* is true, outputs body to stderr."
  ([& more] (when *verbose* (apply println-err more))))

(defn prob
  "Returns x with probability p, else returns y (or nil if not given)."
  ([p] (prob p p nil))
  ([p x] (prob p x nil))
  ([p x y] (if (< (rand) p) x y)))

(defmacro finally-loop
  "Same as loop, but executes a finally clause after the loop terminates."
  ([finally-clause bindings & body]
     `(try (loop ~bindings ~@body) (finally ~finally-clause))))

(defmacro closed-loop
  "Same as loop, but takes an object with a .close method and calls it after
  the loop terminates."
  ([closable bindings & body]
     `(finally-loop (.close ~closable) ~bindings ~@body)))

(defmacro web-stream
  "Returns a BufferedReader stream from the URL."
  ([url] `(-> (.openStream ~url)
              (InputStreamReader.)
              (BufferedReader.))))

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
