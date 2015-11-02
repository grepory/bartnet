(ns bartnet.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [clojure.tools.cli :refer [parse-opts]]
            [opsee.middleware.config :refer [config]]
            [opsee.middleware.migrate :as migrate]
            [bartnet.api :as api]
            [bartnet.upload-cmd :as upload-cmd]
            [bartnet.bus :as bus]
            [bartnet.autobus :as autobus]
            [bartnet.nsq :as nsq]
            [bartnet.results :as results]
            [bartnet.websocket :as websocket]
            [bartnet.sql :as sql]
            [opsee.middleware.core :refer :all]
            [ring.adapter.jetty9 :refer :all]
            [cheshire.core :refer :all]
            [bartnet.instance :as instance]
            [bartnet.launch :as launch])
  (:import [java.util.concurrent ScheduledThreadPoolExecutor]
           [io.aleph.dirigiste Executors]))

(def ^{:private true} ws-server (atom nil))

(defn- start-ws-server [executor scheduler db bus config]
  (if-not @ws-server
    (do
      (reset! instance/store-addr (get-in config [:fieri :addr]))
      (reset! launch/auth-addr (get-in config [:vape :addr]))
      (reset! results/results-addr (get-in config [:beavis :addr]))
      (reset! ws-server
              (run-jetty
               (api/handler executor scheduler bus db config)
               (assoc (:server config)
                      :websockets {"/stream" (websocket/ws-handler scheduler bus)}))))))

(defn stop-server []
  (do
    (if @ws-server (do
                     (.stop @ws-server)
                     (reset! ws-server nil)))))

(defn start-server [args]
  (let [conf (config (last args))
        db (sql/pool (:db-spec conf))
        bus (bus/message-bus (if (:nsq conf)
                               (nsq/message-bus (:nsq conf))
                               (autobus/autobus)))
        executor (Executors/utilizationExecutor (:thread-util conf) (:max-threads conf))
        scheduler (ScheduledThreadPoolExecutor. 10)]
    (start-ws-server executor scheduler db bus conf)))

(.addShutdownHook
 (Runtime/getRuntime)
 (Thread. (fn []
            (println "Shutting down...")
            (stop-server))))

(defn -main [& args]
  (let [cmd (first args)
        subargs (rest args)]
    (case cmd
      "server" (start-server subargs)
      "db" (migrate/db-cmd subargs)
      "upload" (upload-cmd/upload subargs))))

