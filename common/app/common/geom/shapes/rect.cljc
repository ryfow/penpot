;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.geom.shapes.rect
  (:require
   [clojure.spec.alpha :as s]
   [app.common.spec :as us]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as gpa]
   [app.common.geom.shapes.common :as gco]
   [app.common.math :as mth]
   [app.common.data :as d]))

;; --- SHAPE -> RECT

(defn- rect->rect-shape
  [{:keys [x y width height] :as shape}]
  (assoc shape
         :x1 x
         :y1 y
         :x2 (+ x width)
         :y2 (+ y height)))

(defn- path->rect-shape
  [{:keys [segments] :as shape}]
  (merge shape
         {:type :rect}
         (:selrect shape)))

(defn shape->rect-shape
  "Coerce shape to rect like shape."

  [{:keys [type] :as shape}]
  (case type
    (:curve :path) (path->rect-shape shape)
    (rect->rect-shape shape)))

;; Shape->PATH

(declare rect->path)

(defn shape->path
  [shape]
  (case (:type shape)
    (:curve :path) shape
    (rect->path shape)))

(defn rect->path
  [{:keys [x y width height] :as shape}]

  (let [points [(gpt/point x y)
                (gpt/point (+ x width) y)
                (gpt/point (+ x width) (+ y height))
                (gpt/point x (+ y height))
                (gpt/point x y)]]
    (-> shape
        (assoc :type :path)
        (assoc :segments points))))

;; -- Points

(defn points->selrect [points]
  (let [minx (transduce (map :x) min ##Inf points)
        miny (transduce (map :y) min ##Inf points)
        maxx (transduce (map :x) max ##-Inf points)
        maxy (transduce (map :y) max ##-Inf points)]
    {:x1 minx
     :y1 miny
     :x2 maxx
     :y2 maxy
     :x minx
     :y miny
     :width (- maxx minx)
     :height (- maxy miny)
     :type :rect}))

