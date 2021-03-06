(ns memory-hole.db.core
  (:require
   [cheshire.core :refer [generate-string parse-string]]
   [clojure.java.jdbc :as jdbc]
   [clojure.set :refer [difference]]
   [clojure.walk :refer [postwalk]]
   [conman.core :as conman]
   [cuerdas.core :as string]
   [mount.core :refer [defstate]]
   [memory-hole.config :refer [env]]
   [buddy.hashers :as hashers])
  (:import org.postgresql.util.PGobject
           java.sql.Array
           clojure.lang.IPersistentMap
           clojure.lang.IPersistentVector
           [java.sql
            Date
            Timestamp
            PreparedStatement]))

(defstate ^:dynamic *db*
  :start (conman/connect! {:jdbc-url (env :database-url)})
  :stop (conman/disconnect! *db*))

(conman/bind-connection *db*
                        "sql/issues.sql"
                        "sql/tags.sql"
                        "sql/users.sql"
                        "sql/groups.sql"
                        "sql/attachments.sql")

(defn ->kebab-case-keyword* [k]
  (-> (reduce
        (fn [s c]
          (if (and
                (not-empty s)
                (Character/isLowerCase (last s))
                (Character/isUpperCase c))
            (str s "-" c)
            (str s c)))
        "" (name k))
      (string/replace #"[\s]+" "-")
      (.replaceAll "_" "-")
      (.toLowerCase)
      (keyword)))

(def ->kebab-case-keyword (memoize ->kebab-case-keyword*))

(defn transform-keys [t coll]
  "Recursively transforms all map keys in coll with t."
  (letfn [(transform [[k v]] [(t k) v])]
    (postwalk (fn [x] (if (map? x) (into {} (map transform x)) x)) coll)))

(defn result-one-snake->kebab
  [this result options]
  (->> (hugsql.adapter/result-one this result options)
       (transform-keys ->kebab-case-keyword)))

(defn result-many-snake->kebab
  [this result options]
  (->> (hugsql.adapter/result-many this result options)
       (map #(transform-keys ->kebab-case-keyword %))))

(defmethod hugsql.core/hugsql-result-fn :1 [sym]
  'memory-hole.db.core/result-one-snake->kebab)

(defmethod hugsql.core/hugsql-result-fn :* [sym]
  'memory-hole.db.core/result-many-snake->kebab)

(defn deserialize [pgobj]
  (let [type  (.getType pgobj)
        value (.getValue pgobj)]
    (case type
      "record" (some-> value (subs 1 (dec (count value))) (.split ",") vec)
      "json" (parse-string value true)
      "jsonb" (parse-string value true)
      "citext" (str value)
      value)))

(defn to-date [^java.sql.Date sql-date]
  (-> sql-date (.getTime) (java.util.Date.)))

(extend-protocol jdbc/IResultSetReadColumn
  Date
  (result-set-read-column [v _ _] (to-date v))

  Timestamp
  (result-set-read-column [v _ _] (to-date v))

  Array
  (result-set-read-column [v _ _]
    (->> (.getArray v)
         (map #(if (instance? PGobject %)
                (deserialize %) %))
         (remove nil?)
         (vec)))

  PGobject
  (result-set-read-column [pgobj _metadata _index]
    (deserialize pgobj)))

(extend-type java.util.Date
  jdbc/ISQLParameter
  (set-parameter [v ^PreparedStatement stmt ^long idx]
    (.setTimestamp stmt idx (Timestamp. (.getTime v)))))

(defn to-pg-json [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (generate-string value))))

(extend-type clojure.lang.IPersistentVector
  jdbc/ISQLParameter
  (set-parameter [v ^java.sql.PreparedStatement stmt ^long idx]
    (let [conn      (.getConnection stmt)
          meta      (.getParameterMetaData stmt)
          type-name (.getParameterTypeName meta idx)]
      (if-let [elem-type (when (= (first type-name) \_) (apply str (rest type-name)))]
        (.setObject stmt idx (.createArrayOf conn elem-type (to-array v)))
        (.setObject stmt idx (to-pg-json v))))))

(extend-protocol jdbc/ISQLValue
  IPersistentMap
  (sql-value [value] (to-pg-json value))
  IPersistentVector
  (sql-value [value] (to-pg-json value)))

(defn support-issue [m]
  (conman/with-transaction [*db*]
    (when-let [issue (not-empty (support-issue* m))]
      (-> issue
          (update :tags distinct)
          (update :files distinct)
          (merge (inc-issue-views<! m))))))

(defn create-missing-tags [issue-tags]
  (let [current-tags (map :tag (tags))]
    (doseq [tag (difference (set issue-tags)
                            (set current-tags))]
      (create-tag<! {:tag tag}))))

(defn reset-issue-tags! [user-id support-issue-id tags]
  (create-missing-tags tags)
  (dissoc-tags-from-issue!
   {:support-issue-id support-issue-id
    :user-id user-id})
  (assoc-tags-with-issue!
   {:support-issue-id support-issue-id
    :tags             tags
    :user-id user-id}))

(defn user-can-access-group [{:keys [user-id group-id]}]
  (some
   (comp (partial = group-id) :group-id)
   (groups-for-user {:user-id user-id})))

(defn create-issue-with-tags! [{:keys [tags user-id] :as issue}]
  (conman/with-transaction [*db*]
    (when (user-can-access-group (select-keys issue [:user-id :group-id]))
      (let [support-issue-id (:support-issue-id
                              (add-issue<! (dissoc issue :tags)))]
        (reset-issue-tags! user-id support-issue-id tags)
        support-issue-id))))

(defn update-issue-with-tags! [{:keys [user-id support-issue-id tags] :as issue}]
  (conman/with-transaction [*db*]
    (when (user-can-access-group (select-keys issue [:user-id :group-id]))
      (reset-issue-tags! user-id support-issue-id tags)
      (update-issue! (dissoc issue :tags)))))

(defn dissoc-from-tags-and-delete-issue-and-files! [m]
  (conman/with-transaction [*db*]
    (delete-issue-files! m)
    (dissoc-tags-from-issue! m)
    (delete-issue! m)))

(defn update-user-info! [{:keys [screenname pass admin is-active] :as user}]
  (conman/with-transaction [*db*]
    (merge
      user
      (if-let [{:keys [user-id]} (user-by-screenname {:screenname screenname})]
        (update-user<! {:user-id    user-id
                        :admin      admin
                        :is-active  is-active
                        :screenname screenname
                        :pass       pass})
        (insert-user<! {:screenname screenname
                        :admin      admin
                        :is-active  is-active
                        :pass       pass})))))

(defn insert-user-with-belongs-to!
  "inserts a user and adds them to specified groups in a transaction."
  [{:keys [screenname pass admin is-active belongs-to] :as user}]
  (conman/with-transaction [*db*]
    (let [{:keys [user-id]}
          (insert-user<! {:screenname screenname
                          :admin admin
                          :is-active is-active
                          :pass pass})]
      (add-user-to-groups! {:user-id user-id
                            :groups belongs-to})
      (user-by-screenname {:screenname screenname}))))

(defn update-or-insert-user-with-belongs-to!
  "updates a user and modifies their group membership."
  [{:keys [screenname pass admin is-active belongs-to member-of update-password?] :as user}]
  (conman/with-transaction [*db*]
    (let [belongs-to (into [] (distinct (concat belongs-to member-of)))
          existing-user (user-by-screenname {:screenname screenname})
          user-exists? (not (empty? existing-user))
          {:keys [user-id]} (if user-exists?
                              existing-user
                              (insert-user<! {:screenname screenname
                                              :admin admin
                                              :is-active is-active
                                              :pass pass}))
          old-groups (:belongs-to existing-user [])
          del-groups (remove (set belongs-to) old-groups)
          add-groups (remove (set old-groups) belongs-to)]
      (when user-exists?
        (if update-password?
          (update-user-with-pass<! (-> user
                                       (select-keys [:screenname
                                                     :pass
                                                     :admin
                                                     :is-active
                                                     :user-id])))
          (update-user<! {:user-id    user-id
                          :admin      admin
                          :is-active  is-active
                          :screenname screenname})))
      (when-not (empty? del-groups)
        (remove-user-from-groups! {:user-id user-id
                                   :groups del-groups}))
      (when-not (empty? add-groups)
        (add-user-to-groups! {:user-id user-id
                              :groups add-groups}))
      (select-keys
       (user-by-screenname {:screenname screenname})
       [:user-id
        :screenname
        :admin
        :is-active
        :last-login
        :belongs-to]))))

(defn run-query-if-user-can-access-issue
  "runs query-fn with no args if user can access, otherwise returns nil"
  [{:keys [user-id support-issue-id] :as m} query-fn]
  (conman/with-transaction [*db*]
    (when (user-can-access-issue? m)
      (query-fn))))
