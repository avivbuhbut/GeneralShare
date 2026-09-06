from pathlib import Path

p = Path('shift-sync-phone/settings.gradle.kts')
s = p.read_text()
needle = 'mavenCentral()'
repo = 'maven { url = uri("https://jitpack.io") }'
if needle not in s:
    raise SystemExit('mavenCentral() not found')
# Put JitPack after every mavenCentral() so both pluginManagement and
# dependencyResolutionManagement are covered. This is intentionally idempotent
# on the freshly reconstructed source used by every CI build.
s = s.replace(needle, needle + '\n        ' + repo)
p.write_text(s)
print(p.read_text())
assert 'dependencyResolutionManagement' in s
assert s.count('jitpack.io') >= 1
print('JITPACK_REPOSITORY_FIX_OK')
