FROM openjdk:8-alpine AS openjdk

RUN apk add wget git make bash

FROM openjdk AS boot

RUN wget -O /usr/local/bin/boot https://github.com/boot-clj/boot-bin/releases/download/latest/boot.sh

RUN chmod 755 /usr/local/bin/boot

COPY . /usr/boot-clj/

WORKDIR /usr/boot-clj

RUN make deps

RUN make install
