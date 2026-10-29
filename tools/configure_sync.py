"""Configure the private build-time sync verifier without putting a password in shell history."""
import getpass
import hashlib
import os
from pathlib import Path

password = getpass.getpass('Choose the sync access password: ')
if len(password) < 12:
    raise SystemExit('Use at least 12 characters.')
if password != getpass.getpass('Repeat the password: '):
    raise SystemExit('Passwords do not match.')
path = Path(__file__).resolve().parent.parent / 'sync-access.properties'
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, 'w') as output:
    output.write('syncPasswordSha256=' + hashlib.sha256(password.encode()).hexdigest() + '\n')
print('Saved the private verifier. The password was not stored.')
