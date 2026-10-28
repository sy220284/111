# Optional independently implemented Linux N-API backends. Original binaries remain untouched.
CC ?= cc
NODE_INCLUDE ?= $(shell node -p 'require("node:path").resolve(require("node:path").dirname(process.execPath),"../include/node")')
BUILD ?= ../../.generated/native-linux
CPPFLAGS += -I$(NODE_INCLUDE)
CFLAGS ?= -O2 -g -std=c11 -fPIC -Wall -Wextra -Werror
LDFLAGS ?= -shared -pthread
.PHONY: all clean
all: $(BUILD)/bun-ant-linux.node $(BUILD)/clipboard-napi.node $(BUILD)/audio-capture.node
$(BUILD):
	mkdir -p "$@"
$(BUILD)/bun-ant-linux.node: bun_ant_linux_napi.c | $(BUILD)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) $< -o "$@"
$(BUILD)/clipboard-napi.node: clipboard_napi.c | $(BUILD)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) $< -o "$@" -lX11
$(BUILD)/audio-capture.node: audio_capture_napi.c | $(BUILD)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) $< -o "$@" -l:libasound.so.2
clean:
	rm -rf "$(BUILD)"