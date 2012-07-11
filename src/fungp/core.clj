;;; Mike Vollmer, 2012, GPL
;;;
;;; [Project hosted on GitHub](https://github.com/probabilityZero/fungp)
;;;
;;; What is this?
;;; -------------
;;;
;;; **fungp** is a parallel genetic programming library implemented in the
;;; Clojure programming language, pronounced fun-gee-pee. The "fun" comes
;;; from functional, and because genetic programming can be fun! Also I'm
;;; bad at naming things.
;;;
;;; > There are only two hard things in Computer Science: cache invalidation,
;;; > naming things, and off-by-one errors.
;;; >
;;; > --- *Paraphrased from Phil Karlton*
;;; 
;;; The library is in its early stages right now, but it's usable. Currently it
;;; has the following features:
;;;
;;;  * Custom evaluation and reporting logic
;;;  * Parallelism: subpopulations run in native threads
;;;  * Evolve and test functions of multiple arities
;;;
;;; How do I use it?
;;; ----------------
;;;
;;; Call the **run-genetic-programming** function with a map containing these keyword parameters:
;;;
;;; * iterations : number of iterations *between migrations*
;;; * migrations : number of migrations
;;; * num-islands : number of islands
;;; * population-size : size of the populations
;;; * tournament-size : size of the tournaments
;;; * mutation-probability : probability of mutation
;;; * mutation-depth : depth of mutated trees
;;; * max-depth : maximum depth of trees
;;; * terminals : terminals used in tree building
;;; * functions : functions used in tree building, in the form [function arity]
;;; * fitness : a fitness function that takes a tree and returns an error number, lower is better
;;; * report : a reporting function passed [best-tree best-fit] at each migration
;;; 
;;; * * *
;;; 

(ns fungp.core
  "This is the start of the core of the library."
  (:use fungp.util))

;;; ### Tree manipulation
;;;
;;; My method of random tree generation is a combination of the "grow" and "fill"
;;; methods of tree building, similar to Koza's "ramped half-and-half" method.

(defn create-tree
  "Build a tree of source code, given a mutation depth, terminal sequence, 
   function sequence, and type keyword. The type can be either :grow or :fill.
   The terminal sequence should consist of symbols or quoted code, while elements in the
   function sequence should contain both the function and a number representing
   its arity, in this form: [function arity]."
  [mutation-depth terminals functions type]
  ;; conditions: either return terminal or create function and recurse
  (cond (zero? mutation-depth) (rand-nth terminals)
        (and (= type :grow) (flip 0.5)) (rand-nth terminals)
        :else (let [[func arity] (rand-nth functions)]
                (cons func (repeatedly arity 
                                       #(create-tree (- mutation-depth 1)
                                                     terminals
                                                     functions
                                                     type))))))        

(defn create-population
  "Creates a population of trees given a population size, mutation depth, terminal
   sequence, and function sequence. It uses a variation of Koza's \"ramped half-and-half\"
   method: a coin flip determines whether to use the \"fill\" or \"grow\" method, and the
   mutation depth is a randomly chosen number between 1 and the specified max mutation depth."
  [population-size mutation-depth terminals functions]
  (if (zero? population-size) []
    (conj (create-population (- population-size 1) mutation-depth terminals functions)
          (create-tree (+ 1 (rand-int mutation-depth)) terminals functions 
                       (if (flip 0.5) :grow :fill)))))

(defn max-tree-height
  "Find the maximum height of a tree. The max height is the distance from the root to the
   deepest leaf."
  [tree] 
  (if (not (seq? tree)) 0 
    (+ 1 (reduce max (map max-tree-height tree)))))

(defn valid-tree?
  "Checks the type on a tree to make sure it's a list, symbol, or number."
  [tree] (or (list? tree) (symbol? tree) (number? tree)))

