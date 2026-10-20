SHELL := /bin/bash

SDK_ROOT ?= $(HOME)/Library/Android/sdk
NDK_VERSION ?= 29.0.14206865
NDK_ROOT ?= $(SDK_ROOT)/ndk/$(NDK_VERSION)
NDK_HOST ?= darwin-x86_64
NDK_BIN := $(NDK_ROOT)/toolchains/llvm/prebuilt/$(NDK_HOST)/bin
ANDROID_API ?= 26

GO ?= go
GRADLE ?= ./gradlew
CottenDns_DIR := third_party/CottenDns
CottenDns_CMD := ./cmd/client
CottenDns_BUILD_DIR := $(CottenDns_DIR)/build/android
JNI_LIBS_DIR := app/src/main/jniLibs
GO_CACHE := $(CottenDns_DIR)/.gocache
CottenDns_SOURCE_HASH = $(shell $(GO) run scripts/cottendns-build-manifest.go source)
CottenDns_LDFLAGS = -s -w -linkmode external -extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" -X cottendns-go/internal/version.BuildVersion=source-$(CottenDns_SOURCE_HASH)

PhantomDns_DIR := third_party/PhantomDns
PhantomDns_CMD := ./cmd/client
PhantomDns_BUILD_DIR := $(PhantomDns_DIR)/build/android
PhantomDns_GO_CACHE := $(PhantomDns_DIR)/.gocache
PhantomDns_SOURCE_HASH = $(shell $(GO) run scripts/cottendns-build-manifest.go source phantom)
PhantomDns_LDFLAGS = -s -w -linkmode external -extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" -X phantomdns-go/internal/version.BuildVersion=source-$(PhantomDns_SOURCE_HASH)

.PHONY: all debug CottenDns CottenDns-arm64 CottenDns-armv7 CottenDns-x86_64 CottenDns-x86 clean clean-CottenDns clean-app check-ndk debug-outputs
.PHONY: PhantomDns PhantomDns-arm64 PhantomDns-armv7 PhantomDns-x86_64 PhantomDns-x86 vendor-PhantomDns clean-PhantomDns

all: debug

debug: CottenDns PhantomDns
	$(GRADLE) :app:assembleDebug

CottenDns: CottenDns-arm64 CottenDns-armv7 CottenDns-x86_64 CottenDns-x86
	$(GO) run scripts/cottendns-build-manifest.go record

check-ndk:
	@test -x "$(NDK_BIN)/aarch64-linux-android$(ANDROID_API)-clang" || (echo "Android NDK not found at $(NDK_ROOT). Install NDK $(NDK_VERSION) or set NDK_ROOT=/path/to/ndk."; exit 1)

CottenDns-arm64: check-ndk
	@mkdir -p "$(CottenDns_BUILD_DIR)/arm64-v8a" "$(JNI_LIBS_DIR)/arm64-v8a" "$(GO_CACHE)"
	cd "$(CottenDns_DIR)" && GOCACHE="$$PWD/.gocache" CGO_ENABLED=1 CC="$(NDK_BIN)/aarch64-linux-android$(ANDROID_API)-clang" GOOS=android GOARCH=arm64 $(GO) build -trimpath -ldflags='$(CottenDns_LDFLAGS)' -o "build/android/arm64-v8a/CottenDns-client" "$(CottenDns_CMD)"
	cp "$(CottenDns_BUILD_DIR)/arm64-v8a/CottenDns-client" "$(JNI_LIBS_DIR)/arm64-v8a/libcottendns_client.so"
	chmod 755 "$(CottenDns_BUILD_DIR)/arm64-v8a/CottenDns-client" "$(JNI_LIBS_DIR)/arm64-v8a/libcottendns_client.so"

