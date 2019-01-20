# base openjdk image
FROM openjdk:8-alpine

# install build tools
RUN apk add build-base curl maven bash
#
