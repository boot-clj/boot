.PHONY: help install deploy test clean basejar

version    = $(shell grep ^version version.properties |sed 's/.*=//')
bootbin    = $(PWD)/bin/boot.sh
bootexe    = $(PWD)/bin/boot.exe
bootjarurl = https://github.com/tailrecursion/boot/releases/download/p1/boot
bootjar    = boot/boot/target/boot-$(version).jar
podjar     = boot/pod/target/pod-$(version).jar
aetherjar  = boot/aether/target/aether-$(version).jar
aetheruber = aether.uber.jar
workerjar  = boot/worker/target/worker-$(version).jar
corejar    = boot/core/target/core-$(version).jar
basejar    = boot/base/target/base-$(version).jar
baseuber   = boot/base/target/base-$(version)-jar-with-dependencies.jar
alljars    = $(podjar) $(aetherjar) $(workerjar) $(corejar) $(baseuber) $(bootjar)

help:
	@echo "version =" $(version)
	@echo "Usage: make {help|install|deploy|test}" 1>&2 && false

clean:
	(cd boot/base && mvn -q clean)
	(cd boot/core && lein clean)
	(cd boot/aether && lein clean)
	(cd boot/pod && lein clean)
	(cd boot/worker && lein clean)

bin/lein:
	mkdir -p bin
	wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein -O bin/lein
	chmod 755 bin/lein

$(bootjar): boot/boot/project.clj
	(cd boot/boot && lein install)

$(basejar): boot/base/pom.in.xml $(shell find boot/base/src/main/java)
	(cd boot/base && cat pom.in.xml |sed 's/__VERSION__/$(version)/' > pom.xml && mvn -q install)

basejar: $(basejar)

$(podjar): boot/pod/project.clj $(shell find boot/pod/src)
	(cd boot/pod && lein install)

$(aetherjar): boot/aether/project.clj $(podjar) $(shell find boot/aether/src)
	(cd boot/aether && lein install && lein uberjar && \
		mkdir -p ../base/src/main/resources && \
	 	cp target/aether-$(version)-standalone.jar ../base/src/main/resources/$(aetheruber))

$(workerjar): boot/worker/project.clj $(shell find boot/worker/src)
	(cd boot/worker && lein install)

$(corejar): boot/core/project.clj $(shell find boot/core/src)
	(cd boot/core && lein install)

$(baseuber): $(basejar) $(shell find boot/base/src/main)
	(cd boot/base && mvn -q assembly:assembly -DdescriptorId=jar-with-dependencies)

$(bootbin): $(baseuber)
	mkdir -p bin
	cat head.sh $(baseuber) > $(bootbin)
	chmod 0755 $(bootbin)
	@echo "*** Created boot executable: $(bootbin) ***"

$(bootexe): $(baseuber)
	@if [ -z $$RUNNING_IN_CI ] && which launch4j; then \
		launch4j launch4j-config.xml; \
		echo "*** Created boot executable: $(bootexe) ***"; \
	else true; fi

.installed: $(basejar) $(alljars) $(bootbin) $(bootexe)
	date > .installed

install: .installed

.deployed: .installed
	(cd boot/base   && lein deploy clojars boot/base $(version) target/base-$(version).jar pom.xml)
	(cd boot/pod    && lein deploy clojars)
	(cd boot/aether && lein deploy clojars)
	(cd boot/worker && lein deploy clojars)
	(cd boot/core   && lein deploy clojars)
	(cd boot/boot   && lein deploy clojars)
	date > .deployed

deploy: .deployed

test:
	rm -rf test
	mkdir -p test/1/boot-project/src
	echo "hi there" > test/1/boot-project/src/hello.txt
	(cd test/1/boot-project \
		&& $(bootbin) -d org.clojure/clojure:1.6.0 -s src -- speak -t ordinance -- pom -p boot-project -v 0.1.0 -- jar -M Foo=bar -- uberjar -- war)# \
		&& jar tf target/boot-project-0.1.0.jar | grep '^META-INF/MANIFEST.MF$$' \
		&& unzip -p target/boot-project-0.1.0.jar META-INF/MANIFEST.MF | grep '^Foo: bar' \
		&& echo TEST 1 OK) \
		|| (echo TEST 1 FAIL 1>&2; false)