CottenDns-armv7: check-ndk
	@mkdir -p "$(CottenDns_BUILD_DIR)/armeabi-v7a" "$(JNI_LIBS_DIR)/armeabi-v7a" "$(GO_CACHE)"
	cd "$(CottenDns_DIR)" && GOCACHE="$$PWD/.gocache" CGO_ENABLED=1 CC="$(NDK_BIN)/armv7a-linux-androideabi$(ANDROID_API)-clang" GOOS=android GOARCH=arm GOARM=7 $(GO) build -trimpath -ldflags='$(CottenDns_LDFLAGS)' -o "build/android/armeabi-v7a/CottenDns-client" "$(CottenDns_CMD)"
	cp "$(CottenDns_BUILD_DIR)/armeabi-v7a/CottenDns-client" "$(JNI_LIBS_DIR)/armeabi-v7a/libcottendns_client.so"
	chmod 755 "$(CottenDns_BUILD_DIR)/armeabi-v7a/CottenDns-client" "$(JNI_LIBS_DIR)/armeabi-v7a/libcottendns_client.so"

CottenDns-x86_64: check-ndk
	@mkdir -p "$(CottenDns_BUILD_DIR)/x86_64" "$(JNI_LIBS_DIR)/x86_64" "$(GO_CACHE)"
	cd "$(CottenDns_DIR)" && GOCACHE="$$PWD/.gocache" CGO_ENABLED=1 CC="$(NDK_BIN)/x86_64-linux-android$(ANDROID_API)-clang" GOOS=android GOARCH=amd64 $(GO) build -trimpath -ldflags='$(CottenDns_LDFLAGS)' -o "build/android/x86_64/CottenDns-client" "$(CottenDns_CMD)"
	cp "$(CottenDns_BUILD_DIR)/x86_64/CottenDns-client" "$(JNI_LIBS_DIR)/x86_64/libcottendns_client.so"
	chmod 755 "$(CottenDns_BUILD_DIR)/x86_64/CottenDns-client" "$(JNI_LIBS_DIR)/x86_64/libcottendns_client.so"

CottenDns-x86: check-ndk
	@mkdir -p "$(CottenDns_BUILD_DIR)/x86" "$(JNI_LIBS_DIR)/x86" "$(GO_CACHE)"
	cd "$(CottenDns_DIR)" && GOCACHE="$$PWD/.gocache" CGO_ENABLED=1 CC="$(NDK_BIN)/i686-linux-android$(ANDROID_API)-clang" GOOS=android GOARCH=386 $(GO) build -trimpath -ldflags='$(CottenDns_LDFLAGS)' -o "build/android/x86/CottenDns-client" "$(CottenDns_CMD)"
	cp "$(CottenDns_BUILD_DIR)/x86/CottenDns-client" "$(JNI_LIBS_DIR)/x86/libcottendns_client.so"
	chmod 755 "$(CottenDns_BUILD_DIR)/x86/CottenDns-client" "$(JNI_LIBS_DIR)/x86/libcottendns_client.so"

debug-outputs:
	@find app/build/outputs/apk/debug -type f -name '*.apk' -print | sort

# --- PhantomDns second engine -------------------------------------------------
# Vendor the source first (checks it into third_party/PhantomDns), then build the
# per-ABI client binaries into jniLibs as libphantomdns_client.so. Same NDK/CGO
# recipe as CottenDns; the two engines package side by side.

vendor-PhantomDns:
	scripts/vendor-phantomdns.sh

PhantomDns: PhantomDns-arm64 PhantomDns-armv7 PhantomDns-x86_64 PhantomDns-x86
	$(GO) run scripts/cottendns-build-manifest.go record phantom

PhantomDns-arm64: check-ndk
	@mkdir -p "$(PhantomDns_BUILD_DIR)/arm64-v8a" "$(JNI_LIBS_DIR)/arm64-v8a" "$(PhantomDns_GO_CACHE)"
	cd "$(PhantomDns_DIR)" && GOCACHE="$$PWD/.gocache" CGO_ENABLED=1 CC="$(NDK_BIN)/aarch64-linux-android$(ANDROID_API)-clang" GOOS=android GOARCH=arm64 $(GO) build -trimpath -ldflags='$(PhantomDns_LDFLAGS)' -o "build/android/arm64-v8a/PhantomDns-client" "$(PhantomDns_CMD)"
	cp "$(PhantomDns_BUILD_DIR)/arm64-v8a/PhantomDns-client" "$(JNI_LIBS_DIR)/arm64-v8a/libphantomdns_client.so"
	chmod 755 "$(PhantomDns_BUILD_DIR)/arm64-v8a/PhantomDns-client" "$(JNI_LIBS_DIR)/arm64-v8a/libphantomdns_client.so"

