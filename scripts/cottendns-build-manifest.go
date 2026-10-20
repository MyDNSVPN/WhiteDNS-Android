// Run from the Android repository root. Uses only the Go standard library.
package main

import (
	"bytes"
	"crypto/sha256"
	"debug/elf"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
)

var manifestPath = "app/cottendns-native-build.json"
var corePath = "third_party/CottenDns"
var coreName = "CottenDNS"
var binaryName = "libcottendns_client.so"

type receipt struct {
	Version      int               `json:"version"`
	SourceSHA256 string            `json:"source_sha256"`
	Binaries     map[string]string `json:"binaries"`
}

var targets = map[string]struct {
	arch    string
	machine elf.Machine
}{
	"arm64-v8a": {"arm64", elf.EM_AARCH64}, "armeabi-v7a": {"arm", elf.EM_ARM},
	"x86_64": {"amd64", elf.EM_X86_64}, "x86": {"386", elf.EM_386},
}

func digest(data []byte) string { sum := sha256.Sum256(data); return hex.EncodeToString(sum[:]) }
func sourceHash() (string, error) {
	var files []string
	for _, dir := range []string{"cmd", "internal"} {
		if err := filepath.WalkDir(filepath.Join(corePath, dir), func(path string, d fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			// These inputs are intentionally local-only and may be absent from a
			// published checkout. They cannot change a compiled native client.
			name := strings.ToLower(d.Name())
			if d.IsDir() {
				switch name {
				case "testdata", ".cache", ".gocache", ".git", "__pycache__", "build":
					return filepath.SkipDir
				}
				return nil
			}
			if strings.HasSuffix(name, "_test.go") || strings.HasPrefix(name, "readme") ||
				strings.HasSuffix(name, ".log") || strings.HasSuffix(name, ".tmp") ||
				strings.HasSuffix(name, ".bak") || strings.HasSuffix(name, ".pyc") ||
				strings.HasSuffix(name, ".cache") || strings.HasSuffix(name, "~") {
				return nil
			}
			// PhantomDNS reads its user database from /etc at runtime, not from
			// the client executable. Never fingerprint a local user database.
			rel, err := filepath.Rel(corePath, path)
			if err != nil {
				return err
			}
			if filepath.ToSlash(rel) == "internal/users.json" {
				return nil
			}
			if d.Type()&os.ModeSymlink != 0 {
				return fmt.Errorf("symlinked core input: %s", path)
			}
			// Keep production source, platform resources, licenses and embedded
			// resolver data. Line ending normalization below keeps hashes portable.
			files = append(files, path)
			return nil
		}); err != nil {
			return "", err
		}
	}
	files = append(files, filepath.Join(corePath, "go.mod"), filepath.Join(corePath, "go.sum"))
	sort.Slice(files, func(i, j int) bool { return filepath.ToSlash(files[i]) < filepath.ToSlash(files[j]) })
	var tree bytes.Buffer
	for _, path := range files {
		data, err := os.ReadFile(path)
		if err != nil {
			return "", err
		}
		rel, err := filepath.Rel(corePath, path)
		if err != nil {
			return "", err
		}
		// Go source and bundled text assets are equivalent across LF/CRLF checkouts.
		fmt.Fprintf(&tree, "%s %s\n", filepath.ToSlash(rel), digest(bytes.ReplaceAll(data, []byte("\r\n"), []byte("\n"))))
	}
	return digest(tree.Bytes()), nil
}
func inspectBinaries(hash string) (map[string]string, error) {
	out := make(map[string]string)
	for abi, target := range targets {
		path := filepath.Join("app/src/main/jniLibs", abi, binaryName)
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}
		binary, err := elf.Open(path)
		if err != nil {
			return nil, err
		}
		if binary.Machine != target.machine {
			binary.Close()
			return nil, fmt.Errorf("wrong ABI in %s", path)
		}
		for _, p := range binary.Progs {
			if p.Type == elf.PT_LOAD && p.Align < 16384 {
				binary.Close()
				return nil, fmt.Errorf("%s lacks 16 KiB page alignment", path)
			}
		}
		binary.Close()
		version, err := exec.Command("go", "version", "-m", path).CombinedOutput()
		if err != nil {
			return nil, fmt.Errorf("inspect %s: %w", abi, err)
		}
		text := string(version)
		if !strings.Contains(text, "GOOS=android") || !strings.Contains(text, "GOARCH="+target.arch) || !bytes.Contains(data, []byte("source-"+hash)) {
			return nil, fmt.Errorf("stale %s %s core: missing source-%s build stamp; rebuild native binaries", coreName, abi, hash)
		}
		out[abi] = digest(data)
	}
	return out, nil
}
func run() error {
	if len(os.Args) == 3 {
		if os.Args[2] != "phantom" {
			return fmt.Errorf("unknown core %q", os.Args[2])
		}
		manifestPath = "app/phantomdns-native-build.json"
		corePath = "third_party/PhantomDns"
		coreName = "PhantomDNS"
		binaryName = "libphantomdns_client.so"
	}

	if len(os.Args) < 2 {
		return fmt.Errorf("usage: go run scripts/cottendns-build-manifest.go source|record|verify [phantom]")
	}
	hash, err := sourceHash()
	if err != nil {
		return err
	}
	if os.Args[1] == "source" {
		fmt.Println(hash)
		return nil
	}
	if os.Args[1] != "record" && os.Args[1] != "verify" {
		return fmt.Errorf("unknown action")
	}
	binaries, err := inspectBinaries(hash)
	if err != nil {
		return err
	}
	if os.Args[1] == "record" {
		data, err := json.MarshalIndent(receipt{1, hash, binaries}, "", "  ")
		if err != nil {
			return err
		}
		if err := os.WriteFile(manifestPath, append(data, '\n'), 0644); err != nil {
			return err
		}
		fmt.Println("Recorded four Android cores built from source-" + hash)
		return nil
	}
	data, err := os.ReadFile(manifestPath)
	if err != nil {
		return err
	}
	var expected receipt
	if err := json.Unmarshal(data, &expected); err != nil {
		return err
	}
	if expected.Version != 1 || expected.SourceSHA256 != hash || len(expected.Binaries) != len(targets) {
		return fmt.Errorf("native core source receipt is stale; rebuild %s", coreName)
	}
	for abi, actual := range binaries {
		if expected.Binaries[abi] != actual {
			return fmt.Errorf("native binary checksum mismatch for %s", abi)
		}
	}
	fmt.Printf("Verified source, checksums, Android ABIs and 16 KiB alignment for all four %s cores.\n", coreName)
	return nil
}
func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
