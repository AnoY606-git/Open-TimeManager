#!/usr/bin/env python3
# Copyright (C) 2026 AnoY606-git and SplitBox contributors
# SPDX-License-Identifier: GPL-3.0-only
# На основе https://github.com/AnoY606-git/SplitBox/blob/main/scripts/bootstrap.py
"""Необязательная установка закреплённых JDK/SDK для Linux x86_64; Gradle загружает Wrapper."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / '.toolchain'
DOWNLOADS = TOOLS / 'downloads'
CONFIG = json.loads((ROOT / 'gradle/toolchain.json').read_text())


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def download(item):
    destination = DOWNLOADS / item['filename']
    if not destination.is_file() or digest(destination) != item['sha256']:
        temporary = destination.with_suffix(destination.suffix + '.part')
        try:
            subprocess.run(['curl', '-fL', '--retry', '3', '--connect-timeout', '30',
                            '--max-time', '900', '--silent', '--show-error', item['url'],
                            '-o', str(temporary)], check=True)
            if digest(temporary) != item['sha256']:
                raise RuntimeError('Не совпадает SHA-256: ' + item['filename'])
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
    print('Проверен файл: ' + item['filename'], flush=True)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accept-licenses', action='store_true', help='Принять лицензии Android SDK для разработчиков (для CI или автоматической установки)')
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine() not in ('x86_64', 'AMD64') or sys.version_info < (3, 12):
        raise SystemExit('Для этого скрипта нужны Linux x86_64 и Python 3.12+. На других системах используйте JDK 17, Android SDK и gradlew напрямую.')
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    wrapper = ROOT / 'gradle/wrapper/gradle-wrapper.jar'
    if digest(wrapper) != CONFIG['wrapperJarSha256']:
        raise SystemExit('Не совпадает SHA-256 Gradle Wrapper JAR.')
    java = TOOLS / 'jdk/bin/java'
    if not java.is_file():
        archive_path = download(CONFIG['jdk'])
        with tarfile.open(archive_path) as archive:
            folder = archive.getnames()[0].split('/')[0]
            archive.extractall(TOOLS, filter='data')
        (TOOLS / folder).rename(TOOLS / 'jdk')
    detected = (TOOLS / 'jdk/release').read_text()
    if 'JAVA_RUNTIME_VERSION="' + CONFIG['jdkVersion'] + '"' not in detected:
        raise SystemExit('Локальный JDK проекта отличается от gradle/toolchain.json; используйте соответствующий JDK или стандартную сборку через Gradle Wrapper.')
    sdk_tools = TOOLS / 'android-sdk/cmdline-tools' / CONFIG['commandLineToolsDirectory']
    if not (sdk_tools / 'lib/sdkmanager-classpath.jar').is_file():
        archive_path = download(CONFIG['androidCommandLineTools'])
        temporary = TOOLS / 'unpack-sdk'
        with zipfile.ZipFile(archive_path) as archive:
            archive.extractall(temporary)
        sdk_tools.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(temporary / 'cmdline-tools'), str(sdk_tools))
        shutil.rmtree(temporary)
        for path in (sdk_tools / 'bin').iterdir():
            path.chmod(0o755)
    sdk_revision = (sdk_tools / 'source.properties').read_text().splitlines()
    if 'Pkg.Revision=' + CONFIG['commandLineToolsVersion'] not in sdk_revision:
        raise SystemExit('Версия локальных Android command line tools отличается от gradle/toolchain.json.')
    sdk = TOOLS / 'android-sdk'
    # Вызываем Java напрямую: исходный shell-скрипт sdkmanager некорректно обрабатывает пробелы в путях.
    command = [str(java), '-Dcom.android.sdklib.toolsdir=' + str(sdk_tools),
               '-classpath', str(sdk_tools / 'lib/sdkmanager-classpath.jar'),
               'com.android.sdklib.tool.sdkmanager.SdkManagerCli', '--sdk_root=' + str(sdk)]
    environment = os.environ.copy()
    environment.setdefault('ANDROID_USER_HOME', str(TOOLS / 'android-user'))
    environment['JAVA_HOME'] = str(TOOLS / 'jdk')
    environment['ANDROID_HOME'] = str(sdk)
    if args.accept_licenses:
        with (DOWNLOADS / 'sdk-licenses.log').open('w') as output:
            subprocess.run([*command, '--licenses'], input='y\n' * 100, text=True, check=True, stdout=output, env=environment)
    else:
        subprocess.run([*command, '--licenses'], check=True, env=environment)
    subprocess.run([*command, *CONFIG['sdkPackages']], check=True, env=environment)
    local = ROOT / 'local.properties'
    if not local.exists():
        local.write_text('sdk.dir=' + str(sdk).replace('\\', '\\\\').replace(':', '\\:') + '\n')
    (TOOLS / 'installed-toolchain.json').write_text(json.dumps(CONFIG, indent=2) + '\n')
    print('Закреплённые локальные JDK/SDK готовы. Существующий local.properties сохранён. Для запуска Gradle Wrapper используйте scripts/with-toolchain.sh ./gradlew.')


if __name__ == '__main__':
    main()