PhantomDns-armv7: check-ndk
	@mkdir -p "$(PhantomDns_BUILD_DIR)/armeabi-v7a" "$(JNI_LIBS_DIR)/armeabi-v7a" "$(PhantomDns_GO_CACHE)"
	cd "$(PhantomDns_DIR)" && GOCACHE="$$PWD/.gocache" CGO_ENABLED=1 CC="$(NDK_BIN)/armv7a-linux-androideabi$(ANDROID_API)-clang" GOOS=android GOARCH=arm GOARM=7 $(GO) build -trimpath -ldflags='$(PhantomDns_LDFLAGS)' -o "build/android/armeabi-v7a/PhantomDns-client" "$(PhantomDns_CMD)"
	cp "$(PhantomDns_BUILD_DIR)/armeabi-v7a/PhantomDns-client" "$(JNI_LIBS_DIR)/armeabi-v7a/libphantomdns_client.so"
	chmod 755 "$(PhantomDns_BUILD_DIR)/armeabi-v7a/PhantomDns-client" "$(JNI_LIBS_DIR)/armeabi-v7a/libphantomdns_client.so"

PhantomDns-x86_64: check-ndk
	@mkdir -p "$(PhantomDns_BUILD_DIR)/x86_64" "$(JNI_LIBS_DIR)/x86_64" "$(PhantomDns_GO_CACHE)"
	cd "$(PhantomDns_DIR)" && GOCACHE="$$PWD/.gocache" CGO_ENABLED=1 CC="$(NDK_BIN)/x86_64-linux-android$(ANDROID_API)-clang" GOOS=android GOARCH=amd64 $(GO) build -trimpath -ldflags='$(PhantomDns_LDFLAGS)' -o "build/android/x86_64/PhantomDns-client" "$(PhantomDns_CMD)"
	cp "$(PhantomDns_BUILD_DIR)/x86_64/PhantomDns-client" "$(JNI_LIBS_DIR)/x86_64/libphantomdns_client.so"
	chmod 755 "$(PhantomDns_BUILD_DIR)/x86_64/PhantomDns-client" "$(JNI_LIBS_DIR)/x86_64/libphantomdns_client.so"

PhantomDns-x86: check-ndk
	@mkdir -p "$(PhantomDns_BUILD_DIR)/x86" "$(JNI_LIBS_DIR)/x86" "$(PhantomDns_GO_CACHE)"
	cd "$(PhantomDns_DIR)" && GOCACHE="$$PWD/.gocache" CGO_ENABLED=1 CC="$(NDK_BIN)/i686-linux-android$(ANDROID_API)-clang" GOOS=android GOARCH=386 $(GO) build -trimpath -ldflags='$(PhantomDns_LDFLAGS)' -o "build/android/x86/PhantomDns-client" "$(PhantomDns_CMD)"
	cp "$(PhantomDns_BUILD_DIR)/x86/PhantomDns-client" "$(JNI_LIBS_DIR)/x86/libphantomdns_client.so"
	chmod 755 "$(PhantomDns_BUILD_DIR)/x86/PhantomDns-client" "$(JNI_LIBS_DIR)/x86/libphantomdns_client.so"

clean: clean-app clean-CottenDns clean-PhantomDns

clean-app:
	$(GRADLE) :app:clean

clean-CottenDns:
	rm -rf "$(CottenDns_BUILD_DIR)" "$(GO_CACHE)"

clean-PhantomDns:
	rm -rf "$(PhantomDns_BUILD_DIR)" "$(PhantomDns_GO_CACHE)"
