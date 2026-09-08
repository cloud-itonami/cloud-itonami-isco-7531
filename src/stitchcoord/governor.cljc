(ns stitchcoord.governor
  "StitchCoordGovernor — the independent safety/scope layer gating
  every tailoring workshop scheduling/logistics proposal an advisor
  may make for a tailoring, dressmaking, fur or millinery workshop
  crew. ISCO-08 7531 (Tailors, Dressmakers, Furriers and Hatters)
  covers needle, cutting-tool (shears) and sewing-machine/equipment
  workshop hazards, plus, for furriers specifically, material-
  provenance/handling considerations for pelt materials — this
  governor's hazard-escalation path stays generic (needle-hazard /
  cutting-tool-hazard / equipment-condition) rather than gating one
  dominant technique-specific hazard. The governor never dispatches
  hardware itself, never performs garment-construction work itself,
  and never finalizes a garment-construction-execution decision (e.g.
  deciding a garment is finished) or a workshop-safety-clearance
  decision (e.g. declaring a workshop safety-cleared), nor overrides
  a shop safety officer's judgment — those are permanently out of
  this actor's scope and remain a shop safety officer's exclusive
  judgment (README's 'Robotics premise': this actor coordinates
  WORKSHOP SCHEDULING/LOGISTICS ONLY — it never performs garment-
  construction work or makes workshop-safety-clearance decisions
  itself). Modeled closely on cloud-itonami-isco-7319's
  craftcoord.governor (closest domain shape — a published, tested
  generic workshop scheduling/logistics coordination pattern for
  handicraft workshops).

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. artisan provenance     — the crew member must be independently
                                verified/registered before any action.
    2. workshop provenance    — the workshop site must be independently
                                verified/registered before any action.
    3. no-actuation           — proposal :effect must be :propose (the
                                governor never dispatches hardware and
                                never performs garment-construction
                                work itself; it only gates what the
                                advisor may coordinate).
    4. closed op-allowlist    — only :log-work-record,
                                :schedule-crew-operation,
                                :flag-safety-concern and
                                :coordinate-supply-order may ever be
                                proposed; anything else is refused.
    5. scope-excluded action  — any proposal to directly finalize a
                                garment-construction-execution
                                decision (e.g. deciding a garment is
                                finished) or a workshop-safety-
                                clearance decision (e.g. declaring a
                                workshop safety-cleared), or to
                                override a shop safety officer's
                                judgment, is a hard, permanent block
                                (checked both against the proposed
                                :op and, defense-in-depth, against the
                                proposal's :rationale text — matched
                                as full finalization/execution ACTION
                                phrases such as \"finalize the
                                garment construction\" / \"declare
                                the workshop safety cleared\" /
                                \"override the shop safety officer's
                                judgment\", never as bare nouns like
                                \"needle\", \"pelt\" or \"shears\",
                                so the check can never self-trip on
                                the advisor's own routine rationale
                                text, e.g. \"logged work record for
                                artisan …\" or \"scheduled crew
                                operation for tailoring task …\" or
                                \"…routed for shop safety officer
                                review\" — none of these is a
                                finalization action, and all are
                                exercised by
                                `governor-test/default-mock-advisor-proposals-never-self-trip-on-scope-exclusion`).
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off
  regardless of confidence):
    6. :op :flag-safety-concern (a needle-hazard /
                                cutting-tool-hazard / equipment-
                                condition concern always escalates to
                                a human, never auto-commits).
    7. :op :coordinate-supply-order above `supply-cost-threshold`.
    8. low confidence (< `confidence-floor`)."
  (:require [kotoba.lang.text :as str]
            [stitchcoord.store :as store]))

(def confidence-floor 0.6)
(def supply-cost-threshold 2000)

(def allowed-ops
  #{:log-work-record :schedule-crew-operation
    :flag-safety-concern :coordinate-supply-order})

;; Defense-in-depth: none of these ops are ever in `allowed-ops`
;; above, so they are already refused by the closed-allowlist check
;; below; they are named again here — as explicit finalization/
;; execution ACTIONS, never bare nouns — so a future allowlist edit
;; cannot silently re-open this specific out-of-scope path without
;; also touching this list.
(def ^:private scope-excluded-ops
  #{:finalize-garment-construction :finalize-garment-construction-execution-decision
    :declare-workshop-safety-cleared :declare-safety-clearance
    :override-shop-safety-officer-judgment :override-safety-officer-judgment})

;; Full finalization/execution ACTION phrases only — never bare nouns
;; ("needle", "pelt", "shears", "workshop", "officer") — so this can
;; never match inside the mock advisor's own default rationale text
;; (which legitimately contains those bare nouns, e.g. "tailoring
;; task" / "shop safety officer review"). See
;; `governor-test/default-mock-advisor-proposals-never-self-trip-on-scope-exclusion`.
(def ^:private scope-excluded-phrases
  ["finalize the garment construction" "finalize the garment construction execution decision"
   "proceed with the garment construction" "authorize the garment construction"
   "declare the workshop safety cleared" "declare the workshop safety-cleared"
   "declare safety-cleared" "declare workshop-safety-cleared"
   "override the shop safety officer's judgment"
   "override the safety officer's judgment"
   "override shop safety officer judgment"])

(defn- contains-excluded-phrase? [s]
  (let [s (str/lower (or s ""))]
    (boolean (some #(str/includes? s %) scope-excluded-phrases))))

(defn- hard-violations [proposal artisan-record workshop-record]
  (let [{:keys [op rationale]} proposal]
    (cond-> []
      (nil? artisan-record)
      (conj {:rule :no-artisan
             :detail "未登録 artisan への提案は不可（artisan record は独立して検証・登録済みでなければならない）"})

      (nil? workshop-record)
      (conj {:rule :no-workshop
             :detail "未登録 workshop への提案は不可（workshop record は独立して検証・登録済みでなければならない）"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation
             :detail "effect は :propose のみ許可（governor は仕立て作業を直接実行しない）"})

      (not (contains? allowed-ops op))
      (conj {:rule :unknown-op
             :detail (str op " は closed op-allowlist に無い — 提案不可")})

      (or (contains? scope-excluded-ops op) (contains-excluded-phrase? rationale))
      (conj {:rule :scope-excluded-action
             :detail "衣服製作実行判断の確定・workshop safety clearance 判断の確定・shop safety officer の判断の上書きは、この actor の権限外 — 常に永続ブロック"}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `stitchcoord.store/Store`. Pure — never mutates
  the store, never dispatches a workshop operation."
  [request _context proposal store]
  (let [artisan-record (store/artisan store (:artisan-id request))
        workshop-record (some->> (:workshop-id proposal) (store/workshop store))
        hard (hard-violations proposal artisan-record workshop-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        supply-order-over-threshold?
        (and (= :coordinate-supply-order (:op proposal))
             (number? (:cost proposal))
             (> (:cost proposal) supply-cost-threshold))
        always-risky? (or (= :flag-safety-concern (:op proposal))
                           supply-order-over-threshold?)]
    {:ok? (and (not hard?) (not low?) (not always-risky?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? always-risky?))}))
