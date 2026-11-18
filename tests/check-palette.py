#!/usr/bin/env python3
"""Fails if ThemeManager's marker colors differ from res/values/colors.xml."""
import re, sys
colors = dict(re.findall(r'<color name="(mb_\w+)">#([0-9A-Fa-f]{6,8})</color>', open('res/values/colors.xml').read()))
src = open('src/com/werismoln/multibooter/settings/ThemeManager.java').read()
pairs = {'M_BG':'mb_bg','M_SURFACE':'mb_surface','M_SURFACE_HIGH':'mb_surface_high','M_ACCENT':'mb_accent',
         'M_ACCENT_DEEP':'mb_accent_deep','M_ACCENT_SOFT':'mb_accent_soft','M_ON_ACCENT':'mb_on_accent',
         'M_TEXT':'mb_text','M_TEXT2':'mb_text2','M_MUTED':'mb_muted','M_DIVIDER':'mb_divider'}
bad = 0
for const, name in pairs.items():
    m = re.search(r'static final int %s = 0x([0-9A-Fa-f]{8});' % const, src)
    want = colors[name].upper(); want = want if len(want) == 8 else 'FF' + want
    if not m or m.group(1).upper() != want:
        bad += 1; print('MISMATCH', const, m.group(1) if m else None, 'vs', name, want)
print('palette markers match colors.xml' if not bad else 'FAILED'); sys.exit(1 if bad else 0)
