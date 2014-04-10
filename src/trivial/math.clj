(ns trivial.math
  "Mathematical functions.")

(defn prob
  "Returns x with probability p, else returns y (or nil if not given)."
  ([p] (prob p p nil))
  ([p x] (prob p x nil))
  ([p x y] (if (< (rand) p) x y)))

(defn elapsed-time
  "Returns the time in nanoseconds `time` nanoseconds from now.
  Optionally takes a scale factor to multiply `time` by."
  ([time] (+ (System/nanoTime) time))
  ([time scale] (elapsed-time (* time scale))))

(defn highest-sequential
  "Steps through the collection, and returns the first number for which
  the next number isn't its increment. Optionally takes a starting value,
  and a step."
  ([coll]
     (highest-sequential coll 1))
  ([coll step]
     (if (empty? coll)
       nil
       (let [start (first coll)]
         (highest-sequential (rest coll) start step))))
  ([coll start step]
     (if (empty? coll)
       start
       (let [nxt (first coll)]
         (if (== (+ start step) nxt)
           (recur (rest coll) nxt step)
           start)))))
