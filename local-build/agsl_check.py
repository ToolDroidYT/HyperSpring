import re,sys,skia
src=open(sys.argv[1]).read()
for m in re.finditer(r'static final String (\w+) =\s*(.*?);\n', src, re.S):
    name, body = m.group(1), m.group(2)
    body = '\n'.join(l for l in body.split('\n') if not l.strip().startswith('//'))
    body = re.sub(r'("\s*)//[^\n]*', r'\1', body)
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', body)
    if not parts: continue
    code = ''.join(parts).encode().decode('unicode_escape')
    if 'main(' not in code: continue
    try:
        eff = skia.RuntimeEffect.MakeForShader(code)
        print(sys.argv[1].split('/')[-1], name, 'OK')
        try: print('  uniforms:', [u.name for u in eff.uniforms()])
        except Exception as e: pass
    except Exception as e:
        print(sys.argv[1].split('/')[-1], name, 'ERROR:', e); sys.exit(1)
