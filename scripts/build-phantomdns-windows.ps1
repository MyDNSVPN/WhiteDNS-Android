param([string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
$ndkBin = "$Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin"
if (!(Test-Path -LiteralPath "$ndkBin/clang.exe")) { throw 'Install Android NDK 29.0.14206865.' }
$projectRoot = (Resolve-Path "$PSScriptRoot/..").Path
$manifestTool = "$projectRoot/scripts/cottendns-build-manifest.go"
Push-Location $projectRoot
try { $coreSourceHash = (go run $manifestTool source phantom).Trim(); if ($LASTEXITCODE) { throw 'Core source hashing failed' } } finally { Pop-Location }
Push-Location "$projectRoot/third_party/PhantomDns"
try {
    $env:CGO_ENABLED = '1'
    $env:GOOS = 'android'
    $env:GOARM = '7'
    $env:GOCACHE = "$projectRoot/.gradle/phantomdns-gocache"
    foreach ($target in @(
        @{arch='arm64'; abi='arm64-v8a'; triple='aarch64-linux-android26'},
        @{arch='arm'; abi='armeabi-v7a'; triple='armv7a-linux-androideabi26'},
        @{arch='amd64'; abi='x86_64'; triple='x86_64-linux-android26'},
        @{arch='386'; abi='x86'; triple='i686-linux-android26'}
    )) {
        $env:GOARCH = $target.arch
        $env:CC = "$ndkBin/clang.exe --target=$($target.triple)"
        $output = "$projectRoot/app/src/main/jniLibs/$($target.abi)/libphantomdns_client.so"
        go build -buildvcs=false -trimpath "-ldflags=-s -w -linkmode external -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384 -X phantomdns-go/internal/version.BuildVersion=source-$coreSourceHash" -o $output ./cmd/client
        if ($LASTEXITCODE) { throw "PhantomDNS build failed: $($target.abi)" }
        Write-Output "Built $($target.abi)"
    }
} finally { Pop-Location }
$env:GOOS = (go env GOHOSTOS).Trim()
$env:GOARCH = (go env GOHOSTARCH).Trim()
$env:CGO_ENABLED = '0'
Push-Location $projectRoot
try { go run $manifestTool record phantom; if ($LASTEXITCODE) { throw 'Core build manifest verification failed' } } finally { Pop-Location }
