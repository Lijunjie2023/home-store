#!/usr/bin/env python3
"""Run Gradle with the cloud's writable tool/cache locations and trusted system CAs.
Normal Android Studio/local development can use ./gradlew directly instead.
"""
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlsplit

root = Path(__file__).resolve().parents[1]
base = Path('/workspace/shared/android')
sdk = Path(os.environ.get('ANDROID_HOME', str(base / 'sdk')))
jdk = Path(os.environ.get('JAVA_HOME', str(base / 'jdk-21')))
if not (sdk / 'platforms/android-35/android.jar').exists() or not (jdk / 'bin/javac').exists():
    sys.exit('Install JDK 17+ and Android SDK Platform 35 / Build Tools 35.0.0; set JAVA_HOME and ANDROID_HOME.')
os.environ['ANDROID_HOME'] = str(sdk)
os.environ['JAVA_HOME'] = str(jdk)
os.environ['ANDROID_USER_HOME'] = str(base / 'android-user')
os.environ.pop('ANDROID_SDK_HOME', None)
os.environ.setdefault('GRADLE_USER_HOME', str(base / 'gradle-home'))
(base / 'java-home/.android').mkdir(parents=True, exist_ok=True)
installed_gradle = base / 'gradle-8.11.1/bin/gradle'
command = [str(installed_gradle if installed_gradle.exists() else root / 'gradlew'),
           '--no-daemon', '--console=plain', '-Duser.home=' + str(base / 'java-home')]
# Use the platform's existing trust roots. Never disable TLS checks.
trust_store = Path('/etc/ssl/certs/java/cacerts')
if trust_store.is_file():
    command.append('-Djavax.net.ssl.trustStore=' + str(trust_store))
proxy = urlsplit(os.environ.get('HTTPS_PROXY', ''))
if proxy.hostname:
    command += ['-Dhttps.proxyHost=' + proxy.hostname,
                '-Dhttps.proxyPort=' + str(proxy.port or 80),
                '-Dhttp.proxyHost=' + proxy.hostname,
                '-Dhttp.proxyPort=' + str(proxy.port or 80),
                '-Dhttp.nonProxyHosts=localhost|127.*']
command += sys.argv[1:] or [':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug']
sys.exit(subprocess.call(command, cwd=root))
