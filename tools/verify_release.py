"""Run after ./gradlew build; stdlib only, also used by GitHub Actions."""
import json
import hashlib
import re
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
version = re.search(r"^version = '([^']+)'", (root / 'build.gradle').read_text(), re.M)[1]
jar = root / 'build/libs' / f'applied-intelligence-{version}-mc1.21.1-neoforge.jar'
for license_name, upstream_blob in {
    'LICENSE-T3CODE.txt': '55ee675bb1de9e580b69f3dd68684df2a4dffba7',
    'LICENSE-T3CODE-WOW.txt': 'c2d954d6b80366e53f4b78ef0747b42406d9e842',
    'LICENSE-DEEPSEEK-HARNESS.txt': 'c1f7a78e89e4e4dc7b86664c3b3c76eb5eee1785',
    'LICENSE-PI.txt': 'b0a8e9b81083294360c69b4ec45d3d39a2b28197',
    'LICENSE-OPENCODE.txt': '6439474beed8e0271df9862eff97ffd70ec2464c',
}.items():
    data = (root / license_name).read_bytes()
    git_blob = b'blob ' + str(len(data)).encode() + b'\0' + data
    assert hashlib.sha1(git_blob).hexdigest() == upstream_blob, 'Original MIT license text changed'

with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    assert 'dev/tgft/appliedintelligence/client/NativeMultilineField.class' in names, 'Steering editor missing'
    assert 'dev/tgft/appliedintelligence/client/SteeringDraft.class' in names, 'Steering draft state missing'
    assert 'META-INF/LICENSE' in names, 'License missing from JAR'
    assert 'META-INF/THIRD_PARTY_NOTICES.md' in names, 'Third-party notices missing'
    assert archive.read('META-INF/SECURITY.md') == (root / 'SECURITY.md').read_bytes(), 'Withdrawal notice missing or outdated'
    assert 'META-INF/LICENSE-GSON.txt' in names, 'Gson license missing'
    for license_name in ('LICENSE-T3CODE.txt', 'LICENSE-T3CODE-WOW.txt', 'LICENSE-DEEPSEEK-HARNESS.txt', 'LICENSE-PI.txt', 'LICENSE-OPENCODE.txt'):
        assert archive.read('META-INF/' + license_name) == (root / license_name).read_bytes(), 'Upstream license missing or modified'
    assert not any(n.startswith(('appeng/', 'org/lwjgl/')) or n.endswith(('.lua', '.tga', '.ts', '.js')) for n in names), 'Unexpected upstream runtime/assets bundled'
    for adapter in ('AgentBackend', 'AgentSession', 'PiSession', 'AcpSession', 'OpenCodeSession'):
        assert f'dev/tgft/appliedintelligence/local/{adapter}.class' in names, 'Agent adapter missing'
    assert b'Main-Class:' not in archive.read('META-INF/MANIFEST.MF'), 'Standalone entry point must be absent'
    assert not any(n.startswith(('dev/tgft/appliedintelligence/remote/', 'dev/tgft/appliedintelligence/launcher/', 'dev/tgft/appliedintelligence/prototype/', 'org/eclipse/jetty/', 'org/bouncycastle/', 'com/fasterxml/')) or n.endswith('agent-host.jar') or 'ConnectionSettingsScreen' in n for n in names), 'Remote capability included'
    assert 'dev/tgft/appliedintelligence/local/LocalAgentService.class' in names, 'Local world archive missing'
    for name in names:
        if name.startswith('dev/tgft/appliedintelligence/') and name.endswith('.class'):
            code = archive.read(name)
            assert not any(symbol in code for symbol in (b'/ai/v1', b'com/sun/net/httpserver', b'java/net/ServerSocket')), 'Remote listener/endpoint code included'
    protocol = archive.read('dev/tgft/appliedintelligence/Protocol.class')
    assert not any(action in protocol for action in (b'remote_pair', b'host_configure', b'host_pair_code')), 'Retired control action included'


    assert not any(('FakeCodex' in n or 'FakeAppServer' in n or 'FakeOtherAgent' in n) or n.endswith(('Test.class', '.py', '.sqlite')) or 'applied-intelligence-local.json' in n for n in names), 'Development files included'
    zh = json.loads(archive.read('assets/appliedintelligence/lang/zh_cn.json'))
    en = json.loads(archive.read('assets/appliedintelligence/lang/en_us.json'))
    assert zh.keys() == en.keys(), 'Translation keys differ'
    assert f'version="{version}"' in archive.read('META-INF/neoforge.mods.toml').decode(), 'Metadata version differs'
print(f'Verified {jar.name}: licenses, {len(zh)} translations, version and package contents')
