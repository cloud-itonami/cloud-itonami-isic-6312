(ns portal.store
  "SSoT for the web-portal actor, behind a `Store` protocol so the backend
  is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic default
                       for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store. Pure `.cljc`, so it runs offline AND can be
                       pointed at a real Datomic Local or a kotoba-server pod
                       by swapping `langchain.db`'s `:db-api`.

  Both implement the same protocol and pass the same contract
  (test/portal/store_contract_test.clj) — the actor, the PortalGovernor and
  the audit ledger never know which SSoT they run on.

  Entity shapes: a third-party `source` (license-class-tagged), an
  aggregated `listing`, a `placement` (a listing occupying a slot,
  possibly sponsored), a `content-license` (provenance for
  `:licensed-syndication` listings), and an advertiser `contract` (tenant ×
  tier, licensed reporting). There is NO field anywhere in this schema for
  order-fulfillment or payment processing — this actor only curates and
  discloses portal content, it never handles money movement.

  The ledger stays append-only on every backend — 'who published/featured/
  removed what, on what license/contract, on what source basis' is always
  a query over an immutable log."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (source [s id])
  (all-sources [s])
  (listing [s id])
  (all-listings [s])
  (placement [s slot-id] "the current listing occupying a placement slot")
  (content-license [s license-id])
  (contract [s tenant])
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-sources [s sources]           "replace/seed sources (map id→source)")
  (with-listings [s listings]         "replace/seed listings (map id→listing)")
  (with-placements [s placements]     "replace/seed placements (map slot-id→placement)")
  (with-content-licenses [s licenses] "replace/seed content licenses (map license-id→license)")
  (with-contracts [s contracts]       "replace/seed advertiser contracts (map tenant→contract)"))

;; ───────────────────────── demo data (fictitious, non-real listings) ─────

