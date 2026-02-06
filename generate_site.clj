#!/usr/bin/env bb
;;; Site generator for 560005.town - pulls data from Datasette and creates Zola pages
;;; Usage: bb generate_site.clj [datasette-url]

(require '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

;; Configuration
(def ^:dynamic *datasette-url* "https://edit.560005.town")
(def content-dir "content")

(defn execute-sql
  "Execute SQL query against Datasette"
  [sql & [params]]
  (let [url (str *datasette-url* "/data.json")
        query-params (merge {"sql" sql} params)]
    (try
      (-> (http/get url {:query-params query-params :accept :json})
          :body
          (json/parse-string true))
      (catch Exception e
        (println "Error executing SQL:" sql)
        (println "Error:" (.getMessage e))
        nil))))

(defn fetch-facets
  "Fetch facet data for a column using Datasette's faceting"
  [table column]
  (let [url (str *datasette-url* "/data/" table ".json")]
    (try
      (-> (http/get url {:query-params {"_facet" column} :accept :json})
          :body
          (json/parse-string true))
      (catch Exception e
        (println "Error fetching facets for" table column ":" (.getMessage e))
        nil))))

(defn slug
  "Convert string to URL-friendly slug"
  [s]
  (-> s
      str/lower-case
      (str/replace #"[^a-z0-9\s-]" "")
      (str/replace #"\s+" "-")
      str/trim))

(defn create-frontmatter
  "Create YAML frontmatter for Zola"
  [data]
  (let [format-value (fn [v]
                       (cond
                         (string? v) (str "\"" (str/replace v #"\"" "\\\"") "\"")
                         (boolean? v) (str/lower-case (str v))
                         (number? v) (str v)
                         (vector? v) (json/generate-string v)
                         :else (str "\"" v "\"")))]
    (str "+++\n"
         (->> data
              (map (fn [[k v]] (str (name k) " = " (format-value v))))
              (str/join "\n"))
         "\n+++")))

(defn ensure-dirs
  "Ensure content directories exist"
  []
  (doseq [dir [content-dir
               (str content-dir "/c")
               (str content-dir "/t")]]
    (fs/create-dirs dir)))

(defn fetch-categories
  "Fetch all categories using SQL"
  []
  (execute-sql "SELECT id, slug, name FROM categories ORDER BY name"))

(defn fetch-listings-for-category
  "Fetch listings for a specific category using SQL"
  [category-slug & [limit]]
  (let [limit-clause (if limit (str " LIMIT " limit) "")
        sql (str "SELECT l.*, c.slug as category_slug, c.name as category_name
                  FROM listings l
                  JOIN categories c ON l.category_id = c.id
                  WHERE c.slug = :category_slug
                  ORDER BY l.name" limit-clause)]
    (execute-sql sql {"_shape" "array" "category_slug" category-slug})))

(defn fetch-all-listings
  "Fetch all listings using SQL"
  [& [limit]]
  (let [limit-clause (if limit (str " LIMIT " limit) "")
        sql (str "SELECT l.*, c.slug as category_slug, c.name as category_name
                  FROM listings l
                  LEFT JOIN categories c ON l.category_id = c.id
                  ORDER BY l.created_at DESC" limit-clause)]
    (execute-sql sql {"_shape" "array"})))

(defn fetch-listing-by-id
  "Fetch a specific listing by ID"
  [id]
  (let [sql "SELECT l.*, c.slug as category_slug, c.name as category_name
             FROM listings l
             LEFT JOIN categories c ON l.category_id = c.id
             WHERE l.id = :id"]
    (execute-sql sql {"_shape" "array" "id" (str id)})))

(defn fetch-categories-with-counts
  "Fetch categories with listing counts using SQL"
  []
  (execute-sql "SELECT c.id, c.slug, c.name, COUNT(l.id) as listing_count
                FROM categories c
                LEFT JOIN listings l ON c.id = l.category_id
                GROUP BY c.id, c.slug, c.name
                ORDER BY c.name" {"_shape" "array"}))

(defn extract-all-tags
  "Extract all unique tags using SQL and JSON functions"
  []
  (let [sql "SELECT DISTINCT json_each.value as tag
             FROM listings, json_each(listings.tags)
             WHERE listings.tags IS NOT NULL
             ORDER BY json_each.value"]
    (execute-sql sql {"_shape" "array"})))

(defn fetch-listings-by-tag
  "Fetch listings that have a specific tag using SQL"
  [tag]
  (let [sql "SELECT l.*, c.slug as category_slug, c.name as category_name
             FROM listings l
             LEFT JOIN categories c ON l.category_id = c.id
             WHERE l.tags IS NOT NULL
             AND EXISTS (
               SELECT 1 FROM json_each(l.tags)
               WHERE json_each.value = :tag
             )
             ORDER BY l.name"]
    (execute-sql sql {"_shape" "array" "tag" tag})))

(defn fetch-sample-listings-per-category
  "Fetch sample listings for each category using SQL"
  [limit-per-category]
  (let [sql "SELECT l.*, c.slug as category_slug, c.name as category_name,
                    ROW_NUMBER() OVER (PARTITION BY c.id ORDER BY l.created_at DESC) as rn
             FROM listings l
             JOIN categories c ON l.category_id = c.id"]
    (->> (execute-sql sql {"_shape" "array"})
         :rows
         (filter #(<= (get % "rn") limit-per-category))
         (group-by #(get % "category_slug")))))

;; Page generation functions
(defn generate-listing-pages "Generate individual listing pages"
  [category-slug listings]
  (when (and listings (seq (:rows listings)))
    (let [cat-dir (str content-dir "/c/" category-slug)]
      (doseq [listing (:rows listings)]
        (let [listing-slug (slug (get listing "name"))
              listing-dir (str cat-dir "/" listing-slug "/" (get listing "id"))]

          (fs/create-dirs listing-dir)

          ;; Parse tags
          (let [tags (when (get listing "tags")
                       (try
                         (let [parsed (json/parse-string (get listing "tags"))]
                           (if (vector? parsed) parsed []))
                         (catch Exception _ [])))

                frontmatter (str "+++\n"
                             "title = \"" (get listing "name") " - 560005.town\"\n"
                             "description = \"" (subs (or (get listing "description") "") 0 (min 160 (count (or (get listing "description") "")))) "\"\n"
                             "template = \"listing.html\"\n\n"
                             "[extra]\n"
                             "listing_name = \"" (get listing "name") "\"\n"
                             "category_slug = \"" category-slug "\"\n"
                             "phone = \"" (or (get listing "phone") "") "\"\n"
                             "address = \"" (or (get listing "address") "") "\"\n"
                             "tags = " (json/generate-string tags) "\n"
                             "verified = " (str/lower-case (str (boolean (get listing "verified")))) "\n"
                             "created_at = \"" (or (get listing "created_at") "") "\"\n"
                             (when (get listing "latitude") (str "latitude = " (get listing "latitude") "\n"))
                             (when (get listing "longitude") (str "longitude = " (get listing "longitude") "\n"))
                             "+++")

                content (str frontmatter "\n\n"
                             "# " (get listing "name") "\n\n"
                             (when (get listing "description")
                               (str (get listing "description") "\n\n"))

                             ;; Contact info
                             (when (or (get listing "phone") (get listing "address"))
                               (str "## Contact\n\n"
                                    (when (get listing "phone")
                                      (str "**Phone:** " (get listing "phone") "\n\n"))
                                    (when (get listing "address")
                                      (str "**Address:** " (get listing "address") "\n\n"))))

                             ;; Tags
                             (when (seq tags)
                               (str "## Tags\n\n"
                                    (->> tags
                                         (map #(str "[" % "](/t/" (slug %) "/)"))
                                         (str/join " "))
                                    "\n\n"))

                             ;; Verification status
                             (when (get listing "verified")
                               "*✓ Verified listing*\n\n")

                             ;; Back link
                             "[← Back to all " category-slug "](/c/" category-slug "/)\n")]

            (spit (str listing-dir "/index.md") content)))))))

(defn generate-index-page
  "Generate the main index page"
  []
  (println "Generating index page...")
  (let [categories-with-counts (fetch-categories-with-counts)
        sample-listings (fetch-sample-listings-per-category 3)
        all-tags (extract-all-tags)]

    (when categories-with-counts
      (let [frontmatter (create-frontmatter
                         {:title "560005.town - East Bangalore Directory"
                          :description "Community-maintained listings of local services, people, and places"
                          :template "index.html"})

            content (str frontmatter "\n\n"
                         "# East Bangalore Directory\n\n"
                         "Welcome to the community directory for East Bangalore. Find local services, people, and places.\n\n"
                         "## Categories\n\n"

                         ;; Categories section
                         (->> (:rows categories-with-counts)
                              (map (fn [cat]
                                     (let [cat-slug (or (get cat "slug") (get cat :slug))
                                           cat-name (or (get cat "name") (get cat :name))
                                           listing-count (or (get cat "listing_count") (get cat :listing_count))
                                           sample-for-cat (get sample-listings cat-slug [])]
                                       (str "### [" cat-name "](/c/" cat-slug "/)\n"
                                            "*" listing-count " listings*\n\n"
                                            (when (seq sample-for-cat)
                                              (str (->> sample-for-cat
                                                        (take 3)
                                                        (map (fn [listing]
                                                               (str "- [" (get listing "name") "](/c/" cat-slug "/"
                                                                    (slug (get listing "name")) "/" (get listing "id") "/)")))
                                                        (str/join "\n"))
                                                   (when (> listing-count 3)
                                                     (str "\n- [View all " cat-name " listings →](/c/" cat-slug "/)"))
                                                   "\n\n"))))))
                              (str/join ""))

                         ;; Tags section
                         "## Popular Tags\n\n"
                         (->> (:rows all-tags)
                              (take 20)
                              (map #(str "[" (get % "tag") "](/t/" (slug (get % "tag")) "/)"))
                              (str/join " "))
                         "\n")]

        (spit (str content-dir "/_index.md") content)
        (println "✓ Index page generated")))))

(defn generate-category-pages
  "Generate category listing pages"
  []
  (println "Generating category pages...")
  (let [categories (fetch-categories)]
    (when categories
      (doseq [cat (:rows categories)]
        (let [cat-slug (or (get cat "slug") (get cat :slug))
              cat-name (or (get cat "name") (get cat :name))]
          (println (str "  Generating category: " cat-name))

          ;; Create category directory
          (let [cat-dir (str content-dir "/c/" cat-slug)]
            (fs/create-dirs cat-dir)

            ;; Get all listings for this category
            (let [listings (fetch-listings-for-category cat-slug)]
              (when listings
                (let [frontmatter (str "+++\n"
                                       "title = \"" cat-name " - 560005.town\"\n"
                                       "description = \"All " (str/lower-case cat-name) " listings in East Bangalore\"\n"
                                       "template = \"category.html\"\n\n"
                                       "[extra]\n"
                                       "category_name = \"" cat-name "\"\n"
                                       "category_slug = \"" cat-slug "\"\n"
                                       "+++")

                      content (str frontmatter "\n\n"
                                   "# " cat-name "\n\n"
                                   (if (seq (:rows listings))
                                     (str "*" (count (:rows listings)) " listings found*\n\n"
                                          (->> (:rows listings)
                                               (map (fn [listing]
                                                      (let [listing-slug (slug (get listing "name"))]
                                                        (str "## [" (get listing "name") "](" listing-slug "/" (get listing "id") "/)\n"
                                                             (when (get listing "description")
                                                               (let [desc (get listing "description")
                                                                     truncated (if (> (count desc) 200)
                                                                                 (str (subs desc 0 200) "...")
                                                                                 desc)]
                                                                 (str truncated "\n")))
                                                             (when (get listing "phone")
                                                               (str "📞 " (get listing "phone") "  "))
                                                             (when (get listing "address")
                                                               (str "📍 " (get listing "address")))
                                                             "\n\n"))))
                                               (str/join "")))
                                     "*No listings found in this category.*\n"))]

                  (spit (str cat-dir "/_index.md") content)

                  ;; Generate individual listing pages
                  (generate-listing-pages cat-slug listings))))))))))

(defn generate-tag-pages
  "Generate tag pages"
  []
  (println "Generating tag pages...")
  (let [all-tags (extract-all-tags)]

    (when all-tags
      (let [tag-dir (str content-dir "/t")]
        (fs/create-dirs tag-dir)

        (doseq [tag-row (:rows all-tags)]
          (let [tag (get tag-row "tag")
                tag-slug (slug tag)]
            (println (str "  Generating tag: " tag))

            ;; Create tag directory
            (let [tag-page-dir (str tag-dir "/" tag-slug)]
              (fs/create-dirs tag-page-dir)

              ;; Get listings with this tag using SQL
              (let [tag-listings (fetch-listings-by-tag tag)

                    frontmatter (str "+++\n"
                                 "title = \"#" tag " - 560005.town\"\n"
                                 "description = \"All listings tagged with " tag "\"\n"
                                 "template = \"tag.html\"\n\n"
                                 "[extra]\n"
                                 "tag_name = \"" tag "\"\n"
                                 "tag_slug = \"" tag-slug "\"\n"
                                 "+++")

                    content (str frontmatter "\n\n"
                                 "# #" tag "\n\n"
                                 (if (and tag-listings (seq (:rows tag-listings)))
                                   (str "*" (count (:rows tag-listings)) " listings found*\n\n"
                                        (->> (:rows tag-listings)
                                             (map (fn [listing]
                                                    (let [cat-slug (get listing "category_slug")
                                                          listing-slug (slug (get listing "name"))]
                                                      (str "## [" (get listing "name") "](/c/" cat-slug "/" listing-slug "/" (get listing "id") "/)\n"
                                                           (when (get listing "description")
                                                             (let [desc (get listing "description")
                                                                   truncated (if (> (count desc) 200)
                                                                               (str (subs desc 0 200) "...")
                                                                               desc)]
                                                               (str truncated "\n")))
                                                           "*Category: [" (get listing "category_name") "](/c/" cat-slug "/)*\n\n"))))
                                             (str/join "")))
                                   "*No listings found with this tag.*\n"))]

                (spit (str tag-page-dir "/index.md") content)))))))))

(defn clean-content-dir []
  (when (fs/exists? content-dir)
    (fs/delete-tree content-dir))
  (ensure-dirs))

(defn generate-all []
  (println "🚀 Starting site generation...")
  (println "Datasette URL:" *datasette-url*)

  (clean-content-dir)
  (generate-index-page)
  (generate-category-pages)
  (generate-tag-pages)

  (println "✅ Site generation complete!")
  (println "\nNext steps:")
  (println "1. Review the generated content in the 'content' directory")
  (println "2. Run 'zola build' to build the static site")
  (println "3. Run 'zola serve' to preview locally"))

;; Main execution
(defn -main [& args]
  (when (first args)
    (alter-var-root #'*datasette-url* (constantly (first args))))

  (binding [*datasette-url* *datasette-url*]
    (generate-all)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
