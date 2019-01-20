# base boot image
FROM boot-clj/clojure:1.10.0

# install leiningen
RUN wget -O /usr/local/bin/lein https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein

RUN chmod 755 /usr/local/bin/lein
#

# install latest boot
RUN wget -O /usr/local/bin/boot https://github.com/boot-clj/boot-bin/releases/download/latest/boot.sh

RUN chmod 755 /usr/local/bin/boot
#
