# base openjdk image
FROM boot-clj/openjdk:8-alpine

# clojure version
ARG CLOJURE_VERSION=1.10.0.411

# install clojure
RUN wget -O install-cljs.sh https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh

RUN chmod +x install-cljs.sh

RUN ./install-cljs.sh
#
