(ns stitchcoord.actor
  "StitchCoordActor — the ISCO-08 7531 tailoring workshop
  scheduling/logistics coordination actor as a
  `langgraph.graph/state-graph` (ADR-2607121000 / CLAUDE.md Actors
  section). One graph run = one workshop-coordination operation
  request (intake -> advise -> govern -> decide -> commit/hold, with
  a human-approval interrupt for escalated proposals). No infinite
  internal loop; checkpointed per superstep so an interrupted run can
  resume after human sign-off. ISCO-08 7531 (Tailors, Dressmakers,
  Furriers and Hatters) covers needle, cutting-tool (shears) and
  sewing-machine/equipment workshop hazards, plus, for furriers
  specifically, material-provenance/handling considerations for pelt
  materials — so this actor's scope stays deliberately narrow
  (workshop scheduling/logistics coordination only). Modeled closely
  on cloud-itonami-isco-7319's craftcoord.actor (closest domain shape
  — a published, tested generic workshop scheduling/logistics
  coordination pattern for handicraft workshops).

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                             +-> :request-approval   (:escalate? true, interrupt-before)
                                             +-> :hold               (:hard? true)
  ```

  The unconditional invariant: the Stitch Coordination Advisor can
  never directly commit an operating record the StitchCoordGovernor
  refuses, and can never make this actor finalize a garment-
  construction-execution decision (e.g. deciding a garment is
  finished) or a workshop-safety-clearance decision (e.g. declaring a
  workshop safety-cleared), nor override a shop safety officer's
  judgment — every commit-record! call is gated behind `:decide`,
  and that decision class is a permanent hard block regardless of
  disposition path. This actor coordinates WORKSHOP
  SCHEDULING/LOGISTICS ONLY — it never performs garment-construction
  work or makes workshop-safety-clearance decisions itself."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [stitchcoord.advisor :as advisor]
            [stitchcoord.governor :as governor]
            [stitchcoord.store :as store]))

(defn build-graph
  "Build a compiled StitchCoordActor graph. `store` implements
  `stitchcoord.store/Store`. `advisor` implements
  `stitchcoord.advisor/Advisor` (defaults to `mock-advisor`).
  `checkpointer` defaults to an in-memory one."
  [{:keys [store advisor checkpointer]
    :or {advisor (advisor/mock-advisor)
         checkpointer (cp/mem-checkpointer)}}]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :audit       {:reducer into :default []}}})
      (g/add-node :intake (fn [s] s))
      (g/add-node :advise
                   (fn [{:keys [request]}]
                     (let [p (advisor/-advise advisor store request)]
                       {:proposal p
                        :audit [{:node :advise :request request :proposal p}]})))
      (g/add-node :govern
                   (fn [{:keys [request context proposal]}]
                     (let [v (governor/check request context proposal store)]
                       {:verdict v
                        :audit [{:node :govern :verdict v}]})))
      (g/add-node :decide
                   (fn [{:keys [verdict]}]
                     {:disposition (cond
                                     (:hard? verdict) :hold
                                     (:escalate? verdict) :request-approval
                                     :else :commit)}))
      (g/add-node :request-approval (fn [s] s))
      (g/add-node :commit
                   (fn [{:keys [request proposal]}]
                     (let [record {:artisan-id (:artisan-id request)
                                    :op (:op proposal)
                                    :workshop-id (:workshop-id proposal)
                                    :payload proposal}]
                       (store/commit-record! store record)
                       (store/append-ledger! store {:disposition :commit :record record})
                       {:record record
                        :audit [{:node :commit :record record}]})))
      (g/add-node :hold
                   (fn [{:keys [verdict]}]
                     (store/append-ledger! store {:disposition :hold :verdict verdict})
                     {:audit [{:node :hold :verdict verdict}]}))
      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges
       :decide
       (fn [{:keys [disposition]}]
         (case disposition
           :commit :commit
           :request-approval :request-approval
           :hold)))
      (g/add-edge :request-approval :commit)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/compile-graph {:checkpointer checkpointer
                         :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one operation request to completion or interrupt. `thread-id`
  scopes checkpointing for resume after human approval."
  [graph request context thread-id]
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: the interrupted `:request-approval` node
  advances straight to `:commit` on resume (approval is the act of
  resuming the thread)."
  [graph thread-id]
  (g/run* graph nil {:thread-id thread-id :resume? true}))
