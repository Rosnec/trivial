(ns trivial.util)

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
  ([n callback thunk]
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
  [n callback & body]
  `(try-times* ~n ~callback (fn [] ~@body)))
