"""Query OSV for every locked runtime dependency. Uses no credentials."""
import json
from pathlib import Path
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError(f'Unexpected redirect to {newurl}')


coordinates = []
queries = []
for line in Path(__file__).with_name('gradle.lockfile').read_text().splitlines():
    if line.startswith('#') or '=' not in line:
        continue
    coordinate, configurations = line.split('=', 1)
    if 'runtimeClasspath' not in configurations.split(','):
        continue
    group, artifact, version = coordinate.split(':')
    coordinates.append(coordinate)
    queries.append({'package': {'ecosystem': 'Maven', 'name': group + ':' + artifact}, 'version': version})
request = urllib.request.Request(
    'https://api.osv.dev/v1/querybatch',
    json.dumps({'queries': queries}).encode(),
    {'Content-Type': 'application/json'},
)
opener = urllib.request.build_opener(NoRedirect())
with opener.open(request, timeout=30) as response:
    if response.status != 200:
        raise RuntimeError(f'Unexpected status {response.status}')
    payload = response.read(4 * 1024 * 1024 + 1)
    if len(payload) > 4 * 1024 * 1024:
        raise RuntimeError('Vulnerability response too large')
    results = json.loads(payload.decode('utf-8'))['results']
if len(results) != len(coordinates):
    raise RuntimeError('Incomplete vulnerability response')
found = False
for coordinate, result in zip(coordinates, results):
    vulnerabilities = [item['id'] for item in result.get('vulns', [])]
    found |= bool(vulnerabilities)
    print(coordinate + ': ' + (', '.join(vulnerabilities) or 'no reported entries'))
print('OSV results describe known entries, not a security certification.')
raise SystemExit(1 if found else 0)