(defn compile-tree
  "Compiles a tree into a Clojure function, and thus into JVM bytecode. Takes a tree
   and the parameters the function should have."
  [tree parameters] (eval (list 'fn parameters tree)))

;;; **rand-subtree** and **replace-subtree** are two of the most important functions.
;;; They define how the trees are modified.

;;; The basic idea for how I implemented both of them is that recursion can be
;;; used to reduce the problem at each step: given a tree (or a subtree, all of
;;; which have the same form), recurse on a random subtree, along with a
;;; reduced value of n. The base case is when n is zero or the function hits a leaf.
;;;
;;; Additionally, **replace-subtree** uses concat to reconstruct
;;; the tree on its way back up the stack.

(defn rand-subtree
  "Return a random subtree of a list. Takes an optional second parameter that limits
   the depth to go before selecting a crossover point."
  ([tree]
    (rand-subtree tree (rand-int (+ 1 (max-tree-height tree)))))
  ([tree n]
    (if (or (zero? n) (not (seq? tree))) tree
      (recur (rand-nth (rest tree))
             (rand-int n)))))

(defn replace-subtree
  "Replace a random subtree with a given subtree. Takes an optional second parameter
   that limits the depth to go before selecting a crossover point."
  ([tree sub] 
    (replace-subtree tree sub (rand-int (+ 1 (max-tree-height tree)))))
  ([tree sub n]
    (if (or (zero? n) (not (seq? tree))) sub
      (let [r (+ 1 (rand-int (count (rest tree))))] 
        (concat (take r tree)
                (list (replace-subtree
                        (nth tree r) sub
                        (rand-int n)))
                (nthrest tree (+ r 1)))))))

(defn truncate
  "Prevent trees from growing too big by lifting a subtree if the tree height is
   greater than the max tree height."
  [tree height] 
  (if (< (max-tree-height tree) height) 
    (rand-subtree tree) 
    tree))

;;; ### Mutation, crossover, and selection
;;;
;;; With rand-subtree and replace-subtree out of the way, the rest of the
;;; single-generation pass is pretty simple. Mutation and crossover both
;;; can easily be written in terms of rand-subtree and replace-subtree.
;;;
;;; **Mutation** takes a tree and (occasionally) randomly changes part of it.
;;; The idea, like the rest of the fundamental aspects of genetic algorithms,
;;; is taken from nature; when DNA is copied, there is a slight chance of
;;; "mistakes" being introduced in the copy. This can lead to beneficial
;;; changes and increases genetic diversity.


(defn mutate-tree
  "Mutate a tree. Mutation takes one of three forms, chosen randomly: replace a random
   subtree with a newly generated tree, replace a random subtree with a terminal, or
   \"lift\" a random subtree to replace the root. The function takes a tree, mutation rate,
   a mutation depth (max size of new subtrees), terminals, and functions."
  [tree mutation-probability mutation-depth terminals functions]
  (if (flip mutation-probability)
    (let [coin (rand)] ;; random number between 0 and 1
      (cond (< coin 0.33) 
            (replace-subtree tree (create-tree mutation-depth terminals functions :grow))
            (< coin 0.66)
            (replace-subtree tree (rand-nth terminals))
            :else (rand-subtree tree)))
    tree)) 

(defn mutate-population
  "Apply mutation to every tree in a population. Similar arguments to mutate-tree."
  [population mutation-probability mutation-depth terminals functions]
  (map #(mutate-tree % mutation-probability mutation-depth terminals functions) population))

;;; **Crossover** is the process of combining two parents to make a child.
;;; It involves copying the genetic material (in this case, lisp code) from
;;; the two parents, combining them, and returning the result of the combination.

(defn crossover
  "The crossover function is simple to define in terms of replace-subtree
   and rand-subtree. Basically, crossing over two trees involves selecting a
   random subtree from one tree, and placing it randomly in the other tree."
  [tree1 tree2] (replace-subtree tree1 (rand-subtree tree2)))

;;; Now it's time to get into functions that operate on populations. 
;;; 
;;; **Selection** is the process in which more fit individuals are "selected," or
;;; more likely to breed (be involved in a crossover), while less fit individuals
;;; are less likely to breed.
;;;
;;; To carry out the selection phase, it's necessary to determine how fit the
;;; individuals are. The following functions use the training data to give the
;;; individual trees a grade, which is the sum of the error. Lower grades are
;;; better. Then, in the selection phase, individuals with lower error are more
;;; likely to be selected for crossover, and thus pass on their genetic
;;; material to the next generation.

(defn fitness-zip
  "Compute the fitness of all the trees in the population, and map the trees to their population in a zipmap."
  [population fitness]
  (seq (zipmap population (map fitness population))))

(defn tournament-selection
  "Use tournament selection to create a new generation. In each tournament the two best individuals
   in the randomly chosen group will reproduce to form a child tree. A larger tournament size
   will lead to more selective pressure. The function takes a population, tournament size,
   parameter list, fitness function, and test cases, and returns a new population."
  [population tournament-size fitness-zip max-depth]
  (let [child 
        (fn [] 
          (let [selected (map first (sort-by second 
                                             (repeatedly tournament-size 
                                                         #(rand-nth fitness-zip))))]
            (truncate (crossover (first selected) 
                                 (second selected)) 
                      max-depth)))]
    (repeatedly (count population) child)))

(defn get-best-fitness
  "Takes the fitness zip and finds the best in the population."
  [fitness-zip]
  (first (sort-by second fitness-zip)))

(defn elitism
  "Put the best-seen individual back in the population."
  [population best]
  (conj (rest population) best))


;;; ### Putting it together
;;;
;;; This takes care of all the steps necessary to complete one generation of the algorithm.
;;; The process can be extended to multiple generations with a simple tail recursive
;;; function.
;;;
;;; There are some extra considerations here. The function should:
;;;
;;;  * stop when a perfect individual has been found, meaning fitness is zero
;;;  * be resumable, meaning the search can halt, returning information, and that information
;;;    can be passed back in to start the search at the same place


(defn generations
  "Runs n generations of a population, and returns the population and the best tree in the form [population best-tree fitness]."
  [n population tournament-size mutation-probability mutation-depth max-depth terminals functions fitness]
  (let [computed-fitness (fitness-zip population fitness)
        [best-tree best-fit] (get-best-fitness computed-fitness)]
    (if (or (zero? n) (zero? best-fit)) ;; terminating condition
      [population best-tree best-fit] ;; return
      (recur (- n 1)                  ;; else recurse
             (-> population
                 (tournament-selection tournament-size computed-fitness max-depth)
                 (mutate-population mutation-probability mutation-depth terminals functions)
                 (elitism best-tree))
             tournament-size mutation-probability mutation-depth max-depth terminals functions fitness))))
 
;;; ### Islands
;;;
;;; The above code works for running generations of a single population. The concept of islands is
;;; to have multiple separated populations evolving in parallel, and cross over between them.

(defn create-islands
  "Create a list of populations (islands)."
  [num-islands population-size mutation-depth terminals functions]
  (repeatedly num-islands #(create-population population-size mutation-depth terminals functions)))

(defn island-crossover
  "Individuals migrate between islands."
  [islands]
  (let [cross (map rand-nth islands)]
    (map (fn [[island selected]] (conj (rest (shuffle island)) selected))
         (zipmap islands cross))))

(defn island-generations
  "Run generations on all the islands and cross over between them. See the documentation for the generations function.
   Returns with the form [island best-tree best-fit]."
  [n1 n2 islands tournament-size mutation-probability mutation-depth max-depth terminals functions fitness report] 
  (let [islands-fit (map #(generations n2 % tournament-size mutation-probability
                                        mutation-depth max-depth terminals functions fitness) islands)
        islands (map first islands-fit)
        [_ best-tree best-fit] (first (sort-by #(nth % 2) islands-fit))]
    (if (or (zero? n1) (zero? best-fit))
      [islands best-tree best-fit]
      (do (report best-tree best-fit)
        (recur (- n1 1) n2 islands tournament-size mutation-probability
               mutation-depth max-depth terminals functions fitness report)))))

(defn run-genetic-programming
  "This is the entry function. Call this with a map of the parameters to run the genetic programming algorithm."
  [{:keys [iterations migrations num-islands population-size tournament-size mutation-probability
           mutation-depth max-depth terminals functions fitness report]}]
  (island-generations migrations iterations 
                      (create-islands num-islands population-size mutation-depth terminals functions)
                      tournament-size mutation-probability mutation-depth max-depth terminals functions fitness report))
        
;;; And that's it! For the core of the library, anyway.
