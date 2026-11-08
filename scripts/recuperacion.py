"""Herramienta operativa H8. Ejecutar mediante los wrappers Docker; nunca imprime secretos."""
import argparse
import contextlib
import datetime as dt
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess as sp
import tarfile
import tempfile
from concurrent.futures import ThreadPoolExecutor
from zoneinfo import ZoneInfo

os.umask(0o077)
TABLES = ('usuario', 'vehiculo', 'orden', 'presupuesto', 'renglon', 'flyway_schema_history')

def run(args, **kwargs):
    return sp.run(args, check=True, stdout=sp.PIPE, stderr=sp.PIPE, **kwargs).stdout

def mysql_command(db, root=False):
    if not re.fullmatch(r'[a-zA-Z0-9_.-]+', db):
        raise ValueError('Identificador de contenedor inválido')
    user, secret = ('root', 'db_root_password') if root else ('taller_backup', 'db_backup_password')
    shell = f'export MYSQL_PWD="$(cat /run/secrets/{secret})"; exec mysql --protocol=TCP -h127.0.0.1 -u{user} --batch --raw --skip-column-names --unbuffered taller'
    return ['docker', 'exec', '-i', db, 'sh', '-c', shell]

def sql(db, query, root=False):
    return run(mysql_command(db, root), input=query.encode()).decode().strip()

def fingerprints(db):
    # Solo se escriben dentro del archivo cifrado, nunca en el registro de ejecución.
    return {table: sql(db, f'CHECKSUM TABLE `{table}` EXTENDED;') for table in TABLES}

