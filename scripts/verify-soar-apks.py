"""Require every packaged ABI to include its source-built Soar executable."""
from pathlib import Path
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
directory = Path(sys.argv[1])
apks = list(directory.glob('*.apk'))
assert apks, 'No APKs to verify'
for path in apks:
    with zipfile.ZipFile(path) as apk:
        native = [name for name in apk.namelist() if name.endswith('/libstormdns_client.so')]
        assert native, f'{path.name}: no legacy native engine'
        for legacy in native:
            abi = legacy.split('/')[1]
            data = apk.read(f'lib/{abi}/libsoar_client.so')
            assert data == (root / f'app/src/main/jniLibs/{abi}/libsoar_client.so').read_bytes(), abi
            assert data[:4] == b'\x7fELF'
        assert b'Apache' in apk.read('assets/soar-third-party-notices.txt')
    print(f'{path.name}: Soar present and source-built bytes match ({len(native)} ABIs)')
