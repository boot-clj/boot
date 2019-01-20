# base boot image
FROM boot-clj/tools:latest as boot

ARG VERSION=3.0.0-SNAPSHOT

RUN mkdir -p /usr/boot-clj

COPY . /usr/boot-clj

# base
WORKDIR /usr/boot-clj/boot/base

RUN mvn -q install

# pod
WORKDIR /usr/boot-clj/boot/pod

RUN lein install

# aether
WORKDIR /usr/boot-clj/boot/aether

RUN lein install

RUN lein uberjar

RUN cp target/aether-3.0.0-SNAPSHOT-standalone.jar /usr/boot-clj/boot/base/src/main/resources/aether.uber.jar

# worker
WORKDIR /usr/boot-clj/boot/worker

RUN lein install

# core
WORKDIR /usr/boot-clj/boot/core

RUN lein install

# tasks
WORKDIR /usr/boot-clj/boot/tasks

RUN lein install

# base uber
WORKDIR /usr/boot-clj/boot/base

RUN mvn -q assembly:assembly -DdescriptorId=jar-with-dependencies

# boot jar
WORKDIR /usr/boot-clj/boot/boot

RUN lein install

# final artifact
WORKDIR /usr/boot-clj/

RUN mkdir -p ~/.boot/cache/bin/3.0.0-SNAPSHOT

RUN cp boot/base/target/base-3.0.0-SNAPSHOT-jar-with-dependencies.jar ~/.boot/cache/bin/3.0.0-SNAPSHOT/boot.jar
