.PHONY: help deps install deploy test clean

SHELL       := /bin/bash
export PATH := bin:$(PATH)
export LEIN_SNAPSHOTS_IN_RELEASE := yes

version      = $(shell grep ^version version.properties |sed 's/.*=//')
verfile      = version.properties
distjar      = $(PWD)/bin/boot.jar
bootjar      = boot/boot/target/boot-$(version).jar
podjar       = boot/pod/target/pod-$(version).jar
aetherjar    = boot/aether/target/aether-$(version).jar
aetheruber   = aether.uber.jar
workerjar    = boot/worker/target/worker-$(version).jar
corejar      = boot/core/target/core-$(version).jar
basejar      = boot/base/target/base-$(version).jar
baseuber     = boot/base/target/base-$(version)-uber.jar
alljars      = $(podjar) $(aetherjar) $(workerjar) $(corejar) $(baseuber) $(bootjar)
java_version = $(shell java -version 2>&1 | awk -F '"' '/version/ {print $$2}' |awk -F. '{print $$1 "." $$2}')

help:
	@echo "version =" $(version)
	@echo "Usage: make {help|deps|install|deploy|test|clean}" 1>&2 && false

clean:
	(cd boot/base && mvn -q clean && rm -f src/main/resources/$(aetheruber))
	(cd boot/core && lein clean)
	(cd boot/aether && lein clean)
	(cd boot/pod && lein clean)
	(cd boot/worker && lein clean)
	(rm -Rfv bin)
	(rm -fv .installed .deployed .tested)

mkdirs:
	mkdir -p bin

bin/lein: mkdirs
	wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein -O bin/lein
	chmod 755 bin/lein

bin/boot: mkdirs
	curl -fsSLo bin/boot https://github.com/boot-clj/boot-bin/releases/download/latest/boot.sh
	chmod 755 bin/boot

deps: bin/lein bin/boot

$(bootjar): $(verfile) boot/boot/project.clj
	(cd boot/boot && lein install)

boot/base/pom.xml: $(verfile) boot/base/pom.in.xml
	(cd boot/base && cat pom.in.xml |sed 's/__VERSION__/$(version)/' > pom.xml)

$(basejar): boot/base/pom.xml $(shell find boot/base/src/main/java)
	(cd boot/base && mvn -q install)

$(podjar): $(verfile) boot/pod/project.clj $(shell find boot/pod/src)
	(cd boot/pod && lein install)

$(aetherjar): $(verfile) boot/aether/project.clj $(podjar) $(shell find boot/aether/src)
	(cd boot/aether && lein install && lein uberjar && \
		mkdir -p ../base/src/main/resources && \
		cp target/aether-$(version)-standalone.jar ../base/src/main/resources/$(aetheruber))

$(workerjar): $(verfile) boot/worker/project.clj $(shell find boot/worker/src)
	(cd boot/worker && lein install)

$(corejar): $(verfile) boot/core/project.clj $(shell find boot/core/src)
	(cd boot/core && lein install)

$(baseuber): boot/base/pom.xml $(shell find boot/base/src/main)
	(cd boot/base && mvn -q assembly:assembly -DdescriptorId=jar-with-dependencies)
	(cd boot/base && mv target/base-$(version)-jar-with-dependencies.jar target/base-$(version)-uber.jar)

.installed: mkdirs $(basejar) $(alljars)
	cp $(baseuber) $(distjar)
	# FIXME: this is just for testing -- remove before release
	mkdir -p $$HOME/.boot/cache/bin/$(version)
	cp $(baseuber) $$HOME/.boot/cache/bin/$(version)/boot.jar
	# End testing code -- cut above.
	date > .installed

install: .installed

.deployed: .installed
	@echo -e "\033[0;33m<< Java version: $(java_version) >>\033[0m"
	@[ "$(java_version)" == "1.7" ] \
		|| (echo -e "\033[0;31mYou must build with Java version 1.7 only.\033[0m" && false)
	(cd boot/base   && lein deploy clojars boot/base $(version) target/base-$(version)-uber.jar pom.xml)
	(cd boot/pod    && lein deploy clojars)
	(cd boot/aether && lein deploy clojars)
	(cd boot/worker && lein deploy clojars)
	(cd boot/core   && lein deploy clojars)
	(cd boot/boot   && lein deploy clojars)
	date > .deployed

deploy: .deployed

.tested: bin/boot
	(export BOOT_VERSION=$(version) && export BOOT_EMIT_TARGET=no && cd boot/core && ../../bin/boot -x test)
	(export BOOT_VERSION=$(version) && export BOOT_EMIT_TARGET=no && cd boot/worker && ../../bin/boot -x test)
	(cd boot/pod && lein test)
	date > .tested

test: .installed .tested
