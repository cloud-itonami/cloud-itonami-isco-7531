(ns stitchcoord.store
  "SSoT for the ISCO-08 7531 tailoring workshop scheduling/logistics
  coordination actor (itonami actor pattern, ADR-2607121000 /
  CLAUDE.md Actors section; README's 'Robotics premise' — a workshop
  scheduling/logistics coordination robot performs crew scheduling,
  job/commission/progress-record logging and fabric/pelt/millinery
  materials supply-order coordination for a tailoring, dressmaking,
  fur or millinery workshop under this advisor/governor pair, which
  never dispatches hardware itself, never performs garment-
  construction work itself, and never finalizes a garment-
  construction-execution decision or a workshop-safety-clearance
  decision, nor overrides a shop safety officer's judgment — those
  remain the shop safety officer's exclusive judgment). ISCO-08 7531
  (Tailors, Dressmakers, Furriers and Hatters) covers needle/shears/
  sewing-machine workshop hazards plus, for furriers specifically,
  material-provenance/handling considerations for pelt materials —
  this store's domain shape stays generic (a job/commission record,
  not a technique-specific batch record) so it covers tailoring,
  dressmaking, fur and millinery work alike. Modeled closely on
  cloud-itonami-isco-7319's craftcoord.store (closest domain shape —
  a published, tested generic workshop scheduling/logistics
  coordination pattern for handicraft workshops).

  Domain:

    artisan  — a registered tailoring/dressmaking/fur/millinery
               workshop crew member (:artisan-id, :name)
    workshop — a registered tailoring/dressmaking/fur/millinery
               workshop site {:workshop-id :name :max-supply-cost}.
               `:max-supply-cost` is an informational registered
               ceiling used only to decide whether a
               `:coordinate-supply-order` proposal escalates to human
               sign-off (the governor never blocks a within-threshold
               order outright; it only decides commit vs. escalate).
    record   — a committed operating record (a logged job/commission/
               progress entry, a scheduled crew/task operation, a
               flagged safety concern, or a coordinated fabric/pelt/
               millinery-materials supply order) — written ONLY via
               commit-record!.
    ledger   — append-only audit trail, commit or hold.")

(defprotocol Store
  (artisan [s artisan-id])
  (workshop [s workshop-id])
  (records-of [s artisan-id])
  (ledger [s])
  (register-artisan! [s a])
  (register-workshop! [s w])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (artisan [_ artisan-id] (get-in @a [:artisans artisan-id]))
  (workshop [_ workshop-id] (get-in @a [:workshops workshop-id]))
  (records-of [_ artisan-id] (filter #(= artisan-id (:artisan-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-artisan! [s m]
    (swap! a assoc-in [:artisans (:artisan-id m)] m) s)
  (register-workshop! [s w]
    (swap! a assoc-in [:workshops (:workshop-id w)] w) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:artisans {} :workshops {} :records [] :ledger []}
                                    seed)))))
