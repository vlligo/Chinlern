"""Check tracked files (or staged content with --staged) for common credential leaks.

This is a guard against mistakes, not a comprehensive secret scanner.
"""
import re
import subprocess
import sys
from pathlib import PurePosixPath

staged = '--staged' in sys.argv
files = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')[:-1]
patterns = [
    rb'AIza[0-9A-Za-z_-]{35}',
    rb'gh[pousr]_[A-Za-z0-9]{20,}',
    rb'github_pat_[A-Za-z0-9_]{30,}',
    rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
    rb'eyJ[A-Za-z0-9_-]{12,}\.eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]+',
]
issues = []
for name in files:
    path = PurePosixPath(name)
    if (path.name in {'google-services.json', 'sync-access.properties', '.firebaserc', 'local.properties'}
            or path.suffix in {'.apk', '.aab', '.jks', '.keystore', '.log', '.pem', '.key'}
            or path.name.startswith('.env') and path.name != '.env.example'
            or any(part in {'build', 'release', '.firebase'} for part in path.parts)):
        issues.append(name + ': private file or generated artifact')
    data = subprocess.check_output(['git', 'show', ':' + name]) if staged else open(name, 'rb').read()
    if any(re.search(pattern, data) for pattern in patterns):
        issues.append(name + ': possible credential (value withheld)')
if issues:
    raise SystemExit('\n'.join(issues))
print(f'Checked {len(files)} files: no known credential patterns or private files found.')
