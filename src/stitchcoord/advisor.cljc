(ns stitchcoord.advisor
  "Stitch Coordination Advisor — proposing a tailoring workshop
  scheduling/logistics coordination operation (log a work record,
  schedule a crew operation, flag a safety concern, coordinate a
  fabric/pelt/millinery-materials supply order) from a crew roster,
  workshop registration and safety-reporting policy. Swappable
  mock/llm; the advisor ONLY proposes — `stitchcoord.governor`
  independently gates every proposal and always escalates safety
  concerns and above-threshold supply orders. ISCO-08 7531 (Tailors,
  Dressmakers, Furriers and Hatters) covers needle, cutting-tool
  (shears) and sewing-machine/equipment workshop hazards, plus, for
  furriers specifically, material-provenance/handling considerations
  for pelt materials — hazard reporting here stays generic
  (needle-hazard / cutting-tool-hazard / equipment-condition) rather
  than naming one dominant technique-specific hazard. The advisor
  never proposes to directly finalize a garment-construction-
  execution decision (e.g. deciding a garment is finished) or a
  workshop-safety-clearance decision (e.g. declaring a workshop
  safety-cleared), or to override a shop safety officer's judgment —
  those stay permanently out of this actor's scope. Modeled closely
  on cloud-itonami-isco-7319's craftcoord.advisor (closest domain
  shape — a published, tested generic workshop scheduling/logistics
  coordination pattern for handicraft workshops).

  A proposal: {:op :log-work-record|:schedule-crew-operation|
               :flag-safety-concern|:coordinate-supply-order
               :effect :propose :artisan-id str :workshop-id str
               :cost number :hazard-type kw :task str :stake kw
               :confidence n :rationale str}")

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- rationale-for [op artisan-id workshop-id hazard-type]
  (case op
    :log-work-record
    (str "logged work record for artisan " artisan-id " at workshop " workshop-id)

    :schedule-crew-operation
    (str "scheduled crew operation for tailoring task at workshop " workshop-id)

    :flag-safety-concern
    (str "flagged " (name (or hazard-type :hazard)) " concern for artisan "
         artisan-id " at workshop " workshop-id " — routed for shop safety officer review")

    :coordinate-supply-order
    (str "coordinated supply order for artisan " artisan-id " at workshop " workshop-id)

    (str "proposed " (name op) " for artisan " artisan-id " at workshop " workshop-id)))

(defn- infer [_store {:keys [op stake artisan-id workshop-id cost hazard-type task]
                       :as request}]
  {:op op
   :effect :propose
   :artisan-id artisan-id
   :workshop-id workshop-id
   :cost cost
   :hazard-type hazard-type
   :task task
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (rationale-for op artisan-id workshop-id hazard-type)})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a tailoring workshop scheduling/logistics coordination
   advisor covering ISCO-08 7531 (Tailors, Dressmakers, Furriers and
   Hatters) — needle, cutting-tool (shears) and sewing-machine/
   equipment workshop hazards, plus, for furriers specifically,
   material-provenance/handling considerations for pelt materials.
   Given a request, propose an :op (one of :log-work-record,
   :schedule-crew-operation, :flag-safety-concern,
   :coordinate-supply-order), the :artisan-id, :workshop-id, and any
   :cost/:hazard-type/:task fields, an honest :confidence and a
   :stake. Never propose an op outside this closed list, and never
   propose to directly finalize a garment-construction-execution
   decision (e.g. deciding a garment is finished) or a workshop-
   safety-clearance decision (e.g. declaring a workshop safety-
   cleared), or to override a shop safety officer's judgment — those
   are always out of this actor's scope; it coordinates workshop
   scheduling/logistics only and never performs garment-construction
   work or makes workshop-safety-clearance decisions itself. Safety
   concerns always require human sign-off regardless of confidence.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
