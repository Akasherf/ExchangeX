"""Safeguard tests: simulate Docker; actual PostgreSQL restore is exercised in CI."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class ReleaseScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.d = Path(self.temp.name)
        self.bin = self.d / 'bin'
        self.bin.mkdir()
        self.log = self.d / 'docker.log'
        executable = self.bin / 'docker'
        executable.write_text('''#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >> "$MOCK_DOCKER_LOG"
case "$*" in
  *pg_dump*) printf 'PGDMP\\000mockarchivebytes';;
  *pg_restore*) cat >/dev/null; ;;
  *psql*) cat >> "$MOCK_DOCKER_LOG"; if [[ "${MOCK_DB_EXISTS:-0}" == 1 ]]; then printf '1\\n'; fi ;;
esac
''')
        executable.chmod(0o755)
        self.env = os.environ.copy()
        self.env['PATH'] = str(self.bin) + os.pathsep + self.env['PATH']
        self.env['MOCK_DOCKER_LOG'] = str(self.log)

    def run_script(self, script, *args, env=None):
        return subprocess.run(['bash', str(ROOT/'scripts'/script), *map(str,args)],
            cwd=ROOT, env=env or self.env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_backup_is_private_and_checksum_matches(self):
        dest = self.d / 'backup'
        cp = self.run_script('backup-postgres.sh', dest)
        self.assertEqual(cp.returncode,0,cp.stderr)
        dumps = list(dest.glob('*.dump'))
        self.assertEqual(len(dumps),1)
        archive = dumps[0]
        self.assertEqual(archive.stat().st_mode & 0o777,0o600)
        manifest = Path(str(archive)+'.sha256')
        digest, name = manifest.read_text().split()
        self.assertEqual(name,archive.name)
        self.assertEqual(digest,hashlib.sha256(archive.read_bytes()).hexdigest())
        self.assertEqual(manifest.stat().st_mode & 0o777,0o600)

    def test_restore_rejects_unsafe_and_live_names_without_docker_call(self):
        archive = self.d / 'backup.dump'
        archive.write_bytes(b'example')
        for name in ['exchangex', 'postgres', 'exchangex_test', 'exchangex_restore_bad;DROP',
                     'exchangex_restore_../foo', 'exchangex_restore_AAAA']:
            with self.subTest(name=name):
                result=self.run_script('restore-postgres.sh',archive,name)
                self.assertEqual(result.returncode,2,result.stderr)
        self.assertFalse(self.log.exists())

    def test_restore_rejects_corrupt_manifest_before_database_creation(self):
        archive = self.d / 'backup.dump'
        archive.write_bytes(b'example')
        Path(str(archive)+'.sha256').write_text('0'*64+'  backup.dump\n')
        result=self.run_script('restore-postgres.sh',archive,'exchangex_restore_abcd')
        self.assertNotEqual(result.returncode,0)
        self.assertFalse(self.log.exists())

    def test_restore_aborts_when_target_exists(self):
        archive = self.d / 'backup.dump'
        archive.write_bytes(b'example')
        env = self.env.copy(); env['MOCK_DB_EXISTS']='1'; env['ALLOW_UNVERIFIED_BACKUP']='1'
        result=self.run_script('restore-postgres.sh',archive,'exchangex_restore_abcd',env=env)
        self.assertNotEqual(result.returncode,0,result.stdout)
        self.assertIn('already exists',result.stderr)
        self.assertNotIn('CREATE DATABASE',self.log.read_text())

    def test_restore_creates_only_disposable_database_and_never_cleans_existing(self):
        archive = self.d / 'backup.dump'
        archive.write_bytes(b'example')
        env = self.env.copy(); env['ALLOW_UNVERIFIED_BACKUP']='1'
        result=self.run_script('restore-postgres.sh',archive,'exchangex_restore_abcd',env=env)
        self.assertEqual(result.returncode,0,result.stdout+' '+result.stderr)
        calls=self.log.read_text()
        self.assertIn('CREATE DATABASE',calls)
        self.assertIn('exchangex_restore_abcd',calls)
        self.assertIn('--single-transaction',calls)
        self.assertNotIn('DROP DATABASE',calls)
        self.assertNotIn('--clean',calls)

    def test_readonly_audits_do_not_include_mutation_commands(self):
        for path in [ROOT/'scripts/sql/audit-legacy-orders.sql',
                     ROOT/'scripts/sql/verify-restored-schema.sql']:
            sql=path.read_text()
            self.assertIn('READ ONLY',sql)
            stripped = re.sub(r'--[^\n]*','',sql)
            self.assertNotRegex(stripped,r'(?i)\b(?:DELETE|UPDATE|INSERT|TRUNCATE|ALTER|DROP)\s+(?:FROM\s+)?(?:TABLE\s+)?(?:orders|trades|trading_accounts)\b')

if __name__ == '__main__': unittest.main()
