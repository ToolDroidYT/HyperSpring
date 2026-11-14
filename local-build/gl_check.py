"""Compila los shaders GLSL del genio (GlRenderer.java) con Mesa, sin pantalla.

Uso: python3 local-build/gl_check.py   (desde la raíz del repo)
Necesita: libEGL/libGLESv2 de Mesa (paquetes libegl1 y libgles2 en Linux).
"""
import ctypes as C, re, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'app/src/main/java/com/icehome/up/GlRenderer.java')

egl = C.CDLL('libEGL.so.1'); gl = C.CDLL('libGLESv2.so.2')
egl.eglGetProcAddress.restype = C.c_void_p
gpd = C.CFUNCTYPE(C.c_void_p, C.c_uint, C.c_void_p, C.c_void_p)(egl.eglGetProcAddress(b'eglGetPlatformDisplayEXT'))
egl.eglInitialize.argtypes = [C.c_void_p, C.c_void_p, C.c_void_p]
dpy = gpd(0x31DD, None, None)  # EGL_PLATFORM_SURFACELESS_MESA
assert egl.eglInitialize(C.c_void_p(dpy), None, None), 'EGL no arranca'
egl.eglBindAPI(0x30A0)
egl.eglCreateContext.restype = C.c_void_p; egl.eglCreateContext.argtypes = [C.c_void_p] * 4
ctx = egl.eglCreateContext(C.c_void_p(dpy), C.c_void_p(None), None, (C.c_int * 3)(0x3098, 2, 0x3038))
egl.eglMakeCurrent.argtypes = [C.c_void_p] * 4
assert egl.eglMakeCurrent(C.c_void_p(dpy), None, None, C.c_void_p(ctx)), 'sin contexto GLES'

src = open(SRC).read()
JS = {}
def jstr(name):
    if name in JS:
        return JS[name]
    m = re.search(r'static final String ' + name + r' =\s*(.*?);\n', src, re.S)
    out = ''
    for lit, ident in re.findall(r'"((?:[^"\\]|\\.)*)"|\b([A-Z_]{4,})\b', re.sub(r'//[^\n]*', '', m.group(1))):
        out += bytes(lit, 'utf8').decode('unicode_escape') if ident == '' else jstr(ident)
    JS[name] = out
    return out
for n in ('SPLASH_AT', 'VERTEX', 'FRAGMENT_BODY', 'FRAGMENT_WINDOW', 'FRAGMENT_SPLASH'):
    jstr(n)

def comp(kind, code, what):
    sh = gl.glCreateShader(kind); b = C.c_char_p(code.encode())
    gl.glShaderSource(sh, 1, C.byref(b), None); gl.glCompileShader(sh)
    ok = C.c_int(); gl.glGetShaderiv(sh, 0x8B81, C.byref(ok))
    log = C.create_string_buffer(4096); gl.glGetShaderInfoLog(sh, 4096, None, log)
    if not ok.value:
        print(what, 'ERROR:', log.value.decode()); sys.exit(1)
    return sh

for name, frag in (('ventana', 'FRAGMENT_WINDOW'), ('inicio', 'FRAGMENT_SPLASH')):
    p = gl.glCreateProgram()
    gl.glAttachShader(p, comp(0x8B31, JS['VERTEX'], 'VERTEX'))
    gl.glAttachShader(p, comp(0x8B30, JS[frag] + JS['FRAGMENT_BODY'], frag))
    gl.glLinkProgram(p)
    ok = C.c_int(); gl.glGetProgramiv(p, 0x8B82, C.byref(ok))
    print('genio,', name + ':', 'OK' if ok.value else 'ERROR')
    if not ok.value: sys.exit(1)
