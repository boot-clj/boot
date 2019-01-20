# base boot image
FROM boot-clj/tools:latest as boot

# clojure arguments
ARG CLOJURE=1.10
ENV VERSION=3.0.0-SNAPSHOT
#

# copy src
COPY . /usr/boot-clj/
#

# boot root
WORKDIR /usr/boot-clj

RUN echo VERSION=${VERSION} >> version.properties
#

# boot base
WORKDIR /usr/boot-clj/boot/base

RUN echo VERSION=${VERSION} >> version.properties

#RUN clojure -A:${CLOJURE}:test -Spom

#RUN cat pom.in.xml | sed 's/__VERSION__/$(version)/' > pom.xml

RUN mvn install
#

# boot pod
WORKDIR /usr/boot-clj/boot/pod

RUN echo VERSION=${VERSION} >> version.properties

#RUN clojure -A:${CLOJURE} -Spom

RUN lein install
#

# boot aether
WORKDIR /usr/boot-clj/boot/aether

RUN echo VERSION=${VERSION} >> version.properties

#RUN clojure -A:${CLOJURE} -Spom

RUN lein install

RUN lein uberjar

RUN cp target/aether-${VERSION}-standalone.jar ../base/src/main/resources/aether.uber.jar
#

# boot worker
WORKDIR /usr/boot-clj/boot/worker

RUN echo VERSION=${VERSION} >> version.properties

#RUN clojure -A:${CLOJURE} -Spom

RUN lein install
#

# boot core
WORKDIR /usr/boot-clj/boot/core

RUN echo VERSION=${VERSION} >> version.properties

#RUN clojure -A:${CLOJURE} -Spom

RUN lein install
#

# boot tasks
WORKDIR /usr/boot-clj/boot/tasks

RUN echo VERSION=${VERSION} >> version.properties

#RUN clojure -A:${CLOJURE} -Spom

RUN lein install
#

# boot uberjar
WORKDIR /usr/boot-clj/boot/base

RUN mvn  assembly:assembly -DdescriptorId=jar-with-dependencies
#

# install boot locally
RUN mkdir -p ~/.boot/cache/bin/${VERSION}

RUN cp target/base-${VERSION}.jar ~/.boot/cache/bin/${VERSION}/boot.jar
#

# dont run as root
#WORKDIR /usr/boot-clj

#RUN addgroup -g 1000 -S boot

#RUN adduser -u 1000 -S boot -G boot

#USER boot
#
