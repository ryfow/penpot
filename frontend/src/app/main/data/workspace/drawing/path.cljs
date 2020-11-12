;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.drawing.path
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.main.streams :as ms]
   [app.util.geom.path :as ugp]
   [app.main.data.workspace.drawing.common :as common]))

(defn finish-event? [{:keys [type shift] :as event}]
  (or (= event ::end-path-drawing)
      (= event :interrupt)
      (and (ms/keyboard-event? event)
           (= type :down)
           (= 13 (:key event)))))

#_(defn init-path []
  (fn [state]
    (update-in state [:workspace-drawing :object]
               assoc :content []
               :initialized? true)))

#_(defn add-path-command [command]
  (fn [state]
    (update-in state [:workspace-drawing :object :content] conj command)))

#_(defn update-point-segment [state index point]
  (let [segments (count (get-in state [:workspace-drawing :object :segments]))
        exists? (< -1 index segments)]
    (cond-> state
      exists? (assoc-in [:workspace-drawing :object :segments index] point))))

#_(defn finish-drawing-path []
  (fn [state]
    (update-in
     state [:workspace-drawing :object]
     (fn [shape] (-> shape
                     (update :segments #(vec (butlast %)))
                     (gsh/update-path-selrect))))))


(defn calculate-selrect [shape]
  (let [points (->> shape
                    :content
                    (mapv #(gpt/point
                            (-> % :params :x)
                            (-> % :params :y))))]
    (assoc shape
           :points points
           :selrect (gsh/points->selrect points))))

(defn init-path []
  (ptk/reify ::init-path
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-drawing :object :initialized?] true)
          (assoc-in [:workspace-drawing :object :last-point] nil)))))

(defn finish-path []
  (ptk/reify ::finish-path
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-drawing :object :last-point] nil)
          (update-in [:workspace-drawing :object] calculate-selrect)))))

(defn preview-next-point [{:keys [x y]}]
  (ptk/reify ::add-node
    ptk/UpdateEvent
    (update [_ state]
      (let [point {:x x :y y}
            {:keys [last-point prev-handler]} (get-in state [:workspace-drawing :object])

            command (cond
                      (and last-point (not prev-handler))
                      {:command :line-to
                       :params point}

                      (and last-point prev-handler)
                      {:command :curve-to
                       :params (ugp/make-curve-params point prev-handler)}

                      :else
                      nil)
            ]
        (-> state
            (assoc-in  [:workspace-drawing :object :preview] command))))))

(defn add-node [{:keys [x y]}]
  (ptk/reify ::add-node
    ptk/UpdateEvent
    (update [_ state]
      (let [point {:x x :y y}
            {:keys [last-point prev-handler]} (get-in state [:workspace-drawing :object])

            command (cond
                      (and last-point (not prev-handler))
                      {:command :line-to
                       :params point}

                      (and last-point prev-handler)
                      {:command :curve-to
                       :params (ugp/make-curve-params point prev-handler)}

                      :else
                      {:command :move-to
                       :params point})
            ]
        (-> state
            (assoc-in  [:workspace-drawing :object :last-point] point)
            (update-in [:workspace-drawing :object] dissoc :prev-handler)
            (update-in [:workspace-drawing :object :content] (fnil conj []) command)
            (update-in [:workspace-drawing :object] calculate-selrect))))))

(defn drag-handler [{:keys [x y]}]
  (ptk/reify ::drag-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [change-handler (fn [content]
                             (let [last-idx (dec (count content))
                                   last (get content last-idx nil)
                                   prev (get content (dec last-idx) nil)
                                   {last-x :x last-y :y} (:params last)
                                   opposite (when last (ugp/opposite-handler (gpt/point last-x last-y) (gpt/point x y)))]

                               (cond
                                 (and prev (= (:command last) :line-to))
                                 (-> content
                                     (assoc last-idx {:command :curve-to
                                                      :params {:x (-> last :params :x)
                                                               :y (-> last :params :y)
                                                               :c1x (-> prev :params :x)
                                                               :c1y (-> prev :params :y)
                                                               :c2x (-> last :params :x)
                                                               :c2y (-> last :params :y)}})
                                     (update-in
                                      [last-idx :params]
                                      #(-> %
                                           (assoc :c2x (:x opposite)
                                                  :c2y (:y opposite)))))

                                 (= (:command last) :curve-to)
                                 (update-in content
                                            [last-idx :params]
                                            #(-> %
                                                 (assoc :c2x (:x opposite)
                                                        :c2y (:y opposite))))
                                 :else
                                 content))


                             )
            handler (gpt/point x y)]
        (-> state
            (update-in [:workspace-drawing :object :content] change-handler)
            (assoc-in [:workspace-drawing :object :drag-handler] handler))))))

