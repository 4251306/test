CC ?= gcc
CFLAGS ?= -Wall -Wextra -Werror -std=c11
SRC = common/mix.c common/protocol.c common/ultranet.c common/host_test.c
OUT = build/host_test

.PHONY: test clean

test: $(OUT)
	./$(OUT)

$(OUT): $(SRC) common/mix.h common/protocol.h common/ultranet.h
	mkdir -p build
	$(CC) $(CFLAGS) -Icommon -o $(OUT) $(SRC)

clean:
	rm -rf build
