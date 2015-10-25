.PHONY: build clean

build: target/loader.jar

clean:
	rm -rf target

target/boot/Loader.class: src/boot/Loader.java
	mkdir -p target/boot
	javac -d target $<

target/loader.jar: target/boot/Loader.class
	cp tag-release.properties target/boot
	(cd target && jar cef boot.Loader loader.jar boot)