def sha(path):
    with open(path, 'rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def git(*args):
    return run(['git', '-c', 'safe.directory=/source', '-C', '/source', *args]).decode().strip()

def keygen(args):
    key = Path('/secrets/respaldo-age.key')
    if not key.exists():
        run(['age-keygen', '-o', str(key)])
    recipient = run(['age-keygen', '-y', str(key)])
    Path('/secrets/respaldo-age.pub').write_bytes(recipient)
    print('Identidad age preparada; se conserva si ya existía. No se muestran claves.')

@contextlib.contextmanager
def backup_lock(db):
    # Coordinación operativa, sin privilegios globales: actualizar.ps1 y respaldar.ps1
    # comparten además un bloqueo exclusivo de archivo en el host. No ejecutar DDL manual.
    p = sp.Popen(mysql_command(db), stdin=sp.PIPE, stdout=sp.PIPE, stderr=sp.PIPE)
    pool = ThreadPoolExecutor(max_workers=1)
    try:
        p.stdin.write(b"SELECT IF(GET_LOCK('taller_mantenimiento',15)=1,'LOCKED','BUSY');\n")
        p.stdin.flush()
        if pool.submit(p.stdout.readline).result(timeout=20).strip() != b'LOCKED':
            raise RuntimeError('No se pudo obtener el bloqueo de mantenimiento')
        yield
    finally:
        p.kill() if p.poll() is None else None  # Cerrar la conexión libera el bloqueo.
        p.wait(timeout=5)
        pool.shutdown(wait=True)

def verify_outer(directory):
    manifest = json.loads((directory / 'manifest.json').read_text())
    if set(manifest['files']) != {'database.tar.age', 'code.tar.age'}:
        raise ValueError('Contenido de respaldo no reconocido')
    for name, checksum in manifest['files'].items():
        if sha(directory / name) != checksum:
            raise ValueError('Checksum de respaldo incorrecto')
    return manifest

def decrypt(archive, output):
    run(['age', '-d', '-i', '/identity/respaldo-age.key', '-o', str(output), str(archive)])

def extract(archive, directory):
    with tarfile.open(archive) as tar:
        tar.extractall(directory, filter='data')

def inspect_archives(directory, work):
    verify_outer(directory)
    for label in ('database', 'code'):
        plain = work / (label + '.tar')
        decrypt(directory / (label + '.tar.age'), plain)
        folder = work / label
        folder.mkdir()
        extract(plain, folder)
        metadata = json.loads((folder / 'metadata.json').read_text())
        for name, checksum in metadata['files'].items():
            path = (folder / name).resolve()
            if not path.is_relative_to(folder.resolve()) or sha(path) != checksum:
                raise ValueError('Integridad interna incorrecta')
    with gzip.open(work / 'database/database.sql.gz') as f:
        while f.read(1024 * 1024):
            pass  # Valida también CRC y fin del gzip.
    verifier = work / 'verify-repository'
    run(['git', 'init', str(verifier)])
    run(['git', '-C', str(verifier), 'bundle', 'verify', str(work / 'code/source.bundle')])

def package(folder, destination):
    plain = folder.parent / (folder.name + '.tar')
    with tarfile.open(plain, 'w') as tar:
        for child in sorted(folder.iterdir()):
            tar.add(child, arcname=child.name)
    run(['age', '-R', '/identity/respaldo-age.pub', '-o', str(destination), str(plain)])

def promote(source, bucket, name):
    bucket.mkdir(parents=True, exist_ok=True)
    target = bucket / name
    if target.exists():
        verify_outer(target)
        return target
    pending = bucket / ('.pending-' + name)
    if pending.exists():
        raise RuntimeError('Existe una copia parcial; revisar antes de repetir')
    shutil.copytree(source, pending)
    verify_outer(pending)
    pending.rename(target)
    return target

def rotate(bucket, keep, pattern):
    if not bucket.exists():
        return
    entries = sorted(p for p in bucket.iterdir() if p.is_dir() and re.fullmatch(pattern, p.name))
    for path in entries[:-keep]:
        if path.resolve().parent != bucket.resolve():
            raise ValueError('Rotación fuera del depósito')
        shutil.rmtree(path)

def backup(args):
    out = Path('/backup')
    out.mkdir(parents=True, exist_ok=True)
    with open(out / '.operation.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if git('status', '--porcelain'):
            raise RuntimeError('Confirmar los cambios de código antes del respaldo')
        now = dt.datetime.now(dt.timezone.utc)
        stamp = now.strftime('%Y%m%dT%H%M%SZ')
        commit = git('rev-parse', 'HEAD')
        pending = out / ('.pending-' + stamp)
        pending.mkdir()
        with tempfile.TemporaryDirectory(dir='/work') as tmp:
            work = Path(tmp)
            dbdir, codedir = work / 'database', work / 'code'
            dbdir.mkdir(); codedir.mkdir()
            with backup_lock(args.db):
                before = fingerprints(args.db)
                dump = ['docker', 'exec', args.db, 'sh', '-c', 'export MYSQL_PWD="$(cat /run/secrets/db_backup_password)"; exec mysqldump -h127.0.0.1 -utaller_backup --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --hex-blob --triggers --events --skip-lock-tables taller']
                with open(dbdir / 'database.sql.gz', 'wb') as f:
                    with gzip.GzipFile(fileobj=f, mode='wb', mtime=0) as compressed:
                        p = sp.Popen(dump, stdout=sp.PIPE, stderr=sp.PIPE)
                        shutil.copyfileobj(p.stdout, compressed)
                        if p.wait() != 0:
                            raise RuntimeError('Falló el respaldo lógico')
                if before != fingerprints(args.db):
                    raise RuntimeError('Hubo cambios durante la medición; repetir en un intervalo estable')
            metadata = {'createdAt': now.isoformat(), 'commit': commit, 'fingerprints': before,
                        'schema': sql(args.db, 'SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history WHERE success=1;'),
                        'files': {'database.sql.gz': sha(dbdir / 'database.sql.gz')}}
            (dbdir / 'metadata.json').write_text(json.dumps(metadata))
            git('bundle', 'create', str(codedir / 'source.bundle'), '--all')
            run(['git', '-c', 'safe.directory=/source', '-C', '/source', 'archive', '--format=tar', '-o', str(codedir / 'source.tar'), 'HEAD'])
            image_ids = json.loads(run(['docker', 'inspect', '--format', '{{json .Config.Image}}', args.db]))
            code_metadata = {'commit': commit, 'databaseImage': image_ids,
                             'files': {n: sha(codedir / n) for n in ('source.bundle', 'source.tar')}}
            (codedir / 'metadata.json').write_text(json.dumps(code_metadata))
            package(dbdir, pending / 'database.tar.age')
            package(codedir, pending / 'code.tar.age')
            outer = {'createdAt': now.isoformat(), 'commit': commit, 'files': {n: sha(pending / n) for n in ('database.tar.age', 'code.tar.age')}}
            (pending / 'manifest.json').write_text(json.dumps(outer, indent=2))
            check = work / 'check'; check.mkdir()
            inspect_archives(pending, check)
        local = now.astimezone(ZoneInfo('America/Argentina/Buenos_Aires'))
        week, month = local.strftime('%G-W%V'), local.strftime('%Y-%m')
        final = promote(pending, out / 'daily', stamp)
        promote(final, out / 'weekly', week)
        if args.external:
            external = Path('/external')
            promote(final, external / 'daily', stamp)
            promote(final, external / 'weekly', week)
            promote(final, external / 'monthly', month)
            rotate(external / 'daily', 7, r'\d{8}T\d{6}Z')
            rotate(external / 'weekly', 4, r'\d{4}-W\d{2}')
            rotate(external / 'monthly', 3, r'\d{4}-\d{2}')
        rotate(out / 'daily', 7, r'\d{8}T\d{6}Z')
        rotate(out / 'weekly', 4, r'\d{4}-W\d{2}')
        shutil.rmtree(pending)
        print(json.dumps({'backup': stamp, 'commit': commit, 'verified': True, 'externalDirectoryCopied': args.external}))

def unpack(args):
    # Solo descifra en tmpfs /work. El código recuperado se puede copiar a /recovered.
    source = Path('/input')
    with tempfile.TemporaryDirectory(dir='/work') as tmp:
        work = Path(tmp)
        inspect_archives(source, work)
        metadata = json.loads((work / 'code/metadata.json').read_text())
        if args.action == 'code':
            target = Path('/recovered')
            if any(target.iterdir()):
                raise ValueError('El destino de código recuperado debe estar vacío')
            run(['git', 'clone', str(work / 'code/source.bundle'), str(target)])
            run(['git', '-c', 'safe.directory=' + str(target), '-C', str(target), 'checkout', '--detach', metadata['commit']])
            print(json.dumps({'codeRecovered': True, 'commit': metadata['commit']}))
            return
        # La BDD se importa solo en un contenedor de recuperación explícito, nunca en la principal.
        labels = json.loads(run(['docker', 'inspect', '--format', '{{json .Config.Labels}}', args.db]))
        if not labels.get('com.docker.compose.project', '').startswith('taller-recuperacion-'):
            raise ValueError('Solo se permite restaurar una BDD de taller-recuperacion-*')
        if sql(args.db, 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\'taller\';', True) != '0':
            raise ValueError('La BDD de recuperación debe estar vacía')
        with gzip.open(work / 'database/database.sql.gz') as f:
            p = sp.Popen(mysql_command(args.db, True), stdin=sp.PIPE, stdout=sp.PIPE, stderr=sp.PIPE)
            shutil.copyfileobj(f, p.stdin); p.stdin.close()
            if p.wait() != 0:
                raise RuntimeError('Falló la importación')
        original = json.loads((work / 'database/metadata.json').read_text())
        if fingerprints(args.db) != original['fingerprints']:
            raise ValueError('Las tablas recuperadas no coinciden con el respaldo')
        age = (dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(original['createdAt'])).total_seconds()
        print(json.dumps({'restored': True, 'commit': metadata['commit'], 'tablesVerified': len(TABLES), 'backupAgeSeconds': round(age, 3)}))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['keygen', 'backup', 'code', 'restore'])
    parser.add_argument('--db')
    parser.add_argument('--external', action='store_true')
    args = parser.parse_args()
    (unpack if args.action in ('code', 'restore') else globals()[args.action])(args)

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # El stderr de subprocesos puede contener datos; solo se informa el tipo y mensaje propio.
        message = str(error) if isinstance(error, (ValueError, RuntimeError)) else type(error).__name__
        print('Operación no completada: ' + message, flush=True)
        raise SystemExit(1)
