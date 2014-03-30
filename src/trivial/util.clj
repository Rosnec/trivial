(ns trivial.util
  (:require [gloss.io :refer [contiguous]])
  (:import [java.io BufferedReader InputStreamReader]))

; Verbose flag
(def ^:dynamic *verbose* false)

(defmacro assert-args
  {:private true}
  ([& pairs]
     `(do (when-not ~(first pairs)
            (throw (IllegalArgumentException.
                    (str (first ~'&form) " requires "
                         ~(second pairs) " in " ~'*ns* ":"
                         (:line (meta ~'&form))))))
          ~(let [more (nnext pairs)]
             (when more
               (list* `assert-args more))))))

(defmacro dbg
  "Executes the expression x, then prints the expression and its output to
  stderr, while also returning value.
       example=> (dbg (+ 2 2))
       dbg: (+ 2 2) = 4
       4"
  ([x] `(dbg ~x "dbg:" "="))
  ([x msg] `(dbg ~x ~msg "="))
  ([x msg sep] `(let [x# ~x] (println-err ~msg '~x ~sep x#) x#)))

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

(defn print-byte-buffer
  "Prints n chars from a byte buffer to *out*."
  ([buf n] (dotimes [_ n]
             (print (.getChar buf)))))

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

(defmacro with-connection
  "Evaluates the body in an implicit do. Closes the closable when an exception
  occurs or evaluation completes."
  ([closable & body]
     `(try
        ~@body
        (finally (.close ~closable)))))

(defmacro web-stream
  "Returns a BufferedReader stream from the URL."
  ([url] `(-> (.openStream ~url)
              (InputStreamReader.)
              (BufferedReader.))))

(defmacro byte-stream->seq
  "Lazily transforms a byte stream into a chunked seq of bytes."
  ([stream buffer] (repeatedly #(do (.read stream buffer) (seq buffer)))))

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

(comment (defn try-catch-times*

           ([n catch exception thunk]
              (loop [remaining n]
                (if-let [result (try
                                  [(thunk)]
                                  (catch exception e
                                    (if (zero? remaining)
                                      (throw e)
                                      (callback))))])))))

(defmacro try-timez
  [n func & catch-finally]
  `(loop [remaining# ~n]
     (if-let [result# (try [(~func)] ~@catch-finally)]
       (first result#)
       (recur (dec remaining#)))))

(defmacro catch-throw
  "Builds up a vector of catch clauses from the except-response pairs.
  An except-response pair (excresp) takes the form
    [e (fn [e])]
  where e is an exception and (fn [e]) is a single argument function which is
  called with e if e is caught."
  {:private true}
  ([n & excresps]
     (loop [excepts (partition 2 excresps)
            catches []]
       (println excepts)
       (if-let [[exception func] (first excepts)]
         (do
           (println "eyyo")
           (recur (next excepts)
                  (conj catches `(catch ~exception e#
                                        ; call func before throwing exception,
                                        ; in case func has side effects
                                   (~func e#)
                                   (when (zero? ~n)
                                     (throw e#))))))
         catches))))



(defmacro try-timesies
  "Tries to call func n times. Takes any number of exception-expr pairs,
  which consist of the exception to catch, and a function of that exception to
  call when the exception is caught.
   
    "
  ([n func & except-funcs]
     `(loop [remaining# ~n]
        (if-let [result#
                 (try
                   [(~func)]
                   ~@(loop [excepts (partition 2 except-funcs)
                            catches []]
                       (if-let [[exception func] (first excepts)]
                         (recur (next excepts)
                                (conj catches (list 'catch '~exception 'e
                                                 '('~func 'e)
                                                 ('when ('zero? '~n)
                                                   ('throw 'e)))))
                         catches)))]
          (first result#)
          (recur (dec remaining#))))))

(defmacro buffers->bytes
  "Takes a sequence of ByteBuffers and returns a single contiguous byte-array."
  [buf-seq] `(.array (contiguous ~buf-seq)))
