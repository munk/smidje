(ns smidje.parser.parser
  (:require [smidje.parser.arrows :refer [arrow-set]]
            [smidje.parser.checkers :refer [throws truthy TRUTHY falsey FALSEY]]))

(declare generate)

(def provided "provided")

(defn- is-arrow
  [form]
  (or (= form '=>)
      (= form '=not=>)))

(defn- ^{:testable true} provided-form?
  [form]
  (and (seq? form)
       (= (first form) 'provided)))

(defn- has-provided-form? [input]
  (and (> (count input) 3)
       (provided-form? (nth input 3))))

(defn- gen-provided-sym
  [fn1 fn2]
  (symbol (str ".." (gensym (str fn1 "->" fn2)) ".." ))) 

(defn- unnest-provided
  [provided]
  (let [match (first provided)]
    (if (some list? match)
      (let [provided-fn (first match)]
        (loop [provided-flattened [provided-fn] flattened-paramaters [] r (rest match)]
          (cond
            (empty? r) (conj flattened-paramaters (into []  (conj (rest provided ) (flatten  provided-flattened))))

            (and (list? (first r))
                 (not-empty (first r)))
               (let [sub-provide (first r)
                     sub-fn (first sub-provide)
                     metaconst (gen-provided-sym provided-fn sub-fn)]
                 (recur (conj provided-flattened metaconst) (into flattened-paramaters (unnest-provided [sub-provide '=> metaconst])) (rest r)))

            :else (recur (conj provided-flattened (first r)) flattened-paramaters (rest r) )
            )))
      [provided]
      )))

(defn- seperate-provided-forms
  [forms]
  (loop [result [] current-form (into []  (take 3 forms)) input (drop 3 forms)]
    (cond
      (empty? input) (conj result current-form)

      (and (> (count input) 2)
           (list? (first input))
           (is-arrow (second input)))
        (recur (conj result current-form) (into []  (take 3 input)) (drop 3 input))

      :else (recur result (conj current-form (first input)) (rest input)))))

(defn- aggregate-paramater-maps
   [paramater-maps]
   (apply hash-map (mapcat
                    (fn [x]  [(:paramaters x) (dissoc x :paramaters :mock-function)])
                    paramater-maps)
          ))

(defn- build-provided-map
  [provided]
  (merge
   (apply hash-map (drop 3 provided)) 
   {:mock-function (first (first provided))
    :paramaters (into [] (rest (first provided)))
    :arrow (second provided)
    :result (nth provided 2)
    }))

(defn- parse-provided
  [forms]
  (if (has-provided-form? forms)
    {:provided (->> (nth forms 3)
                    rest
                    seperate-provided-forms
                    (mapcat unnest-provided)
                    (map build-provided-map)
                    (group-by :mock-function)
                    (map (fn [x] {:mock-function (first x) :return (aggregate-paramater-maps (second x))} ))
                    )
    }
    {}))

(defn- truth-testing-form? [input]
  (and (not (seq? input))
       (boolean (some #(= input %) ['truthy 'TRUTHY 'falsey 'FALSEY]))))

(defn throws-form?
  [form]
  (and (seq? form)
       (= (first form) 'throws)))

(defn- parse-truth-testing
  [form]
  {:truth-testing form})

(defn- parse-throws
  [form]
  (merge
    ; TODO: validate that second argument is an exception type
    ; TODO: validate optional third argument is a string
    {:throws-exception (second form)}
    (when (> (count form) 2)
      {:throws-message (nth form 2)})))

(defn- parse-expected
  [form]
  (cond
    (truth-testing-form? form) (parse-truth-testing form)
    (throws-form? form) (parse-throws form)
    :else {:expected-result form}))

(defn- parse-equals
  [forms]
  (let [call-form (nth forms 0)
        arrow (nth forms 1)
        expected-form (nth forms 2)]
    (merge
      {:call-form            call-form
       :arrow                arrow
       :expected-result      expected-form
       :expected-result-form `'~expected-form}
      (parse-expected expected-form)
      (parse-provided forms))))


(defn parse
  [forms]
  (loop [result [] input forms]
    ; TODO: check for provided mocks
    ; TODO: assertions must be before provided mocks
    ; TODO: error messages on bad syntax
    (if (and (> (count input) 2)
             (is-arrow (second input)))
      (recur (conj result (parse-equals input)) (drop (if (has-provided-form? input) 4 3 ) input))
      result)))

(defn parse-fact
  [form]
  (let [name (if (string? (second form))
               (clojure.string/replace (second form) #"[^\w\d]+" "-")
               (second form))
        fact-forms (drop 2 form)]
    (-> {:tests [{:name name
         :assertions (parse fact-forms)}]})))

