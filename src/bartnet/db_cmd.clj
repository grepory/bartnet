(ns bartnet.db-cmd
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [bartnet.sql :as sql])
  (:import  [liquibase Liquibase]
            [liquibase.resource ClassLoaderResourceAccessor]
            [liquibase.database.jvm JdbcConnection]
            [java.io OutputStreamWriter]
            [java.nio.charset Charset]))

(def migrate-options
  [
   ["-n" "--dry-run" "output the DDL to stdout, don't run it"
    :default false
    :parse-fn #(Boolean/valueOf %)]
   ["-c" "--count" "only apply the next N change sets"
    :parse-fn #(Integer/parseInt %)]
   ["-i" "--include" "include change sets from the given context"]])

(defn usage [options-summary]
  (->> ["This is the db migrate command for bartnet."
        ""
        "usage: bartnet db migrate [options] <config file>"
        ""
        "Options:"
        options-summary]
       (str/join \newline)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn do-migrate [config options]
  (let [pool (sql/pool (:db-spec config))
        liquibase (new Liquibase "migrations.xml" (new ClassLoaderResourceAccessor) (new JdbcConnection (.getConnection (:datasource pool))))]
    (if-let [count (:count options)]
      (if (:dry-run options)
        (.update liquibase count "" (new OutputStreamWriter System/out (Charset/forName "UTF-8")))
        (.update liquibase count ""))
      (if (:dry-run options)
        (.update liquibase "" (new OutputStreamWriter System/out (Charset/forName "UTF-8")))
        (.update liquibase "")))))

(defn migrate-cmd [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args migrate-options)]
    (cond
      (:help options) (exit 0 (usage summary))
      (not= (count arguments) 1) (exit 1 (usage summary))
      errors (exit 1 (error-msg errors)))
    (let [config (parse-string (slurp (first arguments)) true)]
      (log/info config)
      (do-migrate config options))))

(defn db-cmd [args]
  (case (first args)
    "migrate" (migrate-cmd (rest args))))

