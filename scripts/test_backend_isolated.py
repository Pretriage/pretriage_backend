"""Run backend tests only against a fresh disposable database, never the app database."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--test', help='Optional comma-separated Maven test class patterns')
    args = parser.parse_args()
    if args.test and not re.fullmatch(r'[A-Za-z0-9_.*,$]+', args.test):
        parser.error('Invalid test class pattern')
    root = Path(__file__).resolve().parents[1]
    db_name = 'pretriage_test_' + uuid.uuid4().hex
    if not re.fullmatch(r'pretriage_test_[0-9a-f]{32}', db_name):
        raise RuntimeError('Unsafe test database name')
    subprocess.run(['docker', 'exec', 'postgre', 'createdb', '-U', 'myuser', db_name], check=True)
    log = root / 'target' / 'isolated-tests.log'
    log.parent.mkdir(exist_ok=True)
    try:
        options = ['-Dspring.docker.compose.enabled=false',
                   '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/' + db_name,
                   '-Dspring.jpa.hibernate.ddl-auto=create-drop', 'test']
        if args.test:
            options.insert(0, '-Dtest=' + args.test)
        if os.name == 'nt':
            command = subprocess.list2cmdline([str(root / 'mvnw.cmd'), *options])
        else:
            command = [str(root / 'mvnw'), *options]
        print('Running tests with Docker Compose disabled on disposable database ' + db_name, flush=True)
        with log.open('w', encoding='utf-8') as output:
            result = subprocess.run(command, cwd=root, shell=os.name == 'nt', stdout=output, stderr=subprocess.STDOUT)
        print('\n'.join(log.read_text(encoding='utf-8', errors='replace').splitlines()[-20:]))
        return result.returncode
    finally:
        # The validated random name was created by this invocation; no shared DB is eligible.
        subprocess.run(['docker', 'exec', 'postgre', 'dropdb', '-U', 'myuser', '--force', db_name], check=True)


if __name__ == '__main__':
    raise SystemExit(main())
