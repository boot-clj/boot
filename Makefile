
help:
	@echo
	@echo 'Usage: make {boot|help}'
	@echo
	@echo 'Targets:'
	@echo '  boot         Create executable boot jar file.'
	@echo

clean:
	rm -f boot
	rm -rf resources/*
	(cd boot-classloader; lein clean)
	lein clean

build: clean
	mkdir -p resources
	(cd boot-classloader; lein uberjar)
	cp boot-classloader/target/boot-classloader*-standalone.jar resources/boot-classloader.jar
	lein uberjar

boot: build
	echo '#!/usr/bin/env bash' > boot
	echo 'java $$JVM_OPTS -jar $$0 "$$@"' >> boot
	echo 'exit' >> boot
	cat target/boot*-standalone.jar >> boot
	chmod 0755 boot
	@echo "*** Done. Created boot executable: ./boot ***"
