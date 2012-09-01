;;; ### Santa Fe Ant Trail
;;; 
;;; This is an implementation of Koza's Santa Fe Ant Trail
;;; that uses fungp's local variables and result wrappers.
;;; Unlike the interpret-ants implementation, this one 
;;; takes advantage of the JVM by compiling the individuals
;;; into JVM bytecode.

(ns fungp.sample.compile-ants
  (:use fungp.core)
  (:use fungp.util)
  (:require clojure.pprint))


;;; First some constants need to be established.

(def MAXSTEPS 40)
(def STARTFOOD [[1 0][1 1][1 2][1 3][1 4][1 5][2 5][3 5][3 6][4 6]
                [5 6][6 6][7 7][7 8][8 8][9 8][9 9][10 9][10 10][9 10]
                [8 10][8 9][11 9][11 10]])
(def NUMFOOD (count STARTFOOD))

;;; Vars in Clojure are thread-local, which is a useful property. Here
;;; a bunch of dynamic vars are declared, and they can be referenced by
;;; the rest of the code in this file. Once fungp has been started, there
;;; will be separate versions of these vars in each thread, and so the
;;; code that references them will refer to the local copy in the
;;; respective thread.

(def ^:dynamic ant-x)
(def ^:dynamic ant-y)
(def ^:dynamic ant-dir)
(def ^:dynamic food)
(def ^:dynamic eaten)
(def ^:dynamic steps)

;;; Then some helper functions.

(defn sense-food [dir x y food]
  (cond (= dir 0) (some #(= % [x (inc y)]) food)
        (= dir 1) (some #(= % [(inc x) y]) food)
        (= dir 2) (some #(= % [x (dec y)]) food)
        (= dir 3) (some #(= % [(dec x) y]) food)
        :else (throw (Throwable. "Invalid direction"))))

(defmacro sense [x y]
  `(if (sense-food ant-dir ant-x ant-y food)
    ~x ~y))

(defn new-direction [elm ant-dir]
  (cond (= elm 'left)
        (cond (= ant-dir 0) 3
              (= ant-dir 1) 0
              (= ant-dir 2) 1
              (= ant-dir 3) 2)
        (= elm 'right)
        (cond (= ant-dir 0) 1
              (= ant-dir 1) 2
              (= ant-dir 2) 3
              (= ant-dir 3) 0)
        :else ant-dir))

(defn left []
  (set! ant-dir (new-direction 'left ant-dir)))

(defn right []
  (set! ant-dir (new-direction 'right ant-dir)))

(defn new-x [ant-dir ant-x]
  (cond (= ant-dir 1) (inc ant-x)
        (= ant-dir 3) (dec ant-x)
        :else ant-x))

(defn new-y [ant-dir ant-y]
  (cond (= ant-dir 0) (inc ant-y)
        (= ant-dir 2) (dec ant-y)
        :else ant-y))

(defn new-food [ant-x ant-y food]
  (remove #(= % [ant-x ant-y]) food))

(defn new-eaten [ant-x ant-y food eaten]
  (if (some #(= % [ant-x ant-y]) food) (inc eaten) eaten))

(defn move []
  (do
    (set! ant-x (new-x ant-dir ant-x))
    (set! ant-y (new-y ant-dir ant-y))
    (set! eaten (new-eaten ant-x ant-y food eaten))
    (set! food (new-food ant-x ant-y food))))

(def ant-terminals '[(left) (right) (move)])
(def ant-functions '[[sense 2][do 2][do 3]])

(defn ant-compile-fitness 
  "This evaluates the fitness of the individuals. It uses a binding block to create
   thread-local bindings, evaluates the evolved code, and runs it in a loop."
  [tree]
  (binding [ant-x 0 ant-y 0 ant-dir 0 food STARTFOOD eaten 0 steps 0]
    (let [func (eval (list 'fn [] tree))]
      (loop [n MAXSTEPS]
        (do 
          (func) ;; run the code
          (set! steps (inc steps))
          (if (zero? n)
            (if (empty? food) steps (+ MAXSTEPS (count food)))
            (recur (- n 1))))))))

(defn ant-report
  "Reporting function. Prints out the tree and its score"
  [tree fitness]
  (clojure.pprint/pprint (nth tree 2))(print "\n")
  (print "Error:\t")(print fitness)(print "\n\n")
  (flush))

(defn test-compile-ants [n1 n2]
  (println "Ant problem (compiled)\n")
  (println (str "Food count: " NUMFOOD "\n"))
  (let [options {:iterations n1 :migrations n2 :num-islands 4 :population-size 20 :tournament-size 3
                 :max-depth 8 :mutation-depth 2
                 :terminals ant-terminals :fitness ant-compile-fitness
                 :functions ant-functions :report ant-report}
        [tree score] (rest (run-genetic-programming options))]
    (do (println "Done!")
        (ant-report tree score))))
  