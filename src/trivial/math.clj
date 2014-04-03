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