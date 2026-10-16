#!/usr/bin/env clojure
(ns checker
  (:require [clj-http.client :as http]
            [net.cgrand.enlive-html :as html]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.java.shell :refer [sh]])
  (:import (java.nio.file Files StandardCopyOption))
  (:gen-class))

(defn parse-case [case-id]
  (when-let [[_ number year] (and case-id (re-matches #"(\d+)/(\d{4})" (str/trim case-id)))]
    {:number number :year year}))

(defn build-url [{:keys [number year]}]
  (str "https://srs.justice.bg/bg/12685?from=&to=&actkindcode=&casenumber=" number
       "&caseyear=" year "&casetype="))

;; The case id contains a slash, so the folder uses <number>-<year>.
(defn data-dir []
  (io/file (or (System/getenv "COURT_CHECKER_DIR")
               (str (System/getProperty "user.home") "/.local/share/court-checker"))))

(defn case-dir [{:keys [number year]}]
  (io/file (data-dir) (str number "-" year)))

(defn die [msg]
  (println "❌" msg)
  (shutdown-agents)
  (System/exit 1))

(defn send-notification [summary message urgency]
  (try
    (sh "notify-send" "-u" urgency (str "🏛️ " summary) message)
    (catch Exception e
      (println "⚠️ notify-send failed:" (.getMessage e)))))

(defn fetch-html-nodes [target-url]
  (try
    (let [response (http/get target-url {:connection-timeout 10000 :socket-timeout 20000})]
      (html/html-resource (java.io.StringReader. (:body response))))
    (catch Exception e
      (die (str "HTTP request or parsing failed: " (.getMessage e))))))

;; ---------- parsing ----------

(defn- text [node]
  (str/trim (str/replace (html/text node) #"\s+" " ")))

(defn- link [base td]
  (when-let [href (some-> (html/select td [:a]) first :attrs :href)]
    (str (.resolve (java.net.URI. base) href))))

(defn parse-act [base row]
  ;; columns: №, kind, number/year, plaintiff, defendant, judge, act type, date, file, motives
  (let [[idx _ _ _ _ judge act-type date file motives] (html/select row [:td])]
    {:index       (some-> idx text parse-long)
     :type        (text act-type)
     :date        (text date)
     :judge       (text judge)
     :file-url    (link base file)
     :motives-url (link base motives)}))

(defn parse-court-acts [base nodes]
  (->> (html/select nodes [:table.results-table :tr.table-data])
       (map #(parse-act base %))
       (filter :index)
       (sort-by :index)
       vec))

;; ---------- local state ----------

(defn read-state [file]
  (when (.exists file)
    (edn/read-string (slurp file))))

(defn- move! [from to]
  (Files/move (.toPath from) (.toPath to)
              (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))

(defn write-state! [file state]
  (let [tmp (io/file (str file ".tmp"))]
    (spit tmp (with-out-str (pp/pprint state)))
    (move! tmp file)))

;; ---------- downloads ----------

(defn- iso-date [d]
  (if-let [[_ dd mm yyyy] (re-matches #"(\d{2})\.(\d{2})\.(\d{4})" d)]
    (str yyyy "-" mm "-" dd)
    d))

(defn- safe-name [s]
  (str/replace s #"[^\p{L}\p{N}]+" "_"))

(defn- extension [resp]
  (or (some->> (get-in resp [:headers "Content-Disposition"])
               (re-find #"filename=\"?[^;\"]*\.([A-Za-z0-9]{1,5})\"?\s*(?:;|$)")
               second)
      (when (str/includes? (str (get-in resp [:headers "Content-Type"])) "pdf") "pdf")
      "bin"))

(defn download! [dir base-name url]
  (try
    (let [resp (http/get url {:as :byte-array :connection-timeout 10000 :socket-timeout 60000})
          fname (str base-name "." (extension resp))
          target (io/file dir fname)
          tmp (io/file dir (str fname ".part"))]
      (with-open [o (io/output-stream tmp)]
        (.write o ^bytes (:body resp)))
      (move! tmp target)
      (println "⬇️  saved" fname)
      fname)
    (catch Exception e
      (println "⚠️ download failed:" url (.getMessage e))
      nil)))

(defn- present? [dir fname]
  (and fname (.exists (io/file dir fname))))

(defn sync-files
  "Downloads any act file/motives that is available but not yet stored locally."
  [dir old-acts acts]
  (let [old-by (into {} (map (juxt :index identity)) old-acts)]
    (mapv (fn [{:keys [index type date file-url motives-url] :as act}]
            (let [old (old-by index)
                  base (format "%03d_%s_%s" index (iso-date date) (safe-name type))
                  keep-or-get (fn [old-key url suffix]
                                (let [prev (get old old-key)]
                                  (cond
                                    (nil? url) nil
                                    (present? dir prev) prev
                                    :else (download! dir (str base suffix) url))))]
              (assoc act
                     :file (keep-or-get :file file-url "")
                     :motives-file (keep-or-get :motives-file motives-url "_motives"))))
          acts)))

;; ---------- change detection ----------

(defn diff-acts [old-acts acts]
  (let [old-by (into {} (map (juxt :index identity)) old-acts)
        sig #(select-keys % [:type :date :judge])]
    (for [a acts
          :let [o (old-by (:index a))
                kind (cond
                       (nil? o) :new
                       (and (:file-url a) (not (:file-url o))) :file-added
                       (and (:motives-url a) (not (:motives-url o))) :motives-added
                       (not= (sig a) (sig o)) :changed)]
          :when kind]
      {:kind kind :act a})))

(def kind-label
  {:new "new" :file-added "file now available" :motives-added "motives added" :changed "changed"})

(defn describe-change [{:keys [kind act]}]
  (str "#" (:index act) " " (:type act) " " (:date act) " (" (kind-label kind) ")"))

;; ---------- main ----------

(defn run-check [case-id case-map]
  (let [url (build-url case-map)
        dir (case-dir case-map)
        state-file (io/file dir "acts.edn")]
    (println "==========================================")
    (println (str "🕒 [" (java.time.LocalDateTime/now) "] Checking case " case-id))
    (println "📁 Case folder:" (str dir))
    (.mkdirs dir)
    (let [prev (read-state state-file)
          old-acts (:acts prev)
          acts (parse-court-acts url (fetch-html-nodes url))]
      (when (empty? acts)
        (die "No acts found - page layout may have changed. State left untouched."))
      (println "📊 Acts on page:" (count acts))
      (let [changes (diff-acts old-acts acts)
            acts (sync-files dir old-acts acts)]
        (write-state! state-file {:case case-id
                                  :checked-at (str (java.time.LocalDateTime/now))
                                  :acts acts})
        (cond
          (nil? prev)
          (println "📝 First run: stored baseline of" (count acts) "acts (no notification).")

          (empty? changes)
          (println "😴 No changes.")

          :else
          (let [lines (map describe-change changes)
                decision? (some #(str/includes? (:type (:act %)) "Решение") changes)]
            (println "🔔 Changes detected:")
            (run! #(println "   -" %) lines)
            (send-notification (str "SRS Case " case-id)
                               (str (if decision? "🚨 DECISION: " "🔄 ") (str/join "\n" lines))
                               (if decision? "critical" "normal")))))
      (println "🏁 Done.")
      (println "=========================================="))))

(defn -main [& args]
  (let [case-id (some-> (first args) str/trim)
        case-map (parse-case case-id)]
    (when-not case-map
      (println "Usage: clojure -M:run <number>/<year>   e.g. 62325/2023")
      (shutdown-agents)
      (System/exit 2))
    (run-check case-id case-map)
    (shutdown-agents)))
