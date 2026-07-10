(ns portal.facts
  "R0 source-provenance catalog — the ONLY content-licensing classes the
  PortalGovernor will accept as a citation for an aggregated listing
  (mirrors `cloud-itonami-isic-6311`'s `marketdata.facts` discipline:
  honesty over coverage). Four classes, three of them real, citable, free
  legal bases requiring no license agreement, one structural:

    1. :public-domain      — US federal government works (17 U.S.C. §105,
                              no copyright in works of the US Government).
    2. :cc-attribution     — Creative Commons Attribution family (CC BY /
                              CC BY-SA), full reproduction permitted WITH
                              attribution + link (creativecommons.org).
    3. :fair-use-excerpt   — the fair-use excerpt/commentary doctrine
                              (17 U.S.C. §107). Fair use has NO numeric
                              bright-line test — `policy/license-scope-
                              gate`'s excerpt-length cap is an operator-
                              tunable CONSERVATIVE structural proxy for
                              'this is an excerpt, not a full-text
                              reproduction', not a claim that anything
                              under the cap is legally guaranteed fair use.
    4. :licensed-syndication — the structural class for a real commercial
                              syndication deal (wire services, licensed
                              news partners). Same operator-supplies-own-
                              licensed-feed boundary as `cloud-itonami-
                              isic-6311`'s `:licensed-operator-feed`: a
                              listing citing this class is only accepted
                              when its `:license-id` resolves to an ACTIVE
                              `content-license` record in the store.

  Adding coverage means adding a real, citable legal basis (classes 1-3)
  or a real registered content-license (class 4) — never fabricating
  either.")

(def fair-use-excerpt-max-chars
  "Conservative operator-tunable ceiling on snippet length for
  `:fair-use-excerpt`-sourced listings (see namespace docstring — NOT a
  legal bright-line, a structural safety margin)."
  400)

(def allowed-source-classes
  #{:public-domain :cc-attribution :fair-use-excerpt :licensed-syndication})

(def catalog
  "Each entry: {:id :name :class :basis :url}. `:class` is the value that
  must appear in a listing's `:source :class` for the source-provenance
  check to accept it as grounded."
  [{:id :usc-17-105
    :name "17 U.S.C. §105 — no US federal copyright"
    :class :public-domain
    :basis "US federal government works carry no copyright by statute."
    :url "https://www.copyright.gov/title17/92chap1.html#105"}
   {:id :cc-by-4
    :name "Creative Commons Attribution 4.0 (CC BY 4.0)"
    :class :cc-attribution
    :basis "Full reproduction permitted with attribution + link."
    :url "https://creativecommons.org/licenses/by/4.0/"}
   {:id :usc-17-107
    :name "17 U.S.C. §107 — fair use (excerpt/commentary)"
    :class :fair-use-excerpt
    :basis "Four-factor fair-use test; no numeric bright line, courts weigh substantiality of the excerpt against the whole."
    :url "https://www.copyright.gov/title17/92chap1.html#107"}
   {:id :licensed-syndication
    :name "Operator-registered licensed syndication agreement"
    :class :licensed-syndication
    :basis "A real commercial content-syndication contract, registered as a `content-license` record."
    :url nil}])

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers."
  []
  {:source-count (count catalog)
   :free-legal-bases (into #{} (map :id (remove #(= :licensed-syndication (:class %)) catalog)))
   :note (str "R0 scope: 3 real, citable, free legal bases (US federal PD, "
              "CC BY 4.0, fair-use excerpt) + 1 structural licensed-"
              "syndication class requiring a real registered content-"
              "license. Extend only by appending a real, citable legal "
              "basis or a real registered license — never fabricate "
              "either.")})

(defn class-allowed? [source-class]
  (contains? allowed-source-classes source-class))

(defn licensed-syndication-class? [source-class]
  (= :licensed-syndication source-class))

(defn excerpt-capped-class? [source-class]
  (= :fair-use-excerpt source-class))
