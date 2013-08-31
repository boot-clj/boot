
prepare:
	lein clean

build: prepare
	lein uberjar

boot: build
	rm boot
	echo '#!/usr/bin/env bash' > boot
	echo 'java -jar "$$0" "$$@"' >> boot
	echo 'exit' >> boot
	cat target/boot*-standalone.jar >> boot
	chmod 0755 boot
	@echo "*** Done. Copy ./boot to a directory in your PATH. ***"
