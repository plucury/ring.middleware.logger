;;
;; ring.middleware.logger by Paul Legato.
;; Copyright (C) 2012 Spring Semantics, Inc.
;;
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php).
;; By using this software in any fashion, you are agreeing to be bound
;; by the terms of this license. You must not remove this notice, or
;; any other, from this software.
;;
;; TODO: Configurable logging granularity; e.g. log request start/stop, log parameters, etc.
;;
(ns ring.middleware.logger
  (require
    [onelog.core :as log]
    [clansi.core :as ansi]
    ))

;; TODO: Facilities for easily modifying the default log file name and log level
(def ^:dynamic *ring-log-file* "logs/ring.log")
(def ^:dynamic *ring-log-level* :info )

;; The generation of the calling class, line numbers, etc. is
;; extremely slow, and should be used only in development mode or for
;; debugging. Production code should not log that information if
;; performance is an issue. See
;; http://logging.apache.org/log4j/companions/extras/apidocs/org/apache/log4j/EnhancedPatternLayout.html
;; for information on which fields are slow.
;;
;; TODO: Make these functions, somehow, so that it can alter the
;; spacing dynamically; e.g. if an %x (execution context message) is
;; present, log it, otherwise ignore it. Putting a %x in prints "null"
;; to the log if none is set, which we don't want, which is why it's
;; not here now.
;;
(def debugging-log-prefix-format "%d %l [%p] : %throwable%m%n")
(def production-log-prefix-format "%d [%p] : %throwable%m%n")




;; TODO: Alter this subsystem to contain a predefined map of all
;; acceptable fg/bg combinations, since some (e.g. white on yellow)
;; are practically illegible.
(def id-colorizations
  "Foreground / background color codes allowable for random ID colorization."
  {:white :bg-white :black :bg-black :red :bg-red :green :bg-green :blue :bg-blue :yellow :bg-yellow :magenta :bg-magenta :cyan :bg-cyan})

(def id-foreground-colors (keys id-colorizations))
(def id-colorization-count (count id-foreground-colors))

(defn- get-colorization
  [id]
  "Returns a consistent colorization for the given id; that is, the
  same ID produces the same color pattern. The colorization will have
  distinct foreground and background colors."
  (let [foreground (nth id-foreground-colors (mod id id-colorization-count))
        background-possibilities (vals (dissoc id-colorizations foreground))
        background (nth background-possibilities (mod id (- id-colorization-count 1)))]
    [foreground background]))

(defn- format-id [id]
  "Returns a standard colorized, printable representation of a request id."
  (apply ansi/style (format "%04x" id) [:bright ] (get-colorization id)))

(defn- log4j-pre-logger
  [id
   {:keys [request-method uri remote-addr query-string params headers] :as req}]
  (log/info (str "[" (format-id id) "] Starting " request-method " " uri (if query-string (str "?" query-string)) " for " remote-addr " \nHeaders " headers))
  (if params
    (log/info (str "[" (format-id id) "]  \\ - - - -  Params: " params))))

(defn- log4j-colorless-pre-logger
  [id request]
  "Like log4j-pre-logger, but doesn't log any ANSI color codes."
  (ansi/without-ansi (log4j-pre-logger id request)))

(defn- log4j-post-logger
  [id
   {:keys [request-method uri remote-addr query-string] :as req}
   {:keys [status] :as resp}
   totaltime]
  "Log4J logformatter; logs data about a finished request.

Log messages will be colorized with ANSI escape codes. Use
log4j-colorless-logger if you want plaintext.

The id is randomly colorized according to its value, to make it easier
to visually correlate request/response/exception log sets.

Sends all log messages at \"info\" level to the Log4J logging
  infrastructure, unless status is >= 500, in which case they are sent
  as errors."
  (let [colorid (format-id id)
        colortime (try (apply ansi/style
                         (str totaltime)
                         (cond
                           (>= totaltime 1500) [:bright :red ]
                           (>= totaltime 1000) [:red ]
                           (>= totaltime 500) [:yellow ]
                           :else :default ))
      (catch Exception e (or totaltime "??")))

        colorstatus (try (apply ansi/style
                           (str status)
                           (cond
                             (< status 300) [:default ]
                             (>= status 500) [:bright :red ]
                             (>= status 400) [:red ]
                             :else [:yellow ]))
      (catch Exception e (or status "???")))
        log-message (str
      "[" colorid "] "
      "Finished " request-method " " uri (if query-string (str "?" query-string)) " for " remote-addr " in (" colortime " ms)"
      " Status: " colorstatus
      )]

    (if (and (number? status) (>= status 500))
      (log/error log-message)
      (log/info log-message))))

(defn- log4j-colorless-post-logger
  [id request response totaltime]
  "Like log4j-post-logger, but doesn't log any ANSI color codes."
  (ansi/without-ansi (log4j-post-logger id request response totaltime)))

(defn- log4j-exception-logger
  [id
   {:keys [request-method uri remote-addr] :as request}
   throwable
   totaltime]
  (let [formatted-id (format-id id)]
    (log/error (str "[" formatted-id "] " (ansi/style "Exception!" :bright :red ) " for " remote-addr " in (" totaltime " ms)"))
    (log/error throwable (str "- End stacktrace for " formatted-id "-")))) ;; must include a second string argument to make c.t.logging recognize first arg as an exception)

(defn- log4j-colorless-exception-logger
  [id request throwable totaltime]
  "Like log4j-pre-logger, but doesn't log any ANSI color codes."
  (ansi/without-ansi (log4j-exception-logger id request throwable totaltime)))


(defn- make-logger-middleware
  [handler pre-logger post-logger exception-logger]
  "Adds logging for requests using the given logger functions.

The convenience function (wrap-with-logger) calls this function with
default loggers. Use (make-logger) directly if you want to supply your own
logger functions.

Each request is assigned a random hex id, to allow the 3 possible
logging events to be correlated.

The pre-logger function is called before the handler is invoked, and
receives the id and the request as an argument.

The post-logger function is called after the response is generated,
and receives four arguments: the id, the request, the response, and the
total time taken by the handler.

The exception-logger function is called in a (catch) clause, if an
exception is thrown during the handler's run. It receives four
arguments: the id, the request, the Throwable that was thrown, and the
total time taken to that point. It re-throws the exception after
logging it, so that other middleware has a chance to do something with
it.
"
  ;; originally based on
  ;; https://gist.github.com/kognate/noir.incubator/blob/master/src/noir.incubator/middleware.clj
  (fn [request]
    (let [id (rand-int 0xffff)
          start (System/currentTimeMillis)]
      (try
        (pre-logger id request)
        (let [response (handler request)
              finish (System/currentTimeMillis)
              total (- finish start)]
          (post-logger id request response total)
          response)
        (catch Throwable t
          (let [finish (System/currentTimeMillis)
                total (- finish start)]
            (exception-logger id request t total))
          (throw t))))))


(defn wrap-with-logger
  "Returns a Ring middleware handler which uses the prepackaged color loggers."
  ([handler logfile]
    (log/start! logfile *ring-log-level*)
    (make-logger-middleware handler log4j-pre-logger log4j-post-logger log4j-exception-logger))
  ([handler] (wrap-with-logger handler *ring-log-file*)))

(defn wrap-with-plaintext-logger
  "Returns a Ring middleware handler which uses the ANSI-colorless prepackaged loggers."
  ([handler logfile]
    (log/start! logfile *ring-log-level*)
    (make-logger-middleware handler log4j-colorless-pre-logger log4j-colorless-post-logger log4j-colorless-exception-logger))
  ([handler] (wrap-with-plaintext-logger handler *ring-log-file*)))


