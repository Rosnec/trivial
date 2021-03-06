(ns trivial.core
  (:require [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [trivial.client :as client]
            [trivial.server :as server]
            [trivial.tftp :refer [*drop*]]
            [trivial.util :as util]
            [trivial.util :refer [dbg verbose *verbose*]])
  (:gen-class))

(def cli-options
  [["-p" "--port PORT" "Port number"
    :id :port
    :default 8888
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]],
   ["-d" "--drop" "Packet drop mode"
    :id :drop?
    :default false],
   ["-t" "--timeout TIME" "Time to drop connection (in seconds)"
    :id :timeout
    :default 30
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be a positive integer"]]
   ["-o" "--output FILE" "Output filename"
    :id :output]
   ["-v" "--verbose" "Verbose"
    :id :verbose?
    :default false],
   ["-h" "--help"]])

(def client-options
  [["-H" "--hostname HOST" "Remote host"
    :id :hostname
    :default "localhost"],
   [nil "--IPv6" "IPv6 mode"
    :id :IPv6?
    :default false],
   [nil "--window-size SIZE" "Size of window for sliding-window mode."
    :id :window-size
    :default 0
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 0 % 0xffff) "Must be a number between 0 and 65536"]]])

(def server-options
  [])

(defn usage [options-summary]
  (->> ["A proxy server/client program using a modified TFTP."
        ""
        "Usage: trivial [options] "
        "               [server [server-options]|client url [client-options]]"
        ""
        "Options:"
        options-summary
        ""
        "Modes:"
        "  server   Run in server mode"
        "  client   Run in client mode. Must have a url provided."]
       (string/join \newline)))

(defn usage-client [options-summary]
  (->> ["A client program to a proxy server using a modified TFTP."
        ""
        "Usage: trivial [options] client <url> [client-options]"
        ""
        "Options:"
        options-summary
        ""
        "Argument:"
        "  url   The URL to request from the server."]
       (string/join \newline)))

(defn usage-server [options-summary]
  (->> ["A proxy server program using a modified TFTP."
        ""
        "Usage: trivial [options] server [server-options]"
        ""
        "Options:"
        options-summary]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn -main
  "Runs either the server or client"
  [& args]
  (let [{:keys [options arguments errors summary]}
        (parse-opts args cli-options :in-order true)
        global-options options]
    (cond
     (:help options) (util/exit 0 (usage summary))
     errors (util/exit 1 (error-msg errors)))
    (binding [*verbose* (:verbose? options)
              *drop*    (:drop? options)]
      (case (first arguments)
        "server" (do
                   (verbose "Starting server.")
                   (server/start options))

        "client" (let [{:keys [options arguments errors summary]}
                       (parse-opts (rest arguments) client-options)]
                   (cond
                    (:help options) (util/exit 0 (usage-client summary))
                    (not= (count arguments) 1) (util/exit
                                                1 (usage-client summary))
                    errors (util/exit 1 (error-msg errors)))
                   (let [url (first arguments)]
                     (verbose "Starting client with url:" url)
                     (client/start url (merge global-options options))))

        (util/exit 1 (usage summary))))))
