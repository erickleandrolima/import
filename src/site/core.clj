(ns site.core
  (:require [clojure.java.jdbc :as j]
            [honeysql.core :as sql]
            [honeysql.helpers :refer [select join from where limit]]
            [clojure.java.io :as io]
            [environ.core :refer [env]]))

;; =============================================================================
;; Config vars
;; =============================================================================
(def news-path (env :news-path))
(def news-id (env :news-id))
(def storage-path (env :storage-path))

;; =============================================================================
;; DB Setup
;; =============================================================================
(def db-source (read-string (env :source-db)))

(def db-target (read-string (env :target-db)))

;; =============================================================================
;; File functions
;; =============================================================================
(defn- valid-files [old-news]
  (let [keywords (map #(keyword (str "foto" %)) (range 1 7))]
    (filter #(not (empty? (get old-news %)))
            keywords)))

(defn- file-id [kw]
  (->> kw
       (str)
       (re-find #"\d")))

(defn- find-in-fs [old-news-entry]
  (let [id              (:old-id old-news-entry)
        kws             (:file-kws old-news-entry)
        possible-fnames (map (fn [kw]
                               (str news-path "/" (file-id kw) "/" id ".jpg"))
                             kws)
        existing-files  (filter #(.exists %) (map io/file possible-fnames))]
    existing-files))

(defn with-files [entries]
  (map
   (fn [entry]
     (let [valid-kws (valid-files entry)
           files (-> (assoc entry :file-kws valid-kws)
                     (find-in-fs))]
       (-> entry
           (dissoc :old-id
                   :foto1
                   :foto2
                   :foto3
                   :foto4
                   :foto5
                   :foto6
                   :file-kws)
           (assoc :files files))))
   entries))

;; =============================================================================
;; News functions
;; =============================================================================
(defn new-entry [{:keys [id titulo texto data foto1 foto2 foto3 foto4 foto5 foto6]}]
  {:noticia_titulo   titulo
   :organogramaCod 5
   :noticia_tipo "P"
   :noticia_conteudo texto
   :noticia_data     data
   :noticia_privacidade "P"
   :noticia_comentario "N"
   :old-id id
   :foto1 foto1
   :foto2 foto2
   :foto3 foto3
   :foto4 foto4
   :foto5 foto5
   :foto6 foto6})

(defn get-entries []
  (->> (j/query
        db-source
        (-> (select :*)
            (from :mac_noticias)
            (sql/format)))
       (map new-entry)
       (with-files)))

(defn persist-entry! [db entry]
  ((comp :generated_key first)
   (j/insert! db :noticia (dissoc entry :files))))

;; =============================================================================
;; Upload functions
;; =============================================================================
(defn make-upload [^java.io.File file entry-id date]
  {:organogramaCod 5
   :moduloCod news-id
   :uploadCodReferencia entry-id
   :uploadNomeCampo "noticia_upload"
   :uploadNomeFisico (str (java.util.UUID/randomUUID) ".jpg")
   :uploadNomeOriginal (.getName file)
   :uploadDataCadastro date
   :uploadMime "image/jpeg"
   :uploadDownloads 0
   :file file})

(defn persist-upload! [conn upload-entry]
  (j/insert! conn :_upload (dissoc upload-entry :file)))

(defn build-path [date]
  (str storage-path "/" (.format (java.text.SimpleDateFormat. "Y/MM/dd") date)))

(defn copy-files! [uploads path]
  (map (fn [upload]
         (let [file (:file upload)
               dest (io/file (str (.getPath path) "/" (:uploadNomeFisico upload)))]
           (io/copy file dest)))
       uploads))

(defn import-entry! [entry]
  (j/with-db-transaction [conn db-target]
    (let [entry-id (persist-entry! conn entry)
          date     (java.util.Date.)
          uploads  (map #(make-upload % entry-id date)
                        (:files entry))
          path     (io/file (build-path date))
          realpath (str (.getPath path) "/qualquercoisa.txt")
          _        (doall (map (partial persist-upload! conn) uploads))]
      (if (.exists path)
        (copy-files! uploads path)
        (do
          (io/make-parents realpath)
          (copy-files! uploads path))))))

(defn import! []
  (let [entries       (get-entries)
        entries-count (count entries)]
    (println "Starting import...")
    (doall
     (map (fn [entry n]
            (import-entry! entry)
            (println "Imported entry " n " of " entries-count))
          entries
          (range 1 (inc entries-count))))
    (println "Done!")))

(defn -main [& args]
  (import!))