(defn finish-drag []
  (ptk/reify ::finish-drag
    ptk/UpdateEvent
    (update [_ state]
      (let [handler (get-in state [:workspace-drawing :object :drag-handler])]
        (-> state
            (update-in [:workspace-drawing :object] dissoc :drag-handler)
            (assoc-in [:workspace-drawing :object :prev-handler] handler))))))

(defn make-click-stream
  [stream down-event]
  (->> stream
       (rx/filter ms/mouse-click?)
       (rx/debounce 200)
       (rx/first)
       (rx/map #(add-node down-event))))

(defn make-drag-stream
  [stream down-event]
  (let [mouse-up    (->> stream (rx/filter ms/mouse-up?))
        drag-events (->> ms/mouse-position
                         (rx/take-until mouse-up)
                         (rx/map #(drag-handler %)))]
    (->> (rx/timer 400)
         (rx/merge-map #(rx/concat
                         (rx/of (add-node down-event))
                         drag-events
                         (rx/of (finish-drag)))))))

(defn make-dbl-click-stream
  [stream down-event]
  (->> stream
       (rx/filter ms/mouse-double-click?)
       (rx/first)
       (rx/merge-map
        #(rx/of (add-node down-event)
                ::end-path-drawing))))

(defn handle-drawing-path []
  (ptk/reify ::handle-drawing-path
    ptk/WatchEvent
    (watch [_ state stream]

      ;; clicks stream<[MouseEvent, Position]>
      (let [mouse-down    (->> stream (rx/filter ms/mouse-down?))
            finish-events (->> stream (rx/filter finish-event?))

            mousemove-events
            (->> ms/mouse-position
                 (rx/take-until finish-events)
                 (rx/throttle 100)
                 (rx/map #(preview-next-point %)))

            mousedown-events
            (->> mouse-down
                 (rx/take-until finish-events)
                 (rx/throttle 100)
                 (rx/with-latest merge ms/mouse-position)

                 ;; We change to the stream that emits the first event
                 (rx/switch-map
                  #(rx/race (make-click-stream stream %)
                            (make-drag-stream stream %)
                            (make-dbl-click-stream stream %))))]


        (rx/concat
         (rx/of (init-path))
         (rx/merge mousemove-events
                   mousedown-events)
         (rx/of (finish-path))
         (rx/of common/handle-finish-drawing)))
      

      )))

#_(def handle-drawing-path
  (ptk/reify ::handle-drawing-path
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [flags]} (:workspace-local state)

            last-point (volatile! @ms/mouse-position)

            stoper (->> (rx/filter stoper-event? stream)
                        (rx/share))

            mouse (rx/sample 10 ms/mouse-position)

            points (->> stream
                        (rx/filter ms/mouse-click?)
                        (rx/filter #(false? (:shift %)))
                        (rx/with-latest vector mouse)
                        (rx/map second))

            counter (rx/merge (rx/scan #(inc %) 1 points) (rx/of 1))

            stream' (->> mouse
                         (rx/with-latest vector ms/mouse-position-ctrl)
                         (rx/with-latest vector counter)
                         (rx/map flatten))

            imm-transform #(vector (- % 7) (+ % 7) %)
            immanted-zones (vec (concat
                                 (map imm-transform (range 0 181 15))
                                 (map (comp imm-transform -) (range 0 181 15))))

            align-position (fn [angle pos]
                             (reduce (fn [pos [a1 a2 v]]
                                       (if (< a1 angle a2)
                                         (reduced (gpt/update-angle pos v))
                                         pos))
                                     pos
                                     immanted-zones))]

        (rx/merge
         (rx/of #(initialize-drawing % @last-point))

         (->> points
              (rx/take-until stoper)
              (rx/map (fn [pt] #(insert-point-segment % pt))))

         (rx/concat
          (->> stream'
               (rx/take-until stoper)
               (rx/map (fn [[point ctrl? index :as xxx]]
                         (let [point (if ctrl?
                                       (as-> point $
                                         (gpt/subtract $ @last-point)
                                         (align-position (gpt/angle $) $)
                                         (gpt/add $ @last-point))
                                       point)]
                           #(update-point-segment % index point)))))
          (rx/of finish-drawing-path
                 common/handle-finish-drawing)))))))

(defn close-drawing-path []
  (ptk/reify ::close-drawing-path
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-drawing :object :close?] true))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of ::end-path-drawing))))
