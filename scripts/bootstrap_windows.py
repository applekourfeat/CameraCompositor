"""Install a project-local Android build toolchain, without administrator access."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import os
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)

def fetch(name, url, checksum=None):
    archive = TOOLS / (name + '.zip')
    if not archive.exists():
        print('Downloading ' + name, flush=True)
        urllib.request.urlretrieve(url, archive)
    if checksum and hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
        raise RuntimeError('Checksum mismatch: ' + name)
    destination = TOOLS / name
    if not (destination / '.complete').exists():
        destination.mkdir(exist_ok=True)
        with zipfile.ZipFile(archive) as package:
            package.extractall(destination)
        (destination / '.complete').touch()
    return destination

packages = [
    ('jdk', 'https://aka.ms/download-jdk/microsoft-jdk-21-windows-x64.zip', None),
    ('gradle', 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip',
     urllib.request.urlopen('https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256').read().decode().strip()),
    ('android-cli', 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip',
     '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'),
]
with ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(lambda package: fetch(*package), packages))

jdk = next((TOOLS / 'jdk').glob('*/bin/java.exe')).parents[1]
sdk = TOOLS / 'android-sdk'
sdk.mkdir(exist_ok=True)
env = dict(os.environ, JAVA_HOME=str(jdk), GRADLE_USER_HOME=str(TOOLS / 'gradle-home'))
manager = TOOLS / 'android-cli/cmdline-tools/bin/sdkmanager.bat'
# Standard Android SDK license prompts. Run this script only after reviewing the SDK terms.
subprocess.run([str(manager), '--sdk_root=' + str(sdk), '--licenses'],
               input='y\n' * 100, text=True, env=env, check=True)
subprocess.run([str(manager), '--sdk_root=' + str(sdk), 'platform-tools',
                'platforms;android-36', 'build-tools;35.0.0'], env=env, check=True)
(ROOT / 'local.properties').write_text('sdk.dir=' + sdk.as_posix().replace(':', '\\:') + '\n', encoding='utf-8', newline='\n')
gradle = TOOLS / 'gradle/gradle-8.11.1/bin/gradle.bat'
subprocess.run([str(gradle), 'wrapper', '--gradle-version', '8.11.1', '--distribution-type', 'bin',
                '--gradle-distribution-sha256-sum', packages[1][2]],
               cwd=ROOT, env=env, check=True)
print('Toolchain ready. Run scripts/build.ps1', flush=True)
