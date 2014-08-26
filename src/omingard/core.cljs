;; 1. Set up:
;;    a. Let there be cards in a stack: 2 Rummy decks (104 cards - ace, 2-10, jack, queen, king; each suit twice)
;;    b. Shuffle the stack.
;;    c. Serve cards to columns.
;; 2. Game loop: either move a free open card from one column to another, discard aces
;;    (and after them 2s etc.) to one of eight discard piles, or serve new open cards (1 per column)
;; 3. End of game: continue until there are no more moves or all cards have been discarded to the piles.

(ns omingard.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs.core.async :refer [put! chan <!]]
            [clojure.data :as data]
            [clojure.string :as string]))

(enable-console-print!)
(js/React.initializeTouchEvents true)

;; : : : DEBUGGING HELPERS : : : : : : : : :

      (defn debugg [app text]
        (update-in app [:debug-texts] (fn [a] (cons text a))))

      ;; transforms "J" etc. back to 11 etc.
      (defn value-from-literal-value [literal-value]
        (let [literal-value (string/lower-case literal-value)]
          (cond
            (= literal-value "a") 1
            (= literal-value "j") 11
            (= literal-value "q") 12
            (= literal-value "k") 13
            :else (js/parseInt literal-value))))

      (defn make-card [card-string]
        "Builds a card map from a string - \"d.12.a\", e.g., creates a queen of diamonds (with deck \"a\")."
        (let [card-components (string/split card-string #"\.")
              suit  (keyword (first card-components))
              value (second card-components)
              option (when (= 3 (count card-components)) (keyword (last card-components)))
              deck   (when (some #{option} [:a :b]) option)
              open   (when (= :o option) true)
              card {:suit
                    (cond (= suit :s) :spades
                          (= suit :c) :clubs
                          (= suit :h) :hearts
                          (= suit :d) :diamonds)
              :value (value-from-literal-value value)
              :deck (or deck :a)}]
          (if open (assoc card :open true) card)))
;; - - END -- DEBUGGING HELPERS : : : : : :


;; : : : GLOBAL CONSTANTS : : : : : : : : :
(def columns# 9)


;; : : : 1. GENERATE A STACK OF CARDS : : :

(def suits [:hearts :diamonds :spades :clubs])

(defn cards-for-suit [suit]
  (mapcat
    (fn [value]
      ;; need a deck parameter to distinguish cards with the same value and suit in the same column
      [{:deck :a :suit suit :value value}
       {:deck :b :suit suit :value value}])
    (range 1 14)))

(defn shuffled-stack []
  (shuffle (mapcat cards-for-suit suits)))

(defn piles-for-suits [suits]
  (vec (map-indexed (fn [idx suit] {:index idx :suit suit :cards []}) suits)))

;; initialise app state
(def app-state
  (atom
    {:stack (shuffled-stack)
     :piles (piles-for-suits (mapcat (fn [suit] [suit suit]) suits))
     :columns (vec (map-indexed (fn [idx _] {:index idx :cards []}) (range columns#)))
     :debug-texts []
    }))

(def app-history (atom [@app-state]))

(add-watch app-state :history
  (fn [_ _ _ n]
    (when-not (= (last @app-history) n)
      (swap! app-history conj n))))

(defn serve-card-to-column [state column-index & [open?]]
  (let [card (peek (:stack state))
        card (if (and open? card)
               (assoc card :open true)
               card)]
    (if card
      (-> state
          (update-in [:stack] pop)
          (update-in [:columns column-index :cards] conj card))
      ;; do nothing if stack is empty
      state)))

(defn serve-cards-to-column [state column-index n]
  (reduce
    (fn [memo val]
      (serve-card-to-column memo column-index (if (= (- n 1) val) true false)))
    state
    (range n)))

(defn serve-cards [state]
  (reduce
    (fn [state [idx n]]
      (serve-cards-to-column state idx n))
    state
    (map-indexed vector [1 2 3 4 5 4 3 2 1])))

;; set up initial state of the game
(swap! app-state serve-cards)

;; : : : HELPER FUNCTIONS : : : : : : : : :
;; returns strings b/c we can't use keywords to set CSS classes.
(defn colour [suit]
  "Returns a suit's colour as a string (not a keyword b/c we use it for CSS classes)."
  (cond
    (some #{suit} [:hearts :diamonds]) "red"
    (some #{suit} [:clubs :spades])    "black"))

(defn card-colour [{suit :suit}]
  "Returns a card's colour as a string."
  (colour suit))

(defn display-value [{value :value}]
  "Takes a card and returns their value or converted value (\"A\" for ace, \"J\" for jack etc.)."
  (cond
    (= value 1) "A"
    (and (> value 0) (< value 11)) (str value)
    (= value 11) "J"
    (= value 12) "Q"
    (= value 13) "K"))

(defn symbol-for-suit [suit]
  "Takes a suit and returns its ASCII symbol, e.g. ♠ for :spades."
  (case suit
    :spades "♠"
    :hearts "♥"
    :diamonds "♦"
    :clubs "♣"
    nil))

(defn open? [card]
  "Check whether a card is open."
  (:open card))

(defn label-for [card]
  "Returns a human-readable string for a card, e.g. \"♠ 7\""
  (str (symbol-for-suit (:suit card))
       " "
       (display-value card)
       " ("
       (:deck card)
       ")"))

(defn unmark-card [card]
  "Removes marking from a card."
  (dissoc card :moving))

(defn index-for [vektor card]
  "Takes a vector and a card and returns the index of the card in the vector."
  (first (keep-indexed (fn [idx el] (when (= el card) idx)) vektor)))

(defn children-of [column-cards card]
  "Returns a vector of all the cards below a certain card in a column."
  (vec (rest (drop-while
               (fn [el] (not= el card))
               column-cards))))

(defn with-alternating-colours? [cards]
  "Takes a vector of cards (not a column b/c it's usually fed with the
  result of children-of) and check whether they have alternating colours."
  (let [colours (map card-colour cards)]
    ;; JFYI: the `reduce` can return `false` if the last element of
    ;; `cards` is `false`, but this function expects to be handed cards anyway.
    ;; works when `cards` contains only one card
    (boolean (reduce
               (fn [memo colour] (if-not (= memo colour) colour (reduced false))) ;; `reduced` breaks the iteration
               (first colours)
               (rest colours))))) ;; reduce returns false or the last card's colour

(defn with-descending-values? [cards]
  "Checks whether a vector of cards is ordered by the cards' values and has no gaps."
  (let [values (map :value cards)]
    (= values
       (vec (reverse (range (:value (last cards)) (inc (:value (first cards)))))))))

(defn sorted-from-card? [column-cards card]
  "Takes a column and a card and checks whether the card and its children are sorted (i.e. with alternating colours and descending values)."
  (let [children (children-of column-cards card)]
    (cond
      (empty? children)
        (= card (last column-cards)) ;; card is either the last card in the column (true), or not in the column at all (false)
      :else
        (let [cards (cons card children)]
          (and (with-descending-values? cards)
            (with-alternating-colours? cards))))))

(defn moveable? [column-cards card]
  "Takes a column and a card and checks whether the card can be moved elsewhere."
  (and (open? card)
       (sorted-from-card? column-cards card)))

;; TODO: consider should simply returning a pile index here - after all that's all we need in discardable?
(defn free-pile-for [piles card]
  "Takes a vector of piles and a card and returns a pile where the card can be discarded."
  (first
    (->> piles
         (filter
           (fn [pile]
             (and
               (= (:suit pile) (:suit card))
               (= (count (:cards pile)) (dec (:value card)))))))))

(defn column-for [columns card]
  "Takes a vector of columns and a card and returns the column that contains the card."
  (first
    (->> columns
         (filter
           (fn [column] (some #{card} (:cards column)))))))

(defn pile-for [piles card]
  "Takes a vector of piles and a card and returns the pile that contains the card."
  (first
    (->> piles
         (filter
           (fn [pile] (some #{card} (:cards pile)))))))

(defn discardable? [app card]
  (let [column (column-for (:columns app) card)]
    (and (moveable? (:cards column) card)
         (free-pile-for (:piles app) card))))

;; only a column's last card is discardable
;; idea for improvement: clicking on the highest sorted card on
;; a pile discards all sorted cards below it automatically as well.
(defn discard-card [app card]
  (let [column (column-for (:columns app) card)]
    (cond
      (discardable? app card)
        (-> app
          (update-in [:columns (:index column) :cards]
                     pop)
          (update-in [:piles (:index (free-pile-for (:piles app) card)) :cards]
                     conj (unmark-card card))
          ;; open new last card of column
          (update-in [:columns (:index column) :cards]
                    (fn [cards]
                      (when (seq cards)
                        (assoc-in cards [(dec (count cards)) :open] true)))))
      :else
        (-> app
          (update-in [:columns (:index column) :cards (index-for (:cards column) card)]
                     unmark-card)) ;; do nothing if card cannot be discarded
    )))

(defn path-vector-for-card [app card]
  (let [column (column-for (:columns app) card)
        pile (pile-for (:piles app) card)]
    (cond
      column
        [:columns (:index column) :cards (index-for (:cards column) card)]
      pile
        [:piles (:index pile) :cards (index-for (:cards pile) card)]
      ;; else: the card's still in the stack and a path vector of nil is fine
  )))

(defn mark-card-for-moving [app card]
  "Find and mark a certain card for moving."
  (assoc-in app (conj (path-vector-for-card app card) :moving) true))

(defn mark-column-card-for-moving [app column-index card-index]
  "Mark a card in a column for moving and find it via its column- and card-index."
  (assoc-in app [:columns column-index :cards card-index :moving] true))

(defn mark-card-and-children-for-moving [app card]
  (let [column (column-for (:columns app) card)
        column-cards (:cards column)]
    (reduce
      (fn [memo idx] (mark-column-card-for-moving memo (:index column) idx))
      app
      (range (index-for column-cards card) (count column-cards)))))

(defn all-cards [app]
  (reduce
    (fn [memo el]
      (apply conj memo (:cards el)))
    []
    (:columns app)))

(defn cards-marked-for-moving [app]
  (filter
    :moving
    (all-cards app)))

(defn unmark-all-column-cards [app]
  (let [cards-to-unmark (cards-marked-for-moving app)
        columns (:columns app)]
    (reduce
      (fn [memo card]
        (let [column (column-for columns card)]
          (update-in memo
                     [:columns (:index column) :cards (index-for (:cards column) card)]
                     #(unmark-card %))))
      app
      cards-to-unmark)))

(defn can-be-appended-to? [card column]
  "Takes a card and a column and checks whether the card can be appended to the column."
  (let [upper-card (last (:cards column))
        lower-card card]
    (and (= (:value upper-card) (inc (:value lower-card)))
         (not= (card-colour upper-card) (card-colour lower-card)))))

(defn move-marked-cards-to [app new-column]
  (let [columns (:columns app)
        cards-to-move (cards-marked-for-moving app)]
    (reduce
      (fn [memo card]
        (let [old-column (column-for columns card)]
          (-> memo
            (update-in [:columns (:index old-column) :cards]
                       pop)
            (update-in [:columns (:index old-column) :cards]
                       #(when (seq %)
                          (assoc-in % [(dec (count %)) :open] true)))
            (update-in [:columns (:index new-column) :cards]
                       conj card))))
      app
      cards-to-move)))

(defn handle-click [app clicked-card]
  (let [column (column-for (:columns app) clicked-card)]
    (cond
      ;; card's on top of a pile - maybe write a function for this with a better check
      (and (not column) (open? clicked-card))
        (mark-card-for-moving app clicked-card)
      (moveable? (:cards column) clicked-card)
        (cond
          ;; double click
          (some #{clicked-card} (cards-marked-for-moving app))
            (discard-card app clicked-card)
          ;; single click
          :else
            (cond
              ;; there are cards marked for moving -> try to move cards below `card`.
              (seq (cards-marked-for-moving app))
                (do
                  (js/console.log "Try to move some cards here")
                  (if (can-be-appended-to? (first (cards-marked-for-moving app)) column)
                    (do
                      (js/console.log "Looks safe, moving!")
                      (-> app
                        (move-marked-cards-to (column-for (:columns app) clicked-card))
                        (unmark-all-column-cards)))
                    (do
                      (js/console.log "Sorry, cannot move that there, honey!")
                      (-> app
                          (unmark-all-column-cards)
                          (mark-card-and-children-for-moving clicked-card)))))
              ;; there are no cards marked for moving yet -> mark this one.
              :else
                (do
                  (js/console.log "no cards marked for moving")
                  (mark-card-and-children-for-moving app clicked-card))))
      :else
        app)))


;; : : :   2.   G A M E   L O O P   : : : : : : : : :

(defn serve-new-cards [app]
  "When there are no more moves, append a new open card to each column."
  (let [app (unmark-all-column-cards app)]
    (reduce
      (fn [memo i]
        (serve-card-to-column memo i true))
      app
      (range columns#))))


;; : : : V I E W S : : : : : : : : : :

(defn handle-column-placeholder-click [app column-index]
  (js/console.log "handle-column-placeholder-click" column-index))

(defn column-placeholder-view [column-index owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [channel]}]
      (dom/li #js {:className "m-column--placeholder"
                   :onClick (fn [event]
                     (.preventDefault event)
                     (put! channel [handle-column-placeholder-click @column-index]))}
                  ))))

(defn card-view [card owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [channel]}]
      (dom/li #js {:className (str "m-card" (when (open? card) " as-open") (when (:moving card) " as-moving"))
                   :onClick (fn [event]
                     (.preventDefault event)
                     (put! channel [handle-click @card]))
                   :onTouchEnd (fn [event]
                     (.preventDefault event)
                     (put! channel [handle-click @card]))
                   :ref "card"}
        (dom/span #js {:className (card-colour card)}
          (label-for card))))))

(defn column-view [column owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [channel]}]
      (let [column-cards (:cards column)]
        (dom/div #js {:className "m-column-wrapper"} ;; .m-column on the div and not the <ul> so empty columns don't disappear
          (cond
            (seq column-cards)
              (apply dom/ul #js {:className "m-column"}
                (om/build-all card-view column-cards {:init-state {:channel channel}}))
            :else
              (dom/ul #js {:className "m-column"}
                (om/build column-placeholder-view {:index (:index column)}))))))))


(defn columns-view [columns owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [channel]}]
      (dom/div #js {:className "m-columns-wrapper"}
        (apply dom/ul #js {:className "m-columns cf"}
        (om/build-all column-view columns {:init-state {:channel channel}}))))))

(defn undo [app]
  (when (> (count @app-history) 1)
    (swap! app-history pop)
    (reset! app-state (last @app-history))))

(defn navigation-view [app owner]
  (reify
    om/IRender
    (render [this]
      (dom/div #js {:className "l-navigation-container"}
        (dom/ul #js {:className "m-navigation cf"}
          (dom/li #js {:className "m-navigation--item"}
            (dom/h1 #js {:className "m-navigation--title"}
              "Omingard"))
          (dom/li #js {:className "m-navigation--item as-right"}
            (dom/button #js {:className "m-navigation--hit-me"
                             :onClick (fn [_] (om/transact! app serve-new-cards))}
                        "Hit me!"))
          (dom/li #js {:className "m-navigation--item as-right"}
            (dom/button #js {:className "m-navigation--undo"
                             :onClick (fn [_] (om/transact! app undo))}
                        "↶ Undo")))))))


(defn pile-view [pile owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [channel]}]
      (dom/li #js {:className "m-pile"}
        (let [cards (:cards pile)]
          (if (seq cards)
            (apply dom/ul #js {:className "m-pile--cards"}
              (om/build-all card-view cards {:init-state {:channel channel}}))
            ;; pile has no cards
            (let [suit (:suit pile)]
              (dom/span #js {:className (str "m-pile--placeholder " (colour suit))}
                (symbol-for-suit suit)))))))))

(defn piles-view [piles owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [channel]}]
      (dom/div #js {:className "l-piles-container"}
        (dom/h3 nil "Piles")
        (apply dom/ul #js {:className "m-piles cf"}
          (om/build-all pile-view piles {:init-state {:channel channel}}))))))

(defn omingard-view [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:channel (chan)})
    om/IWillMount
    (will-mount [_]
       (let [channel (om/get-state owner :channel)]
         (go (loop []
           (let [[func & attrs] (<! channel)]
             (om/transact! app (fn [xs] (apply func xs attrs))))
           (recur)))))
    om/IDidMount
    (did-mount [this]
      (.focus (om/get-node owner)))
    om/IRenderState
    (render-state [this {:keys [channel]}]
      (dom/div #js {:className "omingard-wrapper"
                    :tabIndex 0 ;; for focussing
                    :onKeyDown (fn [event]
                      ;; do not `(.preventDefault event)` as that'd disable ctrl+r and other browser keyboard shortcuts
                      (when (= 13 (.-keyCode event))
                            (om/transact! app serve-new-cards)))}
        (om/build navigation-view app)
        (dom/div #js {:className "l-game-container"}
          (om/build columns-view (:columns app) {:init-state {:channel channel}})
          (dom/div #js {:className "l-debug"}
            (dom/h3 nil "Debug (newest click events first)")
            (apply dom/ul #js {:className "m-debug-texts"}
              (map-indexed
                (fn [idx el]
                  (dom/li #js {:className "m-debug-texts--item"}
                    (str (- (count (:debug-texts app)) idx) ". " el)))
                (:debug-texts app))))
          (om/build piles-view (:piles app) {:init-state {:channel channel}}))
      ))))

(om/root
  omingard-view
  app-state
  {:target (. js/document (getElementById "omingard"))})
