(defproject trivial "1.0.0"
  :description "A proxy server implemented using a slightly modified version
               of TFTP, implementing sliding window."
  :url "https://github.com/Rosnec/trivial"
  :license {:name "GNU General Public License version 3"
            :url "https://www.gnu.org/licenses/gpl-3.0.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.cli "0.3.1"]
                 [gloss "0.2.2"]]
  :jar-name "trivial.jar"
  :uberjar-name "trivial-standalone.jar"
  :main trivial.core)