(defn demo-data
  "A small, entirely fictitious dataset so the actor + tests run offline
  and no real listing/subject is ever asserted by this repository. `li-200`
  carries a demo `:allegation-subject?` flag purely to exercise the
  sensitive-subject governor gate — it is not a claim about any real
  person or company."
  []
  {:sources
   {"src-gov1"  {:id "src-gov1" :name "デモ連邦機関(米国、デモ)" :license-class :public-domain :trust-tier :high}
    "src-cc1"   {:id "src-cc1" :name "デモCC-BYブログ" :license-class :cc-attribution :trust-tier :medium}
    "src-news1" {:id "src-news1" :name "デモニュースワイヤー(excerpt only)" :license-class :fair-use-excerpt :trust-tier :high}
    "src-synd1" {:id "src-synd1" :name "デモ配信提携先(ライセンス契約)" :license-class :licensed-syndication :trust-tier :high}}
   :listings
   {"li-100" {:id "li-100" :title "デモ記事A" :snippet "公共ドメイン記事の全文転載(デモ)。"
              :source-id "src-gov1" :category :public-affairs :status :live
              :as-of "2026-07-10T00:00:00Z" :allegation-subject? false}
    "li-200" {:id "li-200" :title "デモ記事B(告発系)" :snippet "デモ企業(架空)への疑惑報道の要約(デモ)。"
              :source-id "src-news1" :category :investigative :status :live
              :as-of "2026-07-09T20:00:00Z" :allegation-subject? true}}
   :placements
   {"slot-home-1" {:slot-id "slot-home-1" :listing-id "li-100" :sponsored? false
                   :disclosure-label nil :as-of "2026-07-10T00:00:00Z"}}
   :content-licenses
   {"lic-synd1" {:license-id "lic-synd1" :provider "デモ配信提携先(架空)" :active? true}
    "lic-expired" {:license-id "lic-expired" :provider "失効デモ提携先(架空)" :active? false}}
   :contracts
   {"tenant-acme"  {:tenant "tenant-acme" :tier :tier/analytics :active? true :purpose :ad-buyer}
    "tenant-basic" {:tenant "tenant-basic" :tier :tier/basic :active? true :purpose :syndication-partner}}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (source [_ id] (get-in @a [:sources id]))
  (all-sources [_] (sort-by :id (vals (:sources @a))))
  (listing [_ id] (get-in @a [:listings id]))
  (all-listings [_] (sort-by :id (vals (:listings @a))))
  (placement [_ slot-id] (get-in @a [:placements slot-id]))
  (content-license [_ license-id] (get-in @a [:content-licenses license-id]))
  (contract [_ tenant] (get-in @a [:contracts tenant]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :listing-upsert   (swap! a assoc-in [:listings (:id value)] value)
      :placement-upsert (swap! a assoc-in [:placements (:slot-id value)] value)
      :correction-apply (swap! a update-in [:listings (first path)] merge (:patch value))
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-sources [s ss]          (when (seq ss) (swap! a assoc :sources ss)) s)
  (with-listings [s ls]         (when (seq ls) (swap! a assoc :listings ls)) s)
  (with-placements [s ps]       (when (seq ps) (swap! a assoc :placements ps)) s)
  (with-content-licenses [s cs] (when (seq cs) (swap! a assoc :content-licenses cs)) s)
  (with-contracts [s cts]       (when (seq cts) (swap! a assoc :contracts cts)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Compound values are stored as EDN strings so `langchain.db` doesn't
  expand them into sub-entities."
  {:source/id          {:db/unique :db.unique/identity}
   :listing/id         {:db/unique :db.unique/identity}
   :placement/slot-id  {:db/unique :db.unique/identity}
   :content-license/id {:db/unique :db.unique/identity}
   :contract/tenant    {:db/unique :db.unique/identity}
   :ledger/seq         {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- source->tx [{:keys [id name license-class trust-tier]}]
  (cond-> {:source/id id}
    name          (assoc :source/name name)
    license-class (assoc :source/license-class license-class)
    trust-tier    (assoc :source/trust-tier trust-tier)))

(defn- pull->source [m]
  (when (:source/id m)
    {:id (:source/id m) :name (:source/name m)
     :license-class (:source/license-class m) :trust-tier (:source/trust-tier m)}))

(def ^:private source-pull
  [:source/id :source/name :source/license-class :source/trust-tier])

(defn- listing->tx [{:keys [id title snippet source-id category status as-of allegation-subject?]}]
  (cond-> {:listing/id id}
    title       (assoc :listing/title title)
    snippet     (assoc :listing/snippet snippet)
    source-id   (assoc :listing/source-id source-id)
    category    (assoc :listing/category category)
    status      (assoc :listing/status status)
    as-of       (assoc :listing/as-of as-of)
    true        (assoc :listing/allegation-subject (boolean allegation-subject?))))

(defn- pull->listing [m]
  (when (:listing/id m)
    {:id (:listing/id m) :title (:listing/title m) :snippet (:listing/snippet m)
     :source-id (:listing/source-id m) :category (:listing/category m)
     :status (:listing/status m) :as-of (:listing/as-of m)
     :allegation-subject? (:listing/allegation-subject m)}))

(def ^:private listing-pull
  [:listing/id :listing/title :listing/snippet :listing/source-id :listing/category
   :listing/status :listing/as-of :listing/allegation-subject])

(defn- placement->tx [{:keys [slot-id listing-id sponsored? disclosure-label as-of]}]
  {:placement/slot-id slot-id :placement/listing-id listing-id
   :placement/sponsored (boolean sponsored?) :placement/disclosure-label disclosure-label
   :placement/as-of as-of})

(defn- pull->placement [m]
  (when (:placement/slot-id m)
    {:slot-id (:placement/slot-id m) :listing-id (:placement/listing-id m)
     :sponsored? (:placement/sponsored m) :disclosure-label (:placement/disclosure-label m)
     :as-of (:placement/as-of m)}))

(def ^:private placement-pull
  [:placement/slot-id :placement/listing-id :placement/sponsored
   :placement/disclosure-label :placement/as-of])

(defn- content-license->tx [{:keys [license-id provider active?]}]
  {:content-license/id license-id :content-license/provider provider
   :content-license/active active?})

(defn- pull->content-license [m]
  (when (:content-license/id m)
    {:license-id (:content-license/id m) :provider (:content-license/provider m)
     :active? (:content-license/active m)}))

(def ^:private content-license-pull
  [:content-license/id :content-license/provider :content-license/active])

(defn- contract->tx [{:keys [tenant tier active? purpose]}]
  {:contract/tenant tenant :contract/tier tier :contract/active active? :contract/purpose purpose})

(defn- pull->contract [m]
  (when (:contract/tenant m)
    {:tenant (:contract/tenant m) :tier (:contract/tier m)
     :active? (:contract/active m) :purpose (:contract/purpose m)}))

(def ^:private contract-pull
  [:contract/tenant :contract/tier :contract/active :contract/purpose])

(defrecord DatomicStore [conn]
  Store
  (source [_ id] (pull->source (d/pull (d/db conn) source-pull [:source/id id])))
  (all-sources [_]
    (->> (d/q '[:find [?id ...] :where [?e :source/id ?id]] (d/db conn))
         (map #(pull->source (d/pull (d/db conn) source-pull [:source/id %])))
         (sort-by :id)))
  (listing [_ id] (pull->listing (d/pull (d/db conn) listing-pull [:listing/id id])))
  (all-listings [_]
    (->> (d/q '[:find [?id ...] :where [?e :listing/id ?id]] (d/db conn))
         (map #(pull->listing (d/pull (d/db conn) listing-pull [:listing/id %])))
         (sort-by :id)))
  (placement [_ slot-id]
    (pull->placement (d/pull (d/db conn) placement-pull [:placement/slot-id slot-id])))
  (content-license [_ license-id]
    (pull->content-license (d/pull (d/db conn) content-license-pull [:content-license/id license-id])))
  (contract [_ tenant] (pull->contract (d/pull (d/db conn) contract-pull [:contract/tenant tenant])))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :listing-upsert   (d/transact! conn [(listing->tx value)])
      :placement-upsert (d/transact! conn [(placement->tx value)])
      :correction-apply
      (d/transact! conn [(listing->tx (merge (listing s (first path)) (:patch value)))])
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-sources [s ss]
    (when (seq ss) (d/transact! conn (mapv source->tx (vals ss)))) s)
  (with-listings [s ls]
    (when (seq ls) (d/transact! conn (mapv listing->tx (vals ls)))) s)
  (with-placements [s ps]
    (when (seq ps) (d/transact! conn (mapv placement->tx (vals ps)))) s)
  (with-content-licenses [s cs]
    (when (seq cs) (d/transact! conn (mapv content-license->tx (vals cs)))) s)
  (with-contracts [s cts]
    (when (seq cts) (d/transact! conn (mapv contract->tx (vals cts)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`; empty when
  omitted."
  ([] (datomic-store {}))
  ([{:keys [sources listings placements content-licenses contracts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-sources sources) (with-listings listings)
         (with-placements placements) (with-content-licenses content-licenses)
         (with-contracts contracts)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo data — the Datomic-backed analog of
  `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  "Human-readable one-liner for a ledger fact (used by the demo)."
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))
