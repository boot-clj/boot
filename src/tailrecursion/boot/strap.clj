(ns tailrecursion.boot.strap)

(defn- latest-core-version [add-deps!]
  (add-deps! '[[tailrecursion/ancient-clj "0.1.7"]])
  (require 'ancient-clj.core)
  (let [latest-version-string! (resolve 'ancient-clj.core/latest-version-string!)]
    (latest-version-string! {:snapshots? false} 'tailrecursion/boot.core)))

(defn emit [add-deps!]
  (printf
    "#!/usr/bin/env boot\n\n#tailrecursion.boot.core/version %s\n"
    (pr-str (latest-core-version add-deps!))))
